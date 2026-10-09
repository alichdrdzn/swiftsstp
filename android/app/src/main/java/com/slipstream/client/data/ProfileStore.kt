package com.slipstream.client.data

import com.slipstream.client.model.Profile
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/** Reads and writes `filesDir/profiles.json` in the shared schema shape. */
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
                    profiles.forEach { put(it.toJson()) }
                })
            }
            val f = File(dir, "profiles.json")
            val tmp = File(dir, "profiles.json.tmp")
            tmp.writeText(root.toString(2))
            if (!tmp.renameTo(f)) f.writeText(root.toString(2))
        }
    }
}
