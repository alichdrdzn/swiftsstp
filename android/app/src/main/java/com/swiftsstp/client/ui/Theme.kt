package com.swiftsstp.client.ui

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp

// Palette lifted from the Windows client (windows/frontend/dist/app.css
// `:root`): pure-black canvas, white/neutral accents for anything that
// used to be blue → violet, mint for the connected state, orange for the
// Home hero family. Deliberately dark-only — a VPN console reads better
// without a white flash when the tunnel drops at night, and true black
// keeps the shell seamless with the status bar.
val Ink = Color(0xFF000000) // --bg
val Surface1 = Color(0xFF131A26) // --card
val Surface2 = Color(0xFF172031) // --card-2
val Surface3 = Color(0xFF1C2537) // --card-3
val Hairline = Color(0xFF232C3D) // --border
val Hairline2 = Color(0xFF2A3446) // --border-2 (ghost buttons, inputs)
val TextPrimary = Color(0xFFE7EDF8) // --text
val TextMuted = Color(0xFF8B96AB) // --muted
val Mint = Color(0xFF2FD08C) // --green
val Amber = Color(0xFFFFB454) // --amber
val Rose = Color(0xFFFF6B6B) // --red
/** Home screen brand hue — profile badge, wordmark family; matches the Windows shell. */
val Orange = Color(0xFFFF7A18)

/** Gradient behind `.log` / inset wells — Windows `#0a0e15`. */
val WellBackground = Color(0xFF0A0E15)

/** Field chrome — Windows `.input` background (`--bg-2`). */
val FieldBackground = Color(0xFF0E1420)

private val SwiftSSTPColours = darkColorScheme(
    // White primary flips every Material 3 control (Switch, checkbox,
    // text-field focus ring, selection) to white-on-black, matching the
    // connect toggle's knob.
    primary = Color.White,
    onPrimary = Color.Black,
    primaryContainer = Color(0xFF1E1E22),
    onPrimaryContainer = Color(0xFFF2F2F5),
    secondary = Mint,
    onSecondary = Color(0xFF00201A),
    secondaryContainer = Color(0xFF14352C),
    onSecondaryContainer = Color(0xFFCFF5E7),
    tertiary = Amber,
    onTertiary = Color(0xFF2B1A00),
    tertiaryContainer = Color(0xFF463100),
    onTertiaryContainer = Color(0xFFFFDEB5),
    // [Ink] is pure black: the redesigned Home reads on true black, and
    // OLED-black also stops the app glowing next to the status bar.
    background = Ink,
    onBackground = TextPrimary,
    surface = Surface1,
    onSurface = TextPrimary,
    surfaceVariant = Surface2,
    onSurfaceVariant = TextMuted,
    surfaceContainer = Surface2,
    surfaceContainerHigh = Surface3,
    surfaceContainerHighest = WellBackground,
    outline = Hairline,
    outlineVariant = Hairline2,
    error = Rose,
    onError = Color(0xFF3A0004),
    errorContainer = Color(0xFF4A1B1F),
    onErrorContainer = Color(0xFFFFD9DA),
)

private val SwiftSSTPTypography = Typography(
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

/** App theme: the Windows palette, dark-only. */
@Composable
fun SwiftSSTPTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = SwiftSSTPColours,
        typography = SwiftSSTPTypography,
        content = content,
    )
}
