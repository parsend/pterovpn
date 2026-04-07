package dev.c0redev.pteraandroid

import android.net.VpnService
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.net.ConnectivityManager
import android.net.IpPrefix
import android.os.Build
import android.os.ParcelFileDescriptor
import android.os.IBinder
import androidx.core.app.ServiceCompat
import dev.c0redev.pteraandroid.core.CoreBridge
import dev.c0redev.pteraandroid.domain.model.ClientSettings
import dev.c0redev.pteraandroid.domain.model.Config
import org.json.JSONObject
import java.io.File
import java.net.Inet4Address
import java.net.InetAddress
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.concurrent.thread

private const val NOTIF_CHANNEL_ID = "ptera_vpn"
const val ACTION_CORE_SESSION = "dev.c0redev.pteraandroid.ACTION_CORE_SESSION"
const val EXTRA_CORE_HANDLE = "core_handle"
const val EXTRA_CORE_MODE = "core_mode"
const val EXTRA_CORE_ERROR = "core_error"

class PteraVpnService : VpnService() {
    private var coreHandle: Long = -1
    private var tunFd: Int = -1
    private var tunPfd: ParcelFileDescriptor? = null
    private val sessionAbort = AtomicBoolean(false)

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            PteraLog.i("onStartCommand ACTION_STOP stopSelf")
            sessionAbort.set(true)
            stopActive()
            stopSelf()
            return START_NOT_STICKY
        }

        val modeRaw = intent?.getStringExtra(EXTRA_SETTINGS_JSON)
        val cfgJson = intent?.getStringExtra(EXTRA_CFG_JSON)
        val settingsJson = modeRaw ?: "{}"
        val configDir = intent?.getStringExtra(EXTRA_CONFIG_DIR) ?: File(filesDir, "ptera-vpn").absolutePath

        PteraLog.i("onStartCommand flags=$flags startId=$startId cfgLen=${cfgJson?.length ?: 0} settingsLen=${settingsJson.length} configDir=$configDir")

        if (cfgJson.isNullOrBlank()) {
            PteraLog.w("onStartCommand empty cfg, stopSelf")
            stopSelf()
            return START_NOT_STICKY
        }

        val settings = runCatching {
            ClientSettings.fromJson(JSONObject(settingsJson))
        }.getOrElse { ClientSettings() }

        val cfg = runCatching {
            Config.fromJson(JSONObject(cfgJson))
        }.getOrElse {
            PteraLog.e("onStartCommand bad cfg json", it)
            stopSelf()
            return START_NOT_STICKY
        }

        PteraLog.i("stopActive before new session mode=${settings.mode}")
        stopActive()
        sessionAbort.set(false)

        ensureForeground("starting")

        thread(name = "ptera-vpn-thread") {
            runCatching {
                PteraLog.i("worker start mode=${settings.mode}")
                val effective = configAfterTcpOnlyProbe(cfg)
                when (settings.mode) {
                    "proxy" -> startProxyInternal(effective, settings, configDir)
                    else -> startTunInternal(effective, settings, configDir)
                }
            }.onFailure { e ->
                PteraLog.e("worker failed", e)
                ensureForeground("error")
                broadcastSessionError(e.message ?: e.toString())
                stopSelf()
            }
        }

        return START_STICKY
    }

    override fun onDestroy() {
        stopActive()
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    private fun ensureForeground(status: String) {
        val mgr = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        if (Build.VERSION.SDK_INT >= 26) {
            val ch = NotificationChannel(
                NOTIF_CHANNEL_ID,
                "ptera-vpn",
                NotificationManager.IMPORTANCE_LOW,
            )
            mgr.createNotificationChannel(ch)
        }

        val notif = Notification.Builder(this, NOTIF_CHANNEL_ID)
            .setContentTitle("ptera-vpn")
            .setContentText(status)
            .setSmallIcon(android.R.drawable.stat_sys_download)
            .setOngoing(true)
            .build()

        if (Build.VERSION.SDK_INT >= 34) {
            ServiceCompat.startForeground(
                this,
                1,
                notif,
                ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE,
            )
        } else {
            startForeground(1, notif)
        }
    }

    private fun configAfterTcpOnlyProbe(cfg: Config): Config {
        val probe = CoreBridge.probePterovpn(cfg.server, cfg.token, 12_000)
        if (probe.error != null) {
            PteraLog.w("configAfterTcpOnlyProbe probe err=${probe.error}, cfg unchanged")
            return cfg
        }
        if (!probe.ok) {
            PteraLog.w("configAfterTcpOnlyProbe probe not ok, cfg unchanged")
            return cfg
        }
        val noQuic = probe.capsNoQuic ?: return cfg
        return if (noQuic) {
            PteraLog.i("configAfterTcpOnlyProbe caps without QUIC -> tcp, quicServer cleared")
            cfg.copy(transport = "tcp", quicServer = null)
        } else {
            cfg
        }
    }

    private fun stopActive() {
        CoreSocketProtect.clear()
        val handle = coreHandle
        PteraLog.i("stopActive prevCoreHandle=$handle tunFd=$tunFd")
        if (handle > 0) {
            runCatching { CoreBridge.stop(handle) }
                .onFailure { PteraLog.e("Core.stop failed", it) }
        }
        coreHandle = -1

        tunFd = -1

        tunPfd?.let {
            runCatching { it.close() }
        }
        tunPfd = null

        stopForeground(STOP_FOREGROUND_DETACH)
    }

    private fun startProxyInternal(cfg: Config, settings: ClientSettings, configDir: String) {
        CoreSocketProtect.install(this)
        val cfgJson = cfg.toJson().toString()
        val listenAddr = settings.proxyListen
        PteraLog.i("startProxy listen=$listenAddr")
        val res = CoreBridge.startProxy(listenAddr, cfgJson, configDir)
        if (res.error != null) throw IllegalStateException(res.error)
        coreHandle = res.handle
        if (sessionAbort.get()) {
            PteraLog.i("startProxy aborted after handle=$coreHandle")
            runCatching { CoreBridge.stop(coreHandle) }
            coreHandle = -1
            stopForeground(STOP_FOREGROUND_DETACH)
            stopSelf()
            return
        }
        PteraLog.i("startProxy ok handle=$coreHandle")
        broadcastSession(coreHandle, "proxy")
        ensureForeground("proxy")
    }

    private fun startTunInternal(cfg: Config, settings: ClientSettings, configDir: String) {
        CoreSocketProtect.install(this)
        val mtu = 1380
        PteraLog.i("startTunInternal mtu=$mtu ipv6Tunnel=${settings.ipv6Tunnel} server=${cfg.server}")

        val builder = Builder()
        builder.setSession("ptera-vpn")
        builder.setMtu(mtu)
        runCatching { builder.addDisallowedApplication(packageName) }
            .onSuccess { PteraLog.i("VpnBuilder addDisallowedApplication ok package=$packageName") }
            .onFailure { e ->
                if (e !is PackageManager.NameNotFoundException) {
                    PteraLog.w("VpnBuilder addDisallowedApplication failed: ${e.message}")
                }
            }

        builder.addAddress("10.13.37.2", 24)

        val tun6 = cfg.tunCIDR6?.takeIf { it.isNotBlank() && settings.ipv6Tunnel }
        if (tun6 != null) {
            val (ip, pfx) = parseCIDR(tun6) ?: error("bad tunCIDR6")
            builder.addAddress(ip, pfx)
        }

        builder.addDnsServer("1.1.1.1")
        builder.addDnsServer("8.8.8.8")
        if (tun6 != null) {
            builder.addDnsServer("2606:4700:4700::1111")
        }

        val cm = getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
        cm.activeNetwork?.let { builder.setUnderlyingNetworks(arrayOf(it)) }
        builder.setMetered(false)

        val routeCidrs = parseCsvCidrs(cfg.routes)
        if (routeCidrs.isEmpty()) {
            builder.addRoute("0.0.0.0", 0)
            if (tun6 != null) builder.addRoute("::", 0)
        } else {
            for (c in routeCidrs) {
                val (ip, pfx) = parseCIDR(c) ?: continue
                builder.addRoute(ip, pfx)
            }
        }

        val excludeCidrs = parseCsvCidrs(cfg.exclude)
        for (c in excludeCidrs) {
            val (ip, pfx) = parseCIDR(c) ?: continue
            val inet = InetAddress.getByName(ip)
            builder.excludeRoute(IpPrefix(inet, pfx))
        }

        val (serverHost, _) = parseHostPort(cfg.server) ?: Pair(null, null)
        val serverHostStr = serverHost ?: throw IllegalStateException("bad server:port")

        val serverIPs = resolveHostIPsPreferV4(serverHostStr)
        for (ip in serverIPs) {
            val pfx = if (ip is Inet4Address) 32 else 128
            builder.excludeRoute(IpPrefix(ip, pfx))
        }

        val quicUsed = isQuicUsed(cfg.transport, cfg.quicServer)
        if (quicUsed) {
            val quicServer = cfg.quicServer ?: ""
            val res = CoreBridge.quicDialTargetIPs(cfg.server, quicServer)
            if (res.error != null) throw IllegalStateException(res.error)
            for (ipStr in res.ips) {
                val inet = InetAddress.getByName(ipStr)
                val pfx = if (inet is Inet4Address) 32 else 128
                builder.excludeRoute(IpPrefix(inet, pfx))
            }
        }

        if (sessionAbort.get()) {
            PteraLog.i("startTunInternal aborted before establish")
            stopForeground(STOP_FOREGROUND_DETACH)
            stopSelf()
            return
        }

        val pfd = builder.establish() ?: error("VpnService.establish returned null")
        tunPfd = pfd
        val fd = pfd.detachFd()
        pfd.close()
        tunFd = fd
        PteraLog.i("TUN established fd=$fd mtu=$mtu")

        val cfgJson = cfg.toJson().toString()
        val res = CoreBridge.startTun(fd, mtu, cfgJson, configDir)
        if (res.error != null) throw IllegalStateException(res.error)
        coreHandle = res.handle
        if (sessionAbort.get()) {
            PteraLog.i("startTun aborted after core start handle=$coreHandle")
            runCatching { CoreBridge.stop(coreHandle) }
            coreHandle = -1
            tunFd = -1
            tunPfd = null
            stopForeground(STOP_FOREGROUND_DETACH)
            stopSelf()
            return
        }
        PteraLog.i("startTun core ok handle=$coreHandle")
        broadcastSession(coreHandle, "tun")
        ensureForeground("connected")
    }

    private fun isQuicUsed(transport: String?, quicServer: String?): Boolean {
        val tr = transport?.trim()?.lowercase().orEmpty()
        return when (tr) {
            "tcp" -> false
            "quic" -> true
            else -> !quicServer.isNullOrBlank()
        }
    }

    private fun parseCsvCidrs(raw: String?): List<String> {
        val s = raw?.trim().orEmpty()
        if (s.isEmpty()) return emptyList()
        return s.split(',').map { it.trim() }.filter { it.isNotBlank() }
    }

    private fun parseCIDR(cidr: String): Pair<String, Int>? {
        val idx = cidr.indexOf('/')
        if (idx <= 0 || idx == cidr.lastIndex) return null
        val ip = cidr.substring(0, idx).trim()
        val pfx = cidr.substring(idx + 1).trim().toIntOrNull() ?: return null
        return ip to pfx
    }

    private fun parseHostPort(server: String): Pair<String?, Int?>? {
        val s = server.trim()
        if (s.isEmpty()) return null

        return try {
            if (s.startsWith("[")) {
                val end = s.indexOf(']')
                if (end < 0) return null
                val host = s.substring(1, end)
                val rest = s.substring(end + 1)
                if (!rest.startsWith(":")) return null
                val port = rest.substring(1).toIntOrNull() ?: return null
                Pair(host, port)
            } else {
                val idx = s.lastIndexOf(':')
                if (idx <= 0 || idx == s.lastIndex) return null
                val host = s.substring(0, idx)
                val port = s.substring(idx + 1).toIntOrNull() ?: return null
                Pair(host, port)
            }
        } catch (_: Throwable) {
            null
        }
    }

    private fun resolveHostIPsPreferV4(host: String): List<InetAddress> {
        val all = InetAddress.getAllByName(host).toList()
        val v4 = all.filterIsInstance<Inet4Address>()
        return if (v4.isNotEmpty()) v4 else all
    }

    private fun broadcastSession(handle: Long, mode: String) {
        PteraLog.i("broadcastSession handle=$handle mode=$mode")
        val i = Intent(ACTION_CORE_SESSION).apply {
            setPackage(packageName)
            putExtra(EXTRA_CORE_HANDLE, handle)
            putExtra(EXTRA_CORE_MODE, mode)
        }
        sendBroadcast(i)
    }

    private fun broadcastSessionError(message: String) {
        PteraLog.e("broadcastSessionError $message")
        val i = Intent(ACTION_CORE_SESSION).apply {
            setPackage(packageName)
            putExtra(EXTRA_CORE_ERROR, message)
        }
        sendBroadcast(i)
    }

    companion object {
        const val ACTION_STOP = "dev.c0redev.pteraandroid.ACTION_STOP_VPN"
        const val EXTRA_CFG_JSON = "cfg_json"
        const val EXTRA_SETTINGS_JSON = "settings_json"
        const val EXTRA_CONFIG_DIR = "config_dir"

        fun stopIntent(context: Context): Intent =
            Intent(context, PteraVpnService::class.java).apply { action = ACTION_STOP }

        fun newIntent(
            context: Context,
            cfgJson: String,
            settingsJson: String,
            configDir: String,
        ): Intent {
            return Intent(context, PteraVpnService::class.java).apply {
                putExtra(EXTRA_CFG_JSON, cfgJson)
                putExtra(EXTRA_SETTINGS_JSON, settingsJson)
                putExtra(EXTRA_CONFIG_DIR, configDir)
            }
        }
    }
}

