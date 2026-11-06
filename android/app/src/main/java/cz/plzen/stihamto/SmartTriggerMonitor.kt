package cz.plzen.stihamto

import android.content.Context
import android.content.Intent
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.net.wifi.WifiInfo
import android.net.wifi.WifiManager
import android.os.Build

object SmartTriggerMonitor {
    private var isRegistered = false
    private var currentlyOnWifi = false

    @Volatile
    private var lastKnownWifiSsid: String? = null

    fun isConnectedToWifi(context: Context): Boolean {
        val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager
            ?: return false
        val activeNet = cm.activeNetwork ?: return false
        val caps = cm.getNetworkCapabilities(activeNet) ?: return false
        return caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI)
    }

    fun getConnectedWifiSsid(context: Context): String? {
        if (!isConnectedToWifi(context)) {
            lastKnownWifiSsid = null
            return null
        }
        try {
            // 1. Moderní způsob pro Android 12+ (API 31+) přes NetworkCapabilities.transportInfo
            val cm = context.applicationContext.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager
            val activeNet = cm?.activeNetwork
            if (activeNet != null) {
                val caps = cm.getNetworkCapabilities(activeNet)
                val wifiInfo = caps?.transportInfo as? WifiInfo
                val modernClean = PmdpRepository.sanitizeSsid(wifiInfo?.ssid)
                if (modernClean.isNotEmpty()) {
                    lastKnownWifiSsid = modernClean
                    return modernClean
                }
            }

            // 2. Fallback přes WifiManager
            val wm = context.applicationContext.getSystemService(Context.WIFI_SERVICE) as? WifiManager
            @Suppress("DEPRECATION")
            val legacyClean = PmdpRepository.sanitizeSsid(wm?.connectionInfo?.ssid)
            if (legacyClean.isNotEmpty()) {
                lastKnownWifiSsid = legacyClean
                return legacyClean
            }
        } catch (_: Throwable) {
        }
        // 3. Hodnota zachycená z NetworkCallback(FLAG_INCLUDE_LOCATION_INFO).onCapabilitiesChanged
        return lastKnownWifiSsid
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

        fun handleWifiAvailable() {
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
                val sleepReason = if (!ssid.isNullOrEmpty()) {
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

        fun handleWifiLost() {
            val wasWifi = currentlyOnWifi
            currentlyOnWifi = isConnectedToWifi(appCtx)
            if (!currentlyOnWifi) {
                lastKnownWifiSsid = null
            }
            // V hluboké noci (01:00 až 04:00) automatické probuzení z výpadku domácí Wi-Fi nebo restartu routeru ignorujeme
            val hourOfDay = java.util.Calendar.getInstance().get(java.util.Calendar.HOUR_OF_DAY)
            if (hourOfDay in 1..3) {
                return
            }

            // Jakmile se telefon odpojí od Wi-Fi (vypnutí Wi-Fi nebo odchod z bytu na mobilní data),
            // okamžitě probudíme notifikaci na zamykací obrazovce (pokud uživatel službu ručně nevypnul)!
            if (wasWifi && !currentlyOnWifi && !PmdpRepository.isServiceExplicitlyStopped(appCtx)) {
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

        val fallbackCallback = object : ConnectivityManager.NetworkCallback() {
            override fun onAvailable(network: Network) {
                handleWifiAvailable()
            }
            override fun onLost(network: Network) {
                handleWifiLost()
            }
        }

        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                try {
                    val modernCallback = object : ConnectivityManager.NetworkCallback(FLAG_INCLUDE_LOCATION_INFO) {
                        override fun onAvailable(network: Network) {
                            handleWifiAvailable()
                        }
                        override fun onCapabilitiesChanged(network: Network, networkCapabilities: NetworkCapabilities) {
                            val wifiInfo = networkCapabilities.transportInfo as? WifiInfo
                            val capSsid = PmdpRepository.sanitizeSsid(wifiInfo?.ssid)
                            if (capSsid.isNotEmpty() && capSsid != lastKnownWifiSsid) {
                                lastKnownWifiSsid = capSsid
                                handleWifiAvailable()
                                appCtx.sendBroadcast(
                                    Intent(LiveDeparturesService.BROADCAST_LIVE_UPDATE).setPackage(appCtx.packageName)
                                )
                            }
                        }
                        override fun onLost(network: Network) {
                            handleWifiLost()
                        }
                    }
                    cm.registerNetworkCallback(request, modernCallback)
                    isRegistered = true
                    return
                } catch (_: Throwable) {
                }
            }

            cm.registerNetworkCallback(request, fallbackCallback)
            isRegistered = true
        } catch (_: Exception) {
        }
    }
}
