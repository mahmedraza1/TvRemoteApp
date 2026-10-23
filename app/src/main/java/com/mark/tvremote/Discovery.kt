package com.mark.tvremote

import android.content.Context
import android.net.nsd.NsdManager
import android.net.nsd.NsdServiceInfo
import android.os.Handler
import android.os.Looper
import androidx.compose.runtime.mutableStateListOf

data class FoundTv(val name: String, val host: String)

/** Finds Android TV / Google TV devices on the network via mDNS (`_androidtvremote2._tcp`). */
class Discovery(context: Context) {
    val found = mutableStateListOf<FoundTv>()

    private val nsd = context.getSystemService(Context.NSD_SERVICE) as NsdManager
    private val main = Handler(Looper.getMainLooper())
    private val pending = ArrayDeque<NsdServiceInfo>()
    private var resolving = false
    private var running = false

    private val listener = object : NsdManager.DiscoveryListener {
        override fun onServiceFound(info: NsdServiceInfo) {
            main.post { pending.add(info); resolveNext() }
        }

        override fun onServiceLost(info: NsdServiceInfo) {
            main.post { found.removeAll { it.name == info.serviceName } }
        }

        override fun onDiscoveryStarted(serviceType: String) {}
        override fun onDiscoveryStopped(serviceType: String) {}
        override fun onStartDiscoveryFailed(serviceType: String, errorCode: Int) { running = false }
        override fun onStopDiscoveryFailed(serviceType: String, errorCode: Int) {}
    }

    fun start() {
        if (running) return
        running = true
        runCatching { nsd.discoverServices("_androidtvremote2._tcp", NsdManager.PROTOCOL_DNS_SD, listener) }
            .onFailure { running = false }
    }

    fun stop() {
        if (!running) return
        running = false
        pending.clear()
        runCatching { nsd.stopServiceDiscovery(listener) }
    }

    // NsdManager can only resolve one service at a time.
    @Suppress("DEPRECATION")
    private fun resolveNext() {
        if (resolving) return
        val info = pending.removeFirstOrNull() ?: return
        resolving = true
        nsd.resolveService(info, object : NsdManager.ResolveListener {
            override fun onServiceResolved(resolved: NsdServiceInfo) {
                main.post {
                    val host = resolved.host?.hostAddress
                    if (host != null && found.none { it.host == host }) found.add(FoundTv(resolved.serviceName, host))
                    resolving = false
                    resolveNext()
                }
            }

            override fun onResolveFailed(failed: NsdServiceInfo, errorCode: Int) {
                main.post { resolving = false; resolveNext() }
            }
        })
    }
}
