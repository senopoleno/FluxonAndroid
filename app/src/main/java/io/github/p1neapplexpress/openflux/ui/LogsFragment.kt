package io.github.p1neapplexpress.openflux.ui

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.os.Bundle
import android.text.SpannableStringBuilder
import android.text.Spanned
import android.text.style.ForegroundColorSpan
import android.view.HapticFeedbackConstants
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import io.github.p1neapplexpress.openflux.R
import io.github.p1neapplexpress.openflux.event.AppEvent
import io.github.p1neapplexpress.openflux.event.EventBus
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

        val appSettings = io.github.p1neapplexpress.openflux.util.AppSettings(requireContext())
        if (appSettings.autoClearLogs) {
            val logPrefs = requireContext().getSharedPreferences("fluxon_logs_meta", Context.MODE_PRIVATE)
            val lastLogTime = logPrefs.getLong("last_log_ts", 0L)
            val now = System.currentTimeMillis()
            if (lastLogTime > 0 && (now - lastLogTime) > 24 * 60 * 60 * 1000L) {
                pending.clear()
                totalLines = 0
                textView.text = ""
            }
            logPrefs.edit().putLong("last_log_ts", now).apply()
        }

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
        activity?.let { DebugMenuHelper.show(it) }
    }
}
