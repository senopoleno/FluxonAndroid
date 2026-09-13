package io.github.p1neapplexpress.openflux.vpn

import io.github.p1neapplexpress.openflux.data.Tunnel
import io.github.p1neapplexpress.openflux.util.Constants

data class VPNConfig(
    val name: String,
    val server: String = "127.0.0.1",
    val port: Int = 1080,
    val username: String? = null,
    val password: String? = null,
    val route: String = Constants.ROUTE_ALL,
    val dns: String = "1.1.1.1",
    val secondaryDns: String = "8.8.8.8",
    val dnsPort: Int = 53,
    val mtu: Int = 1400,
    val perApp: Boolean = false,
    val appBypass: Boolean = false,
    val appList: Array<String> = emptyArray(),
    val ipv6Proxy: Boolean = false,
    val udpGw: String? = null,
    val bypassLan: Boolean = true,
    val killSwitch: Boolean = false,
    val ipType: Int = 0,
    val remoteServer: String? = null,
    val remotePort: Int = 443,
) {
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is VPNConfig) return false
        return name == other.name &&
            server == other.server &&
            port == other.port &&
            username == other.username &&
            password == other.password &&
            route == other.route &&
            dns == other.dns &&
            secondaryDns == other.secondaryDns &&
            dnsPort == other.dnsPort &&
            mtu == other.mtu &&
            perApp == other.perApp &&
            appBypass == other.appBypass &&
            appList.contentEquals(other.appList) &&
            ipv6Proxy == other.ipv6Proxy &&
            udpGw == other.udpGw &&
            bypassLan == other.bypassLan &&
            killSwitch == other.killSwitch &&
            ipType == other.ipType &&
            remoteServer == other.remoteServer &&
            remotePort == other.remotePort
    }

    override fun hashCode(): Int {
        var result = name.hashCode()
        result = 31 * result + server.hashCode()
        result = 31 * result + port
        result = 31 * result + (username?.hashCode() ?: 0)
        result = 31 * result + (password?.hashCode() ?: 0)
        result = 31 * result + route.hashCode()
        result = 31 * result + dns.hashCode()
        result = 31 * result + secondaryDns.hashCode()
        result = 31 * result + dnsPort
        result = 31 * result + mtu
        result = 31 * result + perApp.hashCode()
        result = 31 * result + appBypass.hashCode()
        result = 31 * result + appList.contentHashCode()
        result = 31 * result + ipv6Proxy.hashCode()
        result = 31 * result + (udpGw?.hashCode() ?: 0)
        result = 31 * result + bypassLan.hashCode()
        result = 31 * result + killSwitch.hashCode()
        result = 31 * result + ipType
        result = 31 * result + (remoteServer?.hashCode() ?: 0)
        result = 31 * result + remotePort
        return result
    }
}

object TunnelEndpointHelper {
    fun extractTarget(tunnel: Tunnel): Pair<String, Int> {
        val transportType = tunnel.transportType.lowercase()
        if (transportType == "max" || transportType == "oneme") {
            return Pair("ws-api.oneme.ru", 443)
        }
        val urlStr = argValue(tunnel.transportConnPayload, "--url")
        if (urlStr.isNotBlank()) {
            val uri = runCatching { java.net.URI(urlStr) }.getOrNull()
            val h = uri?.host
            val p = if (uri != null && uri.port > 0) uri.port else if (uri?.scheme.equals("https", ignoreCase = true)) 443 else 80
            if (!h.isNullOrBlank()) return Pair(h, p)
        }
        return Pair("1.1.1.1", 443)
    }

    private fun argValue(payload: List<String>, arg: String): String {
        val idx = payload.indexOf(arg)
        return if (idx != -1 && idx + 1 < payload.size) payload[idx + 1] else ""
    }
}
