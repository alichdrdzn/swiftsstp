package com.swiftsstp.client.ui

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Typeface
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/**
 * Supersampling factor: the bitmap is drawn at [SS]x the on-screen size and
 * laid out at 1/[SS] of its pixel size, so the round joins get clean
 * anti-aliased edges.
 *
 * It has to be applied on BOTH ends — render size times [SS], display size
 * divided by [SS]. Applying it to the display size alone is how the
 * wordmark came out three times too small: the bitmap was rendered at 1x
 * and then laid out at a third of it.
 */
private const val SS = 2

/** Hero size in type units; converted to px at the display density. */
private const val FONT_SP = 46f

/**
 * Extra weight around each glyph, in em. The brand mark's S is far fatter
 * than a stock Black weight with softer corners, so the letters take a
 * round-joined stroke to reach the same chunky, rounded read as the logo.
 */
private const val STROKE_EM = 0.055f

/** Breathing room around the glyphs, in em. */
private const val PAD_EM = 0.2f

/**
 * Hero brand wordmark — uppercase letters in flat white, thickened with a
 * round-joined stroke to match the app logo's heavy, rounded, monochrome
 * letterforms.
 *
 * Drawn once into an offscreen bitmap because Compose text cannot round
 * its joins; the cost is paid once per (text, font size) and the result is
 * a plain [Image]. Deliberately textureless: the icon is flat white on
 * black, and the hero has to read as the same mark family.
 */
@Composable
fun BrandWordmark(
    text: String = "SWIFTSSTP",
    modifier: Modifier = Modifier,
) {
    val density = LocalDensity.current.density
    val layoutPx = with(LocalDensity.current) { FONT_SP.sp.toPx() }
    val bitmap = remember(text, layoutPx) { renderWordmark(text, layoutPx * SS) }
    Image(
        bitmap = bitmap.asImageBitmap(),
        contentDescription = null,
        modifier = modifier
            .width((bitmap.width.toFloat() / SS / density).dp)
            .height((bitmap.height.toFloat() / SS / density).dp),
    )
}

/** Flat-white glyphs with a round-joined stroke, into one ARGB bitmap. */
private fun renderWordmark(text: String, fontPx: Float): Bitmap {
    val tracking = 1.5f / FONT_SP // ~1.5sp of tracking, expressed in em
    val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        typeface = Typeface.create("sans-serif-black", Typeface.NORMAL)
        textSize = fontPx
        letterSpacing = tracking
        color = Color.WHITE
        style = Paint.Style.FILL_AND_STROKE
        strokeJoin = Paint.Join.ROUND
        strokeCap = Paint.Cap.ROUND
        strokeWidth = fontPx * STROKE_EM
    }
    val pad = fontPx * PAD_EM
    val metrics = paint.fontMetrics
    val textH = metrics.descent - metrics.ascent
    // The stroke grows the glyph by half its width on every side, so the
    // canvas reserves that much on top of the advance and the font box.
    val grow = paint.strokeWidth
    val w = (paint.measureText(text) + grow + pad * 2f).toInt().coerceAtLeast(1)
    val h = (textH + grow + pad * 2f).toInt().coerceAtLeast(1)
    val bitmap = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
    Canvas(bitmap).drawText(
        text,
        pad + grow / 2f,
        pad - metrics.ascent + grow / 2f,
        paint,
    )
    return bitmap
}
