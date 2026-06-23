package com.example.twitchnetworknotifier.ui.settings

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.example.twitchnetworknotifier.monitor.data.Settings
import com.example.twitchnetworknotifier.monitor.data.SettingsStore
import kotlinx.coroutines.flow.SharingStarted.Companion.WhileSubscribed
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

class SettingsViewModel(application: Application) : AndroidViewModel(application) {

    private val settingsStore = SettingsStore(application)

    val settings: StateFlow<Settings> = settingsStore.settings
        .stateIn(viewModelScope, WhileSubscribed(5_000), Settings())

    fun save(channelName: String, clientId: String, clientSecret: String) {
        viewModelScope.launch {
            settingsStore.updateChannelName(channelName)
            settingsStore.updateClientId(clientId)
            settingsStore.updateClientSecret(clientSecret)
        }
    }
}
