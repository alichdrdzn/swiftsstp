package com.sstpflash.client

import android.content.Context
import android.content.Intent
import android.net.VpnService
import androidx.core.content.ContextCompat
import com.sstpflash.client.model.Profile
import com.sstpflash.client.vpn.SstpflashVpnService
import kotlinx.coroutines.flow.MutableStateFlow

/**
 * The single source of truth the connection screen renders.
 * The service writes it from its worker thread; Compose collects it.
 */
object VpnController {

    enum class State { DISCONNECTED, CONNECTING, CONNECTED, RECONNECTING, ERROR }

    /**
     * One row of the bundle's per-session stats (from Bundle.StatsJSON):
     * session index, inner address, counters and whether it currently
     * carries traffic.
     */
    data class SessionStat(
        val index: Int,
        val localIp: String,
        val up: Long,
        val down: Long,
        val ready: Boolean,
    )

    data class Snapshot(
        val state: State = State.DISCONNECTED,
        val profileId: String? = null,
        val message: String = "Idle",
        val authError: Boolean = false,
        val up: Long = 0,
        val down: Long = 0,
        val upRate: Long = 0,
        val downRate: Long = 0,
        val localIp: String = "",
        val peerIp: String = "",
        val dns: List<String> = emptyList(),
        val mtu: Int = 0,
        val certSha256: String = "",
        val ipv6Negotiated: Boolean = false,
        val reconnectAttempt: Int = 0,
        /** Profile's parallel-session count for the live connection. */
        val tunnels: Int = 1,
        /** "HTTP host:port" / "SOCKS5 host:port" when the profile proxies. */
        val proxyLabel: String = "",
        /** Per-session rows; empty on the single-session (Client) path. */
        val sessions: List<SessionStat> = emptyList(),
    ) {
        val active: Boolean
            get() = state == State.CONNECTING || state == State.CONNECTED || state == State.RECONNECTING
    }

    val snapshot = MutableStateFlow(Snapshot())
    val log = MutableStateFlow<List<String>>(emptyList())

    private val logLock = Any()
    private const val LOG_CAP = 600

    fun appendLog(line: String) {
        synchronized(logLock) {
            val next = log.value + line
            log.value = if (next.size > LOG_CAP) next.takeLast(LOG_CAP) else next
        }
    }

    fun clearLog() {
        synchronized(logLock) { log.value = emptyList() }
    }

    fun update(transform: (Snapshot) -> Snapshot) {
        snapshot.value = transform(snapshot.value)
    }

    /**
     * Ask for the VPN consent dialog if the system requires one, then start
     * the foreground service. Returns false when consent is still pending,
     * in which case the caller must call [onConsentResult] after the user
     * answers.
     */
    fun prepare(context: Context): Intent? = VpnService.prepare(context)

    fun start(context: Context, profile: Profile) {
        val intent = Intent(context, SstpflashVpnService::class.java)
            .setAction(SstpflashVpnService.ACTION_CONNECT)
            .putExtra(SstpflashVpnService.EXTRA_PROFILE_JSON, profile.toJson().toString())
        ContextCompat.startForegroundService(context, intent)
    }

    fun startAfterConsent(context: Context, profile: Profile) = start(context, profile)

    fun stop() {
        if (SstpflashVpnService.isRunning()) {
            SstpflashVpnService.requestStop()
        } else {
            // No service to talk to: settle the UI rather than leaving a
            // stale "connected" chip on screen.
            update {
                it.copy(
                    state = State.DISCONNECTED,
                    message = "Idle",
                    authError = false,
                    upRate = 0,
                    downRate = 0,
                    reconnectAttempt = 0,
                    tunnels = 1,
                    proxyLabel = "",
                    sessions = emptyList(),
                )
            }
        }
    }
}
