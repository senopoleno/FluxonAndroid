package io.github.p1neapplexpress.openflux.ui

import android.content.Context
import android.content.Intent
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import android.content.pm.ResolveInfo
import android.os.Bundle
import android.view.HapticFeedbackConstants
import android.view.View
import android.view.inputmethod.InputMethodManager
import android.widget.EditText
import android.widget.ImageView
import android.widget.ProgressBar
import android.widget.TextView
import androidx.activity.OnBackPressedCallback
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.WindowCompat
import androidx.core.view.isVisible
import androidx.core.widget.doAfterTextChanged
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.button.MaterialButton
import com.google.android.material.button.MaterialButtonToggleGroup
import io.github.p1neapplexpress.openflux.R
import io.github.p1neapplexpress.openflux.data.AppItem
import io.github.p1neapplexpress.openflux.util.RussianAppsPreset
import io.github.p1neapplexpress.openflux.util.SplitTunnelPreferences
import io.github.p1neapplexpress.openflux.util.ThemePreferences
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class SplitTunnelActivity : AppCompatActivity() {

    private lateinit var prefs: SplitTunnelPreferences
    private lateinit var adapter: AppsAdapter
    private val allApps: MutableList<AppItem> = mutableListOf()

    private lateinit var btnBack: ImageView
    private lateinit var titleContainer: View
    private lateinit var searchBarContainer: View
    private lateinit var btnSearchToggle: ImageView
    private lateinit var toggleGroup: MaterialButtonToggleGroup
    private lateinit var btnTabBypass: MaterialButton
    private lateinit var btnTabProxy: MaterialButton
    private lateinit var modeDescription: TextView
    private lateinit var selectedCountText: TextView
    private lateinit var btnSelectAll: TextView
    private lateinit var btnResetDefaults: TextView
    private lateinit var searchInput: EditText
    private lateinit var btnClearSearch: ImageView
    private lateinit var loadingProgress: ProgressBar
    private lateinit var emptyState: View
    private lateinit var recycler: RecyclerView

    override fun onCreate(savedInstanceState: Bundle?) {
        val themePrefs = ThemePreferences(this)
        themePrefs.applyTheme()
        super.onCreate(savedInstanceState)

        val isNight = when (themePrefs.themeMode) {
            ThemePreferences.THEME_LIGHT -> false
            ThemePreferences.THEME_DARK -> true
            else -> (resources.configuration.uiMode and android.content.res.Configuration.UI_MODE_NIGHT_MASK) == android.content.res.Configuration.UI_MODE_NIGHT_YES
        }
        val insetsController = WindowCompat.getInsetsController(window, window.decorView)
        insetsController.isAppearanceLightStatusBars = !isNight
        insetsController.isAppearanceLightNavigationBars = !isNight

        setContentView(R.layout.activity_split_tunnel)

        prefs = SplitTunnelPreferences(this)

        initViews()
        setupListeners()
        loadInstalledApps()

        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                if (searchBarContainer.isVisible) {
                    closeSearch()
                } else {
                    isEnabled = false
                    onBackPressedDispatcher.onBackPressed()
                }
            }
        })
    }

    private fun initViews() {
        btnBack = findViewById(R.id.btn_back)
        btnBack.setOnClickListener {
            it.performHapticFeedback(HapticFeedbackConstants.VIRTUAL_KEY)
            if (searchBarContainer.isVisible) {
                closeSearch()
            } else {
                finish()
            }
        }

        titleContainer = findViewById(R.id.title_container)
        searchBarContainer = findViewById(R.id.search_bar_container)
        btnSearchToggle = findViewById(R.id.btn_search_toggle)
        btnSearchToggle.setOnClickListener {
            it.performHapticFeedback(HapticFeedbackConstants.VIRTUAL_KEY)
            openSearch()
        }

        toggleGroup = findViewById(R.id.mode_toggle_group)
        btnTabBypass = findViewById(R.id.btn_tab_bypass)
        btnTabProxy = findViewById(R.id.btn_tab_proxy)
        modeDescription = findViewById(R.id.mode_description)
        selectedCountText = findViewById(R.id.selected_count_text)
        btnSelectAll = findViewById(R.id.btn_select_all)
        btnResetDefaults = findViewById(R.id.btn_reset_defaults)
        searchInput = findViewById(R.id.search_input)
        btnClearSearch = findViewById(R.id.btn_clear_search)
        loadingProgress = findViewById(R.id.loading_progress)
        emptyState = findViewById(R.id.empty_state)
        recycler = findViewById(R.id.apps_recycler)

        adapter = AppsAdapter { item ->
            val set = if (prefs.mode == SplitTunnelPreferences.MODE_BYPASS) {
                prefs.bypassApps.toMutableSet().apply {
                    if (item.isSelected) add(item.packageName) else remove(item.packageName)
                }
            } else {
                prefs.proxyApps.toMutableSet().apply {
                    if (item.isSelected) add(item.packageName) else remove(item.packageName)
                }
            }

            if (prefs.mode == SplitTunnelPreferences.MODE_BYPASS) {
                prefs.bypassApps = set
            } else {
                prefs.proxyApps = set
            }
            updateCounter()
            updateSelectAllButtonText()
        }

        adapter.onListFiltered = { count ->
            emptyState.isVisible = count == 0 && loadingProgress.visibility != View.VISIBLE
            recycler.isVisible = count > 0
            updateSelectAllButtonText()
        }

        recycler.layoutManager = LinearLayoutManager(this)
        recycler.adapter = adapter

        if (prefs.mode == SplitTunnelPreferences.MODE_PROXY) {
            toggleGroup.check(R.id.btn_tab_proxy)
        } else {
            toggleGroup.check(R.id.btn_tab_bypass)
        }
        updateModeUi()
    }

    private fun openSearch() {
        titleContainer.isVisible = false
        searchBarContainer.isVisible = true
        searchInput.requestFocus()
        val imm = getSystemService(Context.INPUT_METHOD_SERVICE) as? InputMethodManager
        imm?.showSoftInput(searchInput, InputMethodManager.SHOW_IMPLICIT)
    }

    private fun closeSearch() {
        val imm = getSystemService(Context.INPUT_METHOD_SERVICE) as? InputMethodManager
        imm?.hideSoftInputFromWindow(searchInput.windowToken, 0)
        searchInput.setText("")
        searchBarContainer.isVisible = false
        titleContainer.isVisible = true
    }

    private fun setupListeners() {
        toggleGroup.addOnButtonCheckedListener { _, checkedId, isChecked ->
            if (isChecked) {
                toggleGroup.performHapticFeedback(HapticFeedbackConstants.VIRTUAL_KEY)
                when (checkedId) {
                    R.id.btn_tab_bypass -> prefs.mode = SplitTunnelPreferences.MODE_BYPASS
                    R.id.btn_tab_proxy -> prefs.mode = SplitTunnelPreferences.MODE_PROXY
                }
                updateModeUi()
                refreshAppSelections()
            }
        }

        searchInput.doAfterTextChanged { text ->
            val q = text?.toString() ?: ""
            btnClearSearch.isVisible = q.isNotEmpty()
            adapter.filter(q)
        }

        btnClearSearch.setOnClickListener {
            it.performHapticFeedback(HapticFeedbackConstants.VIRTUAL_KEY)
            searchInput.setText("")
        }

        btnSelectAll.setOnClickListener {
            it.performHapticFeedback(HapticFeedbackConstants.VIRTUAL_KEY)
            val displayed = adapter.getDisplayedApps()
            val anyUnselected = displayed.any { !it.isSelected }
            val newSelectedState = anyUnselected

            val set = if (prefs.mode == SplitTunnelPreferences.MODE_BYPASS) {
                prefs.bypassApps.toMutableSet()
            } else {
                prefs.proxyApps.toMutableSet()
            }

            for (app in displayed) {
                app.isSelected = newSelectedState
                if (newSelectedState) {
                    set.add(app.packageName)
                } else {
                    set.remove(app.packageName)
                }
            }

            if (prefs.mode == SplitTunnelPreferences.MODE_BYPASS) {
                prefs.bypassApps = set
            } else {
                prefs.proxyApps = set
            }

            adapter.notifyDataSetChanged()
            updateCounter()
            updateSelectAllButtonText()
        }

        btnResetDefaults.setOnClickListener {
            it.performHapticFeedback(HapticFeedbackConstants.VIRTUAL_KEY)
            val installedPkgSet = allApps.map { it.packageName }.toSet()
            if (prefs.mode == SplitTunnelPreferences.MODE_BYPASS) {
                prefs.resetToDefaults(installedPkgSet)
            } else {
                prefs.proxyApps = emptySet()
            }
            refreshAppSelections()
        }
    }

    private fun updateModeUi() {
        if (prefs.mode == SplitTunnelPreferences.MODE_BYPASS) {
            modeDescription.setText(R.string.tab_bypass_desc)
        } else {
            modeDescription.setText(R.string.tab_proxy_desc)
        }
    }

    private fun refreshAppSelections() {
        val selectedSet = if (prefs.mode == SplitTunnelPreferences.MODE_BYPASS) {
            prefs.bypassApps
        } else {
            prefs.proxyApps
        }

        for (app in allApps) {
            app.isSelected = app.packageName in selectedSet
        }
        allApps.sortWith(
            compareByDescending<AppItem> { it.isSelected }
                .thenBy { !it.isRussianPreset }
                .thenBy { it.name.lowercase() }
        )
        adapter.submitList(allApps)
        adapter.filter(searchInput.text?.toString() ?: "")
        updateCounter()
        updateSelectAllButtonText()
    }

    private fun updateCounter() {
        val selectedCount = allApps.count { it.isSelected }
        val total = allApps.size
        selectedCountText.text = getString(R.string.selected_apps_count, selectedCount, total)
    }

    private fun updateSelectAllButtonText() {
        val displayed = adapter.getDisplayedApps()
        if (displayed.isNotEmpty() && displayed.all { it.isSelected }) {
            btnSelectAll.setText(R.string.deselect_all)
        } else {
            btnSelectAll.setText(R.string.select_all)
        }
    }

    private fun loadInstalledApps() {
        loadingProgress.visibility = View.VISIBLE
        emptyState.visibility = View.GONE
        recycler.visibility = View.GONE

        lifecycleScope.launch(Dispatchers.IO) {
            val pm = packageManager
            val selfPkg = packageName

            val launcherIntent = Intent(Intent.ACTION_MAIN, null).apply {
                addCategory(Intent.CATEGORY_LAUNCHER)
            }
            val launcherActivities = runCatching {
                pm.queryIntentActivities(launcherIntent, 0)
            }.getOrDefault(emptyList())

            val launcherMap = HashMap<String, ResolveInfo>()
            for (resolve in launcherActivities) {
                val pkg = resolve.activityInfo.packageName
                if (!launcherMap.containsKey(pkg)) {
                    launcherMap[pkg] = resolve
                }
            }

            val installed = runCatching {
                pm.getInstalledApplications(PackageManager.GET_META_DATA)
            }.getOrDefault(emptyList())

            val allPkgSet = LinkedHashSet<String>()
            allPkgSet.addAll(launcherMap.keys)
            for (app in installed) {
                allPkgSet.add(app.packageName)
            }
            allPkgSet.remove(selfPkg)

            val appItems = allPkgSet.mapNotNull { pkg ->
                runCatching {
                    val launcher = launcherMap[pkg]
                    val appInfo = installed.firstOrNull { it.packageName == pkg }
                        ?: pm.getApplicationInfo(pkg, 0)

                    val label = launcher?.loadLabel(pm)?.toString()
                        ?: appInfo.loadLabel(pm).toString()

                    val icon = launcher?.loadIcon(pm)
                        ?: appInfo.loadIcon(pm)

                    val isSystem = (appInfo.flags and ApplicationInfo.FLAG_SYSTEM) != 0
                    val isRussian = pkg in RussianAppsPreset.PACKAGE_NAMES

                    AppItem(
                        name = label,
                        packageName = pkg,
                        icon = icon,
                        isSelected = false,
                        isSystem = isSystem,
                        isRussianPreset = isRussian
                    )
                }.getOrNull()
            }.sortedWith(
                compareBy<AppItem> { item ->
                    when {
                        item.isRussianPreset -> 0
                        !item.isSystem -> 0
                        launcherMap.containsKey(item.packageName) -> 1
                        else -> 2
                    }
                }.thenBy { it.name.lowercase() }
            )

            withContext(Dispatchers.Main) {
                allApps.clear()
                allApps.addAll(appItems)

                if (!prefs.isInitialized()) {
                    val pkgSet = allApps.map { it.packageName }.toSet()
                    prefs.initializeDefaults(pkgSet)
                }

                refreshAppSelections()
                loadingProgress.visibility = View.GONE
                recycler.visibility = View.VISIBLE
            }
        }
    }
}
