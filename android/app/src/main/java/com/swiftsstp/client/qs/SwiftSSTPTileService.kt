package com.swiftsstp.client.qs

import android.app.PendingIntent
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.net.VpnService
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.service.quicksettings.Tile
import android.service.quicksettings.TileService
import com.swiftsstp.client.AppModel
import com.swiftsstp.client.MainActivity
import com.swiftsstp.client.R
import com.swiftsstp.client.VpnController
import com.swiftsstp.client.vpn.SwiftSSTPVpnService
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch

/**
 * Quick-settings tile in the notification shade: one tap toggles the
 * tunnel on and off without opening the app.
 *
 * Disconnect is a plain in-process stop and always works. Connect has to
 * clear two system gates before it may start the VpnService directly:
 * the one-time VPN consent dialog (only the app UI can show it) and the
 * Android 12+ foreground-service background-start restriction — broken
 * specifically for TileService.onClick on Android 14 devices (fixed in
 * 15). Whenever a gate blocks, the tile falls back to opening
 * [MainActivity] with [MainActivity.EXTRA_AUTO_CONNECT], which turns
 * the request into the normal in-app connect flow (consent dialog
 * included).
 */
class SwiftSSTPTileService : TileService() {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private val handler = Handler(Looper.getMainLooper())
    private var listenJob: Job? = null

    override fun onCreate() {
        super.onCreate()
        // The system may bind only this service (fresh process, panel
        // opened) — the profile list has to be loaded before onClick can
        // read the selected one.
        AppModel.init(this)
    }

    override fun onStartListening() {
        super.onStartListening()
        // Mirror live tunnel state into the tile for as long as it is
        // visible; every snapshot while listening re-renders.
        listenJob?.cancel()
        listenJob = scope.launch {
            VpnController.snapshot.collect { render(it) }
        }
    }

    override fun onStopListening() {
        listenJob?.cancel()
        listenJob = null
        super.onStopListening()
    }

    override fun onDestroy() {
        handler.removeCallbacksAndMessages(null)
        scope.cancel()
        super.onDestroy()
    }

    override fun onClick() {
        super.onClick()
        val s = VpnController.snapshot.value
        if (SwiftSSTPVpnService.isRunning() || s.active) {
            VpnController.stop()
            // The stop is asynchronous — the service tears itself down on
            // its own handler thread — so the snapshot still reads
            // CONNECTED here. Drop the light immediately instead of
            // re-rendering that stale value.
            forceInactive()
            return
        }
        // Connecting may need the consent dialog; on a locked device the
        // system asks the user to unlock first.
        if (isLocked) {
            unlockAndRun { connect() }
        } else {
            connect()
        }
    }

    private fun connect() {
        val profile = AppModel.profile(AppModel.selectedId.value)
        if (profile == null) {
            // No profile to dial: the app UI is the only place to make one.
            openApp(autoConnect = false)
            return
        }
        // Consent for the VPN interface is one-time, but until it is
        // granted only an activity can ask for it.
        if (VpnService.prepare(this) != null) {
            openApp(autoConnect = true)
            return
        }
        try {
            VpnController.start(this, profile)
        } catch (_: Exception) {
            // Background FGS start rejected (Android 14 tile bug); an
            // activity makes it a foreground start, which is allowed.
            openApp(autoConnect = true)
        }
    }

    /**
     * Collapses the panel and opens the app. The old Intent overload of
     * [startActivityAndCollapse] was replaced by a PendingIntent one in
     * API 34; both are required while minSdk < 34.
     */
    private fun openApp(autoConnect: Boolean) {
        val intent = Intent(this, MainActivity::class.java)
            .addFlags(
                Intent.FLAG_ACTIVITY_NEW_TASK or
                    Intent.FLAG_ACTIVITY_CLEAR_TOP or
                    Intent.FLAG_ACTIVITY_SINGLE_TOP,
            )
            .putExtra(MainActivity.EXTRA_AUTO_CONNECT, autoConnect)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            val pi = PendingIntent.getActivity(
                this,
                if (autoConnect) RC_OPEN_CONNECT else RC_OPEN,
                intent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
            )
            startActivityAndCollapse(pi)
        } else {
            @Suppress("DEPRECATION")
            startActivityAndCollapse(intent)
        }
    }

    /**
     * Mirrors the tunnel state into the tile.
     *
     * Only the snapshot decides — never [SwiftSSTPVpnService.isRunning].
     * The service object outlives the tunnel for a few milliseconds after
     * the session settles (`stopEverything()` requests the refresh before
     * `stopSelf()` reaches `onDestroy()`), so OR-ing `isRunning()` in made
     * the final refresh paint STATE_ACTIVE with nothing left to correct
     * it: the tile then stayed lit long after the VPN was down.
     */
    private fun render(s: VpnController.Snapshot) {
        val tile = qsTile ?: return
        tile.state = if (s.active) Tile.STATE_ACTIVE else Tile.STATE_INACTIVE
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            tile.subtitle = when (s.state) {
                VpnController.State.CONNECTED -> getString(R.string.tile_connected)
                VpnController.State.CONNECTING,
                VpnController.State.RECONNECTING,
                -> getString(R.string.tile_connecting)
                VpnController.State.ERROR -> getString(R.string.tile_error)
                VpnController.State.DISCONNECTED -> getString(R.string.tile_disconnected)
            }
        }
        tile.updateTile()
    }

    /**
     * Optimistic "off" for a stop the snapshot has not reported yet.
     *
     * A stop is a request, not a guarantee: [RECONCILE_MS] later the
     * snapshot is read again, so a teardown that never happened cannot
     * leave the tile dark over a tunnel that is in fact still up.
     */
    private fun forceInactive() {
        val tile = qsTile ?: return
        tile.state = Tile.STATE_INACTIVE
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            tile.subtitle = getString(R.string.tile_disconnected)
        }
        tile.updateTile()
        handler.removeCallbacks(reconcile)
        handler.postDelayed(reconcile, RECONCILE_MS)
    }

    private val reconcile = Runnable { render(VpnController.snapshot.value) }

    companion object {
        private const val RC_OPEN = 0
        private const val RC_OPEN_CONNECT = 1
        /** Grace before an optimistic "off" is reconciled with the snapshot. */
        private const val RECONCILE_MS = 2500L

        /**
         * Called by the VPN service on state changes so a visible tile
         * re-renders even if a snapshot emission raced past its listener.
         * A no-op when the user never added the tile.
         */
        fun requestRefresh(context: Context) {
            runCatching {
                requestListeningState(
                    context,
                    ComponentName(context, SwiftSSTPTileService::class.java),
                )
            }
        }
    }
}
