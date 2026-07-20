package com.example.twitchnetworknotifier.monitor

import com.example.twitchnetworknotifier.monitor.data.HistoryStore
import com.example.twitchnetworknotifier.monitor.data.Settings
import com.example.twitchnetworknotifier.monitor.data.SettingsStore
import com.example.twitchnetworknotifier.monitor.model.StatusEvent
import com.example.twitchnetworknotifier.monitor.model.StreamStatus
import com.example.twitchnetworknotifier.monitor.model.TwitchCheckResult
import com.example.twitchnetworknotifier.monitor.network.TwitchApiClient
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeoutOrNull

class StreamRepository(
    private val settingsStore: SettingsStore,
    private val historyStore: HistoryStore,
    private val apiClient: TwitchApiClient,
    private val clock: () -> Long = System::currentTimeMillis
) {

    companion object {
        // TODO: TEMP for testing — restore to listOf(10_000L, 20_000L, 40_000L) before shipping.
        private val RETRY_BACKOFF_MILLIS = listOf(2_000L, 4_000L, 6_000L)
        private const val MAX_PROBLEM_NOTIFICATIONS = 3
    }

    private val _currentStatus = MutableStateFlow(StreamStatus.UNKNOWN)
    val currentStatus: StateFlow<StreamStatus> = _currentStatus.asStateFlow()

    // Serializes checks so state mutations never interleave.
    private val checkMutex = Mutex()

    // Bumped on every settings save; checks that captured an older value are stale.
    // Observable so a sleeping retry backoff can wake immediately (Task 2).
    private val credentialGeneration = MutableStateFlow(0L)

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

    fun bumpCredentialGeneration() {
        credentialGeneration.update { it + 1 } // atomic; callable from any thread
    }

    suspend fun checkOnce(): StreamStatus = checkMutex.withLock {
        val myGeneration = credentialGeneration.value
        val settings = settingsStore.settings.first()
        val previousStatus = _currentStatus.value

        val result = performCheck(settings, previousStatus, myGeneration)
            ?: return@withLock _currentStatus.value // aborted mid-retry: no-op tick
        // Stale check: newer credentials were saved while this check ran.
        // Discard silently — no status change, no history row, no alert.
        if (myGeneration != credentialGeneration.value) return@withLock _currentStatus.value

        val newStatus = result.toStatus()
        applyStatus(previousStatus, newStatus)
        newStatus
    }

    // Single immediate check for the Settings save flow: one API call, no
    // retry/backoff (retries stay the background monitor's job). Applies the
    // result via applyStatus only if no newer save happened meanwhile; the
    // mapped status of this call is returned either way. Worst-case wait for the
    // lock is one in-flight API call/timeout from the service loop's check — a
    // sleeping retry backoff releases immediately via the generation race.
    suspend fun checkNow(): StreamStatus = checkMutex.withLock {
        val myGeneration = credentialGeneration.value
        val settings = settingsStore.settings.first()
        val previousStatus = _currentStatus.value

        val result = apiClient.getStreamStatus(settings.channelName, settings.clientId, settings.clientSecret)
        val newStatus = result.toStatus()
        if (myGeneration == credentialGeneration.value) {
            applyStatus(previousStatus, newStatus)
        }
        newStatus
    }

    // Confirms a non-live result with retries only when leaving a healthy/unknown
    // state. Once already in a problem state, a single quick check is used.
    // Returns null when a settings save invalidated this check mid-flight: the
    // backoff sleep races the credentialGeneration signal and wakes immediately.
    private suspend fun performCheck(
        settings: Settings,
        previousStatus: StreamStatus,
        myGeneration: Long
    ): TwitchCheckResult? {
        val firstResult = apiClient.getStreamStatus(settings.channelName, settings.clientId, settings.clientSecret)
        val enteringProblem = previousStatus == StreamStatus.LIVE || previousStatus == StreamStatus.UNKNOWN
        if (firstResult is TwitchCheckResult.Live || !enteringProblem) {
            return firstResult
        }

        var lastResult = firstResult
        for (backoffMillis in RETRY_BACKOFF_MILLIS) {
            val invalidated = withTimeoutOrNull(backoffMillis) {
                credentialGeneration.first { it != myGeneration }
            } != null
            if (invalidated) return null // abort: newer credentials saved
            lastResult = apiClient.getStreamStatus(settings.channelName, settings.clientId, settings.clientSecret)
            if (lastResult is TwitchCheckResult.Live) return lastResult
        }
        return lastResult
    }

    private fun TwitchCheckResult.toStatus(): StreamStatus = when (this) {
        is TwitchCheckResult.Live -> StreamStatus.LIVE
        is TwitchCheckResult.Offline -> StreamStatus.OFFLINE
        is TwitchCheckResult.Failure -> StreamStatus.CONNECTION_ISSUE
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
                    _alerts.emit(StatusEvent(clock(), previousStatus, newStatus, attempt = problemNotificationCount))
                }
            }
            StreamStatus.UNKNOWN -> {
                // Not produced by a check; nothing to notify.
            }
        }
    }
}
