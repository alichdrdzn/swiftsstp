package com.swiftsstp.client.ui

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.swiftsstp.client.VpnController
import com.swiftsstp.client.data.UsageEntry
import com.swiftsstp.client.model.Profile

/**
 * Stats tab — everything the connection view used to show below the connect
 * card: session counters, parallel tunnels, lifetime usage, the negotiated
 * endpoint and the server certificate. Read-only; the only controls here are
 * inside the tunnel chips.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun StatsScreen(
    profiles: List<Profile>,
    selectedId: String?,
    usage: Map<String, UsageEntry>,
    vpn: VpnController.Snapshot,
    onTab: (NavTab) -> Unit,
) {
    val selected = profiles.firstOrNull { it.id == selectedId } ?: profiles.firstOrNull()
    val entry: UsageEntry? = selected?.let { usage[it.id] }
    val profileName = selected?.let { it.name.ifBlank { it.host } }.orEmpty()
    val tunnelsCount = if (vpn.active) vpn.tunnels else (selected?.tunnels ?: 1)
    val proxyLabel = vpn.proxyLabel.ifBlank { selected?.proxy?.label().orEmpty() }

    AppScaffold(state = vpn.state, current = NavTab.STATS, onTab = onTab) { padding ->
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding),
            contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 12.dp, bottom = 28.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            // ---- session counters --------------------------------------
            item {
                SectionCard("Session") {
                    StatRow("Uploaded", formatBytes(vpn.up))
                    StatRow("Downloaded", formatBytes(vpn.down))
                    StatRow("Rate up", formatRate(vpn.upRate))
                    StatRow("Rate down", formatRate(vpn.downRate), showDivider = false)
                }
            }

            // ---- parallel tunnels --------------------------------------
            item {
                SectionCard(
                    "Tunnels",
                    trailing = { MutedCaption(if (vpn.active) "${vpn.tunnels}" else "—") },
                ) {
                    StatRow(
                        label = "Upstream proxy",
                        value = proxyLabel.ifBlank { "direct" },
                        valueColor = if (proxyLabel.isBlank()) TextMuted else Amber,
                        mono = true,
                    )
                    StatRow(
                        label = "Sessions ready",
                        value = if (vpn.sessions.isEmpty()) "—"
                        else "${vpn.sessions.count { it.ready }} / ${vpn.sessions.size}",
                    )
                    if (tunnelsCount > 1) {
                        Spacer(Modifier.height(4.dp))
                        InfoChip("$tunnelsCount tunnels", TextPrimary)
                    }
                    if (vpn.sessions.isNotEmpty()) {
                        FlowRow(
                            modifier = Modifier.padding(top = 12.dp),
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                            verticalArrangement = Arrangement.spacedBy(8.dp),
                        ) {
                            vpn.sessions.forEach { SessionChip(it) }
                        }
                    }
                    Text(
                        text = "One chip per SSTP session: index, inner IP, bytes and readiness. " +
                            "Dead sessions redial in the background while the rest carry traffic.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(top = 10.dp),
                    )
                }
            }

            // ---- lifetime usage ----------------------------------------
            item {
                SectionCard(
                    "Lifetime",
                    trailing = {
                        if (profileName.isNotBlank()) MutedCaption("· $profileName")
                    },
                ) {
                    StatRow("Total up", formatBytes(entry?.totalUp ?: 0))
                    StatRow("Total down", formatBytes(entry?.totalDown ?: 0))
                    StatRow("Sessions", (entry?.sessions ?: 0).toString())
                    StatRow("Reconnects", vpn.reconnectAttempt.toString())
                    StatRow(
                        label = "Last used",
                        value = entry?.lastUsed?.ifBlank { null } ?: "—",
                        showDivider = false,
                    )
                }
            }

            // ---- negotiated endpoint -----------------------------------
            item {
                SectionCard("Endpoint") {
                    StatRow("Local", vpn.localIp)
                    StatRow("Peer", vpn.peerIp)
                    StatRow("MTU", if (vpn.mtu > 0) vpn.mtu.toString() else "—")
                    StatRow("DNS", vpn.dns.joinToString(", "), mono = true, showDivider = false)
                }
            }

            // ---- certificate fingerprint --------------------------------
            item {
                SectionCard("Server certificate") {
                    Text(
                        text = "SHA-256 fingerprint",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Spacer(Modifier.height(6.dp))
                    CertBox(vpn.certSha256)
                }
            }
        }
    }
}

/** One `.chip` from the Windows tunnels card: index, inner IP, bytes, state. */
@Composable
private fun SessionChip(s: VpnController.SessionStat) {
    val color = if (s.ready) Mint else Rose
    val mono = MaterialTheme.typography.labelSmall.copy(
        fontFamily = FontFamily.Monospace,
        fontSize = 11.5.sp,
    )
    Surface(
        shape = RoundedCornerShape(999.dp),
        color = if (s.ready) Mint.copy(alpha = 0.10f) else Rose.copy(alpha = 0.08f),
        contentColor = color,
        border = BorderStroke(1.dp, color.copy(alpha = if (s.ready) 0.45f else 0.4f)),
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 10.dp, vertical = 5.dp),
            horizontalArrangement = Arrangement.spacedBy(7.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = "#${s.index + 1}",
                style = mono.copy(fontWeight = FontWeight.ExtraBold),
                color = TextPrimary,
            )
            Text(
                text = s.localIp.ifBlank { "—" },
                style = mono,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text("↑" + formatBytes(s.up), style = mono, color = Mint)
            Text("↓" + formatBytes(s.down), style = mono, color = TextPrimary)
            Text(
                text = if (s.ready) "READY" else "DOWN",
                style = mono.copy(fontSize = 10.sp, letterSpacing = 0.6.sp),
                color = color,
            )
        }
    }
}

/** Muted trailing hint inside a card header (`.card h3 span.muted`). */
@Composable
private fun MutedCaption(text: String) {
    Text(
        text = text,
        style = MaterialTheme.typography.labelMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        maxLines = 1,
        overflow = TextOverflow.Ellipsis,
    )
}

/**
 * Certificate fingerprint box — mirrors Windows `.cert-fp`: selectable
 * green monospace text on an inset well with a hairline border.
 */
@Composable
private fun CertBox(value: String) {
    val shape = RoundedCornerShape(8.dp)
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .clip(shape)
            .background(Color.Black.copy(alpha = 0.25f))
            .border(1.dp, Hairline, shape)
            .padding(horizontal = 10.dp, vertical = 8.dp),
    ) {
        SelectionContainer {
            Text(
                text = value.ifBlank { "not connected yet" },
                style = MaterialTheme.typography.bodySmall.copy(
                    fontFamily = FontFamily.Monospace,
                    lineHeight = 18.sp,
                ),
                color = if (value.isBlank()) TextMuted else Mint,
                modifier = Modifier.fillMaxWidth(),
            )
        }
    }
}
