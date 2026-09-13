package io.github.p1neapplexpress.openflux.util

import android.app.Activity
import android.app.Dialog
import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.graphics.drawable.ColorDrawable
import android.net.Uri
import android.view.HapticFeedbackConstants
import android.view.View
import android.view.ViewGroup
import android.view.Window
import android.widget.TextView
import io.github.p1neapplexpress.openflux.BuildConfig
import io.github.p1neapplexpress.openflux.R
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.io.BufferedReader
import java.io.InputStreamReader
import java.net.HttpURLConnection
import java.net.URL

object AppUpdateChecker {

    private const val TAG = "AppUpdateChecker"
    private const val PREFS_NAME = "fluxon_update_checker"
    private const val KEY_LAST_CHECK_MS = "last_check_ms"
    private const val CHECK_INTERVAL_MS = 24 * 60 * 60 * 1000L // 24 hours

    private const val GITHUB_API_URL =
        "https://api.github.com/repos/senopoleno/FluxonAndroid/releases/latest"

    private val json = Json {
        ignoreUnknownKeys = true
        isLenient = true
    }

    @Serializable
    data class GitHubRelease(
        val tag_name: String = "",
        val name: String? = null,
        val html_url: String = "",
        val body: String? = null,
        val assets: List<GitHubAsset> = emptyList(),
    )

    @Serializable
    data class GitHubAsset(
        val name: String = "",
        val browser_download_url: String = "",
    )

    fun checkForUpdate(
        activity: Activity,
        force: Boolean = false,
        onResult: ((hasUpdate: Boolean, versionOrError: String) -> Unit)? = null
    ) {
        val prefs = activity.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val lastCheck = prefs.getLong(KEY_LAST_CHECK_MS, 0L)
        val now = System.currentTimeMillis()

        if (!force) {
            val appSettings = AppSettings(activity)
            if (!appSettings.autoUpdateCheck) {
                return
            }
            if ((now - lastCheck) < CHECK_INTERVAL_MS) {
                return
            }
        }

        CoroutineScope(Dispatchers.IO).launch {
            try {
                val url = URL(GITHUB_API_URL)
                val conn = (url.openConnection() as HttpURLConnection).apply {
                    connectTimeout = 7000
                    readTimeout = 7000
                    requestMethod = "GET"
                    setRequestProperty("Accept", "application/vnd.github.v3+json")
                    setRequestProperty("User-Agent", "Fluxon-Android/${BuildConfig.VERSION_NAME}")
                }

                if (conn.responseCode != 200) {
                    Logx.d(TAG, "GitHub releases API returned HTTP ${conn.responseCode}")
                    withContext(Dispatchers.Main) {
                        onResult?.invoke(false, "HTTP ${conn.responseCode}")
                    }
                    return@launch
                }

                val responseBody = BufferedReader(InputStreamReader(conn.inputStream)).use { it.readText() }
                val release = json.decodeFromString<GitHubRelease>(responseBody)

                prefs.edit().putLong(KEY_LAST_CHECK_MS, now).apply()

                val remoteTag = release.tag_name
                val currentVersion = BuildConfig.VERSION_NAME

                if (isNewerVersion(remoteTag, currentVersion)) {
                    Logx.i(TAG, "Update available: $remoteTag (current: $currentVersion)")
                    val apkAsset = release.assets.firstOrNull { it.name.endsWith(".apk", ignoreCase = true) }
                    val downloadUrl = apkAsset?.browser_download_url?.ifBlank { null } ?: release.html_url

                    withContext(Dispatchers.Main) {
                        onResult?.invoke(true, remoteTag)
                        if (!activity.isFinishing && !activity.isDestroyed) {
                            showUpdateDialog(activity, release, downloadUrl)
                        }
                    }
                } else {
                    Logx.d(TAG, "App is up to date: current=$currentVersion, remote=$remoteTag")
                    withContext(Dispatchers.Main) {
                        onResult?.invoke(false, remoteTag)
                    }
                }
            } catch (e: Exception) {
                Logx.d(TAG, "Failed to check for updates: ${e.message}")
                withContext(Dispatchers.Main) {
                    onResult?.invoke(false, e.message ?: "Network error")
                }
            }
        }
    }

    fun showTestUpdateDialog(activity: Activity) {
        val testRelease = GitHubRelease(
            tag_name = "v1.0.1",
            name = "Fluxon v1.0.1 (Тестовый релиз)",
            html_url = "https://github.com/senopoleno/FluxonAndroid/releases",
            body = "1. **Тестирование системы обновлений**:\n   - Уведомление в приложении работает корректно!\n2. **Улучшения интерфейса**:\n   - Проверка всех функций приложения.\n   - Быстрый отклик и стабильность.",
            assets = listOf(
                GitHubAsset(
                    name = "Fluxon.apk",
                    browser_download_url = "https://github.com/senopoleno/FluxonAndroid/releases/latest"
                )
            )
        )
        showUpdateDialog(activity, testRelease, testRelease.assets.first().browser_download_url)
    }

    fun resetLastCheckTime(context: Context) {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        prefs.edit().remove(KEY_LAST_CHECK_MS).apply()
    }

    fun isNewerVersion(remoteTag: String, currentVersion: String): Boolean {
        val cleanRemote = remoteTag.trim().removePrefix("v").removePrefix("V")
        val cleanCurrent = currentVersion.trim().removePrefix("v").removePrefix("V")

        val remoteParts = cleanRemote.split(".").mapNotNull { it.toIntOrNull() }
        val currentParts = cleanCurrent.split(".").mapNotNull { it.toIntOrNull() }

        if (remoteParts.isEmpty() || currentParts.isEmpty()) return false

        val maxLen = maxOf(remoteParts.size, currentParts.size)
        for (i in 0 until maxLen) {
            val r = remoteParts.getOrElse(i) { 0 }
            val c = currentParts.getOrElse(i) { 0 }
            if (r > c) return true
            if (r < c) return false
        }
        return false
    }

    private fun showUpdateDialog(activity: Activity, release: GitHubRelease, downloadUrl: String) {
        val dialog = Dialog(activity)
        dialog.requestWindowFeature(Window.FEATURE_NO_TITLE)
        dialog.window?.setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))

        val view = activity.layoutInflater.inflate(R.layout.dialog_update_available, null)
        dialog.setContentView(view)

        val versionText = String.format(
            activity.getString(R.string.update_version_format),
            release.tag_name,
            BuildConfig.VERSION_NAME
        )
        view.findViewById<TextView>(R.id.update_version_info).text = versionText

        val notesView = view.findViewById<TextView>(R.id.update_notes)
        val body = release.body?.trim().orEmpty()
        if (body.isNotEmpty()) {
            notesView.text = body
        } else {
            notesView.text = activity.getString(R.string.update_no_changelog)
        }

        view.findViewById<View>(R.id.btn_download_update).setOnClickListener {
            it.performHapticFeedback(HapticFeedbackConstants.VIRTUAL_KEY)
            dialog.dismiss()
            try {
                val intent = Intent(Intent.ACTION_VIEW, Uri.parse(downloadUrl)).apply {
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                }
                activity.startActivity(intent)
            } catch (e: Exception) {
                Logx.e(TAG, "Failed to launch browser for update", e)
            }
        }

        view.findViewById<View>(R.id.btn_update_later).setOnClickListener {
            it.performHapticFeedback(HapticFeedbackConstants.VIRTUAL_KEY)
            dialog.dismiss()
        }

        dialog.show()

        val width = (activity.resources.displayMetrics.widthPixels * 0.88).toInt()
        dialog.window?.setLayout(width, ViewGroup.LayoutParams.WRAP_CONTENT)
    }
}
