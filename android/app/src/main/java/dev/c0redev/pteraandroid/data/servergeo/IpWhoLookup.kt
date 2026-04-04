package dev.c0redev.pteraandroid.data.servergeo

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.InetAddress
import java.net.URL
import java.nio.charset.StandardCharsets
import java.util.concurrent.ConcurrentHashMap

object IpWhoLookup {
    private val cache = ConcurrentHashMap<String, ServerGeo?>()

    suspend fun lookupForServerField(serverField: String): ServerGeo? = withContext(Dispatchers.IO) {
        val host = serverHostFromField(serverField)
        if (host.isBlank()) return@withContext null
        val ip = runCatching { InetAddress.getByName(host).hostAddress }.getOrNull()
            ?: return@withContext null
        cache[ip]?.let { return@withContext it }
        val geo = fetch(ip) ?: return@withContext null
        cache[ip] = geo
        geo
    }

    private fun fetch(ip: String): ServerGeo? {
        return runCatching {
            val url = URL("https://ipwho.is/$ip")
            val conn = url.openConnection() as HttpURLConnection
            conn.connectTimeout = 6000
            conn.readTimeout = 6000
            conn.requestMethod = "GET"
            conn.setRequestProperty("Accept", "application/json")
            val code = conn.responseCode
            val stream = if (code in 200..299) conn.inputStream else conn.errorStream
            val body = stream.readBytes().toString(StandardCharsets.UTF_8)
            conn.disconnect()
            if (code !in 200..299) return@runCatching null
            val j = JSONObject(body)
            if (!j.optBoolean("success", false)) return@runCatching null
            val cc = j.optString("country_code", "").trim()
            val country = j.optString("country", "").trim()
            val cj = j.optJSONObject("connection")
            val asn = cj?.optInt("asn", 0) ?: 0
            val org = cj?.optString("org", "")?.trim().orEmpty()
            val asnLabel = when {
                asn > 0 && org.isNotBlank() -> "AS$asn · $org"
                org.isNotBlank() -> org
                asn > 0 -> "AS$asn"
                else -> "—"
            }
            val code2 = if (cc.length == 2) cc else "UN"
            ServerGeo(
                countryCode = code2,
                countryName = country.ifBlank { code2 },
                asnLabel = asnLabel,
                flagEmoji = countryCodeToFlagEmoji(code2),
            )
        }.getOrNull()
    }
}
