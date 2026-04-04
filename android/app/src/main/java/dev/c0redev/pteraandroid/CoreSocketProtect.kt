package dev.c0redev.pteraandroid

import android.net.VpnService
import core.Core
import java.lang.reflect.InvocationHandler
import java.lang.reflect.Proxy

object CoreSocketProtect {

    fun install(service: VpnService) {
        val iface = try {
            Class.forName("core.Core\$Protector")
        } catch (_: ClassNotFoundException) {
            try {
                Class.forName("core.Protector")
            } catch (_: ClassNotFoundException) {
                PteraLog.w("ptera-core без Protector — пересобери AAR (gomobile bind ./android/core), иначе TCP к серверу может уходить в TUN")
                return
            }
        }
        val handler = InvocationHandler { _, method, args ->
            val n = method.name
            if ((n == "protect" || n == "Protect") && args != null && args.size == 1) {
                val fd = (args[0] as Number).toLong()
                return@InvocationHandler service.protect(fd.toInt())
            }
            false
        }
        val proxy = Proxy.newProxyInstance(iface.classLoader, arrayOf(iface), handler)
        runCatching {
            Core::class.java.getMethod("setSocketProtector", iface).invoke(null, proxy)
            PteraLog.i("Core.setSocketProtector зарегистрирован")
        }.onFailure { e ->
            PteraLog.w("setSocketProtector недоступен: ${e.message}")
        }
    }

    fun clear() {
        val iface = try {
            Class.forName("core.Core\$Protector")
        } catch (_: ClassNotFoundException) {
            try {
                Class.forName("core.Protector")
            } catch (_: ClassNotFoundException) {
                return
            }
        }
        runCatching {
            Core::class.java.getMethod("setSocketProtector", iface).invoke(null, null)
        }
    }
}
