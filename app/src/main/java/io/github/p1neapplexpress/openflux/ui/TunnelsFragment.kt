package io.github.p1neapplexpress.openflux.ui

import android.animation.ObjectAnimator
import android.animation.PropertyValuesHolder
import android.animation.ValueAnimator
import android.app.Activity.RESULT_OK
import android.app.Dialog
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.net.VpnService
import android.os.Bundle
import android.view.HapticFeedbackConstants
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.view.Window
import android.view.animation.AccelerateDecelerateInterpolator
import android.view.animation.DecelerateInterpolator
import android.view.animation.LinearInterpolator
import android.view.animation.OvershootInterpolator
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.ColorDrawable
import android.net.Uri
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.PopupWindow
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.ContextCompat
import androidx.core.view.isVisible
import androidx.fragment.app.activityViewModels
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import com.google.android.material.bottomsheet.BottomSheetDialog
import io.github.g00fy2.quickie.QRResult
import io.github.g00fy2.quickie.ScanQRCode
import io.github.p1neapplexpress.openflux.R
import io.github.p1neapplexpress.openflux.util.AppSettings
import io.github.p1neapplexpress.openflux.data.Tunnel
import io.github.p1neapplexpress.openflux.data.TunnelHealth
import io.github.p1neapplexpress.openflux.data.TunnelState
import io.github.p1neapplexpress.openflux.event.AppEvent
import io.github.p1neapplexpress.openflux.ui.widget.AuroraView
import io.github.p1neapplexpress.openflux.ui.widget.PulseRingsView
import io.github.p1neapplexpress.openflux.util.QrDecoder
import io.github.p1neapplexpress.openflux.util.ThemePreferences
import io.github.p1neapplexpress.openflux.util.TunnelLinkParser
import io.github.p1neapplexpress.openflux.util.toUptimeHms
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import java.util.Locale

class TunnelsFragment : BaseFragment() {

    private val vm: TunnelsViewModel by activityViewModels()

    private lateinit var aurora: AuroraView
    private lateinit var pulseRings: PulseRingsView
    private lateinit var ringOuter: View
    private lateinit var ringMid: View
    private lateinit var connectButton: View
    private lateinit var powerIcon: ImageView
    private lateinit var configSelector: View
    private lateinit var configDot: View
    private lateinit var tunnelName: TextView
    private lateinit var chevron: ImageView
    private lateinit var statusText: TextView
    private lateinit var uptimeContainer: View
    private lateinit var uptimeText: TextView
    private lateinit var speedText: TextView
    private lateinit var memoryContainer: View
    private lateinit var memoryText: TextView
    private lateinit var appSettings: AppSettings

    private var rotationAnim: ObjectAnimator? = null
    private var breathAnim: ObjectAnimator? = null
    private var currentVisualState: TunnelState? = null
    private var isInitialStateBinding = true
    private var popup: PopupWindow? = null

    private val vpnPermission = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        if (result.resultCode == RESULT_OK) vm.startCurrent()
        else Toast.makeText(requireContext(), R.string.vpn_permission_required, Toast.LENGTH_LONG).show()
    }

    private val qrScanner = registerForActivityResult(ScanQRCode()) { result ->
        val raw = (result as? QRResult.QRSuccess)?.content?.rawValue
            ?: return@registerForActivityResult
        val tunnel = TunnelLinkParser.parse(raw, requireContext())
        if (tunnel != null) {
            vm.addTunnel(tunnel)
            vm.startTunnel(tunnel)
            Toast.makeText(requireContext(), R.string.config_saved, Toast.LENGTH_SHORT).show()
        } else {
            Toast.makeText(requireContext(), R.string.qr_scan_failed, Toast.LENGTH_LONG).show()
        }
    }

    private val pickQrImage = registerForActivityResult(ActivityResultContracts.GetContent()) { uri: Uri? ->
        if (uri == null) return@registerForActivityResult
        val ctx = requireContext()
        viewLifecycleOwner.lifecycleScope.launch(Dispatchers.IO) {
            val decoded = QrDecoder.decodeFromUri(ctx, uri)
            withContext(Dispatchers.Main) {
                val tunnel = if (decoded != null) TunnelLinkParser.parse(decoded, ctx) else null
                if (tunnel != null) {
                    vm.addTunnel(tunnel)
                    vm.startTunnel(tunnel)
                    Toast.makeText(ctx, R.string.config_saved, Toast.LENGTH_SHORT).show()
                } else {
                    Toast.makeText(ctx, R.string.qr_scan_failed, Toast.LENGTH_LONG).show()
                }
            }
        }
    }

    private fun showAddQrBottomSheet() {
        val dialog = BottomSheetDialog(requireContext())
        val sheetView = layoutInflater.inflate(R.layout.bottom_sheet_add_qr, null)
        dialog.setContentView(sheetView)

        sheetView.findViewById<View>(R.id.option_scan_camera).setOnClickListener {
            it.performHapticFeedback(HapticFeedbackConstants.VIRTUAL_KEY)
            dialog.dismiss()
            qrScanner.launch(null)
        }

        sheetView.findViewById<View>(R.id.option_pick_gallery).setOnClickListener {
            it.performHapticFeedback(HapticFeedbackConstants.VIRTUAL_KEY)
            dialog.dismiss()
            pickQrImage.launch("image/*")
        }

        sheetView.findViewById<View>(R.id.option_import_link).setOnClickListener {
            it.performHapticFeedback(HapticFeedbackConstants.VIRTUAL_KEY)
            dialog.dismiss()
            showImportLinkDialog()
        }

        dialog.show()
    }

    private fun showImportLinkDialog() {
        val context = requireContext()
        val dialog = com.google.android.material.bottomsheet.BottomSheetDialog(context)
        val view = layoutInflater.inflate(R.layout.bottom_sheet_import_link, null)
        dialog.setContentView(view)

        val inputEdit = view.findViewById<com.google.android.material.textfield.TextInputEditText>(R.id.input_link_value)
        val btnPaste = view.findViewById<View>(R.id.btn_paste_link)
        val btnCancel = view.findViewById<View>(R.id.btn_cancel_import)
        val btnConfirm = view.findViewById<View>(R.id.btn_confirm_import)

        val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager
        val clipText = clipboard?.primaryClip?.takeIf { it.itemCount > 0 }?.getItemAt(0)?.text?.toString()?.trim()
        clipText?.let {
            if (it.startsWith("openflux://", ignoreCase = true) || it.startsWith("{") || it.startsWith("vless://") || it.startsWith("vmess://") || it.startsWith("http")) {
                inputEdit.setText(it)
                inputEdit.setSelection(it.length)
            }
        }

        btnPaste.setOnClickListener {
            val clip = clipboard?.primaryClip?.takeIf { it.itemCount > 0 }?.getItemAt(0)?.text?.toString()?.trim()
            if (!clip.isNullOrBlank()) {
                inputEdit.setText(clip)
                inputEdit.setSelection(clip.length)
            }
        }

        btnCancel.setOnClickListener {
            dialog.dismiss()
        }

        btnConfirm.setOnClickListener {
            val text = inputEdit.text?.toString()?.trim()
            val tunnel = TunnelLinkParser.parse(text, context)
            if (tunnel != null) {
                dialog.dismiss()
                vm.addTunnel(tunnel)
                vm.startTunnel(tunnel)
                Toast.makeText(context, R.string.config_saved, Toast.LENGTH_SHORT).show()
            } else {
                Toast.makeText(context, R.string.import_link_invalid, Toast.LENGTH_LONG).show()
            }
        }

        dialog.show()
        inputEdit.requestFocus()
    }

    override fun onCreateView(i: LayoutInflater, c: ViewGroup?, s: Bundle?) =
        i.inflate(R.layout.fragment_tunnels, c, false)

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        aurora = view.findViewById(R.id.aurora)
        pulseRings = view.findViewById(R.id.pulseRings)
        ringOuter = view.findViewById(R.id.ringOuter)
        ringMid = view.findViewById(R.id.ringMid)
        connectButton = view.findViewById(R.id.connectButton)
        powerIcon = view.findViewById(R.id.powerIcon)
        configSelector = view.findViewById(R.id.configSelector)
        configDot = view.findViewById(R.id.configDot)
        tunnelName = view.findViewById(R.id.tunnelName)
        chevron = view.findViewById(R.id.chevron)
        statusText = view.findViewById(R.id.statusText)
        uptimeContainer = view.findViewById(R.id.uptimeContainer)
        uptimeText = view.findViewById(R.id.uptimeText)
        speedText = view.findViewById(R.id.speedText)
        memoryContainer = view.findViewById(R.id.memoryContainer)
        memoryText = view.findViewById(R.id.memoryText)
        appSettings = AppSettings(requireContext())
        updateMemoryUsage()

        connectButton.setOnClickListener {
            it.performHapticFeedback(HapticFeedbackConstants.VIRTUAL_KEY)
            when (vm.active.value) {
                is TunnelState.Running -> vm.stop()
                is TunnelState.Idle, is TunnelState.Error -> requestVpnAndStart()
                else -> Unit
            }
        }

        configSelector.setOnClickListener {
            it.performHapticFeedback(HapticFeedbackConstants.VIRTUAL_KEY)
            showConfigDropdown(it)
        }

        configDot.setOnClickListener {
            it.performHapticFeedback(HapticFeedbackConstants.VIRTUAL_KEY)
            vm.checkSelectedHealth(force = true)
        }

        view.findViewById<View>(R.id.btnSettings)?.setOnClickListener {
            it.performHapticFeedback(HapticFeedbackConstants.VIRTUAL_KEY)
            startActivity(Intent(requireContext(), SettingsActivity::class.java))
        }

        view.findViewById<View>(R.id.btnSplitTunnel)?.setOnClickListener {
            it.performHapticFeedback(HapticFeedbackConstants.VIRTUAL_KEY)
            startActivity(Intent(requireContext(), SplitTunnelActivity::class.java))
        }

        view.findViewById<View>(R.id.switchButton).setOnClickListener {
            parentFragmentManager.beginTransaction()
                .setCustomAnimations(
                    R.anim.slide_in_bottom,
                    R.anim.fade_out,
                    R.anim.fade_in,
                    R.anim.slide_out_bottom
                )
                .replace(R.id.main, AddTunFragment.new())
                .addToBackStack("switch")
                .commit()
        }
        if (vm.active.value is TunnelState.Running) {
            uptimeContainer.alpha = 1f
            uptimeContainer.translationY = 0f
            statusText.translationY = 0f
        } else if (vm.active.value is TunnelState.Idle) {
            statusText.translationY = 64f * resources.displayMetrics.density
            uptimeContainer.alpha = 0f
            uptimeContainer.translationY = 12f
        }

        view.findViewById<View>(R.id.addButton).setOnClickListener {
            it.performHapticFeedback(HapticFeedbackConstants.VIRTUAL_KEY)
            showAddQrBottomSheet()
        }

        observe()
    }

    override fun onResume() {
        super.onResume()
        vm.checkSelectedHealth(force = false)
        updateMemoryUsage()
    }

    private fun requestVpnAndStart() {
        val intent = VpnService.prepare(requireActivity())
        if (intent != null) vpnPermission.launch(intent) else vm.startCurrent()
    }

    private fun observe() {
        viewLifecycleOwner.lifecycleScope.launch {
            viewLifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
                launch { vm.active.collect { applyState(it) } }
                launch { vm.uptimeSeconds.collect { renderUptime(it) } }
                launch { vm.selected.collect { renderSelected(it) } }
                launch {
                    combine(vm.rxSpeed, vm.txSpeed) { rx, tx -> Pair(rx, tx) }
                        .collect { (rx, tx) -> renderSpeed(rx, tx) }
                }
                launch { vm.tunnelHealth.collect { renderHealth(it) } }
            }
        }
    }

    private fun renderSpeed(rx: Long, tx: Long) {
        speedText.text = "↓ ${formatSpeed(rx)}   ↑ ${formatSpeed(tx)}"
    }

    private fun formatSpeed(bytesPerSecond: Long): String = when {
        bytesPerSecond < 1024 -> "$bytesPerSecond B/s"
        bytesPerSecond < 1024 * 1024 -> String.format(Locale.US, "%.0f KB/s", bytesPerSecond / 1024.0)
        else -> String.format(Locale.US, "%.1f MB/s", bytesPerSecond / (1024.0 * 1024.0))
    }

    private fun renderSelected(tunnel: Tunnel?) {
        tunnelName.text = tunnel?.name ?: getString(R.string.no_configs)
        renderHealth(vm.tunnelHealth.value)
    }

    private fun renderHealth(health: TunnelHealth) {
        if (vm.selected.value == null) {
            configDot.background?.setTint(
                ContextCompat.getColor(requireContext(), R.color.state_error)
            )
            return
        }
        val colorRes = when (health) {
            TunnelHealth.AVAILABLE -> R.color.state_running
            TunnelHealth.UNAVAILABLE -> R.color.state_error
            TunnelHealth.CHECKING -> R.color.state_connecting
            TunnelHealth.UNKNOWN -> R.color.state_idle
        }
        configDot.background?.setTint(
            ContextCompat.getColor(requireContext(), colorRes)
        )
    }

    

    private fun showConfigDropdown(anchor: View) {
        val tunnels = vm.tunnels.value.map { it.tunnel }
        if (tunnels.isEmpty()) {
            Toast.makeText(requireContext(), R.string.no_configs, Toast.LENGTH_SHORT).show()
            return
        }

        vm.probeAllTunnels()

        val inflater = LayoutInflater.from(requireContext())
        val content = inflater.inflate(R.layout.dropdown_configs, null)
        val items = content.findViewById<LinearLayout>(R.id.dropdown_items)
        val selectedId = vm.selectedTunnelId
        val rowDots = mutableMapOf<Long, View>()
        val rowPings = mutableMapOf<Long, TextView>()

        fun tintDot(dot: View, health: TunnelHealth) {
            val colorRes = when (health) {
                TunnelHealth.AVAILABLE -> R.color.state_running
                TunnelHealth.UNAVAILABLE -> R.color.state_error
                TunnelHealth.CHECKING -> R.color.state_connecting
                TunnelHealth.UNKNOWN -> R.color.state_idle
            }
            dot.background?.setTint(ContextCompat.getColor(requireContext(), colorRes))
        }

        fun updatePingView(pingView: TextView, ping: Long?) {
            if (ping != null && ping > 0) {
                pingView.isVisible = true
                pingView.text = getString(R.string.ping_ms_format, ping)
                val colorRes = when {
                    ping < 150 -> R.color.state_running
                    ping < 350 -> R.color.state_connecting
                    else -> R.color.state_error
                }
                pingView.setTextColor(ContextCompat.getColor(requireContext(), colorRes))
            } else {
                pingView.isVisible = false
            }
        }

        for (tunnel in tunnels) {
            val row = inflater.inflate(R.layout.item_dropdown_config, items, false)
            val nameView = row.findViewById<TextView>(R.id.item_name)
            val check = row.findViewById<ImageView>(R.id.item_check)
            val dot = row.findViewById<View>(R.id.item_dot)
            val pingView = row.findViewById<TextView>(R.id.item_ping)
            val isSelected = tunnel.id == selectedId

            nameView.text = tunnel.name
            check.isVisible = isSelected

            if (isSelected) {
                nameView.setTextColor(ContextCompat.getColor(requireContext(), R.color.accent_blue))
                nameView.setTypeface(null, Typeface.BOLD)
            } else {
                nameView.setTextColor(ContextCompat.getColor(requireContext(), R.color.text_primary))
            }

            tintDot(dot, vm.getTunnelHealth(tunnel.id))
            rowDots[tunnel.id] = dot

            updatePingView(pingView, vm.getTunnelPing(tunnel.id))
            rowPings[tunnel.id] = pingView

            row.setOnClickListener {
                it.performHapticFeedback(HapticFeedbackConstants.VIRTUAL_KEY)
                vm.selectTunnel(tunnel)
                content.animate()
                    .alpha(0f)
                    .scaleX(0.9f)
                    .scaleY(0.9f)
                    .translationY(-10f)
                    .setDuration(140L)
                    .withEndAction { popup?.dismiss() }
                    .start()
            }

            row.setOnLongClickListener {
                it.performHapticFeedback(HapticFeedbackConstants.LONG_PRESS)
                showItemContextMenu(it, tunnel)
                true
            }

            items.addView(row)
        }

        val anchorLocation = IntArray(2)
        anchor.getLocationOnScreen(anchorLocation)

        content.measure(
            View.MeasureSpec.makeMeasureSpec((260 * resources.displayMetrics.density).toInt(), View.MeasureSpec.AT_MOST),
            View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED)
        )

        val dropdownWidth = maxOf(anchor.width, content.measuredWidth)

        val pw = PopupWindow(
            content,
            dropdownWidth,
            ViewGroup.LayoutParams.WRAP_CONTENT,
            true
        ).apply {
            elevation = 16f
            isOutsideTouchable = true
            isFocusable = true
            setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))
        }

        popup = pw

        val targetX = anchorLocation[0] + (anchor.width - dropdownWidth) / 2
        val targetY = anchorLocation[1] + anchor.height + (6 * resources.displayMetrics.density).toInt()

        content.pivotX = dropdownWidth / 2f
        content.pivotY = 0f
        content.alpha = 0f
        content.scaleX = 0.88f
        content.scaleY = 0.88f
        content.translationY = -16f

        pw.showAtLocation(anchor, android.view.Gravity.NO_GRAVITY, targetX, targetY)

        content.animate()
            .alpha(1f)
            .scaleX(1f)
            .scaleY(1f)
            .translationY(0f)
            .setDuration(220L)
            .setInterpolator(OvershootInterpolator(1.2f))
            .start()

        chevron.animate().rotation(180f).setDuration(220L).setInterpolator(DecelerateInterpolator()).start()

        val dropdownCollectorsJob = viewLifecycleOwner.lifecycleScope.launch {
            launch {
                vm.healthMap.collect { map ->
                    for ((id, h) in map) {
                        val d = rowDots[id] ?: continue
                        tintDot(d, h)
                    }
                }
            }
            launch {
                vm.pingMap.collect { map ->
                    for ((id, ping) in map) {
                        val pv = rowPings[id] ?: continue
                        updatePingView(pv, ping)
                    }
                }
            }
        }

        pw.setOnDismissListener {
            dropdownCollectorsJob.cancel()
            chevron.animate().rotation(0f).setDuration(180L).setInterpolator(DecelerateInterpolator()).start()
            popup = null
        }
    }

    

    private fun showItemContextMenu(anchor: View, tunnel: Tunnel) {
        val inflater = LayoutInflater.from(requireContext())
        val menuView = inflater.inflate(R.layout.popup_item_menu, null)

        val menu = PopupWindow(
            menuView,
            ViewGroup.LayoutParams.WRAP_CONTENT,
            ViewGroup.LayoutParams.WRAP_CONTENT,
            true
        ).apply {
            elevation = 12f
            isOutsideTouchable = true
            isFocusable = true
            setBackgroundDrawable(
                ContextCompat.getDrawable(requireContext(), R.drawable.bg_menu_popup)
            )
        }

        menuView.findViewById<View>(R.id.menu_edit).setOnClickListener {
            menu.dismiss()
            popup?.dismiss()
            parentFragmentManager.beginTransaction()
                .setCustomAnimations(
                    R.anim.slide_in_bottom,
                    R.anim.fade_out,
                    R.anim.fade_in,
                    R.anim.slide_out_bottom
                )
                .replace(R.id.main, AddTunFragment.edit(tunnel))
                .addToBackStack("edit")
                .commit()
        }

        menuView.findViewById<View>(R.id.menu_share_qr)?.setOnClickListener {
            menu.dismiss()
            popup?.dismiss()
            QrShareDialog.show(requireContext(), tunnel)
        }

        menuView.findViewById<View>(R.id.menu_delete).setOnClickListener {
            menu.dismiss()
            confirmDelete(tunnel)
        }

        menuView.alpha = 0f
        menuView.translationY = -8f
        menuView.scaleX = 0.96f
        menuView.scaleY = 0.96f

        menu.showAsDropDown(anchor, 0, 4)

        menuView.animate()
            .alpha(1f).translationY(0f).scaleX(1f).scaleY(1f)
            .setDuration(160)
            .setInterpolator(OvershootInterpolator(1.1f))
            .start()
    }

    private fun confirmDelete(tunnel: Tunnel) {
        val active = vm.active.value
        if (active.isActive && active.tunnel == tunnel) {
            Toast.makeText(requireContext(), R.string.cannot_delete_active, Toast.LENGTH_SHORT).show()
            return
        }

        val dialog = Dialog(requireContext())
        dialog.requestWindowFeature(Window.FEATURE_NO_TITLE)
        dialog.window?.setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))

        val dialogView = layoutInflater.inflate(R.layout.dialog_confirm_delete, null)
        dialog.setContentView(dialogView)

        dialogView.findViewById<TextView>(R.id.dialog_title).text = getString(R.string.delete_config_title)
        dialogView.findViewById<TextView>(R.id.dialog_message).text =
            "«${tunnel.name}»\n${getString(R.string.delete_config_msg)}"

        dialogView.findViewById<View>(R.id.btn_cancel).setOnClickListener {
            it.performHapticFeedback(HapticFeedbackConstants.VIRTUAL_KEY)
            dialog.dismiss()
        }

        dialogView.findViewById<View>(R.id.btn_delete).setOnClickListener {
            it.performHapticFeedback(HapticFeedbackConstants.VIRTUAL_KEY)
            dialog.dismiss()
            vm.removeTunnel(tunnel)
            popup?.dismiss()
            Toast.makeText(requireContext(), R.string.config_deleted, Toast.LENGTH_SHORT).show()
        }

        dialog.show()

        val width = (resources.displayMetrics.widthPixels * 0.88).toInt()
        dialog.window?.setLayout(width, ViewGroup.LayoutParams.WRAP_CONTENT)
    }

    private fun applyState(state: TunnelState) {
        val isFirst = isInitialStateBinding
        isInitialStateBinding = false

        if (state == currentVisualState && !isFirst) return
        currentVisualState = state

        val color = state.color
        aurora.setStateColor(color)

        if (isFirst) {
            when (state) {
                is TunnelState.Idle -> {
                    statusText.text = getString(R.string.tap_to_connect)
                    statusText.setTextColor(ContextCompat.getColor(requireContext(), R.color.text_primary))
                    statusText.alpha = 1f
                    statusText.translationY = 64f * resources.displayMetrics.density
                    aurora.setIntensity(0.4f)
                    pulseRings.stop()
                    stopRotation()
                    startBreath()
                    powerIcon.scaleX = 1f
                    powerIcon.scaleY = 1f
                    powerIcon.alpha = 0.92f
                    connectButton.scaleX = 1f
                    connectButton.scaleY = 1f
                    uptimeContainer.animate().cancel()
                    uptimeContainer.alpha = 0f
                    uptimeContainer.translationY = 12f
                }
                is TunnelState.Connecting,
                is TunnelState.StartingTransport,
                is TunnelState.StartingTun2Socks -> {
                    val label = when (state) {
                        is TunnelState.Connecting -> getString(R.string.connecting)
                        is TunnelState.StartingTransport -> getString(R.string.starting_transport)
                        is TunnelState.StartingTun2Socks -> getString(R.string.starting_tsocks)
                    }
                    statusText.text = label
                    statusText.setTextColor(color)
                    statusText.alpha = 1f
                    statusText.translationY = 0f
                    aurora.setIntensity(0.75f)
                    startRotation()
                    pulseRings.setColor(color)
                    pulseRings.start(color, intervalMs = 1800L)
                    stopBreath()
                    powerIcon.scaleX = 0.94f
                    powerIcon.scaleY = 0.94f
                    powerIcon.alpha = 0.7f
                    connectButton.scaleX = 1f
                    connectButton.scaleY = 1f
                    uptimeContainer.animate().cancel()
                    uptimeContainer.alpha = 0f
                    uptimeContainer.translationY = 12f
                }
                is TunnelState.Running -> {
                    statusText.text = getString(R.string.running)
                    statusText.setTextColor(color)
                    statusText.alpha = 1f
                    statusText.translationY = 0f
                    aurora.setIntensity(1f)
                    stopRotation()
                    pulseRings.setColor(color)
                    pulseRings.start(color, intervalMs = 1400L)
                    startBreath()
                    powerIcon.scaleX = 1.08f
                    powerIcon.scaleY = 1.08f
                    powerIcon.alpha = 1f
                    connectButton.scaleX = 1f
                    connectButton.scaleY = 1f
                    uptimeContainer.animate().cancel()
                    uptimeContainer.alpha = 1f
                    uptimeContainer.translationY = 0f
                }
                is TunnelState.Error -> {
                    statusText.text = state.message
                    statusText.setTextColor(color)
                    statusText.alpha = 1f
                    statusText.translationY = 0f
                    aurora.setIntensity(0.9f)
                    pulseRings.stop()
                    stopRotation()
                    stopBreath()
                    powerIcon.scaleX = 1f
                    powerIcon.scaleY = 1f
                    powerIcon.alpha = 1f
                    connectButton.scaleX = 1f
                    connectButton.scaleY = 1f
                    uptimeContainer.animate().cancel()
                    uptimeContainer.alpha = 0f
                    uptimeContainer.translationY = 12f
                }
            }
            return
        }

        when (state) {
            is TunnelState.Idle -> {
                statusText.text = getString(R.string.tap_to_connect)
                statusText.setTextColor(ContextCompat.getColor(requireContext(), R.color.text_primary))
                crossFadeStatus()
                animateStatusPosition(down = true)
                aurora.setIntensity(0.4f)
                pulseRings.stop()
                stopRotation()
                startBreath()
                animateIcon(scale = 1f, alpha = 0.92f)
                hideUptime()
            }

            is TunnelState.Connecting,
            is TunnelState.StartingTransport,
            is TunnelState.StartingTun2Socks -> {
                val label = when (state) {
                    is TunnelState.Connecting -> getString(R.string.connecting)
                    is TunnelState.StartingTransport -> getString(R.string.starting_transport)
                    is TunnelState.StartingTun2Socks -> getString(R.string.starting_tsocks)
                }
                statusText.text = label
                statusText.setTextColor(color)
                crossFadeStatus()
                animateStatusPosition(down = false)
                aurora.setIntensity(0.75f)
                startRotation()
                pulseRings.setColor(color)
                pulseRings.start(color, intervalMs = 1800L)
                stopBreath()
                animateIcon(scale = 0.94f, alpha = 0.7f)
                hideUptime()
            }

            is TunnelState.Running -> {
                statusText.text = getString(R.string.running)
                statusText.setTextColor(color)
                crossFadeStatus()
                animateStatusPosition(down = false)
                aurora.setIntensity(1f)
                stopRotation()
                pulseRings.setColor(color)
                pulseRings.start(color, intervalMs = 1400L)
                startBreath()
                animateIcon(scale = 1.08f, alpha = 1f)
                popButton()
                showUptime()
            }

            is TunnelState.Error -> {
                statusText.text = state.message
                statusText.setTextColor(color)
                crossFadeStatus()
                animateStatusPosition(down = false)
                aurora.setIntensity(0.9f)
                pulseRings.stop()
                stopRotation()
                stopBreath()
                animateIcon(scale = 1f, alpha = 1f)
                shake()
                hideUptime()
            }
        }
    }

    private fun animateStatusPosition(down: Boolean) {
        val targetY = if (down) 64f * resources.displayMetrics.density else 0f
        statusText.animate()
            .translationY(targetY)
            .setDuration(320L)
            .setInterpolator(DecelerateInterpolator())
            .start()
    }

    private fun renderUptime(seconds: Long) {
        if (seconds <= 0L || currentVisualState !is TunnelState.Running) {
            hideUptime()
            return
        }
        val text = seconds.toUptimeHms()
        if (uptimeText.text != text) {
            uptimeText.text = text
        }
        showUptime()
    }

    private fun crossFadeStatus() {
        statusText.animate().cancel()
        statusText.alpha = 0.6f
        statusText.animate().alpha(1f).setDuration(260L)
            .setInterpolator(DecelerateInterpolator()).start()
    }

    private fun animateIcon(scale: Float, alpha: Float) {
        powerIcon.animate().cancel()
        powerIcon.animate().scaleX(scale).scaleY(scale).alpha(alpha)
            .setDuration(320L).setInterpolator(AccelerateDecelerateInterpolator()).start()
    }

    private fun popButton() {
        connectButton.animate().cancel()
        connectButton.scaleX = 0.94f; connectButton.scaleY = 0.94f
        connectButton.animate().scaleX(1f).scaleY(1f).setDuration(420L)
            .setInterpolator(OvershootInterpolator(1.6f)).start()
    }

    private fun shake() {
        val props = PropertyValuesHolder.ofFloat(View.TRANSLATION_X, 0f, -14f, 14f, -10f, 10f, -4f, 4f, 0f)
        ObjectAnimator.ofPropertyValuesHolder(connectButton, props).apply {
            duration = 520L
            interpolator = AccelerateDecelerateInterpolator()
            start()
        }
    }

    private fun startRotation() {
        if (rotationAnim?.isRunning == true) return
        rotationAnim = ObjectAnimator.ofFloat(ringOuter, View.ROTATION, 0f, 360f).apply {
            duration = 4200L
            repeatCount = ValueAnimator.INFINITE
            interpolator = LinearInterpolator()
            start()
        }
    }

    private fun stopRotation() {
        rotationAnim?.cancel()
        rotationAnim = null
        ringOuter.rotation = 0f
    }

    private fun startBreath() {
        if (breathAnim?.isRunning == true) return
        breathAnim = ObjectAnimator.ofPropertyValuesHolder(
            ringOuter,
            PropertyValuesHolder.ofFloat(View.SCALE_X, 1f, 1.02f),
            PropertyValuesHolder.ofFloat(View.SCALE_Y, 1f, 1.02f),
        ).apply {
            duration = 2400L
            repeatCount = ValueAnimator.INFINITE
            repeatMode = ValueAnimator.REVERSE
            interpolator = AccelerateDecelerateInterpolator()
            start()
        }
    }

    private fun stopBreath() {
        breathAnim?.cancel()
        breathAnim = null
        ringOuter.scaleX = 1f
        ringOuter.scaleY = 1f
    }

    private fun showUptime() {
        if (uptimeContainer.alpha > 0.05f) return
        uptimeContainer.translationY = 12f
        uptimeContainer.animate().cancel()
        uptimeContainer.animate()
            .alpha(1f)
            .translationY(0f)
            .setDuration(350L)
            .setInterpolator(DecelerateInterpolator())
            .start()
    }

    private fun hideUptime() {
        if (uptimeContainer.alpha < 0.05f) return
        uptimeContainer.animate().cancel()
        uptimeContainer.animate()
            .alpha(0f)
            .translationY(12f)
            .setDuration(200L)
            .start()
    }




    private fun updateMemoryUsage() {
        if (!::appSettings.isInitialized || !::memoryContainer.isInitialized || !::memoryText.isInitialized) return
        if (appSettings.showMemoryUsage) {
            memoryContainer.visibility = View.VISIBLE
            val runtime = Runtime.getRuntime()
            val usedMemInMB = (runtime.totalMemory() - runtime.freeMemory()) / (1024 * 1024)
            memoryText.text = getString(R.string.memory_usage_format, usedMemInMB)
        } else {
            memoryContainer.visibility = View.GONE
        }
    }

    override fun onDestroyView() {
        rotationAnim?.cancel()
        breathAnim?.cancel()
        pulseRings.stop()
        popup?.dismiss()
        popup = null
        currentVisualState = null
        isInitialStateBinding = true
        super.onDestroyView()
    }

    override fun onNewEvent(ev: AppEvent) = Unit

    companion object {
        fun new() = TunnelsFragment()
    }
}
