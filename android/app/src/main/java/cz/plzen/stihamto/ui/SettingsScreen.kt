package cz.plzen.stihamto.ui

import android.os.Build
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import cz.plzen.stihamto.UpdateCheckResult
import java.io.File

sealed class UpdateUiState {
    object Idle : UpdateUiState()
    object Checking : UpdateUiState()
    data class UpToDate(val version: String) : UpdateUiState()
    data class UpdateAvailable(val info: UpdateCheckResult) : UpdateUiState()
    data class Downloading(val progress: Float) : UpdateUiState()
    data class ReadyToInstall(val file: File) : UpdateUiState()
    data class Error(val message: String) : UpdateUiState()
}

data class SystemDiagState(
    val hasLoc: Boolean,
    val hasActivity: Boolean,
    val hasNotif: Boolean,
    val ignoringBatteryOpt: Boolean
)

@Composable
fun SettingsScreen(
    walkSpeed: String,
    onWalkSpeedChange: (String) -> Unit,
    crosswalkBuffer: Int,
    onCrosswalkBufferChange: (Int) -> Unit,
    hideMissed: Boolean,
    onHideMissedChange: (Boolean) -> Unit,
    notifCount: Int,
    onNotifCountChange: (Int) -> Unit,
    clusterRadius: Int,
    onClusterRadiusChange: (Int) -> Unit,
    autoTram: Boolean,
    onAutoTramChange: (Boolean) -> Unit,
    favoriteLines: Set<String>,
    allKnownLines: List<String>,
    onToggleFavoriteLine: (String) -> Unit,
    onClearFavoriteLines: () -> Unit,
    sleepOnWifi: Boolean,
    onSleepOnWifiChange: (Boolean) -> Unit,
    currentWifiSsid: String?,
    homeWifiSsids: Set<String>,
    onAddHomeWifi: (String) -> Unit,
    onRemoveHomeWifi: (String) -> Unit,
    wakeUpSteps: Int,
    onWakeUpStepsChange: (Int) -> Unit,
    postRideCooldown: Int,
    onPostRideCooldownChange: (Int) -> Unit,
    walkPassedLimit: Int,
    onWalkPassedLimitChange: (Int) -> Unit,
    diagState: SystemDiagState,
    onRequestPermissions: () -> Unit,
    onOpenBatterySettings: () -> Unit,
    dynamicColorEnabled: Boolean,
    onDynamicColorToggle: (Boolean) -> Unit,
    updateState: UpdateUiState,
    onCheckForUpdates: () -> Unit,
    onDownloadAndInstall: (UpdateCheckResult) -> Unit,
    onInstallDownloaded: (File) -> Unit,
    appVersion: String
) {
    var globalHelpExpanded by remember { mutableStateOf(false) }
    var favPickerExpanded by remember { mutableStateOf(false) }

    LazyColumn(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background),
        contentPadding = PaddingValues(start = 20.dp, end = 20.dp, top = 16.dp, bottom = 40.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp)
    ) {
        // Hlavička
        item {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column {
                    Text(
                        text = "Nastavení",
                        fontSize = 28.sp,
                        fontWeight = FontWeight.Bold,
                        fontFamily = FontFamily.Serif,
                        color = MaterialTheme.colorScheme.onBackground
                    )
                    Text(
                        text = "CHŮZE, ZASTÁVKY A CHYTRÁ AUTOMATIKA",
                        fontSize = 10.sp,
                        letterSpacing = 1.2.sp,
                        fontWeight = FontWeight.Medium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }

                FilterChip(
                    selected = globalHelpExpanded,
                    onClick = { globalHelpExpanded = !globalHelpExpanded },
                    label = { Text(if (globalHelpExpanded) "Skrýt ?" else "Popisky ?", fontSize = 12.sp) },
                    shape = RoundedCornerShape(16.dp),
                    colors = FilterChipDefaults.filterChipColors(
                        selectedContainerColor = MaterialTheme.colorScheme.primaryContainer,
                        selectedLabelColor = MaterialTheme.colorScheme.primary,
                        containerColor = MaterialTheme.colorScheme.surface,
                        labelColor = MaterialTheme.colorScheme.primary
                    )
                )
            }
        }

        // === SEKCE 1: CHŮZE A SPOJE ===
        item { SectionLabel("CHŮZE A SPOJE") }
        item {
            SettingsCard {
                ChoiceSettingBlock(
                    title = "Tempo chůze",
                    helpText = "Podle tvého tempa počítáme, kolik minut půjdeš na zastávku a jestli spoj v klidu stihneš.",
                    showHelp = globalHelpExpanded,
                    options = listOf(
                        "relaxed" to "Klidné",
                        "normal" to "Běžné",
                        "brisk" to "Svižné"
                    ),
                    selected = walkSpeed,
                    onSelected = onWalkSpeedChange
                )
                SettingsDivider()
                ChoiceSettingBlock(
                    title = "Rezerva na přechody a semafory",
                    helpText = "Přidá časovou rezervu na červenou, aby ti tramvaj neujela před nosem.",
                    showHelp = globalHelpExpanded,
                    options = listOf(
                        0 to "Bez rezervy",
                        30 to "+30 s",
                        60 to "+1 min"
                    ),
                    selected = crosswalkBuffer,
                    onSelected = onCrosswalkBufferChange
                )
                SettingsDivider()
                ChoiceSettingBlock(
                    title = "Ujíždějící spoje v notifikaci",
                    helpText = "Schová z notifikace spoje se stavem Nestíháš pro více místa na reálné odjezdy.",
                    showHelp = globalHelpExpanded,
                    options = listOf(
                        false to "Ukazovat vše",
                        true to "Skrýt Nestíháš"
                    ),
                    selected = hideMissed,
                    onSelected = onHideMissedChange
                )
                SettingsDivider()
                ChoiceSettingBlock(
                    title = "Počet spojů v notifikaci",
                    helpText = "Určuje, kolik nejbližších odjezdů se vypíše v notifikaci na zamykací obrazovce.",
                    showHelp = globalHelpExpanded,
                    options = listOf(
                        3 to "3 spoje",
                        4 to "4 spoje",
                        6 to "6 spojů"
                    ),
                    selected = notifCount,
                    onSelected = onNotifCountChange
                )
            }
        }

        // === SEKCE 2: ZASTÁVKY A LINKY V OKOLÍ ===
        item { SectionLabel("ZASTÁVKY A LINKY V OKOLÍ") }
        item {
            SettingsCard {
                ChoiceSettingBlock(
                    title = "Dosah Chytrého uzlu",
                    helpText = "Jak daleko kolem tebe má aplikace hledat další zastávky při zapnutém Chytrém uzlu.",
                    showHelp = globalHelpExpanded,
                    options = listOf(
                        300 to "Blízko (300 m)",
                        380 to "Střední (380 m)",
                        520 to "Širší (520 m)"
                    ),
                    selected = clusterRadius,
                    onSelected = onClusterRadiusChange
                )
                SettingsDivider()
                ChoiceSettingBlock(
                    title = "Přibrat tramvaj v okolí",
                    helpText = "Když máš u sebe jen autobus/trolejbus, automaticky načte i nejbližší tramvaj.",
                    showHelp = globalHelpExpanded,
                    options = listOf(
                        true to "Automaticky",
                        false to "Jen nejbližší"
                    ),
                    selected = autoTram,
                    onSelected = onAutoTramChange
                )
                SettingsDivider()
                FavoriteLinesBlock(
                    favoriteLines = favoriteLines,
                    allLines = allKnownLines,
                    showHelp = globalHelpExpanded,
                    isExpanded = favPickerExpanded,
                    onToggleExpand = { favPickerExpanded = !favPickerExpanded },
                    onToggleFavorite = onToggleFavoriteLine,
                    onClearAll = onClearFavoriteLines
                )
            }
        }

        // === SEKCE 3: AUTOMATIKA A SPÁNEK ===
        item { SectionLabel("AUTOMATIKA A SPÁNEK") }
        item {
            SettingsCard {
                ChoiceSettingBlock(
                    title = "Režim na domácí Wi-Fi",
                    helpText = "Dokud jsi na domácí Wi-Fi, sledování odpočívá s nulovou spotřebou a zapne se až při odchodu.",
                    showHelp = globalHelpExpanded,
                    options = listOf(
                        true to "Doma spí",
                        false to "Běží i na Wi-Fi"
                    ),
                    selected = sleepOnWifi,
                    onSelected = onSleepOnWifiChange
                )
                SettingsDivider()
                HomeWifiBlock(
                    currentWifiSsid = currentWifiSsid,
                    savedSsids = homeWifiSsids,
                    showHelp = globalHelpExpanded,
                    onAddHomeWifi = onAddHomeWifi,
                    onRemoveHomeWifi = onRemoveHomeWifi
                )
                SettingsDivider()
                ChoiceSettingBlock(
                    title = "Probuzení chůzí (krokoměr)",
                    helpText = "Kolik souvislých kroků stačí udělat, aby se aplikace probudila a rozsvítila odjezdy.",
                    showHelp = globalHelpExpanded,
                    options = listOf(
                        15 to "Citlivé (15)",
                        30 to "Běžné (30)",
                        50 to "Odolné (50)"
                    ),
                    selected = wakeUpSteps,
                    onSelected = onWakeUpStepsChange
                )
                SettingsDivider()
                ChoiceSettingBlock(
                    title = "Pauza po jízdě v MHD",
                    helpText = "Po vystoupení dá sledování pauzu, aby se cestou ze zastávky hned nezapínalo.",
                    showHelp = globalHelpExpanded,
                    options = listOf(
                        0 to "Bez pauzy",
                        10 to "10 min",
                        20 to "20 min"
                    ),
                    selected = postRideCooldown,
                    onSelected = onPostRideCooldownChange
                )
                SettingsDivider()
                ChoiceSettingBlock(
                    title = "Rozpoznání procházky",
                    helpText = "Když procházíš městem a mineš několik zastávek bez nástupu, aplikace usne.",
                    showHelp = globalHelpExpanded,
                    options = listOf(
                        2 to "Po 2 zastávkách",
                        3 to "Po 3 zastávkách",
                        0 to "Vypnuto"
                    ),
                    selected = walkPassedLimit,
                    onSelected = onWalkPassedLimitChange
                )
            }
        }

        // === SEKCE 4: VZHLED & MATERIAL YOU ===
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            item { SectionLabel("VZHLED A TÉMA") }
            item {
                SettingsCard {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = "Dynamické barvy (Material You)",
                                fontSize = 15.sp,
                                fontWeight = FontWeight.Medium,
                                color = MaterialTheme.colorScheme.onSurface
                            )
                            Text(
                                text = "Přizpůsobit barvy aplikace tapetě telefonu",
                                fontSize = 12.5.sp,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                        Switch(
                            checked = dynamicColorEnabled,
                            onCheckedChange = onDynamicColorToggle,
                            colors = SwitchDefaults.colors(
                                checkedThumbColor = MaterialTheme.colorScheme.primary,
                                checkedTrackColor = MaterialTheme.colorScheme.primaryContainer
                            )
                        )
                    }
                }
            }
        }

        // === SEKCE 5: STAV SYSTÉMU A BATERIE ===
        item { SectionLabel("STAV SYSTÉMU A BATERIE") }
        item {
            SettingsCard {
                SystemDiagBlock(
                    diagState = diagState,
                    showHelp = globalHelpExpanded,
                    onRequestPermissions = onRequestPermissions,
                    onOpenBatterySettings = onOpenBatterySettings
                )
            }
        }

        // === SEKCE 6: AKTUALIZACE A GITHUB ===
        item { SectionLabel("AKTUALIZACE A GITHUB") }
        item {
            SettingsCard {
                AppUpdateBlock(
                    currentVersion = appVersion,
                    updateState = updateState,
                    onCheck = onCheckForUpdates,
                    onDownload = onDownloadAndInstall,
                    onInstall = onInstallDownloaded
                )
            }
        }

        // Patička
        item {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(vertical = 16.dp),
                contentAlignment = Alignment.Center
            ) {
                Text(
                    text = "Stíhám to? v$appVersion · Plzeň MHD (Material 3)",
                    fontSize = 11.5.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
    }
}

@Composable
private fun SectionLabel(text: String) {
    Text(
        text = text,
        fontSize = 10.sp,
        letterSpacing = 1.2.sp,
        fontWeight = FontWeight.Medium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(start = 6.dp, top = 8.dp)
    )
}

@Composable
private fun SettingsCard(content: @Composable ColumnScope.() -> Unit) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(20.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)
    ) {
        Column(
            modifier = Modifier.padding(16.dp),
            content = content
        )
    }
}

@Composable
private fun SettingsDivider() {
    HorizontalDivider(
        modifier = Modifier.padding(vertical = 14.dp),
        thickness = 1.dp,
        color = MaterialTheme.colorScheme.outline.copy(alpha = 0.5f)
    )
}

@Composable
private fun <T> ChoiceSettingBlock(
    title: String,
    helpText: String,
    showHelp: Boolean,
    options: List<Pair<T, String>>,
    selected: T,
    onSelected: (T) -> Unit
) {
    var localHelp by remember { mutableStateOf(false) }
    val isHelpVisible = showHelp || localHelp

    Column {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clickable { localHelp = !localHelp },
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = title,
                fontSize = 15.sp,
                fontWeight = FontWeight.Medium,
                color = MaterialTheme.colorScheme.onSurface
            )
            Box(
                modifier = Modifier
                    .size(22.dp)
                    .clip(CircleShape)
                    .background(if (isHelpVisible) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.surfaceVariant),
                contentAlignment = Alignment.Center
            ) {
                Text(
                    text = "?",
                    fontSize = 11.5.sp,
                    fontWeight = FontWeight.Bold,
                    color = if (isHelpVisible) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.primary
                )
            }
        }

        AnimatedVisibility(visible = isHelpVisible) {
            Text(
                text = helpText,
                fontSize = 12.5.sp,
                lineHeight = 17.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = 4.dp, bottom = 4.dp)
            )
        }

        Spacer(modifier = Modifier.height(10.dp))
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            options.forEach { (value, label) ->
                val active = value == selected
                Box(
                    modifier = Modifier
                        .weight(1f)
                        .clip(RoundedCornerShape(14.dp))
                        .background(
                            if (active) MaterialTheme.colorScheme.primary
                            else MaterialTheme.colorScheme.surfaceVariant
                        )
                        .clickable { onSelected(value) }
                        .padding(vertical = 9.dp, horizontal = 4.dp),
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        text = label,
                        fontSize = 12.5.sp,
                        fontWeight = if (active) FontWeight.Medium else FontWeight.Normal,
                        color = if (active) MaterialTheme.colorScheme.onPrimary
                        else MaterialTheme.colorScheme.onSurface,
                        maxLines = 1
                    )
                }
            }
        }
    }
}

@Composable
private fun FavoriteLinesBlock(
    favoriteLines: Set<String>,
    allLines: List<String>,
    showHelp: Boolean,
    isExpanded: Boolean,
    onToggleExpand: () -> Unit,
    onToggleFavorite: (String) -> Unit,
    onClearAll: () -> Unit
) {
    var localHelp by remember { mutableStateOf(false) }
    val isHelpVisible = showHelp || localHelp

    val sortedFavs = remember(favoriteLines) {
        favoriteLines.sortedWith(
            compareBy<String> { it.startsWith("N", ignoreCase = true) }
                .thenBy { it.filter { ch -> ch.isDigit() }.toIntOrNull() ?: 999 }
        )
    }

    Column {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clickable { localHelp = !localHelp },
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = "Prioritní linky",
                fontSize = 15.sp,
                fontWeight = FontWeight.Medium,
                color = MaterialTheme.colorScheme.onSurface
            )
            Box(
                modifier = Modifier
                    .size(22.dp)
                    .clip(CircleShape)
                    .background(if (isHelpVisible) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.surfaceVariant),
                contentAlignment = Alignment.Center
            ) {
                Text(
                    text = "?",
                    fontSize = 11.5.sp,
                    fontWeight = FontWeight.Bold,
                    color = if (isHelpVisible) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.primary
                )
            }
        }

        AnimatedVisibility(visible = isHelpVisible) {
            Text(
                text = "Spoje tvých prioritních linek se v aplikaci i v notifikaci řadí vždy na první místo.",
                fontSize = 12.5.sp,
                lineHeight = 17.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = 4.dp, bottom = 4.dp)
            )
        }

        Spacer(modifier = Modifier.height(8.dp))
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = if (sortedFavs.isEmpty()) "Zatím nemáš vybranou prioritní linku"
                else "Vybráno: ${sortedFavs.joinToString(", ")}",
                fontSize = 13.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.weight(1f)
            )
            Button(
                onClick = onToggleExpand,
                shape = RoundedCornerShape(14.dp),
                colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.primaryContainer),
                contentPadding = PaddingValues(horizontal = 12.dp, vertical = 6.dp)
            ) {
                Text(
                    text = if (isExpanded) "Hotovo" else "Upravit linky",
                    fontSize = 12.sp,
                    fontWeight = FontWeight.Medium,
                    color = MaterialTheme.colorScheme.primary
                )
            }
        }

        AnimatedVisibility(visible = isExpanded) {
            Column(modifier = Modifier.padding(top = 12.dp)) {
                val columns = 5
                val rows = allLines.chunked(columns)

                rows.forEach { rowItems ->
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(bottom = 6.dp),
                        horizontalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        for (i in 0 until columns) {
                            if (i < rowItems.size) {
                                val lineName = rowItems[i]
                                val isFav = favoriteLines.contains(lineName)
                                Box(
                                    modifier = Modifier
                                        .weight(1f)
                                        .clip(RoundedCornerShape(12.dp))
                                        .background(
                                            if (isFav) MaterialTheme.colorScheme.primary
                                            else MaterialTheme.colorScheme.surfaceVariant
                                        )
                                        .clickable { onToggleFavorite(lineName) }
                                        .padding(vertical = 8.dp),
                                    contentAlignment = Alignment.Center
                                ) {
                                    Text(
                                        text = lineName,
                                        fontSize = 13.sp,
                                        fontWeight = if (isFav) FontWeight.Bold else FontWeight.Medium,
                                        color = if (isFav) MaterialTheme.colorScheme.onPrimary
                                        else MaterialTheme.colorScheme.onSurface
                                    )
                                }
                            } else {
                                Spacer(modifier = Modifier.weight(1f))
                            }
                        }
                    }
                }

                if (favoriteLines.isNotEmpty()) {
                    Spacer(modifier = Modifier.height(4.dp))
                    Button(
                        onClick = onClearAll,
                        modifier = Modifier.fillMaxWidth(),
                        shape = RoundedCornerShape(12.dp),
                        colors = ButtonDefaults.buttonColors(containerColor = Color(0xFFF4E3E0))
                    ) {
                        Text(
                            text = "Zrušit všechny prioritní linky",
                            fontSize = 12.sp,
                            fontWeight = FontWeight.Medium,
                            color = Color(0xFF963A2F)
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun HomeWifiBlock(
    currentWifiSsid: String?,
    savedSsids: Set<String>,
    showHelp: Boolean,
    onAddHomeWifi: (String) -> Unit,
    onRemoveHomeWifi: (String) -> Unit
) {
    var localHelp by remember { mutableStateOf(false) }
    val isHelpVisible = showHelp || localHelp

    Column {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clickable { localHelp = !localHelp },
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = "Klidové Wi-Fi zóny (Doma / Práce)",
                fontSize = 15.sp,
                fontWeight = FontWeight.Medium,
                color = MaterialTheme.colorScheme.onSurface
            )
            Box(
                modifier = Modifier
                    .size(22.dp)
                    .clip(CircleShape)
                    .background(if (isHelpVisible) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.surfaceVariant),
                contentAlignment = Alignment.Center
            ) {
                Text(
                    text = "?",
                    fontSize = 11.5.sp,
                    fontWeight = FontWeight.Bold,
                    color = if (isHelpVisible) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.primary
                )
            }
        }

        AnimatedVisibility(visible = isHelpVisible) {
            Text(
                text = "Aplikace usne a přestane čerpat baterii pouze na zde uložených Wi-Fi sítích. Ve vozech PMDP se neuspává.",
                fontSize = 12.5.sp,
                lineHeight = 17.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = 4.dp, bottom = 4.dp)
            )
        }

        Spacer(modifier = Modifier.height(8.dp))

        val wifiText = if (!currentWifiSsid.isNullOrEmpty()) {
            val isSaved = savedSsids.contains(currentWifiSsid)
            "Aktuální Wi-Fi: $currentWifiSsid (${if (isSaved) "Uložená" else "Není uložena"})"
        } else {
            "Aktuálně nejste na Wi-Fi síti"
        }

        Text(
            text = wifiText,
            fontSize = 13.sp,
            color = MaterialTheme.colorScheme.onSurface
        )

        if (!currentWifiSsid.isNullOrEmpty() && !savedSsids.contains(currentWifiSsid)) {
            Spacer(modifier = Modifier.height(8.dp))
            Button(
                onClick = { onAddHomeWifi(currentWifiSsid) },
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(14.dp),
                colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.primary)
            ) {
                Text(
                    text = "+ Přidat síť \"$currentWifiSsid\" mezi domácí",
                    fontSize = 12.5.sp,
                    fontWeight = FontWeight.Medium,
                    color = MaterialTheme.colorScheme.onPrimary
                )
            }
        }

        Spacer(modifier = Modifier.height(6.dp))

        if (savedSsids.isEmpty()) {
            Text(
                text = "Zatím nemáte uloženou žádnou specifickou Wi-Fi (aplikace se uspává na jakékoliv ne-veřejné Wi-Fi).",
                fontSize = 12.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        } else {
            savedSsids.forEach { ssid ->
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(bottom = 6.dp)
                        .clip(RoundedCornerShape(12.dp))
                        .background(MaterialTheme.colorScheme.surfaceVariant)
                        .padding(horizontal = 12.dp, vertical = 8.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = "📶 $ssid",
                        fontSize = 13.sp,
                        fontWeight = FontWeight.Medium,
                        color = MaterialTheme.colorScheme.onSurface
                    )
                    Text(
                        text = "Odebrat",
                        fontSize = 12.sp,
                        color = Color(0xFF963A2F),
                        modifier = Modifier
                            .clickable { onRemoveHomeWifi(ssid) }
                            .padding(4.dp)
                    )
                }
            }
        }
    }
}

@Composable
private fun SystemDiagBlock(
    diagState: SystemDiagState,
    showHelp: Boolean,
    onRequestPermissions: () -> Unit,
    onOpenBatterySettings: () -> Unit
) {
    var localHelp by remember { mutableStateOf(false) }
    val isHelpVisible = showHelp || localHelp

    Column {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clickable { localHelp = !localHelp },
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = "Oprávnění a běh na pozadí",
                fontSize = 15.sp,
                fontWeight = FontWeight.Medium,
                color = MaterialTheme.colorScheme.onSurface
            )
            Box(
                modifier = Modifier
                    .size(22.dp)
                    .clip(CircleShape)
                    .background(if (isHelpVisible) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.surfaceVariant),
                contentAlignment = Alignment.Center
            ) {
                Text(
                    text = "?",
                    fontSize = 11.5.sp,
                    fontWeight = FontWeight.Bold,
                    color = if (isHelpVisible) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.primary
                )
            }
        }

        AnimatedVisibility(visible = isHelpVisible) {
            Text(
                text = "Aby se odjezdy spolehlivě rozsvítily i s telefonem v kapse, systém nesmí aplikaci uspávat.",
                fontSize = 12.5.sp,
                lineHeight = 17.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = 4.dp, bottom = 4.dp)
            )
        }

        Spacer(modifier = Modifier.height(8.dp))

        val items = listOf(
            "Přesná poloha (GPS)" to diagState.hasLoc,
            "Rozpoznání chůze (krokoměr)" to diagState.hasActivity,
            "Notifikace na zamykací obrazovce" to diagState.hasNotif,
            "Neomezený běh na pozadí (baterie)" to diagState.ignoringBatteryOpt
        )

        items.forEach { (label, ok) ->
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(bottom = 6.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = label,
                    fontSize = 13.sp,
                    color = MaterialTheme.colorScheme.onSurface
                )
                Box(
                    modifier = Modifier
                        .clip(RoundedCornerShape(10.dp))
                        .background(if (ok) Color(0xFFE2EFE6) else Color(0xFFF4E3E0))
                        .padding(horizontal = 10.dp, vertical = 3.dp)
                ) {
                    Text(
                        text = if (ok) "V pořádku" else "Chybí",
                        fontSize = 11.sp,
                        fontWeight = FontWeight.Medium,
                        color = if (ok) Color(0xFF255238) else Color(0xFF963A2F)
                    )
                }
            }
        }

        if (!diagState.hasLoc || !diagState.hasActivity || !diagState.hasNotif) {
            Spacer(modifier = Modifier.height(6.dp))
            Button(
                onClick = onRequestPermissions,
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(14.dp),
                colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.primary)
            ) {
                Text(
                    text = "Doplnit chybějící oprávnění",
                    fontSize = 12.5.sp,
                    fontWeight = FontWeight.Medium,
                    color = MaterialTheme.colorScheme.onPrimary
                )
            }
        }

        if (!diagState.ignoringBatteryOpt) {
            Spacer(modifier = Modifier.height(6.dp))
            OutlinedButton(
                onClick = onOpenBatterySettings,
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(14.dp)
            ) {
                Text(
                    text = "Povolit běh na pozadí bez uspávání",
                    fontSize = 12.5.sp,
                    fontWeight = FontWeight.Medium,
                    color = MaterialTheme.colorScheme.primary
                )
            }
        }
    }
}

@Composable
private fun AppUpdateBlock(
    currentVersion: String,
    updateState: UpdateUiState,
    onCheck: () -> Unit,
    onDownload: (UpdateCheckResult) -> Unit,
    onInstall: (File) -> Unit
) {
    Column {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = "Aktualizace aplikace",
                fontSize = 15.sp,
                fontWeight = FontWeight.Medium,
                color = MaterialTheme.colorScheme.onSurface
            )
            Text(
                text = "v$currentVersion",
                fontSize = 12.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }

        Text(
            text = "Aplikace se synchronizuje přímo s repozitářem na GitHubu a umí se sama aktualizovat bez nutnosti Google Play.",
            fontSize = 12.5.sp,
            lineHeight = 17.sp,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(top = 4.dp, bottom = 10.dp)
        )

        when (updateState) {
            is UpdateUiState.Idle -> {
                Button(
                    onClick = onCheck,
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(14.dp),
                    colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.primary)
                ) {
                    Text(
                        text = "⚡ Zkontrolovat aktualizace z GitHubu",
                        fontSize = 13.sp,
                        fontWeight = FontWeight.Medium,
                        color = MaterialTheme.colorScheme.onPrimary
                    )
                }
            }

            is UpdateUiState.Checking -> {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(14.dp))
                        .background(MaterialTheme.colorScheme.surfaceVariant)
                        .padding(14.dp),
                    horizontalArrangement = Arrangement.Center,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(18.dp),
                        strokeWidth = 2.dp,
                        color = MaterialTheme.colorScheme.primary
                    )
                    Spacer(modifier = Modifier.width(10.dp))
                    Text(
                        text = "Ověřuji novou verzi na GitHubu…",
                        fontSize = 13.sp,
                        color = MaterialTheme.colorScheme.onSurface
                    )
                }
            }

            is UpdateUiState.UpToDate -> {
                Column(modifier = Modifier.fillMaxWidth()) {
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(12.dp))
                            .background(Color(0xFFE2EFE6))
                            .padding(12.dp),
                        contentAlignment = Alignment.Center
                    ) {
                        Text(
                            text = "✓ Máte nejnovější verzi (${updateState.version})",
                            fontSize = 13.sp,
                            fontWeight = FontWeight.Medium,
                            color = Color(0xFF255238)
                        )
                    }
                    Spacer(modifier = Modifier.height(6.dp))
                    TextButton(
                        onClick = onCheck,
                        modifier = Modifier.align(Alignment.CenterHorizontally)
                    ) {
                        Text("Zkontrolovat znovu", fontSize = 12.sp)
                    }
                }
            }

            is UpdateUiState.UpdateAvailable -> {
                val info = updateState.info
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(14.dp))
                        .background(MaterialTheme.colorScheme.primaryContainer)
                        .padding(14.dp)
                ) {
                    Text(
                        text = "🎉 K dispozici je verze ${info.latestVersionName}!",
                        fontSize = 14.sp,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.onPrimaryContainer
                    )
                    if (info.releaseNotes.isNotEmpty()) {
                        Spacer(modifier = Modifier.height(6.dp))
                        Text(
                            text = info.releaseNotes,
                            fontSize = 12.sp,
                            lineHeight = 16.sp,
                            color = MaterialTheme.colorScheme.onPrimaryContainer
                        )
                    }
                    Spacer(modifier = Modifier.height(12.dp))
                    Button(
                        onClick = { onDownload(info) },
                        modifier = Modifier.fillMaxWidth(),
                        shape = RoundedCornerShape(12.dp),
                        colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.primary)
                    ) {
                        Text(
                            text = "⬇ Stáhnout a nainstalovat",
                            fontSize = 13.sp,
                            fontWeight = FontWeight.Medium,
                            color = MaterialTheme.colorScheme.onPrimary
                        )
                    }
                }
            }

            is UpdateUiState.Downloading -> {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(14.dp))
                        .background(MaterialTheme.colorScheme.surfaceVariant)
                        .padding(14.dp)
                ) {
                    val percent = (updateState.progress * 100).toInt()
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Text(
                            text = "Stahuji APK z GitHubu…",
                            fontSize = 13.sp,
                            color = MaterialTheme.colorScheme.onSurface
                        )
                        Text(
                            text = "$percent %",
                            fontSize = 13.sp,
                            fontWeight = FontWeight.Bold,
                            color = MaterialTheme.colorScheme.primary
                        )
                    }
                    Spacer(modifier = Modifier.height(8.dp))
                    LinearProgressIndicator(
                        progress = { updateState.progress },
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(6.dp)
                            .clip(RoundedCornerShape(3.dp)),
                        color = MaterialTheme.colorScheme.primary,
                        trackColor = MaterialTheme.colorScheme.surface
                    )
                }
            }

            is UpdateUiState.ReadyToInstall -> {
                Button(
                    onClick = { onInstall(updateState.file) },
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(14.dp),
                    colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.primary)
                ) {
                    Text(
                        text = "📦 Spustit instalaci aktualizace",
                        fontSize = 13.sp,
                        fontWeight = FontWeight.Medium,
                        color = MaterialTheme.colorScheme.onPrimary
                    )
                }
            }

            is UpdateUiState.Error -> {
                Column(modifier = Modifier.fillMaxWidth()) {
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(12.dp))
                            .background(Color(0xFFF4E3E0))
                            .padding(12.dp)
                    ) {
                        Text(
                            text = "Chyba při stahování: ${updateState.message}",
                            fontSize = 12.5.sp,
                            color = Color(0xFF963A2F)
                        )
                    }
                    Spacer(modifier = Modifier.height(6.dp))
                    Button(
                        onClick = onCheck,
                        modifier = Modifier.fillMaxWidth(),
                        shape = RoundedCornerShape(12.dp),
                        colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.primary)
                    ) {
                        Text("Zkusit znovu", fontSize = 12.5.sp, color = MaterialTheme.colorScheme.onPrimary)
                    }
                }
            }
        }
    }
}
