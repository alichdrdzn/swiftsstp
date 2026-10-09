package com.slipstream.client.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.slipstream.client.VpnController
import com.slipstream.client.model.Profile

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

/** Small uppercase caption above a group of fields. */
@Composable
fun SectionLabel(text: String) {
    Text(
        text = text.uppercase(),
        style = MaterialTheme.typography.labelMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(start = 2.dp, bottom = 6.dp),
    )
}

/** A titled card that groups related controls. */
@Composable
fun SectionCard(
    title: String? = null,
    modifier: Modifier = Modifier,
    content: @Composable () -> Unit,
) {
    Card(
        modifier = modifier.fillMaxWidth(),
        shape = RoundedCornerShape(18.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        elevation = CardDefaults.cardElevation(defaultElevation = 0.dp),
        border = androidx.compose.foundation.BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
    ) {
        Column(Modifier.padding(16.dp)) {
            if (title != null) {
                Text(
                    text = title,
                    style = MaterialTheme.typography.titleSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(bottom = 12.dp),
                )
            }
            content()
        }
    }
}

/** Rounded tonal pill for a TLS mode. */
@Composable
fun TlsChip(mode: String, modifier: Modifier = Modifier) {
    val (label, fg, bg) = when (mode) {
        Profile.TLS_VERIFY -> Triple("VERIFY", AccentBlue, AccentBlue.copy(alpha = 0.14f))
        Profile.TLS_CUSTOM_CA -> Triple("CUSTOM CA", Amber, Amber.copy(alpha = 0.14f))
        else -> Triple("INSECURE", Rose, Rose.copy(alpha = 0.14f))
    }
    Surface(
        modifier = modifier,
        shape = RoundedCornerShape(7.dp),
        color = bg,
        contentColor = fg,
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.labelSmall,
            modifier = Modifier.padding(horizontal = 7.dp, vertical = 3.dp),
        )
    }
}

/** Connection state pill: dot + label, colour carried by the state. */
@Composable
fun StateChip(state: VpnController.State, modifier: Modifier = Modifier) {
    val (label, color) = when (state) {
        VpnController.State.CONNECTED -> "CONNECTED" to Mint
        VpnController.State.CONNECTING -> "CONNECTING" to Amber
        VpnController.State.RECONNECTING -> "RECONNECTING" to Amber
        VpnController.State.ERROR -> "ERROR" to Rose
        VpnController.State.DISCONNECTED -> "DISCONNECTED" to TextMuted
    }
    Surface(
        modifier = modifier,
        shape = RoundedCornerShape(999.dp),
        color = color.copy(alpha = 0.13f),
        contentColor = color,
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 11.dp, vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(7.dp),
        ) {
            Box(
                modifier = Modifier
                    .size(7.dp)
                    .background(color, CircleShape),
            )
            Text(text = label, style = MaterialTheme.typography.labelSmall)
        }
    }
}

/** A compact "label over value" statistic cell. */
@Composable
fun StatCell(
    label: String,
    value: String,
    sub: String? = null,
    valueColor: Color = MaterialTheme.colorScheme.onSurface,
    modifier: Modifier = Modifier,
) {
    Column(modifier) {
        Text(
            text = label.uppercase(),
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.height(4.dp))
        Text(
            text = value,
            style = MaterialTheme.typography.titleMedium,
            color = valueColor,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
        if (sub != null) {
            Text(
                text = sub,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

/** Monospace, copy-friendly technical value (fingerprints, addresses). */
@Composable
fun MonoLine(value: String, modifier: Modifier = Modifier) {
    Text(
        text = value.ifBlank { "—" },
        style = MaterialTheme.typography.labelSmall.copy(fontFamily = FontFamily.Monospace),
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = modifier,
    )
}

@Composable
fun Divider(modifier: Modifier = Modifier) {
    Box(
        modifier
            .fillMaxWidth()
            .height(1.dp)
            .background(MaterialTheme.colorScheme.outlineVariant),
    )
}

@Composable
fun HGap(dp: Int) = Spacer(Modifier.width(dp.dp))
