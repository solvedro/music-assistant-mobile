package io.music_assistant.client.wireguard

import android.util.Log
import java.net.InetSocketAddress
import java.net.Proxy
import java.net.ProxySelector
import java.net.SocketAddress
import java.net.URI

actual object WireGuardTunnel {
    private const val TAG = "WireGuardTunnel"
    private var libraryLoaded = false
    private var defaultProxySelector: ProxySelector? = null

    val proxySelector = object : ProxySelector() {
        override fun select(uri: URI?): List<Proxy> {
            val port = getTunnelPort()
            if (port > 0) {
                val host = uri?.host
                if (host != null && (host == "127.0.0.1" || host == "localhost")) {
                    return listOf(Proxy.NO_PROXY)
                }
                return listOf(Proxy(Proxy.Type.SOCKS, InetSocketAddress("127.0.0.1", port)))
            }
            return listOf(Proxy.NO_PROXY)
        }

        override fun connectFailed(uri: URI?, sa: SocketAddress?, ioe: java.io.IOException?) {
            Log.w(TAG, "SOCKS connection failed for $uri: ${ioe?.message}")
        }
    }

    init {
        try {
            System.loadLibrary("wgproxy")
            libraryLoaded = true
            Log.i(TAG, "libwgproxy loaded successfully")
        } catch (t: Throwable) {
            Log.e(TAG, "Failed to load libwgproxy", t)
        }
    }

    actual fun isSupported(): Boolean = libraryLoaded

    actual fun startTunnel(config: String): Int {
        if (!libraryLoaded) {
            Log.e(TAG, "Cannot start tunnel: libwgproxy not loaded")
            return -99
        }
        val port = nativeStartTunnel(config)
        if (port > 0) {
            Log.i(TAG, "Tunnel started on SOCKS port $port, setting default ProxySelector")
            try {
                if (defaultProxySelector == null) {
                    defaultProxySelector = ProxySelector.getDefault()
                }
                ProxySelector.setDefault(proxySelector)
            } catch (t: Throwable) {
                Log.w(TAG, "Failed to set default ProxySelector", t)
            }
        } else {
            Log.e(TAG, "Tunnel start returned error code: $port")
        }
        return port
    }

    actual fun stopTunnel() {
        if (!libraryLoaded) return
        nativeStopTunnel()
        try {
            defaultProxySelector?.let {
                ProxySelector.setDefault(it)
            }
        } catch (t: Throwable) {
            Log.w(TAG, "Failed to restore default ProxySelector", t)
        }
    }

    actual fun getTunnelPort(): Int {
        if (!libraryLoaded) return 0
        return nativeGetTunnelPort()
    }

    actual fun isTunnelRunning(): Boolean {
        if (!libraryLoaded) return false
        return nativeIsTunnelRunning()
    }

    actual fun getLastError(): String {
        if (!libraryLoaded) return "libwgproxy not loaded"
        return nativeGetLastError()
    }

    @JvmStatic
    private external fun nativeStartTunnel(config: String): Int

    @JvmStatic
    private external fun nativeStopTunnel()

    @JvmStatic
    private external fun nativeGetTunnelPort(): Int

    @JvmStatic
    private external fun nativeIsTunnelRunning(): Boolean

    @JvmStatic
    private external fun nativeGetLastError(): String
}
