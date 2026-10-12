package com.swiftsstp.client.ui

import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Image
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.Person
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.NavigationBarItemDefaults
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.swiftsstp.client.R
import com.swiftsstp.client.VpnController
import com.swiftsstp.client.model.Profile

/** Human byte counts: "812 B", "1.2 MB", "3.04 GB". */
fun formatBytes(v: Long): String = when {
    v >= 1073741824L -> "%.2f GB".format(v / 1073741824.0)
    v >= 1048576L -> "%.1f MB".format(v / 1048576.0)
    v >= 1024L -> "%.0f KB".format(v / 1024.0)
    else -> "$v B"
}

fun formatRate(v: Long): String = when {
    v >= 1048576L -> "%.1f MB/s".format(v / 1048576.0)
    v >= 1024L -> "%.0f KB/s".format(v / 1024.0)
    else -> "$v B/s"
}

/** Which bottom-bar tab is on screen; the four root tabs of the app. */
enum class NavTab { HOME, PROFILES, STATS, LOG }

/**
 * Slim shared top bar — brand mark, "SwiftSSTP"
 * wordmark with the "SSTP client" caption, and the status `.pill` on the
 * right. Navigation moved to the bottom bar, so this is one row only.
 *
 * [showBrand] = false (Home) draws nothing visible but keeps the slot and
 * its status-bar inset: Scaffold derives the content inset from the topBar's
 * height, so an inset-only zero-height box preserves today's layout while
 * letting the hero wordmark own the top of the screen.
 */
@Composable
fun SwiftSSTPTopBar(
    state: VpnController.State,
    modifier: Modifier = Modifier,
    showBrand: Boolean = true,
) {
    if (!showBrand) {
        Box(modifier.fillMaxWidth().statusBarsPadding())
        return
    }
    Column(modifier.fillMaxWidth().background(FieldBackground)) {
        // Edge-to-edge: Scaffold does not pad custom topBar composables, so
        // the brand row must apply the status-bar inset itself the way
        // Material3 TopAppBar does — otherwise it slides under the system
        // status bar.
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .statusBarsPadding()
                .padding(start = 16.dp, end = 16.dp, top = 12.dp, bottom = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Image(
                painter = painterResource(R.drawable.ic_brand_mark),
                contentDescription = null,
                modifier = Modifier.size(22.dp),
            )
            Spacer(Modifier.width(9.dp))
            Row(verticalAlignment = Alignment.Bottom) {
                Text(
                    text = "SwiftSSTP",
                    style = TextStyle(
                        fontSize = 17.sp,
                        fontWeight = FontWeight.Bold,
                        letterSpacing = 0.3.sp,
                    ),
                )
                Spacer(Modifier.width(8.dp))
                Text(
                    text = "SSTP client",
                    style = TextStyle(fontSize = 12.sp),
                    color = TextMuted,
                )
            }
            Spacer(Modifier.weight(1f))
            StateChip(state)
        }
        Box(Modifier.fillMaxWidth().height(1.dp).background(Hairline))
    }
}

/**
 * Bottom navigation — icon + label per tab,
 * the active one in white on a faint white pill.
 */
@Composable
fun SwiftSSTPBottomBar(
    current: NavTab,
    onSelect: (NavTab) -> Unit,
    modifier: Modifier = Modifier,
) {
    NavigationBar(
        modifier = modifier.fillMaxWidth(),
        containerColor = FieldBackground,
        tonalElevation = 0.dp,
    ) {
        TabBarItem(NavTab.HOME, "Home", Icons.Default.Home, current, onSelect)
        TabBarItem(NavTab.PROFILES, "Profiles", Icons.Default.Person, current, onSelect)
        TabBarItem(NavTab.STATS, "Stats", AppIcons.BarChart, current, onSelect)
        TabBarItem(NavTab.LOG, "Log", AppIcons.Terminal, current, onSelect)
    }
}

@Composable
private fun RowScope.TabBarItem(
    tab: NavTab,
    label: String,
    icon: ImageVector,
    current: NavTab,
    onSelect: (NavTab) -> Unit,
) {
    val active = current == tab
    NavigationBarItem(
        selected = active,
        onClick = { onSelect(tab) },
        icon = {
            Icon(
                imageVector = icon,
                contentDescription = label,
                modifier = Modifier.size(22.dp),
            )
        },
        label = { Text(label, style = MaterialTheme.typography.labelMedium) },
        colors = NavigationBarItemDefaults.colors(
            selectedIconColor = Color.White,
            selectedTextColor = Color.White,
            unselectedIconColor = TextMuted,
            unselectedTextColor = TextMuted,
            indicatorColor = Color.White.copy(alpha = 0.12f),
        ),
    )
}

/**
 * The one scaffold every root tab shares: slim brand top bar, the bottom
 * navigation, and the tab's own content between them. The profile editor is a
 * pushed screen and keeps its own top bar instead.
 */
@Composable
fun AppScaffold(
    state: VpnController.State,
    current: NavTab,
    onTab: (NavTab) -> Unit,
    showBrand: Boolean = true,
    content: @Composable (PaddingValues) -> Unit,
) {
    Scaffold(
        containerColor = MaterialTheme.colorScheme.background,
        topBar = { SwiftSSTPTopBar(state, showBrand = showBrand) },
        bottomBar = { SwiftSSTPBottomBar(current, onTab) },
        content = content,
    )
}

/** Small uppercase caption above a group of fields — Windows `.field-label`. */
@Composable
fun SectionLabel(text: String) {
    Text(
        text = text.uppercase(),
        style = MaterialTheme.typography.labelMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(start = 2.dp, bottom = 6.dp),
    )
}

/**
 * A titled card that groups related controls — mirrors the Windows `.card`
 * (vertical `--card-2` → `--card` gradient, 1px `--border`, 14px radius,
 * uppercase 13sp `.card h3` header with an optional muted trailing slot).
 */
@Composable
fun SectionCard(
    title: String? = null,
    modifier: Modifier = Modifier,
    trailing: (@Composable () -> Unit)? = null,
    content: @Composable () -> Unit,
) {
    val shape = RoundedCornerShape(14.dp)
    Box(
        modifier = modifier
            .fillMaxWidth()
            .shadow(8.dp, shape, ambientColor = Color.Black, spotColor = Color.Black)
            .background(Brush.verticalGradient(listOf(Surface2, Surface1)), shape)
            .border(1.dp, Hairline, shape),
    ) {
        Column(Modifier.padding(18.dp)) {
            if (title != null) {
                Row(
                    modifier = Modifier.fillMaxWidth().padding(bottom = 14.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        text = title.uppercase(),
                        style = TextStyle(fontSize = 13.sp, fontWeight = FontWeight.Medium),
                        letterSpacing = 0.8.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.weight(1f),
                    )
                    trailing?.invoke()
                }
            }
            content()
        }
    }
}

/** TLS mode tag — mirrors Windows `tlsTag()` (`.tag.ok` / `.tag.warn`). */
@Composable
fun TlsChip(mode: String, modifier: Modifier = Modifier) {
    val (label, color) = when (mode) {
        Profile.TLS_VERIFY -> "TLS verify" to Mint
        Profile.TLS_CUSTOM_CA -> "TLS custom CA" to Mint
        else -> "TLS insecure" to Amber
    }
    InfoChip(label, color, modifier)
}

/**
 * Status pill for the top bar — mirrors Windows `.pill[data-state]`:
 * uppercase, letter-spaced, 999px radius, tinted background *and* border,
 * breathing while the tunnel is being established.
 */
@Composable
fun StateChip(state: VpnController.State, modifier: Modifier = Modifier) {
    val (label, color) = when (state) {
        VpnController.State.CONNECTED -> "CONNECTED" to Mint
        VpnController.State.CONNECTING -> "CONNECTING" to Amber
        VpnController.State.RECONNECTING -> "RECONNECTING" to Amber
        VpnController.State.ERROR -> "ERROR" to Rose
        VpnController.State.DISCONNECTED -> "DISCONNECTED" to TextMuted
    }
    val busy = state == VpnController.State.CONNECTING || state == VpnController.State.RECONNECTING
    val transition = rememberInfiniteTransition(label = "state-pill")
    val pulse by transition.animateFloat(
        initialValue = 1f,
        targetValue = 0.55f,
        animationSpec = infiniteRepeatable(tween(600), RepeatMode.Reverse),
        label = "state-pill-alpha",
    )
    Surface(
        modifier = modifier.alpha(if (busy) pulse else 1f),
        shape = RoundedCornerShape(999.dp),
        color = color.copy(alpha = 0.13f),
        contentColor = color,
        border = BorderStroke(1.dp, color.copy(alpha = 0.5f)),
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.labelSmall.copy(
                fontSize = 12.sp,
                fontWeight = FontWeight.Bold,
                letterSpacing = 0.4.sp,
            ),
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 5.dp),
        )
    }
}

/**
 * Tonal tag for a fact about a profile or the connection — mirrors the
 * Windows `.tag` family (`.tag.warn` amber, `.tag.ok` green).
 */
@Composable
fun InfoChip(text: String, color: Color, modifier: Modifier = Modifier) {
    Surface(
        modifier = modifier,
        shape = RoundedCornerShape(999.dp),
        color = color.copy(alpha = 0.14f),
        contentColor = color,
        border = BorderStroke(1.dp, color.copy(alpha = 0.4f)),
    ) {
        Text(
            text = text,
            style = MaterialTheme.typography.labelSmall.copy(
                fontSize = 11.sp,
                fontWeight = FontWeight.Bold,
                letterSpacing = 0.2.sp,
            ),
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.padding(horizontal = 8.dp, vertical = 3.dp),
        )
    }
}

/**
 * "Label — value" line with a dashed hairline underneath — mirrors the
 * Windows `.stat-row`.
 */
@Composable
fun StatRow(
    label: String,
    value: String,
    modifier: Modifier = Modifier,
    valueColor: Color = TextPrimary,
    mono: Boolean = false,
    showDivider: Boolean = true,
) {
    Column(modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(vertical = 7.dp),
            verticalAlignment = Alignment.Top,
        ) {
            Text(
                text = label,
                style = MaterialTheme.typography.bodyMedium,
                color = TextMuted,
                modifier = Modifier.padding(end = 12.dp),
            )
            Text(
                text = value.ifBlank { "—" },
                style = MaterialTheme.typography.bodyLarge.copy(
                    fontWeight = FontWeight.Bold,
                    fontFamily = if (mono) FontFamily.Monospace else FontFamily.SansSerif,
                ),
                color = valueColor,
                textAlign = TextAlign.End,
                modifier = Modifier.weight(1f),
            )
        }
        if (showDivider) DashedRule()
    }
}

/** The `1px dashed rgba(255,255,255,.06)` separator of `.stat-row`. */
@Composable
private fun DashedRule() {
    Box(
        Modifier
            .fillMaxWidth()
            .height(1.dp)
            .drawBehind {
                drawLine(
                    color = Color.White.copy(alpha = 0.06f),
                    start = Offset(0f, size.height / 2f),
                    end = Offset(size.width, size.height / 2f),
                    strokeWidth = 1.dp.toPx(),
                    pathEffect = PathEffect.dashPathEffect(floatArrayOf(10f, 8f)),
                )
            },
    )
}

/**
 * The one primary action button — a solid white pill with a bold near-black
 * label, mirroring Windows `.btn-primary` (999px radius, neutral black
 * shadow rather than the old accent glow). [busy] reproduces
 * `.btn-primary.busy` (dimmed, still tappable — it is the Stop button), and
 * a pressed press scales and dims slightly so a tap reads without any hue.
 */
@Composable
fun PrimaryButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    busy: Boolean = false,
    enabled: Boolean = true,
    height: Dp = 56.dp,
    fontSize: TextUnit = 16.sp,
) {
    val shape = RoundedCornerShape(999.dp)
    val interactionSource = remember { MutableInteractionSource() }
    val pressed by interactionSource.collectIsPressedAsState()
    val dim = (if (busy) 0.78f else 1f) * (if (pressed) 0.92f else 1f)
    Box(
        modifier = modifier
            .height(height)
            .graphicsLayer {
                scaleX = if (pressed) 0.98f else 1f
                scaleY = if (pressed) 0.98f else 1f
            }
            .shadow(if (enabled) 10.dp else 0.dp, shape, ambientColor = Color.Black, spotColor = Color.Black)
            .clip(shape)
            .background(Color.White.copy(alpha = dim))
            .clickable(
                interactionSource = interactionSource,
                indication = null,
                enabled = enabled,
                onClick = onClick,
            )
            .padding(horizontal = 24.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = text,
            color = Color(0xFF0B0B0B).copy(alpha = if (enabled) 1f else 0.5f),
            style = TextStyle(fontSize = fontSize, fontWeight = FontWeight.ExtraBold, letterSpacing = 0.3.sp),
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

/**
 * Outline button — mirrors Windows `.btn-ghost` (transparent, 1px
 * `--border-2`, muted bold label, neutral white border on interaction).
 */
@Composable
fun GhostButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    contentColor: Color = TextMuted,
    height: Dp = 34.dp,
    fontSize: TextUnit = 13.sp,
) {
    val shape = RoundedCornerShape(999.dp)
    val interactionSource = remember { MutableInteractionSource() }
    val pressed by interactionSource.collectIsPressedAsState()
    val borderColor = if (pressed && enabled) TextPrimary else Hairline2
    val color = when {
        !enabled -> contentColor.copy(alpha = 0.4f)
        pressed -> TextPrimary
        else -> contentColor
    }
    Box(
        modifier = modifier
            .height(height)
            .clip(shape)
            .background(Color.Transparent)
            .border(1.dp, borderColor, shape)
            .clickable(
                interactionSource = interactionSource,
                indication = null,
                enabled = enabled,
                onClick = onClick,
            )
            .padding(horizontal = 12.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = text,
            color = color,
            style = TextStyle(fontSize = fontSize, fontWeight = FontWeight.Bold, letterSpacing = 0.2.sp),
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}
