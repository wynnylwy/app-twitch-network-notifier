package com.example.twitchnetworknotifier.monitor

import com.example.twitchnetworknotifier.monitor.data.HistoryStore
import com.example.twitchnetworknotifier.monitor.data.Settings
import com.example.twitchnetworknotifier.monitor.data.SettingsStore
import com.example.twitchnetworknotifier.monitor.model.StatusEvent
import com.example.twitchnetworknotifier.monitor.model.StreamStatus
import com.example.twitchnetworknotifier.monitor.model.TwitchCheckResult
import com.example.twitchnetworknotifier.monitor.network.TwitchApiClient
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map

class StreamRepository(
    private val settingsStore: SettingsStore,
    private val historyStore: HistoryStore,
    private val apiClient: TwitchApiClient,
    private val clock: () -> Long = System::currentTimeMillis
) {

    companion object {
        private val RETRY_BACKOFF_MILLIS = listOf(10_000L, 20_000L, 40_000L)
        private const val MAX_PROBLEM_NOTIFICATIONS = 3
    }

    private val _currentStatus = MutableStateFlow(StreamStatus.UNKNOWN)
    val currentStatus: StateFlow<StreamStatus> = _currentStatus.asStateFlow()

    private val _alerts = MutableSharedFlow<StatusEvent>(extraBufferCapacity = 1)
    val alerts: SharedFlow<StatusEvent> = _alerts.asSharedFlow()

    // Notifications sent for the current consecutive problem streak; resets on any status change.
    private var problemNotificationCount = 0

    val monitoringEnabled: Flow<Boolean> = settingsStore.settings.map { it.monitoringEnabled }
    val history: Flow<List<StatusEvent>> = historyStore.events

    suspend fun setMonitoringEnabled(enabled: Boolean) {
        settingsStore.setMonitoringEnabled(enabled)
        if (!enabled) {
            // Monitoring stopped: clear the stale status so the UI reflects "Monitoring off".
            _currentStatus.value = StreamStatus.UNKNOWN
            problemNotificationCount = 0
        }
    }

    suspend fun checkOnce(): StreamStatus {
        val settings = settingsStore.settings.first()
        val previousStatus = _currentStatus.value

        val result = performCheck(settings, previousStatus)
        val newStatus = when (result) {
            is TwitchCheckResult.Live -> StreamStatus.LIVE
            is TwitchCheckResult.Offline -> StreamStatus.OFFLINE
            is TwitchCheckResult.Failure -> StreamStatus.CONNECTION_ISSUE
        }

        applyStatus(previousStatus, newStatus)
        return newStatus
    }

    // Confirms a non-live result with retries only when leaving a healthy/unknown
    // state. Once already in a problem state, a single quick check is used.
    private suspend fun performCheck(settings: Settings, previousStatus: StreamStatus): TwitchCheckResult {
        val firstResult = apiClient.getStreamStatus(settings.channelName, settings.clientId, settings.clientSecret)
        val enteringProblem = previousStatus == StreamStatus.LIVE || previousStatus == StreamStatus.UNKNOWN
        if (firstResult is TwitchCheckResult.Live || !enteringProblem) {
            return firstResult
        }

        var lastResult = firstResult
        for (backoffMillis in RETRY_BACKOFF_MILLIS) {
            delay(backoffMillis)
            lastResult = apiClient.getStreamStatus(settings.channelName, settings.clientId, settings.clientSecret)
            if (lastResult is TwitchCheckResult.Live) return lastResult
        }
        return lastResult
    }

    private suspend fun applyStatus(previousStatus: StreamStatus, newStatus: StreamStatus) {
        if (newStatus != previousStatus) {
            _currentStatus.value = newStatus
            problemNotificationCount = 0
            historyStore.addEvent(StatusEvent(clock(), previousStatus, newStatus))
        }

        when (newStatus) {
            StreamStatus.LIVE -> {
                if (previousStatus == StreamStatus.OFFLINE || previousStatus == StreamStatus.CONNECTION_ISSUE) {
                    _alerts.emit(StatusEvent(clock(), previousStatus, newStatus))
                }
            }
            StreamStatus.OFFLINE, StreamStatus.CONNECTION_ISSUE -> {
                if (problemNotificationCount < MAX_PROBLEM_NOTIFICATIONS) {
                    problemNotificationCount++
                    _alerts.emit(StatusEvent(clock(), previousStatus, newStatus))
                }
            }
            StreamStatus.UNKNOWN -> {
                // Not produced by a check; nothing to notify.
            }
        }
    }
}
