package com.slipstream.client

import android.Manifest
import android.app.Activity
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.core.content.ContextCompat
import com.slipstream.client.model.Profile
import com.slipstream.client.ui.ConnectionScreen
import com.slipstream.client.ui.ProfilesScreen
import com.slipstream.client.ui.ProfileEditorScreen
import com.slipstream.client.ui.SlipstreamTheme

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        AppModel.init(this)
        enableEdgeToEdge()
        setContent {
            SlipstreamTheme {
                App()
            }
        }
    }
}

@Composable
private fun App() {
    val screen by AppModel.screen.collectAsState()
    val profiles by AppModel.profiles.collectAsState()
    val usage by AppModel.usage.collectAsState()
    val selectedId by AppModel.selectedId.collectAsState()
    val vpn by VpnController.snapshot.collectAsState()
    val log by VpnController.log.collectAsState()

    val context = LocalContext.current

    // Profile awaiting the system's VPN consent dialog, if any.
    var pendingProfile by remember { mutableStateOf<Profile?>(null) }
    var pendingDelete by remember { mutableStateOf<Profile?>(null) }

    val vpnConsent = rememberLauncherForActivityResult(
        ActivityResultContracts.StartActivityForResult(),
    ) { result ->
        if (result.resultCode == Activity.RESULT_OK) {
            pendingProfile?.let { VpnController.start(context, it) }
        }
        pendingProfile = null
    }

    val notificationPermission = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission(),
    ) { /* the foreground service still runs either way */ }

    val connect: (Profile) -> Unit = { profile ->
        val prepare = VpnController.prepare(context)
        if (prepare != null) {
            pendingProfile = profile
            vpnConsent.launch(prepare)
        } else {
            VpnController.start(context, profile)
        }
    }

    LaunchedEffect(Unit) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            val granted = ContextCompat.checkSelfPermission(
                context, Manifest.permission.POST_NOTIFICATIONS,
            ) == PackageManager.PERMISSION_GRANTED
            if (!granted) notificationPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
        }
    }

    BackHandler(enabled = screen !is Screen.Profiles) {
        AppModel.screen.value = Screen.Profiles
    }

    when (val current = screen) {
        is Screen.Profiles -> ProfilesScreen(
            profiles = profiles,
            usage = usage,
            onAdd = { AppModel.screen.value = Screen.Editor(null) },
            onEdit = { AppModel.screen.value = Screen.Editor(it.id) },
            onDuplicate = { AppModel.duplicate(it.id) },
            onDelete = { pendingDelete = it },
            onUse = { profile ->
                AppModel.selectedId.value = profile.id
                AppModel.screen.value = Screen.Connection
                connect(profile)
            },
        )

        is Screen.Editor -> {
            val initial = AppModel.profile(current.profileId) ?: Profile.blank()
            ProfileEditorScreen(
                initial = initial,
                onBack = { AppModel.screen.value = Screen.Profiles },
                onSave = { profile ->
                    AppModel.save(profile)
                    AppModel.screen.value = Screen.Profiles
                },
            )
        }

        is Screen.Connection -> ConnectionScreen(
            profiles = profiles,
            selectedId = selectedId,
            usage = usage,
            vpn = vpn,
            log = log,
            onSelect = { AppModel.selectedId.value = it.id },
            onConnect = connect,
            onDisconnect = { VpnController.stop() },
            onNewProfile = { AppModel.screen.value = Screen.Editor(null) },
        )
    }

    pendingDelete?.let { target ->
        AlertDialog(
            onDismissRequest = { pendingDelete = null },
            containerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
            title = { Text("Delete profile?") },
            text = {
                Text(
                    "\"${target.name.ifBlank { target.host }}\" and its lifetime usage " +
                        "will be removed from this device. This cannot be undone.",
                )
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        pendingDelete = null
                        AppModel.delete(target.id)
                    },
                ) { Text("Delete") }
            },
            dismissButton = {
                TextButton(onClick = { pendingDelete = null }) { Text("Cancel") }
            },
        )
    }
}
