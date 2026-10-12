package com.swiftsstp.client.data

import com.swiftsstp.client.model.Profile
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/**
 * Reads and writes `filesDir/profiles.json` in the shared schema shape:
 * one entry per server — address, credentials and certificate handling,
 * nothing else. Serialization goes through [Profile.fromJson] and
 * [Profile.storeJson], so the moved tunnel settings (mtu, families, DNS,
 * reconnect, proxy, tunnels, per-app rules) are neither written here nor
 * lost on load: an older file that still carries them reads them once,
 * and from then on no save puts them back. Those live in settings.json,
 * owned by [SettingsStore].
 */
object ProfileStore {

    fun load(dir: File): List<Profile> {
        val f = File(dir, "profiles.json")
        if (!f.exists()) return emptyList()
        return runCatching {
            val root = JSONObject(f.readText())
            val arr = root.optJSONArray("profiles") ?: return emptyList()
            buildList {
                for (i in 0 until arr.length()) {
                    val o = arr.optJSONObject(i) ?: continue
                    add(Profile.fromJson(o))
                }
            }
        }.getOrDefault(emptyList())
    }

    fun save(dir: File, profiles: List<Profile>) {
        runCatching {
            val root = JSONObject().apply {
                put("version", 1)
                put("profiles", JSONArray().apply {
                    profiles.forEach { put(it.storeJson()) }
                })
            }
            val body = root.toString(2)
            val f = File(dir, "profiles.json")
            val tmp = File(dir, "profiles.json.tmp")
            tmp.writeText(body)
            if (!tmp.renameTo(f)) f.writeText(body)
        }
    }
}
