package io.music_assistant.client.wireguard

actual object WireGuardTunnel {
    actual fun isSupported(): Boolean = false
    actual fun startTunnel(config: String): Int = -1
    actual fun stopTunnel() {}
    actual fun getTunnelPort(): Int = 0
    actual fun isTunnelRunning(): Boolean = false
    actual fun getLastError(): String = ""
}
