package com.deskalert.trigger

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

/**
 * After a reboot, Android will rebind an enabled NotificationListenerService
 * on its own in most cases, but on some OEM skins (Xiaomi, Oppo, etc.) that
 * doesn't happen reliably. This nudges the system to re-evaluate listener
 * bindings by toggling the component, which is a common workaround.
 */
class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != Intent.ACTION_BOOT_COMPLETED) return

        val pm = context.packageManager
        val component = android.content.ComponentName(context, DeskAlertNotificationListener::class.java)

        pm.setComponentEnabledSetting(
            component,
            android.content.pm.PackageManager.COMPONENT_ENABLED_STATE_DISABLED,
            android.content.pm.PackageManager.DONT_KILL_APP
        )
        pm.setComponentEnabledSetting(
            component,
            android.content.pm.PackageManager.COMPONENT_ENABLED_STATE_ENABLED,
            android.content.pm.PackageManager.DONT_KILL_APP
        )
    }
}
