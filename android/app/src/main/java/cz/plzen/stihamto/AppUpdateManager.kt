package cz.plzen.stihamto

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.provider.Settings
import android.util.Base64
import androidx.core.content.FileProvider
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.FileOutputStream
import java.net.HttpURLConnection
import java.net.URL

data class UpdateCheckResult(
    val isUpdateAvailable: Boolean,
    val currentVersionCode: Int,
    val currentVersionName: String,
    val latestVersionCode: Int,
    val latestVersionName: String,
    val releaseNotes: String,
    val apkUrl: String
)

object AppUpdateManager {

    private const val GITHUB_REPO = "hiekevoj/Stiham_to"

    // 1. GitHub Contents API (okamžitá odezva bez 5min CDN zpoždění raw.githubusercontent.com)
    private const val GITHUB_CONTENTS_API_URL =
        "https://api.github.com/repos/$GITHUB_REPO/contents/version.json?ref=main"

    // 2. GitHub Releases API (pokud je vytvořen plnohodnotný GitHub Release)
    private const val GITHUB_RELEASES_API_URL =
        "https://api.github.com/repos/$GITHUB_REPO/releases/latest"

    // 3. GitHub Tags API (pokud je pushnutý jen git tag, např. v0.1.3)
    private const val GITHUB_TAGS_API_URL =
        "https://api.github.com/repos/$GITHUB_REPO/tags"

    // 4. Záložní přímý raw odkaz
    private const val GITHUB_VERSION_JSON_URL =
        "https://raw.githubusercontent.com/$GITHUB_REPO/main/version.json"

    private const val FALLBACK_DIRECT_APK_URL =
        "https://github.com/$GITHUB_REPO/raw/main/StihamTo-Plzen.apk"

    /**
     * Sémantické porovnání verzí (SemVer).
     * Vrací kladné číslo pokud v1 > v2, záporné pokud v1 < v2, 0 pokud v1 == v2.
     */
    fun compareVersions(v1: String, v2: String): Int {
        val clean1 = v1.trim().removePrefix("v").removePrefix("V").substringBefore("-")
        val clean2 = v2.trim().removePrefix("v").removePrefix("V").substringBefore("-")

        val parts1 = clean1.split(".").mapNotNull { it.trim().toIntOrNull() }
        val parts2 = clean2.split(".").mapNotNull { it.trim().toIntOrNull() }

        val maxLen = maxOf(parts1.size, parts2.size)
        for (i in 0 until maxLen) {
            val num1 = parts1.getOrElse(i) { 0 }
            val num2 = parts2.getOrElse(i) { 0 }
            if (num1 != num2) {
                return num1.compareTo(num2)
            }
        }
        return 0
    }

    fun isNewerVersion(remoteVersion: String, currentVersion: String): Boolean {
        return compareVersions(remoteVersion, currentVersion) > 0
    }

    /**
     * Zkontroluje na GitHubu existenci novější verze aplikace ze 4 nezávislých zdrojů:
     * 1. GitHub Contents API pro version.json (obchází 5min Fastly CDN cache na raw.githubusercontent.com!)
     * 2. GitHub Releases API (/releases/latest)
     * 3. GitHub Tags API (/tags – zachytí i prosté git tagy bez vytvořeného Release)
     * 4. Přímý raw version.json jako poslední záloha
     */
    suspend fun checkForUpdates(context: Context): UpdateCheckResult = withContext(Dispatchers.IO) {
        val pm = context.packageManager
        val pInfo = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            pm.getPackageInfo(context.packageName, PackageManager.PackageInfoFlags.of(0))
        } else {
            @Suppress("DEPRECATION")
            pm.getPackageInfo(context.packageName, 0)
        }

        val currentCode = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            pInfo.longVersionCode.toInt()
        } else {
            @Suppress("DEPRECATION")
            pInfo.versionCode
        }
        val currentName = pInfo.versionName ?: "0.1.4"

        var latestFoundCode = currentCode
        var latestFoundName = currentName
        var releaseNotes = ""
        var apkDownloadUrl = FALLBACK_DIRECT_APK_URL
        var hasNewer = false
        var anySourceSucceeded = false
        var lastError: Exception? = null
        val httpStatusCodes = mutableListOf<Int>()

        fun applyCandidateVersion(
            candCode: Int,
            candName: String,
            candNotes: String,
            candApkUrl: String?
        ) {
            val cleanCandName = candName.trim().removePrefix("v").removePrefix("V")
            if (cleanCandName.isEmpty()) return
            val isCodeNewer = candCode > currentCode
            val isNameNewer = isNewerVersion(cleanCandName, currentName)

            if (isCodeNewer || isNameNewer) {
                if (!hasNewer || candCode > latestFoundCode || isNewerVersion(cleanCandName, latestFoundName)) {
                    hasNewer = true
                    latestFoundCode = maxOf(candCode, currentCode + 1)
                    latestFoundName = cleanCandName
                    if (candNotes.isNotEmpty()) {
                        releaseNotes = candNotes
                    }
                    if (!candApkUrl.isNullOrEmpty() && !candApkUrl.contains("/public/")) {
                        apkDownloadUrl = candApkUrl
                    }
                }
            }
        }

        // 1. PRIMÁRNÍ ZDROJ: GitHub Contents API (okamžitě vrací čerstvý version.json přímo z Gitu bez CDN cache!)
        try {
            val conn = (URL(GITHUB_CONTENTS_API_URL).openConnection() as HttpURLConnection).apply {
                useCaches = false
                connectTimeout = 8000
                readTimeout = 8000
                requestMethod = "GET"
                setRequestProperty("User-Agent", "StihamTo-Plzen-App")
                setRequestProperty("Accept", "application/vnd.github.v3+json")
                setRequestProperty("Cache-Control", "no-cache, no-store, max-age=0")
            }

            val code = conn.responseCode
            httpStatusCodes.add(code)
            if (code == 200) {
                val body = conn.inputStream.bufferedReader().use { it.readText() }
                val metaObj = JSONObject(body)
                val base64Content = metaObj.optString("content", "")
                if (base64Content.isNotEmpty()) {
                    val decodedJson = String(Base64.decode(base64Content, Base64.DEFAULT), Charsets.UTF_8)
                    val verObj = JSONObject(decodedJson)
                    anySourceSucceeded = true
                    applyCandidateVersion(
                        candCode = verObj.optInt("versionCode", currentCode),
                        candName = verObj.optString("versionName", currentName),
                        candNotes = verObj.optString("releaseNotes", "").trim(),
                        candApkUrl = verObj.optString("apkUrl", FALLBACK_DIRECT_APK_URL).trim()
                    )
                }
            }
            conn.disconnect()
        } catch (e: Exception) {
            lastError = e
            android.util.Log.w("AppUpdateManager", "GitHub Contents API dotaz selhal: ${e.message}")
        }

        // 2. ZDROJ: GitHub Releases API (pokud existuje oficiální Release s přiloženým APK assetem)
        try {
            val conn = (URL(GITHUB_RELEASES_API_URL).openConnection() as HttpURLConnection).apply {
                useCaches = false
                connectTimeout = 8000
                readTimeout = 8000
                requestMethod = "GET"
                setRequestProperty("User-Agent", "StihamTo-Plzen-App")
                setRequestProperty("Accept", "application/vnd.github.v3+json")
            }

            val code = conn.responseCode
            httpStatusCodes.add(code)
            if (code == 200) {
                val jsonStr = conn.inputStream.bufferedReader().use { it.readText() }
                val obj = JSONObject(jsonStr)
                val rawTag = obj.optString("tag_name", "").trim()
                val notes = obj.optString("body", "").trim()

                var assetApkUrl: String? = null
                val assets = obj.optJSONArray("assets")
                if (assets != null) {
                    for (i in 0 until assets.length()) {
                        val asset = assets.getJSONObject(i)
                        val name = asset.optString("name", "")
                        if (name.endsWith(".apk", ignoreCase = true)) {
                            val rawUrl = asset.optString("browser_download_url", "")
                            if (rawUrl.isNotEmpty()) {
                                assetApkUrl = rawUrl
                                break
                            }
                        }
                    }
                }
                anySourceSucceeded = true
                applyCandidateVersion(
                    candCode = currentCode,
                    candName = rawTag,
                    candNotes = notes,
                    candApkUrl = assetApkUrl
                )
            }
            conn.disconnect()
        } catch (e: Exception) {
            lastError = e
            android.util.Log.w("AppUpdateManager", "GitHub Releases API dotaz selhal: ${e.message}")
        }

        // 3. ZDROJ: GitHub Tags API (zachytí git tagy v0.1.x i když nebyl ručně publikován Release!)
        try {
            val conn = (URL(GITHUB_TAGS_API_URL).openConnection() as HttpURLConnection).apply {
                useCaches = false
                connectTimeout = 8000
                readTimeout = 8000
                requestMethod = "GET"
                setRequestProperty("User-Agent", "StihamTo-Plzen-App")
                setRequestProperty("Accept", "application/vnd.github.v3+json")
            }

            val code = conn.responseCode
            httpStatusCodes.add(code)
            if (code == 200) {
                val jsonStr = conn.inputStream.bufferedReader().use { it.readText() }
                val arr = JSONArray(jsonStr)
                if (arr.length() > 0) {
                    anySourceSucceeded = true
                    for (i in 0 until minOf(5, arr.length())) {
                        val tagObj = arr.getJSONObject(i)
                        val tagName = tagObj.optString("name", "").trim()
                        applyCandidateVersion(
                            candCode = currentCode,
                            candName = tagName,
                            candNotes = "",
                            candApkUrl = null
                        )
                    }
                }
            }
            conn.disconnect()
        } catch (e: Exception) {
            lastError = e
            android.util.Log.w("AppUpdateManager", "GitHub Tags API dotaz selhal: ${e.message}")
        }

        // 4. ZÁLOŽNÍ ZDROJ: Přímý raw.githubusercontent.com/version.json
        try {
            val cacheBusterUrl = "$GITHUB_VERSION_JSON_URL?t=${System.currentTimeMillis()}"
            val conn = (URL(cacheBusterUrl).openConnection() as HttpURLConnection).apply {
                useCaches = false
                connectTimeout = 8000
                readTimeout = 8000
                requestMethod = "GET"
                setRequestProperty("User-Agent", "StihamTo-Plzen-App")
                setRequestProperty("Cache-Control", "no-cache, no-store, max-age=0")
                setRequestProperty("Pragma", "no-cache")
            }

            val code = conn.responseCode
            httpStatusCodes.add(code)
            if (code == 200) {
                val jsonStr = conn.inputStream.bufferedReader().use { it.readText() }
                val obj = JSONObject(jsonStr)
                anySourceSucceeded = true
                applyCandidateVersion(
                    candCode = obj.optInt("versionCode", currentCode),
                    candName = obj.optString("versionName", currentName),
                    candNotes = obj.optString("releaseNotes", "").trim(),
                    candApkUrl = obj.optString("apkUrl", FALLBACK_DIRECT_APK_URL).trim()
                )
            }
            conn.disconnect()
        } catch (e: Exception) {
            lastError = e
            android.util.Log.w("AppUpdateManager", "raw version.json dotaz selhal: ${e.message}")
        }

        // Pokud žádný ze 4 zdrojů neodpověděl 200 OK, sestavíme přesnou diagnostickou zprávu,
        // aby bylo na první pohled jasné, zda je problém v síti telefonu nebo v nastavení GitHubu.
        if (!anySourceSucceeded && !hasNewer) {
            val detailedReason = when {
                httpStatusCodes.isNotEmpty() && httpStatusCodes.all { it == 404 } ->
                    "GitHub vrátil HTTP 404 – repozitář $GITHUB_REPO je nastaven jako soukromý (Private) nebo neexistuje. V nastavení GitHubu (Settings → Change visibility) ho přepněte na Public."
                httpStatusCodes.any { it == 403 || it == 429 } ->
                    "GitHub dočasně omezil počet dotazů z této IP adresy (HTTP ${httpStatusCodes.first { it == 403 || it == 429 }} Rate Limit). Zkuste to za pár minut."
                httpStatusCodes.any { it == 401 } ->
                    "GitHub vyžaduje přihlášení (HTTP 401) – repozitář $GITHUB_REPO je soukromý."
                httpStatusCodes.any { it in 500..599 } ->
                    "Server GitHubu má momentálně výpadek (HTTP ${httpStatusCodes.first { it in 500..599 }})."
                lastError is java.net.UnknownHostException ->
                    "Telefon nedokáže přeložit adresu api.github.com (vypnutý internet nebo chyba DNS)."
                lastError is java.net.SocketTimeoutException ->
                    "Vypršel časový limit spojení s GitHubem (pomalé spojení nebo blokovaný port)."
                lastError is javax.net.ssl.SSLException ->
                    "Chyba zabezpečeného spojení SSL/TLS (${lastError?.message})."
                httpStatusCodes.isNotEmpty() ->
                    "GitHub vrátil stav HTTP ${httpStatusCodes.distinct().joinToString("/")}."
                else ->
                    "Nelze navázat spojení s GitHubem (${lastError?.javaClass?.simpleName ?: "zkontrolujte internet"}: ${lastError?.message ?: "bez odezvy"})."
            }
            throw java.io.IOException(detailedReason)
        }

        // Připojíme unikátní timestamp k URL samotného APK, aby CDN při stahování nevrátila starý zkešovaný APK soubor
        val finalApkUrl = if (apkDownloadUrl.contains("?")) {
            "$apkDownloadUrl&t=${System.currentTimeMillis()}"
        } else {
            "$apkDownloadUrl?t=${System.currentTimeMillis()}"
        }

        UpdateCheckResult(
            isUpdateAvailable = hasNewer,
            currentVersionCode = currentCode,
            currentVersionName = currentName,
            latestVersionCode = latestFoundCode,
            latestVersionName = latestFoundName,
            releaseNotes = releaseNotes.ifEmpty { "K dispozici je nová verze aplikace Stíhám to? ($latestFoundName)." },
            apkUrl = finalApkUrl
        )
    }

    /**
     * Stáhne APK soubor s plnou podporou vícenásobných HTTP přesměrování (301, 302, 303, 307, 308)
     * a průběžným hlášením postupu (0.0f .. 1.0f).
     */
    suspend fun downloadApk(
        context: Context,
        apkUrl: String,
        onProgress: (Float) -> Unit
    ): File = withContext(Dispatchers.IO) {
        val updatesDir = File(context.cacheDir, "updates")
        if (!updatesDir.exists()) {
            updatesDir.mkdirs()
        }
        val targetFile = File(updatesDir, "StihamTo-Plzen-update.apk")
        if (targetFile.exists()) {
            targetFile.delete()
        }

        var currentUrl = apkUrl
        var finalConnection: HttpURLConnection? = null
        var redirectCount = 0

        while (redirectCount < 6) {
            val url = URL(currentUrl)
            val conn = url.openConnection() as HttpURLConnection
            conn.useCaches = false
            conn.instanceFollowRedirects = false
            conn.connectTimeout = 15000
            conn.readTimeout = 30000
            conn.setRequestProperty("User-Agent", "StihamTo-Plzen-App")
            conn.setRequestProperty("Cache-Control", "no-cache, no-store, max-age=0")
            conn.setRequestProperty("Accept", "application/vnd.android.package-archive, application/octet-stream, */*")

            val status = conn.responseCode
            if (status == HttpURLConnection.HTTP_MOVED_TEMP ||
                status == HttpURLConnection.HTTP_MOVED_PERM ||
                status == HttpURLConnection.HTTP_SEE_OTHER ||
                status == 307 || status == 308
            ) {
                val location = conn.getHeaderField("Location")
                    ?: throw IllegalStateException("Přesměrování bez hlavičky Location (HTTP $status)")
                currentUrl = if (location.startsWith("http://") || location.startsWith("https://")) {
                    location
                } else {
                    URL(url, location).toString()
                }
                conn.disconnect()
                redirectCount++
                continue
            }

            if (status != HttpURLConnection.HTTP_OK) {
                conn.disconnect()
                val msg = when (status) {
                    404 -> "Stahování APK selhalo (HTTP 404) – soubor v repozitáři chybí nebo je repozitář soukromý (Private)."
                    403, 429 -> "GitHub omezil stahování (HTTP $status Rate Limit). Zkuste to za chvíli."
                    else -> "Server vrátil chybu při stahování aktualizace: HTTP $status"
                }
                throw IllegalStateException(msg)
            }

            finalConnection = conn
            break
        }

        val conn = finalConnection
            ?: throw IllegalStateException("Příliš mnoho přesměrování při stahování APK souboru.")

        val totalLength = conn.contentLength.toLong()
        var downloadedBytes = 0L

        try {
            conn.inputStream.use { input ->
                FileOutputStream(targetFile).use { output ->
                    val buffer = ByteArray(8192)
                    var bytesRead: Int
                    while (input.read(buffer).also { bytesRead = it } != -1) {
                        output.write(buffer, 0, bytesRead)
                        downloadedBytes += bytesRead
                        if (totalLength > 0) {
                            val progress = (downloadedBytes.toFloat() / totalLength).coerceIn(0f, 1f)
                            onProgress(progress)
                        }
                    }
                    output.flush()
                }
            }
        } finally {
            conn.disconnect()
        }

        if (!targetFile.exists() || targetFile.length() < 100_000L) {
            throw IllegalStateException("Stažený APK soubor je nekompletní nebo poškozený (${targetFile.length()} B).")
        }

        // Ověření integrity ZIP / APK hlavičky (magic bytes: 0x50, 0x4B, 0x03, 0x04)
        val header = ByteArray(4)
        java.io.FileInputStream(targetFile).use { it.read(header) }
        val isZip = header[0] == 0x50.toByte() && header[1] == 0x4B.toByte() && header[2] == 0x03.toByte() && header[3] == 0x04.toByte()
        if (!isZip) {
            targetFile.delete()
            throw IllegalStateException("Stažený soubor není platný instalační balíček Android (APK).")
        }

        targetFile
    }

    /**
     * Spustí systémový instalátor staženého APK souboru.
     */
    fun startInstall(activity: Activity, apkFile: File) {
        if (!apkFile.exists()) {
            throw IllegalStateException("Soubor instalačního balíčku nebyl nalezen.")
        }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            if (!activity.packageManager.canRequestPackageInstalls()) {
                val intent = Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES).apply {
                    data = Uri.parse("package:${activity.packageName}")
                }
                activity.startActivity(intent)
                return
            }
        }

        val apkUri = FileProvider.getUriForFile(
            activity,
            "${activity.packageName}.fileprovider",
            apkFile
        )

        val installIntent = Intent(Intent.ACTION_VIEW).apply {
            setDataAndType(apkUri, "application/vnd.android.package-archive")
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }

        activity.startActivity(installIntent)
    }
}
