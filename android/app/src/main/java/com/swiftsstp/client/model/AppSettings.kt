package com.swiftsstp.client.model

import org.json.JSONArray
import org.json.JSONObject

/**
 * The app-wide half of the configuration — exactly the keys
 * `settings.json` writes in docs/PROFILE_SCHEMA.md. These fields used to
 * live on every [Profile]; they are now one set of values for the whole
 * app, and a connect takes the selected profile and applies this on top
 * of it before dialing ([applyTo]). The certificate mode deliberately
 * stays per profile: which CA a given server is verified against is a
 * property of that server, not of the device.
 *
 * The defaults are the schema's documented ones (mtu 0 = engine default,
 * IPv4 on, IPv6 off, empty DNS, reconnect on, direct proxy, one session,
 * allow-mode with nothing listed), and [fromJson] falls back to them for
 * every absent key so a hand-edited or older file cannot produce a
 * configuration the engine has no rule for.
 */
data class AppSettings(
    /** Schema `mtu`: 0 = engine default (1400); otherwise clamped 576..1500. */
    val mtu: Int = 0,
    val ipv4Enabled: Boolean = true,
    val ipv6Enabled: Boolean = false,
    /** Schema `dns_override`: empty means use the DNS the server provides. */
    val dnsOverride: List<String> = emptyList(),
    val autoReconnect: AutoReconnect = AutoReconnect(),
    val proxy: ProxySettings = ProxySettings(),
    /** Schema `tunnels`: parallel sessions to bundle (1..8). */
    val tunnels: Int = 1,
    val rules: AppRules = AppRules(),
) {
    /** The settings.json document, key names exactly as the schema writes them. */
    fun toJson(): JSONObject = JSONObject().apply {
        put("version", 1)
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
        put("tunnels", tunnels.coerceIn(Profile.MIN_TUNNELS, Profile.MAX_TUNNELS))
        // Per-app tunneling is Android-only in the schema; Windows simply
        // does not write this field and tolerates its absence, so we always
        // write it in full and never nothing.
        put("app_rules", JSONObject().apply {
            put("mode", rules.mode)
            put("packages", JSONArray(rules.packages))
        })
    }

    /**
     * Overlay these app-wide values onto a profile: the result is the
     * effective configuration a connect dials with. The profile keeps its
     * identity, credentials and certificate handling; every moved field is
     * replaced wholesale, so a profile's leftovers can never win over what
     * the Settings tab says.
     */
    fun applyTo(profile: Profile): Profile = profile.copy(
        mtu = mtu,
        ipv4Enabled = ipv4Enabled,
        ipv6Enabled = ipv6Enabled,
        dnsOverride = dnsOverride,
        autoReconnect = autoReconnect,
        proxy = proxy,
        tunnels = tunnels,
        rules = rules,
    )

    /**
     * True when at least one moved field differs from the built-in
     * defaults — the schema's migration trigger for seeding settings.json
     * out of a pre-split profiles.json.
     */
    fun differsFromDefaults(): Boolean = this != AppSettings()

    companion object {
        fun fromJson(o: JSONObject): AppSettings {
            val auto = o.optJSONObject("auto_reconnect")
            val appRules = o.optJSONObject("app_rules")
            val packages = appRules?.optJSONArray("packages")
            val dns = o.optJSONArray("dns_override")
            // Older or hand-edited files may carry an unknown proxy type:
            // anything outside the documented set falls back to direct,
            // which is always safe (it simply connects without a proxy).
            val px = o.optJSONObject("proxy")
            val proxyType = px?.optString("type", ProxySettings.TYPE_NONE)
                ?.let { if (it in ProxySettings.TYPES) it else ProxySettings.TYPE_NONE }
                ?: ProxySettings.TYPE_NONE
            return AppSettings(
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
                proxy = ProxySettings(
                    type = proxyType,
                    host = px?.optString("host", "") ?: "",
                    port = px?.optInt("port", 0) ?: 0,
                    username = px?.optString("username", "") ?: "",
                    password = px?.optString("password", "") ?: "",
                ),
                tunnels = o.optInt("tunnels", Profile.MIN_TUNNELS)
                    .coerceIn(Profile.MIN_TUNNELS, Profile.MAX_TUNNELS),
                rules = AppRules(
                    mode = appRules?.optString("mode", AppRules.MODE_ALLOW)?.let {
                        if (it == AppRules.MODE_DENY) AppRules.MODE_DENY else AppRules.MODE_ALLOW
                    } ?: AppRules.MODE_ALLOW,
                    packages = buildList {
                        if (packages != null) for (i in 0 until packages.length()) {
                            val v = packages.optString(i).trim()
                            if (v.isNotEmpty()) add(v)
                        }
                    },
                ),
            )
        }
    }
}
