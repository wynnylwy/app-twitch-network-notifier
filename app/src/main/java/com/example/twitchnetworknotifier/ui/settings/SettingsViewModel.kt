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
        // Defense-in-depth: the modal dialogs already block the Save button mid-flow,
        // but never allow a second connect flow to start concurrently.
        if (_saveFlowState.value != SaveFlowState.Idle) return
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
