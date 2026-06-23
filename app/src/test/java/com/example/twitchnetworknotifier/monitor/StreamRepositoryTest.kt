package com.example.twitchnetworknotifier.monitor

import com.example.twitchnetworknotifier.monitor.data.HistoryStore
import com.example.twitchnetworknotifier.monitor.data.Settings
import com.example.twitchnetworknotifier.monitor.data.SettingsStore
import com.example.twitchnetworknotifier.monitor.model.StatusEvent
import com.example.twitchnetworknotifier.monitor.model.StreamStatus
import com.example.twitchnetworknotifier.monitor.model.TwitchCheckResult
import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.Test
import kotlin.test.assertEquals

class StreamRepositoryTest {

    private fun buildRepository(
        apiClient: FakeTwitchApiClient,
        settingsStore: SettingsStore,
        historyStore: HistoryStore,
        clock: () -> Long = { 0L }
    ) = StreamRepository(settingsStore, historyStore, apiClient, clock)

    private fun fakeSettingsStore(settings: Settings = Settings(channelName = "mychannel")): SettingsStore {
        val store = mockk<SettingsStore>()
        every { store.settings } returns MutableStateFlow(settings)
        return store
    }

    private fun fakeHistoryStore(): Pair<HistoryStore, MutableList<StatusEvent>> {
        val recorded = mutableListOf<StatusEvent>()
        val store = mockk<HistoryStore>()
        every { store.events } returns MutableStateFlow(emptyList())
        coEvery { store.addEvent(any()) } answers { recorded.add(firstArg()) }
        return store to recorded
    }

    @Test
    fun firstCheckLiveTransitionsFromUnknownAndRecordsHistory() = runTest {
        val api = FakeTwitchApiClient()
        api.queueResult(TwitchCheckResult.Live)
        val (historyStore, recorded) = fakeHistoryStore()
        val repository = buildRepository(api, fakeSettingsStore(), historyStore, clock = { 5_000L })

        val status = repository.checkOnce()

        assertEquals(StreamStatus.LIVE, status)
        assertEquals(StreamStatus.LIVE, repository.currentStatus.value)
        assertEquals(1, recorded.size)
        assertEquals(StreamStatus.UNKNOWN, recorded[0].fromState)
        assertEquals(StreamStatus.LIVE, recorded[0].toState)
        assertEquals(5_000L, recorded[0].timestampMillis)
    }

    @Test
    fun confirmedOfflineDoesNotRetry() = runTest {
        val api = FakeTwitchApiClient()
        api.queueResult(TwitchCheckResult.Offline)
        val (historyStore, _) = fakeHistoryStore()
        val repository = buildRepository(api, fakeSettingsStore(), historyStore)

        val status = repository.checkOnce()

        assertEquals(StreamStatus.OFFLINE, status)
        assertEquals(1, api.callCount)
    }

    @Test
    fun callFailureRetriesThreeTimesWithBackoffBeforeConnectionIssue() = runTest {
        val api = FakeTwitchApiClient()
        repeat(4) { api.queueResult(TwitchCheckResult.Failure("boom")) }
        val (historyStore, _) = fakeHistoryStore()
        val repository = buildRepository(api, fakeSettingsStore(), historyStore)

        val status = repository.checkOnce()
        advanceUntilIdle()

        assertEquals(StreamStatus.CONNECTION_ISSUE, status)
        assertEquals(4, api.callCount)
    }

    @Test
    fun callFailureThenSuccessOnRetryRecoversWithoutConnectionIssue() = runTest {
        val api = FakeTwitchApiClient()
        api.queueResult(TwitchCheckResult.Failure("boom"))
        api.queueResult(TwitchCheckResult.Live)
        val (historyStore, _) = fakeHistoryStore()
        val repository = buildRepository(api, fakeSettingsStore(), historyStore)

        val status = repository.checkOnce()
        advanceUntilIdle()

        assertEquals(StreamStatus.LIVE, status)
        assertEquals(2, api.callCount)
    }

    @Test
    fun repeatedSameStatusDoesNotEmitDuplicateAlert() = runTest {
        val api = FakeTwitchApiClient()
        api.queueResult(TwitchCheckResult.Live)
        api.queueResult(TwitchCheckResult.Live)
        val (historyStore, recorded) = fakeHistoryStore()
        val repository = buildRepository(api, fakeSettingsStore(), historyStore)

        repository.checkOnce()
        repository.checkOnce()

        assertEquals(1, recorded.size)
    }

    @Test
    fun alertsFlowEmitsOnlyOnStateChange() = runTest {
        val api = FakeTwitchApiClient()
        api.queueResult(TwitchCheckResult.Live)
        api.queueResult(TwitchCheckResult.Offline)
        val (historyStore, _) = fakeHistoryStore()
        val repository = buildRepository(api, fakeSettingsStore(), historyStore)

        val emitted = mutableListOf<StatusEvent>()
        val job = launch { repository.alerts.collect { emitted.add(it) } }
        testScheduler.runCurrent()

        repository.checkOnce()
        repository.checkOnce()
        advanceUntilIdle()
        job.cancel()

        assertEquals(2, emitted.size)
        assertEquals(StreamStatus.LIVE, emitted[0].toState)
        assertEquals(StreamStatus.OFFLINE, emitted[1].toState)
    }
}
