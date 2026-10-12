package com.swiftsstp.client.ui

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowDropDown
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.swiftsstp.client.VpnController
import com.swiftsstp.client.model.Profile
import com.swiftsstp.client.net.IpGeo
import kotlinx.coroutines.delay

/** Toggle track at rest — dark charcoal, matching the Windows shell's neutral. */
private val ToggleTrackOff = Color(0xFF26262C)
private val ToggleTrackConnected = Color(0xFF10623F)
private val ToggleTrackConnecting = Color(0xFF6B4409)
private val ToggleTrackError = Color(0xFF6B1F1F)

/**
 * Home tab — the connect screen: the hero wordmark, a white profile badge,
 * one big pill toggle, the state word and the egress-IP lines. No stat
 * cards live here; they moved to the Stats tab, and the update check and
 * its offer moved to the Settings tab, which owns everything app-wide.
 *
 * The toggle is the only control in the app that starts or stops a tunnel,
 * and it carries the state colour (green / amber / rose), which frees the
 * status word below to stay plain white on black.
 */
@Composable
fun HomeScreen(
    profiles: List<Profile>,
    selectedId: String?,
    vpn: VpnController.Snapshot,
    onSelect: (Profile) -> Unit,
    onConnect: (Profile) -> Unit,
    onDisconnect: () -> Unit,
    onNewProfile: () -> Unit,
    onTab: (NavTab) -> Unit,
) {
    val selected = profiles.firstOrNull { it.id == selectedId } ?: profiles.firstOrNull()
    val profileName = selected?.let { it.name.ifBlank { it.host } }.orEmpty()
    val geo by IpGeo.state.collectAsState()

    // The profile list opens IN FLOW under the badge instead of in a
    // popup (a popup anchored there lands on the connect toggle), so it
    // needs a height budget. It may borrow exactly the free space the
    // three weighted spacers hold: those heights are captured only while
    // the list is closed, because once it is open the spacers have
    // already given their space away and re-measuring would hand the
    // list a budget that shrinks under it on every status refresh.
    var pickerOpen by remember { mutableStateOf(false) }
    var slackTop by remember { mutableStateOf(0) }
    var slackMid by remember { mutableStateOf(0) }
    var slackBot by remember { mutableStateOf(0) }
    val density = LocalDensity.current
    val listBudget = (with(density) { (slackTop + slackMid + slackBot).toDp() } - 8.dp)
        .coerceAtLeast(96.dp)

    // Refresh triggers: this tab appearing, the tunnel state settling (the
    // delay debounces a CONNECTING → CONNECTED → … burst into one request),
    // and a slow heartbeat while Home stays on screen.
    LaunchedEffect(Unit) {
        IpGeo.refresh()
        while (true) {
            delay(60_000)
            IpGeo.refresh()
        }
    }
    LaunchedEffect(vpn.state) {
        delay(2_000)
        IpGeo.refresh()
    }

    AppScaffold(state = vpn.state, current = NavTab.HOME, onTab = onTab, showBrand = false) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(horizontal = 24.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            // 1.5f above / 1f below centres the wordmark near the upper third.
            // Each weighted spacer reports the free space it holds (see the
            // budget above): that height is what the profile list may take.
            Spacer(
                Modifier
                    .weight(1.5f)
                    .onGloballyPositioned { if (!pickerOpen) slackTop = it.size.height },
            )

            BrandWordmark()

            Spacer(
                Modifier
                    .weight(1f)
                    .onGloballyPositioned { if (!pickerOpen) slackMid = it.size.height },
            )

            ProfileBadge(
                selected = selected,
                profiles = profiles,
                open = pickerOpen,
                onOpenChange = { pickerOpen = it },
                onSelect = onSelect,
                listMaxHeight = listBudget,
            )

            if (profiles.isEmpty()) {
                Spacer(Modifier.height(16.dp))
                Text(
                    text = "Create a server profile first — credentials stay on this device.",
                    style = MaterialTheme.typography.bodySmall,
                    color = TextMuted,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.widthIn(max = 280.dp),
                )
                Spacer(Modifier.height(10.dp))
                GhostButton(text = "New profile", onClick = onNewProfile)
            }

            Spacer(Modifier.height(30.dp))

            ConnectToggle(
                state = vpn.state,
                enabled = selected != null,
                onClick = {
                    // Choosing to connect also puts the profile list away:
                    // it is in flow now, so leaving it open would keep the
                    // column expanded under the toggle the user just used.
                    pickerOpen = false
                    if (vpn.active) onDisconnect() else selected?.let(onConnect)
                },
            )

            Spacer(Modifier.height(20.dp))

            StateText(state = vpn.state, vpn = vpn, profileName = profileName)

            Spacer(Modifier.height(30.dp))

            IpAddressBlock(result = geo)

            Spacer(
                Modifier
                    .weight(1f)
                    .onGloballyPositioned { if (!pickerOpen) slackBot = it.size.height },
            )
        }
    }
}

/**
 * Profile badge — the primary button's white pill naming the selected
 * server; tapping opens the profile list underneath it. It is the
 * second-brightest thing on screen after the wordmark, so the "which
 * server?" question answers at a glance. With nothing selected there is
 * nothing to celebrate: the pill empties out to the ghost-button grey
 * instead of taking an accent colour.
 *
 * The list is laid out IN FLOW rather than in a popup: a popup anchored
 * to the badge opens on top of the connect toggle, which sits 30dp
 * below it, and covering the one connect control is the bug being fixed
 * here. In flow, opening the list pushes the toggle and the rest of the
 * column down instead of covering them, and it closes again the moment
 * a profile is picked, the badge is tapped a second time, or the user
 * connects. [listMaxHeight] is the height budget the Home column's
 * weighted spacers can hand over, so the column never runs past the
 * bottom of the screen at any size; the list scrolls inside it. Its
 * width is measured off the pill itself, so list and pill stay flush
 * whatever the profile name runs to.
 */
@Composable
private fun ProfileBadge(
    selected: Profile?,
    profiles: List<Profile>,
    open: Boolean,
    onOpenChange: (Boolean) -> Unit,
    onSelect: (Profile) -> Unit,
    listMaxHeight: Dp,
) {
    val density = LocalDensity.current
    var pillWidth by remember { mutableStateOf(0.dp) }
    val shape = RoundedCornerShape(999.dp)
    val tint = if (selected == null) TextMuted else Color(0xFF0B0B0B)
    Column(Modifier.widthIn(max = 300.dp)) {
        Row(
            modifier = Modifier
                // FIRST in the chain, so it reports the pill's final size.
                // Behind the padding below it would report the content box
                // instead and drop the pill's own 16dp side padding, which
                // would make the list 32dp narrower than the badge.
                .onSizeChanged { pillWidth = with(density) { it.width.toDp() } }
                .height(40.dp)
                .clip(shape)
                .background(if (selected == null) Color.Transparent else Color.White)
                .border(1.dp, if (selected == null) Hairline2 else Color.Transparent, shape)
                .clickable { onOpenChange(!open) }
                .padding(horizontal = 16.dp)
                // Stable handle for the layout test: the pill's text is
                // also a row in the list, so text alone cannot pick it.
                .testTag("profile-badge-pill"),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = selected?.let { p -> p.name.ifBlank { p.host } } ?: "No profiles",
                style = MaterialTheme.typography.labelLarge,
                color = tint,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f),
            )
            Spacer(Modifier.width(6.dp))
            Icon(
                Icons.Default.ArrowDropDown,
                contentDescription = null,
                tint = tint,
            )
        }

        if (open && profiles.isNotEmpty() && pillWidth > 0.dp) {
            Spacer(Modifier.height(6.dp))
            ProfileList(
                profiles = profiles,
                modifier = Modifier
                    .width(pillWidth)
                    .heightIn(max = listMaxHeight),
                onSelect = {
                    onOpenChange(false)
                    onSelect(it)
                },
            )
        }
    }
}

/**
 * The profile list — the same card treatment as every other surface on
 * the screen (14dp radius, the two-tone card gradient, hairline border,
 * the standard shadow), scrolling inside the height budget its caller
 * works out. Rows keep the stock DropdownMenuItem look the popup used,
 * so what changed is only where the list sits, not how it reads.
 */
@Composable
private fun ProfileList(
    profiles: List<Profile>,
    modifier: Modifier = Modifier,
    onSelect: (Profile) -> Unit,
) {
    val shape = RoundedCornerShape(14.dp)
    Box(
        modifier = modifier
            .shadow(8.dp, shape, ambientColor = Color.Black, spotColor = Color.Black)
            .background(Brush.verticalGradient(listOf(Surface2, Surface1)), shape)
            .border(1.dp, Hairline, shape)
            .testTag("profile-list"),
    ) {
        Column(Modifier.verticalScroll(rememberScrollState())) {
            profiles.forEach { p ->
                DropdownMenuItem(
                    text = {
                        Text(
                            text = p.name.ifBlank { p.host },
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    },
                    onClick = { onSelect(p) },
                )
            }
        }
    }
}

/**
 * The connect/disconnect toggle — a wide pill whose white knob slides right
 * while the tunnel is coming up or up, and takes the state colour on the
 * track (green connected, amber connecting, rose error with the knob still
 * left). A spring with a little overshoot sells the slide; while connecting
 * only the border breathes, so a busy tunnel never competes with the
 * wordmark for attention.
 */
@Composable
private fun ConnectToggle(
    state: VpnController.State,
    enabled: Boolean,
    onClick: () -> Unit,
) {
    val active = state == VpnController.State.CONNECTING ||
        state == VpnController.State.CONNECTED ||
        state == VpnController.State.RECONNECTING
    val busy = state == VpnController.State.CONNECTING || state == VpnController.State.RECONNECTING

    val trackColor = when (state) {
        VpnController.State.CONNECTED -> ToggleTrackConnected
        VpnController.State.CONNECTING, VpnController.State.RECONNECTING -> ToggleTrackConnecting
        VpnController.State.ERROR -> ToggleTrackError
        else -> ToggleTrackOff
    }
    val borderColor = when (state) {
        VpnController.State.CONNECTED -> Mint.copy(alpha = 0.60f)
        VpnController.State.CONNECTING, VpnController.State.RECONNECTING -> Amber.copy(alpha = 0.60f)
        VpnController.State.ERROR -> Rose.copy(alpha = 0.60f)
        else -> Color.White.copy(alpha = 0.08f)
    }

    // 200 − 60 knob − 8dp inset each end = 124dp of travel.
    val knobOffset by animateDpAsState(
        targetValue = if (active) 124.dp else 0.dp,
        animationSpec = spring(dampingRatio = 0.72f, stiffness = Spring.StiffnessMedium),
        label = "connect-toggle-knob",
    )

    // Busy states breathe — but only the border; the knob and track hold still.
    val transition = rememberInfiniteTransition(label = "connect-toggle")
    val breathe by transition.animateFloat(
        initialValue = 1f,
        targetValue = 0.55f,
        animationSpec = infiniteRepeatable(tween(650), RepeatMode.Reverse),
        label = "connect-toggle-border-alpha",
    )

    val interactionSource = remember { MutableInteractionSource() }
    val pressed by interactionSource.collectIsPressedAsState()

    Box(
        modifier = Modifier
            .width(200.dp)
            .height(76.dp)
            .graphicsLayer {
                scaleX = if (pressed) 0.98f else 1f
                scaleY = if (pressed) 0.98f else 1f
            }
            .shadow(10.dp, RoundedCornerShape(999.dp))
            .clip(RoundedCornerShape(999.dp))
            .background(trackColor)
            .border(
                1.dp,
                borderColor.copy(alpha = borderColor.alpha * (if (busy) breathe else 1f)),
                RoundedCornerShape(999.dp),
            )
            .clickable(
                interactionSource = interactionSource,
                indication = null,
                enabled = enabled,
                onClick = onClick,
            )
            .semantics { contentDescription = if (active) "Disconnect" else "Connect" }
            .alpha(if (enabled) 1f else 0.45f),
        contentAlignment = Alignment.CenterStart,
    ) {
        Box(
            modifier = Modifier
                .padding(start = 8.dp)
                .offset(x = knobOffset)
                .size(60.dp)
                .shadow(8.dp, CircleShape)
                .background(Color.White, CircleShape),
        )
    }
}

/**
 * Status word and its muted sub-line. Deliberately colourless: the toggle
 * above carries the state colour, so the word itself stays pure white and
 * high-contrast on black.
 */
@Composable
private fun StateText(state: VpnController.State, vpn: VpnController.Snapshot, profileName: String) {
    val stateBig = when (state) {
        VpnController.State.CONNECTED -> "Connected"
        VpnController.State.CONNECTING -> "Connecting…"
        VpnController.State.RECONNECTING -> "Reconnecting…"
        VpnController.State.ERROR -> if (vpn.authError) "Auth rejected" else "Error"
        VpnController.State.DISCONNECTED -> "Disconnected"
    }
    val stateSub = when (state) {
        VpnController.State.CONNECTED -> "whole-device tunnel · $profileName"
        VpnController.State.CONNECTING -> "$profileName · whole-device"
        VpnController.State.RECONNECTING -> "attempt ${vpn.reconnectAttempt} · ${vpn.message}"
        VpnController.State.ERROR -> vpn.message
        VpnController.State.DISCONNECTED ->
            if (profileName.isBlank()) "add a profile to connect" else "ready · $profileName"
    }
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Text(
            text = stateBig,
            style = TextStyle(fontSize = 26.sp, fontWeight = FontWeight.Bold),
            color = Color.White,
        )
        Text(
            text = stateSub,
            style = MaterialTheme.typography.bodySmall.copy(fontSize = 13.sp),
            color = TextMuted,
            textAlign = TextAlign.Center,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.padding(top = 4.dp),
        )
        if (vpn.authError) {
            Text(
                text = "The server rejected these credentials. Reconnect is paused until the profile is fixed.",
                style = MaterialTheme.typography.bodySmall.copy(fontSize = 12.sp),
                color = Rose,
                textAlign = TextAlign.Center,
                modifier = Modifier.padding(top = 8.dp).widthIn(max = 300.dp),
            )
        }
    }
}

/**
 * Egress IP — two centred lines (micro-label, then the address with its
 * country flag). No card chrome: on pure black a hairline box only adds
 * noise. Tap copies; "Copied" fades in under the label in a reserved line so
 * nothing jumps.
 */
@Composable
private fun IpAddressBlock(result: IpGeo.Result?) {
    val context = LocalContext.current
    var copied by remember { mutableStateOf(false) }
    LaunchedEffect(copied) {
        if (copied) {
            delay(1500)
            copied = false
        }
    }

    val flag = IpGeo.flagEmoji(result?.country.orEmpty())
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        modifier = Modifier
            .fillMaxWidth()
            .clickable(enabled = result != null) {
                copyToClipboard(context, "SwiftSSTP IP", result!!.ip)
                copied = true
            },
    ) {
        Text(
            text = "YOUR IP ADDRESS",
            style = MaterialTheme.typography.labelMedium.copy(fontSize = 11.sp, letterSpacing = 1.2.sp),
            color = Color.White.copy(alpha = 0.65f),
        )
        Text(
            text = "Copied",
            style = MaterialTheme.typography.labelMedium.copy(fontSize = 11.sp),
            color = Mint,
            modifier = Modifier.alpha(if (copied) 1f else 0f),
        )
        Spacer(Modifier.height(8.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            if (flag.isNotBlank()) {
                Text(text = flag, fontSize = 17.sp)
                Spacer(Modifier.width(6.dp))
            }
            Text(
                text = result?.ip ?: "—",
                style = MaterialTheme.typography.labelLarge.copy(
                    fontSize = 17.sp,
                    fontWeight = FontWeight.SemiBold,
                    letterSpacing = 0.sp,
                ),
                color = if (result == null) TextMuted else Color.White,
            )
        }
    }
}

/** Plain-text clipboard copy via the platform service (no Compose deprecations). */
private fun copyToClipboard(context: Context, label: String, text: String) {
    val clipboard = context.getSystemService(ClipboardManager::class.java) ?: return
    clipboard.setPrimaryClip(ClipData.newPlainText(label, text))
}
