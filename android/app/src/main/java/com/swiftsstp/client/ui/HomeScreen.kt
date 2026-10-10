package com.swiftsstp.client.ui

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
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
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.swiftsstp.client.VpnController
import com.swiftsstp.client.model.Profile
import com.swiftsstp.client.net.IpGeo
import kotlinx.coroutines.delay

/**
 * Home tab — the WARP connect screen: a profile pill, the one big circular
 * connect button, the state line under it, and the egress-IP card. No stat
 * cards live here; they moved to the Stats tab.
 *
 * The circle is the only control in the app that starts or stops a tunnel.
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

    AppScaffold(state = vpn.state, current = NavTab.HOME, onTab = onTab) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(horizontal = 24.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Spacer(Modifier.weight(1f))

            ProfileSelectorPill(selected = selected, profiles = profiles, onSelect = onSelect)

            Spacer(Modifier.height(30.dp))

            ConnectCircleButton(
                state = vpn.state,
                enabled = selected != null,
                onClick = {
                    if (vpn.active) onDisconnect() else selected?.let(onConnect)
                },
            )

            Spacer(Modifier.height(22.dp))

            StateText(state = vpn.state, vpn = vpn, profileName = profileName)

            if (profiles.isEmpty()) {
                Spacer(Modifier.height(18.dp))
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

            Spacer(Modifier.height(26.dp))

            IpAddressCard(result = geo)

            Spacer(Modifier.weight(1f))
        }
    }
}

/**
 * Profile selector pill — the slim "which server?" control at the top of the
 * WARP home screen; tapping it opens the profile list.
 */
@Composable
private fun ProfileSelectorPill(
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
                .background(FieldBackground)
                .border(1.dp, Hairline2, shape)
                .clickable { menuOpen = true }
                .padding(horizontal = 16.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = selected?.let { p -> p.name.ifBlank { p.host } } ?: "No profiles",
                style = MaterialTheme.typography.labelLarge,
                color = if (selected == null) TextMuted else TextPrimary,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f),
            )
            Spacer(Modifier.width(6.dp))
            Icon(Icons.Default.ArrowDropDown, contentDescription = null, tint = TextMuted)
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
 * The big circular connect button — WARP's power circle: a dark disc with a
 * hairline ring at rest, mint and glowing while connected, amber and breathing
 * while the tunnel is coming up, rose on error.
 */
@Composable
private fun ConnectCircleButton(
    state: VpnController.State,
    enabled: Boolean,
    onClick: () -> Unit,
) {
    val busy = state == VpnController.State.CONNECTING || state == VpnController.State.RECONNECTING
    val accent = when (state) {
        VpnController.State.CONNECTED -> Mint
        VpnController.State.CONNECTING, VpnController.State.RECONNECTING -> Amber
        VpnController.State.ERROR -> Rose
        else -> Hairline2
    }
    val iconColor = when (state) {
        VpnController.State.CONNECTED -> Mint
        VpnController.State.CONNECTING, VpnController.State.RECONNECTING -> Amber
        VpnController.State.ERROR -> Rose
        else -> TextPrimary
    }
    val glow = when (state) {
        VpnController.State.CONNECTED -> Mint
        VpnController.State.CONNECTING, VpnController.State.RECONNECTING -> Amber
        VpnController.State.ERROR -> Rose
        else -> Color.Black
    }
    val elevation = when (state) {
        VpnController.State.CONNECTED -> 28.dp
        VpnController.State.CONNECTING, VpnController.State.RECONNECTING -> 18.dp
        VpnController.State.ERROR -> 12.dp
        else -> 0.dp
    }

    // Busy states breathe — the ring's scale and alpha cycle continuously.
    val transition = rememberInfiniteTransition(label = "connect-circle")
    val pulseAlpha by transition.animateFloat(
        initialValue = 1f,
        targetValue = 0.6f,
        animationSpec = infiniteRepeatable(tween(650), RepeatMode.Reverse),
        label = "connect-circle-alpha",
    )
    val pulseScale by transition.animateFloat(
        initialValue = 1f,
        targetValue = 1.035f,
        animationSpec = infiniteRepeatable(tween(650), RepeatMode.Reverse),
        label = "connect-circle-scale",
    )

    val interactionSource = remember { MutableInteractionSource() }
    val pressed by interactionSource.collectIsPressedAsState()

    Box(
        modifier = Modifier
            .size(190.dp)
            .graphicsLayer {
                scaleX = pulseScale * (if (pressed) 0.98f else 1f)
                scaleY = pulseScale * (if (pressed) 0.98f else 1f)
                alpha = (if (busy) pulseAlpha else 1f) * (if (pressed) 0.85f else 1f)
            }
            .shadow(elevation, CircleShape, ambientColor = glow, spotColor = glow)
            .clip(CircleShape)
            .background(Surface1)
            .border(2.dp, accent, CircleShape)
            .clickable(
                interactionSource = interactionSource,
                indication = null,
                enabled = enabled,
                onClick = onClick,
            )
            .alpha(if (enabled) 1f else 0.45f),
        contentAlignment = Alignment.Center,
    ) {
        Icon(
            imageVector = AppIcons.Power,
            contentDescription = if (state == VpnController.State.CONNECTED) {
                "Disconnect"
            } else {
                "Connect"
            },
            tint = iconColor,
            modifier = Modifier.size(64.dp),
        )
    }
}

/** The state word and its muted sub-line under the connect button. */
@Composable
private fun StateText(state: VpnController.State, vpn: VpnController.Snapshot, profileName: String) {
    val (stateBig, stateColor) = when (state) {
        VpnController.State.CONNECTED -> "Connected" to Mint
        VpnController.State.CONNECTING -> "Connecting…" to Amber
        VpnController.State.RECONNECTING -> "Reconnecting…" to Amber
        VpnController.State.ERROR -> (if (vpn.authError) "Auth rejected" else "Error") to Rose
        VpnController.State.DISCONNECTED -> "Not connected" to TextPrimary
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
            style = TextStyle(fontSize = 24.sp, fontWeight = FontWeight.Bold),
            color = stateColor,
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
 * Egress IP card — the WARP "your IP" row: a slim rounded card with the
 * address in monospace behind its country's flag. Tapping copies the address.
 */
@Composable
private fun IpAddressCard(result: IpGeo.Result?) {
    val context = LocalContext.current
    val shape = RoundedCornerShape(14.dp)
    var copied by remember { mutableStateOf(false) }
    LaunchedEffect(copied) {
        if (copied) {
            delay(1500)
            copied = false
        }
    }

    val flag = IpGeo.flagEmoji(result?.country.orEmpty())
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .clip(shape)
            .background(Surface1)
            .border(1.dp, Hairline, shape)
            .clickable(enabled = result != null) {
                copyToClipboard(context, "SwiftSSTP IP", result!!.ip)
                copied = true
            }
            .padding(horizontal = 18.dp, vertical = 14.dp),
    ) {
        Column {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = "IP ADDRESS",
                    style = MaterialTheme.typography.labelMedium.copy(letterSpacing = 1.sp),
                    color = TextMuted,
                )
                Spacer(Modifier.weight(1f))
                Text(
                    text = "Copied",
                    style = MaterialTheme.typography.labelMedium,
                    color = Mint,
                    modifier = Modifier.alpha(if (copied) 1f else 0f),
                )
            }
            Spacer(Modifier.height(8.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                if (flag.isNotBlank()) {
                    Text(text = flag, fontSize = 20.sp)
                    Spacer(Modifier.width(10.dp))
                }
                Text(
                    text = result?.ip ?: "—",
                    style = MaterialTheme.typography.labelLarge.copy(
                        fontSize = 18.sp,
                        fontFamily = FontFamily.Monospace,
                        fontWeight = FontWeight.SemiBold,
                        letterSpacing = 0.sp,
                    ),
                    color = if (result == null) TextMuted else TextPrimary,
                )
            }
        }
    }
}

/** Plain-text clipboard copy via the platform service (no Compose deprecations). */
private fun copyToClipboard(context: Context, label: String, text: String) {
    val clipboard = context.getSystemService(ClipboardManager::class.java) ?: return
    clipboard.setPrimaryClip(ClipData.newPlainText(label, text))
}
