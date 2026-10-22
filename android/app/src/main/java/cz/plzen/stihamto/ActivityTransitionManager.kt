package cz.plzen.stihamto

import android.Manifest
import android.annotation.SuppressLint
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.util.Log
import androidx.core.content.ContextCompat
import com.google.android.gms.location.ActivityRecognition
import com.google.android.gms.location.ActivityTransition
import com.google.android.gms.location.ActivityTransitionRequest
import com.google.android.gms.location.DetectedActivity

object ActivityTransitionManager {
    private const val TAG = "ActivityTransMgr"
    private var isRegistered = false

    private fun getPendingIntent(context: Context): PendingIntent {
        val intent = Intent(context, ActivityTransitionReceiver::class.java).apply {
            action = ActivityTransitionReceiver.ACTION_ACTIVITY_TRANSITION
        }
        val flags = PendingIntent.FLAG_UPDATE_CURRENT or (
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) PendingIntent.FLAG_MUTABLE else 0
        )
        return PendingIntent.getBroadcast(context, 1001, intent, flags)
    }

    @SuppressLint("MissingPermission")
    fun registerTransitions(context: Context) {
        if (isRegistered) return

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            val hasPerm = ContextCompat.checkSelfPermission(
                context,
                Manifest.permission.ACTIVITY_RECOGNITION
            ) == PackageManager.PERMISSION_GRANTED
            if (!hasPerm) {
                Log.w(TAG, "Chybí oprávnění ACTIVITY_RECOGNITION, přechody nelze registrovat")
                return
            }
        }

        val transitions = mutableListOf(
            // 1. Chůze / Běh (Okamžité probuzení a sledování odjezdů)
            ActivityTransition.Builder()
                .setActivityType(DetectedActivity.WALKING)
                .setActivityTransition(ActivityTransition.ACTIVITY_TRANSITION_ENTER)
                .build(),
            ActivityTransition.Builder()
                .setActivityType(DetectedActivity.WALKING)
                .setActivityTransition(ActivityTransition.ACTIVITY_TRANSITION_EXIT)
                .build(),
            ActivityTransition.Builder()
                .setActivityType(DetectedActivity.RUNNING)
                .setActivityTransition(ActivityTransition.ACTIVITY_TRANSITION_ENTER)
                .build(),
            ActivityTransition.Builder()
                .setActivityType(DetectedActivity.RUNNING)
                .setActivityTransition(ActivityTransition.ACTIVITY_TRANSITION_EXIT)
                .build(),

            // 2. Sezení / Stání na místě (Přepnutí do úsporného režimu bez GPS)
            ActivityTransition.Builder()
                .setActivityType(DetectedActivity.STILL)
                .setActivityTransition(ActivityTransition.ACTIVITY_TRANSITION_ENTER)
                .build(),
            ActivityTransition.Builder()
                .setActivityType(DetectedActivity.STILL)
                .setActivityTransition(ActivityTransition.ACTIVITY_TRANSITION_EXIT)
                .build(),

            // 3. Jízda ve vozidle MHD / Autě (Pauza pro cestu tramvají/busem)
            ActivityTransition.Builder()
                .setActivityType(DetectedActivity.IN_VEHICLE)
                .setActivityTransition(ActivityTransition.ACTIVITY_TRANSITION_ENTER)
                .build(),
            ActivityTransition.Builder()
                .setActivityType(DetectedActivity.IN_VEHICLE)
                .setActivityTransition(ActivityTransition.ACTIVITY_TRANSITION_EXIT)
                .build()
        )

        val request = ActivityTransitionRequest(transitions)
        val pendingIntent = getPendingIntent(context)

        ActivityRecognition.getClient(context)
            .requestActivityTransitionUpdates(request, pendingIntent)
            .addOnSuccessListener {
                isRegistered = true
                Log.i(TAG, "Úspěšně registrovány systémové přechody aktivit (Activity Recognition)")
            }
            .addOnFailureListener { e ->
                Log.e(TAG, "Chyba při registraci přechodů aktivit: ${e.message}")
            }
    }

    @SuppressLint("MissingPermission")
    fun unregisterTransitions(context: Context) {
        if (!isRegistered) return
        val pendingIntent = getPendingIntent(context)
        ActivityRecognition.getClient(context)
            .removeActivityTransitionUpdates(pendingIntent)
            .addOnCompleteListener {
                isRegistered = false
                Log.i(TAG, "Odregistrovány přechody aktivit")
            }
    }
}
