package io.github.p1neapplexpress.openflux.util

import java.net.Authenticator
import java.net.Inet4Address
import java.net.NetworkInterface
import java.net.PasswordAuthentication
import java.net.ServerSocket
import java.util.UUID

object LocalSocksSession {

    data class SessionInfo(
        val port: Int,
        val username: String,
        val password: String,
        val isAuthEnabled: Boolean = true,
        val isSharedLan: Boolean = false
    )

    fun generateRandomUsername(): String {
        val randSuffix = UUID.randomUUID().toString().replace("-", "").take(8)
        return "fluxon-$randSuffix"
    }

    @Volatile
    private var currentSession: SessionInfo = SessionInfo(
        port = 1080,
        username = generateRandomUsername(),
        password = "",
        isAuthEnabled = true,
        isSharedLan = false
    )

    fun generateNew(
        authEnabled: Boolean = true,
        customUser: String? = null,
        customPass: String? = null,
        shareLan: Boolean = false,
        customPort: Int? = null
    ): SessionInfo {
        val port = customPort ?: if (shareLan) 10808 else findAvailablePort()
        val username = customUser?.ifBlank { null } ?: generateRandomUsername()
        val password = if (!authEnabled) {
            ""
        } else {
            customPass?.ifBlank { null } ?: UUID.randomUUID().toString()
        }

        val session = SessionInfo(
            port = port,
            username = username,
            password = password,
            isAuthEnabled = authEnabled,
            isSharedLan = shareLan
        )
        currentSession = session
        if (authEnabled && password.isNotEmpty()) {
            setupAuthenticator(session)
        }
        Logx.i("LocalSocksSession", "Generated SOCKS5 session on port $port, auth=$authEnabled, shareLan=$shareLan, user=$username")
        return session
    }

    fun getActive(): SessionInfo = currentSession

    fun setupAuthenticator(session: SessionInfo = currentSession) {
        if (!session.isAuthEnabled || session.password.isEmpty()) return
        Authenticator.setDefault(object : Authenticator() {
            override fun getPasswordAuthentication(): PasswordAuthentication {
                return PasswordAuthentication(session.username, session.password.toCharArray())
            }
        })
    }

    fun getLocalIpAddress(): String {
        try {
            val interfaces = NetworkInterface.getNetworkInterfaces() ?: return "127.0.0.1"
            for (iface in interfaces) {
                if (iface.isLoopback || !iface.isUp) continue
                for (addr in iface.inetAddresses) {
                    if (addr is Inet4Address && !addr.isLoopbackAddress) {
                        val host = addr.hostAddress ?: continue
                        if (host.startsWith("192.168.") || host.startsWith("10.") || host.startsWith("172.")) {
                            return host
                        }
                    }
                }
            }
        } catch (_: Exception) { }
        return "192.168.43.1"
    }

    private fun findAvailablePort(): Int {
        for (attempt in 1..25) {
            val candidate = (30000..60000).random()
            if (isPortFree(candidate)) return candidate
        }
        return 10808
    }

    private fun isPortFree(port: Int): Boolean {
        return try {
            ServerSocket(port).use { true }
        } catch (_: Exception) {
            false
        }
    }
}
