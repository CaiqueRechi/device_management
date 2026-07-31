package br.com.rechi.mobile

import android.annotation.SuppressLint
import android.app.Activity
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.net.Uri
import android.os.Bundle
import android.view.Gravity
import android.view.View
import android.view.WindowInsets
import android.view.WindowManager
import android.webkit.WebResourceError
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import android.webkit.CookieManager
import android.widget.Button
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.TextView
import br.com.rechi.mobile.configuration.ConfigurationRepository
import br.com.rechi.mobile.configuration.ConfigurationResult
import br.com.rechi.mobile.configuration.ConfigurationStorage
import br.com.rechi.mobile.connectivity.ConnectivityMonitor
import br.com.rechi.mobile.kiosk.KioskPolicyController
import br.com.rechi.mobile.wifi.WifiConnectionLauncher
import java.io.ByteArrayInputStream

class MainActivity : Activity() {
    private lateinit var root: FrameLayout
    private lateinit var webView: WebView
    private lateinit var offlineView: View
    private lateinit var statusIndicator: View
    private lateinit var statusText: TextView
    private lateinit var progressBar: ProgressBar
    private lateinit var retryButton: Button
    private lateinit var repository: ConfigurationRepository
    private lateinit var connectivityMonitor: ConnectivityMonitor

    private var activeUrl: String? = null
    private var hasInternet = false
    private var configurationLoading = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(
            WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON or
                WindowManager.LayoutParams.FLAG_SECURE
        )

        repository = ConfigurationRepository(ConfigurationStorage(this))
        activeUrl = repository.cachedUrl()
        connectivityMonitor = ConnectivityMonitor(this, ::onConnectivityChanged)

        root = FrameLayout(this)
        webView = WebView(this)
        offlineView = buildOfflineView()
        root.addView(webView, matchParentParams())
        root.addView(offlineView, matchParentParams())
        setContentView(root)

        configureWebView()
        hideSystemUi()
    }

    override fun onStart() {
        super.onStart()
        connectivityMonitor.start()
    }

    override fun onStop() {
        connectivityMonitor.stop()
        super.onStop()
    }

    override fun onDestroy() {
        repository.close()
        webView.destroy()
        super.onDestroy()
    }

    override fun onResume() {
        super.onResume()
        hideSystemUi()
        KioskPolicyController.applyKioskPolicies(this)
        KioskPolicyController.startKiosk(this)
        if (::connectivityMonitor.isInitialized) {
            onConnectivityChanged(connectivityMonitor.hasValidatedInternet())
        }
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (hasFocus) hideSystemUi()
    }

    @Deprecated("Back button is intentionally captured in kiosk mode.")
    override fun onBackPressed() {
        activeUrl?.let(webView::loadUrl)
    }

    private fun onConnectivityChanged(connected: Boolean) {
        val recovered = !hasInternet && connected
        hasInternet = connected

        if (!connected) {
            showOfflineState(R.string.connection_offline, loading = false)
            return
        }

        showOfflineState(R.string.connection_online, loading = true)
        val cachedUrl = activeUrl
        if (cachedUrl != null) {
            loadActiveUrl(cachedUrl)
            if (recovered) refreshConfiguration(showLoading = false)
        } else {
            refreshConfiguration(showLoading = true)
        }
    }

    private fun refreshConfiguration(showLoading: Boolean) {
        if (!hasInternet || configurationLoading) return
        configurationLoading = true
        if (showLoading) showOfflineState(R.string.configuration_loading, loading = true)

        repository.refresh { result ->
            configurationLoading = false
            when (result) {
                is ConfigurationResult.Success -> {
                    activeUrl = result.configuration.url
                    loadActiveUrl(result.configuration.url)
                }
                is ConfigurationResult.Failure -> {
                    if (activeUrl == null) {
                        val message = if (result.reason == "Invalid web URL") {
                            R.string.configuration_invalid
                        } else {
                            R.string.configuration_error
                        }
                        showOfflineState(message, loading = false)
                    }
                }
            }
        }
    }

    private fun loadActiveUrl(url: String) {
        if (!hasInternet) return
        offlineView.visibility = View.GONE
        webView.visibility = View.VISIBLE
        if (webView.url != url) webView.loadUrl(url)
    }

    private fun showOfflineState(message: Int, loading: Boolean) {
        webView.visibility = View.GONE
        offlineView.visibility = View.VISIBLE
        statusText.setText(message)
        progressBar.visibility = if (loading) View.VISIBLE else View.GONE
        statusIndicator.background = circleDrawable(
            if (hasInternet) COLOR_SUCCESS else COLOR_ERROR
        )
        retryButton.isEnabled = !loading
    }

    private fun openWifiSelector() {
        showOfflineState(R.string.connection_checking, loading = true)
        KioskPolicyController.allowWifiSettingsTemporarily(this)
        val launched = WifiConnectionLauncher.open(this)
        if (!launched) {
            KioskPolicyController.applyKioskPolicies(this)
            showOfflineState(R.string.wifi_open_error, loading = false)
        }
    }

    private fun retry() {
        val connected = connectivityMonitor.hasValidatedInternet()
        if (connected) {
            hasInternet = true
            activeUrl?.let(::loadActiveUrl) ?: refreshConfiguration(showLoading = true)
        } else {
            hasInternet = false
            showOfflineState(R.string.connection_offline, loading = false)
        }
    }

    @SuppressLint("SetJavaScriptEnabled")
    private fun configureWebView() {
        WebView.setWebContentsDebuggingEnabled(false)
        webView.settings.apply {
            javaScriptEnabled = true
            domStorageEnabled = true
            cacheMode = WebSettings.LOAD_NO_CACHE
            mediaPlaybackRequiresUserGesture = false
            setSupportMultipleWindows(false)
            allowFileAccess = false
            allowContentAccess = false
            mixedContentMode = WebSettings.MIXED_CONTENT_NEVER_ALLOW
            javaScriptCanOpenWindowsAutomatically = false
            builtInZoomControls = false
            displayZoomControls = false
            if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.O) {
                safeBrowsingEnabled = true
            }
        }
        CookieManager.getInstance().setAcceptThirdPartyCookies(webView, false)
        webView.isLongClickable = false
        webView.setOnLongClickListener { true }

        webView.webViewClient = object : WebViewClient() {
            override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean {
                return if (isAllowedUrl(request.url)) {
                    false
                } else {
                    activeUrl?.let(view::loadUrl)
                    true
                }
            }

            override fun shouldInterceptRequest(
                view: WebView,
                request: WebResourceRequest
            ): WebResourceResponse? {
                return if (isAllowedUrl(request.url)) {
                    null
                } else {
                    WebResourceResponse(
                        "text/plain",
                        "UTF-8",
                        ByteArrayInputStream(ByteArray(0))
                    )
                }
            }

            override fun onReceivedError(
                view: WebView,
                request: WebResourceRequest,
                error: WebResourceError
            ) {
                if (request.isForMainFrame) {
                    hasInternet = connectivityMonitor.hasValidatedInternet()
                    showOfflineState(
                        if (hasInternet) R.string.configuration_error else R.string.connection_offline,
                        loading = false
                    )
                }
            }
        }
    }

    private fun isAllowedUrl(uri: Uri): Boolean {
        val configured = activeUrl?.let(Uri::parse) ?: return false
        return uri.scheme == "https" &&
            uri.host.equals(configured.host, ignoreCase = true) &&
            effectivePort(uri) == effectivePort(configured) &&
            uri.userInfo.isNullOrBlank()
    }

    private fun effectivePort(uri: Uri): Int =
        if (uri.port != -1) uri.port else if (uri.scheme == "https") 443 else 80

    private fun buildOfflineView(): View {
        val content = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
            setPadding(64, 72, 64, 72)
            background = GradientDrawable().apply {
                colors = intArrayOf(0xFFF4F7FA.toInt(), Color.WHITE)
                orientation = GradientDrawable.Orientation.TOP_BOTTOM
            }
        }

        content.addView(TextView(this).apply {
            text = getString(R.string.app_name)
            textSize = 18f
            setTextColor(COLOR_BRAND)
            gravity = Gravity.CENTER
        }, linearParams(top = 0, bottom = 48))

        content.addView(TextView(this).apply {
            text = getString(R.string.offline_title)
            textSize = 28f
            setTextColor(COLOR_TEXT)
            gravity = Gravity.CENTER
        }, linearParams(bottom = 18))

        content.addView(TextView(this).apply {
            text = getString(R.string.offline_message)
            textSize = 17f
            setTextColor(COLOR_MUTED)
            gravity = Gravity.CENTER
        }, linearParams(bottom = 36))

        val statusRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER
            setPadding(28, 20, 28, 20)
            background = roundedDrawable(0xFFE8EDF2.toInt(), 18f)
        }
        statusIndicator = View(this).apply { background = circleDrawable(COLOR_ERROR) }
        statusRow.addView(statusIndicator, LinearLayout.LayoutParams(24, 24).apply {
            marginEnd = 18
        })
        statusText = TextView(this).apply {
            setText(R.string.connection_checking)
            textSize = 16f
            setTextColor(COLOR_TEXT)
        }
        statusRow.addView(statusText)
        progressBar = ProgressBar(this).apply { visibility = View.GONE }
        statusRow.addView(progressBar, LinearLayout.LayoutParams(42, 42).apply {
            marginStart = 18
        })
        content.addView(statusRow, linearParams(bottom = 42))

        content.addView(Button(this).apply {
            setText(R.string.connect_wifi)
            setTextColor(Color.WHITE)
            textSize = 16f
            background = roundedDrawable(COLOR_BRAND, 16f)
            setOnClickListener { openWifiSelector() }
        }, linearParams(height = 112, bottom = 20))

        retryButton = Button(this).apply {
            setText(R.string.try_again)
            setTextColor(COLOR_BRAND)
            textSize = 16f
            background = roundedDrawable(0xFFE4EDF5.toInt(), 16f)
            setOnClickListener { retry() }
        }
        content.addView(retryButton, linearParams(height = 112))

        return FrameLayout(this).apply {
            setBackgroundColor(Color.WHITE)
            addView(content, FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.WRAP_CONTENT,
                Gravity.CENTER
            ))
        }
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
            View.SYSTEM_UI_FLAG_FULLSCREEN or View.SYSTEM_UI_FLAG_HIDE_NAVIGATION or
                View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY or View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN or
                View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION or View.SYSTEM_UI_FLAG_LAYOUT_STABLE
            )
    }

    private fun matchParentParams() = FrameLayout.LayoutParams(
        FrameLayout.LayoutParams.MATCH_PARENT,
        FrameLayout.LayoutParams.MATCH_PARENT
    )

    private fun linearParams(
        height: Int = LinearLayout.LayoutParams.WRAP_CONTENT,
        top: Int = 0,
        bottom: Int = 0
    ) = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, height).apply {
        topMargin = top
        bottomMargin = bottom
    }

    private fun roundedDrawable(color: Int, radius: Float) = GradientDrawable().apply {
        setColor(color)
        cornerRadius = radius
    }

    private fun circleDrawable(color: Int) = GradientDrawable().apply {
        shape = GradientDrawable.OVAL
        setColor(color)
    }

    private companion object {
        const val COLOR_BRAND = 0xFF005A8D.toInt()
        const val COLOR_TEXT = 0xFF15232D.toInt()
        const val COLOR_MUTED = 0xFF52636F.toInt()
        const val COLOR_ERROR = 0xFFD74444.toInt()
        const val COLOR_SUCCESS = 0xFF238B57.toInt()
    }
}
