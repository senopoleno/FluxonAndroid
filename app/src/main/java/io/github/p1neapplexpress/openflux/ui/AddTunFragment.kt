package io.github.p1neapplexpress.openflux.ui

import android.os.Bundle
import android.view.HapticFeedbackConstants
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.ImageView
import android.widget.TextView
import android.widget.Toast
import androidx.core.view.isVisible
import androidx.core.widget.doAfterTextChanged
import androidx.fragment.app.activityViewModels
import com.google.android.material.bottomsheet.BottomSheetDialog
import com.google.android.material.textfield.TextInputLayout
import io.github.p1neapplexpress.openflux.R
import io.github.p1neapplexpress.openflux.data.TransportType
import io.github.p1neapplexpress.openflux.data.Tunnel
import io.github.p1neapplexpress.openflux.event.AppEvent
import io.github.p1neapplexpress.openflux.util.performAppHaptics
import kotlinx.serialization.json.Json
import java.io.File

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
        val toolbarTitle = view.findViewById<TextView>(R.id.toolbar_title)
        val nameContainer = view.findViewById<TextInputLayout>(R.id.nameContainer)
        val transportLayout = view.findViewById<View>(R.id.select_transport_layout)
        val transportIcon = view.findViewById<ImageView>(R.id.transport_icon)
        val maxContainer = view.findViewById<View>(R.id.maxContainer)
        val maxTokenContainer = view.findViewById<TextInputLayout>(R.id.maxTokenContainer)
        val maxUserIdContainer = view.findViewById<TextInputLayout>(R.id.maxUserIdContainer)
        val yandexContainer = view.findViewById<TextInputLayout>(R.id.yandexUrlContainer)
        val encryptionKeyContainer = view.findViewById<TextInputLayout>(R.id.encryptionKeyContainer)
        val transportLabel = view.findViewById<TextView>(R.id.selectedTransport)
        val docUrl = view.findViewById<TextView>(R.id.documentUrl)
        val maxToken = view.findViewById<TextView>(R.id.maxToken)
        val maxUid = view.findViewById<TextView>(R.id.maxUserId)
        val name = view.findViewById<TextView>(R.id.name)
        val encryptionKey = view.findViewById<TextView>(R.id.encryptionKey)
        val save = view.findViewById<Button>(R.id.saveButton)

        btnBack.setOnClickListener {
            it.performAppHaptics(HapticFeedbackConstants.VIRTUAL_KEY)
            requireActivity().onBackPressedDispatcher.onBackPressed()
        }

        fun TextInputLayout.clearError() {
            error = null
            isErrorEnabled = false
        }

        // Clear errors on typing
        name.doAfterTextChanged { nameContainer.clearError() }
        docUrl.doAfterTextChanged { yandexContainer.clearError() }
        maxToken.doAfterTextChanged { maxTokenContainer.clearError() }
        maxUid.doAfterTextChanged { maxUserIdContainer.clearError() }
        encryptionKey.doAfterTextChanged { encryptionKeyContainer.clearError() }

        fun updateTransportUi(type: TransportType) {
            transport = type
            when (type) {
                TransportType.yandex -> {
                    transportIcon.setImageResource(R.drawable.yandex_docs)
                    transportLabel.text = getString(R.string.yandex_docs_backend)
                    maxContainer.isVisible = false
                    yandexContainer.isVisible = true
                    yandexContainer.hint = getString(R.string.document_url)
                }
                TransportType.vyandex -> {
                    transportIcon.setImageResource(R.drawable.volga)
                    transportLabel.text = getString(R.string.vyandex_backend)
                    maxContainer.isVisible = false
                    yandexContainer.isVisible = true
                    yandexContainer.hint = getString(R.string.document_url)
                }
                TransportType.max -> {
                    transportIcon.setImageResource(R.drawable.max_msg)
                    transportLabel.text = getString(R.string.max_messenger_backend)
                    maxContainer.isVisible = true
                    yandexContainer.isVisible = false
                }
                TransportType.cups -> {
                    transportIcon.setImageResource(R.drawable.ic_cups)
                    transportLabel.text = getString(R.string.cups_backend)
                    maxContainer.isVisible = false
                    yandexContainer.isVisible = true
                    yandexContainer.hint = getString(R.string.cups_url_hint)
                }
                TransportType.mailru -> {
                    transportIcon.setImageResource(R.drawable.ic_mailru)
                    transportLabel.text = getString(R.string.mailru_backend)
                    maxContainer.isVisible = false
                    yandexContainer.isVisible = true
                    yandexContainer.hint = getString(R.string.mailru_url_hint)
                }
            }
            yandexContainer.clearError()
            maxTokenContainer.clearError()
            maxUserIdContainer.clearError()
        }

        // ─── Заполнение при редактировании ───
        editing?.let { t ->
            toolbarTitle.text = getString(R.string.edit_config)
            name.setText(t.name)
            val initialTransport = TransportType.from(t.transportType)
            updateTransportUi(initialTransport)

            when (initialTransport) {
                TransportType.yandex, TransportType.vyandex -> {
                    val urlsVal = argValue(t.transportConnPayload, "--urls")
                    val displayUrl = if (urlsVal.isNotEmpty()) urlsVal.split(",").joinToString("\n") else argValue(t.transportConnPayload, "--url")
                    docUrl.setText(displayUrl)
                }
                TransportType.max -> {
                    maxToken.setText(argValue(t.transportConnPayload, "--maxToken"))
                    maxUid.setText(argValue(t.transportConnPayload, "--maxUid"))
                }
                TransportType.cups, TransportType.mailru -> {
                    docUrl.setText(argValue(t.transportConnPayload, "--url"))
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

            save.text = getString(R.string.save)
        } ?: run {
            toolbarTitle.text = getString(R.string.enter_manually)
            updateTransportUi(TransportType.yandex)
        }

        transportLayout.setOnClickListener {
            it.performAppHaptics(HapticFeedbackConstants.VIRTUAL_KEY)
            showTransportBottomSheet(transport) { selected ->
                updateTransportUi(selected)
            }
        }

        save.setOnClickListener {
            it.performAppHaptics(HapticFeedbackConstants.VIRTUAL_KEY)
            val n = name.text?.toString()?.trim().orEmpty()
            if (n.isEmpty()) {
                nameContainer.error = getString(R.string.name_required)
                name.requestFocus()
                return@setOnClickListener
            }
            nameContainer.clearError()

            val url = docUrl.text?.toString()?.trim().orEmpty()
            val token = maxToken.text?.toString()?.trim().orEmpty()
            val uidStr = maxUid.text?.toString()?.trim().orEmpty()

            when (transport) {
                TransportType.yandex, TransportType.vyandex -> {
                    val urls = url.split(Regex("[,\\s\\n\\r]+")).map { it.trim() }.filter { it.isNotEmpty() }
                    if (urls.isEmpty()) {
                        yandexContainer.error = getString(R.string.err_invalid_url)
                        docUrl.requestFocus()
                        return@setOnClickListener
                    }
                    for (u in urls) {
                        if (!u.startsWith("http://", ignoreCase = true) && !u.startsWith("https://", ignoreCase = true)) {
                            yandexContainer.error = getString(R.string.err_invalid_url)
                            docUrl.requestFocus()
                            return@setOnClickListener
                        }
                        val host = runCatching { java.net.URI(u).host }.getOrNull()?.lowercase()
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
                    }
                    yandexContainer.clearError()
                }
                TransportType.max -> {
                    if (token.isEmpty()) {
                        maxTokenContainer.error = getString(R.string.err_max_token_required)
                        maxToken.requestFocus()
                        return@setOnClickListener
                    }
                    maxTokenContainer.clearError()

                    val uidLong = uidStr.toLongOrNull()
                    if (uidLong == null || uidLong <= 0) {
                        maxUserIdContainer.error = getString(R.string.err_max_uid_required)
                        maxUid.requestFocus()
                        return@setOnClickListener
                    }
                    maxUserIdContainer.clearError()
                }
                TransportType.cups -> {
                    if (url.isEmpty()) {
                        yandexContainer.error = getString(R.string.err_cups_url_required)
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
                    if (!host.contains("cups.online")) {
                        yandexContainer.error = getString(R.string.err_cups_domain_invalid)
                        docUrl.requestFocus()
                        return@setOnClickListener
                    }
                    yandexContainer.clearError()
                }
                TransportType.mailru -> {
                    if (url.isEmpty()) {
                        yandexContainer.error = getString(R.string.err_mailru_url_required)
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
                    val isMailRuDomain = host.contains("mail.ru") || host.contains("my.mail.ru") || host.contains("cloud.mail.ru")
                    if (!isMailRuDomain) {
                        yandexContainer.error = getString(R.string.err_mailru_domain_invalid)
                        docUrl.requestFocus()
                        return@setOnClickListener
                    }
                    yandexContainer.clearError()
                }
            }

            val encKey = encryptionKey.text?.toString()?.trim().orEmpty()
            if (encKey.isNotEmpty() && encKey.length < 16) {
                encryptionKeyContainer.error = getString(R.string.err_encryption_key_short)
                encryptionKey.requestFocus()
                return@setOnClickListener
            }
            encryptionKeyContainer.clearError()

            val newId = editing?.id ?: System.currentTimeMillis()
            val newTunnel = createTunnel(
                id = newId,
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
    }

    private fun showTransportBottomSheet(
        current: TransportType,
        onSelect: (TransportType) -> Unit
    ) {
        val dialog = BottomSheetDialog(requireContext())
        val sheetView = layoutInflater.inflate(R.layout.bottom_sheet_transport_picker, null)
        dialog.setContentView(sheetView)

        sheetView.findViewById<View>(R.id.check_yandex)?.isVisible = current == TransportType.yandex
        sheetView.findViewById<View>(R.id.check_vyandex)?.isVisible = current == TransportType.vyandex
        sheetView.findViewById<View>(R.id.check_max)?.isVisible = current == TransportType.max
        sheetView.findViewById<View>(R.id.check_cups)?.isVisible = current == TransportType.cups
        sheetView.findViewById<View>(R.id.check_mailru)?.isVisible = current == TransportType.mailru

        fun bindOption(viewId: Int, type: TransportType) {
            sheetView.findViewById<View>(viewId)?.setOnClickListener {
                it.performAppHaptics(HapticFeedbackConstants.VIRTUAL_KEY)
                onSelect(type)
                dialog.dismiss()
            }
        }

        bindOption(R.id.picker_yandex, TransportType.yandex)
        bindOption(R.id.picker_vyandex, TransportType.vyandex)
        bindOption(R.id.picker_max, TransportType.max)
        bindOption(R.id.picker_cups, TransportType.cups)
        bindOption(R.id.picker_mailru, TransportType.mailru)

        dialog.show()
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
        when (transport) {
            TransportType.yandex, TransportType.vyandex, TransportType.cups, TransportType.mailru -> {
                if (docUrl.isEmpty()) return null
            }
            TransportType.max -> {
                if (maxToken.isEmpty() || maxUid.isEmpty()) return null
            }
        }

        val keyFile = if (encKey.isNotEmpty()) {
            val f = File(requireContext().filesDir, "key_${id}.txt")
            runCatching {
                requireContext().openFileOutput(f.name, android.content.Context.MODE_PRIVATE).use { fos ->
                    fos.write(encKey.toByteArray(java.nio.charset.StandardCharsets.UTF_8))
                    fos.flush()
                    fos.fd.sync()
                }
                f.setReadable(true, true)
                f.setWritable(true, true)
            }
            f
        } else {
            val f = File(requireContext().filesDir, "key_${id}.txt")
            runCatching { f.delete() }
            null
        }

        val payload = when (transport) {
            TransportType.yandex -> {
                val urls = docUrl.split(Regex("[,\\s\\n\\r]+")).map { it.trim() }.filter { it.isNotEmpty() }
                buildList {
                    add("--role=client"); add("--transport"); add("yandex")
                    if (urls.size > 1) {
                        add("--urls"); add(urls.joinToString(","))
                        add("--url"); add(urls.first())
                    } else if (urls.size == 1) {
                        add("--url"); add(urls.first())
                    }
                    keyFile?.let {
                        add("--encryption-key-file")
                        add(it.absolutePath)
                    }
                }
            }
            TransportType.vyandex -> {
                val urls = docUrl.split(Regex("[,\\s\\n\\r]+")).map { it.trim() }.filter { it.isNotEmpty() }
                buildList {
                    add("--role=client"); add("--transport"); add("vyandex")
                    if (urls.size > 1) {
                        add("--urls"); add(urls.joinToString(","))
                        add("--url"); add(urls.first())
                    } else if (urls.size == 1) {
                        add("--url"); add(urls.first())
                    }
                    keyFile?.let {
                        add("--encryption-key-file")
                        add(it.absolutePath)
                    }
                }
            }
            TransportType.max -> {
                buildList {
                    add("--role=client"); add("--transport"); add("oneme")
                    add("--maxToken"); add(maxToken)
                    add("--maxUid"); add(maxUid)
                    keyFile?.let {
                        add("--encryption-key-file")
                        add(it.absolutePath)
                    }
                }
            }
            TransportType.cups -> {
                buildList {
                    add("--role=client"); add("--transport"); add("cupsonline")
                    add("--url"); add(docUrl)
                    keyFile?.let {
                        add("--encryption-key-file")
                        add(it.absolutePath)
                    }
                }
            }
            TransportType.mailru -> {
                buildList {
                    add("--role=client"); add("--transport"); add("mailru")
                    add("--url"); add(docUrl)
                    keyFile?.let {
                        add("--encryption-key-file")
                        add(it.absolutePath)
                    }
                }
            }
        }
        return Tunnel(
            id = id,
            name = name,
            transportType = transport.name,
            transportConnPayload = payload,
            encryptionKey = if (encKey.isEmpty()) "" else encKey,
        )
    }

    override fun onNewEvent(ev: AppEvent) = Unit
}
