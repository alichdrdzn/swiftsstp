package com.swiftsstp.client.data

import com.swiftsstp.client.model.AppSettings
import com.swiftsstp.client.model.Profile
import org.json.JSONObject
import java.io.File

/**
 * Reads and writes `filesDir/settings.json` — the app-wide half of the
 * configuration, in the shape docs/PROFILE_SCHEMA.md documents. The file
 * is the persistence; [current] is the in-memory copy every connect path
 * merges in, so the UI and the quick-settings tile agree without a disk
 * hit on the connection path.
 *
 * An install that predates the split has no settings.json and its values
 * are buried in profiles.json instead: [load] runs the documented
 * migration once — the first profile (file order) that differs from the
 * defaults in at least one moved field seeds the new file — so an
 * existing install keeps the values it had configured rather than
 * silently reverting to the defaults.
 */
object SettingsStore {

    /** The settings a connect merges in; seeded by [load], replaced by [save]. */
    @Volatile
    var current: AppSettings = AppSettings()
        private set

    @Volatile
    private var loaded = false

    /**
     * Load exactly once per process, whichever caller gets there first (the
     * UI at launch, the tile service in its own process, the first connect).
     * Idempotent and free after that: a connect must not re-read a file.
     */
    @Synchronized
    fun ensureLoaded(dir: File) {
        if (!loaded) load(dir)
    }

    /**
     * settings.json → [AppSettings]; a missing or unreadable file runs the
     * migration from profiles.json and is persisted immediately, so the
     * next load reads what this one decided instead of migrating twice.
     */
    @Synchronized
    fun load(dir: File): AppSettings {
        val settings = read(dir) ?: migrate(dir).also { write(dir, it) }
        current = settings
        loaded = true
        return settings
    }

    /**
     * Apply a save in memory without touching the disk. The tunnel path
     * reads [current], so a connect pressed right after the Settings tab's
     * Save must already see the new values instead of racing the IO write.
     */
    @Synchronized
    fun set(settings: AppSettings) {
        current = settings
        loaded = true
    }

    /** Replace the in-memory copy and persist it; callers keep using [current]. */
    @Synchronized
    fun save(dir: File, settings: AppSettings) {
        current = settings
        write(dir, settings)
    }

    /** settings.json → model, or null when there is nothing usable to read. */
    private fun read(dir: File): AppSettings? {
        val f = File(dir, "settings.json")
        if (!f.exists()) return null
        return runCatching { AppSettings.fromJson(JSONObject(f.readText())) }.getOrNull()
    }

    /**
     * The schema's migration: seed from the first profile in profiles.json
     * (file order) whose moved fields differ from the defaults; when none
     * does, write the defaults. Old profiles.json files still carry the
     * moved keys — [Profile.fromJson] reads them, and from this point on no
     * writer puts them back.
     */
    private fun migrate(dir: File): AppSettings =
        ProfileStore.load(dir)
            .firstOrNull { it.appSettings().differsFromDefaults() }
            ?.appSettings()
            ?: AppSettings()

    /** Atomic, like ProfileStore: a crash mid-write must not truncate the config. */
    private fun write(dir: File, settings: AppSettings) {
        runCatching {
            val body = settings.toJson().toString(2)
            val f = File(dir, "settings.json")
            val tmp = File(dir, "settings.json.tmp")
            tmp.writeText(body)
            if (!tmp.renameTo(f)) f.writeText(body)
        }
    }
}
