package io.github.p1neapplexpress.openflux.ui

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.provider.Settings
import android.view.HapticFeedbackConstants
import android.view.View
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.WindowCompat
import androidx.lifecycle.lifecycleScope
import com.google.android.material.bottomsheet.BottomSheetDialog
import com.google.android.material.button.MaterialButton
import com.google.android.material.button.MaterialButtonToggleGroup
import com.google.android.material.materialswitch.MaterialSwitch
import com.google.android.material.textfield.TextInputEditText
import io.github.p1neapplexpress.openflux.BuildConfig
import io.github.p1neapplexpress.openflux.R
import io.github.p1neapplexpress.openflux.util.AppSettings
import io.github.p1neapplexpress.openflux.util.AppUpdateChecker
import io.github.p1neapplexpress.openflux.util.ConfigBackupManager
import io.github.p1neapplexpress.openflux.util.DomainRulesPreferences
import io.github.p1neapplexpress.openflux.util.LocalSocksSession
import io.github.p1neapplexpress.openflux.util.SplitTunnelPreferences
import io.github.p1neapplexpress.openflux.util.ThemePreferences
import kotlinx.coroutines.launch
import java.util.UUID

class SettingsActivity : AppCompatActivity() {

    private lateinit var appSettings: AppSettings
    private lateinit var themePrefs: ThemePreferences
    private lateinit var splitPrefs: SplitTunnelPreferences

    private lateinit var themeToggleGroup: MaterialButtonToggleGroup
    private lateinit var textSplitTunnelSummary: TextView
    private lateinit var textDnsSummary: TextView
    private lateinit var textMtuSummary: TextView
    private lateinit var textIpTypeSummary: TextView

    private lateinit var switchBypassLan: MaterialSwitch
    private lateinit var switchNotifySpeed: MaterialSwitch
    private lateinit var switchMemoryMonitor: MaterialSwitch

    private lateinit var switchKillSwitch: MaterialSwitch
    private lateinit var switchHotspot: MaterialSwitch
    private lateinit var switchSocks5Auth: MaterialSwitch
    private lateinit var switchFailover: MaterialSwitch
    private lateinit var switchAutoBoot: MaterialSwitch
    private lateinit var switchAutoClearLogs: MaterialSwitch

    private lateinit var switchAutoUpdate: MaterialSwitch
    private lateinit var textVersion: TextView

    private val exportBackupLauncher = registerForActivityResult(
        ActivityResultContracts.CreateDocument("application/json")
    ) { uri ->
        if (uri != null) {
            lifecycleScope.launch {
                val result = ConfigBackupManager.exportBackup(this@SettingsActivity, uri)
                result.fold(
                    onSuccess = { count ->
                        Toast.makeText(
                            this@SettingsActivity,
                            getString(R.string.settings_backup_exported, count),
                            Toast.LENGTH_SHORT
                        ).show()
                    },
                    onFailure = { error ->
                        Toast.makeText(
                            this@SettingsActivity,
                            getString(R.string.settings_backup_error, error.localizedMessage ?: "Unknown error"),
                            Toast.LENGTH_SHORT
                        ).show()
                    }
                )
            }
        }
    }

    private val importBackupLauncher = registerForActivityResult(
        ActivityResultContracts.OpenDocument()
    ) { uri ->
        if (uri != null) {
            showStyledImportBottomSheet(uri)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        themePrefs = ThemePreferences(this)
        themePrefs.applyTheme()

        val isNight = when (themePrefs.themeMode) {
            ThemePreferences.THEME_LIGHT -> false
            ThemePreferences.THEME_DARK -> true
            else -> (resources.configuration.uiMode and android.content.res.Configuration.UI_MODE_NIGHT_MASK) == android.content.res.Configuration.UI_MODE_NIGHT_YES
        }
        val insetsController = WindowCompat.getInsetsController(window, window.decorView)
        insetsController.isAppearanceLightStatusBars = !isNight
        insetsController.isAppearanceLightNavigationBars = !isNight

        setContentView(R.layout.activity_settings)

        appSettings = AppSettings(this)
        splitPrefs = SplitTunnelPreferences(this)

        initViews()
        setupTopBar()
        setupAppearance()
        setupNetwork()
        setupInterface()
        setupAutomation()
        setupBackup()
        setupUpdates()
        setupAbout()
    }

    override fun onResume() {
        super.onResume()
        updateSplitTunnelSummary()
        updateDnsSummary()
        updateMtuSummary()
        updateIpTypeSummary()
    }

    private fun initViews() {
        themeToggleGroup = findViewById(R.id.theme_toggle_group)
        textSplitTunnelSummary = findViewById(R.id.text_split_tunnel_summary)
        textDnsSummary = findViewById(R.id.text_dns_summary)
        textMtuSummary = findViewById(R.id.text_mtu_summary)
        textIpTypeSummary = findViewById(R.id.text_ip_type_summary)

        switchBypassLan = findViewById(R.id.switch_bypass_lan)
        switchNotifySpeed = findViewById(R.id.switch_notify_speed)
        switchMemoryMonitor = findViewById(R.id.switch_memory_monitor)

        switchKillSwitch = findViewById(R.id.switch_kill_switch)
        switchHotspot = findViewById(R.id.switch_hotspot)
        switchSocks5Auth = findViewById(R.id.switch_socks5_auth)
        switchFailover = findViewById(R.id.switch_failover)
        switchAutoBoot = findViewById(R.id.switch_auto_boot)
        switchAutoClearLogs = findViewById(R.id.switch_auto_clear_logs)

        switchAutoUpdate = findViewById(R.id.switch_auto_update)
        textVersion = findViewById(R.id.text_version)
    }

    private fun setupTopBar() {
        findViewById<View>(R.id.btn_back).setOnClickListener {
            it.performHapticFeedback(HapticFeedbackConstants.VIRTUAL_KEY)
            finish()
        }
    }

    private fun setupAppearance() {
        val checkedBtnId = when (themePrefs.themeMode) {
            ThemePreferences.THEME_LIGHT -> R.id.btn_theme_light
            ThemePreferences.THEME_DARK -> R.id.btn_theme_dark
            else -> R.id.btn_theme_system
        }
        themeToggleGroup.check(checkedBtnId)

        themeToggleGroup.addOnButtonCheckedListener { _, checkedId, isChecked ->
            if (isChecked) {
                val newMode = when (checkedId) {
                    R.id.btn_theme_light -> ThemePreferences.THEME_LIGHT
                    R.id.btn_theme_dark -> ThemePreferences.THEME_DARK
                    else -> ThemePreferences.THEME_SYSTEM
                }
                if (newMode != themePrefs.themeMode) {
                    themePrefs.themeMode = newMode
                    themePrefs.applyTheme()
                    overridePendingTransition(R.anim.fade_in, R.anim.fade_out)
                }
            }
        }
    }

    private fun setupNetwork() {
        // 1. Split Tunneling
        findViewById<View>(R.id.row_split_tunnel).setOnClickListener {
            it.performHapticFeedback(HapticFeedbackConstants.VIRTUAL_KEY)
            startActivity(Intent(this, SplitTunnelActivity::class.java))
        }
        updateSplitTunnelSummary()

        // 2. DNS
        findViewById<View>(R.id.row_dns).setOnClickListener {
            it.performHapticFeedback(HapticFeedbackConstants.VIRTUAL_KEY)
            startActivity(Intent(this, DnsSettingsActivity::class.java))
        }
        updateDnsSummary()

        // 3. MTU BottomSheet
        findViewById<View>(R.id.row_mtu).setOnClickListener {
            it.performHapticFeedback(HapticFeedbackConstants.VIRTUAL_KEY)
            MtuBottomSheetDialog(this, appSettings.mtu) { newMtu ->
                appSettings.mtu = newMtu
                updateMtuSummary()
            }.show()
        }
        updateMtuSummary()

        // 4. IP Type
        findViewById<View>(R.id.row_ip_type).setOnClickListener {
            it.performHapticFeedback(HapticFeedbackConstants.VIRTUAL_KEY)
            showIpTypeBottomSheet()
        }
        updateIpTypeSummary()

        // 5. Bypass LAN
        switchBypassLan.isChecked = appSettings.bypassLan
        switchBypassLan.jumpDrawablesToCurrentState()
        switchBypassLan.setOnCheckedChangeListener { _, isChecked ->
            appSettings.bypassLan = isChecked
        }
    }

    private fun setupInterface() {
        switchNotifySpeed.isChecked = appSettings.showNotificationSpeed
        switchNotifySpeed.jumpDrawablesToCurrentState()
        switchNotifySpeed.setOnCheckedChangeListener { _, isChecked ->
            appSettings.showNotificationSpeed = isChecked
        }

        switchMemoryMonitor.isChecked = appSettings.showMemoryUsage
        switchMemoryMonitor.jumpDrawablesToCurrentState()
        switchMemoryMonitor.setOnCheckedChangeListener { _, isChecked ->
            appSettings.showMemoryUsage = isChecked
        }
    }

    private fun setupAutomation() {
        // 1. Kill Switch
        switchKillSwitch.isChecked = appSettings.killSwitch
        switchKillSwitch.jumpDrawablesToCurrentState()
        switchKillSwitch.setOnCheckedChangeListener { _, isChecked ->
            appSettings.killSwitch = isChecked
            if (isChecked) {
                Toast.makeText(this, R.string.settings_kill_switch_system, Toast.LENGTH_SHORT).show()
                try {
                    startActivity(Intent(Settings.ACTION_VPN_SETTINGS))
                } catch (_: Exception) { }
            }
        }
        findViewById<View>(R.id.row_kill_switch).setOnClickListener {
            try {
                startActivity(Intent(Settings.ACTION_VPN_SETTINGS))
            } catch (_: Exception) {
                switchKillSwitch.toggle()
            }
        }

        // 2. Hotspot Proxy Sharing
        switchHotspot.isChecked = appSettings.shareLanProxy
        switchHotspot.jumpDrawablesToCurrentState()
        switchHotspot.setOnCheckedChangeListener { _, isChecked ->
            appSettings.shareLanProxy = isChecked
            if (isChecked) {
                val session = io.github.p1neapplexpress.openflux.util.LocalSocksSession.getActive()
                io.github.p1neapplexpress.openflux.service.HotspotProxyBridge.start(
                    lanPort = appSettings.lanProxyPort,
                    targetLocalPort = session.port,
                    authEnabled = appSettings.socks5AuthEnabled,
                    username = session.username,
                    password = session.password
                )
                showHotspotBottomSheet()
            } else {
                io.github.p1neapplexpress.openflux.service.HotspotProxyBridge.stop()
            }
        }
        findViewById<View>(R.id.row_hotspot).setOnClickListener {
            showHotspotBottomSheet()
        }

        // 3. SOCKS5 RFC 1929 Auth
        switchSocks5Auth.isChecked = appSettings.socks5AuthEnabled
        switchSocks5Auth.jumpDrawablesToCurrentState()
        switchSocks5Auth.setOnCheckedChangeListener { _, isChecked ->
            appSettings.socks5AuthEnabled = isChecked
        }
        findViewById<View>(R.id.row_socks5_auth).setOnClickListener {
            showSocks5AuthBottomSheet()
        }

        // 4. Failover
        switchFailover.isChecked = appSettings.autoFailover
        switchFailover.jumpDrawablesToCurrentState()
        switchFailover.setOnCheckedChangeListener { _, isChecked ->
            appSettings.autoFailover = isChecked
        }

        // 5. Auto-boot
        switchAutoBoot.isChecked = appSettings.autoConnectOnBoot
        switchAutoBoot.jumpDrawablesToCurrentState()
        switchAutoBoot.setOnCheckedChangeListener { _, isChecked ->
            appSettings.autoConnectOnBoot = isChecked
        }

        // 6. Auto-clear logs
        switchAutoClearLogs.isChecked = appSettings.autoClearLogs
        switchAutoClearLogs.jumpDrawablesToCurrentState()
        switchAutoClearLogs.setOnCheckedChangeListener { _, isChecked ->
            appSettings.autoClearLogs = isChecked
        }
    }

    private fun setupBackup() {
        findViewById<View>(R.id.row_export_backup).setOnClickListener {
            it.performHapticFeedback(HapticFeedbackConstants.VIRTUAL_KEY)
            exportBackupLauncher.launch("fluxon_backup.json")
        }

        findViewById<View>(R.id.row_import_backup).setOnClickListener {
            it.performHapticFeedback(HapticFeedbackConstants.VIRTUAL_KEY)
            importBackupLauncher.launch(arrayOf("application/json", "*/*"))
        }
    }

    private fun setupUpdates() {
        switchAutoUpdate.isChecked = appSettings.autoUpdateCheck
        switchAutoUpdate.jumpDrawablesToCurrentState()
        switchAutoUpdate.setOnCheckedChangeListener { _, isChecked ->
            appSettings.autoUpdateCheck = isChecked
        }

        findViewById<View>(R.id.row_check_update_now).setOnClickListener {
            it.performHapticFeedback(HapticFeedbackConstants.VIRTUAL_KEY)
            Toast.makeText(this, R.string.update_checking, Toast.LENGTH_SHORT).show()
            AppUpdateChecker.checkForUpdate(this, force = true) { hasUpdate, versionOrError ->
                if (!hasUpdate) {
                    if (versionOrError.startsWith("v") || versionOrError.startsWith("1.") || versionOrError.all { it.isDigit() || it == '.' }) {
                        Toast.makeText(this, getString(R.string.update_latest_installed, versionOrError), Toast.LENGTH_SHORT).show()
                    } else {
                        Toast.makeText(this, getString(R.string.update_error, versionOrError), Toast.LENGTH_SHORT).show()
                    }
                }
            }
        }
    }

    private var versionClickCount = 0
    private var lastVersionClickTime = 0L

    private fun setupAbout() {
        textVersion.text = BuildConfig.VERSION_NAME

        val onVersionClicked: (View) -> Unit = { view ->
            val now = System.currentTimeMillis()
            if (now - lastVersionClickTime > 1500L) {
                versionClickCount = 0
            }
            lastVersionClickTime = now
            versionClickCount++

            if (versionClickCount >= 5) {
                versionClickCount = 0
                view.performHapticFeedback(HapticFeedbackConstants.CONFIRM)
                DebugMenuHelper.show(this)
            } else if (versionClickCount >= 2) {
                view.performHapticFeedback(HapticFeedbackConstants.VIRTUAL_KEY)
                val remaining = 5 - versionClickCount
                val msg = if (remaining == 1) {
                    getString(R.string.debug_click_step_one)
                } else {
                    getString(R.string.debug_click_steps_left, remaining)
                }
                Toast.makeText(this, msg, Toast.LENGTH_SHORT).show()
            }
        }

        findViewById<View>(R.id.row_version)?.setOnClickListener(onVersionClicked)
        textVersion.setOnClickListener(onVersionClicked)
    }

    private fun updateSplitTunnelSummary() {
        val installed = packageManager.getInstalledApplications(0).map { it.packageName }.toSet()
        val appCount = if (splitPrefs.mode == SplitTunnelPreferences.MODE_BYPASS) {
            splitPrefs.bypassApps.count { it in installed }
        } else {
            splitPrefs.proxyApps.count { it in installed }
        }
        val domainPrefs = DomainRulesPreferences(this)
        val domainCount = domainPrefs.domains.size
        textSplitTunnelSummary.text = when {
            appCount == 0 && domainCount == 0 -> getString(R.string.settings_split_tunnel_none)
            else -> getString(R.string.split_summary_format, appCount, domainCount)
        }
    }

    private fun updateDnsSummary() {
        textDnsSummary.text = when {
            appSettings.useSystemDns -> "${getString(R.string.dns_use_system_title)} (${appSettings.primaryDns})"
            appSettings.dohEnabled -> {
                val providerName = when (appSettings.dohProvider) {
                    AppSettings.DOH_PROVIDER_CLOUDFLARE -> "Cloudflare"
                    AppSettings.DOH_PROVIDER_GOOGLE -> "Google"
                    AppSettings.DOH_PROVIDER_ADGUARD -> "AdGuard"
                    AppSettings.DOH_PROVIDER_QUAD9 -> "Quad9"
                    AppSettings.DOH_PROVIDER_CUSTOM -> "Custom"
                    else -> "Cloudflare"
                }
                "DoH: $providerName"
            }
            else -> when (appSettings.dnsMode) {
                AppSettings.DNS_MODE_CLOUDFLARE -> "Cloudflare"
                AppSettings.DNS_MODE_GOOGLE -> "Google"
                AppSettings.DNS_MODE_ADGUARD -> "AdGuard DNS"
                AppSettings.DNS_MODE_QUAD9 -> "Quad9"
                AppSettings.DNS_MODE_CUSTOM -> "${getString(R.string.settings_dns_custom)} (${appSettings.primaryDns})"
                else -> "Cloudflare"
            }
        }
    }

    private fun updateMtuSummary() {
        textMtuSummary.text = getString(R.string.settings_mtu_summary, appSettings.mtu)
    }

    private fun updateIpTypeSummary() {
        textIpTypeSummary.text = when (appSettings.ipType) {
            AppSettings.IP_TYPE_IPV4 -> getString(R.string.settings_ip_type_ipv4)
            AppSettings.IP_TYPE_IPV6 -> getString(R.string.settings_ip_type_ipv6)
            else -> getString(R.string.settings_ip_type_auto)
        }
    }

    private fun showIpTypeBottomSheet() {
        val dialog = BottomSheetDialog(this)
        val view = layoutInflater.inflate(R.layout.bottom_sheet_ip_type, null)
        dialog.setContentView(view)

        val cardAuto = view.findViewById<View>(R.id.card_ip_auto)
        val cardIpv4 = view.findViewById<View>(R.id.card_ip_v4)
        val cardIpv6 = view.findViewById<View>(R.id.card_ip_v6)

        cardAuto.setOnClickListener {
            appSettings.ipType = AppSettings.IP_TYPE_AUTO
            updateIpTypeSummary()
            dialog.dismiss()
        }
        cardIpv4.setOnClickListener {
            appSettings.ipType = AppSettings.IP_TYPE_IPV4
            updateIpTypeSummary()
            dialog.dismiss()
        }
        cardIpv6.setOnClickListener {
            appSettings.ipType = AppSettings.IP_TYPE_IPV6
            updateIpTypeSummary()
            dialog.dismiss()
        }
        dialog.show()
    }

    private fun showHotspotBottomSheet() {
        val dialog = BottomSheetDialog(this)
        val view = layoutInflater.inflate(R.layout.bottom_sheet_hotspot, null)
        dialog.setContentView(view)

        val textIp = view.findViewById<TextView>(R.id.text_hotspot_ip)
        val textPort = view.findViewById<TextView>(R.id.text_hotspot_port)
        val btnCopy = view.findViewById<View>(R.id.btn_copy_hotspot)
        val btnClose = view.findViewById<View>(R.id.btn_close_hotspot)

        val ip = LocalSocksSession.getLocalIpAddress()
        val port = appSettings.lanProxyPort

        textIp.text = ip
        textPort.text = port.toString()

        btnCopy.setOnClickListener {
            it.performHapticFeedback(HapticFeedbackConstants.VIRTUAL_KEY)
            val clipboard = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
            val clip = ClipData.newPlainText("SOCKS5 Proxy", "$ip:$port")
            clipboard.setPrimaryClip(clip)
            Toast.makeText(this, R.string.hotspot_sheet_copied, Toast.LENGTH_SHORT).show()
        }

        btnClose.setOnClickListener { dialog.dismiss() }
        dialog.show()
    }

    private fun showSocks5AuthBottomSheet() {
        val dialog = BottomSheetDialog(this)
        val view = layoutInflater.inflate(R.layout.bottom_sheet_socks5_auth, null)
        dialog.setContentView(view)

        val inputUser = view.findViewById<TextInputEditText>(R.id.input_socks_user)
        val inputPass = view.findViewById<TextInputEditText>(R.id.input_socks_pass)
        val btnCancel = view.findViewById<View>(R.id.btn_cancel_socks)
        val btnSave = view.findViewById<View>(R.id.btn_save_socks)

        val active = LocalSocksSession.getActive()
        val activeUser = appSettings.socks5CustomUser.ifEmpty { active.username }
        val activePass = appSettings.socks5CustomPass.ifEmpty { active.password }

        inputUser.setText(activeUser)
        inputPass.setText(activePass)

        btnCancel.setOnClickListener { dialog.dismiss() }

        btnSave.setOnClickListener {
            it.performHapticFeedback(HapticFeedbackConstants.VIRTUAL_KEY)
            appSettings.socks5CustomUser = inputUser.text?.toString().orEmpty().trim()
            appSettings.socks5CustomPass = inputPass.text?.toString().orEmpty().trim()
            dialog.dismiss()
        }

        dialog.show()
    }

    private fun showStyledImportBottomSheet(uri: Uri) {
        lifecycleScope.launch {
            val peek = ConfigBackupManager.peekBackup(this@SettingsActivity, uri)
            peek.fold(
                onSuccess = { backup ->
                    val dialog = BottomSheetDialog(this@SettingsActivity)
                    val view = layoutInflater.inflate(R.layout.bottom_sheet_import_backup, null)
                    dialog.setContentView(view)

                    val textTunnels = view.findViewById<TextView>(R.id.text_import_tunnels_count)
                    val textRules = view.findViewById<TextView>(R.id.text_import_rules_count)
                    val btnCancel = view.findViewById<View>(R.id.btn_cancel_import)
                    val btnConfirm = view.findViewById<View>(R.id.btn_confirm_import)

                    textTunnels.text = getString(R.string.import_sheet_tunnels, backup.tunnels.size)
                    val rulesCount = backup.bypassApps.size + backup.proxyApps.size + backup.domains.size
                    textRules.text = getString(R.string.import_sheet_rules, rulesCount)

                    btnCancel.setOnClickListener {
                        dialog.dismiss()
                    }

                    btnConfirm.setOnClickListener {
                        it.performHapticFeedback(HapticFeedbackConstants.VIRTUAL_KEY)
                        dialog.dismiss()
                        lifecycleScope.launch {
                            val applyResult = ConfigBackupManager.applyBackup(this@SettingsActivity, backup)
                            applyResult.fold(
                                onSuccess = { count ->
                                    updateSplitTunnelSummary()
                                    Toast.makeText(
                                        this@SettingsActivity,
                                        getString(R.string.settings_import_success, count),
                                        Toast.LENGTH_SHORT
                                    ).show()
                                },
                                onFailure = { err ->
                                    Toast.makeText(
                                        this@SettingsActivity,
                                        getString(R.string.settings_backup_error, err.localizedMessage ?: "Import failed"),
                                        Toast.LENGTH_SHORT
                                    ).show()
                                }
                            )
                        }
                    }

                    dialog.show()
                },
                onFailure = { error ->
                    Toast.makeText(
                        this@SettingsActivity,
                        getString(R.string.settings_backup_error, error.localizedMessage ?: "Invalid file"),
                        Toast.LENGTH_SHORT
                    ).show()
                }
            )
        }
    }
}
