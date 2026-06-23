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
    }

    private val _currentStatus = MutableStateFlow(StreamStatus.UNKNOWN)
    val currentStatus: StateFlow<StreamStatus> = _currentStatus.asStateFlow()

    private val _alerts = MutableSharedFlow<StatusEvent>(extraBufferCapacity = 1)
    val alerts: SharedFlow<StatusEvent> = _alerts.asSharedFlow()

    val monitoringEnabled: Flow<Boolean> = settingsStore.settings.map { it.monitoringEnabled }
    val history: Flow<List<StatusEvent>> = historyStore.events

    suspend fun setMonitoringEnabled(enabled: Boolean) {
        settingsStore.setMonitoringEnabled(enabled)
    }

    suspend fun checkOnce(): StreamStatus {
        val settings = settingsStore.settings.first()
        val result = performCheckWithRetry(settings)
        val newStatus = when (result) {
            is TwitchCheckResult.Live -> StreamStatus.LIVE
            is TwitchCheckResult.Offline -> StreamStatus.OFFLINE
            is TwitchCheckResult.Failure -> StreamStatus.CONNECTION_ISSUE
        }

        val previousStatus = _currentStatus.value
        if (newStatus != previousStatus) {
            _currentStatus.value = newStatus
            val event = StatusEvent(clock(), previousStatus, newStatus)
            historyStore.addEvent(event)
            _alerts.emit(event)
        }
        return newStatus
    }

    private suspend fun performCheckWithRetry(settings: Settings): TwitchCheckResult {
        var lastResult = apiClient.getStreamStatus(settings.channelName, settings.clientId, settings.clientSecret)
        if (lastResult !is TwitchCheckResult.Failure) return lastResult

        for (backoffMillis in RETRY_BACKOFF_MILLIS) {
            delay(backoffMillis)
            lastResult = apiClient.getStreamStatus(settings.channelName, settings.clientId, settings.clientSecret)
            if (lastResult !is TwitchCheckResult.Failure) return lastResult
        }
        return lastResult
    }
}
