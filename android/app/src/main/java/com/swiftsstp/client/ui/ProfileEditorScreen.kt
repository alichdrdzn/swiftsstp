package com.swiftsstp.client.ui

import android.content.Intent
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.border
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
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
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
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.swiftsstp.client.model.AppRules
import com.swiftsstp.client.model.AutoReconnect
import com.swiftsstp.client.model.Profile
import com.swiftsstp.client.model.ProxySettings
import java.io.File
import java.io.FileOutputStream

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
    var packagesText by remember { mutableStateOf(initial.rules.packages.joinToString("\n")) }
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
        rules = AppRules(mode = ruleMode, packages = splitList(packagesText)),
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
                OutlinedTextField(
                    value = packagesText,
                    onValueChange = { packagesText = it },
                    label = { Text("Package names") },
                    placeholder = { Text("com.example.one\ncom.example.two") },
                    modifier = Modifier.fillMaxWidth(),
                    minLines = 3,
                    supportingText = { Text("One per line or comma separated; empty = whole device") },
                )
            }
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
