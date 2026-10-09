package com.slipstream.client.ui

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp

// Palette: near-black ink, cool blue accent, mint for the connected state.
// Deliberately dark-only — a VPN console reads better without a white flash
// when the tunnel drops at night.
val Ink = Color(0xFF0A0D13)
val Surface1 = Color(0xFF11151D)
val Surface2 = Color(0xFF171D27)
val Surface3 = Color(0xFF1D2531)
val Hairline = Color(0xFF2A3242)
val TextPrimary = Color(0xFFE6E9F2)
val TextMuted = Color(0xFF98A2B8)
val AccentBlue = Color(0xFF7FB0FF)
val Mint = Color(0xFF7FDCC0)
val Amber = Color(0xFFFFC46B)
val Rose = Color(0xFFFF8080)

private val SlipstreamColors = darkColorScheme(
    primary = AccentBlue,
    onPrimary = Color(0xFF06101F),
    primaryContainer = Color(0xFF1C2B45),
    onPrimaryContainer = Color(0xFFD8E6FF),
    secondary = Mint,
    onSecondary = Color(0xFF00201A),
    secondaryContainer = Color(0xFF14352C),
    onSecondaryContainer = Color(0xFFCFF5E7),
    tertiary = Amber,
    onTertiary = Color(0xFF2B1A00),
    tertiaryContainer = Color(0xFF463100),
    onTertiaryContainer = Color(0xFFFFDEB5),
    background = Ink,
    onBackground = TextPrimary,
    surface = Surface1,
    onSurface = TextPrimary,
    surfaceVariant = Surface2,
    onSurfaceVariant = TextMuted,
    surfaceContainer = Surface2,
    surfaceContainerHigh = Surface3,
    surfaceContainerHighest = Color(0xFF232B38),
    outline = Hairline,
    outlineVariant = Color(0xFF1F2734),
    error = Rose,
    onError = Color(0xFF3A0004),
    errorContainer = Color(0xFF4A1B1F),
    onErrorContainer = Color(0xFFFFD9DA),
)

private val SlipstreamTypography = Typography(
    displaySmall = TextStyle(
        fontFamily = FontFamily.SansSerif,
        fontWeight = FontWeight.SemiBold,
        fontSize = 34.sp,
        letterSpacing = (-0.5).sp,
    ),
    headlineSmall = TextStyle(
        fontFamily = FontFamily.SansSerif,
        fontWeight = FontWeight.SemiBold,
        fontSize = 22.sp,
        letterSpacing = (-0.2).sp,
    ),
    titleLarge = TextStyle(
        fontFamily = FontFamily.SansSerif,
        fontWeight = FontWeight.SemiBold,
        fontSize = 19.sp,
        letterSpacing = (-0.2).sp,
    ),
    titleMedium = TextStyle(
        fontFamily = FontFamily.SansSerif,
        fontWeight = FontWeight.SemiBold,
        fontSize = 16.sp,
    ),
    titleSmall = TextStyle(
        fontFamily = FontFamily.SansSerif,
        fontWeight = FontWeight.Medium,
        fontSize = 14.sp,
    ),
    bodyLarge = TextStyle(
        fontFamily = FontFamily.SansSerif,
        fontWeight = FontWeight.Normal,
        fontSize = 15.sp,
        lineHeight = 21.sp,
    ),
    bodyMedium = TextStyle(
        fontFamily = FontFamily.SansSerif,
        fontWeight = FontWeight.Normal,
        fontSize = 14.sp,
        lineHeight = 20.sp,
    ),
    bodySmall = TextStyle(
        fontFamily = FontFamily.SansSerif,
        fontWeight = FontWeight.Normal,
        fontSize = 12.5.sp,
        lineHeight = 17.sp,
    ),
    labelLarge = TextStyle(
        fontFamily = FontFamily.SansSerif,
        fontWeight = FontWeight.SemiBold,
        fontSize = 14.sp,
        letterSpacing = 0.2.sp,
    ),
    labelMedium = TextStyle(
        fontFamily = FontFamily.SansSerif,
        fontWeight = FontWeight.Medium,
        fontSize = 12.sp,
        letterSpacing = 0.4.sp,
    ),
    labelSmall = TextStyle(
        fontFamily = FontFamily.Monospace,
        fontWeight = FontWeight.Medium,
        fontSize = 11.sp,
        letterSpacing = 0.3.sp,
    ),
)

@Composable
fun SlipstreamTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = SlipstreamColors,
        typography = SlipstreamTypography,
        content = content,
    )
}
