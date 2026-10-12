package com.swiftsstp.client.ui

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Path
import android.graphics.PorterDuff
import android.graphics.PorterDuffXfermode
import android.graphics.Shader
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
import kotlin.math.max

/** Supersampling factor: render at 3× the layout size so glyph edges stay crisp. */
private const val SCALE = 3

/**
 * Hard-edged neutral stops — white → light grey → mid grey → back. Adjacent
 * equal stops make flat facets with hard seams instead of a smooth two-colour
 * wash, which is the whole point of the wordmark: hue is gone, but the cut
 * planes remain, so the letters read as lit white rather than flat fill.
 */
private val FacetColors = intArrayOf(
    0xFFFFFFFF.toInt(),
    0xFFFFFFFF.toInt(),
    0xFFE6E6E6.toInt(),
    0xFFE6E6E6.toInt(),
    0xFFCFCFCF.toInt(),
    0xFFF4F4F4.toInt(),
    0xFFFFFFFF.toInt(),
)
private val FacetStops = floatArrayOf(0f, 0.18f, 0.30f, 0.52f, 0.66f, 0.80f, 1f)

/**
 * Hero brand wordmark — uppercase letters filled with a faceted white
 * texture plus translucent shards, the app's one piece of ornament.
 *
 * Rendered once into an offscreen bitmap (a DST_IN punch keeps the texture
 * inside the glyphs) rather than through Compose text drawing: hard gradient
 * stops and xfermodes anti-alias reliably on a software canvas, and the cost
 * is paid once per (text, font size) — the result is a plain [Image].
 */
@Composable
fun BrandWordmark(
    text: String = "SWIFTSSTP",
    modifier: Modifier = Modifier,
) {
    val density = LocalDensity.current.density
    val fontPx = with(LocalDensity.current) { 46.sp.toPx() }
    val bitmap = remember(text, fontPx) { renderWordmark(text, fontPx) }
    // The bitmap is SCALE× oversampled; convert back to dp so the layout box
    // matches exactly what gets drawn (no stretch, no clipping).
    Image(
        bitmap = bitmap.asImageBitmap(),
        contentDescription = null,
        modifier = modifier
            .width((bitmap.width / SCALE / density).dp)
            .height((bitmap.height / SCALE / density).dp),
    )
}

/** Texture + glyph punch into one ARGB bitmap; runs off the UI thread's path but is memoised. */
private fun renderWordmark(text: String, fontPx: Float): Bitmap {
    val typeface = Typeface.create("sans-serif-black", Typeface.NORMAL)
    // ~1.5sp tracking at a 46sp font, expressed in em as Paint wants it.
    val tracking = 1.5f / 46f
    val metrics = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        this.typeface = typeface
        textSize = fontPx
        letterSpacing = tracking
    }
    val pad = fontPx * 0.25f
    val textH = metrics.fontMetrics.let { it.descent - it.ascent }
    val w = (metrics.measureText(text) + pad * 2f).toInt().coerceAtLeast(1)
    val h = (textH + pad * 2f).toInt().coerceAtLeast(1)
    val bitmap = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
    val canvas = Canvas(bitmap)

    // 1. Faceted field — diagonal hard-stop gradient across the whole canvas.
    val field = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        shader = LinearGradient(
            0f, 0f, w.toFloat(), h.toFloat(),
            FacetColors, FacetStops, Shader.TileMode.CLAMP,
        )
    }
    canvas.drawRect(0f, 0f, w.toFloat(), h.toFloat(), field)

    // 2. Abstract geometry over the field: translucent shards and streaks,
    // alternating light and dark so the facets read as cut planes.
    val shard = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }
    val shards = listOf(
        floatArrayOf(0.04f, 0.10f, 0.44f, 0.02f, 0.20f, 0.72f),
        floatArrayOf(0.50f, 0.00f, 0.97f, 0.30f, 0.58f, 0.90f),
        floatArrayOf(0.28f, 0.52f, 0.86f, 0.66f, 0.16f, 0.99f),
        floatArrayOf(0.70f, 0.08f, 1.00f, 0.58f, 0.42f, 0.36f),
        floatArrayOf(0.08f, 0.58f, 0.52f, 1.00f, 0.00f, 0.94f),
    )
    shards.forEachIndexed { i, s ->
        shard.color = if (i % 2 == 0) Color.argb(60, 255, 255, 255) else Color.argb(70, 0, 0, 0)
        canvas.drawPath(
            Path().apply {
                moveTo(s[0] * w, s[1] * h)
                lineTo(s[2] * w, s[3] * h)
                lineTo(s[4] * w, s[5] * h)
                close()
            },
            shard,
        )
    }
    val streak = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = max(2f, fontPx * 0.018f)
    }
    for (i in 0..9) {
        val y = h * (i / 10f) - h * 0.12f
        streak.color = if (i % 3 == 0) Color.argb(72, 255, 255, 255) else Color.argb(52, 0, 0, 0)
        canvas.drawLine(-w * 0.08f, y, w * 1.08f, y - w * 0.26f, streak)
    }

    // 3. Punch the glyphs out with DST_IN: only the texture inside the
    // letters survives; everything outside them becomes transparent.
    val punch = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        this.typeface = typeface
        textSize = fontPx
        letterSpacing = tracking
        color = Color.WHITE
        xfermode = PorterDuffXfermode(PorterDuff.Mode.DST_IN)
    }
    canvas.drawText(text, pad, pad - punch.fontMetrics.ascent, punch)
    return bitmap
}
