package cz.plzen.stihamto

import android.Manifest
import android.annotation.SuppressLint
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.util.Log
import androidx.core.content.ContextCompat
import com.google.android.gms.location.LocationServices

/**
 * Zajišťuje samočinnou obnovu hlídání po restartu telefonu (BOOT_COMPLETED)
 * a po aktualizaci aplikace (MY_PACKAGE_REPLACED), aby o aplikaci uživatel
 * nemusel vůbec přemýšlet ani ji ručně zapínat.
 */
class SystemBootAndUpdateReceiver : BroadcastReceiver() {

    @SuppressLint("MissingPermission")
    override fun onReceive(context: Context, intent: Intent) {
        val action = intent.action ?: return
        if (action != Intent.ACTION_BOOT_COMPLETED &&
            action != "android.intent.action.QUICKBOOT_POWERON" &&
            action != Intent.ACTION_MY_PACKAGE_REPLACED
        ) {
            return
        }

        try {
            if (PmdpRepository.isServiceExplicitlyStopped(context)) {
                Log.i(TAG, "Služba byla uživatelem ručně vypnuta, po $action ji nespouštíme.")
                return
            }

            Log.i(TAG, "Obnovuji automatické spouštěče po události: $action")
            SmartTriggerMonitor.ensureWifiCallbackRegistered(context)
            ActivityTransitionManager.registerTransitions(context)

            val hasFineLoc = ContextCompat.checkSelfPermission(
                context,
                Manifest.permission.ACCESS_FINE_LOCATION
            ) == PackageManager.PERMISSION_GRANTED

            val hasBgLoc = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                ContextCompat.checkSelfPermission(
                    context,
                    Manifest.permission.ACCESS_BACKGROUND_LOCATION
                ) == PackageManager.PERMISSION_GRANTED
            } else true

            if (hasFineLoc) {
                val (savedLat, savedLon) = PmdpRepository.getLastKnownLocation(context)
                if (hasBgLoc) {
                    GeofenceManager.registerNearbyGeofences(context, savedLat, savedLon, force = true)
                }
                try {
                    LocationServices.getFusedLocationProviderClient(context)
                        .lastLocation
                        .addOnSuccessListener { loc ->
                            if (loc != null) {
                                PmdpRepository.saveLastKnownLocation(context, loc.latitude, loc.longitude)
                                if (hasBgLoc) {
                                    GeofenceManager.registerNearbyGeofences(context, loc.latitude, loc.longitude, force = true)
                                }
                            }
                        }
                } catch (_: Throwable) {
                }
            }

            // Po aktualizaci aplikace (nebo restartu), pokud máme oprávnění, uvedeme službu do tichého Standby režimu
            if (hasFineLoc && hasBgLoc) {
                val standbyIntent = Intent(context, LiveDeparturesService::class.java).apply {
                    this.action = LiveDeparturesService.ACTION_SLEEP_STANDBY
                    putExtra("sleep_reason", "Připraveno – probudí se při chůzi k zastávce")
                }
                try {
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                        context.startForegroundService(standbyIntent)
                    } else {
                        context.startService(standbyIntent)
                    }
                } catch (e: Throwable) {
                    Log.w(TAG, "Nelze spustit Standby službu přímo po $action (spustí ji Activity Recognition / Geofence): ${e.message}")
                }
            }
        } catch (t: Throwable) {
            Log.e(TAG, "Chyba v SystemBootAndUpdateReceiver: ${t.message}", t)
        }
    }

    companion object {
        private const val TAG = "BootUpdateReceiver"
    }
}
