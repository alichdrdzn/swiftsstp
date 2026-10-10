package com.swiftsstp.client.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.swiftsstp.client.VpnController
import com.swiftsstp.client.data.UsageEntry
import com.swiftsstp.client.model.Profile

/**
 * Profile list — mirrors the Windows profiles view: a header row with the
 * "+ New profile" primary button (it replaces the FAB), one card per server
 * with its name, host line, `.tag` row, lifetime usage and the `.btn-ghost`
 * action row.
 *
 * "Use" only selects the profile and jumps to the Home tab; it never starts
 * a tunnel, so the circle there stays the single path.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun ProfilesScreen(
    profiles: List<Profile>,
    usage: Map<String, UsageEntry>,
    state: VpnController.State,
    onAdd: () -> Unit,
    onEdit: (Profile) -> Unit,
    onDuplicate: (Profile) -> Unit,
    onDelete: (Profile) -> Unit,
    onUse: (Profile) -> Unit,
    onTab: (NavTab) -> Unit,
) {
    AppScaffold(state = state, current = NavTab.PROFILES, onTab = onTab) { padding ->
        Column(Modifier.fillMaxSize().padding(padding)) {
            ListHead(
                subtitle = if (profiles.isEmpty()) "No servers yet"
                else "${profiles.size} server${if (profiles.size == 1) "" else "s"} configured",
                onAdd = onAdd,
            )
            if (profiles.isEmpty()) {
                EmptyState(Modifier.weight(1f), onAdd)
            } else {
                LazyColumn(
                    modifier = Modifier.weight(1f).fillMaxWidth(),
                    contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 12.dp, bottom = 28.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    items(profiles, key = { it.id }) { profile ->
                        ProfileCard(
                            profile = profile,
                            usage = usage[profile.id],
                            onEdit = { onEdit(profile) },
                            onDuplicate = { onDuplicate(profile) },
                            onDelete = { onDelete(profile) },
                            onUse = { onUse(profile) },
                        )
                    }
                }
            }
        }
    }
}

/** `.list-head`: title + count on the left, the primary button on the right. */
@Composable
private fun ListHead(subtitle: String, onAdd: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(start = 16.dp, end = 16.dp, top = 16.dp, bottom = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(
                text = "Profiles",
                style = TextStyle(fontSize = 20.sp, fontWeight = FontWeight.SemiBold),
                letterSpacing = (-0.2).sp,
            )
            Text(
                text = subtitle,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Spacer(Modifier.size(12.dp))
        GradientButton(
            text = "+ New profile",
            onClick = onAdd,
            height = 44.dp,
            fontSize = 14.sp,
        )
    }
}

/** One `.card.profile-card`: identity, tags, lifetime usage, actions. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun ProfileCard(
    profile: Profile,
    usage: UsageEntry?,
    onEdit: () -> Unit,
    onDuplicate: () -> Unit,
    onDelete: () -> Unit,
    onUse: () -> Unit,
) {
    SectionCard {
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(
                text = profile.name.ifBlank { "unnamed" },
                style = TextStyle(fontSize = 17.sp, fontWeight = FontWeight.ExtraBold),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                text = buildString {
                    append(profile.host.ifBlank { "no host" })
                    append(":${profile.port}")
                    if (profile.sni.isNotBlank()) append(" · sni ${profile.sni}")
                },
                style = MaterialTheme.typography.bodyMedium.copy(fontSize = 13.5.sp),
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )

            FlowRow(
                horizontalArrangement = Arrangement.spacedBy(6.dp),
                verticalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                TlsChip(profile.tlsMode)
                InfoChip(profile.username.ifBlank { "—" }, AccentBlue)
                if (profile.autoReconnect.enabled) InfoChip("auto-reconnect", AccentBlue)
                if (profile.proxy.enabled) InfoChip("proxy ${profile.proxy.type}", Amber)
                if (profile.tunnels > 1) InfoChip("${profile.tunnels} tunnels", Mint)
            }

            Row(
                horizontalArrangement = Arrangement.spacedBy(12.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = "↓ ${formatBytes(usage?.totalDown ?: 0)}",
                    style = MaterialTheme.typography.labelMedium,
                    color = AccentBlue,
                )
                Text(
                    text = "↑ ${formatBytes(usage?.totalUp ?: 0)}",
                    style = MaterialTheme.typography.labelMedium,
                    color = Mint,
                )
                Text(
                    text = "${usage?.sessions ?: 0} session${if ((usage?.sessions ?: 0) == 1) "" else "s"}",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }

            FlowRow(
                modifier = Modifier.padding(top = 4.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                GhostButton(text = "Edit", onClick = onEdit)
                GhostButton(text = "Duplicate", onClick = onDuplicate)
                GhostButton(text = "Delete", onClick = onDelete)
                GhostButton(text = "Use", onClick = onUse)
            }
        }
    }
}

/** `.empty`: headline, hint, and a way to create the first profile. */
@Composable
private fun EmptyState(modifier: Modifier = Modifier, onAdd: () -> Unit) {
    Column(
        modifier = modifier.fillMaxSize().padding(horizontal = 32.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Text(
            text = "No profiles yet",
            style = MaterialTheme.typography.headlineSmall,
            fontWeight = FontWeight.SemiBold,
            textAlign = TextAlign.Center,
        )
        Spacer(Modifier.size(8.dp))
        Text(
            text = "Add an SSTP server to get started. Credentials stay on this device.",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
        )
        Spacer(Modifier.size(20.dp))
        GradientButton(
            text = "New profile",
            onClick = onAdd,
            height = 46.dp,
            fontSize = 15.sp,
        )
    }
}
