package cz.plzen.stihamto

import android.annotation.SuppressLint
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import com.google.android.gms.location.Geofence
import com.google.android.gms.location.GeofencingRequest
import com.google.android.gms.location.LocationServices

object GeofenceManager {
    private const val GEOFENCE_RADIUS_METERS = 320f
    private const val MAX_GEOFENCES = 50
    private const val MIN_REREGISTER_DISTANCE_METERS = 250.0

    @Volatile
    private var lastRegisteredLat: Double = Double.NaN

    @Volatile
    private var lastRegisteredLon: Double = Double.NaN

    @SuppressLint("MissingPermission")
    fun registerNearbyGeofences(context: Context, lat: Double, lon: Double, force: Boolean = false) {
        try {
            if (!force && !lastRegisteredLat.isNaN() && !lastRegisteredLon.isNaN()) {
                val moved = PmdpRepository.haversineMeters(lastRegisteredLat, lastRegisteredLon, lat, lon)
                if (moved < MIN_REREGISTER_DISTANCE_METERS) {
                    return
                }
            }

            val geofencingClient = LocationServices.getGeofencingClient(context)
            val nearestStops = PmdpRepository.findNearestStops(
                context = context,
                lat = lat,
                lon = lon,
                limit = MAX_GEOFENCES
            )

            if (nearestStops.isEmpty()) return
            lastRegisteredLat = lat
            lastRegisteredLon = lon

            val geofenceList = nearestStops.map { ranked ->
                Geofence.Builder()
                    .setRequestId("STOP_${ranked.stop.cisNumber}")
                    .setCircularRegion(
                        ranked.stop.latitude,
                        ranked.stop.longitude,
                        GEOFENCE_RADIUS_METERS
                    )
                    .setExpirationDuration(Geofence.NEVER_EXPIRE)
                    .setTransitionTypes(
                        Geofence.GEOFENCE_TRANSITION_ENTER or
                                Geofence.GEOFENCE_TRANSITION_DWELL or
                                Geofence.GEOFENCE_TRANSITION_EXIT
                    )
                    .setLoiteringDelay(5_000)
                    .setNotificationResponsiveness(5_000)
                    .build()
            }

            val request = GeofencingRequest.Builder()
                .setInitialTrigger(GeofencingRequest.INITIAL_TRIGGER_ENTER)
                .addGeofences(geofenceList)
                .build()

            val intent = Intent(context, GeofenceBroadcastReceiver::class.java)
            val pendingIntent = PendingIntent.getBroadcast(
                context,
                9001,
                intent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_MUTABLE
            )

            geofencingClient.removeGeofences(pendingIntent).addOnCompleteListener {
                try {
                    geofencingClient.addGeofences(request, pendingIntent)
                } catch (_: SecurityException) {
                }
            }
        } catch (_: Exception) {
        }
    }
}
