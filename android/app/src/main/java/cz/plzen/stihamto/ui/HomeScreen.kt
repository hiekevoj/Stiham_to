package cz.plzen.stihamto.ui

import androidx.compose.animation.*
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import cz.plzen.stihamto.LiveDeparture
import cz.plzen.stihamto.LiveDeparturesResult
import cz.plzen.stihamto.PmdpStop
import cz.plzen.stihamto.R
import cz.plzen.stihamto.RankedStop

data class HeroState(
    val isRunning: Boolean,
    val isSleeping: Boolean,
    val title: String,
    val subtitle: String,
    val actionText: String
)

@Composable
fun HomeScreen(
    result: LiveDeparturesResult?,
    heroState: HeroState,
    hasMissingPermissions: Boolean,
    selectedTraction: String,
    isMultiStop: Boolean,
    pinnedCis: Int?,
    searchQuery: String,
    searchResults: List<PmdpStop>,
    errorMessage: String?,
    onGpsClick: () -> Unit,
    onRequestPermissions: () -> Unit,
    onHeroToggle: () -> Unit,
    onHeroStop: () -> Unit,
    onSelectTraction: (String) -> Unit,
    onToggleMultiStop: () -> Unit,
    onSearchQueryChange: (String) -> Unit,
    onSelectStop: (Int) -> Unit,
    onSelectAllNearby: () -> Unit,
    onToggleFavoriteLine: (String) -> Unit,
    onRetry: () -> Unit
) {
    LazyColumn(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background),
        contentPadding = PaddingValues(start = 20.dp, end = 20.dp, top = 16.dp, bottom = 32.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        // 1. Editorial Header
        item {
            HomeTopBar(onGpsClick = onGpsClick)
        }

        // 2. Chybějící oprávnění (zobrazí se pouze při chybějících právech)
        if (hasMissingPermissions) {
            item {
                PermissionWarningCard(onRequestPermissions = onRequestPermissions)
            }
        }

        // 3. Hero karta stavu pozadí
        item {
            HeroCard(
                heroState = heroState,
                onHeroToggle = onHeroToggle,
                onHeroStop = onHeroStop
            )
        }

        // 4. Filtry dopravy (Vše, Tram, Bus/Trol) a Chytrý uzel
        item {
            TransportFilterSection(
                selectedTraction = selectedTraction,
                isMultiStop = isMultiStop,
                onSelectTraction = onSelectTraction,
                onToggleMultiStop = onToggleMultiStop
            )
        }

        // 5. Hledání zastávky
        item {
            SearchStopsBlock(
                query = searchQuery,
                searchResults = searchResults,
                onQueryChange = onSearchQueryChange,
                onSelectStop = { stop -> onSelectStop(stop.cisNumber) }
            )
        }

        // 6. Horizontální pás okolních zastávek
        if (result != null && result.nearbyStops.isNotEmpty()) {
            item {
                NearbyStopsRow(
                    nearbyStops = result.nearbyStops,
                    pinnedCis = pinnedCis,
                    isMultiStop = isMultiStop,
                    onSelectAll = onSelectAllNearby,
                    onSelectStop = onSelectStop
                )
            }
        }

        // 7. Hlavička aktuální vybrané zastávky / uzlu
        item {
            StopHeader(result = result, errorMessage = errorMessage)
        }

        // 8. Seznam odjezdů nebo chybový stav
        if (errorMessage != null) {
            item {
                ErrorCard(message = errorMessage, onRetry = onRetry)
            }
        } else if (result == null) {
            item {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(vertical = 32.dp),
                    contentAlignment = Alignment.Center
                ) {
                    CircularProgressIndicator(color = MaterialTheme.colorScheme.primary)
                }
            }
        } else {
            val nowMs = System.currentTimeMillis()
            val validDeps = result.departures.filter { dep ->
                val remMs = dep.targetDepartureEpochMs - nowMs
                remMs >= -30_000L
            }

            if (validDeps.isEmpty()) {
                item {
                    Text(
                        text = "V nejbližší době neodjíždí žádný spoj zvoleného filtru.",
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        fontSize = 14.sp,
                        modifier = Modifier.padding(vertical = 20.dp, horizontal = 4.dp)
                    )
                }
            } else {
                items(validDeps, key = { "${it.lineName}_${it.targetDepartureEpochMs}_${it.destination}_${it.stopCis}" }) { dep ->
                    DepartureCard(
                        departure = dep,
                        onToggleFavorite = { onToggleFavoriteLine(dep.lineName) }
                    )
                }
            }
        }
    }
}

@Composable
private fun HomeTopBar(onGpsClick: () -> Unit) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column {
            Text(
                text = "Stíhám to?",
                fontSize = 28.sp,
                fontWeight = FontWeight.Bold,
                fontFamily = FontFamily.Serif,
                color = MaterialTheme.colorScheme.onBackground
            )
            Text(
                text = "PLZEŇ  ·  MHD V OKOLÍ",
                fontSize = 10.sp,
                letterSpacing = 1.4.sp,
                fontWeight = FontWeight.Medium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }

        IconButton(
            onClick = onGpsClick,
            modifier = Modifier
                .size(44.dp)
                .clip(CircleShape)
                .background(MaterialTheme.colorScheme.surface)
        ) {
            Icon(
                painter = painterResource(id = R.drawable.ic_gps),
                contentDescription = "Moje poloha",
                tint = MaterialTheme.colorScheme.primary,
                modifier = Modifier.size(20.dp)
            )
        }
    }
}

@Composable
private fun PermissionWarningCard(onRequestPermissions: () -> Unit) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(containerColor = Color(0xFF945D14))
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clickable { onRequestPermissions() }
                .padding(16.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.Center
        ) {
            Text(
                text = "Povolit polohu, krokoměr a notifikace",
                color = Color.White,
                fontSize = 13.sp,
                fontWeight = FontWeight.Medium
            )
        }
    }
}

@Composable
private fun HeroCard(
    heroState: HeroState,
    onHeroToggle: () -> Unit,
    onHeroStop: () -> Unit
) {
    val cardColor = if (heroState.isRunning && !heroState.isSleeping) {
        MaterialTheme.colorScheme.primaryContainer
    } else {
        MaterialTheme.colorScheme.surfaceVariant
    }

    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(24.dp),
        colors = CardDefaults.cardColors(containerColor = cardColor)
    ) {
        Column(modifier = Modifier.padding(20.dp)) {
            Text(
                text = heroState.title,
                fontSize = 21.sp,
                fontWeight = FontWeight.Bold,
                fontFamily = FontFamily.Serif,
                color = MaterialTheme.colorScheme.onSurface
            )
            Spacer(modifier = Modifier.height(4.dp))
            Text(
                text = heroState.subtitle,
                fontSize = 13.sp,
                lineHeight = 18.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )

            Spacer(modifier = Modifier.height(16.dp))
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Button(
                    onClick = onHeroToggle,
                    shape = RoundedCornerShape(20.dp),
                    colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.primary)
                ) {
                    Text(
                        text = heroState.actionText,
                        fontSize = 13.sp,
                        fontWeight = FontWeight.Medium,
                        color = MaterialTheme.colorScheme.onPrimary
                    )
                }

                if (heroState.isRunning) {
                    OutlinedButton(
                        onClick = onHeroStop,
                        shape = RoundedCornerShape(20.dp)
                    ) {
                        Text(
                            text = "Vypnout úplně",
                            fontSize = 12.sp,
                            fontWeight = FontWeight.Medium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun TransportFilterSection(
    selectedTraction: String,
    isMultiStop: Boolean,
    onSelectTraction: (String) -> Unit,
    onToggleMultiStop: () -> Unit
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.Bottom
    ) {
        // Sloupec druhů dopravy
        Column(modifier = Modifier.weight(3f)) {
            Text(
                text = "FILTR DOPRAVY",
                fontSize = 9.5.sp,
                letterSpacing = 1.2.sp,
                fontWeight = FontWeight.Medium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(start = 6.dp, bottom = 6.dp)
            )

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                val tractions = listOf(
                    Triple("all", "Vše", R.drawable.ic_all_lines),
                    Triple("tram", "Tramvaje", R.drawable.ic_tram),
                    Triple("bus_trol", "Bus a Trol", R.drawable.ic_bus)
                )
                tractions.forEach { (key, label, iconRes) ->
                    val isSelected = selectedTraction == key
                    Column(
                        horizontalAlignment = Alignment.CenterHorizontally,
                        modifier = Modifier
                            .weight(1f)
                            .clickable { onSelectTraction(key) }
                    ) {
                        Box(
                            modifier = Modifier
                                .size(54.dp)
                                .clip(RoundedCornerShape(16.dp))
                                .background(
                                    if (isSelected) MaterialTheme.colorScheme.primary
                                    else MaterialTheme.colorScheme.surface
                                ),
                            contentAlignment = Alignment.Center
                        ) {
                            Icon(
                                painter = painterResource(id = iconRes),
                                contentDescription = label,
                                tint = if (isSelected) MaterialTheme.colorScheme.onPrimary
                                else MaterialTheme.colorScheme.primary,
                                modifier = Modifier.size(24.dp)
                            )
                        }
                        Spacer(modifier = Modifier.height(6.dp))
                        Text(
                            text = label,
                            fontSize = 11.sp,
                            fontWeight = if (isSelected) FontWeight.Medium else FontWeight.Normal,
                            color = if (isSelected) MaterialTheme.colorScheme.onBackground
                            else MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            }
        }

        // Svislý oddělovač
        Box(
            modifier = Modifier
                .padding(horizontal = 10.dp, vertical = 8.dp)
                .width(1.dp)
                .height(58.dp)
                .background(MaterialTheme.colorScheme.outline.copy(alpha = 0.5f))
        )

        // Sloupec režimu okolí (Chytrý uzel)
        Column(
            modifier = Modifier.weight(1.05f),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Text(
                text = "REŽIM",
                fontSize = 9.5.sp,
                letterSpacing = 1.2.sp,
                fontWeight = FontWeight.Medium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(bottom = 6.dp)
            )

            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                modifier = Modifier.clickable { onToggleMultiStop() }
            ) {
                Box(
                    modifier = Modifier
                        .size(54.dp)
                        .clip(RoundedCornerShape(16.dp))
                        .background(
                            if (isMultiStop) MaterialTheme.colorScheme.primary
                            else MaterialTheme.colorScheme.surface
                        ),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        painter = painterResource(id = R.drawable.ic_nodes),
                        contentDescription = "Režim uzlu",
                        tint = if (isMultiStop) MaterialTheme.colorScheme.onPrimary
                        else MaterialTheme.colorScheme.primary,
                        modifier = Modifier.size(24.dp)
                    )
                }
                Spacer(modifier = Modifier.height(6.dp))
                Text(
                    text = if (isMultiStop) "Chytrý uzel" else "1 zastávka",
                    fontSize = 11.sp,
                    fontWeight = if (isMultiStop) FontWeight.Medium else FontWeight.Normal,
                    color = if (isMultiStop) MaterialTheme.colorScheme.onBackground
                    else MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
    }
}

@Composable
private fun SearchStopsBlock(
    query: String,
    searchResults: List<PmdpStop>,
    onQueryChange: (String) -> Unit,
    onSelectStop: (PmdpStop) -> Unit
) {
    Column {
        OutlinedTextField(
            value = query,
            onValueChange = onQueryChange,
            placeholder = { Text("Najít zastávku v Plzni…", fontSize = 14.sp) },
            leadingIcon = {
                Icon(
                    painter = painterResource(id = R.drawable.ic_search),
                    contentDescription = "Hledat",
                    modifier = Modifier.size(18.dp)
                )
            },
            trailingIcon = {
                if (query.isNotEmpty()) {
                    IconButton(onClick = { onQueryChange("") }) {
                        Text("✕", fontSize = 14.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
            },
            singleLine = true,
            shape = RoundedCornerShape(16.dp),
            colors = OutlinedTextFieldDefaults.colors(
                focusedContainerColor = MaterialTheme.colorScheme.surface,
                unfocusedContainerColor = MaterialTheme.colorScheme.surface,
                focusedBorderColor = MaterialTheme.colorScheme.primary,
                unfocusedBorderColor = MaterialTheme.colorScheme.outline
            ),
            modifier = Modifier.fillMaxWidth()
        )

        AnimatedVisibility(visible = searchResults.isNotEmpty()) {
            Card(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = 6.dp),
                shape = RoundedCornerShape(14.dp),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)
            ) {
                Column(modifier = Modifier.padding(6.dp)) {
                    searchResults.forEach { stop ->
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable { onSelectStop(stop) }
                                .padding(horizontal = 14.dp, vertical = 10.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            val linesTxt = stop.lines.take(6).joinToString(", ") { it.name }
                            Text(
                                text = "${stop.name}  ·  linky $linesTxt",
                                fontSize = 13.5.sp,
                                color = MaterialTheme.colorScheme.onSurface
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun NearbyStopsRow(
    nearbyStops: List<RankedStop>,
    pinnedCis: Int?,
    isMultiStop: Boolean,
    onSelectAll: () -> Unit,
    onSelectStop: (Int) -> Unit
) {
    val scrollState = rememberScrollState()
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .horizontalScroll(scrollState),
        horizontalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        val allActive = pinnedCis == null && isMultiStop
        FilterChip(
            selected = allActive,
            onClick = onSelectAll,
            label = { Text("Chytré okolí", fontSize = 12.sp) },
            shape = RoundedCornerShape(18.dp),
            colors = FilterChipDefaults.filterChipColors(
                selectedContainerColor = MaterialTheme.colorScheme.primary,
                selectedLabelColor = MaterialTheme.colorScheme.onPrimary,
                containerColor = MaterialTheme.colorScheme.surfaceVariant,
                labelColor = MaterialTheme.colorScheme.onSurface
            )
        )

        nearbyStops.take(5).forEach { near ->
            val active = pinnedCis == near.stop.cisNumber
            FilterChip(
                selected = active,
                onClick = { onSelectStop(near.stop.cisNumber) },
                label = { Text("${near.stop.name}  ·  ${near.distanceMeters} m", fontSize = 12.sp) },
                shape = RoundedCornerShape(18.dp),
                colors = FilterChipDefaults.filterChipColors(
                    selectedContainerColor = MaterialTheme.colorScheme.primary,
                    selectedLabelColor = MaterialTheme.colorScheme.onPrimary,
                    containerColor = MaterialTheme.colorScheme.surfaceVariant,
                    labelColor = MaterialTheme.colorScheme.onSurface
                )
            )
        }
    }
}

@Composable
private fun StopHeader(result: LiveDeparturesResult?, errorMessage: String?) {
    Column(modifier = Modifier.padding(top = 4.dp)) {
        if (result != null) {
            val isFarAway = result.primaryStop.distanceMeters > 18_000
            val title = when {
                isFarAway -> "Mimo dosah PMDP Plzeň"
                result.clusterStops.size > 1 -> result.clusterStops.joinToString(" + ") { it.stop.name }
                else -> result.primaryStop.stop.name
            }

            val meta = if (isFarAway) {
                "Aplikace je určena pro Plzeň (nejbližší: ${result.primaryStop.stop.name}, ${result.primaryStop.distanceMeters / 1000} km)"
            } else {
                val stopsMeta = result.clusterStops.joinToString("  ·  ") {
                    "${it.stop.name}: ${it.distanceMeters} m (${it.walkingMinutes} min chůze)"
                }
                "$stopsMeta  ·  ${result.fetchedAtHHMMSS.substringBeforeLast(":")}"
            }

            Text(
                text = title,
                fontSize = 23.sp,
                fontWeight = FontWeight.Bold,
                fontFamily = FontFamily.Serif,
                color = MaterialTheme.colorScheme.onBackground
            )
            Spacer(modifier = Modifier.height(2.dp))
            Text(
                text = meta,
                fontSize = 12.5.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        } else if (errorMessage == null) {
            Text(
                text = "Načítám zastávky…",
                fontSize = 23.sp,
                fontWeight = FontWeight.Bold,
                fontFamily = FontFamily.Serif,
                color = MaterialTheme.colorScheme.onBackground
            )
            Spacer(modifier = Modifier.height(2.dp))
            Text(
                text = "Počítám pěší trasu k nejbližším zastávkám",
                fontSize = 12.5.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

@Composable
private fun DepartureCard(
    departure: LiveDeparture,
    onToggleFavorite: () -> Unit
) {
    val isMissed = departure.catchability.status == "missed"
    val cardAlpha = if (isMissed) 0.58f else 1.0f

    val (badgeBg, badgeFg, typeLabel) = when {
        departure.isNight -> Triple(Color(0xFF2B303A), Color(0xFFF6F5F0), "NOC")
        departure.tractionType == 1 -> Triple(Color(0xFFF3E5AB), Color(0xFF3A2E11), "TRAM")
        departure.tractionType == 2 -> Triple(Color(0xFFDCEBE1), Color(0xFF1E462E), "TROL")
        else -> Triple(Color(0xFFF5E1DC), Color(0xFF6E291D), "BUS")
    }

    val (catchBg, catchFg) = when (departure.catchability.status) {
        "comfortable" -> Color(0xFFE2EFE6) to Color(0xFF255238)
        "hurry" -> Color(0xFFF6EAD4) to Color(0xFF8F5812)
        else -> Color(0xFFF4E3E0) to Color(0xFF963A2F)
    }

    Card(
        modifier = Modifier
            .fillMaxWidth()
            .alpha(cardAlpha),
        shape = RoundedCornerShape(20.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        border = if (departure.isFavorite) CardDefaults.outlinedCardBorder().copy(
            brush = androidx.compose.ui.graphics.SolidColor(MaterialTheme.colorScheme.primary),
            width = 2.dp
        ) else CardDefaults.outlinedCardBorder().copy(
            brush = androidx.compose.ui.graphics.SolidColor(MaterialTheme.colorScheme.outline)
        )
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(14.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            // Levý odznak linky (52x52 dp) – klepnutím přepíná prioritní linku
            Box(
                modifier = Modifier
                    .size(52.dp)
                    .clip(RoundedCornerShape(14.dp))
                    .background(badgeBg)
                    .clickable { onToggleFavorite() },
                contentAlignment = Alignment.Center
            ) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Text(
                        text = departure.lineName,
                        fontSize = 18.sp,
                        fontWeight = FontWeight.Bold,
                        color = badgeFg
                    )
                    Text(
                        text = typeLabel,
                        fontSize = 8.sp,
                        letterSpacing = 0.8.sp,
                        fontWeight = FontWeight.Medium,
                        color = badgeFg
                    )
                }
            }

            Spacer(modifier = Modifier.width(14.dp))

            // Střední sloupec: Cíl, zastávka, zpoždění, pilulky stavu
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = departure.destination,
                    fontSize = 16.sp,
                    fontWeight = FontWeight.Medium,
                    color = MaterialTheme.colorScheme.onSurface,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )

                val delayPart = when {
                    departure.delayMin == null -> "dle JŘ"
                    departure.delayMin == 0 -> "včas"
                    else -> "+${departure.delayMin} min"
                }

                Text(
                    text = "${departure.stopName} (${departure.stopDistanceMeters} m)  ·  $delayPart",
                    fontSize = 12.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )

                Spacer(modifier = Modifier.height(6.dp))

                Row(verticalAlignment = Alignment.CenterVertically) {
                    Box(
                        modifier = Modifier
                            .clip(RoundedCornerShape(10.dp))
                            .background(catchBg)
                            .padding(horizontal = 10.dp, vertical = 3.dp)
                    ) {
                        Text(
                            text = departure.catchability.label,
                            color = catchFg,
                            fontSize = 11.sp,
                            fontWeight = FontWeight.Medium
                        )
                    }

                    if (departure.isFavorite) {
                        Spacer(modifier = Modifier.width(6.dp))
                        Box(
                            modifier = Modifier
                                .clip(RoundedCornerShape(10.dp))
                                .background(MaterialTheme.colorScheme.surfaceVariant)
                                .clickable { onToggleFavorite() }
                                .padding(horizontal = 9.dp, vertical = 3.dp)
                        ) {
                            Text(
                                text = "★ Prioritní",
                                color = MaterialTheme.colorScheme.primary,
                                fontSize = 10.5.sp,
                                fontWeight = FontWeight.Medium
                            )
                        }
                    }
                }
            }

            Spacer(modifier = Modifier.width(8.dp))

            // Pravý sloupec: Minuty do odjezdu a čas
            Column(
                modifier = Modifier.widthIn(min = 68.dp),
                horizontalAlignment = Alignment.End
            ) {
                val leavingText = when {
                    departure.leavingInMin < 0 -> "odjelo"
                    departure.leavingInMin == 0 -> "teď"
                    else -> "${departure.leavingInMin} min"
                }
                val leavingColor = if (departure.leavingInMin < 0) Color(0xFF963A2F)
                else MaterialTheme.colorScheme.onSurface

                Text(
                    text = leavingText,
                    fontSize = 18.sp,
                    fontWeight = FontWeight.Bold,
                    color = leavingColor
                )
                Spacer(modifier = Modifier.height(3.dp))
                Text(
                    text = departure.estimatedHHMM,
                    fontSize = 12.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
    }
}

@Composable
private fun ErrorCard(message: String, onRetry: () -> Unit) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)
    ) {
        Column(
            modifier = Modifier.padding(20.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Text(
                text = "Spojení není dostupné",
                fontSize = 18.sp,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.onSurface
            )
            Spacer(modifier = Modifier.height(4.dp))
            Text(
                text = message,
                fontSize = 13.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Spacer(modifier = Modifier.height(16.dp))
            Button(
                onClick = onRetry,
                shape = RoundedCornerShape(14.dp),
                colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.primary)
            ) {
                Text(
                    text = "Zkusit načíst znovu ↻",
                    fontSize = 13.sp,
                    fontWeight = FontWeight.Medium,
                    color = MaterialTheme.colorScheme.onPrimary
                )
            }
        }
    }
}
