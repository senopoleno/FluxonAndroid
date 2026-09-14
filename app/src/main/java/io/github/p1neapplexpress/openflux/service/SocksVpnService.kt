package io.github.p1neapplexpress.openflux.service

import android.annotation.SuppressLint
import android.content.Intent
import android.widget.Toast
import io.github.p1neapplexpress.openflux.R
import io.github.p1neapplexpress.openflux.util.AppSettings
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import android.os.IBinder
import io.github.p1neapplexpress.openflux.IUnifiedService
import io.github.p1neapplexpress.openflux.event.AppEvent
import io.github.p1neapplexpress.openflux.event.EventBus
import io.github.p1neapplexpress.openflux.NativeBridge
import io.github.p1neapplexpress.openflux.util.Constants
import io.github.p1neapplexpress.openflux.util.LocalSocksSession
import io.github.p1neapplexpress.openflux.util.Logx

@SuppressLint("VpnServicePolicy")
class SocksVpnService : android.net.VpnService() {

    companion object {
        const val TAG = "SocksVpnService"
        const val ACTION_DISCONNECT = "io.github.p1neapplexpress.openflux.ACTION_DISCONNECT"
        const val ACTION_CHECK_PING = "io.github.p1neapplexpress.openflux.ACTION_CHECK_PING"
    }

    private lateinit var vpn: VpnServiceController
    private lateinit var supervisor: NativeProcessSupervisor
    private lateinit var tun2socks: Tun2SocksLauncher
    private lateinit var notifications: VpnNotificationManager

    @Volatile private var lastIntent: Intent? = null
    private val serviceScope = kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.Dispatchers.Main + kotlinx.coroutines.SupervisorJob())
    private var pingJob: kotlinx.coroutines.Job? = null

    private val binder = object : IUnifiedService.Stub() {
        override fun isVpnRunning(): Boolean = vpn.isRunning.get()
        override fun stopVpn() = stopEverything()
        override fun isFServiceRunning(): Boolean = supervisor.isConnected
        override fun stopOpenFluxNative() = supervisor.stop()

        override fun startOpenFluxNative(transport: String?, args: Array<String>) {
            transport ?: return
            supervisor.start(transport, args.toList())
        }

        override fun startTun2Socks() {
            synchronized(this@SocksVpnService) {
                val fd = vpn.fd
                if (fd <= 0) {
                    Logx.e(TAG, "no tun fd; aborting tun2socks start")
                    return
                }
                val i = lastIntent ?: run {
                    Logx.e(TAG, "no lastIntent; aborting tun2socks start")
                    return
                }

                val ok = tun2socks.start(
                    fd = fd,
                    server = i.getStringExtra(Constants.INTENT_SERVER) ?: "127.0.0.1",
                    port = i.getIntExtra(Constants.INTENT_PORT, 1080),
                    username = i.getStringExtra(Constants.INTENT_USERNAME),
                    password = i.getStringExtra(Constants.INTENT_PASSWORD),
                    dns = i.getStringExtra(Constants.INTENT_DNS) ?: "8.8.8.8",
                    secondaryDns = i.getStringExtra(Constants.INTENT_SECONDARY_DNS),
                    dnsPort = i.getIntExtra(Constants.INTENT_DNS_PORT, 53),
                    ipv6 = i.getBooleanExtra(Constants.INTENT_IPV6_PROXY, false),
                    udpgw = i.getStringExtra(Constants.INTENT_UDP_GW),
                    mtu = AppSettings(this@SocksVpnService).mtu,
                )

                if (ok) {
                    vpn.isRunning.set(true)
                    notifications.startSpeedUpdates()
                    EventBus.dispatch(AppEvent.LogMessage("[I] tun2socks running"))
                    Logx.i(TAG, "tun2socks running")
                } else {
                    Logx.e(TAG, "tun2socks failed")
                    EventBus.dispatch(AppEvent.LogMessage("[E] tun2socks failed"))
                    stopEverything()
                }
            }
        }

        override fun getFd(): Int = vpn.fd
    }

    override fun onCreate() {
        super.onCreate()
        NativeBridge.ensureLoaded(applicationContext)
        vpn = VpnServiceController(this)
        supervisor = NativeProcessSupervisor(applicationContext)
        tun2socks = Tun2SocksLauncher(applicationContext)
        notifications = VpnNotificationManager(this)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        intent ?: return START_NOT_STICKY
        if (intent.action == ACTION_DISCONNECT) {
            Logx.i(TAG, "ACTION_DISCONNECT received from notification")
            stopEverything()
            return START_NOT_STICKY
        }
        if (intent.action == ACTION_CHECK_PING) {
            Logx.i(TAG, "ACTION_CHECK_PING received from notification")
            handlePingRequest()
            return START_STICKY
        }
        lastIntent = intent
        val tunnelName = intent.getStringExtra(Constants.INTENT_NAME)
        notifications.startForeground(tunnelName)

        if (vpn.isConfigured()) {
            Logx.d(TAG, "VPN already configured, ignoring")
            return START_STICKY
        }

        vpn.configure(intent)
        EventBus.dispatch(AppEvent.LogMessage("[S] VPN configured"))
        Logx.i(TAG, "VPN configured")

        val transportType = intent.getStringExtra(Constants.INTENT_TRANSPORT_TYPE)
        val transportPayload = intent.getStringArrayExtra(Constants.INTENT_TRANSPORT_PAYLOAD)
        if (!transportType.isNullOrBlank() && transportPayload != null && transportPayload.isNotEmpty()) {
            serviceScope.launch(Dispatchers.IO) {
                try {
                    EventBus.dispatch(AppEvent.LogMessage("[I] Starting native transport ($transportType)..."))
                    supervisor.start(transportType, transportPayload.toList())

                    var transportReady = false
                    for (step in 1..40) {
                        kotlinx.coroutines.delay(250)
                        if (supervisor.isConnected) {
                            transportReady = true
                            Logx.i(TAG, "Transport ready after ${step * 250}ms")
                            break
                        }
                    }

                    if (!transportReady) {
                        Logx.e(TAG, "Transport failed to start within timeout")
                        EventBus.dispatch(AppEvent.LogMessage("[E] Transport timeout"))
                        return@launch
                    }

                    withContext(Dispatchers.Main) {
                        binder.startTun2Socks()
                    }

                    val appSettings = AppSettings(applicationContext)
                    if (appSettings.shareLanProxy) {
                        val session = LocalSocksSession.getActive()
                        HotspotProxyBridge.start(
                            lanPort = appSettings.lanProxyPort,
                            targetLocalPort = session.port,
                            authEnabled = appSettings.socks5AuthEnabled,
                            username = session.username,
                            password = session.password
                        )
                    }
                } catch (e: Exception) {
                    Logx.e(TAG, "Error in automatic service startup", e)
                }
            }
        }
        return START_STICKY
    }

    override fun onBind(intent: Intent?): IBinder = binder

    override fun onRevoke() {
        Logx.w(TAG, "onRevoke")
        stopEverything()
        super.onRevoke()
    }

    override fun onDestroy() {
        serviceScope.cancel()
        stopEverything()
        super.onDestroy()
    }


    private fun handlePingRequest() {
        pingJob?.cancel()
        notifications.updatePing(-1L)
        pingJob = serviceScope.launch {
            val port = lastIntent?.getIntExtra(Constants.INTENT_PORT, 1080) ?: 1080
            val pingMs = withContext(Dispatchers.IO) {
                runCatching {
                    val socksProxy = java.net.Proxy(
                        java.net.Proxy.Type.SOCKS,
                        java.net.InetSocketAddress("127.0.0.1", port)
                    )
                    val t0 = android.os.SystemClock.elapsedRealtime()
                    java.net.Socket(socksProxy).use { s ->
                        s.connect(java.net.InetSocketAddress("1.1.1.1", 80), 3500)
                    }
                    (android.os.SystemClock.elapsedRealtime() - t0).coerceAtLeast(1L)
                }.getOrElse {
                    runCatching {
                        val socksProxy = java.net.Proxy(
                            java.net.Proxy.Type.SOCKS,
                            java.net.InetSocketAddress("127.0.0.1", port)
                        )
                        val t0 = android.os.SystemClock.elapsedRealtime()
                        java.net.Socket(socksProxy).use { s ->
                            s.connect(java.net.InetSocketAddress("8.8.8.8", 53), 3500)
                        }
                        (android.os.SystemClock.elapsedRealtime() - t0).coerceAtLeast(1L)
                    }.getOrDefault(-1L)
                }
            }

            if (!isActive) return@launch

            if (pingMs >= 0) {
                notifications.updatePing(pingMs)
                Toast.makeText(
                    applicationContext,
                    getString(R.string.notify_ping_format, pingMs),
                    Toast.LENGTH_SHORT
                ).show()
            } else {
                notifications.updatePing(-2L)
                Toast.makeText(
                    applicationContext,
                    getString(R.string.notify_ping_timeout),
                    Toast.LENGTH_SHORT
                ).show()
            }
        }
    }

    private fun stopEverything() {
        Logx.i(TAG, "stopEverything")
        EventBus.dispatch(AppEvent.VpnDisconnected)
        notifications.stopSpeedUpdates()
        runCatching { HotspotProxyBridge.stop() }
        runCatching { tun2socks.stop() }
        runCatching { supervisor.stop() }
        runCatching { vpn.stop() }
        runCatching { LocalSocksSession.clearAuthenticator() }
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }
}
