package cz.plzen.stihamto

import android.content.Context
import android.content.Intent
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.net.wifi.WifiManager
import android.os.Build

object SmartTriggerMonitor {
    private var isRegistered = false
    private var currentlyOnWifi = false

    fun isConnectedToWifi(context: Context): Boolean {
        val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager
            ?: return false
        val activeNet = cm.activeNetwork ?: return false
        val caps = cm.getNetworkCapabilities(activeNet) ?: return false
        return caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI)
    }

    fun getConnectedWifiSsid(context: Context): String? {
        try {
            val wm = context.applicationContext.getSystemService(Context.WIFI_SERVICE) as? WifiManager
            val info = wm?.connectionInfo
            val clean = PmdpRepository.sanitizeSsid(info?.ssid)
            if (clean.isNotEmpty()) return clean
        } catch (_: Throwable) {
        }
        return null
    }

    fun isConnectedToHomeWifi(context: Context): Boolean {
        if (!isConnectedToWifi(context)) return false
        val ssid = getConnectedWifiSsid(context)
        return PmdpRepository.isHomeWifi(context, ssid)
    }

    fun ensureWifiCallbackRegistered(context: Context) {
        if (isRegistered) return
        val appCtx = context.applicationContext
        val cm = appCtx.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager
            ?: return

        currentlyOnWifi = isConnectedToWifi(appCtx)

        val request = NetworkRequest.Builder()
            .addTransportType(NetworkCapabilities.TRANSPORT_WIFI)
            .build()

        try {
            cm.registerNetworkCallback(request, object : ConnectivityManager.NetworkCallback() {
                override fun onAvailable(network: Network) {
                    currentlyOnWifi = true
                    val ssid = getConnectedWifiSsid(appCtx)
                    val isHome = PmdpRepository.isHomeWifi(appCtx, ssid)

                    // Pokud síť NENÍ na seznamu povolených domácích/pracovních sítí
                    // (např. PMDP-Free v tramvaji), službu NIKDY neuspáváme!
                    if (!isHome) {
                        return
                    }

                    // Při připojení na ověřenou domácí Wi-Fi přepneme službu do tichého spánku (Standby),
                    // aby ze zamykací obrazovky zmizela a nebrala GPS, ale krokoměr dál hlídal odchod.
                    if (PmdpRepository.isSleepOnWifiEnabled(appCtx) &&
                        LiveDeparturesService.isRunning &&
                        !LiveDeparturesService.isSleepingStandby &&
                        !LiveDeparturesService.isManualForceOnWifi &&
                        !LiveDeparturesService.isWalkingWifiOverrideActive()
                    ) {
                        val sleepReason = if (ssid != null && ssid.isNotEmpty()) {
                            "Uspáno (připojeno k Wi-Fi: $ssid)"
                        } else {
                            "Uspáno (připojeno k domácí Wi-Fi)"
                        }
                        val sleepIntent = Intent(appCtx, LiveDeparturesService::class.java).apply {
                            action = LiveDeparturesService.ACTION_SLEEP_STANDBY
                            putExtra("sleep_reason", sleepReason)
                        }
                        try {
                            appCtx.startService(sleepIntent)
                        } catch (_: Exception) {
                        }
                    }
                }

                override fun onLost(network: Network) {
                    val wasWifi = currentlyOnWifi
                    currentlyOnWifi = isConnectedToWifi(appCtx)
                    // Jakmile se telefon odpojí od Wi-Fi (vypnutí Wi-Fi nebo odchod z bytu na mobilní data),
                    // okamžitě probudíme notifikaci na zamykací obrazovce!
                    if (wasWifi && !currentlyOnWifi) {
                        PmdpRepository.clearAutoSleptLocation(appCtx)
                        val wakeIntent = Intent(appCtx, LiveDeparturesService::class.java).apply {
                            action = LiveDeparturesService.ACTION_WAKE_UP
                            putExtra("trigger_reason", "Odpojeno od Wi-Fi")
                        }
                        try {
                            if (LiveDeparturesService.isRunning) {
                                appCtx.startService(wakeIntent)
                            } else if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                                try {
                                    appCtx.startForegroundService(wakeIntent)
                                } catch (e: Throwable) {
                                    android.util.Log.w("SmartTriggerMonitor", "FGS nelze spustit z pozadí: ${e.message}")
                                }
                            } else {
                                appCtx.startService(wakeIntent)
                            }
                        } catch (_: Throwable) {
                        }
                    }
                }
            })
            isRegistered = true
        } catch (_: Exception) {
        }
    }
}
