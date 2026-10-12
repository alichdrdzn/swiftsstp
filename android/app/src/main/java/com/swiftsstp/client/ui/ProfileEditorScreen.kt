package com.swiftsstp.client.ui

import android.content.Intent
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.swiftsstp.client.model.Profile
import java.io.File
import java.io.FileOutputStream

private val TLS_MODES = listOf(Profile.TLS_VERIFY, Profile.TLS_INSECURE, Profile.TLS_CUSTOM_CA)
private val TLS_LABELS = listOf("Verify", "Insecure", "Custom CA")

/**
 * Editor for one profile: the two things that belong to a single server —
 * the Server card (address, SNI, credentials) and the TLS card
 * (certificate mode and CA bundle). Raw text states are folded into the
 * model on save.
 *
 * Everything app-wide (tunnels, per-app routing, upstream proxy, MTU and
 * address families, DNS, auto-reconnect) moved to the Settings tab, so
 * none of it is edited or stored here: the certificate mode is the one
 * connection option the schema keeps per profile, because which CA a
 * server is verified against is a property of that server. The only Save
 * affordance lives in the top bar.
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
    var caError by remember { mutableStateOf<String?>(null) }

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

    // A profile needs a host to dial; nothing else gates the save now that
    // the app-wide options live (and validate) on the Settings tab.
    val canSave = draft.host.isNotBlank()

    fun buildProfile(): Profile = draft.copy(
        port = portText.trim().toIntOrNull()?.coerceIn(1, 65535) ?: 443,
    )

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
                } else {
                    Spacer(Modifier.height(6.dp))
                    Text(
                        text = when (draft.tlsMode) {
                            Profile.TLS_VERIFY ->
                                "The server certificate must chain to a system-trusted CA and name this host."
                            else ->
                                "No certificate validation — the tunnel encrypts but does not authenticate the server."
                        },
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }
    }
}
