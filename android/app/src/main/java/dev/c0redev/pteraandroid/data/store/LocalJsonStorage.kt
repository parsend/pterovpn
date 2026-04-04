package dev.c0redev.pteraandroid.data.store

import android.content.Context
import dev.c0redev.pteraandroid.domain.model.ClientSettings
import dev.c0redev.pteraandroid.domain.model.Config
import dev.c0redev.pteraandroid.domain.model.MetricsStore
import dev.c0redev.pteraandroid.domain.model.ProtectionOptions
import dev.c0redev.pteraandroid.domain.model.SessionRecord
import java.io.File
import java.time.Instant
import org.json.JSONObject

class LocalJsonStorage(private val context: Context) {
    private val baseDir = File(context.filesDir, "ptera-vpn")
    private val configsDir = File(baseDir, "configs")
    private val settingsFile = File(baseDir, "settings.json")
    private val protectionFile = File(baseDir, "protection.json")
    private val metricsFile = File(baseDir, "metrics.json")

    init {
        configsDir.mkdirs()
        baseDir.mkdirs()
    }

    private fun configFileFor(name: String): File {
        val safe = Config.sanitizeName(name)
        return File(configsDir, "$safe.json")
    }

    fun listConfigs(): List<Pair<String, Config>> {
        val out = ArrayList<Pair<String, Config>>()
        val files = configsDir.listFiles() ?: return out
        files.forEach { f ->
            val n = f.name
            if (!n.endsWith(".json")) return@forEach
            val name = n.removeSuffix(".json")
            runCatching {
                out.add(name to loadConfig(name)!!)
            }
        }
        return out.sortedBy { it.first }
    }

    fun loadConfig(name: String): Config? {
        val f = configFileFor(name)
        if (!f.exists()) return null
        val text = f.readText()
        val j = JSONObject(text)
        return Config.fromJson(j)
    }

    fun saveConfig(name: String, config: Config) {
        val f = configFileFor(name)
        val j = config.toJson()
        f.writeText(j.toString(2))
    }

    fun deleteConfig(name: String) {
        val f = configFileFor(name)
        f.delete()
    }

    fun loadClientSettings(): ClientSettings {
        if (!settingsFile.exists()) {
            return ClientSettings()
        }
        val j = JSONObject(settingsFile.readText())
        return ClientSettings.fromJson(j)
    }

    fun saveClientSettings(s: ClientSettings) {
        settingsFile.writeText(s.toJson().toString(2))
    }

    fun loadProtection(): ProtectionOptions? {
        if (!protectionFile.exists()) return null
        val j = JSONObject(protectionFile.readText())
        return ProtectionOptions.fromJson(j)
    }

    fun saveProtection(p: ProtectionOptions?) {
        if (p == null) {
            protectionFile.delete()
            return
        }
        protectionFile.writeText(p.toJson().toString(2))
    }

    fun loadMetrics(): MetricsStore {
        if (!metricsFile.exists()) return MetricsStore(emptyList())
        val j = JSONObject(metricsFile.readText())
        return MetricsStore.fromJson(j)
    }

    @Synchronized
    fun appendMetric(r: SessionRecord) {
        val store = loadMetrics()
        val max = 20
        val next = ArrayList<SessionRecord>(store.records.size + 1)
        next.addAll(store.records)
        next.add(r)
        val trimmed = if (next.size > max) next.takeLast(max) else next
        val out = MetricsStore(records = trimmed).toJson()
        metricsFile.writeText(out.toString(2))
    }

    companion object {
        fun emptyRecordNow(
            server: String,
            configName: String,
            dnsOkBefore: Boolean,
            handshakeOk: Boolean,
            reconnectCount: Int,
            probeOk: Boolean,
            errorType: String? = null,
        ): SessionRecord {
            return SessionRecord(
                start = Instant.now(),
                end = null,
                durationNs = null,
                server = server,
                configName = configName,
                errorType = errorType,
                handshakeOk = handshakeOk,
                reconnectCount = reconnectCount,
                rttBeforeNs = null,
                rttDuringNs = null,
                dnsOkBefore = dnsOkBefore,
                dnsOkAfter = null,
                probeOk = probeOk,
            )
        }
    }
}

