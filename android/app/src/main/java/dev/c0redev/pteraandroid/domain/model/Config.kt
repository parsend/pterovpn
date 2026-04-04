package dev.c0redev.pteraandroid.domain.model

import org.json.JSONObject

data class Config(
    val server: String,
    val token: String,
    val quicServer: String? = null,
    val transport: String? = null,
    val quicServerName: String? = null,
    val quicSkipVerify: Boolean? = null,
    val quicCertPinSHA256: String? = null,
    val quicCaCert: String? = null,
    val quicTraceLog: Boolean? = null,
    val routes: String? = null,
    val exclude: String? = null,
    val tunCIDR6: String? = null,
    val dualTransport: Boolean? = null,
    val protection: ProtectionOptions? = null,
) {
    fun withCloudDefaults(serverMode: String, probeIPv6: Boolean): Config {
        val noPin = quicCertPinSHA256.isNullOrBlank()
        var skip = quicSkipVerify
        if (noPin) {
            skip = null
        }
        val forcedTcp = transport?.equals("tcp", ignoreCase = true) == true
        var t = transport
        var qs = quicServer
        if (forcedTcp) {
            qs = null
        } else {
            when (serverMode.trim().lowercase()) {
                "tcp only" -> {
                    t = "tcp"
                    qs = null
                }
                "quic only", "quic/tcp" -> {
                    if (cloudQuicNeedsDefaultPort(server, qs)) qs = quicHostPortForCloudTcp(server)
                }
                else -> {
                    if (cloudQuicNeedsDefaultPort(server, qs)) qs = quicHostPortForCloudTcp(server)
                }
            }
        }
        var tun6 = tunCIDR6
        if (tun6.isNullOrBlank() && probeIPv6) {
            tun6 = DEFAULT_CLOUD_TUN_CIDR6
        }
        return copy(
            transport = t,
            quicServer = qs,
            quicSkipVerify = skip,
            tunCIDR6 = tun6,
        )
    }

    fun transportSummary(): String {
        val tr = transport?.takeIf { it.isNotBlank() } ?: "auto"
        val qs = quicServer?.let { " · $it" }.orEmpty()
        val dual = when (dualTransport) {
            false -> " · dual off"
            else -> ""
        }
        return "$tr$qs$dual"
    }

    fun toJson(): JSONObject {
        val j = JSONObject()
        j.put("server", server)
        j.put("token", token)
        quicServer?.takeIf { it.isNotBlank() }?.let { j.put("quicServer", it) }
        transport?.takeIf { it.isNotBlank() }?.let { j.put("transport", it) }
        quicServerName?.takeIf { it.isNotBlank() }?.let { j.put("quicServerName", it) }
        quicSkipVerify?.let { j.put("quicSkipVerify", it) }
        quicCertPinSHA256?.takeIf { it.isNotBlank() }?.let { j.put("quicCertPinSHA256", it) }
        quicCaCert?.takeIf { it.isNotBlank() }?.let { j.put("quicCaCert", it) }
        quicTraceLog?.let { j.put("quicTraceLog", it) }
        routes?.let { j.put("routes", it) }
        exclude?.let { j.put("exclude", it) }
        tunCIDR6?.let { j.put("tunCIDR6", it) }
        dualTransport?.let { j.put("dualTransport", it) }
        protection?.let { j.put("protection", it.toJson()) }
        return j
    }

    companion object {
        const val DEFAULT_CLOUD_TUN_CIDR6 = "fd00:13:37::2/64"
        const val CLOUD_DEFAULT_QUIC_PORT = 4433

        fun quicHostPortForCloudTcp(serverTcp: String): String {
            val s = serverTcp.trim()
            if (s.isEmpty()) return s
            val host = when {
                s.startsWith("[") -> {
                    val end = s.indexOf(']')
                    if (end > 0) s.substring(0, end + 1) else s
                }
                else -> {
                    val idx = s.lastIndexOf(':')
                    if (idx > 0) s.substring(0, idx) else s
                }
            }
            return "$host:$CLOUD_DEFAULT_QUIC_PORT"
        }

        private fun cloudQuicNeedsDefaultPort(tcpServer: String, quicServer: String?): Boolean {
            val tcp = tcpServer.trim()
            val qs = quicServer?.trim().orEmpty()
            if (tcp.isEmpty()) return false
            if (qs.isEmpty()) return true
            return qs == tcp
        }

        fun fromJson(j: JSONObject): Config {
            return Config(
                server = j.getString("server"),
                token = j.getString("token"),
                quicServer = j.optString("quicServer", "").takeIf { it.isNotBlank() },
                transport = j.optString("transport", "").takeIf { it.isNotBlank() },
                quicServerName = j.optString("quicServerName", "").takeIf { it.isNotBlank() },
                quicSkipVerify = if (j.has("quicSkipVerify")) j.optBoolean("quicSkipVerify") else null,
                quicCertPinSHA256 = j.optString("quicCertPinSHA256", "").takeIf { it.isNotBlank() },
                quicCaCert = j.optString("quicCaCert", "").takeIf { it.isNotBlank() },
                quicTraceLog = if (j.has("quicTraceLog")) j.optBoolean("quicTraceLog") else null,
                routes = j.optString("routes", "").takeIf { it.isNotBlank() },
                exclude = j.optString("exclude", "").takeIf { it.isNotBlank() },
                tunCIDR6 = j.optString("tunCIDR6", "").takeIf { it.isNotBlank() },
                dualTransport = when {
                    !j.has("dualTransport") || j.isNull("dualTransport") -> null
                    else -> j.optBoolean("dualTransport", true)
                },
                protection = j.optJSONObject("protection")?.let { ProtectionOptions.fromJson(it) },
            )
        }

        fun sanitizeName(raw: String): String {
            val s = raw.trim()
            val b = StringBuilder()
            for (c in s) {
                if (c in 'a'..'z' || c in 'A'..'Z' || c in '0'..'9' || c == '-' || c == '_') {
                    b.append(c)
                }
            }
            val out = b.toString()
            return if (out.isBlank()) "default" else out
        }

        fun parseConnection(s: String): Pair<String, String>? {
            val raw = s.trim()
            if (raw.isEmpty()) return null
            val idx = raw.lastIndexOf(':')
            if (idx <= 0 || idx == raw.lastIndex) return null
            val server = raw.substring(0, idx).trim()
            val token = raw.substring(idx + 1).trim()
            if (server.isEmpty() || token.isEmpty()) return null
            return server to token
        }
    }
}

