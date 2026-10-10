package com.swiftsstp.client

import android.Manifest
import android.app.Activity
import android.content.Intent
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
import com.swiftsstp.client.model.Profile
import com.swiftsstp.client.ui.HomeScreen
import com.swiftsstp.client.ui.LogScreen
import com.swiftsstp.client.ui.NavTab
import com.swiftsstp.client.ui.ProfilesScreen
import com.swiftsstp.client.ui.ProfileEditorScreen
import com.swiftsstp.client.ui.StatsScreen
import com.swiftsstp.client.ui.SwiftSSTPTheme

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        AppModel.init(this)
        consumeAutoConnect(intent)
        enableEdgeToEdge()
        setContent {
            SwiftSSTPTheme {
                App()
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        consumeAutoConnect(intent)
    }

    /**
     * The quick-settings tile asks for a connect by launching this
     * activity with [EXTRA_AUTO_CONNECT]. The request becomes a bump on
     * [AppModel.connectTick], which the connect flow in [App] consumes
     * exactly once (a config change must not re-fire it).
     */
    private fun consumeAutoConnect(intent: Intent?) {
        if (intent?.getBooleanExtra(EXTRA_AUTO_CONNECT, false) != true) return
        intent.removeExtra(EXTRA_AUTO_CONNECT)
        AppModel.connectTick.value++
    }

    companion object {
        const val EXTRA_AUTO_CONNECT = "auto_connect"
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
    val connectTick by AppModel.connectTick.collectAsState()

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

    // External connect requests (quick-settings tile): consume the tick
    // before acting so rotation or recomposition cannot repeat them.
    LaunchedEffect(connectTick) {
        if (connectTick == 0) return@LaunchedEffect
        AppModel.connectTick.value = 0
        AppModel.profile(AppModel.selectedId.value)?.let(connect)
    }

    // The bottom bar switches between the four root tabs; the system back
    // gesture only has somewhere to go from the editor.
    val openTab: (NavTab) -> Unit = {
        AppModel.screen.value = when (it) {
            NavTab.HOME -> Screen.Home
            NavTab.PROFILES -> Screen.Profiles
            NavTab.STATS -> Screen.Stats
            NavTab.LOG -> Screen.Log
        }
    }

    BackHandler(enabled = screen is Screen.Editor) {
        AppModel.screen.value = Screen.Profiles
    }

    when (val current = screen) {
        is Screen.Home -> HomeScreen(
            profiles = profiles,
            selectedId = selectedId,
            vpn = vpn,
            onSelect = { AppModel.selectedId.value = it.id },
            onConnect = connect,
            onDisconnect = { VpnController.stop() },
            onNewProfile = { AppModel.screen.value = Screen.Editor(null) },
            onTab = openTab,
        )

        is Screen.Profiles -> ProfilesScreen(
            profiles = profiles,
            usage = usage,
            state = vpn.state,
            onAdd = { AppModel.screen.value = Screen.Editor(null) },
            onEdit = { AppModel.screen.value = Screen.Editor(it.id) },
            onDuplicate = { AppModel.duplicate(it.id) },
            onDelete = { pendingDelete = it },
            // "Use" only selects: the single connect control lives on the
            // Home tab.
            onUse = { profile ->
                AppModel.selectedId.value = profile.id
                AppModel.screen.value = Screen.Home
            },
            onTab = openTab,
        )

        is Screen.Stats -> StatsScreen(
            profiles = profiles,
            selectedId = selectedId,
            usage = usage,
            vpn = vpn,
            onTab = openTab,
        )

        is Screen.Log -> LogScreen(
            state = vpn.state,
            log = log,
            onTab = openTab,
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
