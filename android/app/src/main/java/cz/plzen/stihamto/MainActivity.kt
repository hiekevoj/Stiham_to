package cz.plzen.stihamto

import android.Manifest
import android.annotation.SuppressLint
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.content.res.ColorStateList
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.PowerManager
import android.provider.Settings
import android.text.Editable
import android.text.TextUtils
import android.text.TextWatcher
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.*
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.core.widget.ImageViewCompat
import androidx.lifecycle.lifecycleScope
import com.google.android.gms.location.LocationServices
import com.google.android.gms.location.Priority
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch

class MainActivity : AppCompatActivity() {

    companion object {
        // Warm Editorial / Botanical Flat Palette (inspirováno Leafora)
        private const val BG_CREAM = "#F6F5F0"
        private const val CARD_WHITE = "#FFFFFF"
        private const val HERO_STONE = "#EAE7DD"
        private const val HERO_ACTIVE_SAGE = "#E1ECE3"
        private const val FOREST_PRIMARY = "#2C4C38"
        private const val INK_DARK = "#1D2B22"
        private const val INK_MUTED = "#6B756E"
        private const val BORDER_SOFT = "#E5E2D7"
        private const val PILL_INACTIVE = "#EAE7DD"
    }

    private var activeTabIndex: Int = 0 // 0 = Domů, 1 = Nastavení
    private lateinit var homeScroll: ScrollView
    private lateinit var settingsScroll: ScrollView
    private lateinit var navHomeTab: LinearLayout
    private lateinit var navHomeIcon: ImageView
    private lateinit var navHomeLabel: TextView
    private lateinit var navSettingsTab: LinearLayout
    private lateinit var navSettingsIcon: ImageView
    private lateinit var navSettingsLabel: TextView

    private lateinit var heroCard: LinearLayout
    private lateinit var heroTitleText: TextView
    private lateinit var heroSubtitleText: TextView
    private lateinit var liveToggleBtn: TextView
    private lateinit var fullStopBtn: TextView
    private lateinit var permissionBtn: TextView

    private lateinit var filterTilesRow: LinearLayout
    private lateinit var clusterTileBox: LinearLayout
    private lateinit var clusterTileIcon: ImageView
    private lateinit var clusterTileLabel: TextView

    private lateinit var searchInput: EditText
    private lateinit var searchResultsLayout: LinearLayout
    private lateinit var nearbyStopsScroll: HorizontalScrollView
    private lateinit var nearbyStopsRow: LinearLayout
    private lateinit var headerStopTitle: TextView
    private lateinit var headerStopMeta: TextView
    private lateinit var departuresContainer: LinearLayout

    // Dynamické prvky na stránce Nastavení
    private val allHelpTextViews = mutableListOf<TextView>()
    private var allHelpExpanded = false
    private lateinit var toggleAllHelpBtn: TextView
    private lateinit var favLinesSummaryText: TextView
    private lateinit var favLinesPickerContainer: LinearLayout
    private var favPickerExpanded = false
    private lateinit var favExpandBtn: TextView
    private lateinit var systemDiagnosticsContainer: LinearLayout
    private val settingsRefreshCallbacks = mutableListOf<() -> Unit>()

    private var searchJob: Job? = null

    private val serifBold: Typeface by lazy {
        Typeface.create("serif", Typeface.BOLD)
    }
    private val sansMedium: Typeface by lazy {
        Typeface.create("sans-serif-medium", Typeface.NORMAL)
    }

    private val updateReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            try {
                updateServiceHeroUI()
                refreshSettingsUI()
                val cached = LiveDeparturesService.latestResult
                if (cached != null && (System.currentTimeMillis() - cached.fetchedEpochMs) < 75_000L) {
                    renderResult(PmdpRepository.recalculateWithLiveCountdown(cached))
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
        updatePermissionButtonVisibility()
        refreshSettingsUI()
        ActivityTransitionManager.registerTransitions(this)
        refreshFromGpsAndStartLive()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        CrashHandler.init(this)
        PmdpRepository.syncRuntimeSettings(this)
        window.statusBarColor = Color.parseColor(BG_CREAM)
        window.navigationBarColor = Color.parseColor(CARD_WHITE)
        WindowInsetsControllerCompat(window, window.decorView).apply {
            isAppearanceLightStatusBars = true
            isAppearanceLightNavigationBars = true
        }

        val previousCrash = CrashHandler.consumeLastCrashReport(this)
        if (previousCrash != null) {
            android.util.Log.e("MainActivity", "Zaznamenán pád z minula:\n$previousCrash")
            Toast.makeText(this, "Aplikace se zotavila po chybě", Toast.LENGTH_LONG).show()
        }

        SmartTriggerMonitor.ensureWifiCallbackRegistered(this)
        ActivityTransitionManager.registerTransitions(this)
        setContentView(buildContentView())

        updatePermissionButtonVisibility()
        updateServiceHeroUI()
        updateFilterTilesUI()
        updateBottomTabsUI()
        refreshSettingsUI()

        val cached = LiveDeparturesService.latestResult
        if (cached != null && (System.currentTimeMillis() - cached.fetchedEpochMs) < 75_000L) {
            renderResult(PmdpRepository.recalculateWithLiveCountdown(cached))
        } else {
            fetchWithCurrentGps()
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
        updatePermissionButtonVisibility()
        updateServiceHeroUI()
        updateFilterTilesUI()
        refreshSettingsUI()

        val cached = LiveDeparturesService.latestResult
        if (cached != null && (System.currentTimeMillis() - cached.fetchedEpochMs) < 75_000L) {
            renderResult(PmdpRepository.recalculateWithLiveCountdown(cached))
        } else {
            fetchWithCurrentGps()
        }
    }

    override fun onPause() {
        super.onPause()
        try {
            unregisterReceiver(updateReceiver)
        } catch (_: Exception) {
        }
    }

    private fun dp(value: Int): Int = (value * resources.displayMetrics.density).toInt()

    private fun roundedBg(
        fillHex: String,
        strokeHex: String? = null,
        radiusDp: Int = 18,
        strokeWidthDp: Int = 1
    ): GradientDrawable {
        return GradientDrawable().apply {
            shape = GradientDrawable.RECTANGLE
            cornerRadius = dp(radiusDp).toFloat()
            setColor(Color.parseColor(fillHex))
            if (strokeHex != null) {
                setStroke(dp(strokeWidthDp), Color.parseColor(strokeHex))
            }
        }
    }

    private fun buildContentView(): View {
        val outerLayout = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(Color.parseColor(BG_CREAM))
            layoutParams = ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT
            )
        }

        val pagesFrame = FrameLayout(this).apply {
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                0,
                1f
            )
        }

        homeScroll = buildHomeScrollView()
        settingsScroll = buildSettingsScrollView()
        settingsScroll.visibility = View.GONE

        pagesFrame.addView(homeScroll)
        pagesFrame.addView(settingsScroll)
        outerLayout.addView(pagesFrame)

        // Spodní navigační lišta se záložkami Domů a Nastavení
        val topDivider = View(this).apply {
            setBackgroundColor(Color.parseColor(BORDER_SOFT))
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(1))
        }
        outerLayout.addView(topDivider)

        val bottomBar = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setBackgroundColor(Color.parseColor(CARD_WHITE))
            setPadding(dp(16), dp(8), dp(16), dp(10))
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            )
        }

        navHomeTab = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            setPadding(0, dp(6), 0, dp(6))
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply {
                rightMargin = dp(6)
            }
            setOnClickListener { switchTab(0) }
        }
        navHomeIcon = ImageView(this).apply {
            setImageResource(R.drawable.ic_home)
            layoutParams = LinearLayout.LayoutParams(dp(22), dp(22))
        }
        navHomeLabel = TextView(this).apply {
            text = "Domů"
            textSize = 12f
            gravity = Gravity.CENTER
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply { topMargin = dp(3) }
        }
        navHomeTab.addView(navHomeIcon)
        navHomeTab.addView(navHomeLabel)

        navSettingsTab = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            setPadding(0, dp(6), 0, dp(6))
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply {
                leftMargin = dp(6)
            }
            setOnClickListener { switchTab(1) }
        }
        navSettingsIcon = ImageView(this).apply {
            setImageResource(R.drawable.ic_settings)
            layoutParams = LinearLayout.LayoutParams(dp(22), dp(22))
        }
        navSettingsLabel = TextView(this).apply {
            text = "Nastavení"
            textSize = 12f
            gravity = Gravity.CENTER
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply { topMargin = dp(3) }
        }
        navSettingsTab.addView(navSettingsIcon)
        navSettingsTab.addView(navSettingsLabel)

        bottomBar.addView(navHomeTab)
        bottomBar.addView(navSettingsTab)
        outerLayout.addView(bottomBar)

        return outerLayout
    }

    private fun switchTab(index: Int) {
        if (activeTabIndex == index) return
        activeTabIndex = index
        if (index == 0) {
            homeScroll.visibility = View.VISIBLE
            settingsScroll.visibility = View.GONE
            updateServiceHeroUI()
            updateFilterTilesUI()
        } else {
            homeScroll.visibility = View.GONE
            settingsScroll.visibility = View.VISIBLE
            refreshSettingsUI()
        }
        updateBottomTabsUI()
    }

    private fun updateBottomTabsUI() {
        val homeActive = activeTabIndex == 0
        navHomeTab.background = if (homeActive) roundedBg(HERO_ACTIVE_SAGE, radiusDp = 16) else null
        ImageViewCompat.setImageTintList(
            navHomeIcon,
            ColorStateList.valueOf(Color.parseColor(if (homeActive) FOREST_PRIMARY else INK_MUTED))
        )
        navHomeLabel.setTextColor(Color.parseColor(if (homeActive) FOREST_PRIMARY else INK_MUTED))
        navHomeLabel.typeface = if (homeActive) sansMedium else Typeface.DEFAULT

        val settingsActive = activeTabIndex == 1
        navSettingsTab.background = if (settingsActive) roundedBg(HERO_ACTIVE_SAGE, radiusDp = 16) else null
        ImageViewCompat.setImageTintList(
            navSettingsIcon,
            ColorStateList.valueOf(Color.parseColor(if (settingsActive) FOREST_PRIMARY else INK_MUTED))
        )
        navSettingsLabel.setTextColor(Color.parseColor(if (settingsActive) FOREST_PRIMARY else INK_MUTED))
        navSettingsLabel.typeface = if (settingsActive) sansMedium else Typeface.DEFAULT
    }

    private fun buildHomeScrollView(): ScrollView {
        val scroll = ScrollView(this).apply {
            setBackgroundColor(Color.parseColor(BG_CREAM))
            isFillViewport = true
            isVerticalScrollBarEnabled = false
        }

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(20), dp(18), dp(20), dp(28))
        }
        scroll.addView(root)

        // 1. Horní hlavička (Editorial Serif styl)
        val topBar = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply { bottomMargin = dp(16) }
        }

        val brandCol = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
        }
        brandCol.addView(TextView(this).apply {
            text = "Stíhám to?"
            typeface = serifBold
            textSize = 28f
            setTextColor(Color.parseColor(INK_DARK))
        })
        brandCol.addView(TextView(this).apply {
            text = "PLZEŇ  ·  MHD V OKOLÍ"
            textSize = 10f
            letterSpacing = 0.14f
            typeface = sansMedium
            setTextColor(Color.parseColor(INK_MUTED))
        })
        topBar.addView(brandCol)

        // Kruhové tlačítko pro aktualizaci GPS
        val gpsBtn = FrameLayout(this).apply {
            background = roundedBg(CARD_WHITE, BORDER_SOFT, radiusDp = 22)
            layoutParams = LinearLayout.LayoutParams(dp(44), dp(44))
            setOnClickListener {
                PmdpRepository.setPinnedStopCis(this@MainActivity, null)
                searchInput.setText("")
                fetchWithCurrentGps()
            }
            val icon = ImageView(this@MainActivity).apply {
                setImageResource(R.drawable.ic_gps)
                layoutParams = FrameLayout.LayoutParams(dp(20), dp(20), Gravity.CENTER)
            }
            addView(icon)
        }
        topBar.addView(gpsBtn)
        root.addView(topBar)

        // Tlačítko oprávnění (zobrazí se jen pokud chybí GPS / Notifikace / Krokoměr)
        permissionBtn = TextView(this).apply {
            text = "Povolit polohu, krokoměr a notifikace"
            textSize = 13f
            typeface = sansMedium
            gravity = Gravity.CENTER
            setTextColor(Color.WHITE)
            background = roundedBg("#945D14", radiusDp = 16)
            setPadding(dp(16), dp(12), dp(16), dp(12))
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply { bottomMargin = dp(14) }
            setOnClickListener { requestNeededPermissions() }
        }
        root.addView(permissionBtn)

        // 2. Hero Karta stavu (čistá, bez Wi-Fi čipu – ten je nyní v Nastavení)
        heroCard = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            background = roundedBg(HERO_STONE, radiusDp = 24)
            setPadding(dp(20), dp(18), dp(20), dp(18))
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            )
        }

        heroTitleText = TextView(this).apply {
            text = "Klidový režim"
            typeface = serifBold
            textSize = 21f
            setTextColor(Color.parseColor(INK_DARK))
        }
        heroCard.addView(heroTitleText)

        heroSubtitleText = TextView(this).apply {
            text = "Automatické zapnutí při odchodu z domu."
            textSize = 13f
            setTextColor(Color.parseColor(INK_MUTED))
            setLineSpacing(dp(2).toFloat(), 1f)
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply { topMargin = dp(4) }
        }
        heroCard.addView(heroSubtitleText)

        val heroActionsRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply { topMargin = dp(16) }
        }

        liveToggleBtn = TextView(this).apply {
            text = "Spustit sledování"
            textSize = 13f
            typeface = sansMedium
            setTextColor(Color.WHITE)
            background = roundedBg(FOREST_PRIMARY, radiusDp = 20)
            setPadding(dp(18), dp(10), dp(18), dp(10))
            setOnClickListener {
                if (LiveDeparturesService.isRunning && !LiveDeparturesService.isSleepingStandby) {
                    val sleepIntent = Intent(this@MainActivity, LiveDeparturesService::class.java).apply {
                        action = LiveDeparturesService.ACTION_SLEEP_STANDBY
                        putExtra("sleep_reason", "Uspáno – probudí se při chůzi k zastávce")
                    }
                    startService(sleepIntent)
                    liveToggleBtn.postDelayed({ updateServiceHeroUI() }, 250)
                } else {
                    startLiveLockscreenService()
                }
            }
        }
        heroActionsRow.addView(liveToggleBtn)

        fullStopBtn = TextView(this).apply {
            text = "Vypnout úplně"
            textSize = 12f
            typeface = sansMedium
            setTextColor(Color.parseColor(INK_MUTED))
            background = roundedBg("#DFDDD2", radiusDp = 20)
            setPadding(dp(14), dp(10), dp(14), dp(10))
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply { leftMargin = dp(8) }
            setOnClickListener {
                val stopIntent = Intent(this@MainActivity, LiveDeparturesService::class.java).apply {
                    action = LiveDeparturesService.ACTION_STOP
                }
                startService(stopIntent)
                stopService(Intent(this@MainActivity, LiveDeparturesService::class.java))
                liveToggleBtn.postDelayed({ updateServiceHeroUI() }, 250)
            }
        }
        heroActionsRow.addView(fullStopBtn)
        heroCard.addView(heroActionsRow)
        root.addView(heroCard)

        // 3. Řada filtrů: vlevo 3 módy dopravy (Vše, Tramvaje, Bus a Trol),
        //    vizuálně oddělené přepážkou a odsazením od přepínače "Chytrý uzel" vpravo
        val filterSection = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.BOTTOM
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply { topMargin = dp(18) }
        }

        val transportCol = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 3f)
        }
        transportCol.addView(TextView(this).apply {
            text = "FILTR DOPRAVY"
            textSize = 9.5f
            letterSpacing = 0.12f
            typeface = sansMedium
            setTextColor(Color.parseColor(INK_MUTED))
            setPadding(dp(6), 0, 0, dp(6))
        })

        filterTilesRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            )
        }

        val tractionItems = listOf(
            Triple("all", "Vše", R.drawable.ic_all_lines),
            Triple("tram", "Tramvaje", R.drawable.ic_tram),
            Triple("bus_trol", "Bus a Trol", R.drawable.ic_bus)
        )

        for ((key, label, iconRes) in tractionItems) {
            val tileWrap = LinearLayout(this).apply {
                tag = key
                orientation = LinearLayout.VERTICAL
                gravity = Gravity.CENTER_HORIZONTAL
                layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
                setOnClickListener {
                    PmdpRepository.setPreferredTraction(this@MainActivity, key)
                    updateFilterTilesUI()
                    triggerRefresh()
                }
            }
            val iconBox = LinearLayout(this).apply {
                gravity = Gravity.CENTER
                layoutParams = LinearLayout.LayoutParams(dp(54), dp(54))
            }
            val iconView = ImageView(this).apply {
                setImageResource(iconRes)
                layoutParams = LinearLayout.LayoutParams(dp(24), dp(24))
            }
            iconBox.addView(iconView)
            tileWrap.addView(iconBox)

            val lblView = TextView(this).apply {
                text = label
                textSize = 11f
                gravity = Gravity.CENTER
                maxLines = 1
                layoutParams = LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.WRAP_CONTENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT
                ).apply { topMargin = dp(6) }
            }
            tileWrap.addView(lblView)
            filterTilesRow.addView(tileWrap)
        }
        transportCol.addView(filterTilesRow)
        filterSection.addView(transportCol)

        val verticalDivider = View(this).apply {
            setBackgroundColor(Color.parseColor("#D6D3C6"))
            layoutParams = LinearLayout.LayoutParams(dp(1), dp(58)).apply {
                leftMargin = dp(10)
                rightMargin = dp(10)
                bottomMargin = dp(8)
            }
        }
        filterSection.addView(verticalDivider)

        val clusterCol = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1.05f)
        }
        clusterCol.addView(TextView(this).apply {
            text = "REŽIM OKOLÍ"
            textSize = 9.5f
            letterSpacing = 0.12f
            typeface = sansMedium
            setTextColor(Color.parseColor(INK_MUTED))
            gravity = Gravity.CENTER
            setPadding(0, 0, 0, dp(6))
        })

        val clusterWrap = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            )
            setOnClickListener {
                val currMulti = PmdpRepository.isMultiStopMode(this@MainActivity) &&
                        PmdpRepository.getPinnedStopCis(this@MainActivity) == null
                PmdpRepository.setPinnedStopCis(this@MainActivity, null)
                PmdpRepository.setMultiStopMode(this@MainActivity, !currMulti)
                updateFilterTilesUI()
                triggerRefresh()
            }
        }
        clusterTileBox = LinearLayout(this).apply {
            gravity = Gravity.CENTER
            layoutParams = LinearLayout.LayoutParams(dp(54), dp(54))
        }
        clusterTileIcon = ImageView(this).apply {
            setImageResource(R.drawable.ic_nodes)
            layoutParams = LinearLayout.LayoutParams(dp(24), dp(24))
        }
        clusterTileBox.addView(clusterTileIcon)
        clusterWrap.addView(clusterTileBox)

        clusterTileLabel = TextView(this).apply {
            text = "Chytrý uzel"
            textSize = 11f
            gravity = Gravity.CENTER
            maxLines = 1
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply { topMargin = dp(6) }
        }
        clusterWrap.addView(clusterTileLabel)
        clusterCol.addView(clusterWrap)
        filterSection.addView(clusterCol)
        root.addView(filterSection)

        // 4. Vyhledávací pole
        val searchBox = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            background = roundedBg(CARD_WHITE, BORDER_SOFT, radiusDp = 16)
            setPadding(dp(14), dp(4), dp(14), dp(4))
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply { topMargin = dp(18) }
        }
        val searchIcon = ImageView(this).apply {
            setImageResource(R.drawable.ic_search)
            layoutParams = LinearLayout.LayoutParams(dp(18), dp(18)).apply { rightMargin = dp(10) }
        }
        searchBox.addView(searchIcon)

        searchInput = EditText(this).apply {
            hint = "Najít zastávku v Plzni…"
            setHintTextColor(Color.parseColor("#9AA29C"))
            setTextColor(Color.parseColor(INK_DARK))
            textSize = 14f
            background = null
            setSingleLine(true)
            setPadding(0, dp(10), 0, dp(10))
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
        }
        searchInput.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {
                onSearchQueryChanged(s?.toString() ?: "")
            }
            override fun afterTextChanged(s: Editable?) {}
        })
        searchBox.addView(searchInput)
        root.addView(searchBox)

        searchResultsLayout = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            visibility = View.GONE
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply { topMargin = dp(6) }
        }
        root.addView(searchResultsLayout)

        // 5. Horizontální řada okolních zastávek
        nearbyStopsScroll = HorizontalScrollView(this).apply {
            isHorizontalScrollBarEnabled = false
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply { topMargin = dp(12) }
        }
        nearbyStopsRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
        }
        nearbyStopsScroll.addView(nearbyStopsRow)
        root.addView(nearbyStopsScroll)

        // 6. Nadpis aktuální zastávky
        val stopHeaderBox = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply { topMargin = dp(20); bottomMargin = dp(12) }
        }
        headerStopTitle = TextView(this).apply {
            text = "Načítám zastávky…"
            typeface = serifBold
            textSize = 23f
            setTextColor(Color.parseColor(INK_DARK))
        }
        headerStopMeta = TextView(this).apply {
            text = "Počítám pěší trasu k nejbližším zastávkám"
            textSize = 13f
            setTextColor(Color.parseColor(INK_MUTED))
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply { topMargin = dp(3) }
        }
        stopHeaderBox.addView(headerStopTitle)
        stopHeaderBox.addView(headerStopMeta)
        root.addView(stopHeaderBox)

        // 7. Kontejner pro karty odjezdů
        departuresContainer = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
        }
        root.addView(departuresContainer)

        return scroll
    }

    /**
     * Postaví přehlednou stránku Nastavení s lidsky napsanými popisky (zobrazitelnými klepnutím na "?" u každé položky).
     */
    private fun buildSettingsScrollView(): ScrollView {
        allHelpTextViews.clear()
        settingsRefreshCallbacks.clear()

        val scroll = ScrollView(this).apply {
            setBackgroundColor(Color.parseColor(BG_CREAM))
            isFillViewport = true
            isVerticalScrollBarEnabled = false
        }

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(20), dp(18), dp(20), dp(32))
        }
        scroll.addView(root)

        // Hlavička stránky Nastavení
        val headerRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply { bottomMargin = dp(16) }
        }

        val titleCol = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
        }
        titleCol.addView(TextView(this).apply {
            text = "Nastavení"
            typeface = serifBold
            textSize = 28f
            setTextColor(Color.parseColor(INK_DARK))
        })
        titleCol.addView(TextView(this).apply {
            text = "CHŮZE, ZASTÁVKY A CHYTRÁ AUTOMATIKA"
            textSize = 10f
            letterSpacing = 0.12f
            typeface = sansMedium
            setTextColor(Color.parseColor(INK_MUTED))
        })
        headerRow.addView(titleCol)

        toggleAllHelpBtn = TextView(this).apply {
            text = "Popisky ?"
            textSize = 12f
            typeface = sansMedium
            setTextColor(Color.parseColor(FOREST_PRIMARY))
            background = roundedBg(CARD_WHITE, BORDER_SOFT, radiusDp = 16)
            setPadding(dp(12), dp(7), dp(12), dp(7))
            setOnClickListener {
                allHelpExpanded = !allHelpExpanded
                for (tv in allHelpTextViews) {
                    tv.visibility = if (allHelpExpanded) View.VISIBLE else View.GONE
                }
                toggleAllHelpBtn.text = if (allHelpExpanded) "Skrýt ?" else "Popisky ?"
                toggleAllHelpBtn.background = roundedBg(
                    if (allHelpExpanded) HERO_ACTIVE_SAGE else CARD_WHITE,
                    BORDER_SOFT,
                    radiusDp = 16
                )
            }
        }
        headerRow.addView(toggleAllHelpBtn)
        root.addView(headerRow)

        // === SEKCE 1: CHŮZE A VYHODNOCENÍ SPOJŮ ===
        root.addView(buildSectionLabel("CHŮZE A SPOJE"))
        val walkCard = buildSettingsCard()

        walkCard.addView(
            buildChoiceSettingRow(
                title = "Tempo chůze",
                helpText = "Podle tvého tempa počítáme, kolik minut půjdeš na zastávku a jestli spoj v klidu stihneš.",
                options = listOf(
                    "relaxed" to "Klidné",
                    "normal" to "Běžné",
                    "brisk" to "Svižné"
                ),
                currentGetter = { PmdpRepository.getWalkSpeedMode(this) },
                onSelected = { mode ->
                    PmdpRepository.setWalkSpeedMode(this, mode)
                    triggerRefresh()
                }
            )
        )

        walkCard.addView(buildCardDivider())

        walkCard.addView(
            buildChoiceSettingRow(
                title = "Rezerva na přechody a semafory",
                helpText = "Přidá malou časovou rezervu na čekání na červenou, aby ti tramvaj neujela těsně před nosem.",
                options = listOf(
                    0 to "Bez rezervy",
                    30 to "+30 s",
                    60 to "+1 min"
                ),
                currentGetter = { PmdpRepository.getCrosswalkBufferSec(this) },
                onSelected = { sec ->
                    PmdpRepository.setCrosswalkBufferSec(this, sec)
                    triggerRefresh()
                }
            )
        )

        walkCard.addView(buildCardDivider())

        walkCard.addView(
            buildChoiceSettingRow(
                title = "Ujíždějící spoje v notifikaci",
                helpText = "Schová z notifikace spoje se stavem Nestíháš, aby v ní zbylo místo na další odjezdy, které reálně stihneš.",
                options = listOf(
                    false to "Ukazovat vše",
                    true to "Skrýt Nestíháš"
                ),
                currentGetter = { PmdpRepository.isHideMissedInNotif(this) },
                onSelected = { hide ->
                    PmdpRepository.setHideMissedInNotif(this, hide)
                    triggerRefresh()
                }
            )
        )

        walkCard.addView(buildCardDivider())

        walkCard.addView(
            buildChoiceSettingRow(
                title = "Počet spojů v notifikaci",
                helpText = "Určuje, kolik nejbližších odjezdů se vypíše pod sebe po rozbalení notifikace na zamykací obrazovce.",
                options = listOf(
                    3 to "3 spoje",
                    4 to "4 spoje",
                    6 to "6 spojů"
                ),
                currentGetter = { PmdpRepository.getNotifDeparturesCount(this) },
                onSelected = { count ->
                    PmdpRepository.setNotifDeparturesCount(this, count)
                    triggerRefresh()
                }
            )
        )
        root.addView(walkCard)

        // === SEKCE 2: ZASTÁVKY A LINKY V OKOLÍ ===
        root.addView(buildSectionLabel("ZASTÁVKY A LINKY V OKOLÍ"))
        val stopsCard = buildSettingsCard()

        stopsCard.addView(
            buildChoiceSettingRow(
                title = "Dosah Chytrého uzlu",
                helpText = "Jak daleko kolem tebe má aplikace hledat další zastávky, když máš zapnutý Chytrý uzel.",
                options = listOf(
                    300 to "Blízko (300 m)",
                    380 to "Střední (380 m)",
                    520 to "Širší (520 m)"
                ),
                currentGetter = { PmdpRepository.getClusterRadiusMeters(this) },
                onSelected = { meters ->
                    PmdpRepository.setClusterRadiusMeters(this, meters)
                    triggerRefresh()
                }
            )
        )

        stopsCard.addView(buildCardDivider())

        stopsCard.addView(
            buildChoiceSettingRow(
                title = "Přibrat tramvaj v okolí",
                helpText = "Když máš u sebe jen autobus nebo trolejbus, automaticky načte i nejbližší tramvaj v docházkové vzdálenosti.",
                options = listOf(
                    true to "Automaticky",
                    false to "Jen nejbližší"
                ),
                currentGetter = { PmdpRepository.isAutoIncludeTramEnabled(this) },
                onSelected = { enabled ->
                    PmdpRepository.setAutoIncludeTramEnabled(this, enabled)
                    triggerRefresh()
                }
            )
        )

        stopsCard.addView(buildCardDivider())

        stopsCard.addView(buildFavoriteLinesSettingBlock())
        root.addView(stopsCard)

        // === SEKCE 3: AUTOMATIKA A ÚSPORA BATERIE ===
        root.addView(buildSectionLabel("AUTOMATIKA A SPÁNEK"))
        val autoCard = buildSettingsCard()

        autoCard.addView(
            buildChoiceSettingRow(
                title = "Režim na domácí Wi-Fi",
                helpText = "Dokud jsi na domácí nebo pracovní Wi-Fi, sledování odpočívá s nulovou spotřebou a zapne se až při odchodu.",
                options = listOf(
                    true to "Doma spí",
                    false to "Běží i na Wi-Fi"
                ),
                currentGetter = { PmdpRepository.isSleepOnWifiEnabled(this) },
                onSelected = { enabled ->
                    PmdpRepository.setSleepOnWifiEnabled(this, enabled)
                    updateServiceHeroUI()
                    triggerRefresh()
                }
            )
        )

        autoCard.addView(buildCardDivider())

        autoCard.addView(buildHomeWifiSettingBlock())

        autoCard.addView(buildCardDivider())

        autoCard.addView(
            buildChoiceSettingRow(
                title = "Probuzení chůzí (krokoměr)",
                helpText = "Kolik souvislých kroků stačí udělat, aby se aplikace sama probudila z klidu a rozsvítila odjezdy.",
                options = listOf(
                    15 to "Citlivé (15)",
                    30 to "Běžné (30)",
                    50 to "Odolné (50)"
                ),
                currentGetter = { PmdpRepository.getWakeUpStepsThreshold(this) },
                onSelected = { steps ->
                    PmdpRepository.setWakeUpStepsThreshold(this, steps)
                    updateServiceHeroUI()
                }
            )
        )

        autoCard.addView(buildCardDivider())

        autoCard.addView(
            buildChoiceSettingRow(
                title = "Pauza po jízdě v MHD",
                helpText = "Po vystoupení z tramvaje nebo autobusu dá sledování na chvíli pauzu, aby se ti cestou ze zastávky hned znovu nezapínalo.",
                options = listOf(
                    0 to "Bez pauzy",
                    10 to "10 min",
                    20 to "20 min"
                ),
                currentGetter = { PmdpRepository.getPostRideCooldownMinutes(this) },
                onSelected = { min ->
                    PmdpRepository.setPostRideCooldownMinutes(this, min)
                }
            )
        )

        autoCard.addView(buildCardDivider())

        autoCard.addView(
            buildChoiceSettingRow(
                title = "Rozpoznání procházky",
                helpText = "Když jen tak procházíš městem a mineš pěšky několik zastávek bez nástupu, aplikace se sama uspí.",
                options = listOf(
                    2 to "Po 2 zastávkách",
                    3 to "Po 3 zastávkách",
                    0 to "Vypnuto"
                ),
                currentGetter = { PmdpRepository.getWalkPassedStopsLimit(this) },
                onSelected = { limit ->
                    PmdpRepository.setWalkPassedStopsLimit(this, limit)
                }
            )
        )
        root.addView(autoCard)

        // === SEKCE 4: OPRÁVNĚNÍ A BĚH NA POZADÍ ===
        root.addView(buildSectionLabel("STAV SYSTÉMU A BATERIE"))
        val sysCard = buildSettingsCard()
        sysCard.addView(buildSystemDiagnosticsBlock())
        root.addView(sysCard)

        // Patička s verzí
        val footerVersion = TextView(this).apply {
            val vName = try {
                packageManager.getPackageInfo(packageName, 0).versionName
            } catch (_: Exception) {
                "0.0.13"
            }
            text = "Stíhám to? v$vName · Plzeň MHD"
            textSize = 11f
            setTextColor(Color.parseColor(INK_MUTED))
            gravity = Gravity.CENTER_HORIZONTAL
            typeface = sansMedium
            setPadding(0, dp(22), 0, dp(8))
        }
        root.addView(footerVersion)

        return scroll
    }

    private fun buildSectionLabel(textStr: String): TextView {
        return TextView(this).apply {
            text = textStr
            textSize = 10f
            letterSpacing = 0.12f
            typeface = sansMedium
            setTextColor(Color.parseColor(INK_MUTED))
            setPadding(dp(6), dp(16), 0, dp(8))
        }
    }

    private fun buildSettingsCard(): LinearLayout {
        return LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            background = roundedBg(CARD_WHITE, BORDER_SOFT, radiusDp = 20)
            setPadding(dp(16), dp(14), dp(16), dp(14))
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            )
        }
    }

    private fun buildCardDivider(): View {
        return View(this).apply {
            setBackgroundColor(Color.parseColor(BORDER_SOFT))
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                dp(1)
            ).apply {
                topMargin = dp(14)
                bottomMargin = dp(14)
            }
        }
    }

    private fun <T> buildChoiceSettingRow(
        title: String,
        helpText: String,
        options: List<Pair<T, String>>,
        currentGetter: () -> T,
        onSelected: (T) -> Unit
    ): View {
        val block = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            )
        }

        val helpView = TextView(this).apply {
            text = helpText
            textSize = 12.5f
            setTextColor(Color.parseColor(INK_MUTED))
            setLineSpacing(dp(2).toFloat(), 1f)
            visibility = View.GONE
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply { topMargin = dp(4); bottomMargin = dp(4) }
        }
        allHelpTextViews.add(helpView)

        val headerRow = buildSettingHeaderWithHelp(title, helpView)
        block.addView(headerRow)
        block.addView(helpView)

        val pillsRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply { topMargin = dp(10) }
        }

        val pillViews = mutableListOf<Pair<T, TextView>>()

        fun updateSelectionUI() {
            val curr = currentGetter()
            for ((value, tv) in pillViews) {
                val active = value == curr
                tv.background = roundedBg(if (active) FOREST_PRIMARY else PILL_INACTIVE, radiusDp = 14)
                tv.setTextColor(if (active) Color.WHITE else Color.parseColor(INK_DARK))
                tv.typeface = if (active) sansMedium else Typeface.DEFAULT
            }
        }

        for ((idx, pair) in options.withIndex()) {
            val (optVal, optLabel) = pair
            val pill = TextView(this).apply {
                text = optLabel
                textSize = 12.5f
                gravity = Gravity.CENTER
                setSingleLine(true)
                setPadding(dp(10), dp(9), dp(10), dp(9))
                layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply {
                    if (idx > 0) leftMargin = dp(6)
                }
                setOnClickListener {
                    onSelected(optVal)
                    updateSelectionUI()
                }
            }
            pillViews.add(optVal to pill)
            pillsRow.addView(pill)
        }

        settingsRefreshCallbacks.add { updateSelectionUI() }
        updateSelectionUI()
        block.addView(pillsRow)

        return block
    }

    private fun buildSettingHeaderWithHelp(title: String, helpView: TextView): LinearLayout {
        val headerRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            )
        }

        val titleTv = TextView(this).apply {
            text = title
            textSize = 15f
            typeface = sansMedium
            setTextColor(Color.parseColor(INK_DARK))
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
        }

        val qBadge = TextView(this).apply {
            text = "?"
            textSize = 11.5f
            typeface = sansMedium
            gravity = Gravity.CENTER
            setTextColor(Color.parseColor(FOREST_PRIMARY))
            background = roundedBg(HERO_STONE, radiusDp = 11)
            layoutParams = LinearLayout.LayoutParams(dp(22), dp(22)).apply { leftMargin = dp(8) }
        }

        val toggleListener = View.OnClickListener {
            val show = helpView.visibility != View.VISIBLE
            helpView.visibility = if (show) View.VISIBLE else View.GONE
            qBadge.background = roundedBg(if (show) FOREST_PRIMARY else HERO_STONE, radiusDp = 11)
            qBadge.setTextColor(if (show) Color.WHITE else Color.parseColor(FOREST_PRIMARY))
        }
        headerRow.setOnClickListener(toggleListener)
        qBadge.setOnClickListener(toggleListener)

        headerRow.addView(titleTv)
        headerRow.addView(qBadge)
        return headerRow
    }

    private fun buildFavoriteLinesSettingBlock(): View {
        val block = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            )
        }

        val helpView = TextView(this).apply {
            text = "Spoje tvých prioritních linek se v aplikaci i v notifikaci řadí vždy na první místo, dokud se dají stihnout."
            textSize = 12.5f
            setTextColor(Color.parseColor(INK_MUTED))
            setLineSpacing(dp(2).toFloat(), 1f)
            visibility = View.GONE
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply { topMargin = dp(4); bottomMargin = dp(4) }
        }
        allHelpTextViews.add(helpView)

        block.addView(buildSettingHeaderWithHelp("Prioritní linky", helpView))
        block.addView(helpView)

        val statusRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply { topMargin = dp(8) }
        }

        favLinesSummaryText = TextView(this).apply {
            textSize = 13f
            setTextColor(Color.parseColor(INK_MUTED))
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply {
                rightMargin = dp(8)
            }
        }
        statusRow.addView(favLinesSummaryText)

        favExpandBtn = TextView(this).apply {
            text = "Upravit linky"
            textSize = 12f
            typeface = sansMedium
            setTextColor(Color.parseColor(FOREST_PRIMARY))
            background = roundedBg(HERO_ACTIVE_SAGE, radiusDp = 14)
            setPadding(dp(12), dp(7), dp(12), dp(7))
            setOnClickListener {
                favPickerExpanded = !favPickerExpanded
                favLinesPickerContainer.visibility = if (favPickerExpanded) View.VISIBLE else View.GONE
                favExpandBtn.text = if (favPickerExpanded) "Hotovo" else "Upravit linky"
            }
        }
        statusRow.addView(favExpandBtn)
        block.addView(statusRow)

        favLinesPickerContainer = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            visibility = View.GONE
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply { topMargin = dp(12) }
        }
        block.addView(favLinesPickerContainer)

        settingsRefreshCallbacks.add { rebuildFavoriteLinesUI() }
        return block
    }

    private fun rebuildFavoriteLinesUI() {
        val favs = PmdpRepository.getFavoriteLines(this)
        val sortedFavs = favs.sortedWith(
            compareBy<String> { it.startsWith("N", ignoreCase = true) }
                .thenBy { it.filter { ch -> ch.isDigit() }.toIntOrNull() ?: 999 }
        )
        favLinesSummaryText.text = if (sortedFavs.isEmpty()) {
            "Zatím nemáš vybranou žádnou prioritní linku"
        } else {
            "Vybráno: linky ${sortedFavs.joinToString(", ")}"
        }

        favLinesPickerContainer.removeAllViews()
        val allLines = PmdpRepository.getAllKnownLines(this)
        val columns = 5
        var currentRow: LinearLayout? = null

        for ((idx, lineName) in allLines.withIndex()) {
            if (idx % columns == 0) {
                currentRow = LinearLayout(this).apply {
                    orientation = LinearLayout.HORIZONTAL
                    layoutParams = LinearLayout.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT,
                        ViewGroup.LayoutParams.WRAP_CONTENT
                    ).apply { bottomMargin = dp(6) }
                }
                favLinesPickerContainer.addView(currentRow)
            }
            val isFav = favs.contains(lineName)
            val chip = TextView(this).apply {
                text = lineName
                textSize = 13f
                gravity = Gravity.CENTER
                typeface = if (isFav) Typeface.DEFAULT_BOLD else sansMedium
                setTextColor(if (isFav) Color.WHITE else Color.parseColor(INK_DARK))
                background = roundedBg(if (isFav) FOREST_PRIMARY else PILL_INACTIVE, radiusDp = 12)
                setPadding(0, dp(8), 0, dp(8))
                layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply {
                    if (idx % columns > 0) leftMargin = dp(6)
                }
                setOnClickListener {
                    PmdpRepository.toggleFavoriteLine(this@MainActivity, lineName)
                    rebuildFavoriteLinesUI()
                    triggerRefresh()
                }
            }
            currentRow?.addView(chip)
        }

        val remainder = allLines.size % columns
        if (remainder != 0 && currentRow != null) {
            for (i in 0 until (columns - remainder)) {
                currentRow.addView(View(this).apply {
                    layoutParams = LinearLayout.LayoutParams(0, dp(1), 1f).apply { leftMargin = dp(6) }
                })
            }
        }

        if (favs.isNotEmpty()) {
            val clearBtn = TextView(this).apply {
                text = "Zrušit všechny prioritní linky"
                textSize = 12f
                typeface = sansMedium
                setTextColor(Color.parseColor("#963A2F"))
                gravity = Gravity.CENTER
                background = roundedBg("#F4E3E0", radiusDp = 12)
                setPadding(dp(12), dp(8), dp(12), dp(8))
                layoutParams = LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT
                ).apply { topMargin = dp(4) }
                setOnClickListener {
                    PmdpRepository.clearFavoriteLines(this@MainActivity)
                    rebuildFavoriteLinesUI()
                    triggerRefresh()
                }
            }
            favLinesPickerContainer.addView(clearBtn)
        }
    }

    private fun buildHomeWifiSettingBlock(): View {
        val block = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            )
        }

        val helpView = TextView(this).apply {
            text = "Aplikace usne a přestane čerpat baterii pouze na zde uložených Wi-Fi sítích. Na veřejných Wi-Fi v tramvajích PMDP nebo v kavárnách se neuspává."
            textSize = 12.5f
            setTextColor(Color.parseColor(INK_MUTED))
            setLineSpacing(dp(2).toFloat(), 1f)
            visibility = View.GONE
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply { topMargin = dp(4); bottomMargin = dp(4) }
        }
        allHelpTextViews.add(helpView)

        block.addView(buildSettingHeaderWithHelp("Klidové Wi-Fi zóny (Doma / Práce)", helpView))
        block.addView(helpView)

        val wifiContainer = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply { topMargin = dp(8) }
        }
        block.addView(wifiContainer)

        fun rebuildWifiUI() {
            wifiContainer.removeAllViews()
            val currentSsid = SmartTriggerMonitor.getConnectedWifiSsid(this)
            val savedSsids = PmdpRepository.getHomeWifiSsids(this)

            val statusTv = TextView(this).apply {
                text = if (!currentSsid.isNullOrEmpty()) {
                    val isSaved = savedSsids.contains(currentSsid)
                    "Aktuální Wi-Fi: $currentSsid (${if (isSaved) "Uložená klidová zóna" else "Není uložena"})"
                } else {
                    "Aktuálně nejste na Wi-Fi síti"
                }
                textSize = 13f
                setTextColor(Color.parseColor(INK_DARK))
                layoutParams = LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT
                ).apply { bottomMargin = dp(6) }
            }
            wifiContainer.addView(statusTv)

            if (!currentSsid.isNullOrEmpty() && !savedSsids.contains(currentSsid) && !PmdpRepository.isDisallowedPublicSsid(currentSsid)) {
                val addBtn = TextView(this).apply {
                    text = "+ Přidat síť \"$currentSsid\" mezi domácí"
                    textSize = 12.5f
                    typeface = sansMedium
                    setTextColor(Color.WHITE)
                    gravity = Gravity.CENTER
                    background = roundedBg(FOREST_PRIMARY, radiusDp = 14)
                    setPadding(dp(14), dp(9), dp(14), dp(9))
                    layoutParams = LinearLayout.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT,
                        ViewGroup.LayoutParams.WRAP_CONTENT
                    ).apply { bottomMargin = dp(8) }
                    setOnClickListener {
                        PmdpRepository.addHomeWifiSsid(this@MainActivity, currentSsid)
                        rebuildWifiUI()
                        updateServiceHeroUI()
                    }
                }
                wifiContainer.addView(addBtn)
            }

            if (savedSsids.isEmpty()) {
                val emptyTv = TextView(this).apply {
                    text = "Zatím nemáte uloženou žádnou specifickou Wi-Fi (aplikace se uspává na jakékoliv ne-veřejné Wi-Fi)."
                    textSize = 12f
                    setTextColor(Color.parseColor(INK_MUTED))
                }
                wifiContainer.addView(emptyTv)
            } else {
                for (ssid in savedSsids) {
                    val row = LinearLayout(this).apply {
                        orientation = LinearLayout.HORIZONTAL
                        gravity = Gravity.CENTER_VERTICAL
                        background = roundedBg(HERO_STONE, radiusDp = 12)
                        setPadding(dp(12), dp(8), dp(12), dp(8))
                        layoutParams = LinearLayout.LayoutParams(
                            ViewGroup.LayoutParams.MATCH_PARENT,
                            ViewGroup.LayoutParams.WRAP_CONTENT
                        ).apply { bottomMargin = dp(6) }
                    }
                    val nameTv = TextView(this).apply {
                        text = "📶 $ssid"
                        textSize = 13f
                        typeface = sansMedium
                        setTextColor(Color.parseColor(INK_DARK))
                        layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
                    }
                    val delBtn = TextView(this).apply {
                        text = "Odebrat"
                        textSize = 12f
                        setTextColor(Color.parseColor("#963A2F"))
                        setPadding(dp(8), dp(4), dp(8), dp(4))
                        setOnClickListener {
                            PmdpRepository.removeHomeWifiSsid(this@MainActivity, ssid)
                            rebuildWifiUI()
                            updateServiceHeroUI()
                        }
                    }
                    row.addView(nameTv)
                    row.addView(delBtn)
                    wifiContainer.addView(row)
                }
            }
        }

        settingsRefreshCallbacks.add { rebuildWifiUI() }
        rebuildWifiUI()
        return block
    }

    private fun buildSystemDiagnosticsBlock(): View {
        val block = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            )
        }

        val helpView = TextView(this).apply {
            text = "Aby se odjezdy spolehlivě rozsvítily i s telefonem v kapse, systém nesmí aplikaci na pozadí uspávat."
            textSize = 12.5f
            setTextColor(Color.parseColor(INK_MUTED))
            setLineSpacing(dp(2).toFloat(), 1f)
            visibility = View.GONE
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply { topMargin = dp(4); bottomMargin = dp(4) }
        }
        allHelpTextViews.add(helpView)

        block.addView(buildSettingHeaderWithHelp("Oprávnění a běh na pozadí", helpView))
        block.addView(helpView)

        systemDiagnosticsContainer = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply { topMargin = dp(8) }
        }
        block.addView(systemDiagnosticsContainer)

        settingsRefreshCallbacks.add { rebuildSystemDiagnosticsUI() }
        return block
    }

    @SuppressLint("BatteryLife")
    private fun rebuildSystemDiagnosticsUI() {
        if (!::systemDiagnosticsContainer.isInitialized) return
        systemDiagnosticsContainer.removeAllViews()

        val hasLoc = ContextCompat.checkSelfPermission(
            this,
            Manifest.permission.ACCESS_FINE_LOCATION
        ) == PackageManager.PERMISSION_GRANTED

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

        val items = listOf(
            "Přesná poloha (GPS)" to hasLoc,
            "Rozpoznání chůze (krokoměr)" to hasActivity,
            "Notifikace na zamykací obrazovce" to hasNotif,
            "Neomezený běh na pozadí (baterie)" to ignoringBatteryOpt
        )

        for ((label, ok) in items) {
            val row = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                layoutParams = LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT
                ).apply { bottomMargin = dp(6) }
            }
            val nameTv = TextView(this).apply {
                text = label
                textSize = 13f
                setTextColor(Color.parseColor(INK_DARK))
                layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
            }
            val badgeTv = TextView(this).apply {
                text = if (ok) "V pořádku" else "Chybí"
                textSize = 11f
                typeface = sansMedium
                setTextColor(Color.parseColor(if (ok) "#255238" else "#963A2F"))
                background = roundedBg(if (ok) "#E2EFE6" else "#F4E3E0", radiusDp = 10)
                setPadding(dp(10), dp(3), dp(10), dp(3))
            }
            row.addView(nameTv)
            row.addView(badgeTv)
            systemDiagnosticsContainer.addView(row)
        }

        if (!hasLoc || !hasActivity || !hasNotif) {
            val permBtn = TextView(this).apply {
                text = "Doplnit chybějící oprávnění"
                textSize = 12.5f
                typeface = sansMedium
                gravity = Gravity.CENTER
                setTextColor(Color.WHITE)
                background = roundedBg(FOREST_PRIMARY, radiusDp = 14)
                setPadding(dp(14), dp(10), dp(14), dp(10))
                layoutParams = LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT
                ).apply { topMargin = dp(6) }
                setOnClickListener { requestNeededPermissions() }
            }
            systemDiagnosticsContainer.addView(permBtn)
        }

        if (!ignoringBatteryOpt && Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            val battBtn = TextView(this).apply {
                text = "Povolit běh na pozadí bez uspávání"
                textSize = 12.5f
                typeface = sansMedium
                gravity = Gravity.CENTER
                setTextColor(Color.parseColor(FOREST_PRIMARY))
                background = roundedBg(HERO_ACTIVE_SAGE, radiusDp = 14)
                setPadding(dp(14), dp(10), dp(14), dp(10))
                layoutParams = LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT
                ).apply { topMargin = dp(6) }
                setOnClickListener {
                    try {
                        val intent = Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS).apply {
                            data = Uri.parse("package:$packageName")
                        }
                        startActivity(intent)
                    } catch (_: Throwable) {
                        try {
                            val fallback = Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS).apply {
                                data = Uri.parse("package:$packageName")
                            }
                            startActivity(fallback)
                        } catch (_: Throwable) {
                        }
                    }
                }
            }
            systemDiagnosticsContainer.addView(battBtn)
        }
    }

    private fun refreshSettingsUI() {
        for (cb in settingsRefreshCallbacks) {
            cb()
        }
    }

    private fun onSearchQueryChanged(query: String) {
        searchJob?.cancel()
        val trimmed = query.trim()
        if (trimmed.isEmpty()) {
            searchResultsLayout.visibility = View.GONE
            searchResultsLayout.removeAllViews()
            return
        }

        searchJob = lifecycleScope.launch {
            val matches = PmdpRepository.searchStopsByName(this@MainActivity, trimmed, limit = 6)
            searchResultsLayout.removeAllViews()
            if (matches.isEmpty()) {
                searchResultsLayout.visibility = View.GONE
                return@launch
            }
            for (stop in matches) {
                val itemBtn = TextView(this@MainActivity).apply {
                    val linesTxt = stop.lines.take(6).joinToString(", ") { it.name }
                    text = "${stop.name}  ·  linky $linesTxt"
                    setTextColor(Color.parseColor(INK_DARK))
                    textSize = 14f
                    background = roundedBg(CARD_WHITE, BORDER_SOFT, radiusDp = 12)
                    setPadding(dp(14), dp(12), dp(14), dp(12))
                    layoutParams = LinearLayout.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT,
                        ViewGroup.LayoutParams.WRAP_CONTENT
                    ).apply { bottomMargin = dp(4) }
                    setOnClickListener {
                        PmdpRepository.setPinnedStopCis(this@MainActivity, stop.cisNumber)
                        searchResultsLayout.visibility = View.GONE
                        updateFilterTilesUI()
                        triggerRefresh()
                    }
                }
                searchResultsLayout.addView(itemBtn)
            }
            searchResultsLayout.visibility = View.VISIBLE
        }
    }

    private fun updatePermissionButtonVisibility() {
        val hasFineLoc = ContextCompat.checkSelfPermission(
            this,
            Manifest.permission.ACCESS_FINE_LOCATION
        ) == PackageManager.PERMISSION_GRANTED

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

        permissionBtn.visibility = if (hasFineLoc && hasNotif && hasActivityRec) View.GONE else View.VISIBLE
    }

    private fun requestNeededPermissions() {
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

    private fun updateServiceHeroUI() {
        val ssid = SmartTriggerMonitor.getConnectedWifiSsid(this)
        val onHomeWifi = SmartTriggerMonitor.isConnectedToHomeWifi(this)
        val onWifi = SmartTriggerMonitor.isConnectedToWifi(this)
        val netText = when {
            onHomeWifi -> "Klidová Wi-Fi${if (!ssid.isNullOrEmpty()) " ($ssid)" else ""}"
            onWifi -> "Veřejná Wi-Fi${if (!ssid.isNullOrEmpty()) " ($ssid)" else ""}"
            else -> "Mobilní data"
        }

        if (LiveDeparturesService.isRunning && !LiveDeparturesService.isSleepingStandby) {
            heroCard.background = roundedBg(HERO_ACTIVE_SAGE, radiusDp = 24)
            heroTitleText.text = "Živé sledování běží"
            heroSubtitleText.text = "Živé odjezdy jsou v notifikaci ($netText). Uspí se po 2,5 min na místě."
            liveToggleBtn.text = "Uspat do klidu"
            liveToggleBtn.background = roundedBg(FOREST_PRIMARY, radiusDp = 20)
            fullStopBtn.visibility = View.VISIBLE
        } else if (LiveDeparturesService.isRunning && LiveDeparturesService.isSleepingStandby) {
            heroCard.background = roundedBg(HERO_STONE, radiusDp = 24)
            heroTitleText.text = "Klidový režim"
            val rideCooldownMin = LiveDeparturesService.getRemainingPostRideCooldownMin()
            val indoorCooldownMin = LiveDeparturesService.getRemainingIndoorCooldownMin()
            val cause = LiveDeparturesService.indoorCooldownCause
            val stepProgress = LiveDeparturesService.standbyStepsProgress
            val stepTarget = PmdpRepository.getWakeUpStepsThreshold(this)
            heroSubtitleText.text = when {
                rideCooldownMin > 0 ->
                    "Pauza po jízdě MHD (ještě $rideCooldownMin min). Při přestupu klepni na Probudit teď."
                indoorCooldownMin > 0 -> when (cause) {
                    LiveDeparturesService.StandbyCooldownCause.WALK ->
                        "Uspáno po procházce městem (klid ještě $indoorCooldownMin min). Chceš-li hledat hned, klepni na Probudit teď."
                    LiveDeparturesService.StandbyCooldownCause.INDOOR_IDLE ->
                        "Uspáno na místě v budově (klid ještě $indoorCooldownMin min). Probudí se chůzí venku nebo klepnutím na Probudit teď."
                    LiveDeparturesService.StandbyCooldownCause.STOP_IDLE ->
                        "Uspáno po 5 min čekání na zastávce (klid ještě $indoorCooldownMin min). Pro obnovení klepni na Probudit teď."
                    else ->
                        "Dočasná pauza (ještě $indoorCooldownMin min). Klepni na Probudit teď pro okamžité zapnutí."
                }
                onHomeWifi && PmdpRepository.isSleepOnWifiEnabled(this) ->
                    "Na klidové Wi-Fi${if (!ssid.isNullOrEmpty()) " ($ssid)" else ""} odpočívá s 0 % baterie. Zapne se automaticky při odchodu."
                else ->
                    "Čeká na souvislou chůzi ($stepProgress/$stepTarget kroků) nebo příchod k zastávce."
            }
            liveToggleBtn.text = "Probudit teď"
            liveToggleBtn.background = roundedBg(FOREST_PRIMARY, radiusDp = 20)
            fullStopBtn.visibility = View.VISIBLE
        } else {
            heroCard.background = roundedBg(HERO_STONE, radiusDp = 24)
            heroTitleText.text = "Sledování je vypnuté"
            heroSubtitleText.text = "Automatické hlídání zastávek i notifikace jsou vypnuty."
            liveToggleBtn.text = "Zapnout sledování"
            liveToggleBtn.background = roundedBg(FOREST_PRIMARY, radiusDp = 20)
            fullStopBtn.visibility = View.GONE
        }
    }

    private fun updateFilterTilesUI() {
        val currentTraction = PmdpRepository.getPreferredTraction(this)
        for (i in 0 until filterTilesRow.childCount) {
            val wrap = filterTilesRow.getChildAt(i) as LinearLayout
            val box = wrap.getChildAt(0) as LinearLayout
            val icon = box.getChildAt(0) as ImageView
            val lbl = wrap.getChildAt(1) as TextView
            val isSelected = wrap.tag == currentTraction

            if (isSelected) {
                box.background = roundedBg(FOREST_PRIMARY, radiusDp = 16)
                ImageViewCompat.setImageTintList(icon, ColorStateList.valueOf(Color.WHITE))
                lbl.setTextColor(Color.parseColor(INK_DARK))
                lbl.typeface = sansMedium
            } else {
                box.background = roundedBg(CARD_WHITE, BORDER_SOFT, radiusDp = 16)
                ImageViewCompat.setImageTintList(icon, ColorStateList.valueOf(Color.parseColor(FOREST_PRIMARY)))
                lbl.setTextColor(Color.parseColor(INK_MUTED))
                lbl.typeface = Typeface.DEFAULT
            }
        }

        val isMulti = PmdpRepository.isMultiStopMode(this) && PmdpRepository.getPinnedStopCis(this) == null
        if (isMulti) {
            clusterTileBox.background = roundedBg(FOREST_PRIMARY, radiusDp = 16)
            ImageViewCompat.setImageTintList(clusterTileIcon, ColorStateList.valueOf(Color.WHITE))
            clusterTileLabel.text = "Chytrý uzel"
            clusterTileLabel.setTextColor(Color.parseColor(INK_DARK))
            clusterTileLabel.typeface = sansMedium
        } else {
            clusterTileBox.background = roundedBg(CARD_WHITE, BORDER_SOFT, radiusDp = 16)
            ImageViewCompat.setImageTintList(clusterTileIcon, ColorStateList.valueOf(Color.parseColor(FOREST_PRIMARY)))
            clusterTileLabel.text = "1 zastávka"
            clusterTileLabel.setTextColor(Color.parseColor(INK_MUTED))
            clusterTileLabel.typeface = Typeface.DEFAULT
        }
    }

    private fun startLiveLockscreenService() {
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
        liveToggleBtn.postDelayed({ updateServiceHeroUI() }, 250)
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
                headerStopTitle.text = "Aktualizuji odjezdy…"
                val multi = PmdpRepository.isMultiStopMode(this@MainActivity) &&
                        PmdpRepository.getPinnedStopCis(this@MainActivity) == null
                val res = PmdpRepository.fetchLiveDepartures(
                    context = this@MainActivity,
                    lat = lat,
                    lon = lon,
                    explicitCis = PmdpRepository.getPinnedStopCis(this@MainActivity),
                    multiStop = multi
                )
                renderResult(res)
                DeparturesWidgetProvider.updateAllWidgets(this@MainActivity, res)
            } catch (e: Exception) {
                headerStopTitle.text = "Spojení není dostupné"
                headerStopMeta.text = e.message ?: "Zkontrolujte připojení k internetu"
                departuresContainer.removeAllViews()
                val retryBtn = TextView(this@MainActivity).apply {
                    text = "Zkusit načíst znovu ↻"
                    textSize = 13f
                    typeface = sansMedium
                    setTextColor(Color.WHITE)
                    gravity = Gravity.CENTER
                    background = roundedBg(FOREST_PRIMARY, radiusDp = 14)
                    setPadding(dp(16), dp(10), dp(16), dp(10))
                    layoutParams = LinearLayout.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT,
                        ViewGroup.LayoutParams.WRAP_CONTENT
                    ).apply { topMargin = dp(16) }
                    setOnClickListener { loadDepartures(lat, lon) }
                }
                departuresContainer.addView(retryBtn)
            }
        }
    }

    private fun renderResult(res: LiveDeparturesResult) {
        try {
            updateFilterTilesUI()

            val isFarAway = res.primaryStop.distanceMeters > 18_000
            if (isFarAway) {
                headerStopTitle.text = "Mimo dosah PMDP Plzeň"
                headerStopMeta.text = "Aplikace je určena pro Plzeň (nejbližší zastávka: ${res.primaryStop.stop.name}, ${res.primaryStop.distanceMeters / 1000} km)"
            } else {
                val isMulti = res.clusterStops.size > 1
                headerStopTitle.text = if (isMulti) {
                    res.clusterStops.joinToString(" + ") { it.stop.name }
                } else {
                    res.primaryStop.stop.name
                }

                val stopsMeta = res.clusterStops.joinToString("  ·  ") {
                    "${it.stop.name}: ${it.distanceMeters} m (${it.walkingMinutes} min chůze)"
                }
                headerStopMeta.text = "$stopsMeta  ·  ${res.fetchedAtHHMMSS.substringBeforeLast(":")}  ·  Klepnutím na čtverec linky ji připneš"
            }

            // Okolní zastávky jako čisté pilulky
            nearbyStopsRow.removeAllViews()
            val pinnedCis = PmdpRepository.getPinnedStopCis(this)
            val allActive = pinnedCis == null && PmdpRepository.isMultiStopMode(this)

            val allChip = TextView(this).apply {
                text = "Chytré okolí"
                textSize = 12f
                typeface = if (allActive) sansMedium else Typeface.DEFAULT
                setTextColor(if (allActive) Color.WHITE else Color.parseColor(INK_DARK))
                background = roundedBg(if (allActive) FOREST_PRIMARY else PILL_INACTIVE, radiusDp = 18)
                setPadding(dp(14), dp(7), dp(14), dp(7))
                layoutParams = LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.WRAP_CONTENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT
                ).apply { rightMargin = dp(8) }
                setOnClickListener {
                    PmdpRepository.setPinnedStopCis(this@MainActivity, null)
                    PmdpRepository.setMultiStopMode(this@MainActivity, true)
                    updateFilterTilesUI()
                    triggerRefresh()
                }
            }
            nearbyStopsRow.addView(allChip)

            for (near in res.nearbyStops.take(5)) {
                val active = pinnedCis == near.stop.cisNumber
                val chip = TextView(this).apply {
                    text = "${near.stop.name}  ·  ${near.distanceMeters} m"
                    textSize = 12f
                    typeface = if (active) sansMedium else Typeface.DEFAULT
                    setTextColor(if (active) Color.WHITE else Color.parseColor(INK_DARK))
                    background = roundedBg(if (active) FOREST_PRIMARY else PILL_INACTIVE, radiusDp = 18)
                    setPadding(dp(14), dp(7), dp(14), dp(7))
                    layoutParams = LinearLayout.LayoutParams(
                        ViewGroup.LayoutParams.WRAP_CONTENT,
                        ViewGroup.LayoutParams.WRAP_CONTENT
                    ).apply { rightMargin = dp(8) }
                    setOnClickListener {
                        PmdpRepository.setPinnedStopCis(this@MainActivity, near.stop.cisNumber)
                        updateFilterTilesUI()
                        triggerRefresh()
                    }
                }
                nearbyStopsRow.addView(chip)
            }

            // Vykreslení karet odjezdů (odfiltrujeme spoje odjeté před > 30 s, aby v seznamu nezůstávali duchové)
            val nowMs = System.currentTimeMillis()
            val validDeps = res.departures.filter { dep ->
                val remMs = dep.targetDepartureEpochMs - nowMs
                remMs >= -30_000L
            }

            departuresContainer.removeAllViews()
            if (validDeps.isEmpty()) {
                departuresContainer.addView(TextView(this).apply {
                    text = "V nejbližší době neodjíždí žádný spoj zvoleného filtru."
                    setTextColor(Color.parseColor(INK_MUTED))
                    textSize = 14f
                    setPadding(dp(4), dp(20), dp(4), dp(20))
                })
                return
            }

            for (dep in validDeps) {
                val card = LinearLayout(this).apply {
                    orientation = LinearLayout.HORIZONTAL
                    gravity = Gravity.CENTER_VERTICAL
                    background = roundedBg(
                        fillHex = CARD_WHITE,
                        strokeHex = if (dep.isFavorite) FOREST_PRIMARY else BORDER_SOFT,
                        radiusDp = 20,
                        strokeWidthDp = if (dep.isFavorite) 2 else 1
                    )
                    setPadding(dp(14), dp(14), dp(16), dp(14))
                    alpha = if (dep.catchability.status == "missed") 0.58f else 1.0f
                    layoutParams = LinearLayout.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT,
                        ViewGroup.LayoutParams.WRAP_CONTENT
                    ).apply { bottomMargin = dp(10) }
                }

                // 1. Levý čtvercový odznak linky (52x52 dp) – klepnutím přepíná prioritní linku
                val (badgeBg, badgeFg, typeLabel) = when {
                    dep.isNight -> Triple("#2B303A", "#F6F5F0", "NOC")
                    dep.tractionType == 1 -> Triple("#F3E5AB", "#3A2E11", "TRAM")
                    dep.tractionType == 2 -> Triple("#DCEBE1", "#1E462E", "TROL")
                    else -> Triple("#F5E1DC", "#6E291D", "BUS")
                }

                val lineSquare = LinearLayout(this).apply {
                    orientation = LinearLayout.VERTICAL
                    gravity = Gravity.CENTER
                    background = roundedBg(
                        fillHex = badgeBg,
                        strokeHex = if (dep.isFavorite) FOREST_PRIMARY else null,
                        radiusDp = 14,
                        strokeWidthDp = 2
                    )
                    layoutParams = LinearLayout.LayoutParams(dp(52), dp(52))
                    setOnClickListener {
                        PmdpRepository.toggleFavoriteLine(this@MainActivity, dep.lineName)
                        rebuildFavoriteLinesUI()
                        triggerRefresh()
                    }
                }
                lineSquare.addView(TextView(this).apply {
                    text = dep.lineName
                    textSize = 18f
                    typeface = Typeface.DEFAULT_BOLD
                    setTextColor(Color.parseColor(badgeFg))
                    gravity = Gravity.CENTER
                    includeFontPadding = false
                    setSingleLine(true)
                })
                lineSquare.addView(TextView(this).apply {
                    text = typeLabel
                    textSize = 8f
                    letterSpacing = 0.08f
                    typeface = sansMedium
                    setTextColor(Color.parseColor(badgeFg))
                    gravity = Gravity.CENTER
                    includeFontPadding = false
                    setSingleLine(true)
                })
                card.addView(lineSquare)

                // 2. Střední sloupec: Cílová stanice, zastávka + zpoždění, a pilulka "Stíháš" (+ případně "Prioritní")
                val midCol = LinearLayout(this).apply {
                    orientation = LinearLayout.VERTICAL
                    layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply {
                        leftMargin = dp(14)
                        rightMargin = dp(8)
                    }
                }

                midCol.addView(TextView(this).apply {
                    text = dep.destination
                    setTextColor(Color.parseColor(INK_DARK))
                    textSize = 16f
                    typeface = sansMedium
                    maxLines = 1
                    ellipsize = TextUtils.TruncateAt.END
                })

                val delayPart = when {
                    dep.delayMin == null -> "dle JŘ"
                    dep.delayMin == 0 -> "včas"
                    else -> "+${dep.delayMin} min"
                }
                midCol.addView(TextView(this).apply {
                    text = "${dep.stopName} (${dep.stopDistanceMeters} m)  ·  $delayPart"
                    setTextColor(Color.parseColor(INK_MUTED))
                    textSize = 12f
                    maxLines = 1
                    ellipsize = TextUtils.TruncateAt.END
                    layoutParams = LinearLayout.LayoutParams(
                        ViewGroup.LayoutParams.WRAP_CONTENT,
                        ViewGroup.LayoutParams.WRAP_CONTENT
                    ).apply { topMargin = dp(2) }
                })

                // Řádek pilulek: Stíháš / Poklusem / Nestíháš + případný štítek "Prioritní"
                val (catchBg, catchFg) = when (dep.catchability.status) {
                    "comfortable" -> "#E2EFE6" to "#255238"
                    "hurry" -> "#F6EAD4" to "#8F5812"
                    else -> "#F4E3E0" to "#963A2F"
                }
                val catchRow = LinearLayout(this).apply {
                    orientation = LinearLayout.HORIZONTAL
                    gravity = Gravity.CENTER_VERTICAL
                    layoutParams = LinearLayout.LayoutParams(
                        ViewGroup.LayoutParams.WRAP_CONTENT,
                        ViewGroup.LayoutParams.WRAP_CONTENT
                    ).apply { topMargin = dp(6) }
                }
                val catchBadge = TextView(this).apply {
                    text = dep.catchability.label
                    setTextColor(Color.parseColor(catchFg))
                    textSize = 11f
                    typeface = sansMedium
                    background = roundedBg(catchBg, radiusDp = 10)
                    setPadding(dp(10), dp(3), dp(10), dp(3))
                    setSingleLine(true)
                }
                catchRow.addView(catchBadge)

                if (dep.isFavorite) {
                    val favPill = TextView(this).apply {
                        text = "Prioritní"
                        setTextColor(Color.parseColor(FOREST_PRIMARY))
                        textSize = 10.5f
                        typeface = sansMedium
                        background = roundedBg("#EAE7DD", radiusDp = 10)
                        setPadding(dp(9), dp(3), dp(9), dp(3))
                        setSingleLine(true)
                        layoutParams = LinearLayout.LayoutParams(
                            ViewGroup.LayoutParams.WRAP_CONTENT,
                            ViewGroup.LayoutParams.WRAP_CONTENT
                        ).apply { leftMargin = dp(6) }
                        setOnClickListener {
                            PmdpRepository.toggleFavoriteLine(this@MainActivity, dep.lineName)
                            rebuildFavoriteLinesUI()
                            triggerRefresh()
                        }
                    }
                    catchRow.addView(favPill)
                }

                midCol.addView(catchRow)
                card.addView(midCol)

                // 3. Pravý sloupec: Dostatečně široký box na odpočet minut a přesný čas odjezdu
                val rightCol = LinearLayout(this).apply {
                    orientation = LinearLayout.VERTICAL
                    gravity = Gravity.END or Gravity.CENTER_VERTICAL
                    minimumWidth = dp(68)
                    layoutParams = LinearLayout.LayoutParams(
                        ViewGroup.LayoutParams.WRAP_CONTENT,
                        ViewGroup.LayoutParams.WRAP_CONTENT
                    )
                }

                rightCol.addView(TextView(this).apply {
                    text = when {
                        dep.leavingInMin < 0 -> "odjelo"
                        dep.leavingInMin == 0 -> "teď"
                        else -> "${dep.leavingInMin} min"
                    }
                    setTextColor(Color.parseColor(if (dep.leavingInMin < 0) "#963A2F" else INK_DARK))
                    textSize = 18f
                    typeface = Typeface.DEFAULT_BOLD
                    gravity = Gravity.END
                    setSingleLine(true)
                    includeFontPadding = false
                })

                rightCol.addView(TextView(this).apply {
                    text = dep.estimatedHHMM
                    setTextColor(Color.parseColor(INK_MUTED))
                    textSize = 12f
                    gravity = Gravity.END
                    setSingleLine(true)
                    layoutParams = LinearLayout.LayoutParams(
                        ViewGroup.LayoutParams.WRAP_CONTENT,
                        ViewGroup.LayoutParams.WRAP_CONTENT
                    ).apply { topMargin = dp(3) }
                })

                card.addView(rightCol)
                departuresContainer.addView(card)
            }
        } catch (t: Throwable) {
            android.util.Log.e("MainActivity", "Chyba při renderResult: ${t.message}", t)
        }
    }
}
