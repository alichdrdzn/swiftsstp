package com.swiftsstp.client.ui

import android.content.Context
import android.content.pm.ApplicationInfo
import androidx.compose.foundation.Image
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.Checkbox
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.listSaver
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.core.graphics.drawable.toBitmap
import com.swiftsstp.client.model.AppRules
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.util.concurrent.ConcurrentHashMap

/**
 * Per-app tunneling's picker machinery, shared by every screen that
 * offers it. It lives in its own file because the fields moved out of the
 * profile editor and into the Settings tab: one implementation, no copy
 * to keep in sync.
 */

/** The package-name list the schema stores, through the saveable round trip. */
internal val StringListSaver = listSaver<List<String>, String>(save = { it }, restore = { it })

/** One installed app as the picker rows show it; [info] carries the icon. */
internal data class InstalledApp(val pkg: String, val label: String, val info: ApplicationInfo)

/** Icons, keyed by package name: decoded once per process, off the main thread. */
private val iconCache = ConcurrentHashMap<String, ImageBitmap>()

/** Labels, keyed by package name: the picker fills it, the chips read it. */
private val labelCache = ConcurrentHashMap<String, String>()

/**
 * What per-app tunneling has selected — one removable chip per app, showing
 * the label when one can be resolved and the bare package name when it
 * cannot (an uninstalled or sideloaded package still routes by name).
 * Nothing selected is a real answer, not a hole: in allow-mode the whole
 * device is tunnelled, in deny-mode nothing is excluded.
 */
@OptIn(ExperimentalLayoutApi::class) // FlowRow has not left experimental yet
@Composable
internal fun SelectedApps(
    mode: String,
    packages: List<String>,
    onRemove: (String) -> Unit,
    onChoose: () -> Unit,
) {
    if (packages.isEmpty()) {
        Text(
            text = if (mode == AppRules.MODE_ALLOW) {
                "All apps — the whole device is tunnelled"
            } else {
                "No apps excluded"
            },
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    } else {
        val labels = packageLabels(packages)
        FlowRow(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            packages.forEach { pkg ->
                PackageChip(label = labels[pkg] ?: pkg, onRemove = { onRemove(pkg) })
            }
        }
    }
    Spacer(Modifier.height(12.dp))
    GhostButton(text = "Choose apps", onClick = onChoose)
}

@Composable
private fun PackageChip(label: String, onRemove: () -> Unit) {
    Surface(
        shape = RoundedCornerShape(999.dp),
        color = FieldBackground,
        border = BorderStroke(1.dp, Hairline2),
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.padding(start = 12.dp, end = 4.dp),
        ) {
            Text(
                text = label,
                style = MaterialTheme.typography.labelMedium,
                color = TextPrimary,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.widthIn(max = 190.dp),
            )
            Spacer(Modifier.width(4.dp))
            IconButton(onClick = onRemove, modifier = Modifier.size(24.dp)) {
                Icon(
                    imageVector = Icons.Default.Close,
                    contentDescription = "Remove $label",
                    tint = TextMuted,
                    modifier = Modifier.size(14.dp),
                )
            }
        }
    }
}

/**
 * Labels for the selected packages, resolved on IO and cached per package:
 * PackageManager is a binder call, and a chip that flickers back to a bare
 * package name on every recomposition is worse than no label at all.
 */
@Composable
private fun packageLabels(packages: List<String>): Map<String, String> {
    val context = LocalContext.current
    var labels by remember(packages) { mutableStateOf(emptyMap<String, String>()) }
    LaunchedEffect(packages) {
        labels = withContext(Dispatchers.IO) {
            packages.associateWith { pkg -> labelCache.getOrPut(pkg) { packageLabel(context, pkg) } }
        }
    }
    return labels
}

/** The app's label, or the package name when there is nothing better. */
@Suppress("DEPRECATION") // same int-flag overload as [installedApps]
private fun packageLabel(context: Context, pkg: String): String = runCatching {
    val pm = context.packageManager
    pm.getApplicationLabel(pm.getApplicationInfo(pkg, 0)).toString().ifBlank { pkg }
}.getOrDefault(pkg)

/**
 * The app picker: a search field matching label or package name, then every
 * installed app with its icon, label, package name and a checkbox, sorted
 * by label and virtualised by LazyColumn so a few hundred rows stay cheap.
 * Our own package is not offered — a tunnel rule cannot meaningfully refer
 * to the app drawing the rule.
 *
 * Selection accumulates here and only reaches the caller on Done; the
 * pending set and the query live in the caller's saveable state rather than
 * in this dialog, whose window has no saved-state owner to restore it from
 * when the phone is rotated mid-choice.
 *
 * Sizing, which is the reason this dialog has misbehaved twice: with
 * [DialogProperties.usePlatformDefaultWidth] = false, DialogLayout
 * measures the content against the whole display and then calls
 * window.setLayout(childWidth, childHeight), so the dialog window is
 * exactly whatever this measures, on a device where the app draws
 * edge-to-edge. Insetting the Surface was not enough the first time:
 * padding lives inside its node, so the Surface (card background
 * included) still measured the full display and the card kept running
 * under the status and navigation bars. The cap therefore lives on a
 * transparent wrapper OUTSIDE the card: at most 94% of the screen width
 * and 84% of its height (a fraction of the display survives even when no
 * inset is reported, so the window cannot outgrow the screen either way),
 * then the bars and the cutout, then the keyboard, then a 16dp gap. The
 * card fills what is left, so it is always fully on screen with the
 * Cancel / Done row clear of the navigation bar and the app list
 * scrolling in the space between.
 */
@Composable
internal fun AppPicker(
    query: String,
    onQueryChange: (String) -> Unit,
    selected: List<String>,
    onSelectedChange: (List<String>) -> Unit,
    onDone: () -> Unit,
    onDismiss: () -> Unit,
) {
    val context = LocalContext.current
    var apps by remember { mutableStateOf<List<InstalledApp>?>(null) }
    LaunchedEffect(Unit) {
        apps = withContext(Dispatchers.IO) { installedApps(context) }
    }
    val visible = remember(apps, query) {
        val needle = query.trim()
        apps.orEmpty().filter {
            needle.isEmpty() ||
                it.label.contains(needle, ignoreCase = true) ||
                it.pkg.contains(needle, ignoreCase = true)
        }
    }

    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(
            usePlatformDefaultWidth = false,
            decorFitsSystemWindows = false,
        ),
    ) {
        // Wrapper, not card: DialogLayout measures this content against the
        // whole display and then sizes the dialog window to it, so the screen
        // cap and the system insets belong here. On the Surface they would sit
        // inside its node and the card background would still span the display.
        Box(
            modifier = Modifier
                .fillMaxWidth(0.94f)
                .fillMaxHeight(0.84f)
                .windowInsetsPadding(WindowInsets.safeDrawing)
                .imePadding()
                .padding(16.dp),
        ) {
            Surface(
                modifier = Modifier.fillMaxSize(),
                shape = RoundedCornerShape(18.dp),
                color = MaterialTheme.colorScheme.surface,
                contentColor = MaterialTheme.colorScheme.onSurface,
            ) {
                Column(Modifier.padding(horizontal = 16.dp, vertical = 14.dp)) {
                    OutlinedTextField(
                        value = query,
                        onValueChange = onQueryChange,
                        label = { Text("Search apps") },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth(),
                    )
                    Row(
                        modifier = Modifier.fillMaxWidth().padding(vertical = 10.dp),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(
                            text = when {
                                apps == null -> "Loading apps…"
                                visible.isEmpty() -> "No apps match"
                                else -> "${selected.size} selected"
                            },
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        GhostButton(
                            text = "Clear",
                            onClick = { onSelectedChange(emptyList()) },
                            enabled = selected.isNotEmpty(),
                        )
                    }
                    LazyColumn(
                        modifier = Modifier.weight(1f).fillMaxWidth(),
                        verticalArrangement = Arrangement.spacedBy(2.dp),
                    ) {
                        items(visible, key = { it.pkg }) { app ->
                            AppRow(
                                app = app,
                                checked = app.pkg in selected,
                                onToggle = {
                                    onSelectedChange(
                                        if (app.pkg in selected) selected - app.pkg
                                        else selected + app.pkg,
                                    )
                                },
                            )
                        }
                    }
                    Row(
                        modifier = Modifier.fillMaxWidth().padding(top = 10.dp),
                        horizontalArrangement = Arrangement.spacedBy(10.dp, Alignment.End),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        GhostButton(text = "Cancel", onClick = onDismiss)
                        PrimaryButton(
                            text = "Done",
                            onClick = onDone,
                            height = 38.dp,
                            fontSize = 14.sp,
                        )
                    }
                }
            }
        }
    }
}

/** One picker row: icon, label over package name, and the selection box. */
@Composable
private fun AppRow(app: InstalledApp, checked: Boolean, onToggle: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onToggle)
            .padding(vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        InstalledIcon(app)
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text(
                text = app.label,
                style = MaterialTheme.typography.bodyLarge,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                text = app.pkg,
                style = MaterialTheme.typography.labelSmall,
                color = TextMuted,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        // The row is the click target, so the box is a read-out only —
        // giving it its own handler would toggle twice.
        Checkbox(checked = checked, onCheckedChange = null)
    }
}

/**
 * The app's icon, decoded off the main thread and cached by package name.
 * LazyColumn only composes what is on screen, so a drawer of a few hundred
 * apps costs one decode per row as it scrolls, not one per installed app up
 * front.
 */
@Composable
private fun InstalledIcon(app: InstalledApp, modifier: Modifier = Modifier) {
    val context = LocalContext.current
    // 2× the 36dp slot, so one bitmap stays sharp on the densest screens.
    val sizePx = with(LocalDensity.current) { 72.dp.roundToPx() }
    val icon by produceState(initialValue = iconCache[app.pkg], app.pkg) {
        if (value == null) {
            val decoded = withContext(Dispatchers.IO) {
                runCatching {
                    app.info.loadIcon(context.packageManager).toBitmap(sizePx, sizePx).asImageBitmap()
                }.getOrNull()
            }
            if (decoded != null) {
                iconCache[app.pkg] = decoded
                value = decoded
            }
        }
    }
    if (icon != null) {
        Image(
            bitmap = icon!!,
            contentDescription = null,
            modifier = modifier.clip(RoundedCornerShape(8.dp)),
        )
    } else {
        // Still decoding (or unreadable): a neutral tile, never a blank row.
        Box(
            modifier
                .clip(RoundedCornerShape(8.dp))
                .background(FieldBackground)
                .border(1.dp, Hairline2, RoundedCornerShape(8.dp)),
        )
    }
}

/**
 * Every installed package except our own, sorted by label. QUERY_ALL_PACKAGES
 * is what makes this list complete (see the manifest): without it the
 * platform hides whatever the caller cannot see, which would gut the one
 * job the picker has.
 *
 * The int-flag overload is deprecated-but-supported below API 33; the flags
 * variant would need a version gate for no gain on a list we load once.
 */
@Suppress("DEPRECATION")
private fun installedApps(context: Context): List<InstalledApp> {
    val pm = context.packageManager
    return pm.getInstalledApplications(0)
        .filter { it.packageName != context.packageName }
        .map { info ->
            InstalledApp(
                pkg = info.packageName,
                label = pm.getApplicationLabel(info)?.toString()?.ifBlank { info.packageName }
                    ?: info.packageName,
                info = info,
            )
        }
        .sortedBy { it.label.lowercase() }
}
