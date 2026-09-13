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
                if (event is AppEvent.SpeedUpdate) {
                    _rxSpeed.value = event.rxSpeed
                    _txSpeed.value = event.txSpeed
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
                _tunnelHealth.value = TunnelHealth.AVAILABLE
                startUptimeCounter()
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

    fun checkTunnelHealth(tunnel: Tunnel?) {
        healthCheckJob?.cancel()
        if (tunnel == null) {
            _tunnelHealth.value = TunnelHealth.UNKNOWN
            return
        }
        if (_active.value is TunnelState.Running && _active.value.tunnel?.id == tunnel.id) {
            _tunnelHealth.value = TunnelHealth.AVAILABLE
            return
        }
        _tunnelHealth.value = TunnelHealth.CHECKING
        healthCheckJob = viewModelScope.launch(Dispatchers.IO) {
            val isAvailable = probeTunnel(tunnel)
            _tunnelHealth.value = if (isAvailable) TunnelHealth.AVAILABLE else TunnelHealth.UNAVAILABLE
        }
    }

    private fun probeTunnel(tunnel: Tunnel): Boolean {
        return try {
            val urlStr = argValue(tunnel.transportConnPayload, "--url")
            if (urlStr.startsWith("http://") || urlStr.startsWith("https://")) {
                val url = java.net.URL(urlStr)
                val conn = url.openConnection() as java.net.HttpURLConnection
                conn.connectTimeout = 3000
                conn.readTimeout = 3000
                conn.requestMethod = "HEAD"
                conn.instanceFollowRedirects = true
                val code = try {
                    conn.responseCode
                } catch (_: Exception) {
                    -1
                } finally {
                    conn.disconnect()
                }
                if (code in 200..499) {
                    true
                } else if (code == -1) {
                    val port = if (url.port != -1) url.port else (if (url.protocol == "https") 443 else 80)
                    java.net.Socket().use { socket ->
                        socket.connect(java.net.InetSocketAddress(url.host, port), 3000)
                        socket.isConnected
                    }
                } else {
                    false
                }
            } else {
                true
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
            while (isActive) {
                _uptimeSeconds.value = (System.currentTimeMillis() - startedAt) / 1000L
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
