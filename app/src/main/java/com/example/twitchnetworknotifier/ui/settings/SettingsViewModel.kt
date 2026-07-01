package com.example.twitchnetworknotifier.ui.settings

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
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

class SettingsViewModel(application: Application) : AndroidViewModel(application) {

    private val settingsStore = SettingsStore(application)

    val settings: StateFlow<Settings> = settingsStore.settings
        .stateIn(viewModelScope, WhileSubscribed(5_000), Settings())

    private val _isSaving = MutableStateFlow(false)
    val isSaving: StateFlow<Boolean> = _isSaving.asStateFlow()

    private val _saveResults = MutableSharedFlow<Result<Unit>>(extraBufferCapacity = 1)
    val saveResults: SharedFlow<Result<Unit>> = _saveResults.asSharedFlow()

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
            _saveResults.emit(result)
        }
    }
}
