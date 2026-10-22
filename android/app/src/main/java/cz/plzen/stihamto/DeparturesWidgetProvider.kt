package cz.plzen.stihamto

import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.os.Build
import android.widget.RemoteViews

class DeparturesWidgetProvider : AppWidgetProvider() {

    companion object {
        const val ACTION_WIDGET_REFRESH = "cz.plzen.stihamto.ACTION_WIDGET_REFRESH"

        fun updateAllWidgets(context: Context, result: LiveDeparturesResult?) {
            try {
                val manager = AppWidgetManager.getInstance(context)
                val component = ComponentName(context, DeparturesWidgetProvider::class.java)
                val ids = manager.getAppWidgetIds(component)
                if (ids.isEmpty()) return

                for (id in ids) {
                    updateSingleWidget(context, manager, id, result)
                }
            } catch (t: Throwable) {
                android.util.Log.e("WidgetProvider", "Chyba při updateAllWidgets: ${t.message}", t)
            }
        }

        private fun updateSingleWidget(
            context: Context,
            manager: AppWidgetManager,
            appWidgetId: Int,
            result: LiveDeparturesResult?
        ) {
            try {
                val views = RemoteViews(context.packageName, R.layout.widget_departures)

            val openIntent = PendingIntent.getActivity(
                context,
                100,
                Intent(context, MainActivity::class.java),
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )
            views.setOnClickPendingIntent(R.id.widgetRoot, openIntent)

            val refreshBroadcastIntent = PendingIntent.getBroadcast(
                context,
                101,
                Intent(context, DeparturesWidgetProvider::class.java).apply {
                    action = ACTION_WIDGET_REFRESH
                },
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )
            views.setOnClickPendingIntent(R.id.widgetRefreshBtn, refreshBroadcastIntent)

            val data = result ?: LiveDeparturesService.latestResult
            if (data != null) {
                val isMulti = data.clusterStops.size > 1
                val stopTitle = if (isMulti) {
                    data.clusterStops.take(2).joinToString(" · ") { it.stop.name }
                } else {
                    data.primaryStop.stop.name
                }
                views.setTextViewText(R.id.widgetStopName, stopTitle)
                val updateTime = data.fetchedAtHHMMSS.substringBeforeLast(":")
                val isFarAway = data.primaryStop.distanceMeters > 18000
                val distInfo = if (isFarAway) "Mimo Plzeň" else "${data.primaryStop.distanceMeters} m (${data.primaryStop.walkingMinutes} min chůze)"
                views.setTextViewText(
                    R.id.widgetSubtitle,
                    "$distInfo  ·  Aktualizováno $updateTime"
                )

                val nowMs = System.currentTimeMillis()
                val validDeps = data.departures.filter { dep ->
                    val remMs = dep.targetDepartureEpochMs - nowMs
                    remMs >= -35_000L
                }
                val linesText = if (validDeps.isEmpty()) {
                    "Žádné odjezdy v nejbližší době."
                } else {
                    validDeps.take(4).joinToString("\n") { d ->
                        val inTxt = when {
                            d.leavingInMin < 0 -> "odjelo"
                            d.leavingInMin == 0 -> "teď"
                            else -> "za ${d.leavingInMin} min"
                        }
                        val delayNote = if (d.delayMin != null && d.delayMin >= 2) " (+${d.delayMin}m)" else ""
                        "${d.vehicleIcon} ${d.lineName} → ${d.destination} — $inTxt$delayNote  ·  ${d.catchability.label}"
                    }
                }
                views.setTextViewText(R.id.widgetDeparturesText, linesText)
            }

            manager.updateAppWidget(appWidgetId, views)
        } catch (t: Throwable) {
            android.util.Log.e("WidgetProvider", "Chyba v updateSingleWidget: ${t.message}", t)
        }
    }
}

    override fun onUpdate(
        context: Context,
        appWidgetManager: AppWidgetManager,
        appWidgetIds: IntArray
    ) {
        for (appWidgetId in appWidgetIds) {
            updateSingleWidget(context, appWidgetManager, appWidgetId, LiveDeparturesService.latestResult)
        }
    }

    override fun onReceive(context: Context, intent: Intent) {
        super.onReceive(context, intent)
        if (intent.action == ACTION_WIDGET_REFRESH) {
            val serviceIntent = Intent(context, LiveDeparturesService::class.java).apply {
                action = if (LiveDeparturesService.isRunning) {
                    LiveDeparturesService.ACTION_REFRESH
                } else {
                    LiveDeparturesService.ACTION_START
                }
            }
            try {
                if (LiveDeparturesService.isRunning) {
                    context.startService(serviceIntent)
                } else if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    context.startForegroundService(serviceIntent)
                } else {
                    context.startService(serviceIntent)
                }
            } catch (_: Throwable) {
            }
        }
    }
}
