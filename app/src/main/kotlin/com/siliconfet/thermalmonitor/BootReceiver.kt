package com.siliconfet.thermalmonitor

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Build

class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        try {

            val action = intent.action ?: return
            if (action != Intent.ACTION_BOOT_COMPLETED &&
                action != Intent.ACTION_LOCKED_BOOT_COMPLETED &&
                action != Intent.ACTION_MY_PACKAGE_REPLACED) return

            if (!UserIntent.isRunning(context)) return

            val anyEnabled = OverlayPrefs.isMainOverlayEnabled(context) ||
                             OverlayPrefs.isDevfreqOverlayEnabled(context) ||
                             OverlayPrefs.isBatteryOverlayEnabled(context) ||
                             OverlayPrefs.isHistoryOverlayEnabled(context)
            if (!anyEnabled) return

            val svcIntent = Intent(context, OverlayService::class.java)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                context.startForegroundService(svcIntent)
            } else {
                context.startService(svcIntent)
            }

            try { KeepAliveJobService.schedule(context) } catch (_: Exception) {}
        } catch (_: Exception) {}
    }
}
