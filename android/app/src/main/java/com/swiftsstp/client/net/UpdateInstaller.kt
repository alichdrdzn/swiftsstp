package com.swiftsstp.client.net

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import androidx.core.content.FileProvider
import com.swiftsstp.client.VpnController
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest

/**
 * The second half of the update channel: download the APK the manifest
 * offered, verify it against the manifest's `sha256`, and hand it to the
 * system installer.
 *
 * Nothing here is trusted until it is verified. The file lands in
 * `<cacheDir>/update/<name>.part`, is size-checked, SHA-256 checked and
 * only then renamed into place and exposed through the FileProvider — a
 * mismatch is a hard failure and the bytes are deleted, never installed.
 *
 * The handoff is deliberately not silent: a sideloaded app cannot install
 * anything without the user, so the best case is one tap on the system
 * installer's own dialog. When the "install unknown apps" grant is missing
 * we route to Settings instead and keep the verified file, so returning
 * from Settings resumes exactly where it stopped.
 */
object UpdateInstaller {

    /**
     * Where a tap on "Install" currently stands. [Ready] survives the
     * installer dialog being dismissed, so a second tap never re-downloads.
     */
    sealed interface State {
        /** Nothing downloaded for the current offer. */
        data object Idle : State

        /** Bytes in flight; [total] is the manifest's `size` when it knew one. */
        data class Downloading(val received: Long, val total: Long) : State {
            /** 0f..1f, or null when the size is unknown so the UI can indeterminate. */
            val fraction: Float?
                get() = if (total > 0) (received.toFloat() / total).coerceIn(0f, 1f) else null
        }

        /** Digesting the finished file; short, but not instant for a ~14 MB APK. */
        data object Verifying : State

        /** Verified and sitting in cacheDir; one tap from the installer. */
        data class Ready(val file: File, val name: String) : State

        /** Download or verification failed; the message is shown verbatim in the card. */
        data class Failed(val message: String) : State
    }

    val state = MutableStateFlow<State>(State.Idle)

    private const val TIMEOUT_CONNECT_MS = 4000
    private const val TIMEOUT_READ_MS = 15000
    private const val CHUNK = 64 * 1024
    /** Progress granularity: one state write per this many bytes. */
    private const val PROGRESS_STEP = 256 * 1024L
    private const val APK_MIME = "application/vnd.android.package-archive"
    private const val FILE_PROVIDER_SUFFIX = ".fileprovider"
    private val HEX = "0123456789abcdef".toCharArray()

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val main = Handler(Looper.getMainLooper())

    @Volatile
    private var inFlight = false

    /**
     * Start (or finish) the install for [asset]. Called from the UI: an
     * already-verified file is handed off right away, otherwise the
     * download runs in the background and hands itself off when it is
     * verified. Safe to call repeatedly — the download collapses into the
     * one already running.
     */
    fun start(context: Context, asset: UpdateChecker.Asset) {
        val app = context.applicationContext
        when (val current = state.value) {
            is State.Ready ->
                if (current.name == asset.name && current.file.exists()) {
                    handoff(app)
                } else {
                    // A newer release superseded the file we verified.
                    download(app, asset)
                }
            is State.Downloading, State.Verifying -> Unit // already on it
            else -> download(app, asset)
        }
    }

    /**
     * Open the install for a [State.Ready] file, on the main thread:
     * either the "install unknown apps" settings page (the grant is missing)
     * or the system installer itself. Never throws — an OEM without a
     * package installer leaves the state untouched and logs a line.
     */
    fun handoff(context: Context) {
        val app = context.applicationContext
        main.post {
            val ready = state.value as? State.Ready ?: return@post
            if (!ready.file.exists()) {
                fail("download missing — tap Install to fetch it again")
                return@post
            }
            if (!app.packageManager.canRequestPackageInstalls()) {
                // Keep the verified APK: coming back from Settings leaves
                // state at Ready and the next tap resumes here.
                val settings = Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES)
                    .setData(Uri.parse("package:${app.packageName}"))
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                runCatching { app.startActivity(settings) }
                    .onFailure { VpnController.appendLog("update: no settings screen for install permission") }
                return@post
            }
            val uri = FileProvider.getUriForFile(
                app,
                app.packageName + FILE_PROVIDER_SUFFIX,
                ready.file,
            )
            val installer = Intent(Intent.ACTION_VIEW)
                .setDataAndType(uri, APK_MIME)
                .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
            runCatching { app.startActivity(installer) }
                .onFailure { VpnController.appendLog("update: no system installer available") }
        }
    }

    /** Forget the verified file (used when the offer itself changes). */
    fun reset() {
        state.value = State.Idle
    }

    private fun download(app: Context, asset: UpdateChecker.Asset) {
        if (inFlight) return
        inFlight = true
        state.value = State.Downloading(0, asset.size)
        scope.launch {
            val dir = File(app.cacheDir, "update").apply { mkdirs() }
            // Anything older is superseded: one offer, one file.
            dir.listFiles()?.forEach { if (it.name != asset.name) it.delete() }
            val part = File(dir, asset.name + ".part")
            try {
                val total = fetch(asset, part)
                state.value = State.Verifying
                val digest = sha256(part)
                if (!digest.equals(asset.sha256, ignoreCase = true)) {
                    part.delete()
                    fail("checksum mismatch, not installing")
                    return@launch
                }
                val target = File(dir, asset.name)
                target.delete()
                if (!part.renameTo(target)) {
                    part.delete()
                    fail("could not move the download into place")
                    return@launch
                }
                VpnController.appendLog("update: ${asset.name} verified (${formatKb(total)})")
                state.value = State.Ready(target, asset.name)
                handoff(app)
            } catch (e: Exception) {
                part.delete()
                fail("download failed (${e.javaClass.simpleName})")
            } finally {
                inFlight = false
            }
        }
    }

    /** Streams [asset.url] into [part]; returns the bytes written. */
    private fun fetch(asset: UpdateChecker.Asset, part: File): Long {
        val connection = (URL(asset.url).openConnection() as HttpURLConnection).apply {
            connectTimeout = TIMEOUT_CONNECT_MS
            readTimeout = TIMEOUT_READ_MS
            instanceFollowRedirects = true
        }
        return try {
            var received = 0L
            var announced = 0L
            // The CDN's own length wins when present; the manifest's size
            // is the fallback so progress still has a denominator.
            val total = connection.contentLengthLong.takeIf { it > 0 } ?: asset.size
            connection.inputStream.use { input ->
                FileOutputStream(part).use { out ->
                    val buffer = ByteArray(CHUNK)
                    while (true) {
                        val n = input.read(buffer)
                        if (n < 0) break
                        out.write(buffer, 0, n)
                        received += n
                        if (received - announced >= PROGRESS_STEP || (total > 0 && received >= total)) {
                            announced = received
                            state.value = State.Downloading(received, total)
                        }
                    }
                    out.fd.sync()
                }
            }
            // Cheap truncation check before spending time on the digest.
            if (asset.size > 0 && received != asset.size) {
                throw IOException("truncated: $received of ${asset.size} bytes")
            }
            received
        } finally {
            connection.disconnect()
        }
    }

    private fun fail(message: String) {
        state.value = State.Failed(message)
        VpnController.appendLog("update: $message")
    }

    /** Streaming digest — the APK is too big to hold in memory twice. */
    private fun sha256(file: File): String {
        val digest = MessageDigest.getInstance("SHA-256")
        file.inputStream().use { input ->
            val buffer = ByteArray(CHUNK)
            while (true) {
                val n = input.read(buffer)
                if (n < 0) break
                digest.update(buffer, 0, n)
            }
        }
        val bytes = digest.digest()
        val out = CharArray(bytes.size * 2)
        bytes.forEachIndexed { i, b ->
            out[i * 2] = HEX[(b.toInt() shr 4) and 0xF]
            out[i * 2 + 1] = HEX[b.toInt() and 0xF]
        }
        return String(out)
    }

    private fun formatKb(bytes: Long) = "${bytes / 1024} KB"
}
