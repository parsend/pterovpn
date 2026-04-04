package dev.c0redev.pteraandroid.ui

import android.app.Application
import android.app.ForegroundServiceStartNotAllowedException
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.net.VpnService
import android.os.Build
import androidx.core.content.ContextCompat
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import dev.c0redev.pteraandroid.ACTION_CORE_SESSION
import dev.c0redev.pteraandroid.BuildConfig
import dev.c0redev.pteraandroid.EXTRA_CORE_ERROR
import dev.c0redev.pteraandroid.EXTRA_CORE_HANDLE
import dev.c0redev.pteraandroid.EXTRA_CORE_MODE
import dev.c0redev.pteraandroid.PteraLog
import dev.c0redev.pteraandroid.PteraVpnService
import dev.c0redev.pteraandroid.core.CoreBridge
import dev.c0redev.pteraandroid.data.cloud.CloudConfigRepository
import dev.c0redev.pteraandroid.data.repo.LocalConfigRepository
import dev.c0redev.pteraandroid.data.servergeo.IpWhoLookup
import dev.c0redev.pteraandroid.data.servergeo.ServerGeo
import dev.c0redev.pteraandroid.domain.model.ClientSettings
import dev.c0redev.pteraandroid.domain.model.Config
import dev.c0redev.pteraandroid.domain.model.SessionRecord
import dev.c0redev.pteraandroid.R
import dev.c0redev.pteraandroid.update.UpdateManager
import dev.c0redev.pteraandroid.update.UpdatePrefs
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import java.io.File
import java.net.InetAddress
import java.time.Duration
import java.time.Instant
import java.util.concurrent.atomic.AtomicBoolean

data class ConfigItemState(
    val name: String,
    val config: Config,
    val pingMs: Long? = null,
    val pingFailed: Boolean = false,
    val probeOk: Boolean = false,
    val ipv6Support: Boolean = false,
    val serverMode: String = "",
    val geo: ServerGeo? = null,
)

data class ConnectionState(
    val connected: Boolean = false,
    val ready: Boolean = false,
    val mode: String? = null,
    val error: String? = null,
)

class ConnectionViewModel(app: Application) : AndroidViewModel(app) {
    private val appCtx = app.applicationContext
    private val localRepo = LocalConfigRepository(appCtx)
    private val cloudRepo = CloudConfigRepository(appCtx)
    private val configDir = File(appCtx.filesDir, "ptera-vpn").absolutePath

    private var coreHandle = -1L
    private var pollJob: Job? = null
    private var pollGeneration = 0L
    private var pendingConnectCfg: Config? = null
    private var pendingConnectName: String? = null
    private var pendingConnectSettings: ClientSettings? = null
    private var activeConfig: Config? = null
    private var activeConfigName: String? = null
    private var activeStartedAt: Instant? = null
    private var autoFallbackDone = false
    private var pendingMetric: MetricDraft? = null
    private var activeMetric: MetricDraft? = null
    private val metricFinalized = AtomicBoolean(false)

    private val _localConfigs = MutableStateFlow<List<ConfigItemState>>(emptyList())
    val localConfigs = _localConfigs
    private val _cloudConfigs = MutableStateFlow<List<ConfigItemState>>(emptyList())
    val cloudConfigs = _cloudConfigs
    private val _logs = MutableStateFlow<List<String>>(emptyList())
    val logs = _logs
    private val _connection = MutableStateFlow(ConnectionState())
    val connection = _connection
    private val _vpnPermissionIntent = MutableStateFlow<Intent?>(null)
    val vpnPermissionIntent = _vpnPermissionIntent
    private val _clientSettings = MutableStateFlow(localRepo.loadClientSettings())
    val clientSettings = _clientSettings
    private val _globalProtection = MutableStateFlow(localRepo.loadProtection())
    val globalProtection = _globalProtection
    private val _metrics = MutableStateFlow(localRepo.loadMetrics())
    val metrics = _metrics
    private val updateManager = UpdateManager()
    private val _updateStatus = MutableStateFlow<String?>(null)
    val updateStatus = _updateStatus
    private val _remoteReleaseTag = MutableStateFlow<String?>(UpdatePrefs.getRemoteReleaseTag(appCtx))
    val remoteReleaseTag = _remoteReleaseTag

    private val _cloudLoading = MutableStateFlow(false)
    val cloudLoading = _cloudLoading.asStateFlow()

    private val _uiMessages = MutableSharedFlow<String>(
        extraBufferCapacity = 32,
        onBufferOverflow = BufferOverflow.DROP_OLDEST,
    )
    val uiMessages: SharedFlow<String> = _uiMessages.asSharedFlow()

    private val _navToQuickTilesSettings = MutableSharedFlow<Unit>(
        extraBufferCapacity = 4,
        onBufferOverflow = BufferOverflow.DROP_OLDEST,
    )
    val navToQuickTilesSettings: SharedFlow<Unit> = _navToQuickTilesSettings.asSharedFlow()

    private val _activeProfileName = MutableStateFlow<String?>(null)
    val activeProfileName = _activeProfileName.asStateFlow()

    private fun setActiveProfileUi(name: String) {
        _activeProfileName.value = name
    }

    private fun clearActiveProfileUi() {
        _activeProfileName.value = null
    }

    private data class MetricDraft(
        val start: Instant,
        val configName: String,
        val server: String,
        val dnsOkBefore: Boolean,
        val rttBeforeNs: Long?,
        val probeOk: Boolean,
        val reconnectCount: Int,
        val handshakeOk: Boolean,
    )

    private val receiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            if (intent.action != ACTION_CORE_SESSION) return
            val handle = intent.getLongExtra(EXTRA_CORE_HANDLE, -1L)
            val mode = intent.getStringExtra(EXTRA_CORE_MODE)
            val error = intent.getStringExtra(EXTRA_CORE_ERROR)
            PteraLog.i("ACTION_CORE_SESSION handle=$handle mode=$mode err=${error ?: "null"}")
            if (!error.isNullOrBlank()) {
                val normalized = error.trim()
                val hideNoSession = normalized.equals("no session", ignoreCase = true) || normalized.equals("bad handle", ignoreCase = true)
                pollJob?.cancel()
                pollJob = null
                ++pollGeneration
                coreHandle = -1
                if (!hideNoSession) {
                    clearActiveProfileUi()
                    _connection.value = ConnectionState(connected = false, ready = false, mode = mode, error = normalized)
                    _logs.value = (_logs.value + listOf("ERR\t$normalized")).takeLast(500)
                    _uiMessages.tryEmit(normalized)
                    val draft = activeMetric
                    if (draft != null) finalizeActiveMetric(Instant.now(), handshakeOk = draft.handshakeOk, errorType = classifyErr(normalized))
                } else {
                    clearActiveProfileUi()
                    _connection.value = ConnectionState(connected = false, ready = false, mode = mode, error = null)
                    val draft = activeMetric
                    if (draft != null) finalizeActiveMetric(Instant.now(), handshakeOk = draft.handshakeOk, errorType = classifyErr(normalized))
                }
                return
            }
            if (handle <= 0) {
                PteraLog.w("ACTION_CORE_SESSION ignore bad handle=$handle")
                return
            }
            coreHandle = handle
            _connection.value = ConnectionState(connected = true, ready = false, mode = mode, error = null)
            startLogPolling(handle, mode)
        }
    }

    init {
        if (Build.VERSION.SDK_INT >= 33) {
            ContextCompat.registerReceiver(appCtx, receiver, IntentFilter(ACTION_CORE_SESSION), ContextCompat.RECEIVER_NOT_EXPORTED)
        } else {
            @Suppress("DEPRECATION")
            appCtx.registerReceiver(receiver, IntentFilter(ACTION_CORE_SESSION))
        }
        refreshLocalConfigs()
        refreshCloudConfigs(true)
        reloadProtectionAndSettings()
        reloadMetrics()
        viewModelScope.launch(Dispatchers.IO) {
            _remoteReleaseTag.value = UpdatePrefs.getRemoteReleaseTag(appCtx)
        }
    }

    override fun onCleared() {
        runCatching { appCtx.unregisterReceiver(receiver) }
        pollJob?.cancel()
        super.onCleared()
    }

    fun refreshLocalConfigs() {
        viewModelScope.launch(Dispatchers.IO) {
            val list = localRepo.listConfigs()
            _localConfigs.value = coroutineScope {
                list.map { stored ->
                    async {
                        val ping = CoreBridge.ping(stored.config.server, 5000)
                        val probe = CoreBridge.probePterovpn(stored.config.server, stored.config.token, 5000)
                        val geo = withTimeoutOrNull(8000) {
                            IpWhoLookup.lookupForServerField(stored.config.server)
                        }
                        ConfigItemState(
                            name = stored.name,
                            config = stored.config,
                            pingMs = ping.rttMs.takeIf { ping.error == null },
                            pingFailed = ping.error != null,
                            probeOk = probe.ok,
                            ipv6Support = probe.ipv6,
                            serverMode = probe.mode,
                            geo = geo,
                        )
                    }
                }.awaitAll()
            }
        }
    }

    fun refreshCloudConfigs(fetch: Boolean) {
        viewModelScope.launch(Dispatchers.IO) {
            _cloudLoading.value = true
            try {
                val raw = cloudRepo.cloudList(fetch)
                _cloudConfigs.value = coroutineScope {
                    raw.map { item ->
                        async {
                            val ping = CoreBridge.ping(item.config.server, 5000)
                            val probe = CoreBridge.probePterovpn(item.config.server, item.config.token, 5000)
                            val geo = withTimeoutOrNull(8000) {
                                IpWhoLookup.lookupForServerField(item.config.server)
                            }
                            ConfigItemState(
                                name = item.name,
                                config = item.config,
                                pingMs = ping.rttMs.takeIf { ping.error == null },
                                pingFailed = ping.error != null,
                                probeOk = probe.ok,
                                ipv6Support = probe.ipv6,
                                serverMode = probe.mode,
                                geo = geo,
                            )
                        }
                    }.awaitAll()
                }
            } catch (e: Exception) {
                _uiMessages.tryEmit(appCtx.getString(R.string.cloud_fetch_error, e.message ?: "unknown"))
            } finally {
                _cloudLoading.value = false
            }
        }
    }

    fun connect(name: String, cfg: Config, applyCloudDefaults: Boolean = false, cloudServerMode: String = "", cloudProbeIpv6: Boolean = false) {
        viewModelScope.launch(Dispatchers.IO) {
            PteraLog.i("connect name=$name")
            if (_connection.value.connected || coreHandle > 0) {
                disconnect()
            }
            val settings = localRepo.loadClientSettings()
            var effective = cfg
            if (applyCloudDefaults) effective = effective.withCloudDefaults(cloudServerMode, cloudProbeIpv6)
            effective = mergeEffectiveConfig(effective)
            beginMetric(name, effective, reconnectCount = 0)
            fillMetricBasics(effective)
            if (settings.mode != "proxy") {
                val prep = VpnService.prepare(appCtx)
                if (prep != null) {
                    pendingConnectCfg = effective
                    pendingConnectName = name
                    pendingConnectSettings = settings
                    _vpnPermissionIntent.value = prep
                    return@launch
                }
            }
            pendingConnectCfg = null
            pendingConnectName = null
            pendingConnectSettings = null
            activeConfig = effective
            activeConfigName = name
            setActiveProfileUi(name)
            activeStartedAt = Instant.now()
            autoFallbackDone = false
            activateMetric()
            startService(effective.toJson().toString(), settings.toJson().toString())
        }
    }

    fun confirmVpnPermission() {
        viewModelScope.launch(Dispatchers.IO) {
            val cfg = pendingConnectCfg ?: return@launch
            val settings = pendingConnectSettings ?: return@launch
            val name = pendingConnectName ?: "pending"
            pendingConnectCfg = null
            pendingConnectName = null
            pendingConnectSettings = null
            activeConfig = cfg
            activeConfigName = name
            setActiveProfileUi(name)
            activeStartedAt = Instant.now()
            autoFallbackDone = false
            activateMetric()
            startService(cfg.toJson().toString(), settings.toJson().toString())
        }
    }

    fun consumeVpnPermissionIntent() { _vpnPermissionIntent.value = null }

    fun cancelPendingVpnConnect() {
        pendingConnectCfg = null
        pendingConnectName = null
        pendingConnectSettings = null
        _vpnPermissionIntent.value = null
        pendingMetric?.let { finalizeMetricDraft(it, Instant.now(), false, "vpn_permission") }
        pendingMetric = null
    }

    private fun mergeEffectiveConfig(cfg: Config): Config {
        var c = cfg
        if (c.protection == null) {
            localRepo.loadProtection()?.let { c = c.copy(protection = it) }
        }
        val settings = localRepo.loadClientSettings()
        if (!settings.dualTun) {
            c = c.copy(dualTransport = false)
        }
        when (ClientSettings.normalizedTransportPreference(settings.transportPreference)) {
            ClientSettings.TRANSPORT_TCP ->
                c = c.copy(transport = "tcp", quicServer = null)
            ClientSettings.TRANSPORT_QUIC -> {
                val tcp = c.server.trim()
                val qs = when {
                    c.quicServer.isNullOrBlank() -> Config.quicHostPortForCloudTcp(c.server)
                    c.quicServer.trim() == tcp -> Config.quicHostPortForCloudTcp(c.server)
                    else -> c.quicServer.trim()
                }
                c = c.copy(transport = "quic", quicServer = qs)
            }
            else -> { }
        }
        return c
    }

    private suspend fun checkDnsOk(): Boolean = withContext(Dispatchers.IO) {
        runCatching {
            withTimeout(5000) { InetAddress.getAllByName("google.com") }
            true
        }.getOrDefault(false)
    }

    private fun classifyErr(err: String?): String {
        val s = err?.lowercase().orEmpty()
        if (s.isBlank()) return "graceful"
        if ("no session" in s || "bad handle" in s) return "graceful"
        if ("timeout" in s || "deadline" in s) return "timeout"
        if ("context canceled" in s || s == "canceled") return "graceful"
        if ("connection reset" in s) return "reset"
        if ("device busy" in s || "resource busy" in s || "address already in use" in s) return "device"
        if ("no such host" in s) return "dns"
        if ("foreground" in s || "securityexception" in s) return "android"
        return "unknown"
    }

    private fun beginMetric(name: String, cfg: Config, reconnectCount: Int) {
        metricFinalized.set(false)
        activeMetric?.let { finalizeMetricDraft(it, Instant.now(), it.handshakeOk, "replaced") }
        pendingMetric = MetricDraft(Instant.now(), name, cfg.server, false, null, false, reconnectCount, false)
    }

    private suspend fun fillMetricBasics(cfg: Config) {
        val draft = pendingMetric ?: return
        val dns = checkDnsOk()
        val ping = CoreBridge.ping(cfg.server, 5000)
        val probe = CoreBridge.probePterovpn(cfg.server, cfg.token, 5000)
        pendingMetric = draft.copy(
            dnsOkBefore = dns,
            rttBeforeNs = ping.rttMs.takeIf { ping.error == null }?.times(1_000_000L),
            probeOk = probe.ok,
        )
    }

    private fun activateMetric() {
        activeMetric = pendingMetric ?: return
        pendingMetric = null
    }

    private fun finalizeActiveMetric(end: Instant, handshakeOk: Boolean, errorType: String) {
        val draft = activeMetric ?: return
        activeMetric = null
        finalizeMetricDraft(draft, end, handshakeOk, errorType)
    }

    private fun finalizeMetricDraft(draft: MetricDraft, end: Instant, handshakeOk: Boolean, errorType: String) {
        if (!metricFinalized.compareAndSet(false, true)) return
        viewModelScope.launch(Dispatchers.IO) {
            localRepo.appendMetric(SessionRecord(
                start = draft.start,
                end = end,
                durationNs = Duration.between(draft.start, end).toNanos(),
                server = draft.server,
                configName = draft.configName,
                errorType = errorType,
                handshakeOk = handshakeOk,
                reconnectCount = draft.reconnectCount,
                rttBeforeNs = draft.rttBeforeNs,
                dnsOkBefore = draft.dnsOkBefore,
                dnsOkAfter = checkDnsOk(),
                probeOk = draft.probeOk,
            ))
            reloadMetrics()
        }
    }

    private fun startService(cfgJson: String, settingsJson: String) {
        PteraLog.i("startForegroundService cfgBytes=${cfgJson.length} settingsBytes=${settingsJson.length} dir=$configDir")
        val intent = PteraVpnService.newIntent(appCtx, cfgJson, settingsJson, configDir)
        try {
            ContextCompat.startForegroundService(appCtx, intent)
        } catch (e: ForegroundServiceStartNotAllowedException) {
            PteraLog.w("FGS blocked: ${e.message}")
            _uiMessages.tryEmit(appCtx.getString(R.string.quick_connect_fgs_blocked))
        }
    }

    fun reloadProtectionAndSettings() {
        viewModelScope.launch(Dispatchers.IO) {
            _clientSettings.value = localRepo.loadClientSettings()
            _globalProtection.value = localRepo.loadProtection()
        }
    }

    fun reloadMetrics() {
        viewModelScope.launch(Dispatchers.IO) { _metrics.value = localRepo.loadMetrics() }
    }

    fun refreshRemoteReleaseTag() {
        viewModelScope.launch(Dispatchers.IO) {
            _remoteReleaseTag.value = UpdatePrefs.getRemoteReleaseTag(appCtx)
        }
    }

    fun checkForUpdateAndInstall() {
        viewModelScope.launch(Dispatchers.IO) {
            runCatching { updateManager.checkAndInstall(appCtx, BuildConfig.VERSION_NAME) }
                .onSuccess {
                    _remoteReleaseTag.value = UpdatePrefs.getRemoteReleaseTag(appCtx)
                    val msg = if (it) {
                        appCtx.getString(R.string.update_install_started)
                    } else {
                        appCtx.getString(R.string.update_none)
                    }
                    _updateStatus.value = msg
                    _uiMessages.tryEmit(msg)
                }
                .onFailure {
                    _remoteReleaseTag.value = UpdatePrefs.getRemoteReleaseTag(appCtx)
                    val msg = appCtx.getString(R.string.update_error_fmt, it.message ?: "unknown")
                    _updateStatus.value = msg
                    _uiMessages.tryEmit(msg)
                }
        }
    }

    fun saveClientSettings(s: ClientSettings) { viewModelScope.launch(Dispatchers.IO) { localRepo.saveClientSettings(s); reloadProtectionAndSettings() } }
    fun saveGlobalProtection(p: dev.c0redev.pteraandroid.domain.model.ProtectionOptions?) { viewModelScope.launch(Dispatchers.IO) { localRepo.saveProtection(p); reloadProtectionAndSettings() } }
    fun upsertLocalConfig(name: String, cfg: Config) { viewModelScope.launch(Dispatchers.IO) { localRepo.saveConfig(name, cfg); refreshLocalConfigs() } }
    fun deleteLocalConfig(name: String) { viewModelScope.launch(Dispatchers.IO) { localRepo.deleteConfig(name); refreshLocalConfigs() } }

    fun importCloudAsLocal(desiredName: String, item: ConfigItemState) {
        viewModelScope.launch(Dispatchers.IO) {
            var base = Config.sanitizeName(desiredName)
            if (base.isBlank()) base = "imported"
            var name = base
            var suffix = 0
            while (localRepo.loadConfig(name) != null) {
                suffix++
                name = "$base-$suffix"
            }
            var cfg = item.config.withCloudDefaults(item.serverMode, item.ipv6Support)
            cfg = mergeEffectiveConfig(cfg)
            localRepo.saveConfig(name, cfg)
            refreshLocalConfigs()
            _uiMessages.tryEmit(appCtx.getString(R.string.cloud_import_saved, name))
        }
    }

    fun requestQuickConnect(profileName: String) {
        viewModelScope.launch(Dispatchers.IO) {
            val cfg = localRepo.loadConfig(profileName)
            if (cfg == null) {
                _uiMessages.tryEmit(appCtx.getString(R.string.quick_connect_unknown_profile, profileName))
                return@launch
            }
            connect(profileName, cfg)
        }
    }

    fun requestNavigateToQuickTilesSettings() {
        _navToQuickTilesSettings.tryEmit(Unit)
    }

    fun disconnect() {
        PteraLog.i("disconnect coreHandle=$coreHandle")
        pollJob?.cancel()
        pollJob = null
        ++pollGeneration
        val currentHandle = coreHandle
        coreHandle = -1
        _connection.value = ConnectionState()
        clearActiveProfileUi()
        activeStartedAt = null
        autoFallbackDone = false
        val draft = activeMetric
        if (draft != null) {
            val lastReady = runCatching { if (currentHandle > 0) CoreBridge.pollState(currentHandle).ready else false }.getOrDefault(false)
            finalizeActiveMetric(Instant.now(), draft.handshakeOk || lastReady, "graceful")
        }
        runCatching { appCtx.startService(PteraVpnService.stopIntent(appCtx)) }
        runCatching { appCtx.stopService(Intent(appCtx, PteraVpnService::class.java)) }
    }

    private suspend fun restartAsTcpFallback() {
        if (autoFallbackDone) return
        val cfg = activeConfig ?: return
        val name = activeConfigName ?: "active"
        val alreadyTcp = cfg.transport.equals("tcp", ignoreCase = true) && cfg.quicServer.isNullOrBlank()
        if (alreadyTcp) return
        autoFallbackDone = true
        PteraLog.w("QUIC startup timeout, fallback to tcp name=$name server=${cfg.server}")
        _logs.value = (_logs.value + listOf("WARN\tQUIC startup timeout, fallback to TCP")).takeLast(500)

        pollJob?.cancel()
        pollJob = null
        ++pollGeneration
        coreHandle = -1
        _connection.value = ConnectionState(connected = false, ready = false, mode = "tun", error = null)
        runCatching { appCtx.startService(PteraVpnService.stopIntent(appCtx)) }
        runCatching { appCtx.stopService(Intent(appCtx, PteraVpnService::class.java)) }
        delay(300)

        val fallbackCfg = cfg.copy(transport = "tcp", quicServer = null)
        activeConfig = fallbackCfg
        activeConfigName = name
        setActiveProfileUi(name)
        activeStartedAt = Instant.now()
        val settings = localRepo.loadClientSettings()
        startService(fallbackCfg.toJson().toString(), settings.toJson().toString())
    }

    private fun startLogPolling(handle: Long, mode: String?) {
        pollJob?.cancel()
        val gen = ++pollGeneration
        PteraLog.i("startLogPolling gen=$gen handle=$handle mode=$mode")
        pollJob = viewModelScope.launch(Dispatchers.IO) {
            val buf = ArrayList<String>(500)
            while (true) {
                ensureActive()
                val state = CoreBridge.pollState(handle)
                val noSession = state.error.equals("no session", ignoreCase = true) || state.error.equals("bad handle", ignoreCase = true)
                val visibleError = if (noSession) null else state.error
                if (gen == pollGeneration) {
                    _connection.value = ConnectionState(connected = state.running, ready = state.ready, mode = mode, error = visibleError)
                }
                val cfg = activeConfig
                val startedAt = activeStartedAt
                if (gen == pollGeneration && state.running && !state.ready && !autoFallbackDone && cfg != null && startedAt != null) {
                    val elapsed = Duration.between(startedAt, Instant.now()).seconds
                    val isTcp = cfg.transport.equals("tcp", ignoreCase = true) && cfg.quicServer.isNullOrBlank()
                    if (!isTcp && elapsed >= 32) {
                        PteraLog.w("startup watchdog timeout elapsed=${elapsed}s handle=$handle, triggering tcp fallback")
                        restartAsTcpFallback()
                        break
                    }
                }
                activeMetric?.let { if (state.ready && !it.handshakeOk) activeMetric = it.copy(handshakeOk = true) }
                val newLogs = CoreBridge.pollLogs(handle, 200)
                if (newLogs.isNotEmpty()) {
                    buf.addAll(newLogs)
                    if (buf.size > 500) repeat(buf.size - 500) { buf.removeAt(0) }
                    if (gen == pollGeneration) {
                        _logs.value = buf.toList()
                    }
                }
                if (!state.running) {
                    if (noSession) {
                        if (gen == pollGeneration) {
                            coreHandle = -1
                            clearActiveProfileUi()
                            _connection.value = ConnectionState(connected = false, ready = false, mode = mode, error = null)
                            pollJob = null
                            PteraLog.w("poll exit noSession gen=$gen handle=$handle")
                        } else {
                            PteraLog.i("poll ignore noSession stale gen=$gen current=$pollGeneration handle=$handle")
                        }
                    } else if (gen == pollGeneration) {
                        PteraLog.w("poll exit err=${state.error} ready=${state.ready} handle=$handle")
                    }
                    if (gen == pollGeneration) {
                        activeMetric?.let { finalizeActiveMetric(Instant.now(), it.handshakeOk || state.ready, classifyErr(state.error)) }
                    }
                    break
                }
                delay(250)
            }
        }
    }
}
