package dev.c0redev.pteraandroid.domain.model

import dev.c0redev.pteraandroid.json.optNullableString
import org.json.JSONArray
import org.json.JSONObject
import java.time.Instant

data class SessionRecord(
    val start: Instant,
    val end: Instant? = null,
    val durationNs: Long? = null,
    val server: String,
    val configName: String,
    val errorType: String? = null,
    val handshakeOk: Boolean,
    val reconnectCount: Int,
    val rttBeforeNs: Long? = null,
    val rttDuringNs: Long? = null,
    val dnsOkBefore: Boolean,
    val dnsOkAfter: Boolean? = null,
    val probeOk: Boolean,
) {
    fun toJson(): JSONObject {
        val j = JSONObject()
        j.put("start", start.toString())
        end?.let { j.put("end", it.toString()) }
        durationNs?.let { j.put("duration", it) }
        j.put("server", server)
        j.put("configName", configName)
        errorType?.let { j.put("errorType", it) }
        j.put("handshakeOK", handshakeOk)
        j.put("reconnectCount", reconnectCount)
        rttBeforeNs?.let { j.put("rttBefore", it) }
        rttDuringNs?.let { j.put("rttDuring", it) }
        j.put("dnsOKBefore", dnsOkBefore)
        dnsOkAfter?.let { j.put("dnsOKAfter", it) }
        j.put("probeOK", probeOk)
        return j
    }

    companion object {
        fun fromJson(j: JSONObject): SessionRecord = SessionRecord(
            start = Instant.parse(j.getString("start")),
            end = j.optNullableString("end")?.let(Instant::parse),
            durationNs = if (j.has("duration") && !j.isNull("duration")) j.getLong("duration") else null,
            server = j.getString("server"),
            configName = j.getString("configName"),
            errorType = j.optNullableString("errorType"),
            handshakeOk = j.getBoolean("handshakeOK"),
            reconnectCount = j.getInt("reconnectCount"),
            rttBeforeNs = if (j.has("rttBefore") && !j.isNull("rttBefore")) j.getLong("rttBefore") else null,
            rttDuringNs = if (j.has("rttDuring") && !j.isNull("rttDuring")) j.getLong("rttDuring") else null,
            dnsOkBefore = j.getBoolean("dnsOKBefore"),
            dnsOkAfter = if (j.has("dnsOKAfter") && !j.isNull("dnsOKAfter")) j.getBoolean("dnsOKAfter") else null,
            probeOk = j.getBoolean("probeOK"),
        )

        fun listFromJson(arr: JSONArray): List<SessionRecord> = List(arr.length()) { i -> fromJson(arr.getJSONObject(i)) }
    }
}

data class MetricsStore(val records: List<SessionRecord> = emptyList()) {
    fun toJson(): JSONObject = JSONObject().put("records", JSONArray().apply { records.forEach { put(it.toJson()) } })

    companion object {
        fun fromJson(j: JSONObject): MetricsStore {
            val arr = j.optJSONArray("records") ?: JSONArray()
            return MetricsStore(records = SessionRecord.listFromJson(arr))
        }
    }
}
