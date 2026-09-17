package eu.rybnik.events.ui.theme

import android.app.Activity
import android.os.Build
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.SideEffect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp
import androidx.core.view.WindowCompat

// Rybnik palette — deep petrol (water / lakes) + warm amber accent.
private val Petrol900 = Color(0xFF063540)
private val Petrol700 = Color(0xFF0B6E7F)
private val Petrol500 = Color(0xFF14A2B8)
private val Petrol100 = Color(0xFFCFEBF0)
private val Amber500 = Color(0xFFE4A11B)
private val Amber100 = Color(0xFFFDEBC7)

private val LightScheme = lightColorScheme(
    primary = Petrol700,
    onPrimary = Color.White,
    primaryContainer = Petrol100,
    onPrimaryContainer = Petrol900,
    secondary = Amber500,
    onSecondary = Color.White,
    secondaryContainer = Amber100,
    onSecondaryContainer = Color(0xFF5A3E00),
    background = Color(0xFFFAFAFA),
    onBackground = Color(0xFF1A1C1E),
    surface = Color.White,
    onSurface = Color(0xFF1A1C1E),
    surfaceVariant = Color(0xFFEEF1F2),
    onSurfaceVariant = Color(0xFF505458),
)

private val DarkScheme = darkColorScheme(
    primary = Petrol500,
    onPrimary = Petrol900,
    primaryContainer = Color(0xFF08505E),
    onPrimaryContainer = Petrol100,
    secondary = Amber500,
    onSecondary = Color(0xFF3A2500),
    secondaryContainer = Color(0xFF5A3E00),
    onSecondaryContainer = Amber100,
    background = Color(0xFF0F1315),
    onBackground = Color(0xFFE1E3E5),
    surface = Color(0xFF161B1D),
    onSurface = Color(0xFFE1E3E5),
    surfaceVariant = Color(0xFF232A2D),
    onSurfaceVariant = Color(0xFFC0C4C7),
    // Alert cards (city notices, smog warnings) live on the error container, so it has
    // to stay legible in dark mode instead of falling back to a bright default.
    error = Color(0xFFFF8A80),
    onError = Color(0xFF4A0A05),
    errorContainer = Color(0xFF5C1710),
    onErrorContainer = Color(0xFFFFDAD4),
    outline = Color(0xFF6B7276),
    outlineVariant = Color(0xFF3A4246),
)

private val RybnikTypography = Typography(
    headlineMedium = TextStyle(fontSize = 24.sp, lineHeight = 30.sp, fontWeight = FontWeight.SemiBold),
    headlineSmall = TextStyle(fontSize = 22.sp, lineHeight = 28.sp, fontWeight = FontWeight.SemiBold),
    titleLarge = TextStyle(fontSize = 20.sp, lineHeight = 26.sp, fontWeight = FontWeight.SemiBold),
    titleMedium = TextStyle(fontSize = 16.sp, lineHeight = 22.sp, fontWeight = FontWeight.SemiBold),
    bodyLarge = TextStyle(fontSize = 16.sp, lineHeight = 22.sp, fontWeight = FontWeight.Normal),
    bodyMedium = TextStyle(fontSize = 14.sp, lineHeight = 20.sp, fontWeight = FontWeight.Normal),
    bodySmall = TextStyle(fontSize = 12.sp, lineHeight = 16.sp, fontWeight = FontWeight.Normal),
    labelLarge = TextStyle(fontSize = 14.sp, lineHeight = 18.sp, fontWeight = FontWeight.Medium),
    labelMedium = TextStyle(fontSize = 12.sp, lineHeight = 16.sp, fontWeight = FontWeight.Medium),
    labelSmall = TextStyle(fontSize = 11.sp, lineHeight = 14.sp, fontWeight = FontWeight.Medium),
)

@Composable
fun RybnikTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    // Deliberately off so the app keeps its brand identity across devices.
    dynamicColor: Boolean = false,
    content: @Composable () -> Unit,
) {
    val scheme = when {
        dynamicColor && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S -> {
            val ctx = LocalContext.current
            if (darkTheme) dynamicDarkColorScheme(ctx) else dynamicLightColorScheme(ctx)
        }
        darkTheme -> DarkScheme
        else -> LightScheme
    }
    val view = LocalView.current
    if (!view.isInEditMode) {
        SideEffect {
            val window = (view.context as Activity).window
            WindowCompat.getInsetsController(window, view).isAppearanceLightStatusBars = !darkTheme
        }
    }
    MaterialTheme(colorScheme = scheme, typography = RybnikTypography, content = content)
}
