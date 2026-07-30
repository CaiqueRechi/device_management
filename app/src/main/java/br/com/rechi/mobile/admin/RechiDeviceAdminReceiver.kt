package br.com.rechi.mobile.admin

import android.app.admin.DeviceAdminReceiver
import android.content.Context
import android.content.Intent
import android.widget.Toast
import br.com.rechi.mobile.R

class RechiDeviceAdminReceiver : DeviceAdminReceiver() {
    override fun onEnabled(context: Context, intent: Intent) {
        Toast.makeText(context, R.string.device_admin_enabled, Toast.LENGTH_SHORT).show()
    }

    override fun onDisabled(context: Context, intent: Intent) {
        Toast.makeText(context, R.string.device_admin_disabled, Toast.LENGTH_SHORT).show()
    }
}
