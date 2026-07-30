package br.com.rechi.mobile

import android.annotation.SuppressLint
import android.app.Activity
import android.graphics.Color
import android.net.Uri
import android.os.Bundle
import android.view.Gravity
import android.view.View
import android.view.WindowInsets
import android.view.WindowManager
import android.webkit.WebResourceError
import android.webkit.WebResourceRequest
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.FrameLayout
import android.widget.TextView
import br.com.rechi.mobile.kiosk.KioskPolicyController

class MainActivity : Activity() {
    private lateinit var webView: WebView
    private lateinit var offlineMessage: TextView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        setContentView(buildContent())
        hideSystemUi()
        configureWebView()
    }

    override fun onResume() {
        super.onResume()
        hideSystemUi()
        KioskPolicyController.applyKioskPolicies(this)
        KioskPolicyController.startKiosk(this)

        if (webView.url.isNullOrBlank()) {
            webView.loadUrl(KIOSK_URL)
        }
    }

    @Deprecated("Back button is intentionally captured in kiosk mode.")
    override fun onBackPressed() {
        webView.loadUrl(KIOSK_URL)
    }

    private fun buildContent(): View {
        return FrameLayout(this).apply {
            setBackgroundColor(Color.WHITE)

            webView = WebView(this@MainActivity).apply {
                id = View.generateViewId()
                isLongClickable = false
                setOnLongClickListener { true }
            }

            offlineMessage = TextView(this@MainActivity).apply {
                text = getString(R.string.kiosk_loading)
                setTextColor(0xFF101820.toInt())
                textSize = 18f
                gravity = Gravity.CENTER
                setPadding(48, 48, 48, 48)
                visibility = View.GONE
            }

            addView(
                webView,
                FrameLayout.LayoutParams(
                    FrameLayout.LayoutParams.MATCH_PARENT,
                    FrameLayout.LayoutParams.MATCH_PARENT
                )
            )
            addView(
                offlineMessage,
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
                    view.loadUrl(KIOSK_URL)
                    true
                }
            }

            override fun onPageFinished(view: WebView, url: String) {
                offlineMessage.visibility = View.GONE
                webView.visibility = View.VISIBLE
            }

            override fun onReceivedError(
                view: WebView,
                request: WebResourceRequest,
                error: WebResourceError
            ) {
                if (request.isForMainFrame) {
                    webView.visibility = View.GONE
                    offlineMessage.text = getString(R.string.kiosk_connection_error)
                    offlineMessage.visibility = View.VISIBLE
                }
            }
        }

        webView.loadUrl(KIOSK_URL)
    }

    private fun isAllowedUrl(uri: Uri): Boolean {
        return uri.scheme == "http" && uri.host == KIOSK_HOST
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

    companion object {
        private const val KIOSK_HOST = "hubibiporahomolog.grupoibipora.local"
        private const val KIOSK_URL = "http://hubibiporahomolog.grupoibipora.local/"
    }
}
