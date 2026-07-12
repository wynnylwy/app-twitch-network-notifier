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
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.currentTime
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
    fun firstCheckLiveRecordsHistoryButEmitsNoAlert() = runTest {
        val api = FakeTwitchApiClient()
        api.queueResult(TwitchCheckResult.Live)
        val (historyStore, recorded) = fakeHistoryStore()
        val repository = buildRepository(api, fakeSettingsStore(), historyStore, clock = { 5_000L })

        val emitted = mutableListOf<StatusEvent>()
        val job = launch { repository.alerts.collect { emitted.add(it) } }
        testScheduler.runCurrent()

        val status = repository.checkOnce()
        advanceUntilIdle()
        job.cancel()

        assertEquals(StreamStatus.LIVE, status)
        assertEquals(1, recorded.size)
        assertEquals(StreamStatus.UNKNOWN, recorded[0].fromState)
        assertEquals(StreamStatus.LIVE, recorded[0].toState)
        assertEquals(0, emitted.size)
    }

    @Test
    fun firstOfflineFromUnknownRetriesToConfirm() = runTest {
        val api = FakeTwitchApiClient()
        repeat(4) { api.queueResult(TwitchCheckResult.Offline) }
        val (historyStore, _) = fakeHistoryStore()
        val repository = buildRepository(api, fakeSettingsStore(), historyStore)

        val status = repository.checkOnce()
        advanceUntilIdle()

        assertEquals(StreamStatus.OFFLINE, status)
        assertEquals(4, api.callCount)
    }

    @Test
    fun offlineBlipRecoversToLiveDuringRetry() = runTest {
        val api = FakeTwitchApiClient()
        api.queueResult(TwitchCheckResult.Offline)
        api.queueResult(TwitchCheckResult.Live)
        val (historyStore, _) = fakeHistoryStore()
        val repository = buildRepository(api, fakeSettingsStore(), historyStore)

        val status = repository.checkOnce()
        advanceUntilIdle()

        assertEquals(StreamStatus.LIVE, status)
        assertEquals(2, api.callCount)
    }

    @Test
    fun offlineRecoversToLiveOnLaterRetry() = runTest {
        val api = FakeTwitchApiClient()
        api.queueResult(TwitchCheckResult.Offline) // initial check
        api.queueResult(TwitchCheckResult.Offline) // retry 1 (10s) still offline
        api.queueResult(TwitchCheckResult.Live)    // retry 2 (20s) recovers
        val (historyStore, _) = fakeHistoryStore()
        val repository = buildRepository(api, fakeSettingsStore(), historyStore)

        val status = repository.checkOnce()
        advanceUntilIdle()

        assertEquals(StreamStatus.LIVE, status)
        assertEquals(3, api.callCount)
    }

    @Test
    fun alreadyOfflineUsesSingleCheck() = runTest {
        val api = FakeTwitchApiClient()
        repeat(4) { api.queueResult(TwitchCheckResult.Offline) } // first cycle confirms offline
        api.queueResult(TwitchCheckResult.Offline)               // second cycle: single check
        val (historyStore, _) = fakeHistoryStore()
        val repository = buildRepository(api, fakeSettingsStore(), historyStore)

        repository.checkOnce()
        advanceUntilIdle()
        val afterFirst = api.callCount

        repository.checkOnce()
        advanceUntilIdle()

        assertEquals(4, afterFirst)
        assertEquals(5, api.callCount)
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
    fun offlineStreakEmitsThreeAlertsThenGoesQuiet() = runTest {
        val api = FakeTwitchApiClient()
        // cycle 1 confirms offline (4 calls); cycles 2-4 single check (1 call each)
        repeat(8) { api.queueResult(TwitchCheckResult.Offline) }
        val (historyStore, _) = fakeHistoryStore()
        val repository = buildRepository(api, fakeSettingsStore(), historyStore)

        val emitted = mutableListOf<StatusEvent>()
        val job = launch { repository.alerts.collect { emitted.add(it) } }
        testScheduler.runCurrent()

        repeat(4) {
            repository.checkOnce()
            advanceUntilIdle()
        }
        job.cancel()

        assertEquals(3, emitted.size)
        assertEquals(listOf(StreamStatus.OFFLINE, StreamStatus.OFFLINE, StreamStatus.OFFLINE), emitted.map { it.toState })
        assertEquals(listOf(1, 2, 3), emitted.map { it.attempt })
    }

    @Test
    fun recoveryToLiveEmitsBackOnlineAndResetsStreak() = runTest {
        val api = FakeTwitchApiClient()
        repeat(4) { api.queueResult(TwitchCheckResult.Offline) } // cycle 1: confirm offline
        api.queueResult(TwitchCheckResult.Live)                  // cycle 2: recovery
        repeat(4) { api.queueResult(TwitchCheckResult.Offline) } // cycle 3: offline again (fresh streak)
        val (historyStore, _) = fakeHistoryStore()
        val repository = buildRepository(api, fakeSettingsStore(), historyStore)

        val emitted = mutableListOf<StatusEvent>()
        val job = launch { repository.alerts.collect { emitted.add(it) } }
        testScheduler.runCurrent()

        repeat(3) {
            repository.checkOnce()
            advanceUntilIdle()
        }
        job.cancel()

        assertEquals(3, emitted.size)
        assertEquals(StreamStatus.OFFLINE, emitted[0].toState) // streak 1, reminder #1
        assertEquals(1, emitted[0].attempt)
        assertEquals(StreamStatus.LIVE, emitted[1].toState)    // back online
        assertEquals(StreamStatus.OFFLINE, emitted[2].toState) // fresh streak, reminder #1
        assertEquals(1, emitted[2].attempt)                    // streak reset after recovery
    }

    @Test
    fun connectionIssueEmitsThreeAlertsThenGoesQuiet() = runTest {
        val api = FakeTwitchApiClient()
        // cycle 1 confirms connection issue (4 calls); cycles 2-4 single check (1 call each)
        repeat(8) { api.queueResult(TwitchCheckResult.Failure("boom")) }
        val (historyStore, _) = fakeHistoryStore()
        val repository = buildRepository(api, fakeSettingsStore(), historyStore)

        val emitted = mutableListOf<StatusEvent>()
        val job = launch { repository.alerts.collect { emitted.add(it) } }
        testScheduler.runCurrent()

        repeat(4) {
            repository.checkOnce()
            advanceUntilIdle()
        }
        job.cancel()

        assertEquals(3, emitted.size)
        assertEquals(listOf(StreamStatus.CONNECTION_ISSUE, StreamStatus.CONNECTION_ISSUE, StreamStatus.CONNECTION_ISSUE), emitted.map { it.toState })
        assertEquals(listOf(1, 2, 3), emitted.map { it.attempt })
    }

    @Test
    fun disablingMonitoringResetsCurrentStatusToUnknown() = runTest {
        val api = FakeTwitchApiClient()
        api.queueResult(TwitchCheckResult.Live)
        val (historyStore, _) = fakeHistoryStore()
        val settingsStore = fakeSettingsStore()
        coEvery { settingsStore.setMonitoringEnabled(any()) } returns Unit
        val repository = buildRepository(api, settingsStore, historyStore)

        repository.checkOnce()
        assertEquals(StreamStatus.LIVE, repository.currentStatus.value)

        repository.setMonitoringEnabled(false)

        assertEquals(StreamStatus.UNKNOWN, repository.currentStatus.value)
    }

    @Test
    fun checkOnceDiscardsResultWhenCredentialsChangedMidCheck() = runTest {
        val api = FakeTwitchApiClient()
        api.queueResult(TwitchCheckResult.Live)
        val (historyStore, recorded) = fakeHistoryStore()
        val repository = buildRepository(api, fakeSettingsStore(), historyStore)

        val emitted = mutableListOf<StatusEvent>()
        val job = launch { repository.alerts.collect { emitted.add(it) } }
        testScheduler.runCurrent()

        // Simulate a save landing while the API call is in flight.
        api.onCall = { repository.bumpCredentialGeneration() }

        val status = repository.checkOnce()
        advanceUntilIdle()
        job.cancel()

        assertEquals(StreamStatus.UNKNOWN, status)                      // unchanged
        assertEquals(StreamStatus.UNKNOWN, repository.currentStatus.value)
        assertEquals(0, recorded.size)                                   // no history row
        assertEquals(0, emitted.size)                                    // no alert
    }

    @Test
    fun savingDuringRetryBackoffAbortsCheckImmediately() = runTest {
        val api = FakeTwitchApiClient()
        repeat(4) { api.queueResult(TwitchCheckResult.Failure("bad creds")) }
        val (historyStore, recorded) = fakeHistoryStore()
        val repository = buildRepository(api, fakeSettingsStore(), historyStore)

        val emitted = mutableListOf<StatusEvent>()
        val collector = launch { repository.alerts.collect { emitted.add(it) } }
        testScheduler.runCurrent()

        val check = async { repository.checkOnce() }
        testScheduler.runCurrent() // first API call fails; now sleeping in backoff

        repository.bumpCredentialGeneration() // user saves new credentials
        testScheduler.runCurrent()            // wakes WITHOUT advancing virtual time
        collector.cancel()

        assertEquals(StreamStatus.UNKNOWN, check.await()) // aborted: status unchanged
        assertEquals(1, api.callCount)                     // no further retry calls
        assertEquals(0, recorded.size)                     // no history row
        assertEquals(0, emitted.size)                      // no stale notification
        assertEquals(0L, currentTime)                      // woke instantly, not after backoff
    }
}
