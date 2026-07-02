# Offline-Detection Alerting Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Make the monitor poll every 2 minutes, confirm a streamer going offline before alerting, send up to 3 repeat reminders (offline and connection-issue tracks separately), and send a single "back online" alert on recovery.

**Architecture:** All behavior lives in `StreamRepository.checkOnce()`. A `performCheck` helper runs the 10/20/40s retry confirmation only on a healthy→problem transition (single quick check once already in a problem state). An `applyStatus` step records history on real transitions only, and emits alerts via the existing `_alerts` SharedFlow according to a per-streak notification cap. The foreground service just shortens its poll interval; the alert→notification wiring is unchanged.

**Tech Stack:** Kotlin, Android, kotlinx.coroutines (Flow/SharedFlow), JUnit + kotlinx-coroutines-test + MockK.

## Global Constraints

- Retry backoff values: `10_000L, 20_000L, 40_000L` (unchanged, exactly these three).
- Max repeat notifications per problem streak: `3`.
- Check interval: `2 * 60 * 1000L` milliseconds.
- Notification-count state is in-memory only (no persistence).
- History records only real status transitions (`newStatus != previousStatus`).
- Do not change `NotificationHelper`, the Twitch API client, settings, or UI.
- Run unit tests with: `./gradlew :app:testDebugUnitTest --tests "com.example.twitchnetworknotifier.monitor.StreamRepositoryTest"`

---

### Task 1: Shorten the check interval to 2 minutes

**Files:**
- Modify: `app/src/main/java/com/example/twitchnetworknotifier/monitor/StreamMonitorService.kt:16`

**Interfaces:**
- Consumes: nothing.
- Produces: nothing new (private constant only).

This constant is consumed by a coroutine that also uses the Android framework
(`LifecycleService`), so it is not covered by a unit test; verify by
compilation and inspection.

- [ ] **Step 1: Change the constant**

In `StreamMonitorService.kt`, replace line 16:

```kotlin
        private const val CHECK_INTERVAL_MILLIS = 5 * 60 * 1000L
```

with:

```kotlin
        private const val CHECK_INTERVAL_MILLIS = 2 * 60 * 1000L
```

- [ ] **Step 2: Verify it compiles**

Run: `./gradlew :app:compileDebugKotlin`
Expected: `BUILD SUCCESSFUL`

- [ ] **Step 3: Commit**

```bash
git add app/src/main/java/com/example/twitchnetworknotifier/monitor/StreamMonitorService.kt
git commit -m "feat: poll stream status every 2 minutes"
```

---

### Task 2: Retry-confirm on transition + capped/repeat/recovery notifications

**Files:**
- Modify: `app/src/main/java/com/example/twitchnetworknotifier/monitor/StreamRepository.kt` (full rewrite of the class body below)
- Test: `app/src/test/java/com/example/twitchnetworknotifier/monitor/StreamRepositoryTest.kt` (replace test methods as shown)

**Interfaces:**
- Consumes:
  - `TwitchApiClient.getStreamStatus(channelName, clientId, clientSecret): TwitchCheckResult`
  - `TwitchCheckResult` = `Live` | `Offline` | `Failure(message: String)`
  - `StreamStatus` = `UNKNOWN | LIVE | OFFLINE | CONNECTION_ISSUE`
  - `StatusEvent(timestampMillis: Long, fromState: StreamStatus, toState: StreamStatus)`
  - `HistoryStore.addEvent(event: StatusEvent)`, `SettingsStore.settings: Flow<Settings>`
  - `Settings(channelName, clientId, clientSecret, monitoringEnabled)`
- Produces (unchanged public surface):
  - `StreamRepository.checkOnce(): StreamStatus`
  - `StreamRepository.currentStatus: StateFlow<StreamStatus>`
  - `StreamRepository.alerts: SharedFlow<StatusEvent>`
  - `StreamRepository.setMonitoringEnabled(enabled: Boolean)`

**Behavior being implemented (reference):**
- `performCheck` does one API call. If it returns `Live`, or the previous status
  is a problem state (`OFFLINE`/`CONNECTION_ISSUE`), return that single result.
  Otherwise (previous is `LIVE`/`UNKNOWN` and result is non-live) retry with
  10/20/40s backoff, returning early on any `Live`, else the final result.
- `applyStatus`: on a real change, update status, reset the streak count to 0,
  and record history. Then: `LIVE` emits a "back online" alert only if the
  previous status was `OFFLINE`/`CONNECTION_ISSUE`; `OFFLINE`/`CONNECTION_ISSUE`
  emit while the streak count is `< 3` (incrementing it); `UNKNOWN` emits
  nothing.

- [ ] **Step 1: Replace the existing test methods with the new suite**

Open `app/src/test/java/com/example/twitchnetworknotifier/monitor/StreamRepositoryTest.kt`.
Keep the imports and the three private helpers (`buildRepository`,
`fakeSettingsStore`, `fakeHistoryStore`) exactly as they are. Replace ALL
`@Test` methods (everything from `firstCheckLive...` through
`alertsFlowEmitsOnlyOnStateChange`) with the following methods:

```kotlin
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
        assertEquals(StreamStatus.LIVE, emitted[1].toState)    // back online
        assertEquals(StreamStatus.OFFLINE, emitted[2].toState) // fresh streak, reminder #1
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
```

- [ ] **Step 2: Run the new tests to verify they fail**

Run: `./gradlew :app:testDebugUnitTest --tests "com.example.twitchnetworknotifier.monitor.StreamRepositoryTest"`
Expected: FAIL — e.g. `firstOfflineFromUnknownRetriesToConfirm` expects 4 calls but the current Failure-only retry makes 1, and `firstCheckLiveRecordsHistoryButEmitsNoAlert` expects 0 alerts but the current code emits on the UNKNOWN→LIVE change.

- [ ] **Step 3: Rewrite `StreamRepository` to the new behavior**

Replace the entire body of
`app/src/main/java/com/example/twitchnetworknotifier/monitor/StreamRepository.kt`
with:

```kotlin
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
```

- [ ] **Step 4: Run the tests to verify they pass**

Run: `./gradlew :app:testDebugUnitTest --tests "com.example.twitchnetworknotifier.monitor.StreamRepositoryTest"`
Expected: `BUILD SUCCESSFUL`, all tests pass.

- [ ] **Step 5: Commit**

```bash
git add app/src/main/java/com/example/twitchnetworknotifier/monitor/StreamRepository.kt app/src/test/java/com/example/twitchnetworknotifier/monitor/StreamRepositoryTest.kt
git commit -m "feat: confirm offline before alerting and cap repeat notifications"
```

---

### Task 3: Full build + regression check

**Files:** none (verification only).

- [ ] **Step 1: Run the whole unit-test suite**

Run: `./gradlew :app:testDebugUnitTest`
Expected: `BUILD SUCCESSFUL` — no other test class regressed.

- [ ] **Step 2: Assemble the debug build**

Run: `./gradlew :app:assembleDebug`
Expected: `BUILD SUCCESSFUL`

No commit — verification only.

---

## Notes for the implementer

- The `_alerts` SharedFlow drives notifications in `StreamMonitorService`, which
  maps `event.toState` to a message string via `NotificationHelper.messageForStatus`.
  `LIVE → "back live"`, `OFFLINE → "offline"`, `CONNECTION_ISSUE → "connection issue"`.
  That is why emitting a `StatusEvent` whose `toState` is the current status is
  sufficient — no `NotificationHelper` change is required.
- For repeat reminders, `previousStatus == newStatus`, so the emitted event has
  equal `fromState`/`toState`; this is intentional and still maps to the correct
  message.
- The `FakeTwitchApiClient` returns `TwitchCheckResult.Live` when its queue is
  empty. Every test above queues enough results to cover all API calls
  (remember: a confirming cycle makes 4 calls, a single-check cycle makes 1), so
  the queue never falls back to `Live` mid-cycle unintentionally.
```
