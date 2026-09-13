package io.github.p1neapplexpress.openflux.ui

import android.app.Dialog
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.graphics.Color
import android.graphics.drawable.ColorDrawable
import android.os.Build
import android.os.Bundle
import android.text.SpannableStringBuilder
import android.text.Spanned
import android.text.style.ForegroundColorSpan
import android.view.HapticFeedbackConstants
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.view.Window
import android.widget.ImageView
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import com.google.android.material.bottomsheet.BottomSheetDialog
import io.github.p1neapplexpress.openflux.BuildConfig
import io.github.p1neapplexpress.openflux.R
import io.github.p1neapplexpress.openflux.event.AppEvent
import io.github.p1neapplexpress.openflux.event.EventBus
import io.github.p1neapplexpress.openflux.util.AppUpdateChecker
import io.github.p1neapplexpress.openflux.util.Logx
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.ConcurrentLinkedQueue

class LogsFragment : BaseFragment() {

    companion object {
        private const val MAX_LINES = 1000
        private const val FLUSH_INTERVAL_MS = 150L
    }

    private lateinit var textView: TextView
    private lateinit var scrollView: ScrollView
    private lateinit var tvLineCount: TextView
    private lateinit var btnAutoScroll: ImageView
    private lateinit var btnCopy: ImageView
    private lateinit var btnClear: ImageView

    private var autoScroll = true
    private val ts = SimpleDateFormat("HH:mm:ss.SSS", Locale.getDefault())

    private val pending = ConcurrentLinkedQueue<String>()
    private var totalLines = 0
    private var flushScheduled = false

    override fun onNewEvent(ev: AppEvent) = Unit

    override fun onCreateView(i: LayoutInflater, c: ViewGroup?, s: Bundle?) =
        i.inflate(R.layout.fragment_logs, c, false)

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        textView = view.findViewById(R.id.logs)
        scrollView = view.findViewById(R.id.log_scroll)
        tvLineCount = view.findViewById(R.id.tv_line_count)
        btnAutoScroll = view.findViewById(R.id.btn_autoscroll)
        btnCopy = view.findViewById(R.id.btn_copy)
        btnClear = view.findViewById(R.id.btn_clear)

        updateLineCount()
        updateAutoScrollUi()

        val tvLogsTitle = view.findViewById<TextView>(R.id.tv_logs_title)
        tvLogsTitle.setOnLongClickListener {
            it.performHapticFeedback(HapticFeedbackConstants.LONG_PRESS)
            showDebugMenu()
            true
        }

        btnAutoScroll.setOnClickListener {
            it.performHapticFeedback(HapticFeedbackConstants.VIRTUAL_KEY)
            autoScroll = !autoScroll
            updateAutoScrollUi()
            if (autoScroll) scrollView.post { scrollView.fullScroll(View.FOCUS_DOWN) }
        }

        btnCopy.setOnClickListener {
            it.performHapticFeedback(HapticFeedbackConstants.VIRTUAL_KEY)
            val text = textView.text.toString()
            if (text.isBlank()) return@setOnClickListener
            val clipboard = requireContext().getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager
            val clip = ClipData.newPlainText("Fluxon Logs", text)
            clipboard?.setPrimaryClip(clip)
            Toast.makeText(requireContext(), R.string.logs_copied, Toast.LENGTH_SHORT).show()
        }

        btnClear.setOnClickListener {
            it.performHapticFeedback(HapticFeedbackConstants.VIRTUAL_KEY)
            pending.clear()
            totalLines = 0
            textView.text = ""
            updateLineCount()
        }

        viewLifecycleOwner.lifecycleScope.launch {
            EventBus.events.collect { ev ->
                if (ev is AppEvent.LogMessage) enqueue(ev.message)
            }
        }
    }

    private fun updateLineCount() {
        tvLineCount.text = "$totalLines"
    }

    private fun updateAutoScrollUi() {
        btnAutoScroll.setColorFilter(
            ContextCompat.getColor(
                requireContext(),
                if (autoScroll) R.color.state_running else R.color.text_tertiary
            )
        )
    }

    private fun enqueue(message: String) {
        message.split('\n').forEach { if (it.isNotEmpty()) pending.offer(it) }
        if (!flushScheduled) {
            flushScheduled = true
            textView.postDelayed({ flushPending(); flushScheduled = false }, FLUSH_INTERVAL_MS)
        }
    }

    private fun flushPending() {
        if (pending.isEmpty()) return

        val toAppend = SpannableStringBuilder()
        var appended = 0
        while (appended < 64) {
            val line = pending.poll() ?: break
            if (totalLines > 0 || appended > 0) toAppend.append('\n')

            val lineNo = (totalLines + 1).toString().padStart(4)
            val base = toAppend.length
            toAppend.append(lineNo).append(' ')
            toAppend.setSpan(ForegroundColorSpan(0xFF64748B.toInt()), base, base + 4, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)

            val timePrefix = "[${ts.format(Date())}] "
            val tsStart = toAppend.length
            toAppend.append(timePrefix)
            toAppend.setSpan(ForegroundColorSpan(0xFF888888.toInt()), tsStart, tsStart + timePrefix.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)

            val msgStart = toAppend.length
            toAppend.append(line)

            val tagSpan = when {
                line.contains("[E]") -> ForegroundColorSpan(0xFFEF4444.toInt())
                line.contains("[W]") -> ForegroundColorSpan(0xFFF59E0B.toInt())
                line.contains("[I]") || line.contains("[S]") -> ForegroundColorSpan(0xFF10B981.toInt())
                line.contains("[D]") -> ForegroundColorSpan(0xFF38BDF8.toInt())
                else -> null
            }
            if (tagSpan != null) {
                toAppend.setSpan(tagSpan, msgStart, msgStart + line.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
            }

            totalLines++
            appended++
        }

        textView.append(toAppend)
        updateLineCount()

        val layout = textView.layout
        if (layout != null && textView.lineCount > MAX_LINES) {
            val cut = layout.getLineStart(textView.lineCount - MAX_LINES)
            textView.text = textView.text.subSequence(cut, textView.text.length)
        }

        if (autoScroll) scrollView.post { scrollView.fullScroll(View.FOCUS_DOWN) }

        if (!pending.isEmpty() && !flushScheduled) {
            flushScheduled = true
            textView.postDelayed({ flushPending(); flushScheduled = false }, FLUSH_INTERVAL_MS)
        }
    }

    override fun onDestroyView() {
        textView.handler?.removeCallbacksAndMessages(null)
        super.onDestroyView()
    }

    private fun showDebugMenu() {
        val bottomSheet = BottomSheetDialog(requireContext())
        val sheetView = layoutInflater.inflate(R.layout.bottom_sheet_debug_menu, null)
        bottomSheet.setContentView(sheetView)

        // 1. Force check update
        sheetView.findViewById<View>(R.id.card_debug_check_update).setOnClickListener {
            it.performHapticFeedback(HapticFeedbackConstants.VIRTUAL_KEY)
            bottomSheet.dismiss()
            Toast.makeText(requireContext(), "Проверка обновлений на GitHub…", Toast.LENGTH_SHORT).show()
            AppUpdateChecker.checkForUpdate(requireActivity(), force = true) { hasUpdate, versionOrError ->
                if (!hasUpdate) {
                    if (versionOrError.startsWith("HTTP") || versionOrError.contains("error", ignoreCase = true)) {
                        Toast.makeText(requireContext(), "Ошибка проверки: $versionOrError", Toast.LENGTH_SHORT).show()
                    } else {
                        Toast.makeText(requireContext(), getString(R.string.debug_up_to_date, versionOrError), Toast.LENGTH_SHORT).show()
                    }
                }
            }
        }

        // 2. Test update dialog (simulation)
        sheetView.findViewById<View>(R.id.card_debug_test_update_dialog).setOnClickListener {
            it.performHapticFeedback(HapticFeedbackConstants.VIRTUAL_KEY)
            bottomSheet.dismiss()
            AppUpdateChecker.showTestUpdateDialog(requireActivity())
        }

        // 3. Reset 24h timer
        sheetView.findViewById<View>(R.id.card_debug_reset_update_timer).setOnClickListener {
            it.performHapticFeedback(HapticFeedbackConstants.VIRTUAL_KEY)
            AppUpdateChecker.resetLastCheckTime(requireContext())
            Toast.makeText(requireContext(), R.string.debug_timer_reset_toast, Toast.LENGTH_SHORT).show()
        }

        // 4. Generate test logs
        sheetView.findViewById<View>(R.id.card_debug_generate_logs).setOnClickListener {
            it.performHapticFeedback(HapticFeedbackConstants.VIRTUAL_KEY)
            Logx.d("TestDebug", "Тестовый отладочный лог (DEBUG). Проверка цвета [D]")
            Logx.i("TestInfo", "Тестовый информационный лог (INFO). Проверка цвета [I]")
            Logx.w("TestWarning", "Тестовое предупреждение (WARNING). Проверка цвета [W]")
            Logx.e("TestError", "Тестовая ошибка (ERROR). Проверка цвета [E]", RuntimeException("Тестовый стектрейс исключения"))
            Toast.makeText(requireContext(), R.string.debug_logs_generated_toast, Toast.LENGTH_SHORT).show()
        }

        // 5. Toggle verbose logging
        val tvVerboseStatus = sheetView.findViewById<TextView>(R.id.tv_debug_verbose_status)
        fun updateVerboseStatus() {
            val level = if (Logx.isVerbose) "DEBUG (подробный)" else "INFO (по умолчанию)"
            tvVerboseStatus.text = getString(R.string.debug_toggle_verbose_desc, level)
        }
        updateVerboseStatus()

        sheetView.findViewById<View>(R.id.card_debug_toggle_verbose).setOnClickListener {
            it.performHapticFeedback(HapticFeedbackConstants.VIRTUAL_KEY)
            Logx.setVerbose(!Logx.isVerbose)
            updateVerboseStatus()
            val state = if (Logx.isVerbose) "DEBUG" else "INFO"
            Toast.makeText(requireContext(), "Уровень логов переключен на: $state", Toast.LENGTH_SHORT).show()
        }

        // 6. Test delete dialog
        sheetView.findViewById<View>(R.id.card_debug_test_delete_dialog).setOnClickListener {
            it.performHapticFeedback(HapticFeedbackConstants.VIRTUAL_KEY)
            bottomSheet.dismiss()
            showTestDeleteDialog()
        }

        // 7. System & build info
        val tvBuildInfo = sheetView.findViewById<TextView>(R.id.tv_debug_build_info)
        val abi = Build.SUPPORTED_ABIS.firstOrNull() ?: "unknown"
        tvBuildInfo.text = "Fluxon v${BuildConfig.VERSION_NAME} (${BuildConfig.BUILD_TYPE}) • Android ${Build.VERSION.RELEASE} (API ${Build.VERSION.SDK_INT}) • ABI: $abi"

        bottomSheet.show()
    }

    private fun showTestDeleteDialog() {
        val dialog = Dialog(requireContext())
        dialog.requestWindowFeature(Window.FEATURE_NO_TITLE)
        dialog.window?.setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))

        val dialogView = layoutInflater.inflate(R.layout.dialog_confirm_delete, null)
        dialog.setContentView(dialogView)

        dialogView.findViewById<TextView>(R.id.dialog_title).text = getString(R.string.delete_config_title)
        dialogView.findViewById<TextView>(R.id.dialog_message).text =
            "«Тестовая конфигурация»\n${getString(R.string.delete_config_msg)}"

        dialogView.findViewById<View>(R.id.btn_cancel).setOnClickListener {
            it.performHapticFeedback(HapticFeedbackConstants.VIRTUAL_KEY)
            dialog.dismiss()
        }

        dialogView.findViewById<View>(R.id.btn_delete).setOnClickListener {
            it.performHapticFeedback(HapticFeedbackConstants.VIRTUAL_KEY)
            dialog.dismiss()
            Toast.makeText(requireContext(), "Тестовое удаление подтверждено", Toast.LENGTH_SHORT).show()
        }

        dialog.show()

        val width = (resources.displayMetrics.widthPixels * 0.88).toInt()
        dialog.window?.setLayout(width, ViewGroup.LayoutParams.WRAP_CONTENT)
    }
}
