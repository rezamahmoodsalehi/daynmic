package app.dynamicisland

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.provider.Settings
import androidx.core.content.ContextCompat

class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent?) {
        if (intent?.action != Intent.ACTION_BOOT_COMPLETED) return
        if (Prefs.enabled(context) && Settings.canDrawOverlays(context)) {
            ContextCompat.startForegroundService(context, Intent(context, IslandService::class.java))
        }
    }
}
