package io.github.p1neapplexpress.openflux.util

import android.content.ComponentName
import android.content.Context
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Path
import androidx.core.content.ContextCompat
import io.github.p1neapplexpress.openflux.R

object AppIconManager {
    const val ICON_DARK = "dark"
    const val ICON_LIGHT = "light"

    private const val ALIAS_DARK = "io.github.p1neapplexpress.openflux.ui.MainActivityDark"
    private const val ALIAS_LIGHT = "io.github.p1neapplexpress.openflux.ui.MainActivityLight"
    private const val ALIAS_DEFAULT = "io.github.p1neapplexpress.openflux.ui.MainActivityDefault"

    fun getCurrentIcon(context: Context): String {
        val appSettings = AppSettings(context)
        val icon = appSettings.appIcon
        return if (icon == ICON_LIGHT) ICON_LIGHT else ICON_DARK
    }

    fun getNotificationLargeIcon(context: Context): Bitmap? {
        val isLight = getCurrentIcon(context) == ICON_LIGHT
        val iconRes = if (isLight) R.mipmap.ic_launcher_round_light else R.mipmap.ic_launcher_round_dark
        return try {
            val drawable = ContextCompat.getDrawable(context, iconRes) ?: return null
            val size = (64 * context.resources.displayMetrics.density).toInt().coerceAtLeast(1)
            val bitmap = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
            val canvas = Canvas(bitmap)
            val path = Path().apply {
                addCircle(size / 2f, size / 2f, size / 2f, Path.Direction.CW)
            }
            canvas.clipPath(path)
            drawable.setBounds(0, 0, size, size)
            drawable.draw(canvas)
            bitmap
        } catch (_: Throwable) {
            null
        }
    }

    fun setAppIcon(context: Context, iconKey: String) {
        val pm = context.packageManager
        val appSettings = AppSettings(context)
        val targetKey = if (iconKey == ICON_LIGHT) ICON_LIGHT else ICON_DARK
        appSettings.appIcon = targetKey

        val aliases = mapOf(
            ICON_DARK to ComponentName(context, ALIAS_DARK),
            ICON_LIGHT to ComponentName(context, ALIAS_LIGHT)
        )

        for ((key, component) in aliases) {
            val newState = if (key == targetKey) {
                PackageManager.COMPONENT_ENABLED_STATE_ENABLED
            } else {
                PackageManager.COMPONENT_ENABLED_STATE_DISABLED
            }
            try {
                pm.setComponentEnabledSetting(
                    component,
                    newState,
                    PackageManager.DONT_KILL_APP
                )
            } catch (_: Exception) {}
        }

        // Keep legacy alias disabled to prevent duplicate launcher icons
        try {
            pm.setComponentEnabledSetting(
                ComponentName(context, ALIAS_DEFAULT),
                PackageManager.COMPONENT_ENABLED_STATE_DISABLED,
                PackageManager.DONT_KILL_APP
            )
        } catch (_: Exception) {}
    }
}
