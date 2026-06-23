package com.example.twitchnetworknotifier.monitor

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import com.example.twitchnetworknotifier.R
import com.example.twitchnetworknotifier.monitor.model.StreamStatus

class NotificationHelper(private val context: Context) {

    companion object {
        const val MONITORING_CHANNEL_ID = "monitoring_channel"
        const val ALERTS_CHANNEL_ID = "alerts_channel"
        const val MONITORING_NOTIFICATION_ID = 1
        const val ALERT_NOTIFICATION_ID = 2
    }

    fun createNotificationChannels() {
        val manager = context.getSystemService(NotificationManager::class.java)

        val monitoringChannel = NotificationChannel(
            MONITORING_CHANNEL_ID,
            context.getString(R.string.notif_monitoring_channel_name),
            NotificationManager.IMPORTANCE_LOW
        ).apply { setSound(null, null) }

        val alertsChannel = NotificationChannel(
            ALERTS_CHANNEL_ID,
            context.getString(R.string.notif_alerts_channel_name),
            NotificationManager.IMPORTANCE_HIGH
        )

        manager.createNotificationChannel(monitoringChannel)
        manager.createNotificationChannel(alertsChannel)
    }

    fun buildMonitoringNotification(): Notification {
        return NotificationCompat.Builder(context, MONITORING_CHANNEL_ID)
            .setContentTitle(context.getString(R.string.app_name))
            .setContentText(context.getString(R.string.notif_monitoring_active))
            .setSmallIcon(R.drawable.ic_launcher_foreground)
            .setOngoing(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .build()
    }

    fun showAlert(message: String) {
        val notification = NotificationCompat.Builder(context, ALERTS_CHANNEL_ID)
            .setContentTitle(context.getString(R.string.app_name))
            .setContentText(message)
            .setSmallIcon(R.drawable.ic_launcher_foreground)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setAutoCancel(true)
            .build()
        NotificationManagerCompat.from(context).notify(ALERT_NOTIFICATION_ID, notification)
    }

    fun messageForStatus(status: StreamStatus): String = when (status) {
        StreamStatus.LIVE -> context.getString(R.string.notif_back_live)
        StreamStatus.OFFLINE -> context.getString(R.string.notif_offline)
        StreamStatus.CONNECTION_ISSUE -> context.getString(R.string.notif_connection_issue)
        StreamStatus.UNKNOWN -> context.getString(R.string.notif_connection_issue)
    }

    fun welcomeMessage(): String = context.getString(R.string.notif_welcome)
}
