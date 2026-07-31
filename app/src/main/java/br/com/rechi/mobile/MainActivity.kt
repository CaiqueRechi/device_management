package br.com.rechi.mobile

import android.Manifest
import android.annotation.SuppressLint
import android.app.Activity
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.net.wifi.ScanResult
import android.net.Uri
import android.net.wifi.WifiConfiguration
import android.net.wifi.WifiManager
import android.net.wifi.WifiNetworkSuggestion
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.text.InputType
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.WindowInsets
import android.view.WindowManager
import android.webkit.WebResourceError
import android.webkit.WebResourceRequest
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.Button
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ArrayAdapter
import android.widget.ProgressBar
import android.widget.ScrollView
import android.widget.Spinner
import android.widget.TextView
import android.widget.Toast
import br.com.rechi.mobile.kiosk.KioskPolicyController
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.nio.charset.StandardCharsets
import java.security.KeyFactory
import java.security.Signature
import java.security.spec.X509EncodedKeySpec
import java.util.Base64
import java.util.UUID

class MainActivity : Activity() {
    private lateinit var webView: WebView
    private lateinit var setupContainer: ScrollView
    private lateinit var setupView: LinearLayout
    private lateinit var wifiStepView: LinearLayout
    private lateinit var apiStepView: LinearLayout
    private lateinit var setupTitle: TextView
    private lateinit var setupDetails: TextView
    private lateinit var connectionSection: InfoDropdown
    private lateinit var apiSection: InfoDropdown
    private lateinit var deviceSection: InfoDropdown
    private lateinit var wifiNetworkSpinner: Spinner
    private lateinit var wifiPasswordInput: EditText
    private lateinit var wifiPasswordToggleButton: Button
    private lateinit var wifiProgressBar: ProgressBar
    private lateinit var wifiStatusText: TextView
    private lateinit var wifiAdapter: ArrayAdapter<WifiNetworkOption>
    private val visibleWifiNetworks = mutableListOf<WifiNetworkOption>()
    private val mainHandler = Handler(Looper.getMainLooper())
    private var kioskUrl = DEFAULT_KIOSK_URL
    private var kioskHost = DEFAULT_KIOSK_HOST
    private var apiConfigStatus = ""
    private var cachedServerPublicKeyBase64 = ""
    private var publicKeyFetchError = ""
    private var lastApiServerTimeEpochSeconds = 0L
    private var isWifiPasswordVisible = false
    private val androidId: String by lazy { resolveAndroidId() }
    private val deviceUuid: String by lazy { resolveDeviceUuid() }
    @Volatile
    private var isFetchingApiConfig = false
    private var pendingWifiSsid = ""
    private var wifiConnectDeadlineMillis = 0L
    private val wifiScanReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            updateVisibleWifiNetworks()
        }
    }
    private val wifiConnectionWatcher = object : Runnable {
        override fun run() {
            val currentSsid = currentWifiSsid()

            when {
                pendingWifiSsid.isNotBlank() && isWifiConnectedTo(pendingWifiSsid) -> {
                    wifiProgressBar.visibility = View.GONE
                    wifiStatusText.text = getString(R.string.wifi_connection_success, pendingWifiSsid)
                    showToast(getString(R.string.wifi_connection_success, pendingWifiSsid))
                    loadKioskUrl()
                }
                System.currentTimeMillis() > wifiConnectDeadlineMillis -> {
                    wifiProgressBar.visibility = View.GONE
                    wifiStatusText.text = getString(
                        R.string.wifi_connection_timeout,
                        pendingWifiSsid.ifBlank { getString(R.string.wifi_unknown_network) },
                        currentSsid.ifBlank { getString(R.string.network_unavailable) }
                    )
                }
                else -> {
                    wifiStatusText.text = getString(
                        R.string.wifi_connection_loading,
                        pendingWifiSsid,
                        currentSsid.ifBlank { getString(R.string.network_unavailable) }
                    )
                    mainHandler.postDelayed(this, WIFI_CONNECT_POLL_MS)
                }
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        KioskPolicyController.applyKioskPolicies(this)
        setContentView(buildContent())
        hideSystemUi()
        registerWifiScanReceiver()
        configureWebView()
    }

    override fun onDestroy() {
        mainHandler.removeCallbacks(wifiConnectionWatcher)
        runCatching { unregisterReceiver(wifiScanReceiver) }
        super.onDestroy()
    }

    override fun onResume() {
        super.onResume()
        hideSystemUi()
        KioskPolicyController.applyKioskPolicies(this)
        KioskPolicyController.startKiosk(this)

        refreshSetupInfo()

        if (webView.url.isNullOrBlank() && isWifiConnected()) {
            loadKioskUrl()
        } else if (webView.url.isNullOrBlank()) {
            showWifiSetupScreen(getString(R.string.kiosk_wifi_required_details))
            startWifiScan()
        }
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)

        if (hasFocus) {
            hideSystemUi()
            KioskPolicyController.applyKioskPolicies(this)
            KioskPolicyController.startKiosk(this)
        }
    }

    override fun onUserLeaveHint() {
        super.onUserLeaveHint()
        KioskPolicyController.startKiosk(this)
    }

    @Deprecated("Back button is intentionally captured in kiosk mode.")
    override fun onBackPressed() {
        loadKioskUrl()
    }

    private fun buildContent(): View {
        return FrameLayout(this).apply {
            setBackgroundColor(Color.WHITE)

            webView = WebView(this@MainActivity).apply {
                id = View.generateViewId()
                isLongClickable = false
                setOnLongClickListener { true }
            }

            setupView = LinearLayout(this@MainActivity).apply {
                orientation = LinearLayout.VERTICAL
                gravity = Gravity.CENTER
                setPadding(48, 48, 48, 48)
                setBackgroundColor(Color.WHITE)

                setupTitle = TextView(this@MainActivity).apply {
                    text = getString(R.string.kiosk_setup_title)
                    setTextColor(0xFF101820.toInt())
                    textSize = 24f
                    typeface = Typeface.DEFAULT_BOLD
                    gravity = Gravity.CENTER
                }

                setupDetails = TextView(this@MainActivity).apply {
                    text = getString(R.string.kiosk_wifi_required_details)
                    setTextColor(0xFF344054.toInt())
                    textSize = 16f
                    gravity = Gravity.CENTER
                    setPadding(0, 20, 0, 20)
                }

                connectionSection = buildDropdownSection(
                    getString(R.string.dropdown_connection_title),
                    expandedByDefault = true
                )
                apiSection = buildDropdownSection(getString(R.string.dropdown_api_title))
                deviceSection = buildDropdownSection(getString(R.string.dropdown_device_title))
                visibleWifiNetworks.replaceWithSingle(
                    WifiNetworkOption("", getString(R.string.wifi_select_network_placeholder))
                )

                wifiAdapter = ArrayAdapter(
                    this@MainActivity,
                    android.R.layout.simple_spinner_item,
                    visibleWifiNetworks
                ).apply {
                    setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item)
                }

                wifiNetworkSpinner = Spinner(this@MainActivity).apply {
                    adapter = wifiAdapter
                    prompt = getString(R.string.wifi_select_network_prompt)
                    setOnTouchListener { _, event ->
                        if (event.action == MotionEvent.ACTION_UP) {
                            startWifiScan()
                        }
                        false
                    }
                }

                wifiPasswordInput = EditText(this@MainActivity).apply {
                    hint = getString(R.string.wifi_password_hint)
                    setSingleLine(true)
                    inputType = InputType.TYPE_CLASS_TEXT or
                        InputType.TYPE_TEXT_VARIATION_PASSWORD
                }
                wifiPasswordToggleButton = Button(this@MainActivity).apply {
                    text = getString(R.string.show_password)
                    textSize = 13f
                    isAllCaps = false
                    setOnClickListener { toggleWifiPasswordVisibility() }
                }

                wifiProgressBar = ProgressBar(this@MainActivity).apply {
                    isIndeterminate = true
                    visibility = View.GONE
                }

                wifiStatusText = TextView(this@MainActivity).apply {
                    text = getString(R.string.wifi_scan_waiting)
                    setTextColor(0xFF344054.toInt())
                    textSize = 14f
                    gravity = Gravity.CENTER
                    setPadding(0, 16, 0, 16)
                }

                wifiStepView = LinearLayout(this@MainActivity).apply {
                    orientation = LinearLayout.VERTICAL
                    addView(connectionSection.container, statusTextLayout())
                    addView(wifiNetworkSpinner, compactTextLayout())
                    addView(buildPasswordRow(), compactTextLayout())
                    addView(wifiProgressBar, compactTextLayout())
                    addView(wifiStatusText, compactTextLayout())
                    addView(buildButton(getString(R.string.connect_wifi)) {
                        connectWifiFromForm()
                    })
                    addView(buildButton(getString(R.string.disconnect_wifi)) {
                        disconnectWifi()
                    })
                }

                apiStepView = LinearLayout(this@MainActivity).apply {
                    orientation = LinearLayout.VERTICAL
                    addView(apiSection.container, statusTextLayout())
                    addView(deviceSection.container, statusTextLayout())
                    addView(buildButton(getString(R.string.retry_connection)) {
                        loadKioskUrl()
                    })
                    addView(buildButton(getString(R.string.back_to_wifi_setup)) {
                        showWifiSetupScreen(getString(R.string.kiosk_wifi_required_details))
                    })
                }

                addView(setupTitle, compactTextLayout())
                addView(setupDetails, compactTextLayout())
                addView(wifiStepView, compactTextLayout())
                addView(apiStepView, compactTextLayout())
            }

            addView(
                webView,
                FrameLayout.LayoutParams(
                    FrameLayout.LayoutParams.MATCH_PARENT,
                    FrameLayout.LayoutParams.MATCH_PARENT
                )
            )
            addView(
                ScrollView(this@MainActivity).apply {
                    setBackgroundColor(Color.WHITE)
                    addView(setupView)
                }.also { setupContainer = it },
                FrameLayout.LayoutParams(
                    FrameLayout.LayoutParams.MATCH_PARENT,
                    FrameLayout.LayoutParams.MATCH_PARENT
                )
            )
        }
    }

    @SuppressLint("SetJavaScriptEnabled")
    private fun configureWebView() {
        WebView.setWebContentsDebuggingEnabled(false)

        webView.settings.apply {
            javaScriptEnabled = true
            domStorageEnabled = true
            cacheMode = WebSettings.LOAD_DEFAULT
            mediaPlaybackRequiresUserGesture = false
            setSupportMultipleWindows(false)
        }

        webView.webViewClient = object : WebViewClient() {
            override fun shouldOverrideUrlLoading(
                view: WebView,
                request: WebResourceRequest
            ): Boolean {
                val requestedUrl = request.url
                return if (isAllowedUrl(requestedUrl)) {
                    false
                } else {
                    view.loadUrl(kioskUrl)
                    true
                }
            }

            override fun onPageFinished(view: WebView, url: String) {
                setupContainer.visibility = View.GONE
                webView.visibility = View.VISIBLE
            }

            override fun onReceivedError(
                view: WebView,
                request: WebResourceRequest,
                error: WebResourceError
            ) {
                if (request.isForMainFrame) {
                    showApiSetupScreen(getString(R.string.kiosk_connection_error))
                }
            }
        }

        loadKioskUrl()
    }

    private fun buildButton(label: String, onClick: () -> Unit): Button {
        return Button(this).apply {
            text = label
            textSize = 15f
            isAllCaps = false
            setOnClickListener { onClick() }
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply {
                topMargin = 12
            }
        }
    }

    private fun buildPasswordRow(): View {
        return LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL

            addView(
                wifiPasswordInput,
                LinearLayout.LayoutParams(
                    0,
                    LinearLayout.LayoutParams.WRAP_CONTENT,
                    1f
                )
            )
            addView(
                wifiPasswordToggleButton,
                LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.WRAP_CONTENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT
                ).apply {
                    leftMargin = 12
                }
            )
        }
    }

    private fun buildDropdownSection(title: String, expandedByDefault: Boolean = false): InfoDropdown {
        val container = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            background = roundedBackground(0xFFF3F6FA.toInt())
        }
        val header = TextView(this).apply {
            setTextColor(0xFF101820.toInt())
            textSize = 15f
            typeface = Typeface.DEFAULT_BOLD
            gravity = Gravity.CENTER_VERTICAL
            setPadding(24, 18, 24, 18)
            isClickable = true
        }
        val body = TextView(this).apply {
            setTextColor(0xFF344054.toInt())
            textSize = 13f
            gravity = Gravity.START
            setPadding(24, 0, 24, 20)
            visibility = if (expandedByDefault) View.VISIBLE else View.GONE
        }
        val section = InfoDropdown(
            container = container,
            header = header,
            body = body,
            title = title,
            expanded = expandedByDefault
        )

        header.setOnClickListener {
            setDropdownExpanded(section, !section.expanded)
        }
        container.addView(header, compactTextLayout())
        container.addView(body, compactTextLayout())
        setDropdownExpanded(section, expandedByDefault)

        return section
    }

    private fun setDropdownExpanded(section: InfoDropdown, expanded: Boolean) {
        section.expanded = expanded
        section.body.visibility = if (expanded) View.VISIBLE else View.GONE
        section.header.text = getString(
            if (expanded) {
                R.string.dropdown_header_open
            } else {
                R.string.dropdown_header_closed
            },
            section.title
        )
    }

    private fun roundedBackground(color: Int): GradientDrawable {
        return GradientDrawable().apply {
            shape = GradientDrawable.RECTANGLE
            cornerRadius = 8f
            setColor(color)
            setStroke(1, 0xFFE1E7EF.toInt())
        }
    }

    private fun compactTextLayout(): LinearLayout.LayoutParams {
        return LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT,
            LinearLayout.LayoutParams.WRAP_CONTENT
        )
    }

    private fun statusTextLayout(): LinearLayout.LayoutParams {
        return compactTextLayout().apply {
            bottomMargin = 10
        }
    }

    private fun loadKioskUrl() {
        refreshSetupInfo()

        if (!isWifiConnected()) {
            showWifiSetupScreen(getString(R.string.kiosk_wifi_required_details))
            return
        }

        showApiSetupScreen(getString(R.string.kiosk_api_loading_details))
        fetchApiConfig { success ->
            if (success) {
                setupContainer.visibility = View.GONE
                webView.visibility = View.VISIBLE
                webView.loadUrl(kioskUrl)
            } else {
                showApiSetupScreen(getString(R.string.api_config_failed_details, apiConfigStatus))
            }
        }
    }

    private fun showWifiSetupScreen(details: String) {
        showSetupScreen(
            title = getString(R.string.kiosk_wifi_step_title),
            details = details,
            step = SetupStep.WIFI
        )
    }

    private fun showApiSetupScreen(details: String) {
        showSetupScreen(
            title = getString(R.string.kiosk_api_step_title),
            details = details,
            step = SetupStep.API
        )
    }

    private fun showSetupScreen(title: String, details: String, step: SetupStep) {
        setupTitle.text = title
        setupDetails.text = details
        refreshSetupInfo()
        wifiStepView.visibility = if (step == SetupStep.WIFI) View.VISIBLE else View.GONE
        apiStepView.visibility = if (step == SetupStep.API) View.VISIBLE else View.GONE
        setDropdownExpanded(connectionSection, step == SetupStep.WIFI)
        setDropdownExpanded(apiSection, step == SetupStep.API)
        setDropdownExpanded(deviceSection, step == SetupStep.API)
        webView.visibility = View.GONE
        setupView.visibility = View.VISIBLE
        setupContainer.visibility = View.VISIBLE
    }

    private fun refreshSetupInfo() {
        val state = KioskPolicyController.describeState(this)
        val networkState = if (isWifiConnected()) {
            getString(R.string.wifi_connected)
        } else if (isNetworkAvailable()) {
            getString(R.string.network_without_wifi)
        } else {
            getString(R.string.network_unavailable)
        }

        connectionSection.body.text = getString(
            R.string.connection_status_details,
            networkState,
            currentWifiSsid().ifBlank { getString(R.string.network_unavailable) }
        )
        apiSection.body.text = getString(
            R.string.api_status_details,
            apiConfigStatus.ifBlank { getString(R.string.api_config_not_loaded) },
            apiConfigUrlWithDevice(),
            publicKeyUrl(),
            kioskUrl,
            kioskHost
        )
        deviceSection.body.text = getString(
            R.string.device_setup_info,
            deviceUuid,
            androidId,
            state.title,
            "${Build.MANUFACTURER} ${Build.MODEL}",
            Build.VERSION.RELEASE,
            packageName
        )
    }

    private fun connectWifiFromForm() {
        val selectedSsid = (wifiNetworkSpinner.selectedItem as? WifiNetworkOption)?.ssid.orEmpty()
        val ssid = selectedSsid
        val password = wifiPasswordInput.text.toString()

        if (ssid.isBlank()) {
            showToast(getString(R.string.wifi_ssid_required))
            return
        }

        saveWifiSsid(ssid)
        pendingWifiSsid = ssid
        wifiConnectDeadlineMillis = System.currentTimeMillis() + WIFI_CONNECT_TIMEOUT_MS
        wifiProgressBar.visibility = View.VISIBLE
        wifiStatusText.text = getString(R.string.wifi_connection_loading, ssid, currentWifiSsid())
        mainHandler.removeCallbacks(wifiConnectionWatcher)

        val result = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            suggestWifiNetwork(ssid, password)
        } else {
            connectLegacyWifi(ssid, password)
        }

        wifiStatusText.text = result.message
        showToast(result.message)

        if (result.success) {
            showWifiSetupScreen(getString(R.string.kiosk_wifi_waiting_details))
            mainHandler.postDelayed(wifiConnectionWatcher, WIFI_CONNECT_POLL_MS)
        } else {
            wifiProgressBar.visibility = View.GONE
            showWifiSetupScreen(getString(R.string.kiosk_wifi_failed_details))
        }
    }

    private fun registerWifiScanReceiver() {
        val filter = IntentFilter(WifiManager.SCAN_RESULTS_AVAILABLE_ACTION)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            registerReceiver(wifiScanReceiver, filter, Context.RECEIVER_NOT_EXPORTED)
        } else {
            registerReceiver(wifiScanReceiver, filter)
        }
    }

    @SuppressLint("MissingPermission")
    private fun startWifiScan() {
        val wifiManager = applicationContext.getSystemService(WifiManager::class.java)

        if (wifiManager == null) {
            wifiStatusText.text = getString(R.string.wifi_manager_unavailable)
            return
        }

        if (!hasWifiScanPermission()) {
            wifiStatusText.text = getString(R.string.wifi_scan_permission_missing)
            updateVisibleWifiNetworks()
            return
        }

        wifiProgressBar.visibility = View.VISIBLE
        wifiStatusText.text = getString(R.string.wifi_scan_loading)

        val started = wifiManager.startScan()
        if (!started) {
            updateVisibleWifiNetworks()
        }
    }

    @SuppressLint("MissingPermission")
    private fun updateVisibleWifiNetworks() {
        val wifiManager = applicationContext.getSystemService(WifiManager::class.java)

        if (wifiManager == null || !hasWifiScanPermission()) {
            wifiProgressBar.visibility = View.GONE
            visibleWifiNetworks.replaceWithSingle(
                WifiNetworkOption("", getString(R.string.wifi_no_networks_found))
            )
            wifiAdapter.notifyDataSetChanged()
            return
        }

        val currentSelection = (wifiNetworkSpinner.selectedItem as? WifiNetworkOption)?.ssid.orEmpty()
        val networks = wifiManager.scanResults
            .filter { it.SSID.trim().isNotBlank() }
            .groupBy { it.SSID.trim() }
            .mapNotNull { (ssid, results) ->
                results.maxByOrNull { it.level }?.toWifiNetworkOption(ssid)
            }
            .sortedByDescending { it.level }

        visibleWifiNetworks.clear()
        if (networks.isEmpty()) {
            visibleWifiNetworks.add(WifiNetworkOption("", getString(R.string.wifi_no_networks_found)))
            wifiStatusText.text = getString(R.string.wifi_scan_empty)
        } else {
            visibleWifiNetworks.add(WifiNetworkOption("", getString(R.string.wifi_select_network_placeholder)))
            visibleWifiNetworks.addAll(networks)
            wifiStatusText.text = getString(R.string.wifi_scan_done, networks.size)
        }

        wifiProgressBar.visibility = View.GONE
        wifiAdapter.notifyDataSetChanged()

        val savedSsid = loadSavedWifiSsid()
        val selection = when {
            visibleWifiNetworks.any { it.ssid == currentSelection } -> currentSelection
            visibleWifiNetworks.any { it.ssid == savedSsid } -> savedSsid
            else -> visibleWifiNetworks.firstOrNull()?.ssid
        }
        val selectionIndex = visibleWifiNetworks.indexOfFirst { it.ssid == selection }
        if (selectionIndex >= 0) {
            wifiNetworkSpinner.setSelection(selectionIndex)
        }
    }

    private fun MutableList<WifiNetworkOption>.replaceWithSingle(value: WifiNetworkOption) {
        clear()
        add(value)
    }

    private fun toggleWifiPasswordVisibility() {
        isWifiPasswordVisible = !isWifiPasswordVisible
        wifiPasswordInput.inputType = if (isWifiPasswordVisible) {
            InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_VISIBLE_PASSWORD
        } else {
            InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD
        }
        wifiPasswordToggleButton.text = getString(
            if (isWifiPasswordVisible) {
                R.string.hide_password
            } else {
                R.string.show_password
            }
        )
        wifiPasswordInput.setSelection(wifiPasswordInput.text.length)
    }

    @SuppressLint("MissingPermission")
    private fun disconnectWifi() {
        val wifiManager = applicationContext.getSystemService(WifiManager::class.java)

        if (wifiManager == null) {
            wifiStatusText.text = getString(R.string.wifi_manager_unavailable)
            return
        }

        mainHandler.removeCallbacks(wifiConnectionWatcher)
        pendingWifiSsid = ""
        wifiProgressBar.visibility = View.GONE

        val currentSsid = currentWifiSsid()
        val selectedSsid = (wifiNetworkSpinner.selectedItem as? WifiNetworkOption)?.ssid.orEmpty()
        val targetSsid = currentSsid.ifBlank { selectedSsid }
        val removedSuggestion = removeWifiSuggestion(wifiManager, targetSsid)

        @Suppress("DEPRECATION")
        val disconnected = wifiManager.disconnect()

        wifiStatusText.text = when {
            disconnected -> getString(R.string.wifi_disconnect_success, targetSsid.ifBlank {
                getString(R.string.wifi_unknown_network)
            })
            removedSuggestion -> getString(R.string.wifi_disconnect_suggestion_removed, targetSsid)
            else -> getString(R.string.wifi_disconnect_blocked)
        }
        refreshSetupInfo()
    }

    private fun fetchApiConfig(onComplete: (Boolean) -> Unit) {
        if (isFetchingApiConfig) {
            onComplete(false)
            return
        }

        isFetchingApiConfig = true
        apiConfigStatus = getString(R.string.api_config_loading)
        refreshSetupInfo()

        Thread {
            var success = false
            val status = runCatching {
                val connection = URL(apiConfigUrlWithDevice()).openConnection() as HttpURLConnection
                connection.connectTimeout = 5000
                connection.readTimeout = 5000
                connection.requestMethod = "GET"
                connection.setRequestProperty("Accept", "application/jwt")
                connection.setRequestProperty("X-Device-UUID", deviceUuid)
                connection.setRequestProperty("X-Android-ID", androidId)

                val responseCode = connection.responseCode
                if (responseCode !in 200..299) {
                    val body = connection.readResponseBody(preferErrorStream = true)
                        .ifBlank { getString(R.string.http_empty_body) }
                    connection.disconnect()
                    return@runCatching getString(
                        R.string.api_config_http_failed,
                        responseCode,
                        apiConfigUrlWithDevice(),
                        body.take(API_ERROR_PREVIEW_LIMIT)
                    )
                }

                val apiServerTime = connection.headerFieldDateEpochSeconds()
                val body = connection.readResponseBody(preferErrorStream = false)
                connection.disconnect()

                applyApiToken(body.trim(), apiServerTime)
                success = true
                getString(R.string.api_config_loaded)
            }.getOrElse {
                getString(
                    R.string.api_config_exception,
                    apiConfigUrlWithDevice(),
                    it.message ?: it.javaClass.simpleName
                )
            }

            runOnUiThread {
                apiConfigStatus = status
                isFetchingApiConfig = false
                refreshSetupInfo()
                onComplete(success)
            }
        }.start()
    }

    private fun applyApiToken(token: String, apiServerTimeEpochSeconds: Long) {
        val parts = token.split(".")
        if (parts.size != 3) {
            throw IllegalArgumentException(getString(R.string.api_config_invalid_jwt))
        }

        if (!isJwtSignatureValid(parts)) {
            throw IllegalArgumentException(getString(R.string.api_config_invalid_signature))
        }

        val claims = JSONObject(base64UrlDecodeToString(parts[1]))
        validateJwtClaims(claims, apiServerTimeEpochSeconds)

        val nextUrl = claims.optString("url").normalizeUrl()

        if (nextUrl.isBlank()) {
            throw IllegalArgumentException(getString(R.string.api_config_missing_url))
        }

        kioskUrl = nextUrl
        kioskHost = Uri.parse(nextUrl).host ?: DEFAULT_KIOSK_HOST
    }

    private fun validateJwtClaims(claims: JSONObject, apiServerTimeEpochSeconds: Long) {
        val deviceNow = System.currentTimeMillis() / 1000
        val now = if (apiServerTimeEpochSeconds > 0) {
            apiServerTimeEpochSeconds
        } else {
            deviceNow
        }
        val issuer = claims.optString("iss")
        val audience = claims.optString("aud")
        val subject = claims.optString("sub")
        val notBefore = claims.optLong("nbf", 0)
        val expiresAt = claims.optLong("exp", 0)
        val url = claims.optString("url")

        require(issuer == BuildConfig.SERVER_JWT_ISSUER) {
            getString(R.string.api_config_invalid_issuer, issuer)
        }
        require(audience == BuildConfig.SERVER_JWT_AUDIENCE) {
            getString(R.string.api_config_invalid_audience, audience)
        }
        require(subject == deviceUuid) {
            getString(R.string.api_config_invalid_subject, subject)
        }
        require(now + JWT_CLOCK_SKEW_SECONDS >= notBefore &&
            expiresAt + JWT_CLOCK_SKEW_SECONDS > now) {
            getString(
                R.string.api_config_expired_token,
                now,
                notBefore,
                expiresAt
            )
        }
        require(isSecureUrl(url)) {
            getString(R.string.api_config_insecure_url)
        }
    }

    private fun isJwtSignatureValid(parts: List<String>): Boolean {
        val preferredPublicKey = fetchServerPublicKeyBase64().ifBlank {
            resolveServerPublicKeyBase64()
        }

        if (preferredPublicKey.isBlank()) {
            throw IllegalArgumentException(
                getString(
                    R.string.api_config_missing_public_key,
                    publicKeyFetchError.ifBlank { getString(R.string.api_config_public_key_no_detail) }
                )
            )
        }

        return verifyJwtSignature(parts, preferredPublicKey)
    }

    private fun verifyJwtSignature(parts: List<String>, publicKeyBase64: String): Boolean {
        val publicKeyBytes = runCatching {
            Base64.getDecoder().decode(publicKeyBase64)
        }.getOrElse {
            throw IllegalArgumentException(
                getString(
                    R.string.api_config_public_key_invalid,
                    publicKeyBase64.previewForError()
                )
            )
        }

        val publicKey = runCatching {
            KeyFactory.getInstance("RSA").generatePublic(X509EncodedKeySpec(publicKeyBytes))
        }.getOrElse {
            throw IllegalArgumentException(
                getString(
                    R.string.api_config_public_key_invalid,
                    it.message ?: it.javaClass.simpleName
                )
            )
        }
        val signature = Signature.getInstance("SHA256withRSA")

        signature.initVerify(publicKey)
        signature.update("${parts[0]}.${parts[1]}".toByteArray(StandardCharsets.UTF_8))

        return signature.verify(base64UrlDecode(parts[2]))
    }

    private fun fetchServerPublicKeyBase64(): String {
        val cached = cachedServerPublicKeyBase64.normalizePublicKeyBase64()
        if (cached.isNotBlank()) {
            return cached
        }

        publicKeyFetchError = ""

        val fetched = runCatching {
            val connection = URL(publicKeyUrl()).openConnection() as HttpURLConnection
            connection.connectTimeout = 5000
            connection.readTimeout = 5000
            connection.requestMethod = "GET"
            connection.setRequestProperty("Accept", "application/json")

            val responseCode = connection.responseCode
            if (responseCode !in 200..299) {
                val body = connection.readResponseBody(preferErrorStream = true)
                    .ifBlank { getString(R.string.http_empty_body) }
                connection.disconnect()
                throw IllegalArgumentException(
                    getString(
                        R.string.api_config_public_key_http_failed,
                        responseCode,
                        body.take(API_ERROR_PREVIEW_LIMIT)
                    )
                )
            }

            val body = connection.readResponseBody(preferErrorStream = false)
            connection.disconnect()

            val payload = JSONObject(body)
            val issuer = payload.optString("issuer")
            val audience = payload.optString("audience")

            require(issuer == BuildConfig.SERVER_JWT_ISSUER) {
                getString(R.string.api_config_invalid_issuer, issuer)
            }
            require(audience == BuildConfig.SERVER_JWT_AUDIENCE) {
                getString(R.string.api_config_invalid_audience, audience)
            }

            payload.optString("public_key_base64").normalizePublicKeyBase64()
        }.getOrElse {
            publicKeyFetchError = it.message ?: it.javaClass.simpleName
            ""
        }

        cachedServerPublicKeyBase64 = fetched
        return fetched
    }

    private fun resolveServerPublicKeyBase64(): String {
        val cached = cachedServerPublicKeyBase64.normalizePublicKeyBase64()
        if (cached.isNotBlank()) {
            return cached
        }

        return BuildConfig.SERVER_JWT_PUBLIC_KEY_BASE64.normalizePublicKeyBase64()
    }

    private fun publicKeyUrl(): String {
        return Uri.parse(BuildConfig.CONFIGURATION_API_BASE_URL)
            .buildUpon()
            .appendPath("api")
            .appendPath("v1")
            .appendPath("device-configuration")
            .appendPath("public-key")
            .build()
            .toString()
    }

    private fun HttpURLConnection.readResponseBody(preferErrorStream: Boolean): String {
        val stream = if (preferErrorStream) {
            errorStream ?: runCatching { inputStream }.getOrNull()
        } else {
            runCatching { inputStream }.getOrNull()
        }

        return stream?.bufferedReader()?.use { it.readText() }.orEmpty()
    }

    private fun HttpURLConnection.headerFieldDateEpochSeconds(): Long {
        val headerTimeMillis = getHeaderFieldDate("Date", 0L)
        val epochSeconds = if (headerTimeMillis > 0) {
            headerTimeMillis / 1000
        } else {
            0L
        }
        lastApiServerTimeEpochSeconds = epochSeconds

        return epochSeconds
    }

    private fun base64UrlDecodeToString(value: String): String {
        return String(base64UrlDecode(value), StandardCharsets.UTF_8)
    }

    private fun base64UrlDecode(value: String): ByteArray {
        return Base64.getUrlDecoder().decode(value.withBase64Padding())
    }

    private fun String.withBase64Padding(): String {
        val padding = (4 - length % 4) % 4
        return this + "=".repeat(padding)
    }

    private fun String.normalizePublicKeyBase64(): String {
        return trim()
            .removePrefix("base64:")
            .replace("-----BEGIN PUBLIC KEY-----", "")
            .replace("-----END PUBLIC KEY-----", "")
            .replace("\\s".toRegex(), "")
            .trim('"')
            .trim('\'')
    }

    private fun String.previewForError(): String {
        val normalized = normalizePublicKeyBase64()
        return if (normalized.length <= 16) normalized else normalized.take(16)
    }

    private fun apiConfigUrlWithDevice(): String {
        return Uri.parse(BuildConfig.CONFIGURATION_API_BASE_URL)
            .buildUpon()
            .appendPath("api")
            .appendPath("v1")
            .appendPath("devices")
            .appendPath(deviceUuid)
            .appendPath("configuration")
            .appendQueryParameter("android_id", androidId)
            .appendQueryParameter("model", "${Build.MANUFACTURER} ${Build.MODEL}")
            .build()
            .toString()
    }

    private fun isNetworkAvailable(): Boolean {
        val connectivityManager = getSystemService(ConnectivityManager::class.java)
            ?: return false
        val network = connectivityManager.activeNetwork ?: return false
        val capabilities = connectivityManager.getNetworkCapabilities(network) ?: return false

        return capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
    }

    private fun isWifiConnected(): Boolean {
        val connectivityManager = getSystemService(ConnectivityManager::class.java)
            ?: return false
        val network = connectivityManager.activeNetwork ?: return false
        val capabilities = connectivityManager.getNetworkCapabilities(network) ?: return false

        return capabilities.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) &&
            capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
    }

    private fun isWifiConnectedTo(ssid: String): Boolean {
        return isWifiConnected() && currentWifiSsid() == ssid
    }

    @SuppressLint("MissingPermission")
    private fun currentWifiSsid(): String {
        val wifiManager = applicationContext.getSystemService(WifiManager::class.java)
            ?: return ""
        val ssid = wifiManager.connectionInfo?.ssid.orEmpty()
            .removeSurrounding("\"")

        return if (ssid == UNKNOWN_WIFI_SSID) "" else ssid
    }

    private fun hasWifiScanPermission(): Boolean {
        return Build.VERSION.SDK_INT < Build.VERSION_CODES.M ||
            checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) ==
            PackageManager.PERMISSION_GRANTED
    }

    private fun loadSavedWifiSsid(): String {
        return getPreferences(Context.MODE_PRIVATE).getString(WIFI_SSID_KEY, "").orEmpty()
    }

    private fun saveWifiSsid(ssid: String) {
        getPreferences(Context.MODE_PRIVATE)
            .edit()
            .putString(WIFI_SSID_KEY, ssid)
            .apply()
    }

    private fun resolveAndroidId(): String {
        return Settings.Secure.getString(contentResolver, Settings.Secure.ANDROID_ID)
            ?.trim()
            .orEmpty()
            .ifBlank { "unknown-${Build.MANUFACTURER}-${Build.MODEL}" }
    }

    private fun resolveDeviceUuid(): String {
        return UUID.nameUUIDFromBytes(
            "$packageName:$androidId".toByteArray(StandardCharsets.UTF_8)
        ).toString()
    }

    @SuppressLint("MissingPermission")
    private fun connectLegacyWifi(ssid: String, password: String): WifiConnectResult {
        val wifiManager = applicationContext.getSystemService(WifiManager::class.java)
            ?: return WifiConnectResult(false, getString(R.string.wifi_manager_unavailable))

        if (!wifiManager.isWifiEnabled) {
            wifiManager.isWifiEnabled = true
        }

        @Suppress("DEPRECATION")
        val configuration = WifiConfiguration().apply {
            SSID = ssid.quoteForWifi()

            if (password.isBlank()) {
                allowedKeyManagement.set(WifiConfiguration.KeyMgmt.NONE)
            } else {
                preSharedKey = password.quoteForWifi()
            }
        }

        @Suppress("DEPRECATION")
        val networkId = wifiManager.addNetwork(configuration)
        if (networkId == -1) {
            return WifiConnectResult(false, getString(R.string.wifi_connection_failed_add_network))
        }

        @Suppress("DEPRECATION")
        val requested = wifiManager.disconnect() &&
            wifiManager.enableNetwork(networkId, true) &&
            wifiManager.reconnect()

        return WifiConnectResult(
            requested,
            if (requested) {
                getString(R.string.wifi_connection_requested_named, ssid)
            } else {
                getString(R.string.wifi_connection_failed_reconnect)
            }
        )
    }

    private fun suggestWifiNetwork(ssid: String, password: String): WifiConnectResult {
        val wifiManager = applicationContext.getSystemService(WifiManager::class.java)
            ?: return WifiConnectResult(false, getString(R.string.wifi_manager_unavailable))

        val suggestion = WifiNetworkSuggestion.Builder()
            .setSsid(ssid)
            .apply {
                if (password.isNotBlank()) {
                    setWpa2Passphrase(password)
                }
                setIsAppInteractionRequired(false)
            }
            .build()

        val status = wifiManager.addNetworkSuggestions(listOf(suggestion))
        val success = status == WifiManager.STATUS_NETWORK_SUGGESTIONS_SUCCESS

        return WifiConnectResult(
            success,
            if (success) {
                getString(R.string.wifi_connection_requested_named, ssid)
            } else {
                getString(R.string.wifi_connection_failed_android, wifiSuggestionStatusName(status))
            }
        )
    }

    private fun removeWifiSuggestion(wifiManager: WifiManager, ssid: String): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q || ssid.isBlank()) {
            return false
        }

        val suggestion = WifiNetworkSuggestion.Builder()
            .setSsid(ssid)
            .build()
        val status = wifiManager.removeNetworkSuggestions(listOf(suggestion))

        return status == WifiManager.STATUS_NETWORK_SUGGESTIONS_SUCCESS
    }

    private fun ScanResult.toWifiNetworkOption(ssid: String): WifiNetworkOption {
        val signal = wifiSignalLabel(level)
        val security = if (capabilities.contains("WEP") ||
            capabilities.contains("WPA") ||
            capabilities.contains("SAE")) {
            getString(R.string.wifi_security_protected)
        } else {
            getString(R.string.wifi_security_open)
        }

        return WifiNetworkOption(
            ssid = ssid,
            label = getString(R.string.wifi_network_option, ssid, signal, security),
            level = level
        )
    }

    private fun wifiSignalLabel(level: Int): String {
        return when {
            level >= -55 -> getString(R.string.wifi_signal_strong)
            level >= -70 -> getString(R.string.wifi_signal_good)
            else -> getString(R.string.wifi_signal_weak)
        }
    }

    private fun wifiSuggestionStatusName(status: Int): String {
        return when (status) {
            WifiManager.STATUS_NETWORK_SUGGESTIONS_ERROR_ADD_DUPLICATE ->
                getString(R.string.wifi_suggestion_duplicate)
            WifiManager.STATUS_NETWORK_SUGGESTIONS_ERROR_ADD_EXCEEDS_MAX_PER_APP ->
                getString(R.string.wifi_suggestion_limit)
            WifiManager.STATUS_NETWORK_SUGGESTIONS_ERROR_APP_DISALLOWED ->
                getString(R.string.wifi_suggestion_disallowed)
            WifiManager.STATUS_NETWORK_SUGGESTIONS_ERROR_INTERNAL ->
                getString(R.string.wifi_suggestion_internal)
            else -> getString(R.string.wifi_suggestion_unknown, status)
        }
    }

    private fun String.quoteForWifi(): String {
        return "\"$this\""
    }

    private fun showToast(message: String) {
        Toast.makeText(this, message, Toast.LENGTH_LONG).show()
    }

    private fun isAllowedUrl(uri: Uri): Boolean {
        return uri.host == kioskHost && (uri.scheme == "http" || uri.scheme == "https")
    }

    private fun isSecureUrl(value: String): Boolean {
        val uri = Uri.parse(value)
        return uri.scheme == "https" && !uri.host.isNullOrBlank() && uri.userInfo.isNullOrBlank()
    }

    private fun String.normalizeUrl(): String {
        val normalized = trim()
        if (normalized.isBlank()) {
            return ""
        }

        return if (normalized.endsWith("/")) normalized else "$normalized/"
    }

    private fun hideSystemUi() {
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.R) {
            window.decorView.windowInsetsController?.hide(
                WindowInsets.Type.statusBars() or WindowInsets.Type.navigationBars()
            ) ?: hideLegacySystemUi()
        } else {
            hideLegacySystemUi()
        }
    }

    private fun hideLegacySystemUi() {
        @Suppress("DEPRECATION")
        window.decorView.systemUiVisibility = (
            View.SYSTEM_UI_FLAG_FULLSCREEN
                or View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
                or View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY
                or View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN
                or View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION
                or View.SYSTEM_UI_FLAG_LAYOUT_STABLE
            )
    }

    private enum class SetupStep {
        WIFI,
        API
    }

    private data class InfoDropdown(
        val container: LinearLayout,
        val header: TextView,
        val body: TextView,
        val title: String,
        var expanded: Boolean
    )

    private data class WifiNetworkOption(
        val ssid: String,
        val label: String,
        val level: Int = Int.MIN_VALUE
    ) {
        override fun toString(): String = label
    }

    private data class WifiConnectResult(
        val success: Boolean,
        val message: String
    )

    companion object {
        private const val DEFAULT_KIOSK_HOST = "rechi.net.br"
        private const val DEFAULT_KIOSK_URL = "https://rechi.net.br/"
        private const val WIFI_SSID_KEY = "wifi_ssid"
        private const val UNKNOWN_WIFI_SSID = "<unknown ssid>"
        private const val WIFI_CONNECT_POLL_MS = 1500L
        private const val WIFI_CONNECT_TIMEOUT_MS = 30000L
        private const val API_ERROR_PREVIEW_LIMIT = 240
        private const val JWT_CLOCK_SKEW_SECONDS = 120L
    }
}
