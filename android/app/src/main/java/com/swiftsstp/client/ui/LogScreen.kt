package com.swiftsstp.client.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.swiftsstp.client.VpnController

/**
 * Log tab — the engine log, full height: the monospace tail of the ring
 * buffer, red for failures, green for tunnel-up lines, and a ghost "clear".
 */
@Composable
fun LogScreen(
    state: VpnController.State,
    log: List<String>,
    onTab: (NavTab) -> Unit,
) {
    AppScaffold(state = state, current = NavTab.LOG, onTab = onTab) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(horizontal = 16.dp, vertical = 12.dp),
        ) {
            LogCard(log, Modifier.weight(1f).fillMaxWidth())
        }
    }
}

/** The `.log-card`: monospace tail, red errors, green "tunnel up", clear. */
@Composable
private fun LogCard(log: List<String>, modifier: Modifier = Modifier) {
    val scroll = rememberScrollState()
    LaunchedEffect(log.size) { scroll.animateScrollTo(scroll.maxValue) }

    SectionCard(
        "Log",
        modifier = modifier,
        trailing = {
            GhostButton(
                text = "clear",
                onClick = { VpnController.clearLog() },
                height = 26.dp,
                fontSize = 11.sp,
            )
        },
    ) {
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
                    .fillMaxHeight()
                    .verticalScroll(scroll)
                    .background(WellBackground, RoundedCornerShape(10.dp))
                    .padding(10.dp),
                verticalArrangement = Arrangement.spacedBy(2.dp),
            ) {
                log.takeLast(200).forEach { line ->
                    Text(
                        text = line,
                        style = MaterialTheme.typography.labelSmall.copy(fontFamily = FontFamily.Monospace),
                        color = logLineColor(line),
                    )
                }
                Spacer(Modifier.height(2.dp))
            }
        }
    }
}

/** Windows `appendLog()` colouring: errors red, tunnel-up lines green. */
private fun logLineColor(line: String): Color {
    val lower = line.lowercase()
    return when {
        "error" in lower || "fail" in lower || "reject" in lower || "warning" in lower -> Rose
        "tunnel up" in lower || "ready" in lower ||
            ("connected" in lower && "disconnected" !in lower) -> Mint
        else -> TextMuted
    }
}
