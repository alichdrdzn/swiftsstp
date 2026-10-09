package com.sstpflash.client.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.sstpflash.client.AppModel
import com.sstpflash.client.VpnController
import com.sstpflash.client.data.UsageEntry
import com.sstpflash.client.model.Profile

/**
 * The live screen: pick a profile, connect, watch session and lifetime
 * counters, the certificate fingerprint, and the scrolling log tail.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ConnectionScreen(
    profiles: List<Profile>,
    selectedId: String?,
    usage: Map<String, UsageEntry>,
    vpn: VpnController.Snapshot,
    log: List<String>,
    onSelect: (Profile) -> Unit,
    onConnect: (Profile) -> Unit,
    onDisconnect: () -> Unit,
    onNewProfile: () -> Unit,
) {
    val selected = profiles.firstOrNull { it.id == selectedId } ?: profiles.firstOrNull()
    val entry: UsageEntry? = selected?.let { usage[it.id] }

    var menuOpen by remember { mutableStateOf(false) }

    Scaffold(
        containerColor = MaterialTheme.colorScheme.background,
        topBar = {
            TopAppBar(
                title = { Text("Connection", style = MaterialTheme.typography.titleLarge) },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.background,
                ),
            )
        },
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            if (profiles.isEmpty()) {
                SectionCard {
                    Text(
                        "No profiles yet.",
                        style = MaterialTheme.typography.bodyLarge,
                    )
                    Text(
                        "Create a server profile first, then connect from here.",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Spacer(Modifier.height(8.dp))
                    Button(onClick = onNewProfile) { Text("New profile") }
                }
                return@Column
            }

            // ---- profile selector ------------------------------------
            SectionCard("Profile") {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text(
                            text = selected?.let { it.name.ifBlank { it.host } } ?: "—",
                            style = MaterialTheme.typography.titleMedium,
                        )
                        Text(
                            text = selected?.let {
                                "${it.host.ifBlank { "no host" }}:${it.port} · ${it.tlsModeLabel()}"
                            } ?: "",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    Box {
                        TextButton(onClick = { menuOpen = true }) { Text("Change") }
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
            }

            // ---- state + the one big action ---------------------------
            SectionCard {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween,
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    StateChip(vpn.state)
                    if (vpn.reconnectAttempt > 0 && vpn.state == VpnController.State.RECONNECTING) {
                        Text(
                            text = "attempt ${vpn.reconnectAttempt}",
                            style = MaterialTheme.typography.labelMedium,
                            color = Amber,
                        )
                    }
                }
                Spacer(Modifier.height(10.dp))
                Text(
                    text = vpn.message,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
                if (vpn.authError) {
                    Spacer(Modifier.height(6.dp))
                    Text(
                        text = "The server rejected these credentials. Reconnect is paused until the profile is fixed.",
                        style = MaterialTheme.typography.bodySmall,
                        color = Rose,
                    )
                }
                Spacer(Modifier.height(14.dp))
                Button(
                    onClick = { if (vpn.active) onDisconnect() else selected?.let(onConnect) },
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(60.dp),
                    shape = RoundedCornerShape(16.dp),
                    colors = if (vpn.active) ButtonDefaults.buttonColors(
                        containerColor = Rose.copy(alpha = 0.16f),
                        contentColor = Rose,
                    ) else ButtonDefaults.buttonColors(
                        containerColor = MaterialTheme.colorScheme.primary,
                        contentColor = MaterialTheme.colorScheme.onPrimary,
                    ),
                ) {
                    Icon(
                        if (vpn.active) Icons.Default.Stop else Icons.Default.PlayArrow,
                        contentDescription = null,
                        modifier = Modifier.size(22.dp),
                    )
                    Spacer(Modifier.size(10.dp))
                    Text(
                        text = if (vpn.active) "Disconnect" else "Connect",
                        style = MaterialTheme.typography.titleMedium,
                    )
                }
            }

            // ---- session statistics -----------------------------------
            SectionCard("This session") {
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    StatCell(
                        label = "Down",
                        value = formatBytes(vpn.down),
                        sub = formatRate(vpn.downRate),
                        valueColor = AccentBlue,
                        modifier = Modifier.weight(1f),
                    )
                    StatCell(
                        label = "Up",
                        value = formatBytes(vpn.up),
                        sub = formatRate(vpn.upRate),
                        valueColor = Mint,
                        modifier = Modifier.weight(1f),
                    )
                }
                Spacer(Modifier.height(14.dp))
                Divider()
                Spacer(Modifier.height(14.dp))
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    StatCell(
                        label = "Local",
                        value = vpn.localIp.ifBlank { "—" },
                        sub = vpn.peerIp.ifBlank { null }?.let { "peer $it" },
                        modifier = Modifier.weight(1f),
                    )
                    StatCell(
                        label = "MTU",
                        value = if (vpn.mtu > 0) vpn.mtu.toString() else "—",
                        sub = vpn.dns.joinToString(", ").ifBlank { null }?.let { "dns $it" },
                        modifier = Modifier.weight(1f),
                    )
                }
            }

            // ---- lifetime totals ---------------------------------------
            SectionCard("Lifetime usage") {
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    StatCell(
                        label = "Down",
                        value = formatBytes(entry?.totalDown ?: 0),
                        modifier = Modifier.weight(1f),
                    )
                    StatCell(
                        label = "Up",
                        value = formatBytes(entry?.totalUp ?: 0),
                        modifier = Modifier.weight(1f),
                    )
                    StatCell(
                        label = "Sessions",
                        value = (entry?.sessions ?: 0).toString(),
                        modifier = Modifier.weight(1f),
                    )
                }
                if (entry?.lastUsed.isNullOrBlank().not()) {
                    Spacer(Modifier.height(10.dp))
                    MonoLine("last used ${entry?.lastUsed}")
                }
            }

            // ---- certificate fingerprint --------------------------------
            SectionCard("Server certificate") {
                Text(
                    text = "SHA-256",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(Modifier.height(4.dp))
                SelectionText(vpn.certSha256)
            }

            // ---- log tail -------------------------------------------------
            LogCard(log)
            Spacer(Modifier.height(24.dp))
        }
    }
}

@Composable
private fun LogCard(log: List<String>) {
    val scroll = rememberScrollState()
    LaunchedEffect(log.size) { scroll.animateScrollTo(scroll.maxValue) }

    SectionCard("Log") {
        if (log.isEmpty()) {
            Text(
                text = "Nothing yet — connect to see the engine log.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        } else {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(max = 280.dp)
                    .verticalScroll(scroll)
                    .background(
                        MaterialTheme.colorScheme.surfaceContainerHighest,
                        RoundedCornerShape(10.dp),
                    )
                    .padding(10.dp),
                verticalArrangement = Arrangement.spacedBy(2.dp),
            ) {
                log.takeLast(200).forEach { line ->
                    Text(
                        text = line,
                        style = MaterialTheme.typography.labelSmall.copy(fontFamily = FontFamily.Monospace),
                        color = when {
                            line.contains("ERROR") || line.contains("failed") -> Rose
                            line.contains("tunnel up") -> Mint
                            else -> MaterialTheme.colorScheme.onSurfaceVariant
                        },
                    )
                }
            }
        }
    }
}

@Composable
private fun SelectionText(value: String) {
    androidx.compose.foundation.text.selection.SelectionContainer {
        Text(
            text = value.ifBlank { "not connected yet" },
            style = MaterialTheme.typography.labelSmall.copy(fontFamily = FontFamily.Monospace),
            color = if (value.isBlank()) MaterialTheme.colorScheme.onSurfaceVariant else AccentBlue,
            modifier = Modifier.fillMaxWidth(),
        )
    }
}
