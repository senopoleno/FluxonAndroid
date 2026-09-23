package io.github.p1neapplexpress.openflux.util

import android.content.Context
import android.content.SharedPreferences

class SplitTunnelPreferences(context: Context) {

    companion object {
        private const val PREFS_NAME = "openflux_split_tunnel"
        private const val KEY_ENABLED = "split_tunnel_enabled"
        private const val KEY_MODE = "split_tunnel_mode"
        private const val KEY_BYPASS_APPS = "bypass_apps"
        private const val KEY_PROXY_APPS = "proxy_apps"
        private const val KEY_HIDE_SYSTEM_APPS = "hide_system_apps"
        private const val KEY_INITIALIZED = "presets_initialized"

        private const val KEY_RU_PREFIX_V2 = "ru_prefix_defaults_applied_v2"

        const val MODE_BYPASS = "bypass"
        const val MODE_PROXY = "proxy"
    }

    private val prefs: SharedPreferences =
        context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    var isEnabled: Boolean
        get() = prefs.getBoolean(KEY_ENABLED, true)
        set(value) = prefs.edit().putBoolean(KEY_ENABLED, value).apply()

    var mode: String
        get() = prefs.getString(KEY_MODE, MODE_BYPASS) ?: MODE_BYPASS
        set(value) = prefs.edit().putString(KEY_MODE, value).apply()

    var hideSystemApps: Boolean
        get() = prefs.getBoolean(KEY_HIDE_SYSTEM_APPS, true)
        set(value) = prefs.edit().putBoolean(KEY_HIDE_SYSTEM_APPS, value).apply()

    var bypassApps: Set<String>
        get() = prefs.getStringSet(KEY_BYPASS_APPS, null)?.toSet() ?: RussianAppsPreset.PACKAGE_NAMES
        set(value) = prefs.edit().putStringSet(KEY_BYPASS_APPS, value).apply()

    var proxyApps: Set<String>
        get() = prefs.getStringSet(KEY_PROXY_APPS, emptySet())?.toSet() ?: emptySet()
        set(value) = prefs.edit().putStringSet(KEY_PROXY_APPS, value).apply()

    fun isInitialized(): Boolean = prefs.getBoolean(KEY_INITIALIZED, false)

    fun computeDefaultBypassApps(installedPackages: Collection<String>): Set<String> {
        return installedPackages.filter { pkg ->
            val lower = pkg.lowercase()
            pkg in RussianAppsPreset.PACKAGE_NAMES || lower.startsWith("ru.") || lower.startsWith("ru")
        }.toSet()
    }

    fun initializeDefaults(installedPackages: Collection<String>) {
        val defaultBypass = computeDefaultBypassApps(installedPackages)
        prefs.edit()
            .putBoolean(KEY_INITIALIZED, true)
            .putBoolean(KEY_RU_PREFIX_V2, true)
            .putStringSet(KEY_BYPASS_APPS, defaultBypass)
            .apply()
    }

    fun resetToDefaults(installedPackages: Collection<String>) {
        bypassApps = computeDefaultBypassApps(installedPackages)
    }

    fun ensureRuDefaultsMigrated(installedPackages: Collection<String>) {
        if (!prefs.getBoolean(KEY_RU_PREFIX_V2, false)) {
            val ruApps = computeDefaultBypassApps(installedPackages)
            val current = prefs.getStringSet(KEY_BYPASS_APPS, null)?.toSet() ?: RussianAppsPreset.PACKAGE_NAMES
            val merged = current + ruApps
            prefs.edit()
                .putBoolean(KEY_RU_PREFIX_V2, true)
                .putStringSet(KEY_BYPASS_APPS, merged)
                .apply()
        }
    }
}
