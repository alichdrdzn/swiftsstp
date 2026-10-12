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
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowDropDown
import androidx.compose.material3.DropdownMenu
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
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
 * Home tab — the connect screen: the hero wordmark, an orange profile badge,
 * one big pill toggle, the state word, and the egress-IP lines. No stat
 * cards live here; they moved to the Stats tab.
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
            Spacer(Modifier.weight(1.5f))

            BrandWordmark()

            Spacer(Modifier.weight(1f))

            ProfileBadge(selected = selected, profiles = profiles, onSelect = onSelect)

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
                    if (vpn.active) onDisconnect() else selected?.let(onConnect)
                },
            )

            Spacer(Modifier.height(20.dp))

            StateText(state = vpn.state, vpn = vpn, profileName = profileName)

            Spacer(Modifier.height(30.dp))

            IpAddressBlock(result = geo)

            Spacer(Modifier.weight(1f))
        }
    }
}

/**
 * Profile badge — an orange-outlined pill naming the selected server; tapping
 * opens the profile list. It is the second-brightest thing on screen after
 * the wordmark, so the "which server?" question answers at a glance.
 */
@Composable
private fun ProfileBadge(
    selected: Profile?,
    profiles: List<Profile>,
    onSelect: (Profile) -> Unit,
) {
    var menuOpen by remember { mutableStateOf(false) }
    val shape = RoundedCornerShape(999.dp)
    Box(Modifier.widthIn(max = 300.dp)) {
        Row(
            modifier = Modifier
                .height(40.dp)
                .clip(shape)
                .background(Orange.copy(alpha = 0.14f))
                .border(1.dp, Orange.copy(alpha = 0.55f), shape)
                .clickable { menuOpen = true }
                .padding(horizontal = 16.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = selected?.let { p -> p.name.ifBlank { p.host } } ?: "No profiles",
                style = MaterialTheme.typography.labelLarge,
                color = if (selected == null) TextMuted else Color(0xFFFFA04A),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f),
            )
            Spacer(Modifier.width(6.dp))
            Icon(
                Icons.Default.ArrowDropDown,
                contentDescription = null,
                tint = if (selected == null) TextMuted else Color(0xFFFFA04A),
            )
        }
        DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
            profiles.forEach { p ->
                DropdownMenuItem(
                    text = { Text(p.name.ifBlank { p.host }) },
                    onClick = {
                        menuOpen = false
                        onSelect(p)
                    },
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

    // 248 − 76 knob − 8dp inset each end = 156dp of travel.
    val knobOffset by animateDpAsState(
        targetValue = if (active) 156.dp else 0.dp,
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
            .width(248.dp)
            .height(92.dp)
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
                .size(76.dp)
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
