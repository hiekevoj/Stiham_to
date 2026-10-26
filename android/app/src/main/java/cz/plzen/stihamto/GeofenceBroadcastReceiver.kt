package cz.plzen.stihamto

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Build
import com.google.android.gms.location.Geofence
import com.google.android.gms.location.GeofencingEvent

class GeofenceBroadcastReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        try {
            if (PmdpRepository.isServiceExplicitlyStopped(context)) return
            SmartTriggerMonitor.ensureWifiCallbackRegistered(context)

            val event = GeofencingEvent.fromIntent(intent) ?: return
            if (event.hasError()) return

            // 1. Pokud jsme připojeni na domácí/pracovní Wi-Fi, Geofence u zastávky ignorujeme!
            if (PmdpRepository.isSleepOnWifiEnabled(context) && SmartTriggerMonitor.isConnectedToHomeWifi(context)) {
                return
            }

            val now = System.currentTimeMillis()
            // 2. Pokud už služba aktivně běží, nebo je v ochranné pauze po jízdě / procházce / v budově,
            //    geofence událost ignorujeme, aby nerestartovala měření ani nepřepisovala notifikaci!
            if (LiveDeparturesService.isRunning && !LiveDeparturesService.isSleepingStandby) {
                return
            }
            if (now < LiveDeparturesService.postRideCooldownUntilMs ||
                now < LiveDeparturesService.indoorPlaceCooldownUntilMs
            ) {
                return
            }

            // 3. Pokud služba na tomto místě už dříve usnula kvůli nečinnosti (např. doma bez Wi-Fi),
            // neprobouzíme ji, dokud se uživatel neposune aspoň o 42 metrů.
            val trigLoc = event.triggeringLocation
            if (trigLoc != null && PmdpRepository.isStillAtAutoSleptLocation(context, trigLoc.latitude, trigLoc.longitude)) {
                return
            }

            when (event.geofenceTransition) {
                Geofence.GEOFENCE_TRANSITION_ENTER,
                Geofence.GEOFENCE_TRANSITION_DWELL -> {
                    val serviceIntent = Intent(context, LiveDeparturesService::class.java).apply {
                        action = LiveDeparturesService.ACTION_START_AUTO
                        putExtra("trigger_reason", "geofence")
                    }
                    try {
                        if (LiveDeparturesService.isRunning) {
                            context.startService(serviceIntent)
                        } else if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                            try {
                                context.startForegroundService(serviceIntent)
                            } catch (_: Throwable) {
                                notifyUserNearStop(context)
                            }
                        } else {
                            context.startService(serviceIntent)
                        }
                    } catch (_: Throwable) {
                    }
                }
            }
        } catch (_: Throwable) {
        }
    }

    private fun notifyUserNearStop(context: Context) {
        try {
            LiveDeparturesService.ensureNotificationChannelExists(context)
            val nm = context.getSystemService(Context.NOTIFICATION_SERVICE) as? android.app.NotificationManager ?: return
            val openAppIntent = android.app.PendingIntent.getActivity(
                context,
                0,
                Intent(context, MainActivity::class.java).apply {
                    flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
                },
                android.app.PendingIntent.FLAG_UPDATE_CURRENT or android.app.PendingIntent.FLAG_IMMUTABLE
            )

            val notif = androidx.core.app.NotificationCompat.Builder(context, LiveDeparturesService.CHANNEL_ID)
                .setSmallIcon(R.drawable.ic_notif_transit)
                .setColor(android.graphics.Color.parseColor("#2C4C38"))
                .setContentTitle("Jste u zastávky MHD")
                .setContentText("Klepnutím zobrazíte odjezdy nejbližších spojů")
                .setPriority(androidx.core.app.NotificationCompat.PRIORITY_HIGH)
                .setContentIntent(openAppIntent)
                .setAutoCancel(true)
                .build()

            nm.notify(LiveDeparturesService.FALLBACK_NOTIFICATION_ID, notif)
        } catch (_: Throwable) {
        }
    }
}
