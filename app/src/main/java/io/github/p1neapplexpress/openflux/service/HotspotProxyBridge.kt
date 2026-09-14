package io.github.p1neapplexpress.openflux.service

import io.github.p1neapplexpress.openflux.util.Logx
import java.io.InputStream
import java.io.OutputStream
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Local TCP bridge for LAN/Hotspot Proxy Sharing.
 * Listens on 0.0.0.0:lanPort, handles RFC 1929 SOCKS5 user/pass authentication if enabled,
 * and transparently forwards traffic to the internal 127.0.0.1:localSocksPort.
 */
object HotspotProxyBridge {

    private const val TAG = "HotspotProxyBridge"
    private val isRunning = AtomicBoolean(false)
    private var serverSocket: ServerSocket? = null
    private var executor: ExecutorService? = null

    @Synchronized
    fun start(
        lanPort: Int,
        targetLocalPort: Int,
        authEnabled: Boolean = false,
        username: String = "",
        password: String = ""
    ) {
        if (isRunning.get()) {
            Logx.d(TAG, "Bridge already running, stopping existing instance first")
            stop()
        }
        isRunning.set(true)

        try {
            val sSocket = ServerSocket(lanPort, 50, InetAddress.getByName("0.0.0.0"))
            serverSocket = sSocket
            val pool = Executors.newCachedThreadPool { r ->
                Thread(r, "HotspotBridgeWorker").apply { isDaemon = true }
            }
            executor = pool

            Logx.i(TAG, "Hotspot proxy bridge started on 0.0.0.0:$lanPort -> 127.0.0.1:$targetLocalPort (auth=$authEnabled)")

            pool.execute {
                while (isRunning.get() && !sSocket.isClosed) {
                    try {
                        val client = sSocket.accept()
                        pool.execute {
                            handleClient(client, targetLocalPort, authEnabled, username, password)
                        }
                    } catch (e: Exception) {
                        if (!sSocket.isClosed) {
                            Logx.w(TAG, "Accept error: ${e.message}")
                        }
                    }
                }
            }
        } catch (e: Exception) {
            Logx.e(TAG, "Failed to start hotspot bridge on port $lanPort", e)
            isRunning.set(false)
        }
    }

    @Synchronized
    fun stop() {
        if (!isRunning.getAndSet(false)) return
        Logx.i(TAG, "Stopping hotspot proxy bridge")
        runCatching { serverSocket?.close() }
        serverSocket = null
        runCatching { executor?.shutdownNow() }
        executor = null
    }

    val running: Boolean get() = isRunning.get()

    private fun handleClient(
        clientSocket: Socket,
        targetLocalPort: Int,
        authEnabled: Boolean,
        expectedUser: String,
        expectedPass: String
    ) {
        try {
            clientSocket.soTimeout = 15000
            val cin = clientSocket.getInputStream()
            val cout = clientSocket.getOutputStream()

            // 1. SOCKS5 Method Negotiation
            val ver = cin.read()
            if (ver != 0x05) {
                clientSocket.close()
                return
            }
            val nMethods = cin.read()
            if (nMethods <= 0) {
                clientSocket.close()
                return
            }
            val methods = ByteArray(nMethods)
            cin.readFully(methods)

            val useAuth = authEnabled && expectedUser.isNotEmpty() && expectedPass.isNotEmpty()
            if (useAuth) {
                if (!methods.contains(0x02.toByte())) {
                    // Method 0x02 (Username/Password) not supported by client
                    cout.write(byteArrayOf(0x05, 0xFF.toByte()))
                    cout.flush()
                    clientSocket.close()
                    return
                }
                // Accept Method 0x02
                cout.write(byteArrayOf(0x05, 0x02))
                cout.flush()

                // RFC 1929 Authentication Subnegotiation
                val authVer = cin.read()
                if (authVer != 0x01) {
                    clientSocket.close()
                    return
                }
                val uLen = cin.read()
                if (uLen <= 0) { clientSocket.close(); return }
                val uBytes = ByteArray(uLen)
                cin.readFully(uBytes)
                val user = String(uBytes, Charsets.UTF_8)

                val pLen = cin.read()
                if (pLen < 0) { clientSocket.close(); return }
                val pBytes = ByteArray(pLen)
                cin.readFully(pBytes)
                val pass = String(pBytes, Charsets.UTF_8)

                if (user != expectedUser || pass != expectedPass) {
                    Logx.w(TAG, "Authentication failed from ${clientSocket.inetAddress.hostAddress}")
                    cout.write(byteArrayOf(0x01, 0x01)) // Failure
                    cout.flush()
                    clientSocket.close()
                    return
                }
                // Authentication Success
                cout.write(byteArrayOf(0x01, 0x00))
                cout.flush()
            } else {
                // Method 0x00 (No Authentication)
                cout.write(byteArrayOf(0x05, 0x00))
                cout.flush()
            }

            // 2. Connect to local SOCKS5 proxy
            val targetSocket = Socket(InetAddress.getByName("127.0.0.1"), targetLocalPort)
            targetSocket.soTimeout = 0
            clientSocket.soTimeout = 0

            val tin = targetSocket.getInputStream()
            val tout = targetSocket.getOutputStream()

            // Local SOCKS5 negotiation (upstream OpenFlux accepts 0x05, 0x00)
            tout.write(byteArrayOf(0x05, 0x01, 0x00))
            tout.flush()
            val tVer = tin.read()
            val tMethod = tin.read()
            if (tVer != 0x05 || tMethod != 0x00) {
                runCatching { targetSocket.close() }
                runCatching { clientSocket.close() }
                return
            }

            // 3. Bidirectional pipe between client and local SOCKS5 proxy
            val t1 = Thread({
                try {
                    pipe(cin, tout)
                } finally {
                    runCatching { targetSocket.close() }
                    runCatching { clientSocket.close() }
                }
            }, "HotspotBridge-ClientToTarget")
            val t2 = Thread({
                try {
                    pipe(tin, cout)
                } finally {
                    runCatching { clientSocket.close() }
                    runCatching { targetSocket.close() }
                }
            }, "HotspotBridge-TargetToClient")
            t1.isDaemon = true
            t2.isDaemon = true
            t1.start()
            t2.start()
        } catch (_: Exception) {
            runCatching { clientSocket.close() }
        }
    }

    private fun pipe(input: InputStream, output: OutputStream) {
        val buf = ByteArray(16384)
        try {
            var n: Int
            while (input.read(buf).also { n = it } != -1) {
                output.write(buf, 0, n)
                output.flush()
            }
        } catch (_: Exception) {
        } finally {
            runCatching { input.close() }
            runCatching { output.close() }
        }
    }

    private fun InputStream.readFully(b: ByteArray) {
        var offset = 0
        while (offset < b.size) {
            val count = read(b, offset, b.size - offset)
            if (count < 0) throw java.io.EOFException("Unexpected EOF")
            offset += count
        }
    }
}
