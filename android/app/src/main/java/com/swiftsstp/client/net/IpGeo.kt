package com.swiftsstp.client.net

import android.os.SystemClock
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import java.io.BufferedReader
import java.io.IOException
import java.io.InputStreamReader
import java.net.HttpURLConnection
import java.net.URL

/**
 * Public egress IP + country, from Cloudflare's `cdn-cgi/trace` endpoint:
 * plain text `key=value` lines, of which we only need `ip=` and `loc=`.
 *
 * Why this works without a "am I connected" dance: only the engine's own
 * sockets are `protect()`ed (see SwiftSSTPVpnService), so *this app's* plain
 * sockets ride the tunnel once it is up — the lookup returns the tunnel's
 * egress IP while connected and the device's home IP while not. Edge case to
 * keep in mind: if the profile (or the user, in system settings) excludes this
 * package from the VPN — `VpnService.addDisallowedApplication()` — the request
 * bypasses the tunnel and reports the home IP even while "connected".
 *
 * Never throws to the UI: every failure keeps the last good [state].
 */
object IpGeo {

    /** One lookup result; [country] is the ISO code from `loc=` (may be blank). */
    data class Result(val ip: String, val country: String)

    /** Last successful lookup, kept forever so the card never flashes empty. */
    val state = MutableStateFlow<Result?>(null)

    private const val TRACE_URL = "https://www.cloudflare.com/cdn-cgi/trace"
    private const val TIMEOUT_MS = 4000
    /** Floor between two HTTP attempts, so bursts of triggers cost one request. */
    private const val MIN_INTERVAL_MS = 2000L

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    @Volatile
    private var lastAttemptAt = 0L

    @Volatile
    private var inFlight = false

    /**
     * Refresh in the background. Safe to call from anywhere (UI, effects,
     * state observers): overlapping or too-early calls are collapsed into the
     * request already running.
     */
    fun refresh() {
        val now = SystemClock.elapsedRealtime()
        if (inFlight || now - lastAttemptAt < MIN_INTERVAL_MS) return
        lastAttemptAt = now
        inFlight = true
        scope.launch {
            try {
                fetch()?.let { state.value = it }
            } catch (_: Exception) {
                // Keep the cached value — an offline or blocked lookup is not
                // worth a toast.
            } finally {
                inFlight = false
            }
        }
    }

    /** Runs on the caller's IO dispatcher. Returns null when nothing usable came back. */
    private fun fetch(): Result? {
        val connection = URL(TRACE_URL).openConnection() as HttpURLConnection
        return try {
            connection.connectTimeout = TIMEOUT_MS
            connection.readTimeout = TIMEOUT_MS
            connection.instanceFollowRedirects = true
            val body = BufferedReader(InputStreamReader(connection.inputStream, Charsets.UTF_8))
                .use { it.readText() }
            parse(body)
        } catch (_: IOException) {
            null
        } catch (_: Exception) {
            null
        } finally {
            connection.disconnect()
        }
    }

    /** `ip=1.2.3.4\nloc=DE\n...` → [Result]; no `ip=` line means no result. */
    internal fun parse(body: String): Result? {
        var ip = ""
        var loc = ""
        for (line in body.lineSequence()) {
            val eq = line.indexOf('=')
            if (eq <= 0) continue
            when (line.substring(0, eq).trim()) {
                "ip" -> ip = line.substring(eq + 1).trim()
                "loc" -> loc = line.substring(eq + 1).trim()
            }
        }
        return if (ip.isBlank()) null else Result(ip, loc)
    }

    /**
     * Regional-indicator flag emoji for a 2-letter ISO country code:
     * "DE" → 🇩🇪. Blank or unknown codes return an empty string.
     */
    fun flagEmoji(country: String): String {
        val code = country.trim().uppercase()
        if (code.length != 2 || code[0] !in 'A'..'Z' || code[1] !in 'A'..'Z') return ""
        val base = 0x1F1E6 - 'A'.code // 'A' maps to the first regional indicator
        return String(Character.toChars(code[0].code + base)) +
            String(Character.toChars(code[1].code + base))
    }
}
