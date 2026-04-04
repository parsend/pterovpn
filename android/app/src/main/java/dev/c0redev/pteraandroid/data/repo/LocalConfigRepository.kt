package dev.c0redev.pteraandroid.data.repo

import android.content.Context
import dev.c0redev.pteraandroid.data.store.LocalJsonStorage
import dev.c0redev.pteraandroid.domain.model.ClientSettings
import dev.c0redev.pteraandroid.domain.model.Config
import dev.c0redev.pteraandroid.domain.model.ProtectionOptions
import dev.c0redev.pteraandroid.domain.model.MetricsStore
import dev.c0redev.pteraandroid.domain.model.SessionRecord

data class StoredConfig(
    val name: String,
    val config: Config,
)

class LocalConfigRepository(context: Context) {
    private val storage = LocalJsonStorage(context)

    fun listConfigs(): List<StoredConfig> {
        return storage.listConfigs().map { (name, cfg) -> StoredConfig(name, cfg) }
    }

    fun loadConfig(name: String): Config? = storage.loadConfig(name)

    fun saveConfig(name: String, config: Config) {
        storage.saveConfig(name, config)
    }

    fun deleteConfig(name: String) {
        storage.deleteConfig(name)
    }

    fun loadClientSettings(): ClientSettings = storage.loadClientSettings()

    fun saveClientSettings(s: ClientSettings) {
        storage.saveClientSettings(s)
    }

    fun loadProtection(): ProtectionOptions? = storage.loadProtection()

    fun saveProtection(p: ProtectionOptions?) = storage.saveProtection(p)

    fun loadMetrics(): MetricsStore = storage.loadMetrics()

    fun appendMetric(r: SessionRecord) = storage.appendMetric(r)
}

