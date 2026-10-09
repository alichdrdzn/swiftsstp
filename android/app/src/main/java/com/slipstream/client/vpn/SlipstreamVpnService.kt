package com.slipstream.client.vpn

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Intent
import android.content.pm.ServiceInfo
import android.net.VpnService
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.ParcelFileDescriptor
import com.slipstream.client.AppModel
import com.slipstream.client.R
import com.slipstream.client.VpnController
import com.slipstream.client.data.UsageStore
import com.slipstream.client.model.AppRules
import com.slipstream.client.model.Profile
import org.json.JSONObject
import slipstream.mobile.Client
import slipstream.mobile.Mobile
import slipstream.mobile.Protector
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.time.LocalTime
import java.time.format.DateTimeFormatter
import java.util.concurrent.CountDownLatch

/**
 * Foreground VpnService that owns the tunnel lifecycle:
 *
 *  1. build the gomobile client, handing it [protect] so the engine socket
 *     escapes the TUN it is carrying;
 *  2. establish the TUN from the negotiated [Stats];
 *  3. bridge packets — TUN reads into `writeIP`, `readIP` into TUN writes;
 *  4. on failure, reconnect with exponential backoff per the profile, but
 *     stop permanently on an authentication error;
 *  5. flush lifetime usage to `usage-stats.json` periodically and at teardown.
 */
class SlipstreamVpnService : VpnService() {

    companion object {
        const val ACTION_CONNECT = "com.slipstream.client.action.CONNECT"
        const val ACTION_DISCONNECT = "com.slipstream.client.action.DISCONNECT"
        const val EXTRA_PROFILE_JSON = "profile_json"

        private const val NOTIF_ID = 7
        private const val CHANNEL_ID = "slipstream_vpn"
        private const val CONNECT_TIMEOUT_MS = 20_000L
        private const val KEEPALIVE_SEC = 30
        private const val USAGE_FLUSH_MS = 10_000L
        private const val MIN_BACKOFF_MS = 500L

        private val TIME_FMT = DateTimeFormatter.ofPattern("HH:mm:ss")

        @Volatile
        private var instance: SlipstreamVpnService? = null

        fun isRunning(): Boolean = instance != null

        fun requestStop() {
            instance?.postStop()
        }
    }

    private val handler = Handler(Looper.getMainLooper())

    @Volatile private var active = false
    @Volatile private var profile: Profile? = null
    @Volatile private var client: Client? = null
    private var worker: Thread? = null

    private var tunPfd: ParcelFileDescriptor? = null
    private var tunIn: FileInputStream? = null
    private var tunOut: FileOutputStream? = null

    // Live session accounting (worker writes, ticker reads).
    @Volatile private var sessionUp = 0L
    @Volatile private var sessionDown = 0L
    private var rateUp = 0L
    private var rateDown = 0L
    private var lastSampleUp = 0L
    private var lastSampleDown = 0L
    private var lastSampleAt = 0L

    // How much of the current session has already reached usage-stats.json.
    private var flushedUp = 0L
    private var flushedDown = 0L
    private var lastFlushAt = 0L

    private var engineLinesConsumed = 0

    // ---- lifecycle -----------------------------------------------------

    override fun onCreate() {
        super.onCreate()
        instance = this
        AppModel.init(this)
        createChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_CONNECT -> {
                val json = intent.getStringExtra(EXTRA_PROFILE_JSON)
                val parsed = runCatching { Profile.fromJson(JSONObject(json ?: "")) }.getOrNull()
                if (parsed == null || parsed.host.isBlank()) {
                    appendLog("start requested without a usable profile")
                    stopEverything()
                    return START_NOT_STICKY
                }
                handleConnect(parsed)
            }
            ACTION_DISCONNECT -> handleDisconnect()
        }
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        active = false
        handler.removeCallbacksAndMessages(null)
        runCatching { client?.close() }
        closeTun()
        if (instance === this) instance = null
        super.onDestroy()
    }

    /** Called from the UI thread through [requestStop]. */
    fun postStop() {
        handler.post { handleDisconnect() }
    }

    // ---- commands ------------------------------------------------------

    private fun handleConnect(p: Profile) {
        startInForeground("Preparing tunnel…")
        handler.removeCallbacks(ticker)
        handler.postDelayed(ticker, 1000L)

        if (active) {
            appendLog("connect ignored: a session is already managed")
            return
        }
        if (VpnController.snapshot.value.profileId != p.id) VpnController.clearLog()

        active = true
        profile = p
        sessionUp = 0; sessionDown = 0; rateUp = 0; rateDown = 0
        lastSampleUp = 0; lastSampleDown = 0; lastSampleAt = System.currentTimeMillis()
        flushedUp = 0; flushedDown = 0; lastFlushAt = System.currentTimeMillis()
        engineLinesConsumed = 0

        VpnController.update {
            it.copy(
                state = VpnController.State.CONNECTING,
                profileId = p.id,
                message = "Connecting to ${p.host}:${p.port}…",
                authError = false,
                up = 0, down = 0, upRate = 0, downRate = 0,
                localIp = "", peerIp = "", dns = emptyList(), mtu = 0,
                certSha256 = "", ipv6Negotiated = false,
                reconnectAttempt = 0,
            )
        }
        appendLog("connecting to ${p.host}:${p.port} (${p.tlsModeLabel()})")

        worker = Thread({ runLoop(p) }, "slipstream-loop").apply {
            isDaemon = true
            start()
        }
    }

    private fun handleDisconnect() {
        if (!active && client == null) {
            stopEverything()
            return
        }
        appendLog("disconnect requested")
        active = false
        // Unblocks readIP and the TUN reader; the worker flushes usage,
        // closes both ends and stops the service.
        runCatching { client?.close() }
        closeTun()
        if (worker?.isAlive != true) stopEverything()
    }

    private fun stopEverything() {
        active = false
        handler.removeCallbacks(ticker)
        runCatching { client?.close() }
        closeTun()
        VpnController.update {
            if (it.state == VpnController.State.ERROR) it
            else it.copy(
                state = VpnController.State.DISCONNECTED,
                message = "Idle",
                authError = false,
                upRate = 0, downRate = 0, reconnectAttempt = 0,
            )
        }
        runCatching { stopForeground(STOP_FOREGROUND_REMOVE) }
        stopSelf()
    }

    // ---- the tunnel loop ----------------------------------------------

    private fun runLoop(p: Profile) {
        var backoff = p.autoReconnect.initialBackoffMs.coerceAtLeast(MIN_BACKOFF_MS)
        val maxBackoff = p.autoReconnect.maxBackoffMs.coerceAtLeast(backoff)
        var attempt = 0
        try {
            while (active) {
                attempt++
                setState(VpnController.State.CONNECTING, "Connecting to ${p.host}:${p.port}…")
                VpnController.update { it.copy(reconnectAttempt = attempt) }

                val c = buildClient(p)
                client = c
                engineLinesConsumed = 0

                val connected = try {
                    c.connect(CONNECT_TIMEOUT_MS)
                    true
                } catch (t: Throwable) {
                    val msg = t.message?.trim().orEmpty().ifBlank { t.toString() }
                    val auth = runCatching { c.isAuthError() }.getOrDefault(false)
                    appendLog("connect failed: $msg")
                    pumpEngineLog(c)
                    runCatching { c.close() }
                    client = null
                    if (auth) {
                        appendLog("stopping: authentication errors are not retried")
                        setState(
                            VpnController.State.ERROR,
                            "Authentication failed — check username and password",
                            authError = true,
                        )
                        break
                    }
                    if (!active) break
                    if (!p.autoReconnect.enabled) {
                        setState(VpnController.State.ERROR, msg)
                        break
                    }
                    setState(VpnController.State.RECONNECTING, "Retrying in ${fmtMs(backoff)} — $msg")
                    if (!sleepWhileActive(backoff)) break
                    backoff = (backoff * 2).coerceAtMost(maxBackoff)
                    continue
                }
                if (!connected) break

                val stats = parseStats(runCatching { c.statsJSON() }.getOrDefault("") ?: "")
                val pfd = establishTun(p, stats)
                if (pfd == null) {
                    appendLog("could not establish the TUN device")
                    runCatching { c.close() }
                    client = null
                    setState(VpnController.State.ERROR, "Could not establish the VPN interface")
                    break
                }

                // Session established: counters and backoff reset.
                backoff = p.autoReconnect.initialBackoffMs.coerceAtLeast(MIN_BACKOFF_MS)
                sessionUp = 0; sessionDown = 0
                lastSampleUp = 0; lastSampleDown = 0
                lastSampleAt = System.currentTimeMillis()
                flushedUp = 0; flushedDown = 0; lastFlushAt = System.currentTimeMillis()
                UsageStore.recordSession(p.id)
                AppModel.onUsageChanged()

                VpnController.update {
                    it.copy(
                        state = VpnController.State.CONNECTED,
                        message = "Connected — ${stats.localIp} → ${stats.peerIp}",
                        authError = false,
                        localIp = stats.localIp, peerIp = stats.peerIp,
                        dns = stats.dns, mtu = stats.mtu,
                        certSha256 = stats.cert, ipv6Negotiated = stats.ipv6,
                        reconnectAttempt = 0,
                    )
                }
                appendLog("tunnel up: local ${stats.localIp} peer ${stats.peerIp} dns ${stats.dns} mtu ${stats.mtu}")
                if (stats.cert.isNotBlank()) appendLog("server cert sha256 ${stats.cert}")
                pumpEngineLog(c)
                updateNotification("Connected · ${stats.localIp}")

                bridge(c, pfd)

                // Teardown: read final counters before the client goes away.
                val finalStats = parseStats(runCatching { c.statsJSON() }.getOrDefault("") ?: "")
                flushUsage(p.id, finalStats, System.currentTimeMillis())
                appendLog("tunnel down: ${finalStats.up} ↑ / ${finalStats.down} ↓ bytes")
                closeTun()
                runCatching { c.close() }
                client = null

                if (!active) break
                if (!p.autoReconnect.enabled) {
                    setState(VpnController.State.ERROR, "Tunnel dropped")
                    break
                }
                setState(VpnController.State.RECONNECTING, "Tunnel dropped — retrying in ${fmtMs(backoff)}")
                if (!sleepWhileActive(backoff)) break
                backoff = (backoff * 2).coerceAtMost(maxBackoff)
            }
        } catch (t: Throwable) {
            appendLog("session loop failed: ${t.message}")
            setState(VpnController.State.ERROR, t.message ?: "Unexpected failure")
        } finally {
            client?.let { c ->
                flushUsage(p.id, parseStats(runCatching { c.statsJSON() }.getOrDefault("") ?: ""), System.currentTimeMillis())
            }
            active = false
            stopEverything()
        }
    }

    /** Returns false when the stop was requested during the wait. */
    private fun sleepWhileActive(ms: Long): Boolean {
        val end = System.currentTimeMillis() + ms
        while (active && System.currentTimeMillis() < end) {
            try {
                Thread.sleep(minOf(200L, end - System.currentTimeMillis()))
            } catch (_: InterruptedException) {
                return false
            }
        }
        return active
    }

    private fun buildClient(p: Profile): Client {
        val ca = if (p.tlsMode == Profile.TLS_CUSTOM_CA && p.caPath.isNotBlank()) {
            runCatching { File(p.caPath).readBytes() }.getOrDefault(ByteArray(0))
        } else {
            ByteArray(0)
        }
        val mode = when (p.tlsMode) {
            Profile.TLS_VERIFY -> 0L
            Profile.TLS_CUSTOM_CA -> 2L
            else -> 1L
        }
        val c = Mobile.newClient(
            p.host, p.port.toLong(), p.sni, p.username, p.password,
            mode, ca, p.mtu.toLong(), KEEPALIVE_SEC.toLong(),
        )
        // The fd bridge: every engine socket is excluded from VPN routing.
        c.setProtector(Protector { fd -> protect(fd.toInt()) })
        return c
    }

    // ---- TUN -----------------------------------------------------------

    private data class Stats(
        val up: Long,
        val down: Long,
        val localIp: String,
        val peerIp: String,
        val dns: List<String>,
        val mtu: Int,
        val localIp6: String,
        val ipv6: Boolean,
        val cert: String,
    )

    private fun parseStats(json: String): Stats {
        val o = runCatching { JSONObject(json) }.getOrDefault(JSONObject())
        val arr = o.optJSONArray("dns")
        val dns = buildList {
            if (arr != null) for (i in 0 until arr.length()) {
                val v = arr.optString(i).trim()
                if (v.isNotEmpty()) add(v)
            }
        }
        return Stats(
            up = o.optLong("up", 0),
            down = o.optLong("down", 0),
            localIp = o.optString("local_ip"),
            peerIp = o.optString("peer_ip"),
            dns = dns,
            mtu = o.optInt("mtu", 1400),
            localIp6 = o.optString("local_ip6"),
            ipv6 = o.optBoolean("ipv6_negotiated", false),
            cert = o.optString("cert_sha256"),
        )
    }

    private fun establishTun(p: Profile, stats: Stats): ParcelFileDescriptor? {
        return try {
            val builder = Builder()
            builder.setSession(p.name.ifBlank { "Slipstream" })
            builder.setMtu(if (stats.mtu in 576..1500) stats.mtu else 1400)
            builder.setBlocking(true)

            if (stats.localIp.isNotBlank()) {
                builder.addAddress(stats.localIp, if (stats.localIp.contains(":")) 128 else 32)
            }
            // Whole-device IPv4 by default; the IPv6 default route only
            // appears when the server actually negotiated IPv6 and the
            // profile asked for it.
            if (p.ipv4Enabled && stats.localIp.isNotBlank() && !stats.localIp.contains(":")) {
                builder.addRoute("0.0.0.0", 0)
            }
            if (p.ipv6Enabled && stats.ipv6 && stats.localIp6.isNotBlank()) {
                builder.addAddress(stats.localIp6, 128)
                builder.addRoute("::", 0)
            }

            val dns = if (p.dnsOverride.isNotEmpty()) p.dnsOverride else stats.dns
            dns.forEach { server ->
                runCatching { builder.addDnsServer(server) }
                    .onFailure { appendLog("ignoring DNS server '$server': ${it.message}") }
            }

            applyAppRules(builder, p)
            builder.establish()
        } catch (t: Throwable) {
            appendLog("TUN setup failed: ${t.message}")
            null
        }
    }

    private fun applyAppRules(builder: Builder, p: Profile) {
        val packages = p.rules.packages
        if (packages.isEmpty()) return // whole device
        val deny = p.rules.mode == AppRules.MODE_DENY
        packages.forEach { pkg ->
            try {
                if (deny) builder.addDisallowedApplication(pkg)
                else builder.addAllowedApplication(pkg)
            } catch (_: Exception) {
                appendLog("per-app rule skipped, package not installed: $pkg")
            }
        }
        appendLog("per-app rules: ${if (deny) "exclude" else "only"} ${packages.joinToString(", ")}")
    }

    // ---- packet bridge -------------------------------------------------

    private fun bridge(c: Client, pfd: ParcelFileDescriptor) {
        tunPfd = pfd
        val input = FileInputStream(pfd.fileDescriptor)
        val output = FileOutputStream(pfd.fileDescriptor)
        tunIn = input
        tunOut = output

        // One count: whichever side dies first ends the session, and the
        // caller then closes both ends to release the other thread.
        val dead = CountDownLatch(1)

        // Engine -> TUN: ReadIP blocks until a packet or closure.
        val toTun = Thread({
            try {
                while (active) {
                    val pkt = c.readIP() ?: break
                    output.write(pkt)
                }
            } catch (t: Throwable) {
                if (active) appendLog("TUN write failed: ${t.message}")
            } finally {
                dead.countDown()
            }
        }, "slip-to-tun")

        // TUN -> engine: one kernel packet in, one inner packet out.
        val fromTun = Thread({
            try {
                val buf = ByteArray(65535)
                while (active) {
                    val n = input.read(buf)
                    if (n < 0) break
                    if (n == 0) continue
                    c.writeIP(buf.copyOf(n))
                }
            } catch (t: Throwable) {
                if (active) appendLog("TUN read failed: ${t.message}")
            } finally {
                dead.countDown()
            }
        }, "slip-from-tun")

        toTun.isDaemon = true
        fromTun.isDaemon = true
        toTun.start()
        fromTun.start()

        // Either side failing is enough to end the session; the caller
        // closes both ends, which releases whichever thread is still in a
        // blocking call.
        try {
            dead.await()
        } catch (_: InterruptedException) {
            Thread.currentThread().interrupt()
        }
        closeTun()
    }

    private fun closeTun() {
        runCatching { tunIn?.close() }
        runCatching { tunOut?.close() }
        runCatching { tunPfd?.close() }
        tunIn = null
        tunOut = null
        tunPfd = null
    }

    // ---- usage, stats and log -----------------------------------------

    private fun flushUsage(id: String, stats: Stats, now: Long) {
        val du = stats.up - flushedUp
        val dd = stats.down - flushedDown
        lastFlushAt = now
        if (du <= 0 && dd <= 0) return
        flushedUp = stats.up
        flushedDown = stats.down
        UsageStore.addBytes(id, du, dd)
        AppModel.onUsageChanged()
    }

    private val ticker = object : Runnable {
        override fun run() {
            val c = client
            val p = profile
            if (active && c != null && p != null) {
                val s = parseStats(runCatching { c.statsJSON() }.getOrDefault("") ?: "")
                val now = System.currentTimeMillis()
                val dt = (now - lastSampleAt).coerceAtLeast(1L)
                if (s.up >= lastSampleUp) rateUp = (s.up - lastSampleUp) * 1000 / dt
                if (s.down >= lastSampleDown) rateDown = (s.down - lastSampleDown) * 1000 / dt
                lastSampleUp = s.up
                lastSampleDown = s.down
                lastSampleAt = now
                sessionUp = s.up
                sessionDown = s.down
                pumpEngineLog(c)

                val connected = VpnController.snapshot.value.state == VpnController.State.CONNECTED
                if (connected) {
                    if (now - lastFlushAt >= USAGE_FLUSH_MS) flushUsage(p.id, s, now)
                    VpnController.update {
                        it.copy(up = s.up, down = s.down, upRate = rateUp, downRate = rateDown)
                    }
                    updateNotification("↓ ${fmtRate(rateDown)}  ↑ ${fmtRate(rateUp)}")
                }
            }
            if (active) handler.postDelayed(this, 1000L)
        }
    }

    /**
     * The engine keeps its own bounded ring of timestamped lines. Only the
     * ones not yet forwarded are appended, so the UI sees one chronological
     * stream for the whole session.
     */
    private fun pumpEngineLog(c: Client?) {
        if (c == null) return
        val text = runCatching { c.logTail() }.getOrDefault("")
        if (text.isBlank()) return
        val lines = text.split("\n").filter { it.isNotBlank() }
        if (lines.size < engineLinesConsumed) engineLinesConsumed = 0
        for (i in engineLinesConsumed until lines.size) VpnController.appendLog(lines[i])
        engineLinesConsumed = lines.size
    }

    private fun setState(state: VpnController.State, message: String, authError: Boolean = false) {
        VpnController.update { it.copy(state = state, message = message, authError = authError) }
        updateNotification(message)
    }

    private fun appendLog(msg: String) {
        VpnController.appendLog("${LocalTime.now().format(TIME_FMT)}  $msg")
    }

    // ---- notification --------------------------------------------------

    private fun createChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val mgr = getSystemService(NotificationManager::class.java)
            val channel = NotificationChannel(
                CHANNEL_ID,
                getString(R.string.notification_channel_vpn),
                NotificationManager.IMPORTANCE_LOW,
            ).apply {
                description = getString(R.string.notification_channel_vpn_desc)
                setShowBadge(false)
            }
            mgr.createNotificationChannel(channel)
        }
    }

    private fun buildNotification(text: String): Notification {
        val builder = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            Notification.Builder(this, CHANNEL_ID)
        } else {
            @Suppress("DEPRECATION")
            Notification.Builder(this)
        }
        return builder
            .setSmallIcon(R.drawable.ic_stat_vpn)
            .setContentTitle(getString(R.string.app_name))
            .setContentText(text)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .build()
    }

    private fun startInForeground(text: String) {
        val n = buildNotification(text)
        // Android 14+ demands a type in the call; the platform has no "vpn"
        // type, so this uses specialUse with the VPN subtype declared in the
        // manifest. Below 34 the two-argument form reads the manifest type.
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            startForeground(NOTIF_ID, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE)
        } else {
            @Suppress("DEPRECATION")
            startForeground(NOTIF_ID, n)
        }
    }

    private fun updateNotification(text: String) {
        runCatching {
            getSystemService(NotificationManager::class.java)
                .notify(NOTIF_ID, buildNotification(text))
        }
    }

    // ---- formatting ----------------------------------------------------

    private fun fmtMs(ms: Long): String =
        if (ms >= 1000) "%.1fs".format(ms / 1000.0) else "${ms}ms"

    private fun fmtRate(bytesPerSec: Long): String = when {
        bytesPerSec >= 1_048_576 -> "%.1f MB/s".format(bytesPerSec / 1_048_576.0)
        bytesPerSec >= 1024 -> "%.0f KB/s".format(bytesPerSec / 1024.0)
        else -> "$bytesPerSec B/s"
    }
}
