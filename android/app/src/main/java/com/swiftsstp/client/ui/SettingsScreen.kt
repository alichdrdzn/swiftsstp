package com.swiftsstp.client.ui

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
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
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.swiftsstp.client.VpnController
import com.swiftsstp.client.model.AppRules
import com.swiftsstp.client.model.AppSettings
import com.swiftsstp.client.model.AutoReconnect
import com.swiftsstp.client.model.Profile
import com.swiftsstp.client.model.ProxySettings
import com.swiftsstp.client.net.UpdateChecker
import com.swiftsstp.client.net.UpdateInstaller
import kotlinx.coroutines.delay

private val PROXY_LABELS = listOf("None", "HTTP CONNECT", "SOCKS5")

/**
 * Settings tab — the app-wide half of the configuration, one set of
 * values shared by every profile (docs/PROFILE_SCHEMA.md): the manual
 * update check and its offer, then the tunnel options that used to live
 * on each profile. Everything here is a draft until the header's Save;
 * leaving the tab without pressing it keeps the previous values, so the
 * app can never end up half-configured.
 *
 * Card order follows how often each one is touched after install:
 * updates and per-app routing first, the transport tuning last.
 */
@Composable
fun SettingsScreen(
    settings: AppSettings,
    state: VpnController.State,
    onSave: (AppSettings) -> Unit,
    onTab: (NavTab) -> Unit,
) {
    // Network.
    var mtuText by rememberSaveable { mutableStateOf(settings.mtu.toString()) }
    var ipv4On by rememberSaveable { mutableStateOf(settings.ipv4Enabled) }
    var ipv6On by rememberSaveable { mutableStateOf(settings.ipv6Enabled) }
    var dnsText by rememberSaveable { mutableStateOf(settings.dnsOverride.joinToString(", ")) }

    // Auto-reconnect.
    var reconnectOn by rememberSaveable { mutableStateOf(settings.autoReconnect.enabled) }
    var backoffInitial by rememberSaveable { mutableStateOf(settings.autoReconnect.initialBackoffMs.toString()) }
    var backoffMax by rememberSaveable { mutableStateOf(settings.autoReconnect.maxBackoffMs.toString()) }

    // Upstream proxy: raw text states, folded into the model on save.
    var proxyType by rememberSaveable { mutableStateOf(settings.proxy.type) }
    var proxyHost by rememberSaveable { mutableStateOf(settings.proxy.host) }
    var proxyPortText by rememberSaveable { mutableStateOf(if (settings.proxy.port > 0) settings.proxy.port.toString() else "") }
    var proxyUser by rememberSaveable { mutableStateOf(settings.proxy.username) }
    var proxyPass by rememberSaveable { mutableStateOf(settings.proxy.password) }
    var showProxyPassword by remember { mutableStateOf(false) }

    // Parallel sessions.
    var tunnels by rememberSaveable { mutableStateOf(settings.tunnels.coerceIn(Profile.MIN_TUNNELS, Profile.MAX_TUNNELS)) }

    // Per-app tunneling. The selection is saveable: the picker's dialog
    // window has no saved-state owner of its own, so a rotation would drop
    // a half-finished multi-select anywhere but here.
    var ruleMode by rememberSaveable { mutableStateOf(settings.rules.mode) }
    var selectedPackages by rememberSaveable(stateSaver = StringListSaver) {
        mutableStateOf(settings.rules.packages)
    }
    var pickerOpen by rememberSaveable { mutableStateOf(false) }
    var pickerQuery by rememberSaveable { mutableStateOf("") }
    var pickerSelected by rememberSaveable(stateSaver = StringListSaver) {
        mutableStateOf(settings.rules.packages)
    }

    val update by UpdateChecker.state.collectAsState()

    // Optional offers can be put away for this session; a mandatory one
    // (min_version says we are too old to be trusted) has no "Later" and
    // therefore nothing to dismiss. A new offer re-arms the dismissal.
    var updateDismissed by remember { mutableStateOf(false) }
    LaunchedEffect(update.availableVersion) { updateDismissed = false }

    val proxyChosen = proxyType == ProxySettings.TYPE_HTTP || proxyType == ProxySettings.TYPE_SOCKS5
    val proxyPortValue = proxyPortText.trim().toIntOrNull()
    val proxyPortOk = proxyPortValue != null && proxyPortValue in 1..65535
    // Save stays off until the proxy validates: half a proxy is worse than
    // none, because the connect would fail deep in the outer transport
    // instead of at a field the user can see.
    val canSave = !proxyChosen || (proxyHost.isNotBlank() && proxyPortOk)

    fun buildSettings(): AppSettings = AppSettings(
        mtu = mtuText.trim().toIntOrNull() ?: 0,
        ipv4Enabled = ipv4On,
        ipv6Enabled = ipv6On,
        dnsOverride = splitList(dnsText),
        autoReconnect = AutoReconnect(
            enabled = reconnectOn,
            initialBackoffMs = backoffInitial.trim().toLongOrNull()?.coerceIn(100, 600_000) ?: 1000L,
            maxBackoffMs = backoffMax.trim().toLongOrNull()?.coerceIn(100, 600_000) ?: 30_000L,
        ),
        proxy = ProxySettings(
            type = if (proxyChosen) proxyType else ProxySettings.TYPE_NONE,
            host = proxyHost.trim(),
            port = if (proxyChosen) proxyPortValue?.coerceIn(1, 65535) ?: 0 else 0,
            username = proxyUser.trim(),
            password = proxyPass,
        ),
        tunnels = tunnels.coerceIn(Profile.MIN_TUNNELS, Profile.MAX_TUNNELS),
        rules = AppRules(mode = ruleMode, packages = selectedPackages),
    )

    AppScaffold(state = state, current = NavTab.SETTINGS, onTab = onTab) { padding ->
        Column(Modifier.fillMaxSize().padding(padding)) {
            SettingsHead(canSave = canSave, onSave = { onSave(buildSettings()) })
            Column(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth()
                    .verticalScroll(rememberScrollState())
                    .padding(horizontal = 16.dp)
                    .padding(top = 12.dp, bottom = 28.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                SectionCard("Updates") {
                    UpdateCheckRow(update = update)
                    if (update.updateAvailable && !(updateDismissed && !update.mandatory)) {
                        Spacer(Modifier.height(12.dp))
                        UpdateCard(offer = update, onLater = { updateDismissed = true })
                    }
                }

                SectionCard("Tunnels") {
                    SectionLabel("Parallel sessions (1–8)")
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        GhostButton(
                            text = "−",
                            onClick = { tunnels = (tunnels - 1).coerceAtLeast(Profile.MIN_TUNNELS) },
                            enabled = tunnels > Profile.MIN_TUNNELS,
                            modifier = Modifier.width(56.dp),
                            height = 44.dp,
                            fontSize = 20.sp,
                        )
                        StepperValue(tunnels.toString())
                        GhostButton(
                            text = "+",
                            onClick = { tunnels = (tunnels + 1).coerceAtMost(Profile.MAX_TUNNELS) },
                            enabled = tunnels < Profile.MAX_TUNNELS,
                            modifier = Modifier.width(56.dp),
                            height = 44.dp,
                            fontSize = 20.sp,
                        )
                    }
                    Spacer(Modifier.height(12.dp))
                    Text(
                        text = "Opens this many parallel SSTP sessions, each with its own inner IP. " +
                            "Concurrent flows are spread across them for aggregate throughput, and each " +
                            "session redials on its own if it drops. 1 = classic single session.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }

                SectionCard("Per-app tunneling") {
                    SectionLabel("Rule")
                    SingleChoiceSegmentedButtonRow(modifier = Modifier.fillMaxWidth()) {
                        SegmentedButton(
                            selected = ruleMode == AppRules.MODE_ALLOW,
                            onClick = { ruleMode = AppRules.MODE_ALLOW },
                            shape = SegmentedButtonDefaults.itemShape(0, 2),
                        ) { Text("Only these apps") }
                        SegmentedButton(
                            selected = ruleMode == AppRules.MODE_DENY,
                            onClick = { ruleMode = AppRules.MODE_DENY },
                            shape = SegmentedButtonDefaults.itemShape(1, 2),
                        ) { Text("All but these apps") }
                    }
                    Spacer(Modifier.height(12.dp))
                    SelectedApps(
                        mode = ruleMode,
                        packages = selectedPackages,
                        onRemove = { pkg -> selectedPackages = selectedPackages - pkg },
                        onChoose = {
                            pickerSelected = selectedPackages
                            pickerQuery = ""
                            pickerOpen = true
                        },
                    )
                }

                SectionCard("Upstream proxy") {
                    SectionLabel("Mode")
                    SingleChoiceSegmentedButtonRow(modifier = Modifier.fillMaxWidth()) {
                        ProxySettings.TYPES.forEachIndexed { index, mode ->
                            SegmentedButton(
                                selected = proxyType == mode,
                                onClick = { proxyType = mode },
                                shape = SegmentedButtonDefaults.itemShape(index, ProxySettings.TYPES.size),
                            ) {
                                Text(PROXY_LABELS[index], maxLines = 1)
                            }
                        }
                    }

                    if (proxyChosen) {
                        Spacer(Modifier.height(12.dp))
                        LabeledField(
                            label = "Proxy host",
                            value = proxyHost,
                            onValueChange = { proxyHost = it },
                            placeholder = "proxy.example.com or 1.2.3.4",
                            isError = proxyChosen && proxyHost.isBlank(),
                            supportingText = if (proxyHost.isBlank()) "Required" else null,
                        )
                        Spacer(Modifier.height(12.dp))
                        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                            LabeledField(
                                label = "Proxy port",
                                value = proxyPortText,
                                onValueChange = { proxyPortText = it.filter(Char::isDigit).take(5) },
                                keyboardType = KeyboardType.Number,
                                modifier = Modifier.weight(1f),
                                isError = proxyChosen && !proxyPortOk,
                                supportingText = if (proxyPortOk) null else "1–65535",
                            )
                            LabeledField(
                                label = "Username (optional)",
                                value = proxyUser,
                                onValueChange = { proxyUser = it },
                                placeholder = "proxy auth",
                                modifier = Modifier.weight(2f),
                            )
                        }
                        Spacer(Modifier.height(12.dp))
                        OutlinedPasswordField(
                            label = "Password (optional)",
                            value = proxyPass,
                            onValueChange = { proxyPass = it },
                            visible = showProxyPassword,
                            onToggleVisible = { showProxyPassword = !showProxyPassword },
                        )
                        Spacer(Modifier.height(6.dp))
                        Text(
                            text = "The outer TCP/TLS transport to the SSTP server goes through this proxy; tunnel credentials stay inside the session.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    } else {
                        Spacer(Modifier.height(6.dp))
                        Text(
                            text = "Direct — the tunnel connects to the server without an intermediary.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }

                SectionCard("Network") {
                    LabeledField(
                        label = "MTU",
                        value = mtuText,
                        onValueChange = { mtuText = it.filter(Char::isDigit).take(4) },
                        keyboardType = KeyboardType.Number,
                        supportingText = "0 = default (1400), clamped 576–1500",
                    )
                    Spacer(Modifier.height(6.dp))
                    SwitchRow(
                        label = "IPv4",
                        description = "Route 0.0.0.0/0 through the tunnel",
                        checked = ipv4On,
                        onCheckedChange = { ipv4On = it },
                    )
                    SwitchRow(
                        label = "IPv6",
                        description = "Route ::/0 only when the server negotiates IPv6",
                        checked = ipv6On,
                        onCheckedChange = { ipv6On = it },
                    )
                    Spacer(Modifier.height(6.dp))
                    LabeledField(
                        label = "DNS override",
                        value = dnsText,
                        onValueChange = { dnsText = it },
                        placeholder = "1.1.1.1, 9.9.9.9",
                        supportingText = "Comma separated; empty uses the server DNS",
                    )
                }

                SectionCard("Auto-reconnect") {
                    SwitchRow(
                        label = "Reconnect automatically",
                        description = "Retry with exponential backoff after a drop",
                        checked = reconnectOn,
                        onCheckedChange = { reconnectOn = it },
                    )
                    if (reconnectOn) {
                        Spacer(Modifier.height(12.dp))
                        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                            LabeledField(
                                label = "Initial backoff (ms)",
                                value = backoffInitial,
                                onValueChange = { backoffInitial = it.filter(Char::isDigit).take(6) },
                                keyboardType = KeyboardType.Number,
                                modifier = Modifier.weight(1f),
                            )
                            LabeledField(
                                label = "Max backoff (ms)",
                                value = backoffMax,
                                onValueChange = { backoffMax = it.filter(Char::isDigit).take(6) },
                                keyboardType = KeyboardType.Number,
                                modifier = Modifier.weight(1f),
                            )
                        }
                    }
                    Spacer(Modifier.height(8.dp))
                    Text(
                        text = "Stops permanently on rejected credentials (auth errors are not retried).",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }

        if (pickerOpen) {
            AppPicker(
                query = pickerQuery,
                onQueryChange = { pickerQuery = it },
                selected = pickerSelected,
                onSelectedChange = { pickerSelected = it },
                onDone = {
                    // Only Done writes back: the persisted schema is still
                    // just a list of package names in rules.packages.
                    selectedPackages = pickerSelected
                    pickerOpen = false
                },
                onDismiss = { pickerOpen = false },
            )
        }
    }
}

/** `.list-head`, as on Profiles: title + subtitle left, the Save button right. */
@Composable
private fun SettingsHead(canSave: Boolean, onSave: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(start = 16.dp, end = 16.dp, top = 16.dp, bottom = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(
                text = "Settings",
                style = TextStyle(fontSize = 20.sp, fontWeight = FontWeight.SemiBold),
                letterSpacing = (-0.2).sp,
            )
            Text(
                text = "App-wide, shared by every profile",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Spacer(Modifier.size(12.dp))
        PrimaryButton(
            text = "Save",
            onClick = onSave,
            enabled = canSave,
            height = 44.dp,
            fontSize = 14.sp,
        )
    }
}

/** Password input with the show/hide eye — the proxy's and the editor's. */
@Composable
private fun OutlinedPasswordField(
    label: String,
    value: String,
    onValueChange: (String) -> Unit,
    visible: Boolean,
    onToggleVisible: () -> Unit,
) {
    OutlinedTextField(
        value = value,
        onValueChange = onValueChange,
        label = { Text(label) },
        singleLine = true,
        visualTransformation = if (visible) VisualTransformation.None
        else PasswordVisualTransformation(),
        trailingIcon = {
            IconButton(onClick = onToggleVisible) {
                Icon(
                    if (visible) AppIcons.EyeOff else AppIcons.Eye,
                    contentDescription = if (visible) "Hide password" else "Show password",
                )
            }
        },
        modifier = Modifier.fillMaxWidth(),
    )
}

/**
 * The update check row — the only thing in the app that can put a request
 * on the wire for a new version. The running version on the left, the
 * button on the right, and every outcome the check can have rendered
 * inline instead of in a toast: "Checking…" while the request runs (the
 * button disabled, because a second tap buys nothing), the offer card
 * below when one exists, a muted "You're on the latest version" that
 * lingers for a few seconds, and one honest line when GitHub cannot be
 * reached.
 *
 * A dev build never makes the request at all: its version name is a
 * placeholder CI did not stamp, so there is nothing on earth to compare a
 * release against.
 */
@Composable
private fun UpdateCheckRow(update: UpdateChecker.Check) {
    val context = LocalContext.current
    val status by UpdateChecker.status.collectAsState()

    // "Up to date" is news, not a state: it earns a few seconds and then
    // the row offers the button again.
    var latest by remember { mutableStateOf(false) }
    LaunchedEffect(status, update.updateAvailable) {
        if (status == UpdateChecker.Status.ANSWERED && !update.updateAvailable) {
            latest = true
            delay(4_000)
            latest = false
        } else {
            latest = false
        }
    }
    var devNote by remember { mutableStateOf(false) }

    Column(Modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = update.versionLabel,
                style = MaterialTheme.typography.labelMedium,
                color = TextMuted,
                maxLines = 1,
            )
            when {
                update.isDev -> if (devNote) {
                    Text(
                        text = "Local build — updates only ship in releases",
                        style = MaterialTheme.typography.bodySmall,
                        color = TextMuted,
                        textAlign = TextAlign.End,
                    )
                } else {
                    GhostButton(text = "Check for updates", onClick = { devNote = true })
                }
                status == UpdateChecker.Status.CHECKING ->
                    GhostButton(text = "Checking…", onClick = {}, enabled = false)
                latest -> Text(
                    text = "You're on the latest version",
                    style = MaterialTheme.typography.bodySmall,
                    color = TextMuted,
                )
                else -> GhostButton(
                    text = "Check for updates",
                    onClick = { UpdateChecker.refresh(context) },
                )
            }
        }
        if (status == UpdateChecker.Status.FAILED) {
            Text(
                text = "could not reach GitHub",
                style = MaterialTheme.typography.bodySmall,
                color = TextMuted,
                textAlign = TextAlign.Center,
                modifier = Modifier.fillMaxWidth().padding(top = 6.dp),
            )
        }
    }
}

/**
 * The update offer — one card under the check row: what is available, and
 * the Install button that downloads the APK, verifies its SHA-256 against
 * the manifest and hands it to the system installer ([UpdateInstaller]
 * does the work; the installer dialog takes over after that, because a
 * sideloaded app can never install silently).
 *
 * Everything the flow can say lives here rather than in a toast: a
 * determinate line while it downloads, "checksum mismatch, not installing"
 * when the bytes do not match, and a Retry that starts over. A mandatory
 * offer ([UpdateChecker.Check.mandatory], set by the manifest's
 * min_version) shows no "Later": when the release says this build is too
 * old to be trusted, the only question left is when.
 */
@Composable
private fun UpdateCard(
    offer: UpdateChecker.Check,
    onLater: () -> Unit,
) {
    val context = LocalContext.current
    val download by UpdateInstaller.state.collectAsState()

    // Coming back from the "install unknown apps" screen resumes the
    // install: the verified APK has been waiting in cacheDir the whole time.
    val permissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.StartActivityForResult(),
    ) {
        if (context.packageManager.canRequestPackageInstalls()) {
            UpdateInstaller.handoff(context)
        }
    }

    val asset = offer.asset
    val install: () -> Unit = {
        if (asset != null) {
            val verified = download is UpdateInstaller.State.Ready
            if (verified && !context.packageManager.canRequestPackageInstalls()) {
                // Route the grant through the launcher so granting it picks
                // the install back up instead of stranding the file.
                permissionLauncher.launch(
                    Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES)
                        .setData(Uri.parse("package:${context.packageName}")),
                )
            } else {
                UpdateInstaller.start(context, asset)
            }
        }
    }

    val downloading = download as? UpdateInstaller.State.Downloading
    val verifying = download is UpdateInstaller.State.Verifying
    val failed = download as? UpdateInstaller.State.Failed
    val busy = downloading != null || verifying
    val fraction = downloading?.fraction
    val headline = when {
        failed != null -> failed.message
        verifying -> "verifying checksum…"
        downloading != null && fraction != null ->
            "downloading… ${(fraction * 100).toInt()}%"
        downloading != null -> "downloading…"
        download is UpdateInstaller.State.Ready -> "v${offer.availableVersion} ready to install"
        else -> "v${offer.availableVersion} available"
    }

    val shape = RoundedCornerShape(14.dp)
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .shadow(8.dp, shape, ambientColor = Color.Black, spotColor = Color.Black)
            .background(Brush.verticalGradient(listOf(Surface2, Surface1)), shape)
            .border(1.dp, Hairline, shape)
            .padding(horizontal = 14.dp, vertical = 12.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(
                    text = headline,
                    style = MaterialTheme.typography.labelLarge,
                    color = if (failed != null) Rose else Color.White,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                    // The headline doubles as the "what's new" link while
                    // there is nothing else to report about the offer.
                    modifier = Modifier.clickable(
                        enabled = offer.notesUrl != null && failed == null && !busy,
                        onClick = { openReleaseNotes(context, offer.notesUrl) },
                    ),
                )
                if (downloading != null) {
                    Spacer(Modifier.height(8.dp))
                    // Determinate by preference: the manifest ships the APK's
                    // byte count, so there is no excuse for a spinner.
                    Box(
                        Modifier
                            .fillMaxWidth()
                            .height(3.dp)
                            .clip(RoundedCornerShape(999.dp))
                            .background(Hairline2),
                    ) {
                        Box(
                            Modifier
                                .fillMaxWidth(fraction ?: 0f)
                                .height(3.dp)
                                .clip(RoundedCornerShape(999.dp))
                                .background(Orange),
                        )
                    }
                }
            }
            if (!offer.mandatory && !busy) {
                GhostButton(text = "Later", onClick = onLater)
                Spacer(Modifier.width(8.dp))
            }
            PrimaryButton(
                text = if (failed != null) "Retry" else "Install",
                onClick = install,
                height = 34.dp,
                fontSize = 13.sp,
                busy = busy,
            )
        }
    }
}

/** "What's new": the release page the manifest points at. */
private fun openReleaseNotes(context: Context, url: String?) {
    if (url.isNullOrBlank()) return
    runCatching { context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url))) }
}
