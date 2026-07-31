package br.com.rechi.mobile.wifi

import android.app.Activity
import android.content.Intent
import android.os.Build
import android.provider.Settings

object WifiConnectionLauncher {
    fun open(activity: Activity): Boolean {
        val intent = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            Intent(Settings.Panel.ACTION_WIFI)
        } else {
            Intent(Settings.ACTION_WIFI_SETTINGS)
        }

        return runCatching {
            activity.startActivity(intent)
            true
        }.getOrDefault(false)
    }
}
