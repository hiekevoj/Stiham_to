package cz.plzen.stihamto.ui

import android.os.Build
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext

enum class AppThemeMode(val title: String) {
    SYSTEM("Systém"),
    LIGHT("Světlý"),
    DARK("Tmavý")
}

enum class AppColorTheme(val title: String, val subtitle: String, val primaryColor: Color) {
    BOTANICAL("Smaragd", "Originální šalvěj a les", Color(0xFF2C4C38)),
    DYNAMIC("Material You", "Dynamicky dle tapety (Android 12+)", Color(0xFF3F826D)),
    PILSNER("Jantar", "Zlatavý jantar a med", Color(0xFFA06200)),
    TRAM_NIGHT("Noční linka", "Hluboké indigo a azur", Color(0xFF0F5B80)),
    RUBY("Rubín", "Karmín a teplé odstíny", Color(0xFF8B1E3F))
}

// === 1. BOTANICAL PALETTE ===
private val BotanicalLight = lightColorScheme(
    primary = Color(0xFF2C4C38),
    onPrimary = Color.White,
    primaryContainer = Color(0xFFE1ECE3),
    onPrimaryContainer = Color(0xFF142B1E),
    secondary = Color(0xFF43654E),
    onSecondary = Color.White,
    secondaryContainer = Color(0xFFEAE7DD),
    onSecondaryContainer = Color(0xFF1D2B22),
    background = Color(0xFFF6F5F0),
    onBackground = Color(0xFF1D2B22),
    surface = Color(0xFFFFFFFF),
    onSurface = Color(0xFF1D2B22),
    surfaceVariant = Color(0xFFEAE7DD),
    onSurfaceVariant = Color(0xFF6B756E),
    outline = Color(0xFFE5E2D7)
)

private val BotanicalDark = darkColorScheme(
    primary = Color(0xFF86B997),
    onPrimary = Color(0xFF072111),
    primaryContainer = Color(0xFF1E3A26),
    onPrimaryContainer = Color(0xFFD4E8DC),
    secondary = Color(0xFFA1B3A6),
    onSecondary = Color(0xFF1B261E),
    secondaryContainer = Color(0xFF242C26),
    onSecondaryContainer = Color(0xFFE5E8E5),
    background = Color(0xFF131714),
    onBackground = Color(0xFFE5E8E5),
    surface = Color(0xFF1A211D),
    onSurface = Color(0xFFE5E8E5),
    surfaceVariant = Color(0xFF242C26),
    onSurfaceVariant = Color(0xFF9AA59D),
    outline = Color(0xFF333E36)
)

// === 2. PILSNER (LEŽÁK) PALETTE ===
private val PilsnerLight = lightColorScheme(
    primary = Color(0xFFA06200),
    onPrimary = Color.White,
    primaryContainer = Color(0xFFFFF0D4),
    onPrimaryContainer = Color(0xFF351C00),
    secondary = Color(0xFF7E570F),
    onSecondary = Color.White,
    secondaryContainer = Color(0xFFF5EEDB),
    onSecondaryContainer = Color(0xFF2C1A04),
    background = Color(0xFFFAF7F2),
    onBackground = Color(0xFF2C1A04),
    surface = Color(0xFFFFFFFF),
    onSurface = Color(0xFF2C1A04),
    surfaceVariant = Color(0xFFF1EAD9),
    onSurfaceVariant = Color(0xFF7B6A56),
    outline = Color(0xFFE5DEC9)
)

private val PilsnerDark = darkColorScheme(
    primary = Color(0xFFFFB85E),
    onPrimary = Color(0xFF553100),
    primaryContainer = Color(0xFF452700),
    onPrimaryContainer = Color(0xFFFFDDB3),
    secondary = Color(0xFFE2BE7D),
    onSecondary = Color(0xFF2A1C00),
    secondaryContainer = Color(0xFF332718),
    onSecondaryContainer = Color(0xFFF5EBE1),
    background = Color(0xFF18120B),
    onBackground = Color(0xFFF5EBE1),
    surface = Color(0xFF221B13),
    onSurface = Color(0xFFF5EBE1),
    surfaceVariant = Color(0xFF30261B),
    onSurfaceVariant = Color(0xFFAA9B8D),
    outline = Color(0xFF473A2B)
)

// === 3. TRAM NIGHT (INDIGO / AZUR) PALETTE ===
private val TramNightLight = lightColorScheme(
    primary = Color(0xFF0F5B80),
    onPrimary = Color.White,
    primaryContainer = Color(0xFFD2ECF9),
    onPrimaryContainer = Color(0xFF001F2F),
    secondary = Color(0xFF2F6177),
    onSecondary = Color.White,
    secondaryContainer = Color(0xFFE2EDF3),
    onSecondaryContainer = Color(0xFF0C202B),
    background = Color(0xFFF2F6F9),
    onBackground = Color(0xFF0C202B),
    surface = Color(0xFFFFFFFF),
    onSurface = Color(0xFF0C202B),
    surfaceVariant = Color(0xFFDFE8ED),
    onSurfaceVariant = Color(0xFF586F7C),
    outline = Color(0xFFD0DCE3)
)

private val TramNightDark = darkColorScheme(
    primary = Color(0xFF6CD2FF),
    onPrimary = Color(0xFF003549),
    primaryContainer = Color(0xFF004C6A),
    onPrimaryContainer = Color(0xFFBCE9FF),
    secondary = Color(0xFF96CCEB),
    onSecondary = Color(0xFF00344A),
    secondaryContainer = Color(0xFF1D2B33),
    onSecondaryContainer = Color(0xFFE2ECF2),
    background = Color(0xFF0B1317),
    onBackground = Color(0xFFE2ECF2),
    surface = Color(0xFF131E24),
    onSurface = Color(0xFFE2ECF2),
    surfaceVariant = Color(0xFF1E2B33),
    onSurfaceVariant = Color(0xFF889DAA),
    outline = Color(0xFF2D3E47)
)

// === 4. RUBY PALETTE ===
private val RubyLight = lightColorScheme(
    primary = Color(0xFF8B1E3F),
    onPrimary = Color.White,
    primaryContainer = Color(0xFFFCE4EC),
    onPrimaryContainer = Color(0xFF3B0014),
    secondary = Color(0xFF753344),
    onSecondary = Color.White,
    secondaryContainer = Color(0xFFF7E7EB),
    onSecondaryContainer = Color(0xFF2E0513),
    background = Color(0xFFFAF4F5),
    onBackground = Color(0xFF2E0513),
    surface = Color(0xFFFFFFFF),
    onSurface = Color(0xFF2E0513),
    surfaceVariant = Color(0xFFF1DEE2),
    onSurfaceVariant = Color(0xFF7A525E),
    outline = Color(0xFFE3D0D5)
)

private val RubyDark = darkColorScheme(
    primary = Color(0xFFFFB1C8),
    onPrimary = Color(0xFF540021),
    primaryContainer = Color(0xFF6F032F),
    onPrimaryContainer = Color(0xFFFFD9E2),
    secondary = Color(0xFFE5B9C4),
    onSecondary = Color(0xFF431F2A),
    secondaryContainer = Color(0xFF331B22),
    onSecondaryContainer = Color(0xFFFCEBED),
    background = Color(0xFF1A0C11),
    onBackground = Color(0xFFFCEBED),
    surface = Color(0xFF24141B),
    onSurface = Color(0xFFFCEBED),
    surfaceVariant = Color(0xFF331D25),
    onSurfaceVariant = Color(0xFFAB8E97),
    outline = Color(0xFF472D35)
)

/**
 * Hlavní téma aplikace Stíhám to? (PMDP Plzeň)
 * Podporuje Dark Mode (Systém / Světlý / Tmavý) a volitelné barevné styly včetně Material 3 Dynamic Color.
 */
@Composable
fun StihamToTheme(
    themeMode: AppThemeMode = AppThemeMode.SYSTEM,
    colorTheme: AppColorTheme = AppColorTheme.BOTANICAL,
    content: @Composable () -> Unit
) {
    val context = LocalContext.current
    val systemDark = isSystemInDarkTheme()
    val isDark = when (themeMode) {
        AppThemeMode.SYSTEM -> systemDark
        AppThemeMode.LIGHT -> false
        AppThemeMode.DARK -> true
    }

    val colorScheme = when (colorTheme) {
        AppColorTheme.DYNAMIC -> {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                if (isDark) dynamicDarkColorScheme(context) else dynamicLightColorScheme(context)
            } else {
                if (isDark) BotanicalDark else BotanicalLight
            }
        }
        AppColorTheme.BOTANICAL -> if (isDark) BotanicalDark else BotanicalLight
        AppColorTheme.PILSNER -> if (isDark) PilsnerDark else PilsnerLight
        AppColorTheme.TRAM_NIGHT -> if (isDark) TramNightDark else TramNightLight
        AppColorTheme.RUBY -> if (isDark) RubyDark else RubyLight
    }

    MaterialTheme(
        colorScheme = colorScheme,
        typography = Typography(),
        content = content
    )
}
