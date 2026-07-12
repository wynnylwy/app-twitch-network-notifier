# Settings "Connecting…" Dialog & Immediate Connect Feedback — Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** After saving settings with monitoring ON, immediately run one Twitch check and show Connecting…/Connected/error dialogs, while any in-flight old-credential retry is aborted and discarded so it can never write stale status/history or pop a stale notification.

**Architecture:** `StreamRepository` gains a `Mutex` serializing all checks, an observable `credentialGeneration` (`MutableStateFlow<Long>`) bumped on every save, an interruptible retry backoff that races the generation signal, and a single-call `checkNow()`. `SettingsViewModel` gains a `SaveFlowState` machine driving dialogs rendered by `SettingsFragment` from state.

**Tech Stack:** Kotlin coroutines (Mutex, StateFlow, withTimeoutOrNull), AndroidX Fragment/Navigation, AlertDialog + CountDownTimer, JUnit4 + kotlinx-coroutines-test + MockK.

**Spec:** `docs/superpowers/specs/2026-07-10-settings-connecting-dialog-design.md`

## Global Constraints

- Package root: `com.example.twitchnetworknotifier`.
- Do NOT touch the TEMP test constants (`RETRY_BACKOFF_MILLIS = listOf(2_000L, 4_000L, 6_000L)`, `CHECK_INTERVAL_MILLIS = 5 * 1000L`) — they stay as-is; restoring them is a separate pre-ship task already marked with TODOs.
- Existing `StreamRepositoryTest` tests must keep passing unmodified — `checkOnce()` behavior is unchanged for the current-generation case.
- `MainFragment` / `MainViewModel` / `StreamMonitorService` must NOT be modified.
- Unit tests run with: `./gradlew testDebugUnitTest` (or a single class via `--tests`).
- Every commit message ends with `Co-Authored-By: Claude Opus 4.8 <noreply@anthropic.com>`.

## File Structure

| File | Responsibility |
|---|---|
| `app/src/main/java/com/example/twitchnetworknotifier/monitor/StreamRepository.kt` | Modify: mutex, `credentialGeneration`, `bumpCredentialGeneration()`, interruptible backoff, stale-discard guard, `checkNow()` |
| `app/src/test/java/com/example/twitchnetworknotifier/monitor/FakeTwitchApiClient.kt` | Modify: add per-call hook so tests can bump the generation mid-call |
| `app/src/test/java/com/example/twitchnetworknotifier/monitor/StreamRepositoryTest.kt` | Modify: new tests for guard, abort, `checkNow()` |
| `app/src/main/java/com/example/twitchnetworknotifier/ui/settings/SettingsViewModel.kt` | Modify: injectable stores, `SaveFlowState`, connect flow in `save()` |
| `app/src/test/java/com/example/twitchnetworknotifier/ui/settings/SettingsViewModelTest.kt` | Create: state-machine tests with fakes |
| `app/src/main/java/com/example/twitchnetworknotifier/ui/settings/SettingsFragment.kt` | Modify: render dialogs from state, countdown, popBackStack |
| `app/src/main/res/values/strings.xml` | Modify: dialog strings |

---

### Task 1: Credential generation + mutex + stale-discard guard in `StreamRepository`

**Files:**
- Modify: `app/src/main/java/com/example/twitchnetworknotifier/monitor/StreamRepository.kt`
- Modify: `app/src/test/java/com/example/twitchnetworknotifier/monitor/FakeTwitchApiClient.kt`
- Test: `app/src/test/java/com/example/twitchnetworknotifier/monitor/StreamRepositoryTest.kt`

**Interfaces:**
- Consumes: existing `StreamRepository.checkOnce()`, `applyStatus`, `FakeTwitchApiClient`.
- Produces: `fun bumpCredentialGeneration()` (thread-safe, non-suspend); `private val credentialGeneration: MutableStateFlow<Long>`; `checkOnce()` wrapped in `checkMutex.withLock` and discarding its result (returning the unchanged current status) when the generation changed mid-check. `FakeTwitchApiClient.onCall: (suspend () -> Unit)?` invoked at the start of every `getStreamStatus`.

- [ ] **Step 1: Add the per-call hook to `FakeTwitchApiClient`**

Replace the body of `FakeTwitchApiClient` with:

```kotlin
class FakeTwitchApiClient : TwitchApiClient {
    var callCount: Int = 0
    /** Invoked at the start of every getStreamStatus call; lets tests mutate state mid-check. */
    var onCall: (suspend () -> Unit)? = null
    private val queuedResults = mutableListOf<TwitchCheckResult>()

    fun queueResult(result: TwitchCheckResult) {
        queuedResults.add(result)
    }

    override suspend fun getStreamStatus(
        channelName: String,
        clientId: String,
        clientSecret: String
    ): TwitchCheckResult {
        callCount++
        onCall?.invoke()
        return if (queuedResults.isNotEmpty()) queuedResults.removeAt(0) else TwitchCheckResult.Live
    }
}
```

- [ ] **Step 2: Write the failing test**

Add to `StreamRepositoryTest`:

```kotlin
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
```

- [ ] **Step 3: Run test to verify it fails**

Run: `./gradlew testDebugUnitTest --tests "com.example.twitchnetworknotifier.monitor.StreamRepositoryTest.checkOnceDiscardsResultWhenCredentialsChangedMidCheck"`
Expected: FAIL — `bumpCredentialGeneration` unresolved (compile error). That counts as the failing state; fix by implementing.

- [ ] **Step 4: Implement generation + mutex + guard**

In `StreamRepository.kt`, add imports:

```kotlin
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
```

Add fields (next to `_currentStatus`; also import `kotlinx.coroutines.flow.update`):

```kotlin
// Serializes checks so state mutations never interleave.
private val checkMutex = Mutex()

// Bumped on every settings save; checks that captured an older value are stale.
// Observable so a sleeping retry backoff can wake immediately (Task 2).
private val credentialGeneration = MutableStateFlow(0L)

fun bumpCredentialGeneration() {
    credentialGeneration.update { it + 1 } // atomic; callable from any thread
}
```

Replace `checkOnce()` with:

```kotlin
suspend fun checkOnce(): StreamStatus = checkMutex.withLock {
    val myGeneration = credentialGeneration.value
    val settings = settingsStore.settings.first()
    val previousStatus = _currentStatus.value

    val result = performCheck(settings, previousStatus)
    // Stale check: newer credentials were saved while this check ran.
    // Discard silently — no status change, no history row, no alert.
    if (myGeneration != credentialGeneration.value) return@withLock _currentStatus.value

    val newStatus = result.toStatus()
    applyStatus(previousStatus, newStatus)
    newStatus
}

private fun TwitchCheckResult.toStatus(): StreamStatus = when (this) {
    is TwitchCheckResult.Live -> StreamStatus.LIVE
    is TwitchCheckResult.Offline -> StreamStatus.OFFLINE
    is TwitchCheckResult.Failure -> StreamStatus.CONNECTION_ISSUE
}
```

(The old inline `when (result)` mapping in `checkOnce` is replaced by `toStatus()`.)

- [ ] **Step 5: Run the full repository test class**

Run: `./gradlew testDebugUnitTest --tests "com.example.twitchnetworknotifier.monitor.StreamRepositoryTest"`
Expected: ALL PASS (new test + all 10 existing tests unchanged).

- [ ] **Step 6: Commit**

```bash
git add app/src/main/java/com/example/twitchnetworknotifier/monitor/StreamRepository.kt \
        app/src/test/java/com/example/twitchnetworknotifier/monitor/FakeTwitchApiClient.kt \
        app/src/test/java/com/example/twitchnetworknotifier/monitor/StreamRepositoryTest.kt
git commit -m "feat: serialize checks and discard stale old-credential results

Co-Authored-By: Claude Opus 4.8 <noreply@anthropic.com>"
```

---

### Task 2: Abort-on-save — interruptible retry backoff

**Files:**
- Modify: `app/src/main/java/com/example/twitchnetworknotifier/monitor/StreamRepository.kt`
- Test: `app/src/test/java/com/example/twitchnetworknotifier/monitor/StreamRepositoryTest.kt`

**Interfaces:**
- Consumes: `credentialGeneration`, `bumpCredentialGeneration()`, `checkMutex` from Task 1.
- Produces: `performCheck(settings, previousStatus, myGeneration): TwitchCheckResult?` — returns `null` when invalidated by a save; its backoff sleep wakes immediately on `bumpCredentialGeneration()`. `checkOnce()` treats `null` as a no-op tick (returns unchanged status).

- [ ] **Step 1: Write the failing test**

Add to `StreamRepositoryTest` (import `kotlinx.coroutines.async` and `kotlinx.coroutines.test.currentTime`):

```kotlin
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
```

- [ ] **Step 2: Run test to verify it fails**

Run: `./gradlew testDebugUnitTest --tests "com.example.twitchnetworknotifier.monitor.StreamRepositoryTest.savingDuringRetryBackoffAbortsCheckImmediately"`
Expected: FAIL — the check sleeps through the full backoff (`currentTime` assertion fails and/or `callCount` is 4), because `delay` is not interruptible by the generation bump. (`runCurrent` does not advance time, so `check.await()` may also hang — a timeout counts as the failure.)

- [ ] **Step 3: Make the backoff race the generation signal**

In `StreamRepository.kt`, add imports:

```kotlin
import kotlinx.coroutines.withTimeoutOrNull
```

(`kotlinx.coroutines.delay` import can be removed once unused.)

Replace `performCheck` with:

```kotlin
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
```

Update the call site in `checkOnce()`:

```kotlin
    val result = performCheck(settings, previousStatus, myGeneration)
        ?: return@withLock _currentStatus.value // aborted mid-retry: no-op tick
```

(The existing `if (myGeneration != credentialGeneration.value)` guard after this line stays — it covers a save landing during the final API call.)

- [ ] **Step 4: Run the full repository test class**

Run: `./gradlew testDebugUnitTest --tests "com.example.twitchnetworknotifier.monitor.StreamRepositoryTest"`
Expected: ALL PASS. The existing retry tests (`firstOfflineFromUnknownRetriesToConfirm`, `callFailureRetriesThreeTimesWithBackoffBeforeConnectionIssue`, etc.) still pass because with no bump the race just times out after `backoffMillis`, identical to the old `delay`.

- [ ] **Step 5: Commit**

```bash
git add app/src/main/java/com/example/twitchnetworknotifier/monitor/StreamRepository.kt \
        app/src/test/java/com/example/twitchnetworknotifier/monitor/StreamRepositoryTest.kt
git commit -m "feat: abort in-flight retry immediately when new credentials are saved

Co-Authored-By: Claude Opus 4.8 <noreply@anthropic.com>"
```

---

### Task 3: `StreamRepository.checkNow()` — single immediate check

**Files:**
- Modify: `app/src/main/java/com/example/twitchnetworknotifier/monitor/StreamRepository.kt`
- Test: `app/src/test/java/com/example/twitchnetworknotifier/monitor/StreamRepositoryTest.kt`

**Interfaces:**
- Consumes: `checkMutex`, `credentialGeneration`, `toStatus()`, `applyStatus` from Tasks 1–2.
- Produces: `suspend fun checkNow(): StreamStatus` — exactly one API call, no retry/backoff; applies status/history/alerts via `applyStatus` only if its generation is still current; always returns the mapped status of its own call. Task 4's ViewModel calls this.

- [ ] **Step 1: Write the failing tests**

Add to `StreamRepositoryTest`:

```kotlin
@Test
fun checkNowUsesSingleCallAndRecordsTransition() = runTest {
    val api = FakeTwitchApiClient()
    api.queueResult(TwitchCheckResult.Offline) // would trigger retries in checkOnce
    val (historyStore, recorded) = fakeHistoryStore()
    val repository = buildRepository(api, fakeSettingsStore(), historyStore, clock = { 7_000L })

    val status = repository.checkNow()
    advanceUntilIdle()

    assertEquals(StreamStatus.OFFLINE, status)
    assertEquals(1, api.callCount) // single call, NO retry/backoff
    assertEquals(1, recorded.size)
    assertEquals(StreamStatus.UNKNOWN, recorded[0].fromState)
    assertEquals(StreamStatus.OFFLINE, recorded[0].toState)
}

@Test
fun checkNowDoesNotDuplicateHistoryWhenStatusUnchanged() = runTest {
    val api = FakeTwitchApiClient()
    api.queueResult(TwitchCheckResult.Failure("bad"))
    api.queueResult(TwitchCheckResult.Failure("still bad"))
    val (historyStore, recorded) = fakeHistoryStore()
    val repository = buildRepository(api, fakeSettingsStore(), historyStore)

    repository.checkNow() // UNKNOWN -> CONNECTION_ISSUE: one row
    repository.checkNow() // repeated failure: no new row
    advanceUntilIdle()

    assertEquals(StreamStatus.CONNECTION_ISSUE, repository.currentStatus.value)
    assertEquals(1, recorded.size)
}

@Test
fun checkNowDiscardsResultWhenCredentialsChangedMidCall() = runTest {
    val api = FakeTwitchApiClient()
    api.queueResult(TwitchCheckResult.Live)
    val (historyStore, recorded) = fakeHistoryStore()
    val repository = buildRepository(api, fakeSettingsStore(), historyStore)

    api.onCall = { repository.bumpCredentialGeneration() }

    repository.checkNow()
    advanceUntilIdle()

    assertEquals(StreamStatus.UNKNOWN, repository.currentStatus.value) // not applied
    assertEquals(0, recorded.size)
}
```

- [ ] **Step 2: Run tests to verify they fail**

Run: `./gradlew testDebugUnitTest --tests "com.example.twitchnetworknotifier.monitor.StreamRepositoryTest"`
Expected: the three new tests FAIL to compile (`checkNow` unresolved).

- [ ] **Step 3: Implement `checkNow()`**

Add to `StreamRepository` (after `checkOnce`):

```kotlin
// Single immediate check for the Settings save flow: one API call, no
// retry/backoff (retries stay the background monitor's job). Applies the
// result via applyStatus only if no newer save happened meanwhile; the
// mapped status of this call is returned either way.
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
```

- [ ] **Step 4: Run the full repository test class**

Run: `./gradlew testDebugUnitTest --tests "com.example.twitchnetworknotifier.monitor.StreamRepositoryTest"`
Expected: ALL PASS.

- [ ] **Step 5: Commit**

```bash
git add app/src/main/java/com/example/twitchnetworknotifier/monitor/StreamRepository.kt \
        app/src/test/java/com/example/twitchnetworknotifier/monitor/StreamRepositoryTest.kt
git commit -m "feat: add StreamRepository.checkNow for immediate single-call checks

Co-Authored-By: Claude Opus 4.8 <noreply@anthropic.com>"
```

---

### Task 4: `SettingsViewModel` — `SaveFlowState` machine

**Files:**
- Modify: `app/src/main/java/com/example/twitchnetworknotifier/ui/settings/SettingsViewModel.kt`
- Create: `app/src/test/java/com/example/twitchnetworknotifier/ui/settings/SettingsViewModelTest.kt`

**Interfaces:**
- Consumes: `StreamRepository.bumpCredentialGeneration()`, `StreamRepository.checkNow(): StreamStatus`, `AppContainer.getRepository(context)`, `SettingsStore`.
- Produces (Task 5's Fragment relies on these exact names):
  - `sealed interface SaveFlowState { data object Idle; data object Connecting; data object Connected; data object ConnectFailed }` (nested in `SettingsViewModel`)
  - `val saveFlowState: StateFlow<SaveFlowState>`
  - `fun acknowledgeConnectFailed()` — error dialog OK → back to `Idle`
  - `fun onConnectedNavigationHandled()` — countdown finished & popped → back to `Idle` (double-pop guard)
  - `save(...)`, `isSaving`, `saveResults`, `loadSettingsOnce()` keep their existing signatures.

- [ ] **Step 1: Make the ViewModel injectable and add the state machine**

Replace `SettingsViewModel.kt` in full:

```kotlin
package com.example.twitchnetworknotifier.ui.settings

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.example.twitchnetworknotifier.monitor.AppContainer
import com.example.twitchnetworknotifier.monitor.StreamRepository
import com.example.twitchnetworknotifier.monitor.model.StreamStatus
import com.example.twitchnetworknotifier.monitor.data.Settings
import com.example.twitchnetworknotifier.monitor.data.SettingsStore
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.SharingStarted.Companion.WhileSubscribed
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

class SettingsViewModel @JvmOverloads constructor(
    application: Application,
    private val settingsStore: SettingsStore = SettingsStore(application),
    private val repository: StreamRepository = AppContainer.getRepository(application)
) : AndroidViewModel(application) {

    sealed interface SaveFlowState {
        data object Idle : SaveFlowState
        data object Connecting : SaveFlowState
        data object Connected : SaveFlowState
        data object ConnectFailed : SaveFlowState
    }

    val settings: StateFlow<Settings> = settingsStore.settings
        .stateIn(viewModelScope, WhileSubscribed(5_000), Settings())

    private val _isSaving = MutableStateFlow(false)
    val isSaving: StateFlow<Boolean> = _isSaving.asStateFlow()

    private val _saveResults = MutableSharedFlow<Result<Unit>>(extraBufferCapacity = 1)
    val saveResults: SharedFlow<Result<Unit>> = _saveResults.asSharedFlow()

    private val _saveFlowState = MutableStateFlow<SaveFlowState>(SaveFlowState.Idle)
    val saveFlowState: StateFlow<SaveFlowState> = _saveFlowState.asStateFlow()

    /**
     * Returns the first real value emitted by the DataStore-backed settings flow,
     * bypassing [settings]'s seeded initial value (an empty [Settings]). Use this
     * for one-shot prefill reads where seeing the seed instead of the persisted
     * value would be incorrect (e.g. pre-filling editable fields).
     */
    suspend fun loadSettingsOnce(): Settings = settingsStore.settings.first()

    fun save(channelName: String, clientId: String, clientSecret: String) {
        if (_isSaving.value) return
        viewModelScope.launch {
            _isSaving.value = true
            val result = runCatching {
                settingsStore.updateChannelName(channelName)
                settingsStore.updateClientId(clientId)
                settingsStore.updateClientSecret(clientSecret)
            }
            _isSaving.value = false

            if (result.isFailure) {
                _saveResults.emit(result)
                return@launch
            }

            // Invalidate any in-flight check that used the old credentials —
            // it aborts/discards instead of applying a stale result.
            repository.bumpCredentialGeneration()

            if (!settingsStore.settings.first().monitoringEnabled) {
                // Monitoring off: keep the existing persist + "Saved" toast path.
                _saveResults.emit(result)
                return@launch
            }

            _saveFlowState.value = SaveFlowState.Connecting
            val status = repository.checkNow()
            _saveFlowState.value = when (status) {
                StreamStatus.LIVE, StreamStatus.OFFLINE -> SaveFlowState.Connected
                else -> SaveFlowState.ConnectFailed
            }
        }
    }

    /** Error dialog dismissed: user stays on Settings to fix and re-save. */
    fun acknowledgeConnectFailed() {
        _saveFlowState.value = SaveFlowState.Idle
    }

    /** Connected countdown finished and navigation happened: reset so a redraw cannot pop again. */
    fun onConnectedNavigationHandled() {
        _saveFlowState.value = SaveFlowState.Idle
    }
}
```

- [ ] **Step 2: Write the tests**

Create `app/src/test/java/com/example/twitchnetworknotifier/ui/settings/SettingsViewModelTest.kt`:

```kotlin
package com.example.twitchnetworknotifier.ui.settings

import android.app.Application
import com.example.twitchnetworknotifier.monitor.StreamRepository
import com.example.twitchnetworknotifier.monitor.model.StreamStatus
import com.example.twitchnetworknotifier.monitor.data.Settings
import com.example.twitchnetworknotifier.monitor.data.SettingsStore
import com.example.twitchnetworknotifier.ui.settings.SettingsViewModel.SaveFlowState
import io.mockk.Runs
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.just
import io.mockk.mockk
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Before
import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

@OptIn(ExperimentalCoroutinesApi::class)
class SettingsViewModelTest {

    private val testDispatcher = StandardTestDispatcher()

    @Before
    fun setUp() {
        Dispatchers.setMain(testDispatcher)
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    private fun fakeSettingsStore(monitoringEnabled: Boolean): SettingsStore {
        val store = mockk<SettingsStore>()
        every { store.settings } returns MutableStateFlow(
            Settings(channelName = "chan", clientId = "id", clientSecret = "sec", monitoringEnabled = monitoringEnabled)
        )
        coEvery { store.updateChannelName(any()) } just Runs
        coEvery { store.updateClientId(any()) } just Runs
        coEvery { store.updateClientSecret(any()) } just Runs
        return store
    }

    private fun fakeRepository(checkResult: StreamStatus): StreamRepository {
        val repository = mockk<StreamRepository>()
        every { repository.bumpCredentialGeneration() } just Runs
        coEvery { repository.checkNow() } returns checkResult
        return repository
    }

    private fun buildViewModel(settingsStore: SettingsStore, repository: StreamRepository) =
        SettingsViewModel(mockk<Application>(relaxed = true), settingsStore, repository)

    @Test
    fun saveWithMonitoringOnAndSuccessGoesConnectingThenConnected() = runTest(testDispatcher.scheduler) {
        // Gate the check so the transient Connecting state is observable deterministically
        // (saveFlowState is a conflated StateFlow — a collector could miss it).
        val gate = CompletableDeferred<StreamStatus>()
        val repository = mockk<StreamRepository>()
        every { repository.bumpCredentialGeneration() } just Runs
        coEvery { repository.checkNow() } coAnswers { gate.await() }
        val viewModel = buildViewModel(fakeSettingsStore(monitoringEnabled = true), repository)

        viewModel.save("chan", "id", "sec")
        advanceUntilIdle() // persist done; check in flight, held by the gate

        assertEquals(SaveFlowState.Connecting, viewModel.saveFlowState.value)

        gate.complete(StreamStatus.LIVE)
        advanceUntilIdle()

        assertEquals(SaveFlowState.Connected, viewModel.saveFlowState.value)
        coVerify(exactly = 1) { repository.bumpCredentialGeneration() }
        coVerify(exactly = 1) { repository.checkNow() }
    }

    @Test
    fun saveWithMonitoringOnOfflineResultAlsoCountsAsConnected() = runTest(testDispatcher.scheduler) {
        val viewModel = buildViewModel(
            fakeSettingsStore(monitoringEnabled = true),
            fakeRepository(StreamStatus.OFFLINE)
        )

        viewModel.save("chan", "id", "sec")
        advanceUntilIdle()

        assertEquals(SaveFlowState.Connected, viewModel.saveFlowState.value)
    }

    @Test
    fun saveWithMonitoringOnAndFailureGoesConnectFailed() = runTest(testDispatcher.scheduler) {
        val viewModel = buildViewModel(
            fakeSettingsStore(monitoringEnabled = true),
            fakeRepository(StreamStatus.CONNECTION_ISSUE)
        )

        viewModel.save("chan", "id", "sec")
        advanceUntilIdle()

        assertEquals(SaveFlowState.ConnectFailed, viewModel.saveFlowState.value)
    }

    @Test
    fun saveWithMonitoringOffSkipsConnectFlowAndEmitsToastResult() = runTest(testDispatcher.scheduler) {
        val repository = fakeRepository(StreamStatus.LIVE)
        val viewModel = buildViewModel(fakeSettingsStore(monitoringEnabled = false), repository)

        val results = mutableListOf<Result<Unit>>()
        val job = launch { viewModel.saveResults.collect { results.add(it) } }

        viewModel.save("chan", "id", "sec")
        advanceUntilIdle()
        job.cancel()

        assertEquals(SaveFlowState.Idle, viewModel.saveFlowState.value)
        assertEquals(1, results.size)
        assertTrue(results[0].isSuccess)
        coVerify(exactly = 0) { repository.checkNow() }
    }

    @Test
    fun persistFailureEmitsToastAndSkipsConnectFlow() = runTest(testDispatcher.scheduler) {
        val store = fakeSettingsStore(monitoringEnabled = true)
        coEvery { store.updateChannelName(any()) } throws RuntimeException("disk full")
        val repository = fakeRepository(StreamStatus.LIVE)
        val viewModel = buildViewModel(store, repository)

        val results = mutableListOf<Result<Unit>>()
        val job = launch { viewModel.saveResults.collect { results.add(it) } }

        viewModel.save("chan", "id", "sec")
        advanceUntilIdle()
        job.cancel()

        assertEquals(SaveFlowState.Idle, viewModel.saveFlowState.value)
        assertEquals(1, results.size)
        assertTrue(results[0].isFailure)
        coVerify(exactly = 0) { repository.checkNow() }
    }

    @Test
    fun acknowledgeConnectFailedReturnsToIdle() = runTest(testDispatcher.scheduler) {
        val viewModel = buildViewModel(
            fakeSettingsStore(monitoringEnabled = true),
            fakeRepository(StreamStatus.CONNECTION_ISSUE)
        )
        viewModel.save("chan", "id", "sec")
        advanceUntilIdle()
        assertEquals(SaveFlowState.ConnectFailed, viewModel.saveFlowState.value)

        viewModel.acknowledgeConnectFailed()

        assertEquals(SaveFlowState.Idle, viewModel.saveFlowState.value)
    }

    @Test
    fun onConnectedNavigationHandledReturnsToIdle() = runTest(testDispatcher.scheduler) {
        val viewModel = buildViewModel(
            fakeSettingsStore(monitoringEnabled = true),
            fakeRepository(StreamStatus.LIVE)
        )
        viewModel.save("chan", "id", "sec")
        advanceUntilIdle()
        assertEquals(SaveFlowState.Connected, viewModel.saveFlowState.value)

        viewModel.onConnectedNavigationHandled()

        assertEquals(SaveFlowState.Idle, viewModel.saveFlowState.value)
    }
}
```

- [ ] **Step 3: Run the ViewModel tests**

Run: `./gradlew testDebugUnitTest --tests "com.example.twitchnetworknotifier.ui.settings.SettingsViewModelTest"`
Expected: ALL PASS (7 tests). Note: written after the implementation in this task because the ViewModel rewrite is one cohesive unit; the tests still gate the commit.

- [ ] **Step 4: Run the whole unit test suite**

Run: `./gradlew testDebugUnitTest`
Expected: ALL PASS (repository tests + ViewModel tests + ExampleUnitTest).

- [ ] **Step 5: Commit**

```bash
git add app/src/main/java/com/example/twitchnetworknotifier/ui/settings/SettingsViewModel.kt \
        app/src/test/java/com/example/twitchnetworknotifier/ui/settings/SettingsViewModelTest.kt
git commit -m "feat: SettingsViewModel connect flow with SaveFlowState machine

Co-Authored-By: Claude Opus 4.8 <noreply@anthropic.com>"
```

---

### Task 5: `SettingsFragment` dialogs + strings + navigation

**Files:**
- Modify: `app/src/main/res/values/strings.xml`
- Modify: `app/src/main/java/com/example/twitchnetworknotifier/ui/settings/SettingsFragment.kt`

**Interfaces:**
- Consumes: `viewModel.saveFlowState: StateFlow<SaveFlowState>`, `viewModel.acknowledgeConnectFailed()`, `viewModel.onConnectedNavigationHandled()` from Task 4.
- Produces: user-visible dialogs; `popBackStack()` to Home after the Connected countdown.

- [ ] **Step 1: Add dialog strings**

In `app/src/main/res/values/strings.xml`, add alongside the existing `dialog_*` strings:

```xml
    <string name="dialog_connecting">Connecting…</string>
    <string name="dialog_connected_title">Connected</string>
    <string name="dialog_connected_countdown">Returning to Home in %1$d…</string>
    <string name="dialog_connect_failed_title">Could not connect</string>
    <string name="dialog_connect_failed_message">Could not connect — check your credentials.</string>
    <string name="dialog_ok">OK</string>
```

- [ ] **Step 2: Render dialogs from state in the Fragment**

Replace `SettingsFragment.kt` in full:

```kotlin
package com.example.twitchnetworknotifier.ui.settings

import android.app.AlertDialog
import android.os.Bundle
import android.os.CountDownTimer
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Toast
import androidx.fragment.app.Fragment
import androidx.fragment.app.viewModels
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.navigation.fragment.findNavController
import com.example.twitchnetworknotifier.R
import com.example.twitchnetworknotifier.databinding.FragmentSettingsBinding
import com.example.twitchnetworknotifier.ui.settings.SettingsViewModel.SaveFlowState
import kotlinx.coroutines.launch

class SettingsFragment : Fragment() {

    private var _binding: FragmentSettingsBinding? = null
    private val binding get() = _binding!!

    private val viewModel: SettingsViewModel by viewModels()

    private var flowDialog: AlertDialog? = null
    private var countdownTimer: CountDownTimer? = null

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        _binding = FragmentSettingsBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        viewLifecycleOwner.lifecycleScope.launch {
            viewLifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
                val settings = viewModel.loadSettingsOnce()
                binding.editChannelName.setText(settings.channelName)
                binding.editClientId.setText(settings.clientId)
                binding.editClientSecret.setText(settings.clientSecret)
            }
        }

        viewLifecycleOwner.lifecycleScope.launch {
            viewLifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
                viewModel.isSaving.collect { saving ->
                    binding.progressSave.visibility = if (saving) View.VISIBLE else View.GONE
                    binding.buttonSave.visibility = if (saving) View.INVISIBLE else View.VISIBLE
                    binding.buttonSave.isEnabled = !saving
                }
            }
        }

        viewLifecycleOwner.lifecycleScope.launch {
            viewLifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
                viewModel.saveResults.collect { result ->
                    val message = if (result.isSuccess) {
                        "Saved"
                    } else {
                        val reason = result.exceptionOrNull()?.message?.takeIf { it.isNotBlank() }
                        if (reason != null) "Save failed: $reason" else "Save failed"
                    }
                    Toast.makeText(requireContext(), message, Toast.LENGTH_LONG).show()
                }
            }
        }

        viewLifecycleOwner.lifecycleScope.launch {
            viewLifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
                viewModel.saveFlowState.collect { state -> renderSaveFlowState(state) }
            }
        }

        binding.buttonSave.setOnClickListener {
            viewModel.save(
                channelName = binding.editChannelName.text.toString(),
                clientId = binding.editClientId.text.toString(),
                clientSecret = binding.editClientSecret.text.toString()
            )
        }
    }

    // Dialogs are derived from state so a configuration change re-shows the
    // right dialog (the countdown restarting on rotation is accepted).
    private fun renderSaveFlowState(state: SaveFlowState) {
        clearFlowDialog()
        when (state) {
            SaveFlowState.Idle -> Unit
            SaveFlowState.Connecting -> {
                flowDialog = AlertDialog.Builder(requireContext())
                    .setMessage(R.string.dialog_connecting)
                    .setCancelable(false)
                    .show()
            }
            SaveFlowState.Connected -> showConnectedCountdown()
            SaveFlowState.ConnectFailed -> {
                flowDialog = AlertDialog.Builder(requireContext())
                    .setTitle(R.string.dialog_connect_failed_title)
                    .setMessage(R.string.dialog_connect_failed_message)
                    .setCancelable(false)
                    .setPositiveButton(R.string.dialog_ok) { _, _ ->
                        viewModel.acknowledgeConnectFailed()
                    }
                    .show()
            }
        }
    }

    private fun showConnectedCountdown() {
        val dialog = AlertDialog.Builder(requireContext())
            .setTitle(R.string.dialog_connected_title)
            .setMessage(getString(R.string.dialog_connected_countdown, 3))
            .setCancelable(false)
            .show()
        flowDialog = dialog

        countdownTimer = object : CountDownTimer(3_000L, 1_000L) {
            override fun onTick(millisUntilFinished: Long) {
                val secondsLeft = ((millisUntilFinished + 999) / 1_000).toInt()
                dialog.setMessage(getString(R.string.dialog_connected_countdown, secondsLeft))
            }

            override fun onFinish() {
                // Reset state BEFORE navigating so a re-render can't pop twice.
                viewModel.onConnectedNavigationHandled()
                clearFlowDialog()
                findNavController().popBackStack()
            }
        }.start()
    }

    private fun clearFlowDialog() {
        countdownTimer?.cancel()
        countdownTimer = null
        flowDialog?.dismiss()
        flowDialog = null
    }

    override fun onDestroyView() {
        clearFlowDialog()
        super.onDestroyView()
        _binding = null
    }
}
```

- [ ] **Step 3: Build and run unit tests**

Run: `./gradlew assembleDebug testDebugUnitTest`
Expected: BUILD SUCCESSFUL, all tests pass.

- [ ] **Step 4: Commit**

```bash
git add app/src/main/res/values/strings.xml \
        app/src/main/java/com/example/twitchnetworknotifier/ui/settings/SettingsFragment.kt
git commit -m "feat: Connecting/Connected/error dialogs on Settings save

Co-Authored-By: Claude Opus 4.8 <noreply@anthropic.com>"
```

---

### Task 6: Manual verification on device/emulator

**Files:** none (verification only).

**Interfaces:** consumes the whole feature end-to-end.

- [ ] **Step 1: Install and launch**

Run: `./gradlew installDebug` then open the app on the connected device/emulator.

- [ ] **Step 2: Verify each flow manually**

With the TEMP intervals (5s poll, 2/4/6s backoff) this is quick:

1. **Monitoring OFF + save** → only the "Saved" toast; no dialog; stays on Settings.
2. **Monitoring ON + valid credentials + save** → "Connecting…" appears, then "Connected" with a 3→2→1 countdown, then auto-return to Home; history shows the transition.
3. **Monitoring ON + wrong client secret + save** → "Connecting…", then the "Could not connect" dialog; OK keeps you on Settings; Home history shows one CONNECTION_ISSUE row.
4. **Stale-retry scenario:** save wrong credentials, and while the background retry cycle is running re-save correct credentials → "Connected" flow proceeds promptly (no multi-second hang waiting out backoff), and NO stale connection-issue notification pops after the recovery.
5. **Rotation:** rotate during "Connecting…" and during the countdown → the correct dialog re-appears (countdown restarts; single pop to Home).

- [ ] **Step 3: Report results**

Report each scenario's outcome to the user (pass/fail with what was observed) before any merge/PR step.
