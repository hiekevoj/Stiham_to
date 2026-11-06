package cz.plzen.stihamto

import android.Manifest
import android.annotation.SuppressLint
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.PowerManager
import android.provider.Settings
import android.widget.Toast
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import com.google.android.gms.location.LocationServices
import com.google.android.gms.location.Priority
import cz.plzen.stihamto.ui.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

class MainActivity : AppCompatActivity() {

    private var searchJob: Job? = null

    // Compose stavy pro vzhled a motiv
    private val themeModeState = mutableStateOf(AppThemeMode.SYSTEM)
    private val colorThemeState = mutableStateOf(
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) AppColorTheme.DYNAMIC else AppColorTheme.BOTANICAL
    )

    // Compose stavy pro odjezdy a obrazovku Domů
    private val liveResultState = mutableStateOf<LiveDeparturesResult?>(null)
    private val errorMessageState = mutableStateOf<String?>(null)
    private val heroState = mutableStateOf(
        HeroState(
            isRunning = false,
            isSleeping = false,
            title = "Stíhám to?",
            subtitle = "Načítám stav sledování…",
            actionText = "Spustit sledování"
        )
    )
    private val hasMissingPermissionsState = mutableStateOf(false)
    private val selectedTractionState = mutableStateOf("all")
    private val isMultiStopState = mutableStateOf(true)
    private val pinnedCisState = mutableStateOf<Int?>(null)
    private val searchQueryState = mutableStateOf("")
    private val searchResultsState = mutableStateOf<List<PmdpStop>>(emptyList())
    private val updateUiState = mutableStateOf<UpdateUiState>(UpdateUiState.Idle)

    // Nastavení
    private val walkSpeedState = mutableStateOf("normal")
    private val crosswalkBufferState = mutableStateOf(30)
    private val hideMissedState = mutableStateOf(true)
    private val notifCountState = mutableStateOf(4)
    private val clusterRadiusState = mutableStateOf(400)
    private val autoTramState = mutableStateOf(true)
    private val favoriteLinesState = mutableStateOf<Set<String>>(emptySet())
    private val allKnownLinesState = mutableStateOf<List<String>>(emptyList())
    private val sleepOnWifiState = mutableStateOf(true)
    private val currentWifiSsidState = mutableStateOf<String?>(null)
    private val homeWifiSsidsState = mutableStateOf<Set<String>>(emptySet())
    private val wakeUpStepsState = mutableStateOf(30)
    private val postRideCooldownState = mutableStateOf(10)
    private val systemDiagState = mutableStateOf(
        SystemDiagState(hasLoc = true, hasActivity = true, hasNotif = true, ignoringBatteryOpt = true)
    )

    private val updateReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            try {
                refreshAllState()
                val cached = LiveDeparturesService.latestResult
                if (cached != null && (System.currentTimeMillis() - cached.fetchedEpochMs) < 75_000L) {
                    liveResultState.value = PmdpRepository.recalculateWithLiveCountdown(cached)
                    errorMessageState.value = null
                } else {
                    fetchWithCurrentGps()
                }
            } catch (t: Throwable) {
                android.util.Log.e("MainActivity", "Chyba v updateReceiver: ${t.message}", t)
            }
        }
    }

    private val permissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { _ ->
        refreshAllState()
        ActivityTransitionManager.registerTransitions(this)
        val hasFine = ContextCompat.checkSelfPermission(
            this,
            Manifest.permission.ACCESS_FINE_LOCATION
        ) == PackageManager.PERMISSION_GRANTED
        val hasBg = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            ContextCompat.checkSelfPermission(
                this,
                Manifest.permission.ACCESS_BACKGROUND_LOCATION
            ) == PackageManager.PERMISSION_GRANTED
        } else true

        if (hasFine && !hasBg && Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            bgLocationPermissionLauncher.launch(Manifest.permission.ACCESS_BACKGROUND_LOCATION)
        } else {
            refreshFromGpsAndStartLive()
        }
    }

    private val bgLocationPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { _ ->
        refreshAllState()
        refreshFromGpsAndStartLive()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        CrashHandler.init(this)
        PmdpRepository.syncRuntimeSettings(this)

        val previousCrash = CrashHandler.consumeLastCrashReport(this)
        if (previousCrash != null) {
            android.util.Log.e("MainActivity", "Zaznamenán pád z minula:\n$previousCrash")
            Toast.makeText(this, "Aplikace se zotavila po chybě", Toast.LENGTH_LONG).show()
        }

        SmartTriggerMonitor.ensureWifiCallbackRegistered(this)
        if (!PmdpRepository.isServiceExplicitlyStopped(this)) {
            ActivityTransitionManager.registerTransitions(this)
            // Pokud máme potřebná oprávnění a služba ještě neběží (např. po instalaci nebo restartu),
            // automaticky ji spustíme na pozadí, aby uživatel nemusel nic ručně zapínat!
            val hasFineLoc = ContextCompat.checkSelfPermission(
                this,
                Manifest.permission.ACCESS_FINE_LOCATION
            ) == PackageManager.PERMISSION_GRANTED
            if (hasFineLoc && !LiveDeparturesService.isRunning) {
                startLiveLockscreenService()
            }
        }

        // Načtení uloženého nastavení motivu
        val prefs = getSharedPreferences("stiham_to_prefs", Context.MODE_PRIVATE)
        val modeStr = prefs.getString("theme_mode", "SYSTEM") ?: "SYSTEM"
        themeModeState.value = try { AppThemeMode.valueOf(modeStr) } catch (_: Exception) { AppThemeMode.SYSTEM }

        val defaultColor = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) "DYNAMIC" else "BOTANICAL"
        val colorStr = prefs.getString("color_theme", defaultColor) ?: defaultColor
        colorThemeState.value = try { AppColorTheme.valueOf(colorStr) } catch (_: Exception) { AppColorTheme.BOTANICAL }

        refreshAllState()

        // Načtení známých linek na pozadí, aby neblokovalo UI thread
        lifecycleScope.launch(Dispatchers.IO) {
            val lines = PmdpRepository.getAllKnownLines(applicationContext)
            withContext(Dispatchers.Main) {
                allKnownLinesState.value = lines
            }
        }

        val cached = LiveDeparturesService.latestResult
        if (cached != null && (System.currentTimeMillis() - cached.fetchedEpochMs) < 75_000L) {
            liveResultState.value = PmdpRepository.recalculateWithLiveCountdown(cached)
        } else {
            fetchWithCurrentGps()
        }

        // Plynulý periodický odpočet minut v pozadí (každých 5 s místo 1 s pro eliminaci janku)
        lifecycleScope.launch {
            while (true) {
                delay(5000)
                val current = liveResultState.value
                if (current != null) {
                    val recalculated = withContext(Dispatchers.Default) {
                        PmdpRepository.recalculateWithLiveCountdown(current)
                    }
                    if (recalculated !== current) {
                        liveResultState.value = recalculated
                    }
                }
            }
        }

        setContent {
            StihamToTheme(
                themeMode = themeModeState.value,
                colorTheme = colorThemeState.value
            ) {
                MainAppScaffold()
            }
        }
    }

    override fun onResume() {
        super.onResume()
        PmdpRepository.syncRuntimeSettings(this)
        val filter = IntentFilter(LiveDeparturesService.BROADCAST_LIVE_UPDATE)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            registerReceiver(updateReceiver, filter, Context.RECEIVER_NOT_EXPORTED)
        } else {
            registerReceiver(updateReceiver, filter)
        }
        refreshAllState()
    }

    override fun onPause() {
        super.onPause()
        try {
            unregisterReceiver(updateReceiver)
        } catch (_: Exception) {
        }
    }

    @OptIn(androidx.compose.foundation.ExperimentalFoundationApi::class)
    @Composable
    private fun MainAppScaffold() {
        val pagerState = rememberPagerState(pageCount = { 2 })
        val coroutineScope = rememberCoroutineScope()

        Scaffold(
            modifier = Modifier.fillMaxSize(),
            bottomBar = {
                NavigationBar(
                    containerColor = MaterialTheme.colorScheme.surface,
                    tonalElevation = 4.dp
                ) {
                    NavigationBarItem(
                        selected = pagerState.currentPage == 0,
                        onClick = {
                            coroutineScope.launch {
                                pagerState.animateScrollToPage(0)
                            }
                        },
                        icon = {
                            Icon(
                                painter = painterResource(id = R.drawable.ic_home),
                                contentDescription = "Domů",
                                modifier = Modifier.padding(2.dp)
                            )
                        },
                        label = { Text("Domů") },
                        colors = NavigationBarItemDefaults.colors(
                            selectedIconColor = MaterialTheme.colorScheme.primary,
                            selectedTextColor = MaterialTheme.colorScheme.primary,
                            indicatorColor = MaterialTheme.colorScheme.primaryContainer
                        )
                    )
                    NavigationBarItem(
                        selected = pagerState.currentPage == 1,
                        onClick = {
                            coroutineScope.launch {
                                pagerState.animateScrollToPage(1)
                            }
                        },
                        icon = {
                            Icon(
                                painter = painterResource(id = R.drawable.ic_settings),
                                contentDescription = "Nastavení",
                                modifier = Modifier.padding(2.dp)
                            )
                        },
                        label = { Text("Nastavení") },
                        colors = NavigationBarItemDefaults.colors(
                            selectedIconColor = MaterialTheme.colorScheme.primary,
                            selectedTextColor = MaterialTheme.colorScheme.primary,
                            indicatorColor = MaterialTheme.colorScheme.primaryContainer
                        )
                    )
                }
            }
        ) { innerPadding ->
            val screenPadding = PaddingValues(
                start = 20.dp,
                end = 20.dp,
                top = innerPadding.calculateTopPadding() + 20.dp,
                bottom = innerPadding.calculateBottomPadding() + 16.dp
            )

            HorizontalPager(
                state = pagerState,
                modifier = Modifier.fillMaxSize()
            ) { page ->
                if (page == 0) {
                    HomeScreen(
                        result = liveResultState.value,
                        heroState = heroState.value,
                        hasMissingPermissions = hasMissingPermissionsState.value,
                        selectedTraction = selectedTractionState.value,
                        isMultiStop = isMultiStopState.value,
                        pinnedCis = pinnedCisState.value,
                        searchQuery = searchQueryState.value,
                        searchResults = searchResultsState.value,
                        errorMessage = errorMessageState.value,
                        onGpsClick = {
                            PmdpRepository.setPinnedStopCis(this@MainActivity, null)
                            pinnedCisState.value = null
                            searchQueryState.value = ""
                            searchResultsState.value = emptyList()
                            fetchWithCurrentGps()
                        },
                        onRequestPermissions = { requestNeededPermissions() },
                        onHeroToggle = { handleHeroToggle() },
                        onHeroStop = { handleHeroStop() },
                        onSelectTraction = { traction ->
                            PmdpRepository.setPreferredTraction(this@MainActivity, traction)
                            selectedTractionState.value = traction
                            triggerRefresh()
                        },
                        onToggleMultiStop = {
                            val curr = isMultiStopState.value && pinnedCisState.value == null
                            PmdpRepository.setPinnedStopCis(this@MainActivity, null)
                            PmdpRepository.setMultiStopMode(this@MainActivity, !curr)
                            pinnedCisState.value = null
                            isMultiStopState.value = !curr
                            triggerRefresh()
                        },
                        onSearchQueryChange = { q -> onSearchQueryChanged(q) },
                        onSelectStop = { cis ->
                            PmdpRepository.setPinnedStopCis(this@MainActivity, cis)
                            pinnedCisState.value = cis
                            searchQueryState.value = ""
                            searchResultsState.value = emptyList()
                            triggerRefresh()
                        },
                        onSelectAllNearby = {
                            PmdpRepository.setPinnedStopCis(this@MainActivity, null)
                            PmdpRepository.setMultiStopMode(this@MainActivity, true)
                            pinnedCisState.value = null
                            isMultiStopState.value = true
                            triggerRefresh()
                        },
                        onToggleFavoriteLine = { line ->
                            PmdpRepository.toggleFavoriteLine(this@MainActivity, line)
                            favoriteLinesState.value = PmdpRepository.getFavoriteLines(this@MainActivity)
                            triggerRefresh()
                        },
                        onRetry = {
                            fetchWithCurrentGps()
                        },
                        contentPadding = screenPadding
                    )
                } else {
                    val vName = try {
                        packageManager.getPackageInfo(packageName, 0).versionName
                    } catch (_: Exception) {
                        "0.1.2"
                    }

                    SettingsScreen(
                        themeMode = themeModeState.value,
                        onThemeModeChange = { mode ->
                            themeModeState.value = mode
                            getSharedPreferences("stiham_to_prefs", Context.MODE_PRIVATE)
                                .edit()
                                .putString("theme_mode", mode.name)
                                .apply()
                        },
                        colorTheme = colorThemeState.value,
                        onColorThemeChange = { theme ->
                            colorThemeState.value = theme
                            getSharedPreferences("stiham_to_prefs", Context.MODE_PRIVATE)
                                .edit()
                                .putString("color_theme", theme.name)
                                .apply()
                        },
                        walkSpeed = walkSpeedState.value,
                        onWalkSpeedChange = { mode ->
                            PmdpRepository.setWalkSpeedMode(this@MainActivity, mode)
                            walkSpeedState.value = mode
                            triggerRefresh()
                        },
                        crosswalkBuffer = crosswalkBufferState.value,
                        onCrosswalkBufferChange = { sec ->
                            PmdpRepository.setCrosswalkBufferSec(this@MainActivity, sec)
                            crosswalkBufferState.value = sec
                            triggerRefresh()
                        },
                        hideMissed = hideMissedState.value,
                        onHideMissedChange = { hide ->
                            PmdpRepository.setHideMissedInNotif(this@MainActivity, hide)
                            hideMissedState.value = hide
                            triggerRefresh()
                        },
                        notifCount = notifCountState.value,
                        onNotifCountChange = { count ->
                            PmdpRepository.setNotifDeparturesCount(this@MainActivity, count)
                            notifCountState.value = count
                            triggerRefresh()
                        },
                        clusterRadius = clusterRadiusState.value,
                        onClusterRadiusChange = { meters ->
                            PmdpRepository.setClusterRadiusMeters(this@MainActivity, meters)
                            clusterRadiusState.value = meters
                            triggerRefresh()
                        },
                        autoTram = autoTramState.value,
                        onAutoTramChange = { enabled ->
                            PmdpRepository.setAutoIncludeTramEnabled(this@MainActivity, enabled)
                            autoTramState.value = enabled
                            triggerRefresh()
                        },
                        favoriteLines = favoriteLinesState.value,
                        allKnownLines = allKnownLinesState.value,
                        onToggleFavoriteLine = { line ->
                            PmdpRepository.toggleFavoriteLine(this@MainActivity, line)
                            favoriteLinesState.value = PmdpRepository.getFavoriteLines(this@MainActivity)
                            triggerRefresh()
                        },
                        onClearFavoriteLines = {
                            PmdpRepository.clearFavoriteLines(this@MainActivity)
                            favoriteLinesState.value = emptySet()
                            triggerRefresh()
                        },
                        sleepOnWifi = sleepOnWifiState.value,
                        onSleepOnWifiChange = { enabled ->
                            PmdpRepository.setSleepOnWifiEnabled(this@MainActivity, enabled)
                            sleepOnWifiState.value = enabled
                            heroState.value = getHeroState()
                            triggerRefresh()
                        },
                        currentWifiSsid = currentWifiSsidState.value,
                        homeWifiSsids = homeWifiSsidsState.value,
                        onAddHomeWifi = { ssid ->
                            PmdpRepository.addHomeWifiSsid(this@MainActivity, ssid)
                            homeWifiSsidsState.value = PmdpRepository.getHomeWifiSsids(this@MainActivity)
                            heroState.value = getHeroState()
                        },
                        onRemoveHomeWifi = { ssid ->
                            PmdpRepository.removeHomeWifiSsid(this@MainActivity, ssid)
                            homeWifiSsidsState.value = PmdpRepository.getHomeWifiSsids(this@MainActivity)
                            heroState.value = getHeroState()
                        },
                        wakeUpSteps = wakeUpStepsState.value,
                        onWakeUpStepsChange = { steps ->
                            PmdpRepository.setWakeUpStepsThreshold(this@MainActivity, steps)
                            wakeUpStepsState.value = steps
                            heroState.value = getHeroState()
                        },
                        postRideCooldown = postRideCooldownState.value,
                        onPostRideCooldownChange = { min ->
                            PmdpRepository.setPostRideCooldownMinutes(this@MainActivity, min)
                            postRideCooldownState.value = min
                        },
                        diagState = systemDiagState.value,
                        onRequestPermissions = { requestNeededPermissions() },
                        onOpenBatterySettings = { openBatterySettings() },
                        updateState = updateUiState.value,
                        onCheckForUpdates = { checkForAppUpdate() },
                        onDownloadAndInstall = { info -> downloadAndInstallUpdate(info) },
                        onInstallDownloaded = { file ->
                            try {
                                AppUpdateManager.startInstall(this@MainActivity, file)
                            } catch (e: Exception) {
                                Toast.makeText(this@MainActivity, e.message, Toast.LENGTH_LONG).show()
                            }
                        },
                        appVersion = vName ?: "0.1.0",
                        contentPadding = screenPadding
                    )
                }
            }
        }
    }

    private fun checkForAppUpdate() {
        updateUiState.value = UpdateUiState.Checking
        lifecycleScope.launch {
            try {
                val result = AppUpdateManager.checkForUpdates(this@MainActivity)
                if (result.isUpdateAvailable) {
                    updateUiState.value = UpdateUiState.UpdateAvailable(result)
                } else {
                    updateUiState.value = UpdateUiState.UpToDate(result.currentVersionName)
                }
            } catch (e: Exception) {
                updateUiState.value = UpdateUiState.Error(e.message ?: "Chyba při komunikaci s GitHubem")
            }
        }
    }

    private fun downloadAndInstallUpdate(info: UpdateCheckResult) {
        updateUiState.value = UpdateUiState.Downloading(0.01f)
        lifecycleScope.launch {
            try {
                val file = AppUpdateManager.downloadApk(this@MainActivity, info.apkUrl) { progress ->
                    updateUiState.value = UpdateUiState.Downloading(progress)
                }
                updateUiState.value = UpdateUiState.ReadyToInstall(file)
                AppUpdateManager.startInstall(this@MainActivity, file)
            } catch (e: Exception) {
                updateUiState.value = UpdateUiState.Error(e.message ?: "Chyba při stahování APK")
            }
        }
    }

    private fun refreshAllState() {
        heroState.value = getHeroState()
        hasMissingPermissionsState.value = checkMissingPermissions()
        selectedTractionState.value = PmdpRepository.getPreferredTraction(this)
        isMultiStopState.value = PmdpRepository.isMultiStopMode(this) && PmdpRepository.getPinnedStopCis(this) == null
        pinnedCisState.value = PmdpRepository.getPinnedStopCis(this)

        walkSpeedState.value = PmdpRepository.getWalkSpeedMode(this)
        crosswalkBufferState.value = PmdpRepository.getCrosswalkBufferSec(this)
        hideMissedState.value = PmdpRepository.isHideMissedInNotif(this)
        notifCountState.value = PmdpRepository.getNotifDeparturesCount(this)
        clusterRadiusState.value = PmdpRepository.getClusterRadiusMeters(this)
        autoTramState.value = PmdpRepository.isAutoIncludeTramEnabled(this)
        favoriteLinesState.value = PmdpRepository.getFavoriteLines(this)
        sleepOnWifiState.value = PmdpRepository.isSleepOnWifiEnabled(this)
        currentWifiSsidState.value = SmartTriggerMonitor.getConnectedWifiSsid(this)
        homeWifiSsidsState.value = PmdpRepository.getHomeWifiSsids(this)
        wakeUpStepsState.value = PmdpRepository.getWakeUpStepsThreshold(this)
        postRideCooldownState.value = PmdpRepository.getPostRideCooldownMinutes(this)
        systemDiagState.value = getSystemDiagState()
    }

    private fun getHeroState(): HeroState {
        val ssid = SmartTriggerMonitor.getConnectedWifiSsid(this)
        val onHomeWifi = SmartTriggerMonitor.isConnectedToHomeWifi(this)
        val onWifi = SmartTriggerMonitor.isConnectedToWifi(this)
        val netText = when {
            onHomeWifi -> "Klidová Wi-Fi${if (!ssid.isNullOrEmpty()) " ($ssid)" else ""}"
            onWifi -> "Veřejná Wi-Fi${if (!ssid.isNullOrEmpty()) " ($ssid)" else ""}"
            else -> "Mobilní data"
        }

        return if (LiveDeparturesService.isRunning && !LiveDeparturesService.isSleepingStandby) {
            HeroState(
                isRunning = true,
                isSleeping = false,
                title = "Živé sledování běží",
                subtitle = "Živé odjezdy jsou v notifikaci ($netText). Uspí se po 3 min na místě.",
                actionText = "Uspat do klidu"
            )
        } else if (LiveDeparturesService.isRunning && LiveDeparturesService.isSleepingStandby) {
            val rideCooldownMin = LiveDeparturesService.getRemainingPostRideCooldownMin()
            val indoorCooldownMin = LiveDeparturesService.getRemainingIndoorCooldownMin()
            val cause = LiveDeparturesService.indoorCooldownCause
            val stepProgress = LiveDeparturesService.standbyStepsProgress
            val stepTarget = PmdpRepository.getWakeUpStepsThreshold(this)

            val sub = when {
                rideCooldownMin > 0 ->
                    "Pauza po jízdě MHD (ještě $rideCooldownMin min). Při přestupu klepni na Probudit teď."
                indoorCooldownMin > 0 -> when (cause) {
                    LiveDeparturesService.StandbyCooldownCause.WALK ->
                        "Uspáno po procházce městem (klid ještě $indoorCooldownMin min)."
                    LiveDeparturesService.StandbyCooldownCause.INDOOR_IDLE ->
                        "Uspáno na místě v budově (klid ještě $indoorCooldownMin min)."
                    LiveDeparturesService.StandbyCooldownCause.STOP_IDLE ->
                        "Uspáno po 8 min čekání na zastávce (klid ještě $indoorCooldownMin min)."
                    else ->
                        "Dočasná pauza (ještě $indoorCooldownMin min)."
                }
                onHomeWifi && PmdpRepository.isSleepOnWifiEnabled(this) ->
                    "Na klidové Wi-Fi${if (!ssid.isNullOrEmpty()) " ($ssid)" else ""} odpočívá s 0 % baterie. Zapne se při odchodu."
                else ->
                    "Čeká na souvislou chůzi ($stepProgress/$stepTarget kroků) nebo příchod k zastávce."
            }

            HeroState(
                isRunning = true,
                isSleeping = true,
                title = "Klidový režim",
                subtitle = sub,
                actionText = "Probudit teď"
            )
        } else {
            HeroState(
                isRunning = false,
                isSleeping = false,
                title = "Sledování je vypnuté",
                subtitle = "Automatické hlídání zastávek i notifikace jsou vypnuty.",
                actionText = "Zapnout sledování"
            )
        }
    }

    private fun checkMissingPermissions(): Boolean {
        val hasFineLoc = ContextCompat.checkSelfPermission(
            this,
            Manifest.permission.ACCESS_FINE_LOCATION
        ) == PackageManager.PERMISSION_GRANTED

        val hasBgLoc = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            ContextCompat.checkSelfPermission(
                this,
                Manifest.permission.ACCESS_BACKGROUND_LOCATION
            ) == PackageManager.PERMISSION_GRANTED
        } else true

        val hasNotif = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            ContextCompat.checkSelfPermission(
                this,
                Manifest.permission.POST_NOTIFICATIONS
            ) == PackageManager.PERMISSION_GRANTED
        } else true

        val hasActivityRec = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            ContextCompat.checkSelfPermission(
                this,
                Manifest.permission.ACTIVITY_RECOGNITION
            ) == PackageManager.PERMISSION_GRANTED
        } else true

        return !(hasFineLoc && hasBgLoc && hasNotif && hasActivityRec)
    }

    private fun getSystemDiagState(): SystemDiagState {
        val hasLoc = ContextCompat.checkSelfPermission(
            this,
            Manifest.permission.ACCESS_FINE_LOCATION
        ) == PackageManager.PERMISSION_GRANTED

        val hasBgLoc = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            ContextCompat.checkSelfPermission(
                this,
                Manifest.permission.ACCESS_BACKGROUND_LOCATION
            ) == PackageManager.PERMISSION_GRANTED
        } else true

        val hasActivity = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            ContextCompat.checkSelfPermission(
                this,
                Manifest.permission.ACTIVITY_RECOGNITION
            ) == PackageManager.PERMISSION_GRANTED
        } else true

        val hasNotif = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            ContextCompat.checkSelfPermission(
                this,
                Manifest.permission.POST_NOTIFICATIONS
            ) == PackageManager.PERMISSION_GRANTED
        } else true

        val pm = getSystemService(Context.POWER_SERVICE) as? PowerManager
        val ignoringBatteryOpt = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            pm?.isIgnoringBatteryOptimizations(packageName) == true
        } else true

        return SystemDiagState(hasLoc && hasBgLoc, hasActivity, hasNotif, ignoringBatteryOpt)
    }

    private fun handleHeroToggle() {
        if (LiveDeparturesService.isRunning && !LiveDeparturesService.isSleepingStandby) {
            val sleepIntent = Intent(this, LiveDeparturesService::class.java).apply {
                action = LiveDeparturesService.ACTION_SLEEP_STANDBY
                putExtra("sleep_reason", "Uspáno – probudí se při chůzi k zastávce")
            }
            startService(sleepIntent)
        } else {
            startLiveLockscreenService()
        }
        lifecycleScope.launch {
            delay(250)
            heroState.value = getHeroState()
        }
    }

    private fun handleHeroStop() {
        PmdpRepository.setServiceExplicitlyStopped(this, true)
        ActivityTransitionManager.unregisterTransitions(this)
        val stopIntent = Intent(this, LiveDeparturesService::class.java).apply {
            action = LiveDeparturesService.ACTION_STOP
        }
        try {
            startService(stopIntent)
        } catch (_: Throwable) {
        }
        stopService(Intent(this, LiveDeparturesService::class.java))
        lifecycleScope.launch {
            delay(250)
            heroState.value = getHeroState()
        }
    }

    private fun startLiveLockscreenService() {
        PmdpRepository.setServiceExplicitlyStopped(this, false)
        ActivityTransitionManager.registerTransitions(this)
        val intent = Intent(this, LiveDeparturesService::class.java).apply {
            action = LiveDeparturesService.ACTION_START
        }
        try {
            if (LiveDeparturesService.isRunning) {
                startService(intent)
            } else if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                startForegroundService(intent)
            } else {
                startService(intent)
            }
        } catch (_: Throwable) {
        }
        lifecycleScope.launch {
            delay(250)
            heroState.value = getHeroState()
        }
    }

    private fun refreshFromGpsAndStartLive() {
        fetchWithCurrentGps()
        startLiveLockscreenService()
    }

    @SuppressLint("MissingPermission")
    private fun fetchWithCurrentGps() {
        val hasLoc = ContextCompat.checkSelfPermission(
            this,
            Manifest.permission.ACCESS_FINE_LOCATION
        ) == PackageManager.PERMISSION_GRANTED

        if (hasLoc) {
            try {
                val client = LocationServices.getFusedLocationProviderClient(this)
                client.getCurrentLocation(Priority.PRIORITY_HIGH_ACCURACY, null)
                    .addOnSuccessListener { loc ->
                        val lat = loc?.latitude ?: LiveDeparturesService.lastLat
                        val lon = loc?.longitude ?: LiveDeparturesService.lastLon
                        GeofenceManager.registerNearbyGeofences(this, lat, lon)
                        loadDepartures(lat, lon)
                    }
                    .addOnFailureListener {
                        loadDepartures(LiveDeparturesService.lastLat, LiveDeparturesService.lastLon)
                    }
            } catch (_: Throwable) {
                loadDepartures(LiveDeparturesService.lastLat, LiveDeparturesService.lastLon)
            }
        } else {
            loadDepartures(LiveDeparturesService.lastLat, LiveDeparturesService.lastLon)
        }
    }

    private fun triggerRefresh() {
        PmdpRepository.syncRuntimeSettings(this)
        if (LiveDeparturesService.isRunning && !LiveDeparturesService.isSleepingStandby) {
            val intent = Intent(this, LiveDeparturesService::class.java).apply {
                action = LiveDeparturesService.ACTION_REFRESH
            }
            startService(intent)
        } else {
            loadDepartures(LiveDeparturesService.lastLat, LiveDeparturesService.lastLon)
        }
    }

    private fun loadDepartures(lat: Double, lon: Double) {
        lifecycleScope.launch {
            try {
                val multi = PmdpRepository.isMultiStopMode(this@MainActivity) &&
                        PmdpRepository.getPinnedStopCis(this@MainActivity) == null
                val res = PmdpRepository.fetchLiveDepartures(
                    context = this@MainActivity,
                    lat = lat,
                    lon = lon,
                    explicitCis = PmdpRepository.getPinnedStopCis(this@MainActivity),
                    multiStop = multi
                )
                liveResultState.value = res
                errorMessageState.value = null
            } catch (e: Exception) {
                errorMessageState.value = e.message ?: "Zkontrolujte připojení k internetu"
            }
        }
    }

    private fun onSearchQueryChanged(query: String) {
        searchQueryState.value = query
        searchJob?.cancel()
        val trimmed = query.trim()
        if (trimmed.isEmpty()) {
            searchResultsState.value = emptyList()
            return
        }

        searchJob = lifecycleScope.launch {
            delay(150)
            val matches = withContext(Dispatchers.IO) {
                PmdpRepository.searchStopsByName(this@MainActivity, trimmed, limit = 6)
            }
            searchResultsState.value = matches
        }
    }

    private fun requestNeededPermissions() {
        val hasFineLoc = ContextCompat.checkSelfPermission(
            this,
            Manifest.permission.ACCESS_FINE_LOCATION
        ) == PackageManager.PERMISSION_GRANTED

        val hasBgLoc = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            ContextCompat.checkSelfPermission(
                this,
                Manifest.permission.ACCESS_BACKGROUND_LOCATION
            ) == PackageManager.PERMISSION_GRANTED
        } else true

        // Pokud již máme základní polohu, ale na Androidu 10+ chybí poloha na pozadí,
        // vyžádáme si samostatně ACCESS_BACKGROUND_LOCATION (požadavek Androidu od verze 11)
        if (hasFineLoc && !hasBgLoc && Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            bgLocationPermissionLauncher.launch(Manifest.permission.ACCESS_BACKGROUND_LOCATION)
            return
        }

        val perms = mutableListOf(
            Manifest.permission.ACCESS_FINE_LOCATION,
            Manifest.permission.ACCESS_COARSE_LOCATION
        )
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            perms.add(Manifest.permission.ACTIVITY_RECOGNITION)
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            perms.add(Manifest.permission.POST_NOTIFICATIONS)
        }
        permissionLauncher.launch(perms.toTypedArray())
    }

    @SuppressLint("BatteryLife")
    private fun openBatterySettings() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            try {
                val intent = Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS).apply {
                    data = Uri.parse("package:$packageName")
                }
                startActivity(intent)
                return
            } catch (t: Throwable) {
                android.util.Log.w("MainActivity", "ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS: ${t.message}")
            }

            try {
                val intent = Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS)
                startActivity(intent)
                return
            } catch (t: Throwable) {
                android.util.Log.w("MainActivity", "ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS: ${t.message}")
            }

            try {
                val fallback = Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS).apply {
                    data = Uri.parse("package:$packageName")
                }
                startActivity(fallback)
            } catch (_: Throwable) {
                Toast.makeText(this, "Nastavení -> Aplikace -> Baterie -> Bez omezení", Toast.LENGTH_SHORT).show()
            }
        }
    }
}
