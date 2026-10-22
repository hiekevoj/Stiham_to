package cz.plzen.stihamto.ui

import android.os.Build
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.background
import androidx.compose.foundation.border
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
    themeMode: AppThemeMode,
    onThemeModeChange: (AppThemeMode) -> Unit,
    colorTheme: AppColorTheme,
    onColorThemeChange: (AppColorTheme) -> Unit,
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
    diagState: SystemDiagState,
    onRequestPermissions: () -> Unit,
    onOpenBatterySettings: () -> Unit,
    updateState: UpdateUiState,
    onCheckForUpdates: () -> Unit,
    onDownloadAndInstall: (UpdateCheckResult) -> Unit,
    onInstallDownloaded: (File) -> Unit,
    appVersion: String,
    contentPadding: PaddingValues = PaddingValues(start = 20.dp, end = 20.dp, top = 20.dp, bottom = 44.dp)
) {
    var favPickerExpanded by remember { mutableStateOf(false) }

    LazyColumn(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background),
        contentPadding = contentPadding,
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        // Hlavička
        item {
            Column(modifier = Modifier.padding(bottom = 2.dp)) {
                Text(
                    text = "Nastavení",
                    fontSize = 28.sp,
                    fontWeight = FontWeight.Bold,
                    fontFamily = FontFamily.Serif,
                    color = MaterialTheme.colorScheme.onBackground
                )
                Text(
                    text = "PŘIZPŮSOBENÍ, VÝPOČET A AUTOMATIKA",
                    fontSize = 10.sp,
                    letterSpacing = 1.2.sp,
                    fontWeight = FontWeight.Medium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }

        // ==========================================
        // 1. SEKCE: VZHLED A BAREVNÝ STYL
        // ==========================================
        item {
            SettingsCategoryCard(title = "Vzhled a téma") {
                // Přepínač Světlý / Tmavý / Systém
                Text(
                    text = "Režim zobrazení",
                    fontSize = 14.5.sp,
                    fontWeight = FontWeight.Medium,
                    color = MaterialTheme.colorScheme.onSurface
                )
                Spacer(modifier = Modifier.height(8.dp))
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    AppThemeMode.values().forEach { mode ->
                        val isSelected = themeMode == mode
                        Box(
                            modifier = Modifier
                                .weight(1f)
                                .clip(RoundedCornerShape(12.dp))
                                .background(
                                    if (isSelected) MaterialTheme.colorScheme.primary
                                    else MaterialTheme.colorScheme.surfaceVariant
                                )
                                .clickable { onThemeModeChange(mode) }
                                .padding(vertical = 10.dp),
                            contentAlignment = Alignment.Center
                        ) {
                            Text(
                                text = mode.title,
                                fontSize = 13.sp,
                                fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Medium,
                                color = if (isSelected) MaterialTheme.colorScheme.onPrimary
                                else MaterialTheme.colorScheme.onSurface
                            )
                        }
                    }
                }

                SettingsDivider()

                // Výběr barevné palety (všechny barvy v jednom řádku bez popisků)
                Text(
                    text = "Barevný styl aplikace",
                    fontSize = 14.5.sp,
                    fontWeight = FontWeight.Medium,
                    color = MaterialTheme.colorScheme.onSurface
                )
                Spacer(modifier = Modifier.height(10.dp))

                val availableThemes = remember {
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                        AppColorTheme.values().toList()
                    } else {
                        AppColorTheme.values().filter { it != AppColorTheme.DYNAMIC }
                    }
                }

                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(vertical = 4.dp),
                    horizontalArrangement = Arrangement.SpaceEvenly,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    availableThemes.forEach { item ->
                        val isSelected = colorTheme == item
                        val ringColor = if (isSelected) MaterialTheme.colorScheme.primary else Color.Transparent

                        Box(
                            modifier = Modifier
                                .size(46.dp)
                                .clip(CircleShape)
                                .border(2.5.dp, ringColor, CircleShape)
                                .padding(4.dp)
                                .clip(CircleShape)
                                .background(
                                    if (item == AppColorTheme.DYNAMIC) MaterialTheme.colorScheme.primary
                                    else item.primaryColor
                                )
                                .clickable { onColorThemeChange(item) },
                            contentAlignment = Alignment.Center
                        ) {
                            if (isSelected) {
                                Box(
                                    modifier = Modifier
                                        .size(10.dp)
                                        .clip(CircleShape)
                                        .background(Color.White)
                                )
                            } else if (item == AppColorTheme.DYNAMIC) {
                                Text(
                                    text = "auto",
                                    fontSize = 10.sp,
                                    fontWeight = FontWeight.Bold,
                                    color = Color.White.copy(alpha = 0.9f)
                                )
                            }
                        }
                    }
                }
            }
        }

        // ==========================================
        // 2. SEKCE: CHŮZE A VÝPOČET SPOJŮ
        // ==========================================
        item {
            SettingsCategoryCard(title = "Chůze a vyhodnocení spojů") {
                SegmentedSettingRow(
                    title = "Tempo chůze",
                    subtitle = "Rychlost pro výpočet docházkové doby k označníku",
                    options = listOf(
                        "relaxed" to "Klidné (3,8 km/h)",
                        "normal" to "Běžné (4,4 km/h)",
                        "brisk" to "Svižné (5,3 km/h)"
                    ),
                    selected = walkSpeed,
                    onSelected = onWalkSpeedChange
                )

                SettingsDivider()

                SegmentedSettingRow(
                    title = "Časová rezerva na semafory",
                    subtitle = "Přidá rezervu na červenou na přechodu",
                    options = listOf(
                        0 to "0 s",
                        30 to "+30 s",
                        60 to "+1 min"
                    ),
                    selected = crosswalkBuffer,
                    onSelected = onCrosswalkBufferChange
                )

                SettingsDivider()

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = "Skrýt ujíždějící spoje",
                            fontSize = 14.sp,
                            fontWeight = FontWeight.Medium,
                            color = MaterialTheme.colorScheme.onSurface
                        )
                        Text(
                            text = "V notifikaci nezobrazovat spoje se stavem Nestíháš",
                            fontSize = 12.sp,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    Switch(
                        checked = hideMissed,
                        onCheckedChange = onHideMissedChange,
                        colors = SwitchDefaults.colors(
                            checkedThumbColor = MaterialTheme.colorScheme.primary,
                            checkedTrackColor = MaterialTheme.colorScheme.primaryContainer
                        )
                    )
                }

                SettingsDivider()

                SegmentedSettingRow(
                    title = "Počet spojů v notifikaci",
                    subtitle = "Kolik nejbližších spojů se vejde na zamykací obrazovku",
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

        // ==========================================
        // 3. SEKCE: ZASTÁVKY A LINKY
        // ==========================================
        item {
            SettingsCategoryCard(title = "Zastávky a prioritní linky") {
                SegmentedSettingRow(
                    title = "Dosah Chytrého uzlu",
                    subtitle = "Vzdálenost pro sloučení okolních zastávek v jeden celek",
                    options = listOf(
                        300 to "300 m",
                        400 to "400 m",
                        500 to "500 m"
                    ),
                    selected = clusterRadius,
                    onSelected = onClusterRadiusChange
                )

                SettingsDivider()

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = "Automaticky přibrat tramvaj",
                            fontSize = 14.sp,
                            fontWeight = FontWeight.Medium,
                            color = MaterialTheme.colorScheme.onSurface
                        )
                        Text(
                            text = "Při chůzi u busu/trolejbusu načte i nejbližší tramvaj",
                            fontSize = 12.sp,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    Switch(
                        checked = autoTram,
                        onCheckedChange = onAutoTramChange,
                        colors = SwitchDefaults.colors(
                            checkedThumbColor = MaterialTheme.colorScheme.primary,
                            checkedTrackColor = MaterialTheme.colorScheme.primaryContainer
                        )
                    )
                }

                SettingsDivider()

                // Výběr prioritních linek
                val favText = if (favoriteLines.isEmpty()) "Žádná vybraná linka"
                else "Vybráno: ${favoriteLines.sorted().joinToString(", ")}"

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = "Prioritní linky",
                            fontSize = 14.sp,
                            fontWeight = FontWeight.Medium,
                            color = MaterialTheme.colorScheme.onSurface
                        )
                        Text(
                            text = favText,
                            fontSize = 12.sp,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    FilledTonalButton(
                        onClick = { favPickerExpanded = !favPickerExpanded },
                        shape = RoundedCornerShape(12.dp),
                        contentPadding = PaddingValues(horizontal = 14.dp, vertical = 6.dp)
                    ) {
                        Text(if (favPickerExpanded) "Hotovo" else "Vybrat")
                    }
                }

                AnimatedVisibility(visible = favPickerExpanded) {
                    Column(modifier = Modifier.padding(top = 12.dp)) {
                        val columns = 5
                        val rows = allKnownLines.chunked(columns)

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
                                                .clip(RoundedCornerShape(10.dp))
                                                .background(
                                                    if (isFav) MaterialTheme.colorScheme.primary
                                                    else MaterialTheme.colorScheme.surfaceVariant
                                                )
                                                .clickable { onToggleFavoriteLine(lineName) }
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
                            TextButton(
                                onClick = onClearFavoriteLines,
                                modifier = Modifier.align(Alignment.CenterHorizontally)
                            ) {
                                Text("Zrušit všechny prioritní linky", color = Color(0xFF963A2F), fontSize = 12.sp)
                            }
                        }
                    }
                }
            }
        }

        // ==========================================
        // 4. SEKCE: AUTOMATIKA & KLIDOVÉ WI-FI
        // ==========================================
        item {
            SettingsCategoryCard(title = "Automatika a klidové Wi-Fi") {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = "Režim spánku na Wi-Fi",
                            fontSize = 14.sp,
                            fontWeight = FontWeight.Medium,
                            color = MaterialTheme.colorScheme.onSurface
                        )
                        Text(
                            text = "Doma odpočívá s nulovou spotřebou, probudí se při odchodu",
                            fontSize = 12.sp,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    Switch(
                        checked = sleepOnWifi,
                        onCheckedChange = onSleepOnWifiChange,
                        colors = SwitchDefaults.colors(
                            checkedThumbColor = MaterialTheme.colorScheme.primary,
                            checkedTrackColor = MaterialTheme.colorScheme.primaryContainer
                        )
                    )
                }

                AnimatedVisibility(visible = sleepOnWifi) {
                    Column {
                        SettingsDivider()

                        // Správa uložených Wi-Fi
                        val currentWifiLabel = if (!currentWifiSsid.isNullOrEmpty()) {
                            val isSaved = homeWifiSsids.contains(currentWifiSsid)
                            "Aktuální: $currentWifiSsid (${if (isSaved) "Uložená" else "Není uložena"})"
                        } else {
                            "Aktuálně nejste připojeni k Wi-Fi"
                        }

                        Text(
                            text = "Klidové Wi-Fi sítě (Doma / Práce)",
                            fontSize = 14.sp,
                            fontWeight = FontWeight.Medium,
                            color = MaterialTheme.colorScheme.onSurface
                        )
                        Text(
                            text = currentWifiLabel,
                            fontSize = 12.sp,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )

                        if (!currentWifiSsid.isNullOrEmpty() && !homeWifiSsids.contains(currentWifiSsid)) {
                            Spacer(modifier = Modifier.height(8.dp))
                            Button(
                                onClick = { onAddHomeWifi(currentWifiSsid) },
                                shape = RoundedCornerShape(10.dp),
                                modifier = Modifier.fillMaxWidth()
                            ) {
                                Text("+ Přidat síť \"$currentWifiSsid\" mezi domácí", fontSize = 12.5.sp)
                            }
                        }

                        if (homeWifiSsids.isNotEmpty()) {
                            Spacer(modifier = Modifier.height(8.dp))
                            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                                homeWifiSsids.forEach { ssid ->
                                    Row(
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .clip(RoundedCornerShape(8.dp))
                                            .background(MaterialTheme.colorScheme.surfaceVariant)
                                            .padding(horizontal = 10.dp, vertical = 6.dp),
                                        horizontalArrangement = Arrangement.SpaceBetween,
                                        verticalAlignment = Alignment.CenterVertically
                                    ) {
                                        Text(ssid, fontSize = 12.5.sp, color = MaterialTheme.colorScheme.onSurface)
                                        Text(
                                            text = "Odebrat",
                                            fontSize = 11.5.sp,
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
                }

                SettingsDivider()

                SegmentedSettingRow(
                    title = "Probuzení chůzí (krokoměr)",
                    subtitle = "Počet kroků pro automatické rozsvícení odjezdů",
                    options = listOf(
                        15 to "15 kroků",
                        30 to "30 kroků",
                        50 to "50 kroků"
                    ),
                    selected = wakeUpSteps,
                    onSelected = onWakeUpStepsChange
                )

                SettingsDivider()

                SegmentedSettingRow(
                    title = "Pauza po jízdě v MHD",
                    subtitle = "Pauza po výstupu, aby se sledování hned nezapínalo",
                    options = listOf(
                        0 to "Vypnuto",
                        10 to "10 min",
                        20 to "20 min"
                    ),
                    selected = postRideCooldown,
                    onSelected = onPostRideCooldownChange
                )
            }
        }

        // ==========================================
        // 5. SEKCE: STAV SYSTÉMU A BATERIE
        // ==========================================
        item {
            SettingsCategoryCard(title = "Stav systému a oprávnění") {
                val items = listOf(
                    "Poloha GPS" to diagState.hasLoc,
                    "Pohyb a chůze" to diagState.hasActivity,
                    "Notifikace" to diagState.hasNotif,
                    "Baterie (bez omezení)" to diagState.ignoringBatteryOpt
                )

                Column(
                    modifier = Modifier.fillMaxWidth(),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    items.forEach { (label, ok) ->
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clip(RoundedCornerShape(12.dp))
                                .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f))
                                .padding(horizontal = 14.dp, vertical = 10.dp),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text(
                                text = label,
                                fontSize = 13.5.sp,
                                fontWeight = FontWeight.Medium,
                                color = MaterialTheme.colorScheme.onSurface
                            )
                            Box(
                                modifier = Modifier
                                    .clip(RoundedCornerShape(8.dp))
                                    .background(
                                        if (ok) MaterialTheme.colorScheme.primaryContainer
                                        else MaterialTheme.colorScheme.errorContainer
                                    )
                                    .padding(horizontal = 10.dp, vertical = 3.dp)
                            ) {
                                Text(
                                    text = if (ok) "Aktivní" else "Chybí",
                                    fontSize = 11.sp,
                                    fontWeight = FontWeight.SemiBold,
                                    color = if (ok) MaterialTheme.colorScheme.onPrimaryContainer
                                    else MaterialTheme.colorScheme.onErrorContainer
                                )
                            }
                        }
                    }
                }

                if (!diagState.hasLoc || !diagState.hasActivity || !diagState.hasNotif) {
                    Spacer(modifier = Modifier.height(12.dp))
                    Button(
                        onClick = onRequestPermissions,
                        shape = RoundedCornerShape(12.dp),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Text("Doplnit chybějící oprávnění", fontSize = 13.sp)
                    }
                }

                if (!diagState.ignoringBatteryOpt) {
                    Spacer(modifier = Modifier.height(8.dp))
                    FilledTonalButton(
                        onClick = onOpenBatterySettings,
                        shape = RoundedCornerShape(12.dp),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Text("Povolit běh na pozadí", fontSize = 13.sp)
                    }
                }
            }
        }

        // ==========================================
        // 6. SEKCE: AKTUALIZACE
        // ==========================================
        item {
            SettingsCategoryCard(title = "Aktualizace") {
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
                    .padding(vertical = 8.dp),
                contentAlignment = Alignment.Center
            ) {
                Text(
                    text = "Stíhám to? v$appVersion",
                    fontSize = 11.5.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
    }
}

@Composable
private fun SettingsCategoryCard(
    title: String,
    content: @Composable ColumnScope.() -> Unit
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(18.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)
    ) {
        Column(
            modifier = Modifier.padding(16.dp)
        ) {
            Text(
                text = title,
                fontSize = 16.sp,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.onSurface,
                modifier = Modifier.padding(bottom = 12.dp)
            )
            content()
        }
    }
}

@Composable
private fun SettingsDivider() {
    HorizontalDivider(
        modifier = Modifier.padding(vertical = 12.dp),
        thickness = 1.dp,
        color = MaterialTheme.colorScheme.outline.copy(alpha = 0.4f)
    )
}

@Composable
private fun <T> SegmentedSettingRow(
    title: String,
    subtitle: String,
    options: List<Pair<T, String>>,
    selected: T,
    onSelected: (T) -> Unit
) {
    Column {
        Text(
            text = title,
            fontSize = 14.sp,
            fontWeight = FontWeight.Medium,
            color = MaterialTheme.colorScheme.onSurface
        )
        Text(
            text = subtitle,
            fontSize = 11.5.sp,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(bottom = 8.dp)
        )

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            options.forEach { (value, label) ->
                val active = value == selected
                Box(
                    modifier = Modifier
                        .weight(1f)
                        .clip(RoundedCornerShape(10.dp))
                        .background(
                            if (active) MaterialTheme.colorScheme.primary
                            else MaterialTheme.colorScheme.surfaceVariant
                        )
                        .clickable { onSelected(value) }
                        .padding(vertical = 8.dp, horizontal = 2.dp),
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        text = label,
                        fontSize = 12.sp,
                        fontWeight = if (active) FontWeight.Bold else FontWeight.Normal,
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
                text = "Verze aplikace",
                fontSize = 14.sp,
                fontWeight = FontWeight.Medium,
                color = MaterialTheme.colorScheme.onSurface
            )
            Text(
                text = "v$currentVersion",
                fontSize = 13.sp,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.primary
            )
        }

        Spacer(modifier = Modifier.height(10.dp))

        when (updateState) {
            is UpdateUiState.Idle -> {
                Button(
                    onClick = onCheck,
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(12.dp)
                ) {
                    Text("Zkontrolovat aktualizace", fontSize = 13.sp)
                }
            }

            is UpdateUiState.Checking -> {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(12.dp))
                        .background(MaterialTheme.colorScheme.surfaceVariant)
                        .padding(12.dp),
                    horizontalArrangement = Arrangement.Center,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(16.dp),
                        strokeWidth = 2.dp,
                        color = MaterialTheme.colorScheme.primary
                    )
                    Spacer(modifier = Modifier.width(10.dp))
                    Text("Ověřuji novou verzi…", fontSize = 12.5.sp)
                }
            }

            is UpdateUiState.UpToDate -> {
                Column(modifier = Modifier.fillMaxWidth()) {
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(10.dp))
                            .background(MaterialTheme.colorScheme.primaryContainer)
                            .padding(10.dp),
                        contentAlignment = Alignment.Center
                    ) {
                        Text(
                            text = "Máte nejnovější verzi (${updateState.version})",
                            fontSize = 12.5.sp,
                            fontWeight = FontWeight.Medium,
                            color = MaterialTheme.colorScheme.onPrimaryContainer
                        )
                    }
                    TextButton(
                        onClick = onCheck,
                        modifier = Modifier.align(Alignment.CenterHorizontally)
                    ) {
                        Text("Zkontrolovat znovu", fontSize = 11.5.sp)
                    }
                }
            }

            is UpdateUiState.UpdateAvailable -> {
                val info = updateState.info
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(12.dp))
                        .background(MaterialTheme.colorScheme.primaryContainer)
                        .padding(12.dp)
                ) {
                    Text(
                        text = "K dispozici je verze ${info.latestVersionName}",
                        fontSize = 13.5.sp,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.onPrimaryContainer
                    )
                    if (info.releaseNotes.isNotEmpty()) {
                        Spacer(modifier = Modifier.height(4.dp))
                        Text(
                            text = info.releaseNotes,
                            fontSize = 11.5.sp,
                            color = MaterialTheme.colorScheme.onPrimaryContainer
                        )
                    }
                    Spacer(modifier = Modifier.height(10.dp))
                    Button(
                        onClick = { onDownload(info) },
                        modifier = Modifier.fillMaxWidth(),
                        shape = RoundedCornerShape(10.dp)
                    ) {
                        Text("Stáhnout a nainstalovat", fontSize = 12.5.sp)
                    }
                }
            }

            is UpdateUiState.Downloading -> {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(12.dp))
                        .background(MaterialTheme.colorScheme.surfaceVariant)
                        .padding(12.dp)
                ) {
                    val percent = (updateState.progress * 100).toInt()
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Text("Stahuji aktualizaci…", fontSize = 12.5.sp)
                        Text("$percent %", fontSize = 12.5.sp, fontWeight = FontWeight.Bold)
                    }
                    Spacer(modifier = Modifier.height(6.dp))
                    LinearProgressIndicator(
                        progress = { updateState.progress },
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(6.dp)
                            .clip(RoundedCornerShape(3.dp)),
                        color = MaterialTheme.colorScheme.primary
                    )
                }
            }

            is UpdateUiState.ReadyToInstall -> {
                Button(
                    onClick = { onInstall(updateState.file) },
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(12.dp)
                ) {
                    Text("Nainstalovat aktualizaci", fontSize = 13.sp)
                }
            }

            is UpdateUiState.Error -> {
                Column(modifier = Modifier.fillMaxWidth()) {
                    Text(
                        text = "Chyba: ${updateState.message}",
                        fontSize = 12.sp,
                        color = Color(0xFF963A2F)
                    )
                    Spacer(modifier = Modifier.height(6.dp))
                    Button(
                        onClick = onCheck,
                        shape = RoundedCornerShape(10.dp),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Text("Zkusit znovu", fontSize = 12.sp)
                    }
                }
            }
        }
    }
}
