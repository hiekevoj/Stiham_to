package cz.plzen.stihamto

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Build
import android.util.Log
import com.google.android.gms.location.ActivityTransition
import com.google.android.gms.location.ActivityTransitionResult
import com.google.android.gms.location.DetectedActivity

/**
 * Systémový BroadcastReceiver pro Google Activity Recognition API.
 * Přijímá hardwarově vyhodnocené změny pohybu (CHŮZE, SEZENÍ/KLID, JÍZDA V MHD)
 * z ultra-úsporného koprocesoru telefonu (Samsung S21) s nulovou spotřebou baterie.
 */
class ActivityTransitionReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        if (!ActivityTransitionResult.hasResult(intent)) return

        val result = ActivityTransitionResult.extractResult(intent) ?: return
        for (event in result.transitionEvents) {
            val activityName = when (event.activityType) {
                DetectedActivity.WALKING -> "WALKING"
                DetectedActivity.RUNNING -> "RUNNING"
                DetectedActivity.IN_VEHICLE -> "IN_VEHICLE"
                DetectedActivity.STILL -> "STILL"
                DetectedActivity.ON_BICYCLE -> "ON_BICYCLE"
                else -> "OTHER(${event.activityType})"
            }
            val transitionType = if (event.transitionType == ActivityTransition.ACTIVITY_TRANSITION_ENTER) "ENTER" else "EXIT"
            Log.d(TAG, "Detekován přechod aktivity: $activityName -> $transitionType")

            when (event.activityType) {
                DetectedActivity.WALKING, DetectedActivity.RUNNING -> {
                    if (event.transitionType == ActivityTransition.ACTIVITY_TRANSITION_ENTER) {
                        // Uživatel se zvedl a začal jít nebo běžet (z kanceláře / bytu)
                        // Okamžitě probouzíme službu a kontrolujeme odjezdy bez ohledu na to, zda ještě visí na Wi-Fi!
                        sendServiceAction(context, LiveDeparturesService.ACTION_WALK_DETECTED)
                    }
                }

                DetectedActivity.STILL -> {
                    if (event.transitionType == ActivityTransition.ACTIVITY_TRANSITION_ENTER) {
                        // Uživatel se zastavil / sedí na židli
                        sendServiceAction(context, LiveDeparturesService.ACTION_STILL_DETECTED)
                    }
                }

                DetectedActivity.IN_VEHICLE -> {
                    if (event.transitionType == ActivityTransition.ACTIVITY_TRANSITION_ENTER) {
                        // Uživatel nasedl do tramvaje / autobusu / auta
                        sendServiceAction(context, LiveDeparturesService.ACTION_IN_VEHICLE_DETECTED)
                    } else if (event.transitionType == ActivityTransition.ACTIVITY_TRANSITION_EXIT) {
                        // Uživatel vystoupil z vozidla
                        sendServiceAction(context, LiveDeparturesService.ACTION_EXIT_VEHICLE_DETECTED)
                    }
                }
            }
        }
    }

    private fun sendServiceAction(context: Context, actionStr: String) {
        val serviceIntent = Intent(context, LiveDeparturesService::class.java).apply {
            action = actionStr
        }
        try {
            if (LiveDeparturesService.isRunning) {
                context.startService(serviceIntent)
            } else if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                try {
                    context.startForegroundService(serviceIntent)
                } catch (e: Throwable) {
                    Log.w(TAG, "Foreground service nelze spustit přímo z pozadí (Android 12+ restrikce): ${e.message}")
                    if (actionStr == LiveDeparturesService.ACTION_WALK_DETECTED) {
                        notifyFallbackToUser(context)
                    }
                }
            } else {
                context.startService(serviceIntent)
            }
        } catch (e: Throwable) {
            Log.e(TAG, "Chyba při předání akce do LiveDeparturesService: ${e.message}")
        }
    }

    private fun notifyFallbackToUser(context: Context) {
        try {
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
                .setContentTitle("Stíhám to? · Zjistit odjezdy")
                .setContentText("Detekována chůze – klepnutím zobrazíte nejbližší odjezdy MHD")
                .setPriority(androidx.core.app.NotificationCompat.PRIORITY_HIGH)
                .setContentIntent(openAppIntent)
                .setAutoCancel(true)
                .build()

            nm.notify(LiveDeparturesService.NOTIFICATION_ID, notif)
        } catch (_: Throwable) {
        }
    }

    companion object {
        private const val TAG = "ActivityTransition"
        const val ACTION_ACTIVITY_TRANSITION = "cz.plzen.stihamto.ACTION_ACTIVITY_TRANSITION"
    }
}
