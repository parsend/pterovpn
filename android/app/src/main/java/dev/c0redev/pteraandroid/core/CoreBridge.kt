package dev.c0redev.pteraandroid.core

import core.Core
import dev.c0redev.pteraandroid.PteraLog
import org.json.JSONArray
import org.json.JSONObject

object CoreBridge {
    data class StartResult(
        val handle: Long,
        val error: String?,
    )

    data class State(
        val ready: Boolean,
        val running: Boolean,
        val error: String?,
        val watchdog: Boolean = false,
    )

    data class ProbeResult(
        val ok: Boolean,
        val ipv6: Boolean,
        val mode: String,
        val leafPin: String,
        val error: String?,
        val capsNoQuic: Boolean? = null,
    )

    data class PingResult(
        val rttMs: Long,
        val error: String?,
    )

    data class QuicIPsResult(
        val ips: List<String>,
        val error: String?,
    )

    fun startTun(tunFd: Int, mtu: Int, cfgJson: String, configDir: String): StartResult {
        PteraLog.i("Core.startTun fd=$tunFd mtu=$mtu cfgBytes=${cfgJson.length} configDir=$configDir")
        val raw = Core.startTun(tunFd.toLong(), mtu.toLong(), cfgJson, configDir)
        val j = JSONObject(raw)
        val handle = j.optLong("handle", 0L)
        val err = nullableErr(j, "error")
        PteraLog.i("Core.startTun -> handle=$handle err=${err ?: "null"}")
        return StartResult(handle = handle, error = err)
    }

    fun stop(handle: Long): Boolean {
        PteraLog.i("Core.stop handle=$handle")
        val ok = Core.stop(handle)
        PteraLog.i("Core.stop -> $ok")
        return ok
    }

    fun pollState(handle: Long): State {
        val raw = Core.pollState(handle)
        val j = JSONObject(raw)
        val err = nullableErr(j, "error")
        val running = j.optBoolean("running", false)
        val ready = j.optBoolean("ready", false)
        val watchdog = j.optBoolean("watchdog", false)
        PteraLog.v("pollState h=$handle ready=$ready running=$running watchdog=$watchdog err=${err ?: "null"}")
        if (!err.isNullOrBlank() && (err.equals("no session", ignoreCase = true) || err.equals("bad handle", ignoreCase = true))) {
            PteraLog.w("pollState missing session h=$handle (stale handle or core already stopped)")
        }
        return State(ready = ready, running = running, error = err, watchdog = watchdog)
    }

    fun pollLogs(handle: Long, max: Int = 200): List<String> {
        val raw = Core.pollLogs(handle, max.toLong())
        val arr = JSONArray(raw)
        val out = ArrayList<String>(arr.length())
        for (i in 0 until arr.length()) out.add(arr.getString(i))
        if (out.isNotEmpty()) {
            PteraLog.i("pollLogs h=$handle n=${out.size}")
            for (line in out.take(60)) {
                PteraLog.i("core| $line")
            }
        }
        return out
    }

    fun startProxy(listenAddr: String, cfgJson: String, configDir: String): StartResult {
        PteraLog.i("Core.startProxy listen=$listenAddr cfgBytes=${cfgJson.length}")
        val raw = Core.startProxy(listenAddr, cfgJson, configDir)
        val j = JSONObject(raw)
        val handle = j.optLong("handle", 0L)
        val err = nullableErr(j, "error")
        PteraLog.i("Core.startProxy -> handle=$handle err=${err ?: "null"}")
        return StartResult(handle = handle, error = err)
    }

    fun probePterovpn(server: String, token: String, timeoutMs: Long): ProbeResult {
        PteraLog.i("probePterovpn server=$server tokenLen=${token.length} timeout=$timeoutMs")
        val raw = Core.probePterovpn(server, token, timeoutMs)
        val j = JSONObject(raw)
        val capsNoQuic = if (j.has("capsNoQuic")) j.optBoolean("capsNoQuic") else null
        val res = ProbeResult(
            ok = j.optBoolean("ok", false),
            ipv6 = j.optBoolean("ipv6", false),
            mode = j.optString("mode", ""),
            leafPin = j.optString("leafPin", ""),
            error = nullableErr(j, "error"),
            capsNoQuic = capsNoQuic,
        )
        PteraLog.i("probePterovpn -> ok=${res.ok} mode=${res.mode} capsNoQuic=$capsNoQuic err=${res.error ?: "null"}")
        return res
    }

    fun ping(server: String, timeoutMs: Long): PingResult {
        PteraLog.v("ping server=$server timeout=$timeoutMs")
        val raw = Core.ping(server, timeoutMs)
        val j = JSONObject(raw)
        val res = PingResult(
            rttMs = j.optLong("rttMs", 0L),
            error = nullableErr(j, "error"),
        )
        PteraLog.v("ping -> rttMs=${res.rttMs} err=${res.error ?: "null"}")
        return res
    }

    fun quicDialTargetIPs(server: String, quicServer: String): QuicIPsResult {
        PteraLog.i("quicDialTargetIPs server=$server quicServer=$quicServer")
        val raw = Core.quicDialTargetIPs(server, quicServer)
        val j = JSONObject(raw)
        val err = nullableErr(j, "error")
        val arr = j.optJSONArray("ips") ?: JSONArray()
        val out = ArrayList<String>(arr.length())
        for (i in 0 until arr.length()) out.add(arr.getString(i))
        PteraLog.i("quicDialTargetIPs -> n=${out.size} err=${err ?: "null"}")
        return QuicIPsResult(ips = out, error = err)
    }

    private fun nullableErr(j: JSONObject, key: String): String? {
        if (!j.has(key) || j.isNull(key)) return null
        val s = j.optString(key, "")
        return s.takeIf { it.isNotEmpty() && it != "null" }
    }
}

