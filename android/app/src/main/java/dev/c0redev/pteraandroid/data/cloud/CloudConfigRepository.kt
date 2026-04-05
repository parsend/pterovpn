package dev.c0redev.pteraandroid.data.cloud

import android.content.Context
import dev.c0redev.pteraandroid.domain.model.Config
import dev.c0redev.pteraandroid.domain.model.ProtectionOptions
import java.io.File
import java.net.HttpURLConnection
import java.net.Inet6Address
import java.net.InetAddress
import java.net.URL
import java.nio.charset.StandardCharsets
import org.json.JSONObject

data class CloudConfigItem(
    val name: String,
    val config: Config,
)

class CloudConfigRepository(private val context: Context) {
    private val baseDir = File(context.filesDir, "ptera-vpn")
    private val cloudFile = File(baseDir, "cloud-config.txt")
    private val cloudConfigUrls = listOf(
        "https://raw.githubusercontent.com/unitdevgcc/pterovpn/refs/heads/mew/cloud-config.txt",
        "https://raw.githubusercontent.com/unitdevgcc/pterovpn/refs/heads/main/cloud-config.txt",
        "https://raw.githubusercontent.com/unitdevgcc/pterovpn/refs/heads/master/cloud-config.txt",
    )

    private fun loadRaw(fetch: Boolean): String {
        if (fetch) {
            val raw = fetchCloudRaw()
            baseDir.mkdirs()
            cloudFile.writeText(raw, Charsets.UTF_8)
            return raw
        }
        if (!cloudFile.exists()) return ""
        return cloudFile.readText(Charsets.UTF_8)
    }

    fun cloudList(fetch: Boolean): List<CloudConfigItem> {
        val raw = loadRaw(fetch)
        if (raw.isBlank()) return emptyList()

        val lines = parseCloudLines(raw)
        if (lines.isEmpty()) return emptyList()

        val out = ArrayList<CloudConfigItem>(lines.size)
        for ((idx, line) in lines.withIndex()) {
            val parts = parseCloudLineParts(line)
            val conn = parts.conn
            val serverToken = Config.parseConnection(conn) ?: continue
            val server = serverToken.first
            val token = serverToken.second

            val transport = parts.transport?.takeIf { it.isNotBlank() }
            var quicServer = Config.quicHostPortForCloudTcp(server)
            if (!parts.quicServer.isNullOrBlank()) {
                quicServer = parts.quicServer.trim()
            }

            val cfgTransport = transport?.lowercase()?.trim()
            val cfg = Config(
                server = server,
                token = token,
                tunCIDR6 = parts.tunCIDR6?.trim()?.takeIf { it.isNotBlank() },
                transport = cfgTransport?.takeIf { it.isNotBlank() },
                quicServer = if (cfgTransport?.equals("tcp", ignoreCase = true) == true) null else quicServer,
                quicServerName = null,
                quicSkipVerify = null,
                quicCertPinSHA256 = null,
                quicCaCert = null,
                quicTraceLog = null,
                routes = null,
                exclude = null,
                dualTransport = if (parts.dualOff) false else null,
                protection = null,
            )

            out.add(CloudConfigItem(name = "cloud-${idx + 1}", config = cfg))
        }
        return out
    }

    private fun fetchCloudRaw(): String {
        var lastError: Exception? = null
        for (url in cloudConfigUrls) {
            try {
                return fetchCloudRawFrom(url)
            } catch (e: Exception) {
                lastError = e
            }
        }
        throw lastError ?: RuntimeException("cloud fetch: no sources")
    }

    private fun fetchCloudRawFrom(url: String): String {
        val conn = URL(url).openConnection() as HttpURLConnection
        conn.connectTimeout = 15000
        conn.readTimeout = 15000
        conn.requestMethod = "GET"
        conn.setRequestProperty("Accept", "*/*")
        try {
            val code = conn.responseCode
            val stream = if (code in 200..299) conn.inputStream else conn.errorStream
            val body = stream?.use { it.readBytes().toString(StandardCharsets.UTF_8) } ?: throw RuntimeException("cloud fetch: empty response")
            if (code != HttpURLConnection.HTTP_OK) {
                throw RuntimeException("cloud fetch: HTTP $code $body")
            }
            return body
        } finally {
            conn.disconnect()
        }
    }

    private fun parseCloudLines(raw: String): List<String> {
        val out = ArrayList<String>()
        raw.split('\n').forEach { line ->
            val s = line.trim()
            if (s.isEmpty() || s.startsWith("#")) return@forEach
            val parts = s.split(Regex("\\s+"))
            if (parts.isEmpty()) return@forEach
            val conn = parts[0]
            val serverToken = Config.parseConnection(conn) ?: return@forEach
            if (serverToken.first.isBlank() || serverToken.second.isBlank()) return@forEach
            out.add(s)
        }
        return out
    }

    private data class ParsedLineParts(
        val conn: String,
        val tunCIDR6: String?,
        val quicServer: String?,
        val transport: String?,
        val dualOff: Boolean,
    )

    private fun parseCloudLineParts(line: String): ParsedLineParts {
        val parts = line.trim().split(Regex("\\s+"))
        if (parts.isEmpty()) return ParsedLineParts(conn = "", tunCIDR6 = null, quicServer = null, transport = null, dualOff = false)

        val conn = parts[0]
        var tunCIDR6: String? = null
        var quicServer: String? = null
        var transport: String? = null
        var dualOff = false

        for (p in parts.drop(1)) {
            val raw = p.trim()
            val low = raw.lowercase()
            when {
                low.startsWith("quic=") -> {
                    val v = raw.substringAfter('=', "")
                    if (v.isNotBlank()) quicServer = v
                }
                low.startsWith("transport=") -> {
                    val v = raw.substringAfter('=', "").trim().lowercase()
                    transport = when (v) {
                        "tcp" -> "tcp"
                        "quic" -> "quic"
                        "auto", "" -> null
                        else -> null
                    }
                }
                low.startsWith("dual=") -> {
                    val v = raw.substringAfter('=', "").trim().lowercase()
                    if (v == "false" || v == "0" || v == "no" || v == "off") dualOff = true
                }
                raw.contains("/") && isValidIPv6Cidr(raw) -> {
                    if (tunCIDR6.isNullOrBlank()) tunCIDR6 = raw
                }
                isHostPort(raw) -> {
                    if (quicServer.isNullOrBlank()) quicServer = raw
                }
            }
        }
        return ParsedLineParts(conn = conn, tunCIDR6 = tunCIDR6, quicServer = quicServer, transport = transport, dualOff = dualOff)
    }

    private fun isValidIPv6Cidr(s: String): Boolean {
        val idx = s.indexOf('/')
        if (idx <= 0 || idx == s.lastIndex) return false
        val ipPart = s.substring(0, idx)
        val prefixPart = s.substring(idx + 1)
        val prefix = prefixPart.toIntOrNull() ?: return false
        if (prefix < 0 || prefix > 128) return false
        return runCatching {
            val ip = InetAddress.getByName(ipPart)
            ip is Inet6Address
        }.getOrDefault(false)
    }

    private fun isHostPort(s: String): Boolean {
        val str = s.trim()
        if (str.isEmpty()) return false

        return try {
            if (str.startsWith("[") && str.contains("]")) {
                val end = str.indexOf(']')
                if (end < 0) return false
                val host = str.substring(1, end)
                val rest = str.substring(end + 1)
                if (!rest.startsWith(":")) return false
                val portStr = rest.substring(1)
                val port = portStr.toIntOrNull() ?: return false
                port in 1..65535 && host.isNotBlank()
            } else {
                val idx = str.lastIndexOf(':')
                if (idx <= 0 || idx == str.lastIndex) return false
                val host = str.substring(0, idx)
                val portStr = str.substring(idx + 1)
                val port = portStr.toIntOrNull() ?: return false
                port in 1..65535 && host.isNotBlank()
            }
        } catch (_: Throwable) {
            false
        }
    }
}

