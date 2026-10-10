package com.sstpflash.client.model

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

/**
 * Upstream proxy for the OUTER transport, schema field `proxy`:
 *  - [type]: "" (direct) | "http" (HTTP CONNECT) | "socks5"
 * Credentials are optional; host/port are required when a mode is chosen.
 * When set, every session of the profile connects through it.
 */
data class ProxySettings(
    val type: String = TYPE_NONE,
    val host: String = "",
    val port: Int = 0,
    val username: String = "",
    val password: String = "",
) {
    /** True when an actual proxy mode (not "None") is configured. */
    val enabled: Boolean
        get() = type == TYPE_HTTP || type == TYPE_SOCKS5

    /** "HTTP 1.2.3.4:8080" for the in-use indicator; "" when direct. */
    fun label(): String {
        if (!enabled) return ""
        val target = host.ifBlank { "?" }
        val portText = if (port > 0) port.toString() else "?"
        return "${type.uppercase()} $target:$portText"
    }

    companion object {
        const val TYPE_NONE = ""
        const val TYPE_HTTP = "http"
        const val TYPE_SOCKS5 = "socks5"

        /** Modes in UI order: None, HTTP CONNECT, SOCKS5. */
        val TYPES = listOf(TYPE_NONE, TYPE_HTTP, TYPE_SOCKS5)
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
    val proxy: ProxySettings = ProxySettings(),
    val tunnels: Int = 1,
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
        put("proxy", JSONObject().apply {
            put("type", proxy.type)
            put("host", proxy.host)
            put("port", proxy.port)
            put("username", proxy.username)
            put("password", proxy.password)
        })
        put("tunnels", tunnels.coerceIn(MIN_TUNNELS, MAX_TUNNELS))
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

        /** Parallel sessions per profile, schema field `tunnels` (1..8). */
        const val MIN_TUNNELS = 1
        const val MAX_TUNNELS = 8

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
            // Older files predate `proxy`/`tunnels`: both are optional and
            // fall back to direct / a single session.
            val px = o.optJSONObject("proxy")
            val proxyType = px?.optString("type", ProxySettings.TYPE_NONE)
                ?.let { if (it in ProxySettings.TYPES) it else ProxySettings.TYPE_NONE }
                ?: ProxySettings.TYPE_NONE
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
                proxy = ProxySettings(
                    type = proxyType,
                    host = px?.optString("host", "") ?: "",
                    port = px?.optInt("port", 0) ?: 0,
                    username = px?.optString("username", "") ?: "",
                    password = px?.optString("password", "") ?: "",
                ),
                tunnels = o.optInt("tunnels", MIN_TUNNELS).coerceIn(MIN_TUNNELS, MAX_TUNNELS),
            )
        }
    }
}
