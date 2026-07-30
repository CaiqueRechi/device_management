package br.com.rechi.mobile

import android.app.Activity
import android.os.Bundle
import android.view.Gravity
import android.view.View
import android.view.WindowInsets
import android.view.WindowManager
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView
import br.com.rechi.mobile.kiosk.KioskPolicyController

class MainActivity : Activity() {
    private lateinit var statusText: TextView
    private lateinit var detailText: TextView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        hideSystemUi()
        setContentView(buildContent())
    }

    override fun onResume() {
        super.onResume()
        hideSystemUi()
        applyPoliciesAndStartKiosk()
    }

    private fun buildContent(): View {
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            setPadding(48, 48, 48, 48)
            setBackgroundColor(0xFF101820.toInt())
        }

        val title = TextView(this).apply {
            text = getString(R.string.kiosk_title)
            setTextColor(0xFFFFFFFF.toInt())
            textSize = 28f
            gravity = Gravity.CENTER
        }

        statusText = TextView(this).apply {
            setTextColor(0xFFE7ECF2.toInt())
            textSize = 18f
            gravity = Gravity.CENTER
            setPadding(0, 28, 0, 8)
        }

        detailText = TextView(this).apply {
            setTextColor(0xFF9FB0C3.toInt())
            textSize = 14f
            gravity = Gravity.CENTER
            setPadding(0, 0, 0, 32)
        }

        val startButton = Button(this).apply {
            text = getString(R.string.start_kiosk)
            setOnClickListener { applyPoliciesAndStartKiosk() }
        }

        val stopButton = Button(this).apply {
            text = getString(R.string.stop_kiosk)
            setOnClickListener {
                KioskPolicyController.stopKiosk(this@MainActivity)
                updateStatus()
            }
        }

        root.addView(title, fullWidthWrapContent())
        root.addView(statusText, fullWidthWrapContent())
        root.addView(detailText, fullWidthWrapContent())
        root.addView(startButton, fullWidthWrapContent())
        root.addView(stopButton, fullWidthWrapContent())

        return root
    }

    private fun applyPoliciesAndStartKiosk() {
        KioskPolicyController.applyKioskPolicies(this)
        KioskPolicyController.startKiosk(this)
        updateStatus()
    }

    private fun updateStatus() {
        val state = KioskPolicyController.describeState(this)
        statusText.text = state.title
        detailText.text = state.details
    }

    private fun hideSystemUi() {
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.R) {
            window.insetsController?.hide(
                WindowInsets.Type.statusBars() or WindowInsets.Type.navigationBars()
            )
        } else {
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
    }

    private fun fullWidthWrapContent(): LinearLayout.LayoutParams {
        return LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT,
            LinearLayout.LayoutParams.WRAP_CONTENT
        ).apply {
            setMargins(0, 8, 0, 8)
        }
    }
}
