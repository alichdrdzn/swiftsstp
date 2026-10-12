package com.swiftsstp.client.ui

import android.content.Context
import android.content.Intent
import android.content.pm.ApplicationInfo
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Image
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
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.Checkbox
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.listSaver
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.core.graphics.drawable.toBitmap
import com.swiftsstp.client.model.AppRules
import com.swiftsstp.client.model.AutoReconnect
import com.swiftsstp.client.model.Profile
import com.swiftsstp.client.model.ProxySettings
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream
import java.util.concurrent.ConcurrentHashMap

private val TLS_MODES = listOf(Profile.TLS_VERIFY, Profile.TLS_INSECURE, Profile.TLS_CUSTOM_CA)
private val TLS_LABELS = listOf("Verify", "Insecure", "Custom CA")
private val PROXY_LABELS = listOf("None", "HTTP CONNECT", "SOCKS5")

/**
 * Editor for one profile; raw text states are folded into the model on save.
 *
 * Card order mirrors the Windows editor: Server (credentials included),
 * TLS (certificate mode, CA, MTU, address families, DNS), Auto-reconnect,
 * Upstream proxy, Tunnels (Windows-style stepper) and Per-app tunneling.
 * The only Save affordance lives in the top bar.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ProfileEditorScreen(
    initial: Profile,
    onBack: () -> Unit,
    onSave: (Profile) -> Unit,
) {
    var draft by remember { mutableStateOf(initial) }
    var showPassword by remember { mutableStateOf(false) }
    var portText by remember { mutableStateOf(initial.port.toString()) }
    var mtuText by remember { mutableStateOf(initial.mtu.toString()) }
    var dnsText by remember { mutableStateOf(initial.dnsOverride.joinToString(", ")) }
    var reconnectOn by remember { mutableStateOf(initial.autoReconnect.enabled) }
    var backoffInitial by remember { mutableStateOf(initial.autoReconnect.initialBackoffMs.toString()) }
    var backoffMax by remember { mutableStateOf(initial.autoReconnect.maxBackoffMs.toString()) }
    var ruleMode by remember { mutableStateOf(initial.rules.mode) }
    // Per-app selection, saveable: the picker's dialog window has no
    // saved-state owner of its own, so a rotation would drop a
    // half-finished multi-select anywhere but here.
    var selectedPackages by rememberSaveable(stateSaver = StringListSaver) {
        mutableStateOf(initial.rules.packages)
    }
    var pickerOpen by rememberSaveable { mutableStateOf(false) }
    var pickerQuery by rememberSaveable { mutableStateOf("") }
    var pickerSelected by rememberSaveable(stateSaver = StringListSaver) {
        mutableStateOf(initial.rules.packages)
    }
    var caError by remember { mutableStateOf<String?>(null) }

    // Upstream proxy: raw text states, folded into the model on save.
    var proxyType by remember { mutableStateOf(initial.proxy.type) }
    var proxyHost by remember { mutableStateOf(initial.proxy.host) }
    var proxyPortText by remember { mutableStateOf(if (initial.proxy.port > 0) initial.proxy.port.toString() else "") }
    var proxyUser by remember { mutableStateOf(initial.proxy.username) }
    var proxyPass by remember { mutableStateOf(initial.proxy.password) }
    var showProxyPassword by remember { mutableStateOf(false) }
    var tunnels by remember { mutableStateOf(initial.tunnels.coerceIn(Profile.MIN_TUNNELS, Profile.MAX_TUNNELS)) }

    val context = LocalContext.current
    val filesDir = context.applicationContext.filesDir

    val caPicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) {
            runCatching {
                context.contentResolver.takePersistableUriPermission(
                    uri, Intent.FLAG_GRANT_READ_URI_PERMISSION,
                )
                val dir = File(filesDir, "ca").apply { mkdirs() }
                val dest = File(dir, "${draft.id}.pem")
                context.contentResolver.openInputStream(uri)?.use { input ->
                    FileOutputStream(dest).use { output -> input.copyTo(output) }
                }
                draft = draft.copy(caPath = dest.absolutePath)
                caError = null
            }.onFailure { caError = it.message ?: "could not read the selected file" }
        }
    }

    val proxyChosen = proxyType == ProxySettings.TYPE_HTTP || proxyType == ProxySettings.TYPE_SOCKS5
    val proxyPortValue = proxyPortText.trim().toIntOrNull()
    val proxyPortOk = proxyPortValue != null && proxyPortValue in 1..65535
    val proxyValid = !proxyChosen || (proxyHost.isNotBlank() && proxyPortOk)

    fun buildProfile(): Profile = draft.copy(
        port = portText.trim().toIntOrNull()?.coerceIn(1, 65535) ?: 443,
        mtu = mtuText.trim().toIntOrNull() ?: 0,
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

    val canSave = draft.host.isNotBlank() && proxyValid

    Scaffold(
        containerColor = MaterialTheme.colorScheme.background,
        topBar = {
            TopAppBar(
                title = {
                    Text(
                        text = if (initial.name.isBlank() && initial.host.isBlank()) {
                            "New profile"
                        } else {
                            "Edit profile"
                        },
                        style = MaterialTheme.typography.titleLarge,
                    )
                },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                },
                actions = {
                    PrimaryButton(
                        text = "Save",
                        onClick = { onSave(buildProfile()) },
                        enabled = canSave,
                        height = 36.dp,
                        fontSize = 14.sp,
                        modifier = Modifier.padding(end = 12.dp),
                    )
                },
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
                .padding(horizontal = 16.dp)
                .padding(top = 12.dp, bottom = 28.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            SectionCard("Server") {
                LabeledField(
                    label = "Profile name",
                    value = draft.name,
                    onValueChange = { draft = draft.copy(name = it) },
                    placeholder = "Office VPN",
                )
                Spacer(Modifier.height(12.dp))
                LabeledField(
                    label = "Host",
                    value = draft.host,
                    onValueChange = { draft = draft.copy(host = it) },
                    placeholder = "vpn.example.com or 1.2.3.4",
                    isError = !canSave && draft.host.isBlank(),
                    supportingText = if (canSave) null else "Required",
                )
                Spacer(Modifier.height(12.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    LabeledField(
                        label = "Port",
                        value = portText,
                        onValueChange = { portText = it.filter(Char::isDigit).take(5) },
                        keyboardType = KeyboardType.Number,
                        modifier = Modifier.weight(1f),
                        supportingText = "0 = 443",
                    )
                    LabeledField(
                        label = "SNI",
                        value = draft.sni,
                        onValueChange = { draft = draft.copy(sni = it) },
                        placeholder = "defaults to host",
                        modifier = Modifier.weight(2f),
                    )
                }
                Spacer(Modifier.height(12.dp))
                LabeledField(
                    label = "Username",
                    value = draft.username,
                    onValueChange = { draft = draft.copy(username = it) },
                    placeholder = "account name",
                )
                Spacer(Modifier.height(12.dp))
                OutlinedTextField(
                    value = draft.password,
                    onValueChange = { draft = draft.copy(password = it) },
                    label = { Text("Password") },
                    singleLine = true,
                    visualTransformation = if (showPassword) VisualTransformation.None
                    else PasswordVisualTransformation(),
                    trailingIcon = {
                        IconButton(onClick = { showPassword = !showPassword }) {
                            Icon(
                                if (showPassword) AppIcons.EyeOff else AppIcons.Eye,
                                contentDescription = if (showPassword) "Hide password" else "Show password",
                            )
                        }
                    },
                    modifier = Modifier.fillMaxWidth(),
                )
            }

            SectionCard("TLS") {
                SectionLabel("Certificate mode")
                SingleChoiceSegmentedButtonRow(modifier = Modifier.fillMaxWidth()) {
                    TLS_MODES.forEachIndexed { index, mode ->
                        SegmentedButton(
                            selected = draft.tlsMode == mode,
                            onClick = { draft = draft.copy(tlsMode = mode) },
                            shape = SegmentedButtonDefaults.itemShape(index, TLS_MODES.size),
                        ) {
                            Text(TLS_LABELS[index], maxLines = 1)
                        }
                    }
                }

                if (draft.tlsMode == Profile.TLS_CUSTOM_CA) {
                    Spacer(Modifier.height(12.dp))
                    SectionLabel("CA bundle (PEM)")
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(12.dp),
                    ) {
                        OutlinedButton(
                            onClick = { caPicker.launch(arrayOf("*/*")) },
                            modifier = Modifier.weight(1f),
                        ) {
                            Icon(AppIcons.Folder, contentDescription = null, modifier = Modifier.size(18.dp))
                            Spacer(Modifier.size(8.dp))
                            Text(
                                text = draft.caPath.substringAfterLast('/').ifBlank { "Choose file" },
                                maxLines = 1,
                            )
                        }
                    }
                    if (caError != null) {
                        Text(
                            text = caError!!,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.error,
                            modifier = Modifier.padding(top = 6.dp),
                        )
                    } else if (draft.caPath.isNotBlank()) {
                        Text(
                            text = "Copied to app storage · verification uses only this bundle",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(top = 6.dp),
                        )
                    }
                }

                Spacer(Modifier.height(12.dp))
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
                    checked = draft.ipv4Enabled,
                    onCheckedChange = { draft = draft.copy(ipv4Enabled = it) },
                )
                SwitchRow(
                    label = "IPv6",
                    description = "Route ::/0 only when the server negotiates IPv6",
                    checked = draft.ipv6Enabled,
                    onCheckedChange = { draft = draft.copy(ipv6Enabled = it) },
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
                    OutlinedTextField(
                        value = proxyPass,
                        onValueChange = { proxyPass = it },
                        label = { Text("Password (optional)") },
                        singleLine = true,
                        visualTransformation = if (showProxyPassword) VisualTransformation.None
                        else PasswordVisualTransformation(),
                        trailingIcon = {
                            IconButton(onClick = { showProxyPassword = !showProxyPassword }) {
                                Icon(
                                    if (showProxyPassword) AppIcons.EyeOff else AppIcons.Eye,
                                    contentDescription = if (showProxyPassword) "Hide password" else "Show password",
                                )
                            }
                        },
                        modifier = Modifier.fillMaxWidth(),
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

/** The read-out of the Windows-style stepper in the Tunnels card. */
@Composable
private fun StepperValue(value: String) {
    val shape = RoundedCornerShape(10.dp)
    Box(
        modifier = Modifier
            .width(96.dp)
            .height(44.dp)
            .clip(shape)
            .background(FieldBackground)
            .border(1.dp, Hairline2, shape),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = value,
            style = TextStyle(fontSize = 16.sp, fontWeight = FontWeight.Bold),
            color = TextPrimary,
        )
    }
}

@Composable
private fun SwitchRow(
    label: String,
    description: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(label, style = MaterialTheme.typography.bodyLarge)
            Text(
                description,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Switch(checked = checked, onCheckedChange = onCheckedChange)
    }
}

@Composable
private fun LabeledField(
    label: String,
    value: String,
    onValueChange: (String) -> Unit,
    modifier: Modifier = Modifier,
    placeholder: String? = null,
    keyboardType: KeyboardType = KeyboardType.Text,
    isError: Boolean = false,
    supportingText: String? = null,
) {
    OutlinedTextField(
        value = value,
        onValueChange = onValueChange,
        label = { Text(label) },
        placeholder = placeholder?.let { { Text(it) } },
        singleLine = true,
        isError = isError,
        supportingText = supportingText?.let { { Text(it) } },
        keyboardOptions = KeyboardOptions(keyboardType = keyboardType),
        colors = OutlinedTextFieldDefaults.colors(
            focusedBorderColor = MaterialTheme.colorScheme.primary,
        ),
        modifier = modifier.fillMaxWidth(),
    )
}

/** "a, b ,c" -> [a, b, c]; blank entries dropped. */
internal fun splitList(raw: String): List<String> =
    raw.split(',', '\n', '\t', ' ')
        .map { it.trim() }
        .filter { it.isNotEmpty() }
        .distinct()

/** The package-name list the schema stores, through the saveable round trip. */
private val StringListSaver = listSaver<List<String>, String>(save = { it }, restore = { it })

/** One installed app as the picker rows show it; [info] carries the icon. */
private data class InstalledApp(val pkg: String, val label: String, val info: ApplicationInfo)

/** Icons, keyed by package name: decoded once per process, off the main thread. */
private val iconCache = ConcurrentHashMap<String, ImageBitmap>()

/** Labels, keyed by package name: the picker fills it, the editor's chips read it. */
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
private fun SelectedApps(
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
 * Selection accumulates here and only reaches the profile on Done; the
 * pending set and the query live in the editor's saveable state rather than
 * in this dialog, whose window has no saved-state owner to restore it from
 * when the phone is rotated mid-choice.
 */
@Composable
private fun AppPicker(
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
        properties = DialogProperties(usePlatformDefaultWidth = false),
    ) {
        Surface(
            modifier = Modifier.padding(16.dp).fillMaxSize(),
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
