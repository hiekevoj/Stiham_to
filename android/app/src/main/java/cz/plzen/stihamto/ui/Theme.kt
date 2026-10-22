package cz.plzen.stihamto.ui

import android.os.Build
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext

// Plzeň / Botanical Brand Palette (Inspirováno Leafora design systémem)
val ForestGreen = Color(0xFF2C4C38)
val ForestGreenDark = Color(0xFF1D3526)
val SageLight = Color(0xFFE1ECE3)
val SageStone = Color(0xFFEAE7DD)
val CreamBg = Color(0xFFF6F5F0)
val InkDark = Color(0xFF1D2B22)
val InkMuted = Color(0xFF6B756E)
val BorderSoft = Color(0xFFE5E2D7)
val CardWhite = Color(0xFFFFFFFF)

// Dark mode specifické odstíny
val DarkBg = Color(0xFF131714)
val DarkSurface = Color(0xFF1A211D)
val DarkSurfaceVariant = Color(0xFF242C26)
val DarkTextPrimary = Color(0xFFE5E8E5)
val DarkTextMuted = Color(0xFF9AA59D)
val DarkPrimary = Color(0xFF86B997)

// Fallback Light ColorScheme
private val LightColorScheme = lightColorScheme(
    primary = ForestGreen,
    onPrimary = Color.White,
    primaryContainer = SageLight,
    onPrimaryContainer = ForestGreenDark,
    secondary = Color(0xFF43654E),
    onSecondary = Color.White,
    secondaryContainer = SageStone,
    onSecondaryContainer = InkDark,
    background = CreamBg,
    onBackground = InkDark,
    surface = CardWhite,
    onSurface = InkDark,
    surfaceVariant = SageStone,
    onSurfaceVariant = InkMuted,
    outline = BorderSoft
)

// Fallback Dark ColorScheme
private val DarkColorScheme = darkColorScheme(
    primary = DarkPrimary,
    onPrimary = Color(0xFF072111),
    primaryContainer = Color(0xFF1E3A26),
    onPrimaryContainer = Color(0xFFD4E8DC),
    secondary = Color(0xFFA1B3A6),
    onSecondary = Color(0xFF1B261E),
    secondaryContainer = DarkSurfaceVariant,
    onSecondaryContainer = DarkTextPrimary,
    background = DarkBg,
    onBackground = DarkTextPrimary,
    surface = DarkSurface,
    onSurface = DarkTextPrimary,
    surfaceVariant = DarkSurfaceVariant,
    onSurfaceVariant = DarkTextMuted,
    outline = Color(0xFF333E36)
)

/**
 * Hlavní téma aplikace Stíhám to? (PMDP Plzeň)
 * Podporuje Material 3 Dynamic Color na Androidu 12+ (API 31+).
 */
@Composable
fun StihamToTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    dynamicColor: Boolean = true,
    content: @Composable () -> Unit
) {
    val context = LocalContext.current
    val colorScheme = when {
        dynamicColor && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S -> {
            if (darkTheme) dynamicDarkColorScheme(context) else dynamicLightColorScheme(context)
        }
        darkTheme -> DarkColorScheme
        else -> LightColorScheme
    }

    MaterialTheme(
        colorScheme = colorScheme,
        typography = Typography(),
        content = content
    )
}
