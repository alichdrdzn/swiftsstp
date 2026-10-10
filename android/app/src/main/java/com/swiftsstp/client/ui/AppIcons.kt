package com.swiftsstp.client.ui

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.PathFillType
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.PathBuilder
import androidx.compose.ui.graphics.vector.path
import androidx.compose.ui.unit.dp

/**
 * The handful of glyphs the app draws by hand.
 *
 * The design only needs six icons, so they live here as tiny inline
 * [ImageVector]s instead of pulling in `material-icons-extended` (thousands of
 * unused icons, several MB of dex). Material's core set covers the rest
 * (ArrowBack, ArrowDropDown, Home, Person, …).
 */
object AppIcons {

    /**
     * Power symbol — the connect button glyph: a broken ring with a
     * vertical stem. Stroked, round-capped, tinted by the caller.
     */
    val Power: ImageVector by lazy { icon("Power") { power() } }

    /** Folder — stands in for the editor's "pick a CA file" affordance. */
    val Folder: ImageVector by lazy { icon("Folder") { folder() } }

    /** Eye — "show password". */
    val Eye: ImageVector by lazy { icon("Eye") { eye(slash = false) } }

    /** Eye with a slash — "hide password". */
    val EyeOff: ImageVector by lazy { icon("EyeOff") { eye(slash = true) } }

    /** Three bars — the Stats tab. */
    val BarChart: ImageVector by lazy { icon("BarChart") { barChart() } }

    /** Outlined console with a prompt — the Log tab. */
    val Terminal: ImageVector by lazy { icon("Terminal") { terminal() } }

    private fun icon(name: String, block: ImageVector.Builder.() -> Unit): ImageVector =
        ImageVector.Builder(
            name = name,
            defaultWidth = 24.dp,
            defaultHeight = 24.dp,
            viewportWidth = 24f,
            viewportHeight = 24f,
        ).apply(block).build()

    /** Stroked path helper; the whole set is drawn at a 24×24 viewport. */
    private fun ImageVector.Builder.stroke(
        width: Float = 2f,
        pathFillType: PathFillType = PathFillType.NonZero,
        block: PathBuilder.() -> Unit,
    ) = path(
        fill = null,
        stroke = SolidColor(Color.Black),
        strokeLineWidth = width,
        strokeLineCap = StrokeCap.Round,
        strokeLineJoin = StrokeJoin.Round,
        pathFillType = pathFillType,
        pathBuilder = block,
    )

    private fun ImageVector.Builder.fill(block: PathBuilder.() -> Unit) = path(
        fill = SolidColor(Color.Black),
        pathBuilder = block,
    )

    // ---- geometry -----------------------------------------------------

    private fun ImageVector.Builder.power() = stroke {
        // Stem, then the ring: a 290° arc of r=7 around (12,13) with a gap at
        // the top; the arc is cubic-bezier'd so no flag conventions are needed.
        moveTo(12f, 2f)
        lineTo(12f, 11.5f)
        moveTo(7.98f, 7.27f)
        curveTo(5.48f, 9.02f, 4.41f, 12.19f, 5.32f, 15.1f)
        curveTo(6.24f, 18.02f, 8.94f, 20f, 12f, 20f)
        curveTo(15.06f, 20f, 17.76f, 18.02f, 18.68f, 15.1f)
        curveTo(19.59f, 12.19f, 18.52f, 9.02f, 16.02f, 7.27f)
    }

    private fun ImageVector.Builder.folder() = fill {
        // Material's folder silhouette.
        moveTo(10f, 4f)
        lineTo(4f, 4f)
        curveTo(2.9f, 4f, 2f, 4.9f, 2f, 6f)
        lineTo(2f, 18f)
        curveTo(2f, 19.1f, 2.9f, 20f, 4f, 20f)
        lineTo(20f, 20f)
        curveTo(21.1f, 20f, 22f, 19.1f, 22f, 18f)
        lineTo(22f, 8f)
        curveTo(22f, 6.9f, 21.1f, 6f, 20f, 6f)
        lineTo(11f, 6f)
        lineTo(10f, 4f)
        close()
    }

    private fun ImageVector.Builder.eye(slash: Boolean) {
        // Almond outline (stroked)…
        stroke {
            moveTo(2.5f, 12f)
            curveTo(6.2f, 5.8f, 17.8f, 5.8f, 21.5f, 12f)
            curveTo(17.8f, 18.2f, 6.2f, 18.2f, 2.5f, 12f)
            close()
        }
        // …pupil…
        stroke {
            moveTo(12f, 14.6f)
            curveTo(13.44f, 14.6f, 14.6f, 13.44f, 14.6f, 12f)
            curveTo(14.6f, 10.56f, 13.44f, 9.4f, 12f, 9.4f)
            curveTo(10.56f, 9.4f, 9.4f, 10.56f, 9.4f, 12f)
            curveTo(9.4f, 13.44f, 10.56f, 14.6f, 12f, 14.6f)
            close()
        }
        // …and, when hiding, the slash through it.
        if (slash) {
            stroke { moveTo(4.5f, 4.2f); lineTo(19.5f, 19.8f) }
        }
    }

    private fun ImageVector.Builder.barChart() {
        fill {
            moveTo(4f, 12.5f)
            lineTo(8f, 12.5f)
            lineTo(8f, 20f)
            lineTo(4f, 20f)
            close()
            moveTo(10f, 8f)
            lineTo(14f, 8f)
            lineTo(14f, 20f)
            lineTo(10f, 20f)
            close()
            moveTo(16f, 4f)
            lineTo(20f, 4f)
            lineTo(20f, 20f)
            lineTo(16f, 20f)
            close()
        }
    }

    private fun ImageVector.Builder.terminal() {
        // Rounded console frame.
        stroke {
            moveTo(5.5f, 4.5f)
            lineTo(18.5f, 4.5f)
            curveTo(19.88f, 4.5f, 21f, 5.62f, 21f, 7f)
            lineTo(21f, 17f)
            curveTo(21f, 18.38f, 19.88f, 19.5f, 18.5f, 19.5f)
            lineTo(5.5f, 19.5f)
            curveTo(4.12f, 19.5f, 3f, 18.38f, 3f, 17f)
            lineTo(3f, 7f)
            curveTo(3f, 5.62f, 4.12f, 4.5f, 5.5f, 4.5f)
            close()
        }
        // Prompt chevron and the input line.
        stroke { moveTo(8f, 9.5f); lineTo(10.5f, 12f); lineTo(8f, 14.5f) }
        stroke { moveTo(13f, 14.5f); lineTo(16.5f, 14.5f) }
    }
}
