package com.example.twitchnetworknotifier.monitor

import android.content.Context
import android.content.Intent
import androidx.core.content.ContextCompat
import androidx.lifecycle.LifecycleService
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.onSubscription
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

class StreamMonitorService : LifecycleService() {

    companion object {
        private const val CHECK_INTERVAL_MILLIS = 2 * 60 * 1000L
        private const val EXTRA_SHOW_WELCOME = "show_welcome"

        fun start(context: Context, showWelcome: Boolean) {
            val intent = Intent(context, StreamMonitorService::class.java)
                .putExtra(EXTRA_SHOW_WELCOME, showWelcome)
            ContextCompat.startForegroundService(context, intent)
        }

        fun stop(context: Context) {
            context.stopService(Intent(context, StreamMonitorService::class.java))
        }
    }

    private lateinit var repository: StreamRepository
    private lateinit var notificationHelper: NotificationHelper
    private var loopJob: Job? = null

    override fun onCreate() {
        super.onCreate()
        notificationHelper = NotificationHelper(this)
        notificationHelper.createNotificationChannels()
        repository = AppContainer.getRepository(this)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        super.onStartCommand(intent, flags, startId)
        startForeground(NotificationHelper.MONITORING_NOTIFICATION_ID, notificationHelper.buildMonitoringNotification())

        if (intent?.getBooleanExtra(EXTRA_SHOW_WELCOME, false) == true) {
            notificationHelper.showAlert(notificationHelper.welcomeMessage())
        }

        if (loopJob == null) {
            loopJob = lifecycleScope.launch {
                val collectorReady = CompletableDeferred<Unit>()
                launch {
                    repository.alerts
                        .onSubscription { collectorReady.complete(Unit) }
                        .collect { event ->
                            notificationHelper.showAlert(notificationHelper.messageForStatus(event.toState))
                        }
                }
                // Wait until the alert collector is subscribed before the first check can
                // emit; otherwise the first alert of the session would be dropped, because
                // repository.alerts is a replay = 0 SharedFlow.
                collectorReady.await()
                while (isActive) {
                    repository.checkOnce()
                    delay(CHECK_INTERVAL_MILLIS)
                }
            }
        }

        return START_STICKY
    }

    override fun onDestroy() {
        loopJob?.cancel()
        loopJob = null
        super.onDestroy()
    }
}
