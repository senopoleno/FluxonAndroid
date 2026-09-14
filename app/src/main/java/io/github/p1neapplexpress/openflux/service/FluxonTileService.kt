package io.github.p1neapplexpress.openflux.service

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.os.Build
import android.os.IBinder
import android.service.quicksettings.Tile
import android.service.quicksettings.TileService
import androidx.annotation.RequiresApi
import io.github.p1neapplexpress.openflux.IUnifiedService
import io.github.p1neapplexpress.openflux.R
import io.github.p1neapplexpress.openflux.data.TunnelRepository
import io.github.p1neapplexpress.openflux.ui.MainActivity
import io.github.p1neapplexpress.openflux.util.AppSettings
import io.github.p1neapplexpress.openflux.util.SplitTunnelPreferences
import io.github.p1neapplexpress.openflux.util.TunnelLinkParser
import io.github.p1neapplexpress.openflux.vpn.VPNConfig
import io.github.p1neapplexpress.openflux.vpn.VpnIntentFactory
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch

@RequiresApi(Build.VERSION_CODES.N)
class FluxonTileService : TileService() {

    private var unifiedService: IUnifiedService? = null
    private var bound = false
    private var tileScope: CoroutineScope? = null
    private var tileJob: Job? = null

    private val serviceConnection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName?, service: IBinder?) {
            unifiedService = IUnifiedService.Stub.asInterface(service)
            bound = true
            updateTileState()
        }

        override fun onServiceDisconnected(name: ComponentName?) {
            unifiedService = null
            bound = false
            updateTileState()
        }
    }

    override fun onStartListening() {
        super.onStartListening()
        bindVpnService()
        updateTileState()
        tileScope?.cancel()
        val scope = CoroutineScope(Dispatchers.Main + Job())
        tileScope = scope
        tileJob = scope.launch {
            io.github.p1neapplexpress.openflux.event.EventBus.events.collect { event ->
                when (event) {
                    is io.github.p1neapplexpress.openflux.event.AppEvent.TransportConnected -> updateTileState()
                    is io.github.p1neapplexpress.openflux.event.AppEvent.VpnDisconnected,
                    is io.github.p1neapplexpress.openflux.event.AppEvent.TransportDisconnected -> updateTileState()
                    else -> Unit
                }
            }
        }
    }

    override fun onStopListening() {
        tileJob?.cancel()
        tileJob = null
        tileScope?.cancel()
        tileScope = null
        unbindVpnService()
        super.onStopListening()
    }

    override fun onClick() {
        super.onClick()
        val isRunning = runCatching { unifiedService?.isVpnRunning == true }.getOrDefault(false)

        if (isRunning) {
            val disconnectIntent = Intent(this, SocksVpnService::class.java).apply {
                action = SocksVpnService.ACTION_DISCONNECT
            }
            startService(disconnectIntent)
            setTileInactive()
        } else {
            val prepareIntent = android.net.VpnService.prepare(this)
            if (prepareIntent != null) {
                val launchIntent = Intent(this, MainActivity::class.java).apply {
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                }
                startActivityAndCollapse(launchIntent)
                return
            }

            val repo = TunnelRepository(this)
            val selected = repo.getSelected()
            if (selected == null) {
                val launchIntent = Intent(this, MainActivity::class.java).apply {
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                }
                startActivityAndCollapse(launchIntent)
                return
            }

            connectTunnel(selected)
            setTileActive(selected.name)
        }
    }

    private fun updateTileState() {
        val tile = qsTile ?: return
        val isRunning = runCatching { unifiedService?.isVpnRunning == true }.getOrDefault(false)

        if (isRunning) {
            val repo = TunnelRepository(this)
            val name = repo.getSelected()?.name ?: getString(R.string.quick_settings_connected)
            setTileActive(name)
        } else {
            setTileInactive()
        }
    }

    private fun setTileActive(label: String) {
        val tile = qsTile ?: return
        tile.state = Tile.STATE_ACTIVE
        tile.label = getString(R.string.quick_settings_tile_label)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            tile.subtitle = label
        }
        tile.updateTile()
    }

    private fun setTileInactive() {
        val tile = qsTile ?: return
        tile.state = Tile.STATE_INACTIVE
        tile.label = getString(R.string.quick_settings_tile_label)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            tile.subtitle = getString(R.string.quick_settings_disconnected)
        }
        tile.updateTile()
    }

    private fun connectTunnel(tunnel: io.github.p1neapplexpress.openflux.data.Tunnel) {
        val prepared = TunnelLinkParser.ensureLocalKeyFile(this, tunnel)
        val splitPrefs = SplitTunnelPreferences(this)
        val appBypass = splitPrefs.mode == SplitTunnelPreferences.MODE_BYPASS
        val selectedApps = if (appBypass) splitPrefs.bypassApps else splitPrefs.proxyApps
        val perApp = selectedApps.isNotEmpty()
        val appList = selectedApps.toTypedArray()

        val appSettings = AppSettings(this)
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
                if (idx + 1 < modifiedPayload.size) modifiedPayload.removeAt(idx + 1)
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
            username = session.username,
            password = session.password,
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

        val intent = VpnIntentFactory.build(this, cfg).apply {
            putExtra(io.github.p1neapplexpress.openflux.util.Constants.INTENT_AUTONOMOUS, true)
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            startForegroundService(intent)
        } else {
            startService(intent)
        }
    }

    private fun bindVpnService() {
        if (!bound) {
            val intent = Intent(this, SocksVpnService::class.java)
            bindService(intent, serviceConnection, 0)
        }
    }

    private fun unbindVpnService() {
        if (bound) {
            runCatching { unbindService(serviceConnection) }
            bound = false
            unifiedService = null
        }
    }
}
