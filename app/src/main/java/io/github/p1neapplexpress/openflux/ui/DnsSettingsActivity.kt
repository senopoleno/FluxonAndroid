package io.github.p1neapplexpress.openflux.ui

import android.os.Bundle
import android.view.HapticFeedbackConstants
import android.view.View
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.RadioButton
import android.widget.RadioGroup
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.isVisible
import androidx.core.widget.doAfterTextChanged
import com.google.android.material.card.MaterialCardView
import com.google.android.material.materialswitch.MaterialSwitch
import com.google.android.material.textfield.TextInputEditText
import com.google.android.material.textfield.TextInputLayout
import io.github.p1neapplexpress.openflux.R
import io.github.p1neapplexpress.openflux.util.AppSettings
import io.github.p1neapplexpress.openflux.util.ThemePreferences

class DnsSettingsActivity : AppCompatActivity() {

    private lateinit var appSettings: AppSettings
    private lateinit var switchSystemDns: MaterialSwitch
    private lateinit var textSystemDnsSummary: TextView
    private lateinit var cardDoh: MaterialCardView
    private lateinit var switchDoh: MaterialSwitch
    private lateinit var containerDohProviders: LinearLayout
    private lateinit var groupDohProviders: RadioGroup
    private lateinit var layoutDohCustomUrl: TextInputLayout
    private lateinit var inputDohCustomUrl: TextInputEditText

    private lateinit var cardStandardDns: MaterialCardView
    private lateinit var groupDnsServers: RadioGroup
    private lateinit var containerCustomIp: LinearLayout
    private lateinit var inputCustomDnsPrimary: TextInputEditText
    private lateinit var inputCustomDnsSecondary: TextInputEditText

    override fun onCreate(savedInstanceState: Bundle?) {
        val themePrefs = ThemePreferences(this)
        themePrefs.applyTheme()
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_dns_settings)

        appSettings = AppSettings(this)

        findViewById<ImageView>(R.id.btn_back).setOnClickListener {
            it.performHapticFeedback(HapticFeedbackConstants.VIRTUAL_KEY)
            finish()
        }

        switchSystemDns = findViewById(R.id.switch_system_dns)
        textSystemDnsSummary = findViewById(R.id.text_system_dns_summary)
        cardDoh = findViewById(R.id.card_doh)
        switchDoh = findViewById(R.id.switch_doh)
        containerDohProviders = findViewById(R.id.container_doh_providers)
        groupDohProviders = findViewById(R.id.group_doh_providers)
        layoutDohCustomUrl = findViewById(R.id.layout_doh_custom_url)
        inputDohCustomUrl = findViewById(R.id.input_doh_custom_url)

        cardStandardDns = findViewById(R.id.card_standard_dns)
        groupDnsServers = findViewById(R.id.group_dns_servers)
        containerCustomIp = findViewById(R.id.container_custom_ip)
        inputCustomDnsPrimary = findViewById(R.id.input_custom_dns_primary)
        inputCustomDnsSecondary = findViewById(R.id.input_custom_dns_secondary)

        initValues()
        setupListeners()
        updateUiState()
    }

    private fun initValues() {
        switchSystemDns.isChecked = appSettings.useSystemDns
        switchSystemDns.jumpDrawablesToCurrentState()
        switchDoh.isChecked = appSettings.dohEnabled
        switchDoh.jumpDrawablesToCurrentState()

        val sysDns = appSettings.primaryDns
        val summaryHtml = getString(R.string.dns_use_system_summary, "<font color='#2563EB'>$sysDns</font>")
        textSystemDnsSummary.text = androidx.core.text.HtmlCompat.fromHtml(summaryHtml, androidx.core.text.HtmlCompat.FROM_HTML_MODE_LEGACY)

        when (appSettings.dohProvider) {
            AppSettings.DOH_PROVIDER_CLOUDFLARE -> findViewById<RadioButton>(R.id.radio_doh_cloudflare).isChecked = true
            AppSettings.DOH_PROVIDER_GOOGLE -> findViewById<RadioButton>(R.id.radio_doh_google).isChecked = true
            AppSettings.DOH_PROVIDER_ADGUARD -> findViewById<RadioButton>(R.id.radio_doh_adguard).isChecked = true
            AppSettings.DOH_PROVIDER_QUAD9 -> findViewById<RadioButton>(R.id.radio_doh_quad9).isChecked = true
            AppSettings.DOH_PROVIDER_CUSTOM -> findViewById<RadioButton>(R.id.radio_doh_custom).isChecked = true
            else -> findViewById<RadioButton>(R.id.radio_doh_cloudflare).isChecked = true
        }

        inputDohCustomUrl.setText(appSettings.dohCustomUrl)

        when (appSettings.dnsMode) {
            AppSettings.DNS_MODE_CLOUDFLARE -> findViewById<RadioButton>(R.id.radio_dns_cloudflare).isChecked = true
            AppSettings.DNS_MODE_GOOGLE -> findViewById<RadioButton>(R.id.radio_dns_google).isChecked = true
            AppSettings.DNS_MODE_ADGUARD -> findViewById<RadioButton>(R.id.radio_dns_adguard).isChecked = true
            AppSettings.DNS_MODE_QUAD9 -> findViewById<RadioButton>(R.id.radio_dns_quad9).isChecked = true
            AppSettings.DNS_MODE_CUSTOM -> findViewById<RadioButton>(R.id.radio_dns_custom).isChecked = true
            else -> findViewById<RadioButton>(R.id.radio_dns_cloudflare).isChecked = true
        }

        inputCustomDnsPrimary.setText(appSettings.customDnsPrimary)
        inputCustomDnsSecondary.setText(appSettings.customDnsSecondary)
    }

    private fun setupListeners() {
        switchSystemDns.setOnCheckedChangeListener { _, isChecked ->
            appSettings.useSystemDns = isChecked
            updateUiState()
        }

        switchDoh.setOnCheckedChangeListener { _, isChecked ->
            appSettings.dohEnabled = isChecked
            updateUiState()
        }

        groupDohProviders.setOnCheckedChangeListener { _, checkedId ->
            val provider = when (checkedId) {
                R.id.radio_doh_cloudflare -> AppSettings.DOH_PROVIDER_CLOUDFLARE
                R.id.radio_doh_google -> AppSettings.DOH_PROVIDER_GOOGLE
                R.id.radio_doh_adguard -> AppSettings.DOH_PROVIDER_ADGUARD
                R.id.radio_doh_quad9 -> AppSettings.DOH_PROVIDER_QUAD9
                R.id.radio_doh_custom -> AppSettings.DOH_PROVIDER_CUSTOM
                else -> AppSettings.DOH_PROVIDER_CLOUDFLARE
            }
            appSettings.dohProvider = provider
            layoutDohCustomUrl.isVisible = (provider == AppSettings.DOH_PROVIDER_CUSTOM)
        }

        inputDohCustomUrl.doAfterTextChanged { text ->
            appSettings.dohCustomUrl = text?.toString()?.trim().orEmpty()
        }

        groupDnsServers.setOnCheckedChangeListener { _, checkedId ->
            val mode = when (checkedId) {
                R.id.radio_dns_cloudflare -> AppSettings.DNS_MODE_CLOUDFLARE
                R.id.radio_dns_google -> AppSettings.DNS_MODE_GOOGLE
                R.id.radio_dns_adguard -> AppSettings.DNS_MODE_ADGUARD
                R.id.radio_dns_quad9 -> AppSettings.DNS_MODE_QUAD9
                R.id.radio_dns_custom -> AppSettings.DNS_MODE_CUSTOM
                else -> AppSettings.DNS_MODE_CLOUDFLARE
            }
            appSettings.dnsMode = mode
            containerCustomIp.isVisible = (mode == AppSettings.DNS_MODE_CUSTOM)
        }

        inputCustomDnsPrimary.doAfterTextChanged { text ->
            appSettings.customDnsPrimary = text?.toString()?.trim().orEmpty()
        }

        inputCustomDnsSecondary.doAfterTextChanged { text ->
            appSettings.customDnsSecondary = text?.toString()?.trim().orEmpty()
        }
    }

    private fun updateUiState() {
        val useSystem = appSettings.useSystemDns
        val dohEnabled = appSettings.dohEnabled

        if (useSystem) {
            cardDoh.alpha = 0.45f
            switchDoh.isEnabled = false
            containerDohProviders.isVisible = false

            cardStandardDns.alpha = 0.45f
            setGroupEnabled(groupDnsServers, false)
            containerCustomIp.isVisible = false
        } else {
            cardDoh.alpha = 1.0f
            switchDoh.isEnabled = true
            containerDohProviders.isVisible = dohEnabled
            layoutDohCustomUrl.isVisible = dohEnabled && (appSettings.dohProvider == AppSettings.DOH_PROVIDER_CUSTOM)

            if (dohEnabled) {
                cardStandardDns.alpha = 0.45f
                setGroupEnabled(groupDnsServers, false)
                containerCustomIp.isVisible = false
            } else {
                cardStandardDns.alpha = 1.0f
                setGroupEnabled(groupDnsServers, true)
                containerCustomIp.isVisible = (appSettings.dnsMode == AppSettings.DNS_MODE_CUSTOM)
            }
        }
    }

    private fun setGroupEnabled(group: RadioGroup, enabled: Boolean) {
        for (i in 0 until group.childCount) {
            group.getChildAt(i).isEnabled = enabled
        }
    }
}
