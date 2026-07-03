package com.example.twitchnetworknotifier.monitor

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import com.example.twitchnetworknotifier.MainActivity
import com.example.twitchnetworknotifier.R
import com.example.twitchnetworknotifier.monitor.model.StatusEvent
import com.example.twitchnetworknotifier.monitor.model.StreamStatus
import java.util.concurrent.atomic.AtomicInteger

class NotificationHelper(private val context: Context) {

    companion object {
        const val MONITORING_CHANNEL_ID = "monitoring_channel"
        const val ALERTS_CHANNEL_ID = "alerts_channel"
        const val MONITORING_NOTIFICATION_ID = 1
        private val nextAlertId = AtomicInteger((System.currentTimeMillis() / 10000L).toInt())
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

    private fun contentIntent(): PendingIntent {
        val intent = context.packageManager.getLaunchIntentForPackage(context.packageName)
            ?: Intent(context, MainActivity::class.java).apply {
                flags = Intent.FLAG_ACTIVITY_NEW_TASK
            }
        return PendingIntent.getActivity(
            context,
            0,
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
    }

    fun buildMonitoringNotification(): Notification {
        return NotificationCompat.Builder(context, MONITORING_CHANNEL_ID)
            .setContentTitle(context.getString(R.string.app_name))
            .setContentText(context.getString(R.string.notif_monitoring_active))
            .setSmallIcon(R.drawable.ic_launcher_foreground)
            .setContentIntent(contentIntent())
            .setOngoing(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .build()
    }

    fun showAlert(message: String) {
        val notification = NotificationCompat.Builder(context, ALERTS_CHANNEL_ID)
            .setContentTitle(context.getString(R.string.app_name))
            .setContentText(message)
            .setSmallIcon(R.drawable.ic_launcher_foreground)
            .setContentIntent(contentIntent())
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setAutoCancel(true)
            .build()
        NotificationManagerCompat.from(context).notify(nextAlertId.getAndIncrement(), notification)
    }

    fun messageForStatus(status: StreamStatus): String = when (status) {
        StreamStatus.LIVE -> context.getString(R.string.notif_back_live)
        StreamStatus.OFFLINE -> context.getString(R.string.notif_offline)
        StreamStatus.CONNECTION_ISSUE -> context.getString(R.string.notif_connection_issue)
        StreamStatus.UNKNOWN -> context.getString(R.string.notif_connection_issue)
    }

    // For OFFLINE / CONNECTION_ISSUE alerts, prefix the message with the notification
    // attempt number, e.g. "#2: Your live stream is offline, please check."
    fun alertMessage(event: StatusEvent): String {
        val message = messageForStatus(event.toState)
        return when (event.toState) {
            StreamStatus.OFFLINE, StreamStatus.CONNECTION_ISSUE ->
                context.getString(R.string.notif_problem_format, event.attempt, message)
            else -> message
        }
    }

    fun welcomeMessage(): String = context.getString(R.string.notif_welcome)
}
