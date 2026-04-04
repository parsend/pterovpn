package dev.c0redev.pteraandroid.domain.model

import org.json.JSONObject

data class ClientSettings(
    val mode: String = "tun",
    val systemProxy: Boolean = false,
    val proxyListen: String = "127.0.0.1:1080",
    val ipv6Tunnel: Boolean = false,
    val dualTun: Boolean = true,
    val transportPreference: String = TRANSPORT_AUTO,
) {
    fun toJson(): JSONObject {
        val j = JSONObject()
        if (mode.isNotBlank()) j.put("mode", mode)
        j.put("systemProxy", systemProxy)
        if (proxyListen.isNotBlank()) j.put("proxyListen", proxyListen)
        j.put("ipv6Tunnel", ipv6Tunnel)
        j.put("dualTun", dualTun)
        j.put("transportPreference", Companion.normalizedTransportPreference(transportPreference))
        return j
    }

    companion object {
        const val TRANSPORT_AUTO = "auto"
        const val TRANSPORT_TCP = "tcp"
        const val TRANSPORT_QUIC = "quic"

        fun normalizedTransportPreference(raw: String): String {
            return when (raw.trim().lowercase()) {
                TRANSPORT_TCP -> TRANSPORT_TCP
                TRANSPORT_QUIC -> TRANSPORT_QUIC
                else -> TRANSPORT_AUTO
            }
        }

        fun fromJson(j: JSONObject): ClientSettings {
            val rawMode = j.optString("mode", "tun")
            val mode = if (rawMode == "proxy") "proxy" else "tun"
            return ClientSettings(
                mode = mode,
                systemProxy = j.optBoolean("systemProxy", false),
                proxyListen = j.optString("proxyListen", "127.0.0.1:1080"),
                ipv6Tunnel = j.optBoolean("ipv6Tunnel", false),
                dualTun = j.optBoolean("dualTun", true),
                transportPreference = normalizedTransportPreference(j.optString("transportPreference", TRANSPORT_AUTO)),
            )
        }
    }
}

