package io.github.p1neapplexpress.openflux.ui

import android.app.Application
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.os.Build
import android.os.IBinder
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import io.github.p1neapplexpress.openflux.IUnifiedService
import io.github.p1neapplexpress.openflux.data.Tunnel
import io.github.p1neapplexpress.openflux.data.TunnelHealth
import io.github.p1neapplexpress.openflux.data.TunnelRepository
import io.github.p1neapplexpress.openflux.data.TunnelState
import io.github.p1neapplexpress.openflux.data.TunnelViewType
import io.github.p1neapplexpress.openflux.event.AppEvent
import io.github.p1neapplexpress.openflux.event.EventBus
import io.github.p1neapplexpress.openflux.service.SocksVpnService
import io.github.p1neapplexpress.openflux.util.Logx
import io.github.p1neapplexpress.openflux.util.TunnelLinkParser
import io.github.p1neapplexpress.openflux.vpn.VPNConfig
import io.github.p1neapplexpress.openflux.vpn.VpnIntentFactory
import java.io.File
import java.util.concurrent.ConcurrentHashMap
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class TunnelsViewModel(app: Application) : AndroidViewModel(app) {

    companion object {
        private const val TAG = "TunnelsViewModel"
    }

    private val repo = TunnelRepository(app)

    @Volatile private var service: IUnifiedService? = null
    @Volatile private var bound = false
    private var activeTunnelData: Tunnel? = null

    private val connection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName?, binder: IBinder?) {
            service = IUnifiedService.Stub.asInterface(binder)
            bound = true
            Logx.d(TAG, "service connected")
        }

        override fun onServiceDisconnected(name: ComponentName?) {
            service = null
            bound = false
            Logx.d(TAG, "service disconnected")
            if (_active.value !is TunnelState.Idle) {
                _active.value = TunnelState.Idle
                _rxSpeed.value = 0L
                _txSpeed.value = 0L
                stopUptimeCounter()
                refresh()
            }
        }
    }

    private val _tunnels = MutableStateFlow<List<TunnelViewType>>(emptyList())
    val tunnels: StateFlow<List<TunnelViewType>> = _tunnels.asStateFlow()

    private val _active = MutableStateFlow<TunnelState>(TunnelState.Idle)
    val active: StateFlow<TunnelState> = _active.asStateFlow()

    private val _uptimeSeconds = MutableStateFlow(0L)
    val uptimeSeconds: StateFlow<Long> = _uptimeSeconds.asStateFlow()

    private val _rxSpeed = MutableStateFlow(0L)
    val rxSpeed: StateFlow<Long> = _rxSpeed.asStateFlow()

    private val _txSpeed = MutableStateFlow(0L)
    val txSpeed: StateFlow<Long> = _txSpeed.asStateFlow()

    private val _tunnelHealth = MutableStateFlow(TunnelHealth.UNKNOWN)
    val tunnelHealth: StateFlow<TunnelHealth> = _tunnelHealth.asStateFlow()

    private val _healthMap = MutableStateFlow<Map<Long, TunnelHealth>>(emptyMap())
    val healthMap: StateFlow<Map<Long, TunnelHealth>> = _healthMap.asStateFlow()

    private val healthCache = ConcurrentHashMap<Long, TunnelHealth>()

    fun getTunnelHealth(tunnelId: Long): TunnelHealth = healthCache[tunnelId] ?: TunnelHealth.UNKNOWN

    private val _pingMap = MutableStateFlow<Map<Long, Long>>(emptyMap())
    val pingMap: StateFlow<Map<Long, Long>> = _pingMap.asStateFlow()
    private val pingCache = ConcurrentHashMap<Long, Long>()

    fun getTunnelPing(tunnelId: Long): Long? = pingCache[tunnelId]

    private val _selected = MutableStateFlow<Tunnel?>(null)
    val selected: StateFlow<Tunnel?> = _selected.asStateFlow()

    val selectedTunnelId: Long? get() = _selected.value?.id

    private var uptimeJob: Job? = null
    private var healthCheckJob: Job? = null

    init {
        refresh()
        viewModelScope.launch {
            EventBus.events.collect { event ->
                when (event) {
                    is AppEvent.SpeedUpdate -> {
                        _rxSpeed.value = event.rxSpeed
                        _txSpeed.value = event.txSpeed
                    }
                    is AppEvent.TransportDisconnected -> {
                        if (_active.value.isActive) {
                            Logx.e(TAG, "Transport disconnected event received")
                            _active.value = TunnelState.Error("Transport disconnected")
                            _tunnelHealth.value = TunnelHealth.UNAVAILABLE
                            stopUptimeCounter()
                            refresh()
                        }
                    }
                    is AppEvent.VpnDisconnected -> {
                        if (_active.value.isActive) {
                            Logx.i(TAG, "VpnDisconnected event received")
                            val ctx = getApplication<Application>()
                            try { ctx.unbindService(connection) } catch (_: Exception) {}
                            bound = false
                            service = null
                            activeTunnelData = null
                            _active.value = TunnelState.Idle
                            _rxSpeed.value = 0L
                            _txSpeed.value = 0L
                            stopUptimeCounter()
                            refresh()
                        }
                    }
                    else -> {}
                }
            }
        }
    }

    fun startCurrent() {
        val tunnel = _active.value.tunnel
            ?: _selected.value
            ?: repo.getSelected()
            ?: return
        startTunnel(tunnel)
    }

    fun startTunnel(tunnel: Tunnel) {
        val running = _active.value
        if (running is TunnelState.Running && running.tunnel == tunnel) return

        viewModelScope.launch {
            if (_active.value.isActive) {
                stopInternal()
                delay(150)
            }
            startTunnelInternal(tunnel)
        }
    }

    private fun startTunnelInternal(tunnel: Tunnel) {
        _active.value = TunnelState.Connecting(tunnel)
        _tunnelHealth.value = TunnelHealth.CHECKING

        val ctx = getApplication<Application>()
        val prepared = TunnelLinkParser.ensureLocalKeyFile(ctx, tunnel)
        val splitPrefs = io.github.p1neapplexpress.openflux.util.SplitTunnelPreferences(ctx)
        val appBypass = splitPrefs.mode == io.github.p1neapplexpress.openflux.util.SplitTunnelPreferences.MODE_BYPASS
        val selectedApps = if (appBypass) splitPrefs.bypassApps else splitPrefs.proxyApps
        val perApp = selectedApps.isNotEmpty()
        val appList = selectedApps.toTypedArray()

        val appSettings = io.github.p1neapplexpress.openflux.util.AppSettings(ctx)
        val session = io.github.p1neapplexpress.openflux.util.LocalSocksSession.generateNew(
            authEnabled = appSettings.socks5AuthEnabled,
            customUser = appSettings.socks5CustomUser,
            customPass = appSettings.socks5CustomPass,
            shareLan = appSettings.shareLanProxy,
            customPort = if (appSettings.shareLanProxy) appSettings.lanProxyPort else null
        )

        val modifiedPayload = prepared.transportConnPayload.toMutableList()
        fun removeFlag(flag: String) {
            val idx = modifiedPayload.indexOf(flag)
            if (idx != -1) {
                if (idx + 1 < modifiedPayload.size) {
                    modifiedPayload.removeAt(idx + 1)
                }
                modifiedPayload.removeAt(idx)
            }
        }
        removeFlag("-socks5")
        removeFlag("--socks5")
        removeFlag("-socks5-user")
        removeFlag("--socks5-user")
        removeFlag("-socks5-pass")
        removeFlag("--socks5-pass")

        modifiedPayload.add("--socks5")
        modifiedPayload.add("127.0.0.1:${session.port}")

        val (remoteHost, remotePort) = io.github.p1neapplexpress.openflux.vpn.TunnelEndpointHelper.extractTarget(prepared)
        val cfg = VPNConfig(
            name = prepared.name,
            port = session.port,
            username = if (session.isAuthEnabled && session.password.isNotEmpty()) session.username else null,
            password = if (session.isAuthEnabled && session.password.isNotEmpty()) session.password else null,
            dns = appSettings.primaryDns,
            secondaryDns = appSettings.secondaryDns,
            mtu = appSettings.mtu,
            ipv6Proxy = appSettings.ipv6Proxy,
            perApp = perApp,
            appBypass = appBypass,
            appList = appList,
            bypassLan = appSettings.bypassLan,
            killSwitch = appSettings.killSwitch,
            ipType = appSettings.ipType,
            remoteServer = remoteHost,
            remotePort = remotePort,
            transportType = prepared.transportType,
            transportPayload = modifiedPayload.toTypedArray(),
        )
        val intent = VpnIntentFactory.build(ctx, cfg)

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            ctx.startForegroundService(intent)
        } else {
            ctx.startService(intent)
        }

        ctx.bindService(
            Intent(ctx, SocksVpnService::class.java),
            connection,
            Context.BIND_AUTO_CREATE,
        )

        activeTunnelData = prepared.copy(transportConnPayload = modifiedPayload)
        val isDebug = modifiedPayload.contains("--debug")
        Logx.setVerbose(isDebug)
        Logx.i(TAG, "logging initialized: verbose=$isDebug (level: ${if (isDebug) "DEBUG" else "INFO"})")

        viewModelScope.launch(Dispatchers.IO) {
            var attempts = 0
            while (!bound && attempts < 100) {
                delay(50)
                attempts++
            }
            if (!bound || service == null) {
                Logx.e(TAG, "failed to bind to service")
                _active.value = TunnelState.Error("Service not connected")
                _tunnelHealth.value = TunnelHealth.UNAVAILABLE
                return@launch
            }
            Logx.i(TAG, "service bound, starting transport")

            _active.value = TunnelState.StartingTransport(prepared)
            if (service?.isFServiceRunning() != true) {
                try {
                    service?.startOpenFluxNative(
                        prepared.transportType,
                        modifiedPayload.toTypedArray()
                    )
                } catch (e: Exception) {
                    Logx.e(TAG, "startOpenFluxNative failed", e)
                    _active.value = TunnelState.Error("Transport failed: ${e.message}")
                    _tunnelHealth.value = TunnelHealth.UNAVAILABLE
                    triggerFailoverIfNeeded(tunnel)
                    return@launch
                }
            }

            var transportReady = false
            for (i in 1..40) {
                delay(250)
                try {
                    if (service?.isFServiceRunning() == true) {
                        transportReady = true
                        Logx.i(TAG, "transport started after ${i * 250}ms")
                        break
                    }
                } catch (e: Exception) {
                    Logx.e(TAG, "isFServiceRunning threw", e)
                }
            }
            if (!transportReady) {
                Logx.e(TAG, "transport did not start")
                _active.value = TunnelState.Error("Transport did not start")
                _tunnelHealth.value = TunnelHealth.UNAVAILABLE
                triggerFailoverIfNeeded(tunnel)
                return@launch
            }

            Logx.i(TAG, "starting tun2socks")
            _active.value = TunnelState.StartingTun2Socks(tunnel)
            if (service?.isVpnRunning() != true) {
                try {
                    service?.startTun2Socks()
                } catch (e: Exception) {
                    Logx.e(TAG, "startTun2Socks failed", e)
                    _active.value = TunnelState.Error("tun2socks failed: ${e.message}")
                    _tunnelHealth.value = TunnelHealth.UNAVAILABLE
                    triggerFailoverIfNeeded(tunnel)
                    return@launch
                }
            }

            var vpnReady = false
            for (i in 1..40) {
                delay(250)
                try {
                    if (service?.isVpnRunning() == true) {
                        vpnReady = true
                        Logx.i(TAG, "tun2socks started after ${i * 250}ms")
                        break
                    }
                } catch (e: Exception) {
                    Logx.e(TAG, "isVpnRunning threw", e)
                }
            }
            if (!vpnReady) {
                Logx.e(TAG, "tun2socks did not start")
                _active.value = TunnelState.Error("tun2socks did not start")
                _tunnelHealth.value = TunnelHealth.UNAVAILABLE
                triggerFailoverIfNeeded(tunnel)
                return@launch
            } else {
                _active.value = TunnelState.Running(tunnel)
                _tunnelHealth.value = TunnelHealth.CHECKING
                healthCache[tunnel.id] = TunnelHealth.CHECKING
                _healthMap.value = healthCache.toMap()
                startUptimeCounter()
                viewModelScope.launch(Dispatchers.IO) {
                    kotlinx.coroutines.delay(1500L)
                    probeRunningTunnel(tunnel)
                }
            }
            refresh()
        }
    }

    suspend fun stopInternal() {
        Logx.i(TAG, "stopInternal()")
        withContext(Dispatchers.IO) {
            val ctx = getApplication<Application>()
            try {
                service?.stopOpenFluxNative()
                service?.stopVpn()
            } catch (e: Exception) {
                Logx.e(TAG, "stop failed", e)
            }
            try { ctx.unbindService(connection) } catch (_: Exception) {}
            bound = false
            service = null
            activeTunnelData = null
            Logx.setVerbose(false)

            try {
                val disconnectIntent = Intent(ctx, SocksVpnService::class.java).apply {
                    action = SocksVpnService.ACTION_DISCONNECT
                }
                ctx.startService(disconnectIntent)
            } catch (e: Exception) {
                Logx.w(TAG, "Failed sending disconnect intent: ${e.message}")
            }
        }
        _active.value = TunnelState.Idle
        _rxSpeed.value = 0L
        _txSpeed.value = 0L
        stopUptimeCounter()
        refresh()
    }

    fun stop() {
        Logx.i(TAG, "stop()")
        viewModelScope.launch {
            stopInternal()
        }
    }

    fun refresh() {
        val list = repo.load()
        val running = (_active.value as? TunnelState.Running)?.tunnel
        _tunnels.value = list.map { TunnelViewType(it, enabled = it == running) }
        val sel = repo.getSelected()
        _selected.value = sel
        checkTunnelHealth(running ?: sel)
        probeAllTunnels()
    }

    fun probeAllTunnels() {
        val tunnels = repo.load()
        val runningId = (_active.value as? TunnelState.Running)?.tunnel?.id
        viewModelScope.launch(Dispatchers.IO) {
            coroutineScope {
                tunnels.map { t ->
                    async {
                        if (t.id == runningId) {
                            healthCache[t.id] = _tunnelHealth.value
                        } else {
                            val ok = probeTunnel(t)
                            healthCache[t.id] = if (ok) TunnelHealth.AVAILABLE else TunnelHealth.UNAVAILABLE
                        }
                    }
                }.awaitAll()
            }
            _healthMap.value = healthCache.toMap()
            _pingMap.value = pingCache.toMap()
        }
    }

    fun selectTunnel(tunnel: Tunnel) {
        if (_active.value.isActive) {
            stop()
        }
        repo.setSelectedId(tunnel.id)
        _selected.value = tunnel
        checkTunnelHealth(tunnel, force = true)
    }

    fun checkSelectedHealth(force: Boolean = false) {
        val running = (_active.value as? TunnelState.Running)?.tunnel
        val target = running ?: _selected.value ?: repo.getSelected()
        if (!force && target != null && healthCache.containsKey(target.id) && _active.value !is TunnelState.Running) {
            _tunnelHealth.value = healthCache[target.id] ?: TunnelHealth.UNKNOWN
            return
        }
        if (_active.value is TunnelState.Running) {
            if (!force) return
        }
        checkTunnelHealth(target, force = force)
    }

    fun checkTunnelHealth(tunnel: Tunnel?, force: Boolean = false) {
        healthCheckJob?.cancel()
        if (tunnel == null) {
            _tunnelHealth.value = TunnelHealth.UNKNOWN
            return
        }
        if (!force && healthCache.containsKey(tunnel.id) && _active.value !is TunnelState.Running) {
            _tunnelHealth.value = healthCache[tunnel.id] ?: TunnelHealth.UNKNOWN
            return
        }
        if (_active.value is TunnelState.Running && _active.value.tunnel?.id == tunnel.id) {
            if (!force) {
                _tunnelHealth.value = healthCache[tunnel.id] ?: TunnelHealth.AVAILABLE
                return
            }
            _tunnelHealth.value = TunnelHealth.CHECKING
            healthCheckJob = viewModelScope.launch(Dispatchers.IO) {
                val isAvailable = probeRunningTunnel(tunnel)
                val status = if (isAvailable) TunnelHealth.AVAILABLE else TunnelHealth.UNAVAILABLE
                healthCache[tunnel.id] = status
                _tunnelHealth.value = status
                _healthMap.value = healthCache.toMap()
            }
            return
        }
        _tunnelHealth.value = TunnelHealth.CHECKING
        healthCheckJob = viewModelScope.launch(Dispatchers.IO) {
            val isAvailable = probeTunnel(tunnel)
            val status = if (isAvailable) TunnelHealth.AVAILABLE else TunnelHealth.UNAVAILABLE
            healthCache[tunnel.id] = status
            _tunnelHealth.value = status
            _healthMap.value = healthCache.toMap()
        }
    }

    private suspend fun probeRunningTunnel(tunnel: Tunnel): Boolean = withContext(Dispatchers.IO) {
        if (service?.isFServiceRunning() != true || service?.isVpnRunning() != true) {
            return@withContext false
        }
        for (attempt in 1..3) {
            val ok = checkRunningHealthInternal(tunnel)
            if (ok) return@withContext true
            if (attempt < 3) kotlinx.coroutines.delay(1500L)
        }
        false
    }

    private fun checkRunningHealthInternal(tunnel: Tunnel): Boolean {
        if (service?.isFServiceRunning() != true || service?.isVpnRunning() != true) {
            return false
        }

        val session = io.github.p1neapplexpress.openflux.util.LocalSocksSession.getActive()
        if (session.port !in 1..65535) {
            return false
        }

        // 1. Verify that the local SOCKS proxy is listening and responsive
        val socksPortOpen = try {
            java.net.Socket().use { s ->
                s.connect(java.net.InetSocketAddress("127.0.0.1", session.port), 1500)
                s.isConnected
            }
        } catch (_: Exception) {
            false
        }
        if (!socksPortOpen) {
            healthCache[tunnel.id] = TunnelHealth.UNAVAILABLE
            _tunnelHealth.value = TunnelHealth.UNAVAILABLE
            pingCache.remove(tunnel.id)
            _pingMap.value = pingCache.toMap()
            triggerFailoverIfNeeded(tunnel)
            return false
        }

        // 2. Verify true end-to-end connectivity through SOCKS5 proxy via bridge to remote VPS
        val socksProxy = java.net.Proxy(java.net.Proxy.Type.SOCKS, java.net.InetSocketAddress("127.0.0.1", session.port))
        val latency = probeLiveConnectivityWithLatency(socksProxy)
        if (latency < 0) {
            healthCache[tunnel.id] = TunnelHealth.UNAVAILABLE
            _tunnelHealth.value = TunnelHealth.UNAVAILABLE
            pingCache.remove(tunnel.id)
            _pingMap.value = pingCache.toMap()
            triggerFailoverIfNeeded(tunnel)
            return false
        }

        pingCache[tunnel.id] = latency
        _pingMap.value = pingCache.toMap()
        healthCache[tunnel.id] = TunnelHealth.AVAILABLE
        _tunnelHealth.value = TunnelHealth.AVAILABLE
        return true
    }

    private fun probeLiveConnectivityWithLatency(proxy: java.net.Proxy): Long {
        val endpoints = listOf(
            "http://cp.cloudflare.com/generate_204",
            "http://connectivitycheck.gstatic.com/generate_204",
            "http://detectportal.firefox.com/success.txt"
        )
        for (ep in endpoints) {
            try {
                val url = java.net.URL(ep)
                val conn = url.openConnection(proxy) as java.net.HttpURLConnection
                conn.connectTimeout = 4000
                conn.readTimeout = 4000
                conn.instanceFollowRedirects = false
                conn.setRequestProperty("User-Agent", "Fluxon/1.1.0")
                conn.setRequestProperty("Connection", "close")
                val t0 = android.os.SystemClock.elapsedRealtime()
                try {
                    val code = conn.responseCode
                    if (code in 200..399 || code == 204) {
                        val rtt = android.os.SystemClock.elapsedRealtime() - t0
                        return rtt.coerceAtLeast(1L)
                    }
                } finally {
                    conn.disconnect()
                }
            } catch (e: Exception) {
                Logx.d(TAG, "End-to-end probe failed for $ep: ${e.message}")
            }
        }
        return -1L
    }

    private fun probeTunnel(tunnel: Tunnel): Boolean {
        val isCurrentRunning = (_active.value is TunnelState.Running) &&
                (_active.value.tunnel?.id == tunnel.id)
        return if (isCurrentRunning) {
            val session = io.github.p1neapplexpress.openflux.util.LocalSocksSession.getActive()
            val socksProxy = java.net.Proxy(java.net.Proxy.Type.SOCKS, java.net.InetSocketAddress("127.0.0.1", session.port))
            val latency = probeLiveConnectivityWithLatency(socksProxy)
            if (latency > 0) {
                pingCache[tunnel.id] = latency
                _pingMap.value = pingCache.toMap()
                true
            } else {
                pingCache.remove(tunnel.id)
                _pingMap.value = pingCache.toMap()
                false
            }
        } else {
            val target = io.github.p1neapplexpress.openflux.vpn.TunnelEndpointHelper.extractTarget(tunnel)
            val startMs = android.os.SystemClock.elapsedRealtime()
            val reachable = try {
                java.net.Socket().use { socket ->
                    socket.connect(java.net.InetSocketAddress(target.first, target.second), 2500)
                    socket.isConnected
                }
            } catch (_: Exception) {
                false
            }
            if (reachable) {
                val latency = (android.os.SystemClock.elapsedRealtime() - startMs).coerceAtLeast(1L)
                pingCache[tunnel.id] = latency
                _pingMap.value = pingCache.toMap()
                true
            } else {
                pingCache.remove(tunnel.id)
                _pingMap.value = pingCache.toMap()
                false
            }
        }
    }

    private fun triggerFailoverIfNeeded(failedTunnel: Tunnel) {
        val appSettings = io.github.p1neapplexpress.openflux.util.AppSettings(getApplication())
        if (!appSettings.autoFailover) return

        val all = repo.load()
        if (all.size <= 1) return
        val nextTunnel = all.firstOrNull { it.id != failedTunnel.id } ?: return

        Logx.i(TAG, "Failover triggered: switching from '${failedTunnel.name}' to '${nextTunnel.name}'")
        EventBus.dispatch(io.github.p1neapplexpress.openflux.event.AppEvent.LogMessage("[I] Failover: переключение на '${nextTunnel.name}'..."))

        viewModelScope.launch(Dispatchers.Main) {
            stop()
            kotlinx.coroutines.delay(1000L)
            selectTunnel(nextTunnel)
            startTunnel(nextTunnel)
        }
    }

    private fun argValue(payload: List<String>, key: String): String {
        val i = payload.indexOf(key)
        return if (i != -1 && i + 1 < payload.size) payload[i + 1] else ""
    }

    fun addTunnel(tunnel: Tunnel) {
        val current = repo.load().toMutableList()
        val uniqueTunnel = if (current.any { it.id == tunnel.id }) {
            tunnel.copy(id = System.currentTimeMillis())
        } else {
            tunnel
        }
        val prepared = TunnelLinkParser.ensureLocalKeyFile(getApplication(), uniqueTunnel)
        current.add(prepared)
        repo.save(current)
        refresh()
    }

    fun removeTunnel(tunnel: Tunnel) {
        if (_active.value.tunnel == tunnel) stop()
        val current = repo.load().toMutableList()
        current.removeAll { it.id == tunnel.id }
        repo.save(current)
        runCatching { File(getApplication<Application>().filesDir, "key_${tunnel.id}.txt").delete() }
        refresh()
    }

    fun updateTunnel(old: Tunnel, new: Tunnel) {
        val current = repo.load().toMutableList()
        val idx = current.indexOfFirst { it.id == old.id }
        if (idx < 0) return
        if (_active.value.tunnel == old) stop()
        val prepared = TunnelLinkParser.ensureLocalKeyFile(getApplication(), new)
        current[idx] = prepared
        repo.save(current)
        refresh()
    }

    private fun startUptimeCounter() {
        uptimeJob?.cancel()
        _uptimeSeconds.value = 0L
        uptimeJob = viewModelScope.launch {
            val startedAt = System.currentTimeMillis()
            var ticks = 0
            var consecutiveFailures = 0
            while (isActive) {
                _uptimeSeconds.value = (System.currentTimeMillis() - startedAt) / 1000L
                ticks++
                // Check once every 60 seconds (1 minute)
                if (ticks >= 60 && ticks % 60 == 0) {
                    val currentTunnel = (_active.value as? TunnelState.Running)?.tunnel
                    if (currentTunnel != null) {
                        val isAlive = probeRunningTunnel(currentTunnel)
                        if (isAlive) {
                            consecutiveFailures = 0
                            if (_tunnelHealth.value != TunnelHealth.AVAILABLE) {
                                _tunnelHealth.value = TunnelHealth.AVAILABLE
                                healthCache[currentTunnel.id] = TunnelHealth.AVAILABLE
                                _healthMap.value = healthCache.toMap()
                            }
                        } else {
                            consecutiveFailures++
                            if (service?.isFServiceRunning() != true || service?.isVpnRunning() != true || consecutiveFailures >= 2) {
                                _tunnelHealth.value = TunnelHealth.UNAVAILABLE
                                healthCache[currentTunnel.id] = TunnelHealth.UNAVAILABLE
                                _healthMap.value = healthCache.toMap()
                            }
                        }
                    }
                }
                delay(1_000L)
            }
        }
    }

    private fun stopUptimeCounter() {
        uptimeJob?.cancel()
        uptimeJob = null
        _uptimeSeconds.value = 0L
    }

    override fun onCleared() {
        super.onCleared()
        stopUptimeCounter()
        healthCheckJob?.cancel()
        healthCheckJob = null
        try { getApplication<Application>().unbindService(connection) } catch (_: Exception) {}
    }
}
