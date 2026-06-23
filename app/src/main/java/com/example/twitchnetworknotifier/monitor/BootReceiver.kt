package com.example.twitchnetworknotifier.monitor

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.example.twitchnetworknotifier.monitor.data.SettingsStore
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

class BootReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != Intent.ACTION_BOOT_COMPLETED) return

        val appContext = context.applicationContext
        val pendingResult = goAsync()
        CoroutineScope(Dispatchers.Default).launch {
            try {
                val settings = SettingsStore(appContext).settings.first()
                if (settings.monitoringEnabled) {
                    StreamMonitorService.start(appContext, showWelcome = false)
                }
            } finally {
                pendingResult.finish()
            }
        }
    }
}
