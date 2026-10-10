package com.sstpflash.client.data

import org.json.JSONObject
import java.io.File
import java.time.Instant
import java.time.temporal.ChronoUnit

/** One row of `usage-stats.json` → `servers.<id>`. */
data class UsageEntry(
    val totalUp: Long = 0,
    val totalDown: Long = 0,
    val sessions: Int = 0,
    val firstUsed: String = "",
    val lastUsed: String = "",
) {
    fun toJson(): JSONObject = JSONObject().apply {
        put("total_up", totalUp)
        put("total_down", totalDown)
        put("sessions", sessions)
        put("first_used", firstUsed)
        put("last_used", lastUsed)
    }
}

/**
 * Lifetime counters for `filesDir/usage-stats.json`.
 *
 * All mutations are synchronous and locked: the VPN service calls them from
 * its worker thread (on establishment, every 10s while up, and at teardown)
 * and the UI reads a snapshot after each change. The file is tiny, so a
 * read-modify-write under one lock is simpler and safer than a cache.
 */
object UsageStore {

    private val lock = Any()
    private var file: File? = null

    fun init(dir: File) {
        synchronized(lock) { file = File(dir, "usage-stats.json") }
    }

    fun snapshot(): Map<String, UsageEntry> = synchronized(lock) { load() }

    /** Count one successful tunnel establishment for this profile. */
    fun recordSession(id: String) {
        synchronized(lock) {
            val now = nowIso()
            val cur = load()[id] ?: UsageEntry(firstUsed = now, lastUsed = now)
            mutate(id, cur.copy(
                sessions = cur.sessions + 1,
                firstUsed = cur.firstUsed.ifBlank { now },
                lastUsed = now,
            ))
        }
    }

    /** Add inner bytes transferred since the last flush for this profile. */
    fun addBytes(id: String, up: Long, down: Long) {
        synchronized(lock) {
            if (up <= 0 && down <= 0) return
            val now = nowIso()
            val cur = load()[id] ?: UsageEntry(firstUsed = now, lastUsed = now)
            mutate(id, cur.copy(
                totalUp = cur.totalUp + up.coerceAtLeast(0),
                totalDown = cur.totalDown + down.coerceAtLeast(0),
                firstUsed = cur.firstUsed.ifBlank { now },
                lastUsed = now,
            ))
        }
    }

    /** Called when a profile is deleted so its counters do not dangle. */
    fun forget(id: String) {
        synchronized(lock) {
            val f = file ?: return
            val all = load().toMutableMap()
            if (all.remove(id) != null) write(f, all)
        }
    }

    private fun nowIso(): String =
        Instant.now().truncatedTo(ChronoUnit.SECONDS).toString()

    private fun load(): MutableMap<String, UsageEntry> {
        val f = file ?: return mutableMapOf()
        if (!f.exists()) return mutableMapOf()
        return runCatching {
            val root = JSONObject(f.readText())
            val servers = root.optJSONObject("servers") ?: return mutableMapOf()
            val out = mutableMapOf<String, UsageEntry>()
            for (key in servers.keys()) {
                val s = servers.optJSONObject(key) ?: continue
                out[key] = UsageEntry(
                    totalUp = s.optLong("total_up", 0),
                    totalDown = s.optLong("total_down", 0),
                    sessions = s.optInt("sessions", 0),
                    firstUsed = s.optString("first_used", ""),
                    lastUsed = s.optString("last_used", ""),
                )
            }
            out
        }.getOrDefault(mutableMapOf())
    }

    private fun mutate(id: String, entry: UsageEntry) {
        val f = file ?: return
        val all = load()
        all[id] = entry
        write(f, all)
    }

    private fun write(f: File, all: Map<String, UsageEntry>) {
        runCatching {
            val root = JSONObject().apply {
                put("version", 1)
                put("servers", JSONObject().apply {
                    for ((k, v) in all) put(k, v.toJson())
                })
            }
            val tmp = File(f.parentFile, f.name + ".tmp")
            tmp.writeText(root.toString(2))
            if (!tmp.renameTo(f)) f.writeText(root.toString(2))
        }
    }
}
