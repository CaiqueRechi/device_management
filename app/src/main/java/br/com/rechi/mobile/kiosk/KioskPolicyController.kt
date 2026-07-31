package br.com.rechi.mobile.kiosk

import android.Manifest
import android.app.Activity
import android.app.ActivityManager
import android.app.admin.DevicePolicyManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Build
import android.os.UserManager
import br.com.rechi.mobile.MainActivity
import br.com.rechi.mobile.R
import br.com.rechi.mobile.admin.RechiDeviceAdminReceiver

object KioskPolicyController {
    data class KioskState(
        val title: String,
        val details: String
    )

    fun applyKioskPolicies(context: Context) {
        val devicePolicyManager = context.devicePolicyManager()
        val admin = context.adminComponent()

        if (!devicePolicyManager.isDeviceOwnerApp(context.packageName)) {
            return
        }

        devicePolicyManager.setLockTaskPackages(admin, arrayOf(context.packageName))
        devicePolicyManager.setUninstallBlocked(admin, context.packageName, true)
        setAsHomeActivity(context, devicePolicyManager, admin)
        applyUserRestrictions(devicePolicyManager, admin)
        grantRuntimePermissions(context, devicePolicyManager, admin)

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            devicePolicyManager.setKeyguardDisabled(admin, true)
            devicePolicyManager.setStatusBarDisabled(admin, true)
        }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            devicePolicyManager.setLockTaskFeatures(
                admin,
                DevicePolicyManager.LOCK_TASK_FEATURE_NONE
            )
        }
    }

    fun startKiosk(activity: Activity) {
        runCatching {
            activity.startLockTask()
        }
    }

    fun stopKiosk(activity: Activity) {
        val activityManager = activity.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
        val isLocked = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            activityManager.lockTaskModeState != ActivityManager.LOCK_TASK_MODE_NONE
        } else {
            @Suppress("DEPRECATION")
            activityManager.isInLockTaskMode
        }

        if (isLocked) {
            runCatching {
                activity.stopLockTask()
            }
        }
    }

    fun describeState(context: Context): KioskState {
        val devicePolicyManager = context.devicePolicyManager()
        val isDeviceOwner = devicePolicyManager.isDeviceOwnerApp(context.packageName)
        val isLockTaskPermitted = devicePolicyManager.isLockTaskPermitted(context.packageName)

        return when {
            isDeviceOwner && isLockTaskPermitted -> KioskState(
                title = context.getString(R.string.kiosk_ready_title),
                details = context.getString(R.string.kiosk_ready_details)
            )
            isDeviceOwner -> KioskState(
                title = context.getString(R.string.kiosk_owner_title),
                details = context.getString(R.string.kiosk_owner_details)
            )
            else -> KioskState(
                title = context.getString(R.string.kiosk_not_owner_title),
                details = context.getString(R.string.kiosk_not_owner_details)
            )
        }
    }

    private fun setAsHomeActivity(
        context: Context,
        devicePolicyManager: DevicePolicyManager,
        admin: ComponentName
    ) {
        val filter = IntentFilter(Intent.ACTION_MAIN).apply {
            addCategory(Intent.CATEGORY_HOME)
            addCategory(Intent.CATEGORY_DEFAULT)
        }

        val componentName = ComponentName(context.packageName, MainActivity::class.java.name)
        devicePolicyManager.addPersistentPreferredActivity(admin, filter, componentName)
    }

    private fun applyUserRestrictions(
        devicePolicyManager: DevicePolicyManager,
        admin: ComponentName
    ) {
        val restrictions = listOf(
            UserManager.DISALLOW_ADD_USER,
            UserManager.DISALLOW_APPS_CONTROL,
            UserManager.DISALLOW_CONFIG_CREDENTIALS,
            UserManager.DISALLOW_FACTORY_RESET,
            UserManager.DISALLOW_MOUNT_PHYSICAL_MEDIA,
            UserManager.DISALLOW_SAFE_BOOT,
            UserManager.DISALLOW_UNINSTALL_APPS
        )

        restrictions.forEach { restriction ->
            devicePolicyManager.addUserRestriction(admin, restriction)
        }
    }

    private fun grantRuntimePermissions(
        context: Context,
        devicePolicyManager: DevicePolicyManager,
        admin: ComponentName
    ) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.M) {
            return
        }

        devicePolicyManager.setPermissionGrantState(
            admin,
            context.packageName,
            Manifest.permission.ACCESS_FINE_LOCATION,
            DevicePolicyManager.PERMISSION_GRANT_STATE_GRANTED
        )
    }

    private fun Context.devicePolicyManager(): DevicePolicyManager {
        return getSystemService(Context.DEVICE_POLICY_SERVICE) as DevicePolicyManager
    }

    private fun Context.adminComponent(): ComponentName {
        return ComponentName(this, RechiDeviceAdminReceiver::class.java)
    }
}
