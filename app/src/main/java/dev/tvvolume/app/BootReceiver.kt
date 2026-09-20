package dev.tvvolume.app

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action == Intent.ACTION_BOOT_COMPLETED ||
            intent.action == Intent.ACTION_MY_PACKAGE_REPLACED) {
            TvVolumeClient.resetToDefault(context)
            val svc = Intent(context, TvVolumeService::class.java)
            context.startForegroundService(svc)
        }
    }
}
