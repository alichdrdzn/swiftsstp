package com.swiftsstp.client.net

import android.content.Context
import android.content.SharedPreferences
import android.os.SystemClock
import com.swiftsstp.client.BuildConfig
import com.swiftsstp.client.VpnController
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import org.json.JSONObject
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL

/**
 * Stable-channel update check against `update-manifest.json`
 * (docs/UPDATE_SCHEMA.md). Mirrors [IpGeo]'s shape: an object with a
 * [state] StateFlow that never throws to the UI, debounced so bursts of
 * triggers cost one request, and cached so the answer survives a restart.
 *
 * Why the manifest and not the GitHub API: `releases/latest/download/…` is
 * a redirect into the release-asset CDN, needs no token and has no
 * per-IP rate limit (the REST API's 60 unauthenticated requests per hour
 * would be exhausted in minutes behind one NAT). The response carries an
 * `etag`, so revalidation with `If-None-Match` costs a 304 and no body.
 *
 * Rules this enforces (see docs/UPDATE_SCHEMA.md):
 *  - a build CI did not stamp ("dev") has no comparable version and must
 *    never claim an update exists;
 *  - only a strictly newer `version` is offered, compared as semver and
 *    never as `version_code`;
 *  - a manifest we cannot understand (wrong schema/channel, garbage) keeps
 *    the last known state instead of clearing a valid earlier answer;
 *  - the check lives off the connection path entirely — the tunnel never
 *    waits on GitHub, and an unreachable GitHub disturbs nothing but one
 *    line in the log.
 */
object UpdateChecker {

    /** One release artifact for this platform, as the manifest describes it. */
    data class Asset(
        val name: String,
        val url: String,
        /** Lowercase hex SHA-256 copied from `SHA256SUMS`; the download is verified against it. */
        val sha256: String,
        /** Bytes, for progress and a cheap truncation check. */
        val size: Long,
    )

    /**
     * The checker's answer. [availableVersion] and [asset] are non-null
     * together: an update is only ever offered together with the artifact
     * to install, so a release that ships no Android APK reads as "no
     * update" rather than as an error.
     */
    data class Check(
        val currentVersion: String,
        val isDev: Boolean,
        val availableVersion: String? = null,
        val notesUrl: String? = null,
        val asset: Asset? = null,
        /** Set when `min_version` in the manifest is newer than our build. */
        val mandatory: Boolean = false,
    ) {
        val updateAvailable: Boolean get() = availableVersion != null

        /** What the top bar shows: "v0.2.2", or "dev" for unstamped builds. */
        val versionLabel: String get() = if (isDev) "dev" else "v$currentVersion"
    }

    /** Manifest format version this client understands; anything else is rejected. */
    private const val SCHEMA = 1
    private const val CHANNEL = "stable"

    /**
     * Tried in order; the first one that answers wins. Today there is only
     * GitHub, but the list is what makes adding a mirror a config change
     * and not a release (docs/UPDATE_SCHEMA.md, "Adding a mirror").
     */
    private val MANIFEST_URLS = listOf(
        "https://github.com/alichdrdzn/swiftsstp/releases/latest/download/update-manifest.json",
    )

    private const val TIMEOUT_MS = 4000
    /** Floor between two attempts, so bursts of triggers cost one request. */
    private const val MIN_INTERVAL_MS = 2000L
    /** Refresh rhythm once started: the schema asks for 6–12 hours. */
    private const val REFRESH_INTERVAL_MS = 8L * 60 * 60 * 1000
    /** Failure backoff: 30s doubling to 15 min, so a dead network never spins. */
    private const val BACKOFF_BASE_MS = 30_000L
    private const val BACKOFF_MAX_MS = 15L * 60 * 1000

    private const val PREFS = "update"
    private const val KEY_ETAG = "etag"
    private const val KEY_BODY = "manifest"

    /**
     * The defaultConfig fallbacks in build.gradle.kts. CI stamps
     * versionCode with epoch days (see .github/workflows/release.yml), so
     * versionCode 1 + "1.0.0" can only mean "built outside CI" — and such a
     * build has no version comparable to a release.
     */
    private const val LOCAL_VERSION_CODE = 1
    private const val LOCAL_VERSION_NAME = "1.0.0"

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    /** Current answer, seeded with the running build so the UI is never null. */
    val state = MutableStateFlow(Check(BuildConfig.VERSION_NAME, isDevBuild()))

    @Volatile
    private var prefs: SharedPreferences? = null

    @Volatile
    private var lastAttemptAt = 0L

    @Volatile
    private var blockedUntil = 0L

    @Volatile
    private var inFlight = false

    private var failures = 0

    /**
     * Wire up and check: reads the cached manifest back into [state] so a
     * relaunch shows the badge before the network answers, fires the first
     * check, and arms the 8-hour refresh. Idempotent — MainActivity and its
     * recreations all end up here once.
     */
    @Synchronized
    fun start(context: Context) {
        val app = context.applicationContext
        if (prefs == null) {
            prefs = app.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            prefs?.getString(KEY_BODY, null)?.let { replay(it) }
            scope.launch {
                while (isActive) {
                    delay(REFRESH_INTERVAL_MS)
                    refresh()
                }
            }
        }
        refresh()
    }

    /**
     * Refresh in the background. Safe to call from anywhere (UI, effects,
     * state observers): overlapping or too-early calls are collapsed into
     * the request already running, and repeated failure pushes the next
     * attempt out instead of retrying in a tight loop.
     */
    fun refresh() {
        val now = SystemClock.elapsedRealtime()
        if (inFlight || now < blockedUntil || now - lastAttemptAt < MIN_INTERVAL_MS) return
        lastAttemptAt = now
        inFlight = true
        scope.launch {
            try {
                fetch()
                failures = 0
            } catch (e: Exception) {
                failures++
                blockedUntil = SystemClock.elapsedRealtime() +
                    (BACKOFF_BASE_MS shl (failures - 1).coerceAtMost(5)).coerceAtMost(BACKOFF_MAX_MS)
                // Last known state is kept: an offline or blocked device is
                // not worth anything louder than one log line.
                VpnController.appendLog("update: check failed (${e.javaClass.simpleName})")
            } finally {
                inFlight = false
            }
        }
    }

    /**
     * Runs on the caller's IO dispatcher. Throws on anything that should
     * back off (network, HTTP status); returns normally when the manifest
     * was handled, including "no update".
     */
    private fun fetch() {
        val prefs = this.prefs ?: return
        val cachedBody = prefs.getString(KEY_BODY, null)
        val cachedEtag = prefs.getString(KEY_ETAG, null)
        var open: HttpURLConnection? = null
        try {
            for (url in MANIFEST_URLS) {
                val connection = (URL(url).openConnection() as HttpURLConnection).apply {
                    connectTimeout = TIMEOUT_MS
                    readTimeout = TIMEOUT_MS
                    instanceFollowRedirects = true
                    // The 304 dance below is ours; the platform cache would
                    // answer with a body we never asked for.
                    useCaches = false
                    if (cachedEtag != null) setRequestProperty("If-None-Match", cachedEtag)
                }
                open = connection
                when (val code = connection.responseCode) {
                    HttpURLConnection.HTTP_NOT_MODIFIED -> {
                        val body = cachedBody ?: run {
                            // An etag without a body means we lost our own
                            // cache; drop the etag and refetch next time.
                            prefs.edit().remove(KEY_ETAG).apply()
                            return
                        }
                        applyManifest(body)
                        return
                    }
                    in 200..299 -> {
                        val body = connection.inputStream
                            .bufferedReader(Charsets.UTF_8)
                            .use { it.readText() }
                        prefs.edit()
                            .putString(KEY_ETAG, connection.getHeaderField("ETag"))
                            .putString(KEY_BODY, body)
                            .apply()
                        applyManifest(body)
                        return
                    }
                    else -> {
                        // Not fatal on its own: the next mirror may answer.
                        connection.disconnect()
                        open = null
                    }
                }
            }
            throw IOException("no manifest mirror answered")
        } finally {
            open?.disconnect()
        }
    }

    /**
     * Re-evaluate a manifest body we already trust enough to parse: bad
     * input keeps the last known state, a good one replaces it (including
     * "no update" — a newer release that ships no Android APK must clear
     * an older offer).
     */
    private fun applyManifest(body: String) {
        val parsed = parse(body) ?: run {
            VpnController.appendLog("update: manifest rejected (schema $SCHEMA $CHANNEL only)")
            return
        }
        val next = evaluate(parsed)
        if (next.availableVersion != state.value.availableVersion) {
            if (next.availableVersion != null) {
                VpnController.appendLog("update: v${next.availableVersion} available")
            }
        }
        state.value = next
    }

    /** Same evaluation for a body read back from the prefs cache. */
    private fun replay(body: String) {
        parse(body)?.let { state.value = evaluate(it) }
    }

    /** Pure decision step — the only place an offer is created or withdrawn. */
    private fun evaluate(parsed: Parsed): Check {
        val current = Check(BuildConfig.VERSION_NAME, isDevBuild())
        // A dev build compares against nothing: its versionName is a
        // placeholder, not a release.
        if (current.isDev) return current
        // Not an error: this release simply has no artifact for Android.
        val asset = parsed.android ?: return current
        val cmp = compareVersions(current.currentVersion, parsed.version) ?: return current
        // Equal or newer than us: already up to date. Never trust
        // version_code for this — it is advisory only.
        if (cmp >= 0) return current
        val mandatory = parsed.minVersion?.let { min ->
            val c = compareVersions(current.currentVersion, min)
            c != null && c < 0
        } ?: false
        return current.copy(
            availableVersion = parsed.version,
            notesUrl = parsed.notesUrl,
            asset = asset,
            mandatory = mandatory,
        )
    }

    /** True for builds CI did not stamp — see [LOCAL_VERSION_CODE]. */
    internal fun isDevBuild(): Boolean =
        BuildConfig.VERSION_CODE == LOCAL_VERSION_CODE && BuildConfig.VERSION_NAME == LOCAL_VERSION_NAME

    /** Manifest body → [Parsed], or null when it is not a manifest we know. */
    internal fun parse(body: String): Parsed? {
        return try {
            val root = JSONObject(body)
            if (root.optInt("schema", -1) != SCHEMA) return null
            if (root.optString("channel") != CHANNEL) return null
            val version = root.optString("version")
            if (version.isBlank() || versionParts(version) == null) return null
            val android = root.optJSONObject("assets")?.optJSONObject("android")?.let { asset ->
                val url = asset.optString("url")
                val sha256 = asset.optString("sha256")
                // No url, or a digest we could not verify against, means we
                // must not offer the download at all.
                if (url.isBlank() || !sha256.matches(HEX64)) null
                else Asset(asset.optString("name"), url, sha256, asset.optLong("size"))
            }
            Parsed(
                version = version,
                notesUrl = root.optString("notes_url").ifBlank { null },
                minVersion = root.optString("min_version").ifBlank { null },
                android = android,
            )
        } catch (_: Exception) {
            null
        }
    }

    /** A manifest parsed far enough to matter; [android] may be absent. */
    internal data class Parsed(
        val version: String,
        val notesUrl: String?,
        val minVersion: String?,
        val android: Asset?,
    )

    /**
     * Semver-ish compare of dotted numeric versions: -1 when [a] < [b], 0
     * when equal, 1 when newer, null when either side is not comparable
     * (a prerelease tag, a garbage string). Anything not comparable yields
     * "no update", which is the safe direction.
     */
    internal fun compareVersions(a: String, b: String): Int? {
        val pa = versionParts(a) ?: return null
        val pb = versionParts(b) ?: return null
        for (i in 0 until maxOf(pa.size, pb.size)) {
            val d = (pa.getOrElse(i) { 0 }) - (pb.getOrElse(i) { 0 })
            if (d != 0) return if (d > 0) 1 else -1
        }
        return 0
    }

    /** "1.2.3" → [1, 2, 3]; "v1.2.3-rc.1" → null (we do not compare prereleases). */
    private fun versionParts(v: String): List<Int>? {
        val trimmed = v.trim().removePrefix("v")
        if (trimmed.isEmpty() || trimmed.any { it in "-+" }) return null
        return trimmed.split('.').map { it.toIntOrNull() ?: return null }
    }

    private val HEX64 = Regex("[0-9a-fA-F]{64}")
}
