package io.github.p1neapplexpress.openflux.ui

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.PopupWindow
import android.widget.TextView
import android.widget.Toast
import androidx.core.content.ContextCompat
import androidx.core.view.isVisible
import androidx.core.widget.doAfterTextChanged
import androidx.fragment.app.activityViewModels
import com.google.android.material.materialswitch.MaterialSwitch
import com.google.android.material.textfield.TextInputLayout
import io.github.p1neapplexpress.openflux.R
import io.github.p1neapplexpress.openflux.data.TransportType
import io.github.p1neapplexpress.openflux.data.Tunnel
import io.github.p1neapplexpress.openflux.event.AppEvent
import io.github.p1neapplexpress.openflux.util.dpToPx
import kotlinx.serialization.json.Json
import java.io.File
import kotlin.random.Random

class AddTunFragment : BaseFragment() {

    companion object {
        private const val ARG_EDIT_JSON = "edit_json"

        fun new() = AddTunFragment()

        fun edit(tunnel: Tunnel): AddTunFragment = AddTunFragment().apply {
            arguments = Bundle().apply {
                putString(ARG_EDIT_JSON, Json.encodeToString(Tunnel.serializer(), tunnel))
            }
        }
    }

    private val vm: TunnelsViewModel by activityViewModels()
    private var transport = TransportType.yandex
    private var debug = false
    private var editing: Tunnel? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val raw = arguments?.getString(ARG_EDIT_JSON)
        if (raw != null) {
            editing = runCatching { Json.decodeFromString(Tunnel.serializer(), raw) }.getOrNull()
        }
    }

    override fun onCreateView(i: LayoutInflater, c: ViewGroup?, s: Bundle?) =
        i.inflate(R.layout.fragment_add_tun, c, false)

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        val btnBack = view.findViewById<View>(R.id.btn_back)
        val headerTitle = view.findViewById<TextView>(R.id.headerTitle)
        val nameContainer = view.findViewById<TextInputLayout>(R.id.nameContainer)
        val transportLayout = view.findViewById<View>(R.id.select_transport_layout)
        val maxContainer = view.findViewById<View>(R.id.maxContainer)
        val maxTokenContainer = view.findViewById<TextInputLayout>(R.id.maxTokenContainer)
        val maxUserIdContainer = view.findViewById<TextInputLayout>(R.id.maxUserIdContainer)
        val yandexContainer = view.findViewById<TextInputLayout>(R.id.yandexUrlContainer)
        val encryptionKeyContainer = view.findViewById<TextInputLayout>(R.id.encryptionKeyContainer)
        val transportLabel = view.findViewById<TextView>(R.id.selectedTransport)
        val debugSwitch = view.findViewById<MaterialSwitch>(R.id.debugSwitch)
        val docUrl = view.findViewById<TextView>(R.id.documentUrl)
        val maxToken = view.findViewById<TextView>(R.id.maxToken)
        val maxUid = view.findViewById<TextView>(R.id.maxUserId)
        val name = view.findViewById<TextView>(R.id.name)
        val encryptionKey = view.findViewById<TextView>(R.id.encryptionKey)
        val save = view.findViewById<Button>(R.id.saveButton)

        btnBack.setOnClickListener {
            requireActivity().onBackPressedDispatcher.onBackPressed()
        }

        // Clear errors on typing
        name.doAfterTextChanged { nameContainer.error = null }
        docUrl.doAfterTextChanged { yandexContainer.error = null }
        maxToken.doAfterTextChanged { maxTokenContainer.error = null }
        maxUid.doAfterTextChanged { maxUserIdContainer.error = null }
        encryptionKey.doAfterTextChanged { encryptionKeyContainer.error = null }

        // ─── Заполнение при редактировании ───
        editing?.let { t ->
            headerTitle.text = getString(R.string.edit_config)
            name.setText(t.name)
            transport = TransportType.from(t.transportType)

            when (transport) {
                TransportType.yandex -> {
                    maxContainer.isVisible = false
                    yandexContainer.isVisible = true
                    transportLabel.text = getString(R.string.yandex_docs_backend)
                    docUrl.setText(argValue(t.transportConnPayload, "--url"))
                }
                TransportType.vyandex -> {
                    maxContainer.isVisible = false
                    yandexContainer.isVisible = true
                    transportLabel.text = getString(R.string.vyandex_backend)
                    docUrl.setText(argValue(t.transportConnPayload, "--url"))
                }
                TransportType.max -> {
                    maxContainer.isVisible = true
                    yandexContainer.isVisible = false
                    transportLabel.text = getString(R.string.max_messenger_backend)
                    maxToken.setText(argValue(t.transportConnPayload, "--maxToken"))
                    maxUid.setText(argValue(t.transportConnPayload, "--maxUid"))
                }
                TransportType.cups -> {
                    maxContainer.isVisible = false
                    yandexContainer.isVisible = true
                    transportLabel.text = getString(R.string.cups_backend)
                    docUrl.setText(argValue(t.transportConnPayload, "--url"))
                    yandexContainer.hint = getString(R.string.cups_url_hint)
                }
            }

            val keyFromProp = t.encryptionKey?.trim()
            if (!keyFromProp.isNullOrEmpty()) {
                encryptionKey.setText(keyFromProp)
            } else {
                val keyPath = argValue(t.transportConnPayload, "--encryption-key-file")
                if (keyPath.isNotEmpty()) {
                    try {
                        val kf = File(keyPath)
                        if (kf.exists()) {
                            encryptionKey.setText(kf.readText().trim())
                        }
                    } catch (_: Exception) {}
                }
            }

            debug = t.transportConnPayload.contains("--debug")
            debugSwitch.isChecked = debug
            save.text = getString(R.string.action_edit)
        }

        transportLayout.setOnClickListener {
            it.showTransportDropdown(
                onYandex = {
                    transport = TransportType.yandex
                    maxContainer.isVisible = false
                    yandexContainer.isVisible = true
                    transportLabel.text = getString(R.string.yandex_docs_backend)
                    yandexContainer.error = null
                    maxTokenContainer.error = null
                    maxUserIdContainer.error = null
                },
                onVyandex = {
                    transport = TransportType.vyandex
                    maxContainer.isVisible = false
                    yandexContainer.isVisible = true
                    transportLabel.text = getString(R.string.vyandex_backend)
                    yandexContainer.error = null
                    maxTokenContainer.error = null
                    maxUserIdContainer.error = null
                },
                onMax = {
                    transport = TransportType.max
                    maxContainer.isVisible = true
                    yandexContainer.isVisible = false
                    transportLabel.text = getString(R.string.max_messenger_backend)
                    yandexContainer.error = null
                    maxTokenContainer.error = null
                    maxUserIdContainer.error = null
                },
                onCups = {
                    transport = TransportType.cups
                    maxContainer.isVisible = false
                    yandexContainer.isVisible = true
                    transportLabel.text = getString(R.string.cups_backend)
                    yandexContainer.hint = getString(R.string.cups_url_hint)
                    yandexContainer.error = null
                    maxTokenContainer.error = null
                    maxUserIdContainer.error = null
                },
            )
        }

        debugSwitch.setOnCheckedChangeListener { _, checked ->
            debug = checked
        }

        save.setOnClickListener {
            val n = name.text?.toString()?.trim().orEmpty()
            if (n.isEmpty()) {
                nameContainer.error = getString(R.string.name_required)
                name.requestFocus()
                return@setOnClickListener
            }
            nameContainer.error = null

            val url = docUrl.text?.toString()?.trim().orEmpty()
            val token = maxToken.text?.toString()?.trim().orEmpty()
            val uidStr = maxUid.text?.toString()?.trim().orEmpty()

            when (transport) {
                TransportType.yandex, TransportType.vyandex -> {
                    if (url.isEmpty()) {
                        yandexContainer.error = getString(R.string.err_invalid_url)
                        docUrl.requestFocus()
                        return@setOnClickListener
                    }
                    if (!url.startsWith("http://", ignoreCase = true) && !url.startsWith("https://", ignoreCase = true)) {
                        yandexContainer.error = getString(R.string.err_invalid_url)
                        docUrl.requestFocus()
                        return@setOnClickListener
                    }
                    val host = runCatching { java.net.URI(url).host }.getOrNull()?.lowercase()
                    if (host.isNullOrEmpty() || !host.contains(".")) {
                        yandexContainer.error = getString(R.string.err_invalid_url)
                        docUrl.requestFocus()
                        return@setOnClickListener
                    }
                    val isYandexDomain = host.contains("yandex.") || host.contains("yadi.sk") || host.contains("ya.ru")
                    if (!isYandexDomain) {
                        yandexContainer.error = getString(R.string.err_invalid_yandex_url)
                        docUrl.requestFocus()
                        return@setOnClickListener
                    }
                    yandexContainer.error = null
                }
                TransportType.max -> {
                    if (token.isEmpty()) {
                        maxTokenContainer.error = getString(R.string.err_max_token_required)
                        maxToken.requestFocus()
                        return@setOnClickListener
                    }
                    maxTokenContainer.error = null

                    val uid = uidStr.toLongOrNull()
                    if (uid == null || uid <= 0) {
                        maxUserIdContainer.error = getString(R.string.err_max_uid_required)
                        maxUid.requestFocus()
                        return@setOnClickListener
                    }
                    maxUserIdContainer.error = null
                }
                TransportType.cups -> {
                    if (url.isEmpty()) {
                        yandexContainer.error = getString(R.string.cups_url_hint)
                        docUrl.requestFocus()
                        return@setOnClickListener
                    }
                    yandexContainer.error = null
                }
            }

            val encKey = encryptionKey.text?.toString()?.trim().orEmpty()
            if (encKey.isNotEmpty() && encKey.length < 16) {
                encryptionKeyContainer.error = getString(R.string.err_encryption_key_short)
                encryptionKey.requestFocus()
                return@setOnClickListener
            }
            encryptionKeyContainer.error = null

            val newTunnel = createTunnel(
                id = editing?.id ?: Random(System.currentTimeMillis()).nextLong(),
                name = n,
                docUrl = url,
                maxToken = token,
                maxUid = uidStr,
                encKey = encKey,
            ) ?: return@setOnClickListener

            val old = editing
            if (old != null) {
                vm.updateTunnel(old, newTunnel)
                Toast.makeText(requireContext(), R.string.config_saved, Toast.LENGTH_SHORT).show()
            } else {
                vm.addTunnel(newTunnel)
            }

            requireActivity().onBackPressedDispatcher.onBackPressed()
        }

        if (editing == null) {
            headerTitle.text = getString(R.string.enter_manually)
            transportLabel.text = getString(R.string.yandex_docs_backend)
        }
    }

    private fun argValue(payload: List<String>, key: String): String {
        val idx = payload.indexOf(key)
        return if (idx >= 0 && idx + 1 < payload.size) payload[idx + 1] else ""
    }

    private fun createTunnel(
        id: Long,
        name: String,
        docUrl: String,
        maxToken: String,
        maxUid: String,
        encKey: String = "",
    ): Tunnel? {
        val keyFile = if (encKey.isNotEmpty()) {
            val f = File(requireContext().filesDir, "key_${id}.txt")
            f.writeText(encKey)
            f
        } else null

        val payload = when (transport) {
            TransportType.yandex -> {
                if (docUrl.isEmpty()) return null
                buildList {
                    add("--client"); add("--transport"); add("yandex")
                    add("--url"); add(docUrl)
                    keyFile?.let {
                        add("--encryption-key-file")
                        add(it.absolutePath)
                    }
                    if (debug) add("--debug")
                }
            }
            TransportType.vyandex -> {
                if (docUrl.isEmpty()) return null
                buildList {
                    add("--client"); add("--transport"); add("vyandex")
                    add("--url"); add(docUrl)
                    keyFile?.let {
                        add("--encryption-key-file")
                        add(it.absolutePath)
                    }
                    if (debug) add("--debug")
                }
            }
            TransportType.max -> {
                if (maxToken.isEmpty() || maxUid.isEmpty()) return null
                buildList {
                    add("--client"); add("--transport"); add("oneme")
                    add("--maxToken"); add(maxToken)
                    add("--maxUid"); add(maxUid)
                    keyFile?.let {
                        add("--encryption-key-file")
                        add(it.absolutePath)
                    }
                    if (debug) add("--debug")
                }
            }
            TransportType.cups -> {
                if (docUrl.isEmpty()) return null
                buildList {
                    add("--client"); add("--transport"); add("cupsonline")
                    add("--url"); add(docUrl)
                    keyFile?.let {
                        add("--encryption-key-file")
                        add(it.absolutePath)
                    }
                    if (debug) add("--debug")
                }
            }
        }
        return Tunnel(
            id = id,
            name = name,
            transportType = transport.name,
            transportConnPayload = payload,
            encryptionKey = encKey.trim().ifEmpty { null },
        )
    }

    override fun onNewEvent(ev: AppEvent) = Unit

    private fun View.showTransportDropdown(
        onYandex: () -> Unit,
        onVyandex: () -> Unit,
        onMax: () -> Unit,
        onCups: () -> Unit,
    ) {
        val popupView = LayoutInflater.from(context).inflate(R.layout.dropdown_transport_menu, null)
        val popup = PopupWindow(
            popupView,
            ViewGroup.LayoutParams.WRAP_CONTENT,
            ViewGroup.LayoutParams.WRAP_CONTENT,
            true
        ).apply {
            setBackgroundDrawable(ContextCompat.getDrawable(context, R.drawable.bg_dropdown_transports))
            elevation = 8.dpToPx(context).toFloat()
            animationStyle = R.style.DropdownAnimation
            isOutsideTouchable = true
            isFocusable = true
        }

        popupView.findViewById<View>(R.id.option_yandex)?.setOnClickListener {
            onYandex(); popup.dismiss()
        }
        popupView.findViewById<View>(R.id.option_vyandex)?.setOnClickListener {
            onVyandex(); popup.dismiss()
        }
        popupView.findViewById<View>(R.id.option_max)?.setOnClickListener {
            onMax(); popup.dismiss()
        }
        popupView.findViewById<View>(R.id.option_cups)?.setOnClickListener {
            onCups(); popup.dismiss()
        }

        popupView.measure(View.MeasureSpec.UNSPECIFIED, View.MeasureSpec.UNSPECIFIED)
        popup.showAsDropDown(this, 0, -popupView.measuredHeight - height - 8.dpToPx(context))
    }
}
