package dev.tvvolume.app

import android.accessibilityservice.AccessibilityService
import android.util.Log
import android.view.accessibility.AccessibilityEvent

class AppWatcherService : AccessibilityService() {

    companion object {
        @Volatile
        var currentPackage: String? = null
            private set

        @Volatile
        var lastTrackedPackage: String? = null
            private set
    }

    override fun onServiceConnected() {
        Log.i("TvVolume", "a11y connected")
    }

    override fun onInterrupt() = Unit

    override fun onAccessibilityEvent(event: AccessibilityEvent) {
        if (event.eventType == AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED) {
            val pkg = event.packageName?.toString() ?: return
            Log.i("TvVolume", "a11y window pkg=$pkg")
            if (pkg == packageName) return
            currentPackage = pkg
            if (TvVolumeClient.isTracked(this, pkg) && pkg != lastTrackedPackage) {
                lastTrackedPackage = pkg
                TvVolumeClient.resetToDefault(this)
            }
        }
    }
}
