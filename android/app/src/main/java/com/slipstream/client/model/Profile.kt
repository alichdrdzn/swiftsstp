package com.slipstream.client.model

import org.json.JSONArray
import org.json.JSONObject
import java.security.SecureRandom

/** Auto-reconnect backoff, schema field `auto_reconnect`. */
data class AutoReconnect(
    val enabled: Boolean = true,
    val initialBackoffMs: Long = 1000,
    val maxBackoffMs: Long = 30000,
)

/**
 * Per-app tunneling, schema field `extras`:
 *  - `allowed_apps`: package names
 *  - `app_rule`: "allow" (only these apps use the tunnel) or
 *    "deny" (everything except these apps)
 */
data class AppRules(
    val mode: String = MODE_ALLOW,
    val packages: List<String> = emptyList(),
) {
    companion object {
        const val MODE_ALLOW = "allow"
        const val MODE_DENY = "deny"
    }
}

/** One entry of `profiles.json`, exactly the shape in docs/PROFILE_SCHEMA.md. */
data class Profile(
    val id: String,
    val name: String,
    val host: String,
    val port: Int = 443,
    val sni: String = "",
    val username: String,
    val password: String,
    val tlsMode: String = TLS_INSECURE,
    val caPath: String = "",
    val mtu: Int = 0,
    val ipv4Enabled: Boolean = true,
    val ipv6Enabled: Boolean = false,
    val dnsOverride: List<String> = emptyList(),
    val autoReconnect: AutoReconnect = AutoReconnect(),
    val rules: AppRules = AppRules(),
) {
    fun toJson(): JSONObject = JSONObject().apply {
        put("id", id)
        put("name", name)
        put("host", host)
        put("port", port)
        put("sni", sni)
        put("username", username)
        put("password", password)
        put("tls_mode", tlsMode)
        put("ca_path", caPath)
        put("mtu", mtu)
        put("ipv4_enabled", ipv4Enabled)
        put("ipv6_enabled", ipv6Enabled)
        put("dns_override", JSONArray(dnsOverride))
        put("auto_reconnect", JSONObject().apply {
            put("enabled", autoReconnect.enabled)
            put("initial_backoff_ms", autoReconnect.initialBackoffMs)
            put("max_backoff_ms", autoReconnect.maxBackoffMs)
        })
        put("extras", JSONObject().apply {
            put("allowed_apps", JSONArray(rules.packages))
            put("app_rule", rules.mode)
        })
    }

    fun tlsModeLabel(): String = when (tlsMode) {
        TLS_VERIFY -> "VERIFY"
        TLS_CUSTOM_CA -> "CUSTOM CA"
        else -> "INSECURE"
    }

    companion object {
        const val TLS_VERIFY = "verify"
        const val TLS_INSECURE = "insecure"
        const val TLS_CUSTOM_CA = "custom_ca"

        private val random = SecureRandom()

        /** 16 random hex characters, per the schema's `id` example. */
        fun newId(): String {
            val bytes = ByteArray(8)
            random.nextBytes(bytes)
            return bytes.joinToString("") { "%02x".format(it) }
        }

        fun blank(): Profile = Profile(
            id = newId(),
            name = "",
            host = "",
            username = "",
            password = "",
        )

        fun fromJson(o: JSONObject): Profile {
            val auto = o.optJSONObject("auto_reconnect")
            val extras = o.optJSONObject("extras")
            val dns = o.optJSONArray("dns_override")
            val allowed = extras?.optJSONArray("allowed_apps")
            return Profile(
                id = o.optString("id").ifBlank { newId() },
                name = o.optString("name"),
                host = o.optString("host"),
                port = o.optInt("port", 443),
                sni = o.optString("sni"),
                username = o.optString("username"),
                password = o.optString("password"),
                tlsMode = o.optString("tls_mode", TLS_INSECURE)
                    .let { if (it in listOf(TLS_VERIFY, TLS_INSECURE, TLS_CUSTOM_CA)) it else TLS_INSECURE },
                caPath = o.optString("ca_path"),
                mtu = o.optInt("mtu", 0),
                ipv4Enabled = o.optBoolean("ipv4_enabled", true),
                ipv6Enabled = o.optBoolean("ipv6_enabled", false),
                dnsOverride = buildList {
                    if (dns != null) for (i in 0 until dns.length()) {
                        val v = dns.optString(i).trim()
                        if (v.isNotEmpty()) add(v)
                    }
                },
                autoReconnect = AutoReconnect(
                    enabled = auto?.optBoolean("enabled", true) ?: true,
                    initialBackoffMs = auto?.optLong("initial_backoff_ms", 1000L) ?: 1000L,
                    maxBackoffMs = auto?.optLong("max_backoff_ms", 30000L) ?: 30000L,
                ),
                rules = AppRules(
                    mode = extras?.optString("app_rule", AppRules.MODE_ALLOW)?.let {
                        if (it == AppRules.MODE_DENY) AppRules.MODE_DENY else AppRules.MODE_ALLOW
                    } ?: AppRules.MODE_ALLOW,
                    packages = buildList {
                        if (allowed != null) for (i in 0 until allowed.length()) {
                            val v = allowed.optString(i).trim()
                            if (v.isNotEmpty()) add(v)
                        }
                    },
                ),
            )
        }
    }
}
