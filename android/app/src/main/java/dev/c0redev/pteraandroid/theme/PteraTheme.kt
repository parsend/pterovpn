package dev.c0redev.pteraandroid.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp

private val PteraDarkColors = darkColorScheme(
    primary = Color(0xFF7C9EFF),
    onPrimary = Color(0xFF101633),
    primaryContainer = Color(0xFF1E2A5A),
    onPrimaryContainer = Color(0xFFDDE3FF),
    secondary = Color(0xFF8CD6C5),
    onSecondary = Color(0xFF003731),
    background = Color(0xFF0D1016),
    onBackground = Color(0xFFE2E6EF),
    surface = Color(0xFF131824),
    onSurface = Color(0xFFE2E6EF),
    surfaceVariant = Color(0xFF1D2432),
    onSurfaceVariant = Color(0xFFB9C3D9),
    surfaceDim = Color(0xFF0A0D12),
    surfaceBright = Color(0xFF1A2030),
    surfaceContainerLowest = Color(0xFF0E1218),
    surfaceContainerLow = Color(0xFF151A22),
    surfaceContainer = Color(0xFF1A202A),
    surfaceContainerHigh = Color(0xFF1F2633),
    surfaceContainerHighest = Color(0xFF252D3C),
    error = Color(0xFFFF7C8F),
    onError = Color(0xFF410002),
    errorContainer = Color(0xFF93000A),
    onErrorContainer = Color(0xFFFFDAD6),
    outline = Color(0xFF3C485F),
    outlineVariant = Color(0xFF2C3547),
)

private val Mono = FontFamily.Monospace

private val PteraTypography = Typography(
    displayLarge = TextStyle(fontFamily = FontFamily.SansSerif, fontWeight = FontWeight.Normal, fontSize = 57.sp, lineHeight = 64.sp),
    displayMedium = TextStyle(fontFamily = FontFamily.SansSerif, fontWeight = FontWeight.Normal, fontSize = 45.sp, lineHeight = 52.sp),
    displaySmall = TextStyle(fontFamily = FontFamily.SansSerif, fontWeight = FontWeight.Normal, fontSize = 36.sp, lineHeight = 44.sp),
    headlineLarge = TextStyle(fontFamily = FontFamily.SansSerif, fontWeight = FontWeight.SemiBold, fontSize = 32.sp, lineHeight = 40.sp),
    headlineMedium = TextStyle(fontFamily = FontFamily.SansSerif, fontWeight = FontWeight.SemiBold, fontSize = 28.sp, lineHeight = 36.sp),
    headlineSmall = TextStyle(fontFamily = FontFamily.SansSerif, fontWeight = FontWeight.SemiBold, fontSize = 24.sp, lineHeight = 32.sp),
    titleLarge = TextStyle(fontFamily = FontFamily.SansSerif, fontWeight = FontWeight.SemiBold, fontSize = 22.sp, lineHeight = 28.sp),
    titleMedium = TextStyle(fontFamily = FontFamily.SansSerif, fontWeight = FontWeight.Medium, fontSize = 16.sp, lineHeight = 24.sp),
    titleSmall = TextStyle(fontFamily = FontFamily.SansSerif, fontWeight = FontWeight.Medium, fontSize = 14.sp, lineHeight = 20.sp),
    bodyLarge = TextStyle(fontFamily = FontFamily.SansSerif, fontWeight = FontWeight.Normal, fontSize = 16.sp, lineHeight = 24.sp),
    bodyMedium = TextStyle(fontFamily = FontFamily.SansSerif, fontWeight = FontWeight.Normal, fontSize = 14.sp, lineHeight = 20.sp),
    bodySmall = TextStyle(fontFamily = FontFamily.SansSerif, fontWeight = FontWeight.Normal, fontSize = 12.sp, lineHeight = 16.sp),
    labelLarge = TextStyle(fontFamily = FontFamily.SansSerif, fontWeight = FontWeight.Medium, fontSize = 14.sp, lineHeight = 20.sp),
    labelMedium = TextStyle(fontFamily = Mono, fontWeight = FontWeight.Normal, fontSize = 12.sp, lineHeight = 16.sp),
    labelSmall = TextStyle(fontFamily = Mono, fontWeight = FontWeight.Normal, fontSize = 11.sp, lineHeight = 14.sp),
)

@Composable
fun PteraTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = PteraDarkColors,
        typography = PteraTypography,
        content = content,
    )
}
