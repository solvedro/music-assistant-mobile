package io.music_assistant.client.wireguard

expect object WireGuardTunnel {
    fun isSupported(): Boolean
    fun startTunnel(config: String): Int
    fun stopTunnel()
    fun getTunnelPort(): Int
    fun isTunnelRunning(): Boolean
    fun getLastError(): String
}
