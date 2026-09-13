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
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

class TunnelsViewModel(app: Application) : AndroidViewModel(app) {

    companion object {
        private const val TAG = "TunnelsViewModel"
    }

    private val repo = TunnelRepository(app)

    private var service: IUnifiedService? = null
    private var bound = false
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
        if (running.isActive) stop()

        _active.value = TunnelState.Connecting(tunnel)

        val ctx = getApplication<Application>()
        val prepared = TunnelLinkParser.ensureLocalKeyFile(ctx, tunnel)
        val splitPrefs = io.github.p1neapplexpress.openflux.util.SplitTunnelPreferences(ctx)
        val appBypass = splitPrefs.mode == io.github.p1neapplexpress.openflux.util.SplitTunnelPreferences.MODE_BYPASS
        val selectedApps = if (appBypass) splitPrefs.bypassApps else splitPrefs.proxyApps
        val perApp = selectedApps.isNotEmpty()
        val appList = selectedApps.toTypedArray()

        val cfg = VPNConfig(
            name = prepared.name,
            perApp = perApp,
            appBypass = appBypass,
            appList = appList,
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

        activeTunnelData = prepared

        CoroutineScope(Dispatchers.IO).launch {
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
            try {
                service?.startOpenFluxNative(
                    prepared.transportType,
                    prepared.transportConnPayload.toTypedArray()
                )
            } catch (e: Exception) {
                Logx.e(TAG, "startOpenFluxNative failed", e)
                _active.value = TunnelState.Error("Transport failed: ${e.message}")
                _tunnelHealth.value = TunnelHealth.UNAVAILABLE
                return@launch
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
                return@launch
            }

            Logx.i(TAG, "starting tun2socks")
            _active.value = TunnelState.StartingTun2Socks(tunnel)
            try {
                service?.startTun2Socks()
            } catch (e: Exception) {
                Logx.e(TAG, "startTun2Socks failed", e)
                _active.value = TunnelState.Error("tun2socks failed: ${e.message}")
                _tunnelHealth.value = TunnelHealth.UNAVAILABLE
                return@launch
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

            if (vpnReady) {
                _active.value = TunnelState.Running(tunnel)
                startUptimeCounter()
                checkTunnelHealth(tunnel)
            } else {
                Logx.e(TAG, "tun2socks did not start")
                _active.value = TunnelState.Error("tun2socks did not start")
                _tunnelHealth.value = TunnelHealth.UNAVAILABLE
            }
            refresh()
        }
    }

    fun stop() {
        Logx.i(TAG, "stop()")
        CoroutineScope(Dispatchers.IO).launch {
            try {
                service?.stopOpenFluxNative()
                service?.stopVpn()
            } catch (e: Exception) {
                Logx.e(TAG, "stop failed", e)
            }
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

    fun refresh() {
        val list = repo.load()
        val running = (_active.value as? TunnelState.Running)?.tunnel
        _tunnels.value = list.map { TunnelViewType(it, enabled = it == running) }
        val sel = repo.getSelected()
        _selected.value = sel
        checkTunnelHealth(running ?: sel)
    }

    fun selectTunnel(tunnel: Tunnel) {
        if (_active.value.isActive) {
            stop()
        }
        repo.setSelectedId(tunnel.id)
        _selected.value = tunnel
        checkTunnelHealth(tunnel)
    }

    fun checkSelectedHealth() {
        val running = (_active.value as? TunnelState.Running)?.tunnel
        val target = running ?: _selected.value ?: repo.getSelected()
        checkTunnelHealth(target)
    }

    fun checkTunnelHealth(tunnel: Tunnel?) {
        healthCheckJob?.cancel()
        if (tunnel == null) {
            _tunnelHealth.value = TunnelHealth.UNKNOWN
            return
        }
        _tunnelHealth.value = TunnelHealth.CHECKING
        healthCheckJob = viewModelScope.launch(Dispatchers.IO) {
            val isAvailable = if (_active.value is TunnelState.Running && _active.value.tunnel?.id == tunnel.id) {
                probeRunningTunnel(tunnel)
            } else {
                probeTunnel(tunnel)
            }
            _tunnelHealth.value = if (isAvailable) TunnelHealth.AVAILABLE else TunnelHealth.UNAVAILABLE
        }
    }

    private suspend fun probeRunningTunnel(tunnel: Tunnel): Boolean {
        if (service?.isFServiceRunning() != true || service?.isVpnRunning() != true) {
            return false
        }
        return probeLiveConnectivity()
    }

    private fun probeLiveConnectivity(): Boolean {
        val endpoints = listOf(
            "http://connectivitycheck.gstatic.com/generate_204",
            "http://cp.cloudflare.com/generate_204"
        )
        for (ep in endpoints) {
            try {
                val conn = java.net.URL(ep).openConnection() as java.net.HttpURLConnection
                conn.connectTimeout = 3000
                conn.readTimeout = 3000
                conn.instanceFollowRedirects = false
                try {
                    val code = conn.responseCode
                    if (code in 200..399 || code == 204) {
                        return true
                    }
                } finally {
                    conn.disconnect()
                }
            } catch (_: Exception) {
            }
        }
        return false
    }

    private fun probeTunnel(tunnel: Tunnel): Boolean {
        return try {
            val transportType = tunnel.transportType.lowercase()
            if (transportType == "max" || transportType == "oneme") {
                val token = argValue(tunnel.transportConnPayload, "--maxToken")
                val uid = argValue(tunnel.transportConnPayload, "--maxUid")
                if (token.isBlank() || uid.isBlank() || uid.toLongOrNull() == null) {
                    return false
                }
                java.net.Socket().use { socket ->
                    socket.connect(java.net.InetSocketAddress("ws-api.oneme.ru", 443), 3500)
                    socket.isConnected
                }
            } else {
                val urlStr = argValue(tunnel.transportConnPayload, "--url")
                if (!urlStr.startsWith("http://", ignoreCase = true) && !urlStr.startsWith("https://", ignoreCase = true)) {
                    return false
                }
                val conn = java.net.URL(urlStr).openConnection() as java.net.HttpURLConnection
                conn.connectTimeout = 4000
                conn.readTimeout = 4000
                conn.requestMethod = "GET"
                conn.setRequestProperty("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36")
                conn.setRequestProperty("Accept", "text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8")
                conn.setRequestProperty("Accept-Language", "ru-RU,ru;q=0.9,en-US;q=0.8,en;q=0.7")
                conn.instanceFollowRedirects = true

                val code = try {
                    conn.responseCode
                } catch (_: Exception) {
                    -1
                }

                if (code !in 200..399) {
                    conn.disconnect()
                    return false
                }

                val finalHost = conn.url.host.lowercase()
                if (finalHost.contains("passport.yandex")) {
                    conn.disconnect()
                    return false
                }

                val bodySample = try {
                    conn.inputStream.bufferedReader().use { r ->
                        val buffer = CharArray(16384)
                        val read = r.read(buffer)
                        if (read > 0) String(buffer, 0, read) else ""
                    }
                } catch (_: Exception) {
                    ""
                } finally {
                    conn.disconnect()
                }

                if (urlStr.contains("docs.yandex") || urlStr.contains("disk.yandex") || urlStr.contains("yadi.sk")) {
                    val lower = bodySample.lowercase()
                    if (lower.contains("client-config") || lower.contains("officeactiondata") || lower.contains("publicresource") || lower.contains("docid") || lower.contains("editor_config")) {
                        true
                    } else if (lower.contains("nothing found") || lower.contains("ничего не найдено") || lower.contains("error-content") || lower.contains("not-found")) {
                        false
                    } else {
                        code in 200..399
                    }
                } else {
                    code in 200..399
                }
            }
        } catch (_: Exception) {
            false
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
            while (isActive) {
                _uptimeSeconds.value = (System.currentTimeMillis() - startedAt) / 1000L
                ticks++
                if (ticks % 10 == 0) {
                    val currentTunnel = (_active.value as? TunnelState.Running)?.tunnel
                    if (currentTunnel != null) {
                        val isAlive = probeRunningTunnel(currentTunnel)
                        _tunnelHealth.value = if (isAlive) TunnelHealth.AVAILABLE else TunnelHealth.UNAVAILABLE
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
        try { getApplication<Application>().unbindService(connection) } catch (_: Exception) {}
    }
}
