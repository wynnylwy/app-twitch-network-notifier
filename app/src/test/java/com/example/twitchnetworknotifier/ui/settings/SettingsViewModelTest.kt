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
