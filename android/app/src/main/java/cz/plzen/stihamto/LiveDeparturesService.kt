package cz.plzen.stihamto

import android.annotation.SuppressLint
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.ServiceInfo
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.location.Location
import android.os.Build
import android.os.IBinder
import android.os.Looper
import android.os.PowerManager
import androidx.core.app.NotificationCompat
import com.google.android.gms.location.*
import kotlinx.coroutines.*
import kotlin.math.sqrt

class LiveDeparturesService : Service(), SensorEventListener {

    enum class StandbyCooldownCause {
        NONE,
        WALK,            // Procházka městem (míjení 2 zastávek nebo odchod mimo dosah MHD)
        INDOOR_IDLE,     // Na jednom místě v budově / obchodě
        STOP_IDLE,       // Dlouhé čekání na zastávce
        MANUAL_SLEEP     // Ruční uspání uživatelem
    }

    companion object {
        const val CHANNEL_ID = "stiham_to_live_lockscreen_v2"
        const val CHANNEL_STANDBY_ID = "stiham_to_standby_silent"
        const val NOTIFICATION_ID = 1001
        const val FALLBACK_NOTIFICATION_ID = 1002

        fun ensureNotificationChannelExists(context: Context) {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                val nm = context.getSystemService(Context.NOTIFICATION_SERVICE) as? NotificationManager ?: return
                val activeChannel = NotificationChannel(
                    CHANNEL_ID,
                    "Živé odjezdy MHD na zamykací obrazovce",
                    NotificationManager.IMPORTANCE_DEFAULT
                ).apply {
                    description = "Ukazuje živé odjezdy spojů při chůzi a přiblížení k zastávce"
                    lockscreenVisibility = Notification.VISIBILITY_PUBLIC
                    setShowBadge(false)
                    setSound(null, null)
                    enableVibration(false)
                }
                nm.createNotificationChannel(activeChannel)
            }
        }

        const val ACTION_START = "cz.plzen.stihamto.ACTION_START" // Ruční spuštění v aplikaci
        const val ACTION_START_AUTO = "cz.plzen.stihamto.ACTION_START_AUTO" // Automatické spuštění (Geofence)
        const val ACTION_WAKE_UP = "cz.plzen.stihamto.ACTION_WAKE_UP" // Probuzení ze spánku (Odchod z Wi-Fi / Chůze / Tlačítko)
        const val ACTION_SLEEP_STANDBY = "cz.plzen.stihamto.ACTION_SLEEP_STANDBY" // Přechod do tichého spánku (dočasné pozastavení)
        const val ACTION_REFRESH = "cz.plzen.stihamto.ACTION_REFRESH"
        const val ACTION_TOGGLE_TRAM = "cz.plzen.stihamto.ACTION_TOGGLE_TRAM"
        const val ACTION_NEXT_STOP = "cz.plzen.stihamto.ACTION_NEXT_STOP"
        const val ACTION_STOP = "cz.plzen.stihamto.ACTION_STOP" // Úplné vypnutí služby (musí se pak zapnout ručně)

        // Automatické přechody z Google Activity Recognition (Chůze, Klid, Jízda v MHD)
        const val ACTION_WALK_DETECTED = "cz.plzen.stihamto.ACTION_WALK_DETECTED"
        const val ACTION_STILL_DETECTED = "cz.plzen.stihamto.ACTION_STILL_DETECTED"
        const val ACTION_IN_VEHICLE_DETECTED = "cz.plzen.stihamto.ACTION_IN_VEHICLE_DETECTED"
        const val ACTION_EXIT_VEHICLE_DETECTED = "cz.plzen.stihamto.ACTION_EXIT_VEHICLE_DETECTED"

        const val BROADCAST_LIVE_UPDATE = "cz.plzen.stihamto.BROADCAST_LIVE_UPDATE"

        // Perimetr pro vyčkávání na spoj u zastávky (včetně odchylek městské GPS a rozlehlých nástupišť): 140 m
        private const val STOP_WAITING_RADIUS_METERS = 140

        // 1. Timeout mimo dosah zastávky (> 140 m), pokud se GPS neposune o aspoň 40 m: 3 minuty (180 s)
        //    (řeší chůzi po obchodě, kanceláři nebo bytě na jednom místě)
        private const val IDLE_TIMEOUT_AWAY_MS = 180_000L

        // 2. Timeout čekání v perimetru zastávky (<= 140 m): 8 minut (480 s)
        private const val IDLE_TIMEOUT_AT_STOP_MS = 480_000L

        // 3. Ochranná pauza po jízdě tramvají/busem (> 22 km/h): 20 minut
        //    (aby se po vystoupení z MHD a cestě do práce notifikace hned znovu nezapínala)
        private const val POST_RIDE_COOLDOWN_MS = 20 * 60 * 1000L

        // 4. Ochranná pauza po uspání na místě v obchodě/budově (kde člověk chodí, ale GPS stojí): 12 minut
        private const val INDOOR_SHOP_COOLDOWN_MS = 12 * 60 * 1000L

        // 5. Počet souvislých kroků potřebných k automatickému probuzení ze spánku: 30 kroků
        const val STEPS_TO_WAKE_UP = 30

        // Minimální posun GPS (v metrech), který se počítá jako skutečný přesun po ulici (ne jen chůze po obchodě)
        private const val MIN_GPS_DISPLACEMENT_METERS = 40.0

        @Volatile
        var isRunning: Boolean = false
            private set

        @Volatile
        var isSleepingStandby: Boolean = false
            private set

        @Volatile
        var isManualForceOnWifi: Boolean = false
            private set

        @Volatile
        var walkingWifiOverrideUntilMs: Long = 0L
            private set

        @Volatile
        var postRideCooldownUntilMs: Long = 0L
            private set

        @Volatile
        var indoorPlaceCooldownUntilMs: Long = 0L
            private set

        @Volatile
        var indoorCooldownCause: StandbyCooldownCause = StandbyCooldownCause.NONE
            private set

        fun setIndoorCooldown(untilMs: Long, cause: StandbyCooldownCause) {
            indoorPlaceCooldownUntilMs = untilMs
            indoorCooldownCause = cause
        }

        fun clearIndoorCooldown() {
            indoorPlaceCooldownUntilMs = 0L
            indoorCooldownCause = StandbyCooldownCause.NONE
        }

        fun isWalkingWifiOverrideActive(): Boolean {
            return System.currentTimeMillis() < walkingWifiOverrideUntilMs
        }

        fun getRemainingPostRideCooldownMin(): Int {
            val rem = postRideCooldownUntilMs - System.currentTimeMillis()
            return if (rem > 0) ((rem + 59_999L) / 60_000L).toInt() else 0
        }

        fun getRemainingIndoorCooldownMin(): Int {
            val rem = indoorPlaceCooldownUntilMs - System.currentTimeMillis()
            return if (rem > 0) ((rem + 59_999L) / 60_000L).toInt() else 0
        }

        @Volatile
        var latestResult: LiveDeparturesResult? = null
            private set

        @Volatile
        var lastLat: Double = 49.7478
            private set

        @Volatile
        var lastLon: Double = 13.3746
            private set

        @Volatile
        var sessionStepCount: Int = 0
            private set

        @Volatile
        var standbyStepsProgress: Int = 0
            private set

        @Volatile
        var lastSleepReason: String? = null
            private set
    }

    private val coroutineExceptionHandler = CoroutineExceptionHandler { _, throwable ->
        android.util.Log.e("LiveDeparturesService", "Nezachycená výjimka v serviceScope: ${throwable.message}", throwable)
    }
    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.Main + coroutineExceptionHandler)
    private var refreshJob: Job? = null
    private var standbyWatcherJob: Job? = null
    private lateinit var fusedLocationClient: FusedLocationProviderClient
    private var locationCallback: LocationCallback? = null
    private var standbyLocationCallback: LocationCallback? = null
    private var standbyAnchorLat: Double = Double.NaN
    private var standbyAnchorLon: Double = Double.NaN
    private var sensorManager: SensorManager? = null

    private var prevLat: Double? = null
    private var prevLon: Double? = null

    // Referenční bod pro měření skutečného posunu GPS o >= 40 m (odhalí chůzi na místě v obchodě)
    private var anchorLat: Double = 49.7478
    private var anchorLon: Double = 13.3746
    private var lastGpsProgressTimestampMs: Long = System.currentTimeMillis()

    // Sledování rychlosti jízdy (GPS Doppler + výpočet z posunu mezi body a za 15-30s okno)
    private var lastFixLat: Double = Double.NaN
    private var lastFixLon: Double = Double.NaN
    private var lastFixTimeMs: Long = 0L
    private val recentGpsFixes = ArrayDeque<Triple<Long, Double, Double>>()
    private var highSpeedCount: Int = 0

    // Sledování procházky kolem zastávek (per-zastávka minimální vzdálenost + míjené zastávky)
    private data class StopVisitState(
        val stop: PmdpStop,
        var minAirMeters: Int,
        var minTimeMs: Long
    )
    private val visitedStopsInSession = mutableMapOf<Int, StopVisitState>()
    private val passedStopsOnFoot = mutableSetOf<Int>()
    private var initialNearestStop: PmdpStop? = null
    private var initialNearestAirMeters: Int = Int.MAX_VALUE
    private var sessionActivatedAtMs: Long = System.currentTimeMillis()

    private var nearbyStopCycleIndex: Int = -1

    // Chytré přestupní okno (V5): po výstupu z MHD zobrazí návazné spoje, ale pokud uživatel odchází od zastávky
    // do práce/domů (> 85 m od místa výstupu), okamžitě přejde do klidového režimu!
    private var transferWindowActive: Boolean = false
    private var transferExitLat: Double = Double.NaN
    private var transferExitLon: Double = Double.NaN
    private var transferStartedAtMs: Long = 0L

    // Sledování kroků (hardwarový čítač + akcelerometr)
    private var lastStepCounterValue: Float? = null
    private var lastStepRecordedMs: Long = 0L
    private var lastStandbyStepTimestampMs: Long = 0L
    private val recentStepTimestampsMs = ArrayDeque<Long>()
    private var lastAccelPeakMs: Long = 0L
    private var accelCadenceStreak: Int = 0
    private var wasAboveThreshold: Boolean = false
    private var stepSensorsRegistered: Boolean = false

    // Samsung Galaxy S21 / Android Doze ochrana při chůzi na zastávku s telefonem v kapse
    private var wakeLock: PowerManager.WakeLock? = null

    // Okamžitý přepočet notifikace na zamykací obrazovce při rozsvícení displeje (eliminuje zamrznutí v Doze)
    private var screenStateReceiverRegistered = false
    private val screenStateReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            val action = intent?.action ?: return
            if (action == Intent.ACTION_SCREEN_ON || action == Intent.ACTION_USER_PRESENT) {
                if (!isSleepingStandby && isRunning) {
                    applyLocalCountdownTick(forceNotify = true)
                    val cached = latestResult
                    if (cached == null || (System.currentTimeMillis() - cached.fetchedEpochMs) > 35_000L) {
                        triggerImmediateRefresh()
                    }
                }
            }
        }
    }

    private fun acquireWalkingWakeLock() {
        try {
            if (wakeLock == null) {
                val pm = getSystemService(Context.POWER_SERVICE) as? PowerManager
                wakeLock = pm?.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "StihamTo::WalkingWakeLock")?.apply {
                    setReferenceCounted(false)
                }
            }
            if (wakeLock?.isHeld != true) {
                wakeLock?.acquire(2 * 60 * 1000L) // 2 minuty maximální běh pro chůzi na zastávku (ochrana baterie)
            }
        } catch (_: Throwable) {
        }
    }

    private fun releaseWalkingWakeLock() {
        try {
            if (wakeLock?.isHeld == true) {
                wakeLock?.release()
            }
        } catch (_: Throwable) {
        }
    }

    override fun onCreate() {
        super.onCreate()
        CrashHandler.init(this)
        isRunning = true

        // Obnova perzistovaného stavu (pro případ, že Android zabil proces a restartoval službu)
        val (savedLat, savedLon) = PmdpRepository.getLastKnownLocation(this)
        lastLat = savedLat
        lastLon = savedLon
        anchorLat = savedLat
        anchorLon = savedLon

        val persisted = PmdpRepository.loadStandbyPersistence(this)
        isSleepingStandby = persisted.first
        lastSleepReason = persisted.second
        postRideCooldownUntilMs = persisted.third
        indoorPlaceCooldownUntilMs = persisted.fourth
        indoorCooldownCause = try {
            StandbyCooldownCause.valueOf(persisted.fifth)
        } catch (_: Exception) {
            StandbyCooldownCause.NONE
        }

        sessionStepCount = 0
        standbyStepsProgress = 0
        lastGpsProgressTimestampMs = System.currentTimeMillis()
        createNotificationChannels()
        SmartTriggerMonitor.ensureWifiCallbackRegistered(this)
        ActivityTransitionManager.registerTransitions(this)
        fusedLocationClient = LocationServices.getFusedLocationProviderClient(this)
        registerScreenStateReceiver()
    }

    private fun registerScreenStateReceiver() {
        if (screenStateReceiverRegistered) return
        try {
            val filter = IntentFilter().apply {
                addAction(Intent.ACTION_SCREEN_ON)
                addAction(Intent.ACTION_USER_PRESENT)
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                registerReceiver(screenStateReceiver, filter, Context.RECEIVER_NOT_EXPORTED)
            } else {
                registerReceiver(screenStateReceiver, filter)
            }
            screenStateReceiverRegistered = true
        } catch (_: Throwable) {
        }
    }

    private fun unregisterScreenStateReceiver() {
        if (!screenStateReceiverRegistered) return
        try {
            unregisterReceiver(screenStateReceiver)
        } catch (_: Throwable) {
        }
        screenStateReceiverRegistered = false
    }

    private fun persistCurrentStandbyState() {
        PmdpRepository.saveStandbyPersistence(
            context = this,
            isSleeping = isSleepingStandby,
            sleepReason = lastSleepReason,
            postRideUntilMs = postRideCooldownUntilMs,
            indoorUntilMs = indoorPlaceCooldownUntilMs,
            indoorCauseName = indoorCooldownCause.name
        )
    }

    private fun registerStepSensors() {
        if (stepSensorsRegistered) return
        try {
            lastStepCounterValue = null
            sensorManager = getSystemService(Context.SENSOR_SERVICE) as? SensorManager
            val stepCounter = sensorManager?.getDefaultSensor(Sensor.TYPE_STEP_COUNTER)
            val stepDetector = sensorManager?.getDefaultSensor(Sensor.TYPE_STEP_DETECTOR)

            var registeredHardwarePedometer = false
            // DŮLEŽITÉ: Registrujeme POUZE JEDEN senzor (přednost má TYPE_STEP_COUNTER),
            // aby nedocházelo ke zdvojenému započítávání každého kroku!
            if (stepCounter != null) {
                sensorManager?.registerListener(this, stepCounter, SensorManager.SENSOR_DELAY_NORMAL)
                registeredHardwarePedometer = true
            } else if (stepDetector != null) {
                sensorManager?.registerListener(this, stepDetector, SensorManager.SENSOR_DELAY_NORMAL)
                registeredHardwarePedometer = true
            }

            // Akcelerometr (který probouzí CPU) zapneme POUZE na starých telefonech, které nemají HW krokoměr!
            if (!registeredHardwarePedometer) {
                val accel = sensorManager?.getDefaultSensor(Sensor.TYPE_ACCELEROMETER)
                if (accel != null) {
                    sensorManager?.registerListener(this, accel, SensorManager.SENSOR_DELAY_NORMAL)
                }
            }
            stepSensorsRegistered = true
        } catch (_: Throwable) {
        }
    }

    private fun unregisterStepSensors() {
        if (!stepSensorsRegistered) return
        try {
            sensorManager?.unregisterListener(this)
        } catch (_: Throwable) {
        }
        stepSensorsRegistered = false
        lastStepCounterValue = null
    }

    override fun onSensorChanged(event: SensorEvent?) {
        if (event == null) return
        val now = System.currentTimeMillis()

        when (event.sensor.type) {
            Sensor.TYPE_STEP_DETECTOR -> {
                onHumanStepDetected(now, 1)
            }
            Sensor.TYPE_STEP_COUNTER -> {
                val totalSteps = event.values[0]
                val prev = lastStepCounterValue
                lastStepCounterValue = totalSteps
                if (prev != null && totalSteps > prev) {
                    val delta = (totalSteps - prev).toInt().coerceIn(1, 100)
                    onHumanStepDetected(now, delta)
                }
            }
            Sensor.TYPE_ACCELEROMETER -> {
                val x = event.values[0]
                val y = event.values[1]
                val z = event.values[2]
                val mag = sqrt((x * x + y * y + z * z).toDouble())

                if (mag > 11.55 && !wasAboveThreshold) {
                    wasAboveThreshold = true
                    val dt = now - lastAccelPeakMs
                    lastAccelPeakMs = now
                    if (dt in 310L..880L) {
                        accelCadenceStreak++
                        if (accelCadenceStreak >= 3) {
                            onHumanStepDetected(now, 1)
                        }
                    } else if (dt > 1600L) {
                        accelCadenceStreak = 1
                    }
                } else if (mag < 10.1) {
                    wasAboveThreshold = false
                }
            }
        }
    }

    private fun onHumanStepDetected(now: Long, count: Int) {
        if (now - lastStepRecordedMs < 250L && count == 1) return
        lastStepRecordedMs = now

        repeat(count.coerceAtMost(15)) {
            recentStepTimestampsMs.addLast(now)
        }
        while (recentStepTimestampsMs.isNotEmpty() && now - recentStepTimestampsMs.first() > 20_000L) {
            recentStepTimestampsMs.removeFirst()
        }

        // POZNÁMKA: Samotné kroky NYNÍ NERESETUJÍ `lastGpsProgressTimestampMs`!
        // Proč? Protože v obchodě (nebo v bytě) člověk chodí mezi regály, ale GPS stojí na místě.
        // Odpočet 3 minut resetuje pouze reálný posun GPS o >= 40 metrů!
        sessionStepCount += count

        if (isSleepingStandby) {
            // 1. Pokud běží 20minutová ochranná pauza po výstupu z tramvaje/busu, chůze ze zastávky do práce nás NEPROBUDÍ!
            if (now < postRideCooldownUntilMs) {
                return
            }

            // 2. Pokud jsme se před chvílí uspali kvůli chození na místě v obchodě/budově, držíme klid
            if (now < indoorPlaceCooldownUntilMs) {
                return
            }

            // Pokud mezi kroky uplynulo víc než 35 sekund klidu, počítáme souvislou chůzi znovu od 0
            if (now - lastStandbyStepTimestampMs > 35_000L) {
                standbyStepsProgress = 0
            }
            lastStandbyStepTimestampMs = now
            standbyStepsProgress += count

            // Na domácí Wi-Fi vyžadujeme zvolený práh souvislých kroků (aby se appka neprobouzela při pár krocích po bytě,
            // ale spolehlivě se probudila při odchodu z domu nebo chůzi po ulici).
            // Venku mimo Wi-Fi stačí rychlých 10-15 kroků (V6: okamžitý předstih před pomalým geofencem)!
            val userStepThreshold = PmdpRepository.getWakeUpStepsThreshold(this)
            val isWifi = PmdpRepository.isSleepOnWifiEnabled(this) && SmartTriggerMonitor.isConnectedToHomeWifi(this)
            val threshold = if (isWifi) userStepThreshold else maxOf(10, userStepThreshold / 3)

            if (standbyStepsProgress >= threshold) {
                walkingWifiOverrideUntilMs = now + 8 * 60 * 1000L
                acquireWalkingWakeLock()
                wakeUpFromStandby("Detekována souvislá chůze ($standbyStepsProgress kroků)", resetTemporaryPin = true)
            } else if (standbyStepsProgress % 5 == 0) {
                sendBroadcast(Intent(BROADCAST_LIVE_UPDATE).setPackage(packageName))
            }
        }
    }

    override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) {}

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            PmdpRepository.setServiceExplicitlyStopped(this, true)
            lastSleepReason = "Vypnuto úplně (pro spuštění zapni v aplikaci)"
            isSleepingStandby = false
            persistCurrentStandbyState()
            ActivityTransitionManager.unregisterTransitions(this)
            stopSelf()
            return START_NOT_STICKY
        }

        if (PmdpRepository.isServiceExplicitlyStopped(this) && intent == null) {
            stopSelf()
            return START_NOT_STICKY
        }

        PmdpRepository.setServiceExplicitlyStopped(this, false)
        try {
            val nm = getSystemService(Context.NOTIFICATION_SERVICE) as? NotificationManager
            nm?.cancel(FALLBACK_NOTIFICATION_ID)
        } catch (_: Throwable) {
        }

        // Zajištění splnění Android 8-14 kontraktu Foreground Service hned při přijetí jakéhokoliv startu,
        // čímž se stoprocentně eliminuje RemoteServiceException (ForegroundServiceDidNotStartInTimeException)
        val earlyNotif = if (isSleepingStandby || intent == null) {
            buildStandbyNotification(lastSleepReason ?: "Připraveno – probudí se při chůzi k zastávce")
        } else {
            latestResult?.let { buildNotificationFromResult(it) } ?: buildNotification(
                title = "Hledám nejbližší zastávku…",
                shortLine = "Počítám pěší trasu a odjezdy",
                subStatus = "Živě",
                expandedLines = listOf("Počítám pěší trasu a načítám odjezdy…")
            )
        }
        if (!startForegroundSafely(earlyNotif)) {
            return START_NOT_STICKY
        }

        // Pokud systém službu restartoval po zabití procesu (intent == null),
        // obnovíme perzistovaný stav (ve spánku zůstane spát a nezapne ve 3 ráno plnou GPS!)
        if (intent == null) {
            if (isSleepingStandby ||
                System.currentTimeMillis() < postRideCooldownUntilMs ||
                System.currentTimeMillis() < indoorPlaceCooldownUntilMs ||
                (PmdpRepository.isSleepOnWifiEnabled(this) && SmartTriggerMonitor.isConnectedToHomeWifi(this))
            ) {
                enterStandbySleep(lastSleepReason ?: "Připraveno – probudí se při chůzi k zastávce")
                return START_STICKY
            }
        }

        when (intent?.action) {
            ACTION_SLEEP_STANDBY -> {
                val reason = intent.getStringExtra("sleep_reason") ?: "Uspáno – probudí se při chůzi k zastávce"
                clearIndoorCooldown()
                enterStandbySleep(reason)
                return START_STICKY
            }
            ACTION_WAKE_UP -> {
                val reason = intent.getStringExtra("trigger_reason") ?: "Probuzeno"
                val isManual = intent.getBooleanExtra("is_manual", false)
                if (isManual) {
                    postRideCooldownUntilMs = 0L
                    clearIndoorCooldown()
                    isManualForceOnWifi = SmartTriggerMonitor.isConnectedToWifi(this)
                } else if (System.currentTimeMillis() < postRideCooldownUntilMs) {
                    // Automatický trigger během 20min pauzy po jízdě MHD ignorujeme
                    return START_STICKY
                }
                wakeUpFromStandby(reason, resetTemporaryPin = !isManual)
                return START_STICKY
            }
            ACTION_WALK_DETECTED -> {
                val now = System.currentTimeMillis()
                if (now < postRideCooldownUntilMs || now < indoorPlaceCooldownUntilMs) {
                    return START_STICKY
                }
                // Uživatel se rozešel (odchod z kanceláře / bytu) – přebíjí Wi-Fi, zapíná WakeLock
                walkingWifiOverrideUntilMs = now + 8 * 60 * 1000L
                isSleepingStandby = false
                lastSleepReason = null
                acquireWalkingWakeLock()
                wakeUpFromStandby("Detekována chůze k zastávce", resetTemporaryPin = true)
                return START_STICKY
            }
            ACTION_STILL_DETECTED -> {
                val now = System.currentTimeMillis()
                val dist = latestResult?.primaryStop?.distanceMeters ?: 500
                // Pokud stojíme v perimetru zastávky (<= 140 m), čekáme na tramvaj/autobus! Neuspat do budovy!
                if (dist <= STOP_WAITING_RADIUS_METERS) {
                    return START_STICKY
                }
                if (PmdpRepository.isSleepOnWifiEnabled(this) && SmartTriggerMonitor.isConnectedToHomeWifi(this) && !isManualForceOnWifi) {
                    releaseWalkingWakeLock()
                    clearIndoorCooldown()
                    enterStandbySleep("Doma na Wi-Fi (probudí se při odchodu)")
                } else if (!isManualForceOnWifi && (now - sessionActivatedAtMs) > 180_000L) {
                    releaseWalkingWakeLock()
                    setIndoorCooldown(now + 10 * 60 * 1000L, StandbyCooldownCause.INDOOR_IDLE)
                    enterStandbySleep("V budově na místě (klid ještě 10 min)")
                }
                return START_STICKY
            }
            ACTION_IN_VEHICLE_DETECTED -> {
                val now = System.currentTimeMillis()
                val rideCooldownMin = PmdpRepository.getPostRideCooldownMinutes(this)
                postRideCooldownUntilMs = if (rideCooldownMin > 0) now + rideCooldownMin * 60_000L else 0L
                transferWindowActive = false
                clearIndoorCooldown()
                releaseWalkingWakeLock()
                val msg = if (rideCooldownMin > 0) "V jedoucí MHD (klid ještě $rideCooldownMin min)" else "Uspáno v jedoucí MHD"
                enterStandbySleep(msg)
                return START_STICKY
            }
            ACTION_EXIT_VEHICLE_DETECTED -> {
                val now = System.currentTimeMillis()
                // V5: Chytré přestupní okno! Když uživatel vystoupí z tramvaje/busu, neuspíme službu naslepo,
                // pokud stojí přímo v přestupním uzlu (<= 220 m od zastávky). Zobrazíme návazné spoje a jakmile
                // se od zastávky vzdálí do práce/domů (> 85 m) nebo uplyne 3,5 minuty, teprve zapneme pauzu po jízdě!
                val nearestStop = PmdpRepository.findNearestStops(this, lastLat, lastLon, limit = 1).firstOrNull()
                if (nearestStop != null && nearestStop.distanceMeters <= 220 &&
                    !(PmdpRepository.isSleepOnWifiEnabled(this) && SmartTriggerMonitor.isConnectedToHomeWifi(this))
                ) {
                    postRideCooldownUntilMs = 0L
                    clearIndoorCooldown()
                    transferWindowActive = true
                    transferExitLat = lastLat
                    transferExitLon = lastLon
                    transferStartedAtMs = now
                    acquireWalkingWakeLock()
                    wakeUpFromStandby("Přestup MHD – načítám návazné spoje", resetTemporaryPin = true)
                } else {
                    val rideCooldownMin = PmdpRepository.getPostRideCooldownMinutes(this)
                    postRideCooldownUntilMs = if (rideCooldownMin > 0) now + rideCooldownMin * 60_000L else 0L
                    transferWindowActive = false
                    clearIndoorCooldown()
                    releaseWalkingWakeLock()
                    val msg = if (rideCooldownMin > 0) "Vystoupeno z MHD (klid ještě $rideCooldownMin min)" else "Vystoupeno z MHD (klidový režim)"
                    enterStandbySleep(msg)
                }
                return START_STICKY
            }
            ACTION_TOGGLE_TRAM -> {
                lastGpsProgressTimestampMs = System.currentTimeMillis()
                val curr = PmdpRepository.getPreferredTraction(this)
                val next = if (curr == "tram") "all" else "tram"
                PmdpRepository.setPreferredTraction(this, next)
                triggerImmediateRefresh()
                return START_STICKY
            }
            ACTION_NEXT_STOP -> {
                lastGpsProgressTimestampMs = System.currentTimeMillis()
                val nearby = latestResult?.nearbyStops ?: emptyList()
                if (nearby.isNotEmpty()) {
                    nearbyStopCycleIndex++
                    if (nearbyStopCycleIndex >= minOf(3, nearby.size)) {
                        nearbyStopCycleIndex = -1
                        PmdpRepository.setPinnedStopCis(this, null)
                        PmdpRepository.setMultiStopMode(this, true)
                    } else {
                        val picked = nearby[nearbyStopCycleIndex]
                        PmdpRepository.setPinnedStopCis(this, picked.stop.cisNumber)
                    }
                    triggerImmediateRefresh()
                }
                return START_STICKY
            }
            ACTION_REFRESH -> {
                lastGpsProgressTimestampMs = System.currentTimeMillis()
                if (isSleepingStandby) {
                    postRideCooldownUntilMs = 0L
                    clearIndoorCooldown()
                    wakeUpFromStandby("Ruční obnovení", resetTemporaryPin = false)
                } else {
                    triggerImmediateRefresh()
                }
                return START_STICKY
            }
            ACTION_START -> {
                // Ruční zapnutí z aplikace nebo widgetu – ruší všechny ochranné pauzy
                isManualForceOnWifi = SmartTriggerMonitor.isConnectedToWifi(this)
                postRideCooldownUntilMs = 0L
                clearIndoorCooldown()
                lastSleepReason = null
                PmdpRepository.clearAutoSleptLocation(this)
                if (!isSleepingStandby && locationCallback != null) {
                    persistCurrentStandbyState()
                    triggerImmediateRefresh()
                    return START_STICKY
                }
                isSleepingStandby = false
                standbyStepsProgress = 0
                resetSessionMovementTracking(resetTemporaryPin = false)
                persistCurrentStandbyState()
            }
            ACTION_START_AUTO -> {
                // Pokud už služba aktivně běží, automatický trigger (např. z geofence) nesmí
                // resetovat měření rychlosti/chůze ani přepisovat notifikaci textem "Hledám nejbližší zastávku…"!
                if (!isSleepingStandby && locationCallback != null) {
                    return START_STICKY
                }
                isManualForceOnWifi = false
                val now = System.currentTimeMillis()
                if (now < postRideCooldownUntilMs) {
                    if (!isSleepingStandby) {
                        val remMin = getRemainingPostRideCooldownMin()
                        val reason = "Po jízdě MHD (klid ještě $remMin min)"
                        startForegroundSafely(buildStandbyNotification(reason))
                        enterStandbySleep(reason)
                    }
                    return START_STICKY
                }
                if (now < indoorPlaceCooldownUntilMs) {
                    if (!isSleepingStandby) {
                        val remMin = getRemainingIndoorCooldownMin()
                        val reason = when (indoorCooldownCause) {
                            StandbyCooldownCause.WALK -> "Procházka městem (klid ještě $remMin min)"
                            StandbyCooldownCause.INDOOR_IDLE -> "V budově na místě (klid ještě $remMin min)"
                            StandbyCooldownCause.STOP_IDLE -> "Čekání na zastávce (klid ještě $remMin min)"
                            else -> "Dočasná pauza (klid ještě $remMin min)"
                        }
                        startForegroundSafely(buildStandbyNotification(reason))
                        enterStandbySleep(reason)
                    }
                    return START_STICKY
                }
                if (PmdpRepository.isSleepOnWifiEnabled(this) && SmartTriggerMonitor.isConnectedToHomeWifi(this)) {
                    if (!isSleepingStandby) {
                        val reason = "Doma na Wi-Fi (probudí se při odchodu)"
                        startForegroundSafely(buildStandbyNotification(reason))
                        enterStandbySleep(reason)
                    }
                    return START_STICKY
                }
                isSleepingStandby = false
                standbyStepsProgress = 0
                resetSessionMovementTracking(resetTemporaryPin = true)
                lastSleepReason = null
                persistCurrentStandbyState()
            }
        }

        val initialNotif = latestResult?.let { buildNotificationFromResult(it) } ?: buildNotification(
            title = "Hledám nejbližší zastávku…",
            shortLine = "Počítám pěší trasu a odjezdy",
            subStatus = "Živě",
            expandedLines = listOf("Počítám pěší trasu a načítám odjezdy…")
        )

        startForegroundSafely(initialNotif)
        startLocationUpdates()
        startPeriodicRefreshLoop()

        return START_STICKY
    }

    private fun resetSessionMovementTracking(resetTemporaryPin: Boolean = false) {
        if (resetTemporaryPin) {
            nearbyStopCycleIndex = -1
            PmdpRepository.setPinnedStopCis(this, null)
            PmdpRepository.setMultiStopMode(this, true)
        }
        anchorLat = lastLat
        anchorLon = lastLon
        val now = System.currentTimeMillis()
        lastGpsProgressTimestampMs = now
        sessionActivatedAtMs = now
        lastFixLat = Double.NaN
        lastFixLon = Double.NaN
        lastFixTimeMs = 0L
        recentGpsFixes.clear()
        highSpeedCount = 0
        visitedStopsInSession.clear()
        passedStopsOnFoot.clear()
        initialNearestStop = null
        initialNearestAirMeters = Int.MAX_VALUE
        sessionStepCount = 0
    }

    private fun startForegroundSafely(notif: Notification): Boolean {
        return try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                startForeground(
                    NOTIFICATION_ID,
                    notif,
                    ServiceInfo.FOREGROUND_SERVICE_TYPE_LOCATION
                )
            } else {
                startForeground(NOTIFICATION_ID, notif)
            }
            true
        } catch (t: Throwable) {
            android.util.Log.e("LiveDeparturesService", "Nelze spustit Foreground Service (chybí poloha na pozadí / Android 14 restrikce): ${t.message}")
            try {
                stopSelf()
            } catch (_: Throwable) {
            }
            false
        }
    }

    /**
     * Přepne službu do tichého spánku (Standby):
     * - Zastaví GPS a dotazy na PMDP (0 % spotřeba dat a GPS)
     * - Skryje notifikaci ze zamykací obrazovky (IMPORTANCE_MIN + VISIBILITY_SECRET)
     * - Nechá běžet krokoměr (30 kroků mimo Wi-Fi) a hlídač odpojení Wi-Fi
     */
    private fun enterStandbySleep(reason: String) {
        isSleepingStandby = true
        isManualForceOnWifi = false
        standbyStepsProgress = 0
        lastSleepReason = reason
        persistCurrentStandbyState()

        refreshJob?.cancel()
        stopLocationUpdates()

        val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        val onHomeWifiNow = PmdpRepository.isSleepOnWifiEnabled(this) && SmartTriggerMonitor.isConnectedToHomeWifi(this)
        val inCooldownNow = System.currentTimeMillis() < postRideCooldownUntilMs ||
                System.currentTimeMillis() < indoorPlaceCooldownUntilMs
        if (inCooldownNow) {
            unregisterStepSensors()
            stopStandbyOutdoorWatch()
        } else {
            registerStepSensors()
            if (!onHomeWifiNow) {
                startStandbyOutdoorWatch()
            } else {
                stopStandbyOutdoorWatch()
            }
        }

        val standbyNotif = buildStandbyNotification(reason)
        try {
            nm.notify(NOTIFICATION_ID, standbyNotif)
        } catch (_: Throwable) {
        }
        sendBroadcast(Intent(BROADCAST_LIVE_UPDATE).setPackage(packageName))

        // Hlídač ve spánku: odpočítává zbývající minuty pauzy po jízdě / procházce a po jejím uplynutí
        // zresetuje backend, přepíše notifikaci na "Připraveno" a zapne detekci chůze k zastávce!
        standbyWatcherJob?.cancel()
        standbyWatcherJob = serviceScope.launch {
            var wasHomeWifi = SmartTriggerMonitor.isConnectedToHomeWifi(this@LiveDeparturesService)
            var wasInCooldown = inCooldownNow

            while (isActive && isSleepingStandby) {
                try {
                    delay(15_000L)
                    val nowHomeWifi = SmartTriggerMonitor.isConnectedToHomeWifi(this@LiveDeparturesService)
                    val nowAnyWifi = SmartTriggerMonitor.isConnectedToWifi(this@LiveDeparturesService)
                    if (wasHomeWifi && !nowAnyWifi) {
                        val nowMs = System.currentTimeMillis()
                        if (nowMs >= postRideCooldownUntilMs) {
                            // Uživatel právě vyšel před dům mimo dosah domácí Wi-Fi!
                            clearIndoorCooldown()
                            PmdpRepository.clearAutoSleptLocation(this@LiveDeparturesService)
                            wakeUpFromStandby("Odchod z Wi-Fi", resetTemporaryPin = true)
                            break
                        }
                    }
                    wasHomeWifi = nowHomeWifi

                    val remRideMin = getRemainingPostRideCooldownMin()
                    val remIndoorMin = getRemainingIndoorCooldownMin()
                    val stillInCooldown = remRideMin > 0 || remIndoorMin > 0
                    val sleepOnWifiActive = PmdpRepository.isSleepOnWifiEnabled(this@LiveDeparturesService) && nowHomeWifi

                    if (stillInCooldown) {
                        wasInCooldown = true
                        unregisterStepSensors()
                        stopStandbyOutdoorWatch()
                        val updatedReason = if (remRideMin > 0) {
                            "Po jízdě MHD (klid ještě $remRideMin min)"
                        } else {
                            when (indoorCooldownCause) {
                                StandbyCooldownCause.WALK -> "Procházka městem (klid ještě $remIndoorMin min)"
                                StandbyCooldownCause.INDOOR_IDLE -> "V budově na místě (klid ještě $remIndoorMin min)"
                                StandbyCooldownCause.STOP_IDLE -> "Čekání na zastávce (klid ještě $remIndoorMin min)"
                                else -> "Dočasná pauza (klid ještě $remIndoorMin min)"
                            }
                        }
                        if (updatedReason != lastSleepReason) {
                            lastSleepReason = updatedReason
                            persistCurrentStandbyState()
                            try {
                                nm.notify(NOTIFICATION_ID, buildStandbyNotification(updatedReason))
                            } catch (_: Throwable) {
                            }
                            sendBroadcast(Intent(BROADCAST_LIVE_UPDATE).setPackage(packageName))
                        }
                    } else {
                        if (wasInCooldown || postRideCooldownUntilMs > 0L || indoorPlaceCooldownUntilMs > 0L) {
                            // Ochranné okno po jízdě nebo procházce právě skončilo -> kompletní reset backendu!
                            wasInCooldown = false
                            postRideCooldownUntilMs = 0L
                            clearIndoorCooldown()
                            standbyStepsProgress = 0
                            visitedStopsInSession.clear()
                            passedStopsOnFoot.clear()
                            PmdpRepository.clearAutoSleptLocation(this@LiveDeparturesService)
                            persistCurrentStandbyState()
                        }

                        val readyReason = if (sleepOnWifiActive) {
                            "Doma na Wi-Fi (probudí se při odchodu)"
                        } else {
                            "Připraveno – probudí se při chůzi k zastávce"
                        }

                        if (readyReason != lastSleepReason) {
                            lastSleepReason = readyReason
                            persistCurrentStandbyState()
                            try {
                                nm.notify(NOTIFICATION_ID, buildStandbyNotification(readyReason))
                            } catch (_: Throwable) {
                            }
                            sendBroadcast(Intent(BROADCAST_LIVE_UPDATE).setPackage(packageName))
                        }

                        registerStepSensors()
                        if (sleepOnWifiActive) {
                            stopStandbyOutdoorWatch()
                        } else {
                            startStandbyOutdoorWatch()
                        }
                    }
                } catch (t: Throwable) {
                    if (t is CancellationException) throw t
                    android.util.Log.e("LiveDeparturesService", "Chyba v standbyWatcherJob: ${t.message}", t)
                }
            }
        }
    }

    /**
     * Nízkoenergetický venkovní hlídač pohybu ve spánku (Standby) mimo Wi-Fi a mimo pauzu:
     * Používá pouze buňky sítě / Wi-Fi v okolí (PRIORITY_BALANCED_POWER_ACCURACY, 0 % satelitní GPS).
     * Jakmile po skončení pauzy zaznamená chůzi venku o >= 45 m poblíž zastávky, probudí živou notifikaci
     * (funguje 100% spolehlivě i na telefonech Xiaomi/HyperOS, které v kapse uspávají krokoměr).
     */
    @SuppressLint("MissingPermission")
    private fun startStandbyOutdoorWatch() {
        if (standbyLocationCallback != null) return
        standbyAnchorLat = Double.NaN
        standbyAnchorLon = Double.NaN

        val request = LocationRequest.Builder(Priority.PRIORITY_BALANCED_POWER_ACCURACY, 25_000L)
            .setMinUpdateIntervalMillis(12_000L)
            .setMinUpdateDistanceMeters(25f)
            .setWaitForAccurateLocation(false)
            .build()

        standbyLocationCallback = object : LocationCallback() {
            override fun onLocationResult(result: LocationResult) {
                if (!isSleepingStandby) return
                val now = System.currentTimeMillis()
                if (now < postRideCooldownUntilMs || now < indoorPlaceCooldownUntilMs) return
                if (PmdpRepository.isSleepOnWifiEnabled(this@LiveDeparturesService) &&
                    SmartTriggerMonitor.isConnectedToHomeWifi(this@LiveDeparturesService)
                ) {
                    return
                }

                val loc = result.lastLocation ?: return
                // Odmítneme nepřesné skoky sítě uvnitř budov (např. přepnutí vysílače s přesností > 65 m),
                // aby se aplikace v práci nebo doma bez Wi-Fi neprobouzela každých 15 minut!
                if (loc.hasAccuracy() && loc.accuracy > 65f) {
                    return
                }

                lastLat = loc.latitude
                lastLon = loc.longitude
                PmdpRepository.saveLastKnownLocation(this@LiveDeparturesService, loc.latitude, loc.longitude)

                // Pokud jsme stále na stejném místě (< 42 m), kde služba předtím sama usnula kvůli nečinnosti,
                // neprobouzíme ji z místního šumu polohy!
                if (PmdpRepository.isStillAtAutoSleptLocation(this@LiveDeparturesService, loc.latitude, loc.longitude)) {
                    return
                }

                if (standbyAnchorLat.isNaN() || standbyAnchorLon.isNaN()) {
                    standbyAnchorLat = loc.latitude
                    standbyAnchorLon = loc.longitude
                    GeofenceManager.registerNearbyGeofences(this@LiveDeparturesService, loc.latitude, loc.longitude)
                    return
                }

                // Ignorujeme přesun v rychlém vozidle (> 19 km/h)
                if (loc.hasSpeed() && loc.speed > 5.3f) {
                    standbyAnchorLat = loc.latitude
                    standbyAnchorLon = loc.longitude
                    return
                }

                val movedMeters = PmdpRepository.haversineMeters(
                    standbyAnchorLat,
                    standbyAnchorLon,
                    loc.latitude,
                    loc.longitude
                )
                if (movedMeters >= 55.0) {
                    val nearest = PmdpRepository.findNearestStops(
                        this@LiveDeparturesService,
                        loc.latitude,
                        loc.longitude,
                        limit = 1
                    ).firstOrNull()
                    if (nearest != null && nearest.distanceMeters <= 420) {
                        wakeUpFromStandby("Detekována chůze k zastávce", resetTemporaryPin = true)
                    } else {
                        standbyAnchorLat = loc.latitude
                        standbyAnchorLon = loc.longitude
                    }
                }
            }
        }

        try {
            fusedLocationClient.requestLocationUpdates(
                request,
                standbyLocationCallback!!,
                Looper.getMainLooper()
            )
        } catch (_: SecurityException) {
        }
    }

    private fun stopStandbyOutdoorWatch() {
        try {
            standbyLocationCallback?.let { fusedLocationClient.removeLocationUpdates(it) }
        } catch (_: Throwable) {
        }
        standbyLocationCallback = null
        standbyAnchorLat = Double.NaN
        standbyAnchorLon = Double.NaN
    }

    /**
     * Probudí službu ze spánku (Standby) zpět na zamykací obrazovku!
     */
    private fun wakeUpFromStandby(triggerReason: String, resetTemporaryPin: Boolean = false) {
        isSleepingStandby = false
        standbyStepsProgress = 0
        lastSleepReason = null
        resetSessionMovementTracking(resetTemporaryPin = resetTemporaryPin)
        PmdpRepository.clearAutoSleptLocation(this)
        persistCurrentStandbyState()
        standbyWatcherJob?.cancel()
        unregisterStepSensors()
        stopStandbyOutdoorWatch()

        val wakeNotif = latestResult?.let {
            PmdpRepository.recalculateWithLiveCountdown(it, System.currentTimeMillis(), lastLat, lastLon)
                .let { fresh -> buildNotificationFromResult(fresh) }
        } ?: buildNotification(
            title = "Načítám odjezdy v okolí…",
            shortLine = triggerReason,
            subStatus = "Živě",
            expandedLines = listOf("Aktualizuji polohu a nejbližší spoje…")
        )
        if (!startForegroundSafely(wakeNotif)) return
        try {
            val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            nm.notify(NOTIFICATION_ID, wakeNotif)
        } catch (_: Throwable) {
        }

        sendBroadcast(Intent(BROADCAST_LIVE_UPDATE).setPackage(packageName))
        startLocationUpdates()
        startPeriodicRefreshLoop()
    }

    @SuppressLint("MissingPermission")
    private fun startLocationUpdates() {
        unregisterStepSensors()
        stopStandbyOutdoorWatch()
        if (locationCallback != null) return

        // Adaptivní GPS: při chůzi (Samsung v kapse) měříme častěji (5 s / 6 m), při klidu šetříme baterii (12 s / 12 m)
        val updateInterval = if (isWalkingWifiOverrideActive()) 5_000L else 12_000L
        val minDistance = if (isWalkingWifiOverrideActive()) 6f else 12f

        val request = LocationRequest.Builder(Priority.PRIORITY_HIGH_ACCURACY, updateInterval)
            .setMinUpdateIntervalMillis(3_500L)
            .setMinUpdateDistanceMeters(minDistance)
            .setWaitForAccurateLocation(false)
            .build()

        locationCallback = object : LocationCallback() {
            override fun onLocationResult(result: LocationResult) {
                val loc: Location = result.lastLocation ?: return
                val now = System.currentTimeMillis()

                // 1. Spolehlivá detekce jízdy v MHD / autě (odolná proti indoor driftu při sezení na židli):
                //    - Pokud je přesnost GPS horší než 16 m (např. indoor jitter), odmítáme počítat rychlost i posun.
                //    - Jízda v MHD vyžaduje kvalitní signál (accuracy <= 16 m), Dopplerovu rychlost >= 5.5 m/s (20 km/h)
                //      a reálný posun o >= 75 m v minimálně 2 po sobě jdoucích měřeních.
                val isAccurate = loc.hasAccuracy() && loc.accuracy <= 16f
                val gpsSpeedMps = if (loc.hasSpeed() && isAccurate) loc.speed.toDouble() else 0.0
                var stepSpeedMps = 0.0
                var distFromLastFix = 0.0
                if (!lastFixLat.isNaN() && !lastFixLon.isNaN() && lastFixTimeMs > 0L) {
                    distFromLastFix = PmdpRepository.haversineMeters(lastFixLat, lastFixLon, loc.latitude, loc.longitude)
                    val dtSec = (now - lastFixTimeMs) / 1000.0
                    if (dtSec in 2.0..30.0 && isAccurate) {
                        stepSpeedMps = distFromLastFix / dtSec
                    }
                }
                if (isAccurate) {
                    lastFixLat = loc.latitude
                    lastFixLon = loc.longitude
                    lastFixTimeMs = now
                    recentGpsFixes.addLast(Triple(now, loc.latitude, loc.longitude))
                }

                while (recentGpsFixes.isNotEmpty() && now - recentGpsFixes.first().first > 40_000L) {
                    recentGpsFixes.removeFirst()
                }

                var windowDistMeters = 0.0
                var windowSpeedMps = 0.0
                val baselineFix = recentGpsFixes.firstOrNull { (now - it.first) in 10_000L..38_000L }
                if (baselineFix != null && isAccurate) {
                    val wDtSec = (now - baselineFix.first) / 1000.0
                    windowDistMeters = PmdpRepository.haversineMeters(
                        baselineFix.second,
                        baselineFix.third,
                        loc.latitude,
                        loc.longitude
                    )
                    if (wDtSec > 0) {
                        windowSpeedMps = windowDistMeters / wDtSec
                    }
                }

                val effectiveSpeedMps = maxOf(gpsSpeedMps, stepSpeedMps)
                if (effectiveSpeedMps >= 4.2 && isAccurate) { // >= 15 km/h
                    highSpeedCount++
                } else if (effectiveSpeedMps < 2.5) {
                    highSpeedCount = maxOf(0, highSpeedCount - 1)
                }

                // V5: Ochrana proti vyhodnocení rychlého běhu (dobíhání spoje) jako jízdy tramvají/busem!
                // Pokud má uživatel za posledních 20 s vysokou kadenci kroků (>= 26 kroků) a rychlost je pod 6.4 m/s (23 km/h),
                // tak dobíhá spoj po svých -> NEUSPÁVAT!
                while (recentStepTimestampsMs.isNotEmpty() && now - recentStepTimestampsMs.first() > 20_000L) {
                    recentStepTimestampsMs.removeFirst()
                }
                val isRunningOnFoot = recentStepTimestampsMs.size >= 26 && effectiveSpeedMps < 6.4

                val isFastVehicle = isAccurate && !isRunningOnFoot && (
                    (highSpeedCount >= 2 && distFromLastFix >= 60.0) ||
                    (windowDistMeters >= 90.0 && windowSpeedMps >= 3.8)
                )

                if (isFastVehicle) {
                    val rideCooldownMin = PmdpRepository.getPostRideCooldownMinutes(this@LiveDeparturesService)
                    postRideCooldownUntilMs = if (rideCooldownMin > 0) now + rideCooldownMin * 60_000L else 0L
                    transferWindowActive = false
                    clearIndoorCooldown()
                    releaseWalkingWakeLock()
                    val msg = if (rideCooldownMin > 0) "Po jízdě MHD (klid ještě $rideCooldownMin min)" else "Uspáno v jedoucí MHD"
                    enterStandbySleep(msg)
                    return
                }

                // 2. Uložíme předchozí bod pro vyhodnocení směru chůze (approaching / leaving)
                val stepDist = PmdpRepository.haversineMeters(lastLat, lastLon, loc.latitude, loc.longitude)
                if (stepDist >= 12.0 && (!loc.hasAccuracy() || loc.accuracy <= 35f)) {
                    prevLat = lastLat
                    prevLon = lastLon
                }
                lastLat = loc.latitude
                lastLon = loc.longitude
                PmdpRepository.saveLastKnownLocation(this@LiveDeparturesService, loc.latitude, loc.longitude)

                // 3. Aktualizace sledování procházky kolem jednotlivých zastávek (podle jejich ID)
                updateWalkAwayStopTracking(loc.latitude, loc.longitude, now)

                // 4. Kontrola SKUTEČNÉHO postupu po ulici (>= 40 m od referenčního bodu anchorLat/anchorLon).
                //    Tím odlišíme reálnou cestu na zastávku od chození mezi regály v obchodě!
                //    Ignorujeme nepřesné fixy uvnitř budov (accuracy > 45 m), aby indoor jitter neprodlužoval bdění!
                val isProgressAccurate = !loc.hasAccuracy() || loc.accuracy <= 45f
                val displacementFromAnchor = PmdpRepository.haversineMeters(
                    anchorLat,
                    anchorLon,
                    loc.latitude,
                    loc.longitude
                )
                if (isProgressAccurate && displacementFromAnchor >= MIN_GPS_DISPLACEMENT_METERS) {
                    anchorLat = loc.latitude
                    anchorLon = loc.longitude
                    lastGpsProgressTimestampMs = now
                    PmdpRepository.clearAutoSleptLocation(this@LiveDeparturesService)
                }

                // Okamžitý přepočet vzdálenosti a stihnutelnosti v notifikaci při chůzi (>= 12 m)
                if (stepDist >= 12.0 && latestResult != null) {
                    applyLocalCountdownTick()
                }

                // Okamžité vyhodnocení uspání (např. při míjení zastávek na procházce nebo odchodu z přestupního uzlu)
                checkShouldAutoSleepReason()?.let { sleepReason ->
                    enterStandbySleep(sleepReason)
                    return
                }

                GeofenceManager.registerNearbyGeofences(this@LiveDeparturesService, lastLat, lastLon)
            }
        }

        try {
            // V6: Proaktivní okamžitý Fast-Fix při probuzení z chůze / opuštění budovy,
            // aby uživatel nečekal na studený satelitní GPS start a viděl odjezdy dřív, než dojde k zastávce!
            fusedLocationClient.getCurrentLocation(Priority.PRIORITY_BALANCED_POWER_ACCURACY, null)
                .addOnSuccessListener { loc ->
                    if (loc != null && !isSleepingStandby) {
                        lastLat = loc.latitude
                        lastLon = loc.longitude
                        anchorLat = loc.latitude
                        anchorLon = loc.longitude
                        PmdpRepository.saveLastKnownLocation(this@LiveDeparturesService, loc.latitude, loc.longitude)
                        updateWalkAwayStopTracking(loc.latitude, loc.longitude, System.currentTimeMillis())
                        triggerImmediateRefresh()
                    }
                }

            fusedLocationClient.lastLocation.addOnSuccessListener { loc ->
                if (loc != null && !isSleepingStandby) {
                    lastLat = loc.latitude
                    lastLon = loc.longitude
                    anchorLat = loc.latitude
                    anchorLon = loc.longitude
                    updateWalkAwayStopTracking(loc.latitude, loc.longitude, System.currentTimeMillis())
                    triggerImmediateRefresh()
                }
            }
            fusedLocationClient.requestLocationUpdates(
                request,
                locationCallback!!,
                Looper.getMainLooper()
            )
        } catch (_: SecurityException) {
        }
    }

    /**
     * Sleduje konkrétní zastávky, kolem kterých uživatel prochází:
     * - Pokud se k zastávce přiblíží na <= 125 m a následně se od TÉTO konkrétní zastávky
     *   vzdálí o >= 65 m s odstupem alespoň 35 vteřin (odfiltruje přecházení silnice!),
     *   označíme zastávku jako "míjenou pěšky" (passedStopsOnFoot).
     */
    private fun updateWalkAwayStopTracking(lat: Double, lon: Double, now: Long) {
        try {
            val closeCandidates = PmdpRepository.findNearestStops(this, lat, lon, limit = 5)
            if (closeCandidates.isEmpty()) return

            if (initialNearestStop == null) {
                val firstStop = closeCandidates.first().stop
                initialNearestStop = firstStop
                initialNearestAirMeters = PmdpRepository.haversineMeters(
                    lat, lon, firstStop.latitude, firstStop.longitude
                ).toInt()
            }

            for (ranked in closeCandidates) {
                val stop = ranked.stop
                val airDist = PmdpRepository.haversineMeters(lat, lon, stop.latitude, stop.longitude).toInt()
                if (airDist <= 125) {
                    val existing = visitedStopsInSession[stop.cisNumber]
                    if (existing == null) {
                        visitedStopsInSession[stop.cisNumber] = StopVisitState(
                            stop = stop,
                            minAirMeters = airDist,
                            minTimeMs = now
                        )
                    } else if (airDist < existing.minAirMeters) {
                        existing.minAirMeters = airDist
                        existing.minTimeMs = now
                    }
                }
            }

            for ((cis, visit) in visitedStopsInSession) {
                val currAir = PmdpRepository.haversineMeters(
                    lat, lon, visit.stop.latitude, visit.stop.longitude
                ).toInt()
                val distIncrease = currAir - visit.minAirMeters
                val timeSinceClosestMs = now - visit.minTimeMs
                // Musí uplynout aspoň 35 s od nejbližšího bodu a vzdálenost narůst o >= 65 m
                if (distIncrease >= 65 && timeSinceClosestMs >= 35_000L) {
                    passedStopsOnFoot.add(cis)
                }
            }
        } catch (_: Throwable) {
        }
    }

    private fun stopLocationUpdates() {
        try {
            locationCallback?.let { fusedLocationClient.removeLocationUpdates(it) }
        } catch (_: Throwable) {
        }
        locationCallback = null
    }

    private fun startPeriodicRefreshLoop() {
        standbyWatcherJob?.cancel()
        refreshJob?.cancel()
        refreshJob = serviceScope.launch {
            var tick = 0
            while (isActive && !isSleepingStandby) {
                try {
                    val sleepReason = checkShouldAutoSleepReason()
                    if (sleepReason != null) {
                        enterStandbySleep(sleepReason)
                        break
                    }

                    if (tick % 4 == 0 || latestResult == null) {
                        // Každých 40 sekund (nebo při prvním spuštění) stáhneme čerstvá data ze serveru PMDP
                        val ok = fetchAndUpdateNotification()
                        if (!ok) {
                            delay(20_000L)
                            continue
                        }
                    } else {
                        // Mezi síťovými obnovami (každých 10 s) provedeme čistě lokální dopočet v paměti (0 % baterie/sítě)
                        applyLocalCountdownTick()
                    }
                } catch (t: Throwable) {
                    if (t is CancellationException) throw t
                    android.util.Log.e("LiveDeparturesService", "Chyba v periodickém refresh cyklu: ${t.message}", t)
                }

                tick++
                delay(10_000L)
            }
        }
    }

    /**
     * Interní lokální dopočet odjezdů v paměti každých 10 sekund (nebo při rozsvícení displeje) bez dotazu na síť.
     * Pokud se u některého spoje překlopila minuta odjezdu (např. z 3 min na 2 min jako na tabuli zastávky)
     * nebo se změnila vzdálenost k zastávce, okamžitě přepíše notifikaci i obrazovku aplikace.
     */
    private fun applyLocalCountdownTick(forceNotify: Boolean = false) {
        try {
            if (isSleepingStandby) return
            val current = latestResult ?: return
            val updated = PmdpRepository.recalculateWithLiveCountdown(
                current,
                System.currentTimeMillis(),
                lastLat,
                lastLon
            )

            val changed = forceNotify ||
                current.primaryStop.distanceMeters != updated.primaryStop.distanceMeters ||
                current.departures.size != updated.departures.size ||
                current.departures.zip(updated.departures).any { (oldDep, newDep) ->
                    oldDep.leavingInMin != newDep.leavingInMin ||
                        oldDep.catchability.status != newDep.catchability.status ||
                        oldDep.stopDistanceMeters != newDep.stopDistanceMeters
                }

            latestResult = updated
            if (changed) {
                val notif = buildNotificationFromResult(updated)
                val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
                try {
                    nm.notify(NOTIFICATION_ID, notif)
                } catch (_: Throwable) {
                }
                sendBroadcast(Intent(BROADCAST_LIVE_UPDATE).setPackage(packageName))
            }
        } catch (t: Throwable) {
            android.util.Log.e("LiveDeparturesService", "Chyba v applyLocalCountdownTick: ${t.message}", t)
        }
    }

    /**
     * Zkontroluje, zda máme službu přepnout do tichého spánku (Standby):
     * 1. Jsme na domácí Wi-Fi (a není ručně vynuceno)
     * 2. Procházka:
     *    a) Uživatel minul pěšky alespoň 2 RŮZNÉ zastávky bez nástupu a k žádné další se neblíží
     *    b) Uživatel se souvisle vzdálil úplně mimo dosah zastávek MHD (> 550 m) a k žádné se neblíží
     * 3. Obchod / Byt / Kancelář: za 2,5 minuty jsme se neposunuli o >= 40 m (i když děláme kroky na místě)
     * 4. Sloupek zastávky (<= 40 m): čekáme déle než 5 minut
     */
    private fun checkShouldAutoSleepReason(): String? {
        val now = System.currentTimeMillis()

        // 1. Kontrola domácí Wi-Fi (pokud uživatel aktivně jde, chůze má VŽDY přednost před Wi-Fi)
        if (!isManualForceOnWifi &&
            !isWalkingWifiOverrideActive() &&
            PmdpRepository.isSleepOnWifiEnabled(this) &&
            SmartTriggerMonitor.isConnectedToHomeWifi(this)
        ) {
            PmdpRepository.saveAutoSleptLocation(this, lastLat, lastLon)
            clearIndoorCooldown()
            val ssid = SmartTriggerMonitor.getConnectedWifiSsid(this)
            val ssidLabel = if (!ssid.isNullOrEmpty()) " ($ssid)" else ""
            return "Doma na Wi-Fi$ssidLabel (probudí se při odchodu)"
        }

        val res = latestResult
        val nearestDist = res?.primaryStop?.distanceMeters ?: 100

        // 1b. Kontrola CHYTRÉHO PŘESTUPNÍHO OKNA (V5):
        //     Pokud uživatel vystoupil z MHD a odchází od zastávky do práce/domů (> 85 m od místa výstupu)
        //     nebo přestupní okno vypršelo (> 3,5 minuty), okamžitě aktivujeme standardní pauzu po jízdě!
        if (transferWindowActive) {
            val distFromExit = if (!transferExitLat.isNaN() && !transferExitLon.isNaN()) {
                PmdpRepository.haversineMeters(transferExitLat, transferExitLon, lastLat, lastLon)
            } else 0.0
            val transferElapsedMs = now - transferStartedAtMs

            if (distFromExit > 85.0 || transferElapsedMs >= 210_000L) {
                transferWindowActive = false
                val rideCooldownMin = PmdpRepository.getPostRideCooldownMinutes(this)
                postRideCooldownUntilMs = if (rideCooldownMin > 0) now + rideCooldownMin * 60_000L else 0L
                clearIndoorCooldown()
                return if (rideCooldownMin > 0) {
                    "Po jízdě MHD (klid ještě $rideCooldownMin min)"
                } else {
                    "Vystoupeno z MHD (klidový režim)"
                }
            }
        }

        // 2. Detekce PROCHÁZKY:
        // Aktualizujeme sledování zastávek, ke kterým jsme se přiblížili (<= 125 m) a pak se vzdálili (>= 65 m po >= 35 s)
        updateWalkAwayStopTracking(lastLat, lastLon, now)

        val walkStopsLimit = PmdpRepository.getWalkPassedStopsLimit(this)
        if (walkStopsLimit > 0 && passedStopsOnFoot.size >= walkStopsLimit) {
            val isApproachingAnyStop = res?.nearbyStops?.any {
                it.movementStatus == "approaching" && it.distanceMeters <= 250
            } == true
            if (!isApproachingAnyStop) {
                setIndoorCooldown(now + 8 * 60 * 1000L, StandbyCooldownCause.WALK)
                return "Procházka městem (míjíš zastávky bez nástupu)"
            }
        }

        // Pokud se uživatel souvisle vzdálil úplně mimo dosah zastávek MHD (> 550 m) a k žádné se neblíží
        if (walkStopsLimit > 0 && nearestDist > 550 && (now - sessionActivatedAtMs) >= 90_000L) {
            val isApproachingAny = res?.nearbyStops?.any { it.movementStatus == "approaching" } == true
            if (!isApproachingAny) {
                setIndoorCooldown(now + 6 * 60 * 1000L, StandbyCooldownCause.WALK)
                return "Procházka městem (mimo dosah zastávek MHD)"
            }
        }

        // 3. Kontrola posunu GPS:
        //    - Mimo dosah zastávky (> 140 m): pokud se GPS neposunula o >= 40 m za 3 minuty (obchod, byt, kancelář)
        //    - V perimetru zastávky (<= 140 m): po 8 minutách čekání na zastávce
        val timeWithoutGpsProgressMs = now - lastGpsProgressTimestampMs
        val isAtStopArea = nearestDist <= STOP_WAITING_RADIUS_METERS
        val timeoutLimitMs = if (isAtStopArea) IDLE_TIMEOUT_AT_STOP_MS else IDLE_TIMEOUT_AWAY_MS

        if (timeWithoutGpsProgressMs >= timeoutLimitMs) {
            PmdpRepository.saveAutoSleptLocation(this, lastLat, lastLon)
            return if (isAtStopArea) {
                setIndoorCooldown(now + 6 * 60 * 1000L, StandbyCooldownCause.STOP_IDLE)
                "Čekání na zastávce (klid ještě 6 min)"
            } else {
                // V obchodě nebo budově mimo zastávky nastavíme 12min klid
                setIndoorCooldown(now + INDOOR_SHOP_COOLDOWN_MS, StandbyCooldownCause.INDOOR_IDLE)
                "V budově na místě (klid ještě 12 min)"
            }
        }

        return null
    }

    private fun triggerImmediateRefresh() {
        if (isSleepingStandby) return
        serviceScope.launch {
            fetchAndUpdateNotification()
        }
    }

    private fun buildNotificationFromResult(res: LiveDeparturesResult): Notification {
        val isMulti = res.clusterStops.size > 1
        val headerStopName = if (isMulti) {
            res.clusterStops.take(2).joinToString(" · ") { it.stop.name }
        } else {
            res.primaryStop.stop.name
        }

        val nearestDist = res.primaryStop.distanceMeters
        val subStatus = if (nearestDist > 18_000) {
            "Mimo dosah spojů"
        } else if (nearestDist <= 40) {
            "U zastávky"
        } else {
            "$nearestDist m"
        }

        val nowMs = System.currentTimeMillis()
        val validDeps = res.departures.filter { dep ->
            val remMs = dep.targetDepartureEpochMs - nowMs
            remMs >= -25_000L
        }
        val hideMissed = PmdpRepository.isHideMissedInNotif(this)
        val notifCount = PmdpRepository.getNotifDeparturesCount(this)
        val catchableDeps = validDeps.filter { it.catchability.status != "missed" }
        val displayPool = if (hideMissed && catchableDeps.isNotEmpty()) catchableDeps else validDeps
        val topDeps = displayPool.take(notifCount)
        val firstCatchable = catchableDeps.firstOrNull()
            ?: validDeps.firstOrNull()

        val shortSummary = if (firstCatchable != null) {
            val inText = when {
                firstCatchable.leavingInMin < 0 -> "odjelo"
                firstCatchable.leavingInMin == 0 -> "teď"
                else -> "za ${firstCatchable.leavingInMin} min"
            }
            val delayNote = if (firstCatchable.delayMin != null && firstCatchable.delayMin >= 2) " (+${firstCatchable.delayMin}m)" else ""
            "${firstCatchable.lineName} → ${firstCatchable.destination}  ·  $inText$delayNote (${firstCatchable.catchability.label})"
        } else {
            "Žádné odjezdy v nejbližší době"
        }

        val lines = if (topDeps.isEmpty()) {
            listOf("Žádné spoje pro zvolený filtr.")
        } else {
            topDeps.map { d ->
                val inText = when {
                    d.leavingInMin < 0 -> "odjelo"
                    d.leavingInMin == 0 -> "teď"
                    else -> "za ${d.leavingInMin} min"
                }
                val delayNote = if (d.delayMin != null && d.delayMin >= 2) " (+${d.delayMin}m)" else ""
                val originPart = if (isMulti && d.stopCis != res.primaryStop.stop.cisNumber) " · ${d.stopName}" else ""
                "${d.lineName} → ${d.destination}  ·  $inText$delayNote (${d.catchability.label})$originPart"
            }
        }

        return buildNotification(
            title = headerStopName,
            shortLine = shortSummary,
            subStatus = subStatus,
            expandedLines = lines
        )
    }

    private suspend fun fetchAndUpdateNotification(): Boolean {
        if (isSleepingStandby) return true
        return try {
            val multi = PmdpRepository.isMultiStopMode(this) && PmdpRepository.getPinnedStopCis(this) == null
            val res = PmdpRepository.fetchLiveDepartures(
                context = this,
                lat = lastLat,
                lon = lastLon,
                prevLat = prevLat,
                prevLon = prevLon,
                explicitCis = PmdpRepository.getPinnedStopCis(this),
                multiStop = multi
            )
            latestResult = res

            if (!isSleepingStandby) {
                val notif = buildNotificationFromResult(res)
                val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
                try {
                    nm.notify(NOTIFICATION_ID, notif)
                } catch (_: Throwable) {
                }
            }

            sendBroadcast(Intent(BROADCAST_LIVE_UPDATE).setPackage(packageName))
            true
        } catch (e: Exception) {
            if (!isSleepingStandby && latestResult == null) {
                val notif = buildNotification(
                    title = "Načítám odjezdy…",
                    shortLine = "Čekám na internet (obnovím za 3 s)",
                    subStatus = "Spojení",
                    expandedLines = listOf("Přepínání sítě, za okamžik načtu odjezdy…")
                )
                val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
                nm.notify(NOTIFICATION_ID, notif)
            }
            false
        }
    }

    /**
     * Tichá, na zamykací obrazovce SKRYTÁ notifikace během spánku (Standby).
     */
    private fun buildStandbyNotification(reason: String): Notification {
        val openAppIntent = PendingIntent.getActivity(
            this,
            0,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val wakeIntent = PendingIntent.getService(
            this,
            4,
            Intent(this, LiveDeparturesService::class.java).apply {
                action = ACTION_WAKE_UP
                putExtra("trigger_reason", "Ruční probuzení")
                putExtra("is_manual", true)
            },
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val stopServiceIntent = PendingIntent.getService(
            this,
            3,
            Intent(this, LiveDeparturesService::class.java).apply { action = ACTION_STOP },
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        return NotificationCompat.Builder(this, CHANNEL_STANDBY_ID)
            .setSmallIcon(R.drawable.ic_notif_transit)
            .setColor(android.graphics.Color.parseColor("#2C4C38"))
            .setContentTitle("Klidový režim")
            .setContentText(reason)
            .setPriority(NotificationCompat.PRIORITY_MIN)
            .setVisibility(NotificationCompat.VISIBILITY_SECRET)
            .setSilent(true)
            .setOngoing(true)
            .setShowWhen(false)
            .setContentIntent(openAppIntent)
            .addAction(0, "Probudit", wakeIntent)
            .addAction(0, "Vypnout", stopServiceIntent)
            .build()
    }

    private fun buildNotification(
        title: String,
        shortLine: String,
        subStatus: String,
        expandedLines: List<String>
    ): Notification {
        val openAppIntent = PendingIntent.getActivity(
            this,
            0,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val toggleTramIntent = PendingIntent.getService(
            this,
            1,
            Intent(this, LiveDeparturesService::class.java).apply { action = ACTION_TOGGLE_TRAM },
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val nextStopIntent = PendingIntent.getService(
            this,
            2,
            Intent(this, LiveDeparturesService::class.java).apply { action = ACTION_NEXT_STOP },
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val sleepStandbyIntent = PendingIntent.getService(
            this,
            5,
            Intent(this, LiveDeparturesService::class.java).apply {
                action = ACTION_SLEEP_STANDBY
                putExtra("sleep_reason", "Uspáno ručně")
            },
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val tramBtnLabel = if (PmdpRepository.getPreferredTraction(this) == "tram") {
            "Všechny spoje"
        } else {
            "Jen tramvaje"
        }

        val bigText = expandedLines.joinToString("\n")

        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_notif_transit)
            .setColor(android.graphics.Color.parseColor("#2C4C38"))
            .setContentTitle(title)
            .setContentText(shortLine)
            .setSubText(subStatus)
            .setStyle(
                NotificationCompat.BigTextStyle()
                    .bigText(bigText)
                    .setBigContentTitle(title)
                    .setSummaryText(subStatus)
            )
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setCategory(NotificationCompat.CATEGORY_TRANSPORT)
            .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
            .setSilent(true)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setShowWhen(false)
            .setContentIntent(openAppIntent)
            .addAction(0, tramBtnLabel, toggleTramIntent)
            .addAction(0, "Další zastávka", nextStopIntent)
            .addAction(0, "Uspat", sleepStandbyIntent)
            .build()
    }

    private fun createNotificationChannels() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager

            val activeChannel = NotificationChannel(
                CHANNEL_ID,
                "Živé odjezdy MHD na zamykací obrazovce",
                NotificationManager.IMPORTANCE_DEFAULT
            ).apply {
                description = "Ukazuje živé odjezdy spojů při chůzi a přiblížení k zastávce"
                lockscreenVisibility = Notification.VISIBILITY_PUBLIC
                setShowBadge(false)
                setSound(null, null)
                enableVibration(false)
            }
            nm.createNotificationChannel(activeChannel)

            val standbyChannel = NotificationChannel(
                CHANNEL_STANDBY_ID,
                "Tichý spánkový režim (skryté na zamykací obrazovce)",
                NotificationManager.IMPORTANCE_MIN
            ).apply {
                description = "Skrytý úsporný režim čekající na chůzi nebo odchod z domácí Wi-Fi"
                lockscreenVisibility = Notification.VISIBILITY_SECRET
                setShowBadge(false)
            }
            nm.createNotificationChannel(standbyChannel)
        }
    }

    override fun onDestroy() {
        isRunning = false
        isSleepingStandby = false
        isManualForceOnWifi = false
        refreshJob?.cancel()
        standbyWatcherJob?.cancel()
        serviceScope.cancel()
        unregisterScreenStateReceiver()
        unregisterStepSensors()
        stopStandbyOutdoorWatch()
        stopLocationUpdates()
        releaseWalkingWakeLock()
        if (PmdpRepository.isServiceExplicitlyStopped(this)) {
            ActivityTransitionManager.unregisterTransitions(this)
        }
        sendBroadcast(Intent(BROADCAST_LIVE_UPDATE).setPackage(packageName))
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null
}
