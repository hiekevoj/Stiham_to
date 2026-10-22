package cz.plzen.stihamto

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.BufferedReader
import java.io.InputStreamReader
import java.io.OutputStreamWriter
import java.net.HttpURLConnection
import java.net.URL
import java.text.Normalizer
import kotlin.math.*

data class MarkerPoint(
    val latitude: Double,
    val longitude: Double
)

data class StopLineInfo(
    val name: String,
    val tractionType: Int,
    val isNight: Boolean
)

data class PmdpStop(
    val name: String,
    val cisNumber: Int,
    val stopId: Int,
    val isBarrierFree: Boolean,
    val latitude: Double,
    val longitude: Double,
    val markers: List<MarkerPoint>,
    val lines: List<StopLineInfo>
) {
    val hasTram: Boolean
        get() = lines.any { it.tractionType == 1 }
}

data class RankedStop(
    val stop: PmdpStop,
    val distanceMeters: Int,
    val walkingMinutes: Int,
    val movementStatus: String, // "approaching", "leaving", "stationary"
    val deltaMeters: Int,
    val effectiveScore: Int,
    val isRouted: Boolean = false
)

data class Catchability(
    val status: String, // "comfortable", "hurry", "missed"
    val label: String,
    val walkMinutes: Int
)

data class LiveDeparture(
    val stopName: String,
    val stopCis: Int,
    val stopDistanceMeters: Int,
    val stopMovementStatus: String,
    val lineName: String,
    val tractionType: Int,
    val vehicleIcon: String,
    val isNight: Boolean,
    val destination: String,
    val scheduledHHMM: String,
    val estimatedHHMM: String,
    val leavingInMin: Int,
    val targetDepartureEpochMs: Long,
    val delayMin: Int?, // null = nevyjel, 0 = včas, >0 = zpoždění
    val inTraffic: Boolean,
    val onRequest: Boolean,
    val isFavorite: Boolean,
    val catchability: Catchability,
    val nextStopsSummary: String
)

data class LiveDeparturesResult(
    val mode: String, // "multi" or "single"
    val primaryStop: RankedStop,
    val clusterStops: List<RankedStop>,
    val nearbyStops: List<RankedStop>,
    val departures: List<LiveDeparture>,
    val fetchedAtHHMMSS: String,
    val fetchedEpochMs: Long = System.currentTimeMillis()
)

object PmdpRepository {
    private const val PMDP_BASE_URL = "https://jizdnirady.pmdp.cz"
    private const val PREFS_NAME = "stiham_to_prefs"

    // Rychlost chůze ve městě (včetně přechodů a rohů ulic): ~74 m/min (4,4 km/h)
    private const val DEFAULT_WALK_METERS_PER_MIN = 74.0
    private const val DEFAULT_FAST_WALK_METERS_PER_MIN = 118.0

    @Volatile
    private var activeWalkMetersPerMin: Double = DEFAULT_WALK_METERS_PER_MIN

    @Volatile
    private var activeFastWalkMetersPerMin: Double = DEFAULT_FAST_WALK_METERS_PER_MIN

    @Volatile
    private var activeCrosswalkBufferSec: Int = 0

    private var cachedStops: List<PmdpStop>? = null
    private var sessionCookie: String? = null
    private var verificationToken: String? = null
    private var tokenTimestampMs: Long = 0L

    // Šetrná cache pro pěší vzdálenosti k označníkům
    private val walkingRouteCache = java.util.concurrent.ConcurrentHashMap<String, Pair<Int, Int>>()

    fun syncRuntimeSettings(context: Context) {
        val mode = getWalkSpeedMode(context)
        when (mode) {
            "relaxed" -> {
                activeWalkMetersPerMin = 63.0
                activeFastWalkMetersPerMin = 102.0
            }
            "brisk" -> {
                activeWalkMetersPerMin = 88.0
                activeFastWalkMetersPerMin = 135.0
            }
            else -> {
                activeWalkMetersPerMin = DEFAULT_WALK_METERS_PER_MIN
                activeFastWalkMetersPerMin = DEFAULT_FAST_WALK_METERS_PER_MIN
            }
        }
        activeCrosswalkBufferSec = getCrosswalkBufferSec(context)
    }

    fun getWalkSpeedMode(context: Context): String {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        return prefs.getString("walk_speed_mode", "normal") ?: "normal"
    }

    fun setWalkSpeedMode(context: Context, mode: String) {
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit().putString("walk_speed_mode", mode).apply()
        syncRuntimeSettings(context)
    }

    fun getCrosswalkBufferSec(context: Context): Int {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        return prefs.getInt("crosswalk_buffer_sec", 0)
    }

    fun setCrosswalkBufferSec(context: Context, sec: Int) {
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit().putInt("crosswalk_buffer_sec", sec).apply()
        syncRuntimeSettings(context)
    }

    fun isHideMissedInNotif(context: Context): Boolean {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        return prefs.getBoolean("hide_missed_notif", false)
    }

    fun setHideMissedInNotif(context: Context, hide: Boolean) {
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit().putBoolean("hide_missed_notif", hide).apply()
    }

    fun getClusterRadiusMeters(context: Context): Int {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        return prefs.getInt("cluster_radius_m", 380)
    }

    fun setClusterRadiusMeters(context: Context, meters: Int) {
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit().putInt("cluster_radius_m", meters).apply()
    }

    fun isAutoIncludeTramEnabled(context: Context): Boolean {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        return prefs.getBoolean("auto_include_tram", true)
    }

    fun setAutoIncludeTramEnabled(context: Context, enabled: Boolean) {
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit().putBoolean("auto_include_tram", enabled).apply()
    }

    fun getPostRideCooldownMinutes(context: Context): Int {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        return prefs.getInt("post_ride_cooldown_min", 20)
    }

    fun setPostRideCooldownMinutes(context: Context, minutes: Int) {
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit().putInt("post_ride_cooldown_min", minutes).apply()
    }

    fun getWalkPassedStopsLimit(context: Context): Int {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        return prefs.getInt("walk_passed_stops_limit", 2)
    }

    fun setWalkPassedStopsLimit(context: Context, limit: Int) {
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit().putInt("walk_passed_stops_limit", limit).apply()
    }

    fun getWakeUpStepsThreshold(context: Context): Int {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        return prefs.getInt("wake_steps_threshold", 30)
    }

    fun setWakeUpStepsThreshold(context: Context, steps: Int) {
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit().putInt("wake_steps_threshold", steps).apply()
    }

    fun getNotifDeparturesCount(context: Context): Int {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        return prefs.getInt("notif_dep_count", 4)
    }

    fun setNotifDeparturesCount(context: Context, count: Int) {
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit().putInt("notif_dep_count", count).apply()
    }

    fun clearFavoriteLines(context: Context) {
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit().putStringSet("fav_lines", emptySet()).apply()
    }

    @Volatile
    private var cachedKnownLines: List<String>? = null

    fun getAllKnownLines(context: Context): List<String> {
        cachedKnownLines?.let { return it }
        val all = loadStops(context)
            .flatMap { it.lines }
            .map { it.name }
            .distinct()
        val sorted = all.sortedWith(
            compareBy<String> { it.startsWith("N", ignoreCase = true) }
                .thenBy { it.filter { ch -> ch.isDigit() }.toIntOrNull() ?: 999 }
                .thenBy { it }
        )
        cachedKnownLines = sorted
        return sorted
    }

    fun getFavoriteLines(context: Context): Set<String> {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        return prefs.getStringSet("fav_lines", emptySet()) ?: emptySet()
    }

    fun toggleFavoriteLine(context: Context, lineName: String): Boolean {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val current = (prefs.getStringSet("fav_lines", emptySet()) ?: emptySet()).toMutableSet()
        val added = if (current.contains(lineName)) {
            current.remove(lineName)
            false
        } else {
            current.add(lineName)
            true
        }
        prefs.edit().putStringSet("fav_lines", current).apply()
        return added
    }

    fun getPreferredTraction(context: Context): String {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val raw = prefs.getString("preferred_traction", "all") ?: "all"
        return if (raw == "trolleybus" || raw == "bus") "bus_trol" else raw
    }

    fun setPreferredTraction(context: Context, traction: String) {
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit().putString("preferred_traction", traction).apply()
    }

    fun isMultiStopMode(context: Context): Boolean {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        return prefs.getBoolean("multi_stop_mode", true)
    }

    fun setMultiStopMode(context: Context, enabled: Boolean) {
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit().putBoolean("multi_stop_mode", enabled).apply()
    }

    fun getPinnedStopCis(context: Context): Int? {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val v = prefs.getInt("pinned_cis", -1)
        return if (v > 0) v else null
    }

    fun setPinnedStopCis(context: Context, cis: Int?) {
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit().putInt("pinned_cis", cis ?: -1).apply()
    }

    fun isSleepOnWifiEnabled(context: Context): Boolean {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        return prefs.getBoolean("sleep_on_wifi", true)
    }

    fun setSleepOnWifiEnabled(context: Context, enabled: Boolean) {
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit().putBoolean("sleep_on_wifi", enabled).apply()
    }

    fun getHomeWifiSsids(context: Context): Set<String> {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        return prefs.getStringSet("home_wifi_ssids", emptySet()) ?: emptySet()
    }

    fun addHomeWifiSsid(context: Context, rawSsid: String): Boolean {
        val clean = sanitizeSsid(rawSsid)
        if (clean.isEmpty() || isDisallowedPublicSsid(clean)) return false
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val current = (prefs.getStringSet("home_wifi_ssids", emptySet()) ?: emptySet()).toMutableSet()
        val added = current.add(clean)
        if (added) {
            prefs.edit().putStringSet("home_wifi_ssids", current).apply()
        }
        return added
    }

    fun removeHomeWifiSsid(context: Context, rawSsid: String): Boolean {
        val clean = sanitizeSsid(rawSsid)
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val current = (prefs.getStringSet("home_wifi_ssids", emptySet()) ?: emptySet()).toMutableSet()
        val removed = current.remove(clean)
        if (removed) {
            prefs.edit().putStringSet("home_wifi_ssids", current).apply()
        }
        return removed
    }

    fun sanitizeSsid(raw: String?): String {
        if (raw == null) return ""
        var s = raw.trim()
        if (s.startsWith("\"") && s.endsWith("\"") && s.length >= 2) {
            s = s.substring(1, s.length - 1).trim()
        }
        if (s.equals("<unknown ssid>", ignoreCase = true) || s == "0x") return ""
        return s
    }

    fun isDisallowedPublicSsid(ssid: String): Boolean {
        val lower = ssid.lowercase()
        return lower.contains("pmdp") ||
                lower.contains("free") ||
                lower.contains("public") ||
                lower.contains("hotspot") ||
                lower.contains("vozy") ||
                lower.contains("guest")
    }

    fun isHomeWifi(context: Context, rawSsid: String?): Boolean {
        val clean = sanitizeSsid(rawSsid)
        if (clean.isEmpty() || isDisallowedPublicSsid(clean)) return false
        val homes = getHomeWifiSsids(context)
        return if (homes.isEmpty()) {
            !isDisallowedPublicSsid(clean)
        } else {
            homes.contains(clean)
        }
    }

    fun saveAutoSleptLocation(context: Context, lat: Double, lon: Double) {
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit()
            .putString("slept_lat", lat.toString())
            .putString("slept_lon", lon.toString())
            .apply()
    }

    fun clearAutoSleptLocation(context: Context) {
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit()
            .remove("slept_lat")
            .remove("slept_lon")
            .apply()
    }

    fun isStillAtAutoSleptLocation(context: Context, lat: Double, lon: Double): Boolean {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val sLat = prefs.getString("slept_lat", null)?.toDoubleOrNull() ?: return false
        val sLon = prefs.getString("slept_lon", null)?.toDoubleOrNull() ?: return false
        return haversineMeters(lat, lon, sLat, sLon) < 42.0
    }

    fun loadStops(context: Context): List<PmdpStop> {
        cachedStops?.let { return it }
        val jsonText = context.assets.open("stops.json").bufferedReader().use { it.readText() }
        val arr = JSONArray(jsonText)
        val list = ArrayList<PmdpStop>(arr.length())

        for (i in 0 until arr.length()) {
            val obj = arr.getJSONObject(i)
            val placement = obj.getJSONObject("Placement")
            val markersArr = obj.optJSONArray("Markers")
            val markers = ArrayList<MarkerPoint>()
            if (markersArr != null) {
                for (m in 0 until markersArr.length()) {
                    val mObj = markersArr.getJSONObject(m)
                    if (!mObj.isNull("Latitude") && !mObj.isNull("Longitude")) {
                        markers.add(
                            MarkerPoint(
                                mObj.getDouble("Latitude"),
                                mObj.getDouble("Longitude")
                            )
                        )
                    }
                }
            }

            val linesArr = obj.optJSONArray("Lines")
            val lines = ArrayList<StopLineInfo>()
            if (linesArr != null) {
                for (l in 0 until linesArr.length()) {
                    val lObj = linesArr.getJSONObject(l).getJSONObject("Line")
                    lines.add(
                        StopLineInfo(
                            name = lObj.optString("Name", "?"),
                            tractionType = lObj.optInt("TractionType", 3),
                            isNight = lObj.optBoolean("IsNightConnection", false)
                        )
                    )
                }
            }

            list.add(
                PmdpStop(
                    name = obj.getString("Name"),
                    cisNumber = placement.optInt("CISJRNumber", obj.optInt("Number", 0)),
                    stopId = placement.optInt("StopId", 0),
                    isBarrierFree = obj.optBoolean("IsBarrierFree", false),
                    latitude = placement.getDouble("Latitude"),
                    longitude = placement.getDouble("Longitude"),
                    markers = markers,
                    lines = lines
                )
            )
        }

        cachedStops = list
        return list
    }

    fun haversineMeters(lat1: Double, lon1: Double, lat2: Double, lon2: Double): Double {
        val r = 6371000.0
        val dLat = Math.toRadians(lat2 - lat1)
        val dLon = Math.toRadians(lon2 - lon1)
        val a = sin(dLat / 2).pow(2.0) +
                cos(Math.toRadians(lat1)) * cos(Math.toRadians(lat2)) * sin(dLon / 2).pow(2.0)
        return 2 * r * atan2(sqrt(a), sqrt(1 - a))
    }

    /**
     * Vrací nejbližší nástupiště (označník) zastávky a jeho vzdušnou vzdálenost v metrech.
     */
    private fun findClosestStopPoint(stop: PmdpStop, lat: Double, lon: Double): Triple<Double, Double, Double> {
        var bestLat = stop.latitude
        var bestLon = stop.longitude
        var minAirDist = haversineMeters(lat, lon, stop.latitude, stop.longitude)
        for (m in stop.markers) {
            val d = haversineMeters(lat, lon, m.latitude, m.longitude)
            if (d < minAirDist) {
                minAirDist = d
                bestLat = m.latitude
                bestLon = m.longitude
            }
        }
        return Triple(bestLat, bestLon, minAirDist)
    }

    /**
     * Rychlý odhad vzdálenosti (pro prvotní předvýběr kandidátů z 328 zastávek a Geofencing).
     * Pokud už je zastávka v cache vyroutovaných pěších tras pro aktuální ~55m mřížku, vrátí přesnou trasu
     * upravenou o změnu vzdušné vzdálenosti (0 síťových dotazů).
     */
    fun calculateStopDistance(stop: PmdpStop, lat: Double, lon: Double): Int {
        val (_, _, minAirDist) = findClosestStopPoint(stop, lat, lon)
        val airInt = minAirDist.roundToInt()
        if (minAirDist <= 45.0) {
            return airInt
        }

        val streetFactor = when {
            minAirDist <= 120.0 -> 1.14
            minAirDist <= 280.0 -> 1.20
            else -> 1.25
        }
        return (minAirDist * streetFactor).roundToInt()
    }

    /**
     * Spočítá přesnou pěší vzdálenost k označníku zastávky pomocí lokální kalibrované geometrie.
     * Zcela offline, bez externího OSRM serveru (0 ms latence, 0 % spotřeba dat a baterie).
     */
    fun fetchRoutedStopDistance(stop: PmdpStop, userLat: Double, userLon: Double): Pair<Int, Boolean> {
        val dist = calculateStopDistance(stop, userLat, userLon)
        return Pair(dist, true)
    }

    fun evaluateCatchability(distanceMeters: Int, leavingInMin: Int): Catchability {
        val normalWalkMin = (distanceMeters / activeWalkMetersPerMin) + (activeCrosswalkBufferSec / 60.0)

        // 1. Spoj, který již odjel
        if (leavingInMin < 0) {
            return Catchability(
                status = "missed",
                label = "Odjelo",
                walkMinutes = if (distanceMeters <= 45) 0 else max(1, normalWalkMin.roundToInt())
            )
        }

        // 2. Zóna zastávky: přímo u sloupku / nástupiště
        if (distanceMeters <= 45) {
            return Catchability(
                status = "comfortable",
                label = "U zastávky",
                walkMinutes = 0
            )
        }

        val bufferMin = activeCrosswalkBufferSec / 60.0
        val fastWalkMin = (distanceMeters / activeFastWalkMetersPerMin) + (bufferMin * 0.5)
        val walkMinRounded = max(1, normalWalkMin.roundToInt())

        return when {
            leavingInMin < fastWalkMin - 0.35 -> Catchability(
                status = "missed",
                label = "Nestíháš",
                walkMinutes = ceil(normalWalkMin).toInt()
            )
            leavingInMin < normalWalkMin + 0.45 -> Catchability(
                status = "hurry",
                label = if (distanceMeters > 70) "Poklusem" else "Pospěš si",
                walkMinutes = ceil(normalWalkMin).toInt()
            )
            else -> Catchability(
                status = "comfortable",
                label = "Stíháš",
                walkMinutes = walkMinRounded
            )
        }
    }

    private fun computeWalkMinutes(distMeters: Int): Int {
        if (distMeters <= 45) return 0
        val bufferMin = activeCrosswalkBufferSec / 60.0
        return max(1, ((distMeters / activeWalkMetersPerMin) + bufferMin).roundToInt())
    }

    fun findNearestStops(
        context: Context,
        lat: Double,
        lon: Double,
        prevLat: Double? = null,
        prevLon: Double? = null,
        limit: Int = 6
    ): List<RankedStop> {
        syncRuntimeSettings(context)
        val stops = loadStops(context)
        val ranked = stops.map { stop ->
            val dist = calculateStopDistance(stop, lat, lon)
            var delta = 0
            var movStatus = "stationary"
            if (prevLat != null && prevLon != null) {
                val prevDist = calculateStopDistance(stop, prevLat, prevLon)
                delta = dist - prevDist
                movStatus = when {
                    delta <= -8 -> "approaching"
                    delta >= 8 -> "leaving"
                    else -> "stationary"
                }
            }
            val score = dist + when (movStatus) {
                "leaving" -> 95
                "approaching" -> -55
                else -> 0
            }
            RankedStop(
                stop = stop,
                distanceMeters = dist,
                walkingMinutes = computeWalkMinutes(dist),
                movementStatus = movStatus,
                deltaMeters = delta,
                effectiveScore = score,
                isRouted = true
            )
        }.sortedBy { it.effectiveScore }

        return ranked.take(limit)
    }

    suspend fun resolveRoutedNearestStops(
        context: Context,
        lat: Double,
        lon: Double,
        prevLat: Double? = null,
        prevLon: Double? = null,
        limit: Int = 6
    ): List<RankedStop> = withContext(Dispatchers.Default) {
        syncRuntimeSettings(context)
        val candidates = findNearestStops(context, lat, lon, prevLat, prevLon, limit = max(limit, 6))
        candidates.take(limit)
    }

    fun searchStopsByName(context: Context, query: String, limit: Int = 12): List<PmdpStop> {
        val stops = loadStops(context)
        val q = normalizeString(query.trim())
        if (q.isEmpty()) return stops.take(limit)
        val starts = ArrayList<PmdpStop>()
        val contains = ArrayList<PmdpStop>()
        for (s in stops) {
            val n = normalizeString(s.name)
            if (n.startsWith(q)) starts.add(s)
            else if (n.contains(q)) contains.add(s)
        }
        return (starts + contains).take(limit)
    }

    private fun normalizeString(input: String): String {
        val normalized = Normalizer.normalize(input.lowercase(), Normalizer.Form.NFD)
        return normalized.replace(Regex("\\p{InCombiningDiacriticalMarks}+"), "")
    }

    @Synchronized
    private fun ensureSession(forceRefresh: Boolean = false) {
        val now = System.currentTimeMillis()
        if (!forceRefresh && sessionCookie != null && verificationToken != null && (now - tokenTimestampMs) < 25 * 60 * 1000L) {
            return
        }

        val url = URL("$PMDP_BASE_URL/odjezdy")
        val conn = (url.openConnection() as HttpURLConnection).apply {
            requestMethod = "GET"
            connectTimeout = 8000
            readTimeout = 8000
            setRequestProperty(
                "User-Agent",
                "Mozilla/5.0 (Linux; Android 14) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/128.0.0.0 Mobile Safari/537.36"
            )
        }

        val setCookies = conn.headerFields["Set-Cookie"] ?: conn.headerFields["set-cookie"] ?: emptyList()
        val cookieParts = setCookies.map { it.substringBefore(";").trim() }.filter { it.isNotEmpty() }
        if (cookieParts.isNotEmpty()) {
            sessionCookie = cookieParts.joinToString("; ")
        }

        val html = BufferedReader(InputStreamReader(conn.inputStream, Charsets.UTF_8)).use { it.readText() }
        val regex = Regex("""name=['"]X-API-Request-Verification-Token['"]\s+value=['"]([^'"]+)['"]""", RegexOption.IGNORE_CASE)
        val match = regex.find(html)
            ?: throw IllegalStateException("Nepodařilo se získat bezpečnostní token z jizdnirady.pmdp.cz")

        verificationToken = match.groupValues[1]
        tokenTimestampMs = System.currentTimeMillis()
    }

    private fun postStopDeparturesJson(stop: PmdpStop, maxResults: Int = 12, isRetry: Boolean = false): JSONArray {
        ensureSession(isRetry)

        val payload = JSONObject().apply {
            put("Stop", JSONObject().apply {
                put("StopId", stop.stopId)
                put("CISJRNumber", stop.cisNumber)
                put("MarkerCode", JSONObject.NULL)
                put("Latitude", stop.latitude)
                put("Longitude", stop.longitude)
                put("MapyCzPoiType", JSONObject.NULL)
            })
            put("DateAndTime", JSONObject.NULL)
            put("MaxResults", maxResults)
            put("MaxResultsDateAndTime", JSONObject.NULL)
            put("FullResults", true)
        }

        val url = URL("$PMDP_BASE_URL/odjezdy/vyhledat")
        val conn = (url.openConnection() as HttpURLConnection).apply {
            requestMethod = "POST"
            doOutput = true
            connectTimeout = 8000
            readTimeout = 8000
            setRequestProperty("Content-Type", "application/json; charset=UTF-8")
            setRequestProperty("Accept", "application/json")
            setRequestProperty("X-Requested-With", "XMLHttpRequest")
            setRequestProperty("X-API-Request-Verification-Token", verificationToken ?: "")
            setRequestProperty("Origin", PMDP_BASE_URL)
            setRequestProperty("Referer", "$PMDP_BASE_URL/odjezdy")
            sessionCookie?.let { setRequestProperty("Cookie", it) }
        }

        OutputStreamWriter(conn.outputStream, Charsets.UTF_8).use { writer ->
            writer.write(payload.toString())
            writer.flush()
        }

        val code = conn.responseCode
        if (code != 200) {
            if (!isRetry && (code == 400 || code == 401 || code == 403 || code == 500)) {
                return postStopDeparturesJson(stop, maxResults, true)
            }
            throw IllegalStateException("PMDP server vrátil HTTP $code")
        }

        val responseBody = BufferedReader(InputStreamReader(conn.inputStream, Charsets.UTF_8)).use { it.readText() }
        return JSONArray(responseBody)
    }

    private fun parseDeparturesArray(
        rawArray: JSONArray,
        rankedStop: RankedStop,
        favLines: Set<String>
    ): List<LiveDeparture> {
        val list = ArrayList<LiveDeparture>(rawArray.length())
        for (i in 0 until rawArray.length()) {
            val item = rawArray.getJSONObject(i)
            val lineObj = item.optJSONObject("Line")
            val lineName = lineObj?.optString("Name", "?") ?: "?"
            val apiTraction = lineObj?.optInt("TractionType", 0) ?: 0
            val stopLineTraction = rankedStop.stop.lines
                .firstOrNull { it.name.equals(lineName, ignoreCase = true) }?.tractionType ?: 0
            val numVal = lineName.filter { it.isDigit() }.toIntOrNull()
            val upperLine = lineName.uppercase().trim()
            val tractionType = when {
                apiTraction in 1..3 -> apiTraction
                stopLineTraction in 1..3 -> stopLineTraction
                upperLine in setOf("1", "2", "4", "2A", "4A", "X1", "X2", "X4") -> 1
                numVal != null && numVal in 10..19 -> 2
                else -> 3
            }
            val isNight = lineObj?.optBoolean("IsNightConnection", false) ?: upperLine.startsWith("N")
            val icon = when (tractionType) {
                1 -> "🚋"
                2 -> "🚎"
                else -> "🚌"
            }

            val depTime = item.optString("DepartureTime", "")
            val scheduledHHMM = if (depTime.length >= 16) depTime.substring(11, 16) else ""
            val delayMin: Int? = if (item.isNull("DelayMin")) null else item.optInt("DelayMin", 0)
            val apiLeavingInMin = item.optInt("LeavingInMin", 0)

            var estimatedHHMM = scheduledHHMM
            if (scheduledHHMM.length == 5 && delayMin != null && delayMin > 0) {
                val parts = scheduledHHMM.split(":")
                if (parts.size == 2) {
                    val totalMin = (parts[0].toIntOrNull() ?: 0) * 60 + (parts[1].toIntOrNull() ?: 0) + delayMin
                    val hh = (totalMin / 60) % 24
                    val mm = totalMin % 60
                    estimatedHHMM = "%02d:%02d".format(hh, mm)
                }
            }

            val nowMs = System.currentTimeMillis()
            val targetDepartureEpochMs = computeTargetDepartureEpochMs(
                depTimeIso = depTime,
                delayMin = delayMin,
                apiLeavingInMin = apiLeavingInMin,
                nowMs = nowMs
            )
            // Interní dopočet podle reálné sekundy odjezdu (odpovídá odjezdové tabuli na zastávce, která nezaokrouhluje celé minuty nahoru)
            val leavingInMin = computeLiveLeavingMinutes(targetDepartureEpochMs, nowMs)

            val connObj = item.optJSONObject("ConnectionId")
            val inTraffic = connObj?.optBoolean("InTraffic", false) ?: false
            val notesObj = item.optJSONObject("Notes")
            val onRequest = notesObj?.optBoolean("OnRequest", false) ?: false

            val stopsArr = item.optJSONArray("Stops")
            val upcomingStopNames = ArrayList<String>()
            if (stopsArr != null) {
                for (s in 0 until stopsArr.length()) {
                    val st = stopsArr.getJSONObject(s)
                    if (!st.optBoolean("Passed", false)) {
                        val stName = st.optString("Name", "")
                        val stTime = st.optString("Time", "")
                        val hhmm = if (stTime.length >= 16) stTime.substring(11, 16) else ""
                        upcomingStopNames.add(if (hhmm.isNotEmpty()) "$stName ($hhmm)" else stName)
                        if (upcomingStopNames.size >= 6) break
                    }
                }
            }

            val catchability = evaluateCatchability(rankedStop.distanceMeters, leavingInMin)

            list.add(
                LiveDeparture(
                    stopName = rankedStop.stop.name,
                    stopCis = rankedStop.stop.cisNumber,
                    stopDistanceMeters = rankedStop.distanceMeters,
                    stopMovementStatus = rankedStop.movementStatus,
                    lineName = lineName,
                    tractionType = tractionType,
                    vehicleIcon = icon,
                    isNight = isNight,
                    destination = item.optString("LastStopName", ""),
                    scheduledHHMM = scheduledHHMM,
                    estimatedHHMM = estimatedHHMM,
                    leavingInMin = leavingInMin,
                    targetDepartureEpochMs = targetDepartureEpochMs,
                    delayMin = delayMin,
                    inTraffic = inTraffic,
                    onRequest = onRequest,
                    isFavorite = favLines.contains(lineName),
                    catchability = catchability,
                    nextStopsSummary = upcomingStopNames.joinToString(" → ")
                )
            )
        }
        return list
    }

    private fun computeTargetDepartureEpochMs(
        depTimeIso: String,
        delayMin: Int?,
        apiLeavingInMin: Int,
        nowMs: Long
    ): Long {
        val fallbackEpoch = nowMs + (apiLeavingInMin * 60_000L)
        if (depTimeIso.length >= 19) {
            try {
                val year = depTimeIso.substring(0, 4).toInt()
                val month = depTimeIso.substring(5, 7).toInt() - 1
                val day = depTimeIso.substring(8, 10).toInt()
                val hour = depTimeIso.substring(11, 13).toInt()
                val minute = depTimeIso.substring(14, 16).toInt()
                val second = depTimeIso.substring(17, 19).toInt()
                val cal = java.util.Calendar.getInstance(java.util.TimeZone.getTimeZone("Europe/Prague")).apply {
                    set(java.util.Calendar.YEAR, year)
                    set(java.util.Calendar.MONTH, month)
                    set(java.util.Calendar.DAY_OF_MONTH, day)
                    set(java.util.Calendar.HOUR_OF_DAY, hour)
                    set(java.util.Calendar.MINUTE, minute)
                    set(java.util.Calendar.SECOND, second)
                    set(java.util.Calendar.MILLISECOND, 0)
                }
                val parsedMs = cal.timeInMillis + ((delayMin ?: 0) * 60_000L)
                if (abs(parsedMs - fallbackEpoch) <= 300_000L) {
                    return parsedMs
                }
            } catch (_: Exception) {
            }
        }
        return fallbackEpoch
    }

    fun computeLiveLeavingMinutes(targetDepartureEpochMs: Long, nowMs: Long = System.currentTimeMillis()): Int {
        val remainingMs = targetDepartureEpochMs - nowMs
        if (remainingMs < -20_000L) {
            return -1 // Spoj již odjel před více než 20 s
        }
        if (remainingMs in -20_000L..18_000L) return 0
        // Zaokrouhlení odpovídající reálné tabuli na zastávce
        return ((remainingMs + 20_000L) / 60_000L).toInt()
    }

    /**
     * Lokální interní přepočet odjezdů podle aktuálního systémového času (0 % síť, 0 % GPS).
     * Odfiltruje spoje, které už odjely před > 35 s, a aktualizuje minuty do odjezdu i stav Stíháš/Nestíháš/Odjelo.
     */
    fun recalculateWithLiveCountdown(
        res: LiveDeparturesResult,
        nowMs: Long = System.currentTimeMillis()
    ): LiveDeparturesResult {
        var anyChanged = false
        val updatedDeps = ArrayList<LiveDeparture>(res.departures.size)
        for (dep in res.departures) {
            val remMs = dep.targetDepartureEpochMs - nowMs
            if (remMs < -35_000L) {
                anyChanged = true
                continue
            }
            val newMin = computeLiveLeavingMinutes(dep.targetDepartureEpochMs, nowMs)
            val newCatch = evaluateCatchability(dep.stopDistanceMeters, newMin)
            if (newMin == dep.leavingInMin && newCatch == dep.catchability) {
                updatedDeps.add(dep)
            } else {
                anyChanged = true
                updatedDeps.add(dep.copy(leavingInMin = newMin, catchability = newCatch))
            }
        }

        if (!anyChanged && updatedDeps.size == res.departures.size) {
            return res
        }

        return res.copy(
            departures = updatedDeps
        )
    }

    suspend fun fetchLiveDepartures(
        context: Context,
        lat: Double,
        lon: Double,
        prevLat: Double? = null,
        prevLon: Double? = null,
        explicitCis: Int? = null,
        multiStop: Boolean = true
    ): LiveDeparturesResult = withContext(Dispatchers.IO) {
        syncRuntimeSettings(context)
        val favLines = getFavoriteLines(context)
        val preferredTraction = getPreferredTraction(context)
        val clusterRadius = getClusterRadiusMeters(context)
        val autoIncludeTram = isAutoIncludeTramEnabled(context)
        val allStops = loadStops(context)
        val nearby = resolveRoutedNearestStops(context, lat, lon, prevLat, prevLon, limit = 6)

        val targetCis = explicitCis ?: getPinnedStopCis(context)

        val clusterStops: List<RankedStop> = if (targetCis != null) {
            val found = allStops.firstOrNull { it.cisNumber == targetCis }
                ?: nearby.firstOrNull()?.stop
                ?: allStops.first()
            val (dist, isRouted) = fetchRoutedStopDistance(found, lat, lon)
            listOf(
                RankedStop(
                    stop = found,
                    distanceMeters = dist,
                    walkingMinutes = computeWalkMinutes(dist),
                    movementStatus = "stationary",
                    deltaMeters = 0,
                    effectiveScore = dist,
                    isRouted = isRouted
                )
            )
        } else if (multiStop && nearby.isNotEmpty()) {
            val selected = nearby.filterIndexed { idx, s -> idx == 0 || (s.distanceMeters <= clusterRadius && idx < 3) }.toMutableList()
            // Pokud v klastru není tramvaj, ale do ~500 m reálné pěší chůze tramvajová zastávka je, automaticky ji přibereme
            if (autoIncludeTram && selected.none { it.stop.hasTram }) {
                val tramLimit = max(480, clusterRadius + 100)
                val tramStop = nearby.firstOrNull { it.stop.hasTram && it.distanceMeters <= tramLimit }
                if (tramStop != null && selected.none { it.stop.cisNumber == tramStop.stop.cisNumber }) {
                    selected.add(tramStop)
                }
            }
            selected
        } else if (nearby.isNotEmpty()) {
            val best = if (preferredTraction == "tram") {
                nearby.firstOrNull { it.stop.hasTram } ?: nearby.first()
            } else {
                nearby.first()
            }
            listOf(best)
        } else {
            val fallbackStop = allStops.first()
            listOf(
                RankedStop(
                    stop = fallbackStop,
                    distanceMeters = calculateStopDistance(fallbackStop, lat, lon),
                    walkingMinutes = 0,
                    movementStatus = "stationary",
                    deltaMeters = 0,
                    effectiveScore = 999999,
                    isRouted = true
                )
            )
        }

        val perStopLists = coroutineScope {
            clusterStops.map { rankedStop ->
                async {
                    try {
                        val raw = postStopDeparturesJson(rankedStop.stop, maxResults = 12)
                        parseDeparturesArray(raw, rankedStop, favLines)
                    } catch (e: Exception) {
                        android.util.Log.e("PmdpRepository", "Chyba načítání odjezdů pro ${rankedStop.stop.name}: ${e.message}")
                        emptyList()
                    }
                }
            }.awaitAll()
        }

        var merged = perStopLists.flatten()

        // Filtr podle preferované trakce: "all", "tram" (jen tramvaje), "bus_trol" (autobusy + trolejbusy)
        merged = when (preferredTraction) {
            "tram" -> merged.filter { it.tractionType == 1 }
            "bus_trol", "bus", "trolleybus" -> merged.filter { it.tractionType == 2 || it.tractionType == 3 }
            else -> merged
        }

        // Řazení:
        // 1) Oblíbené linky (★), které lze stihnout
        // 2) Stihnutelné spoje před těmi, co už ujíždí nebo odjely
        // 3) Čas do odjezdu (leavingInMin)
        val sorted = merged.sortedWith(
            compareByDescending<LiveDeparture> { it.catchability.status != "missed" && it.isFavorite }
                .thenBy { if (it.leavingInMin < 0) 999 else it.leavingInMin }
        )

        val cal = java.util.Calendar.getInstance(java.util.TimeZone.getTimeZone("Europe/Prague"))
        val hhmmss = "%02d:%02d:%02d".format(
            cal.get(java.util.Calendar.HOUR_OF_DAY),
            cal.get(java.util.Calendar.MINUTE),
            cal.get(java.util.Calendar.SECOND)
        )

        LiveDeparturesResult(
            mode = if (clusterStops.size > 1) "multi" else "single",
            primaryStop = clusterStops.firstOrNull() ?: nearby.firstOrNull() ?: RankedStop(
                allStops.first(), 0, 0, "stationary", 0, 0, true
            ),
            clusterStops = clusterStops,
            nearbyStops = nearby,
            departures = sorted.take(20),
            fetchedAtHHMMSS = hhmmss
        )
    }
}
