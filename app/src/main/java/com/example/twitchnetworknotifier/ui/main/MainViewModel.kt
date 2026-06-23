package com.example.twitchnetworknotifier.ui.main

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.example.twitchnetworknotifier.monitor.AppContainer
import com.example.twitchnetworknotifier.monitor.model.StatusEvent
import com.example.twitchnetworknotifier.monitor.model.StreamStatus
import kotlinx.coroutines.flow.SharingStarted.Companion.WhileSubscribed
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

class MainViewModel(application: Application) : AndroidViewModel(application) {

    private val repository = AppContainer.getRepository(application)

    val monitoringEnabled: StateFlow<Boolean> = repository.monitoringEnabled
        .stateIn(viewModelScope, WhileSubscribed(5_000), false)

    val currentStatus: StateFlow<StreamStatus> = repository.currentStatus

    val history: StateFlow<List<StatusEvent>> = repository.history
        .stateIn(viewModelScope, WhileSubscribed(5_000), emptyList())

    fun setMonitoringEnabled(enabled: Boolean) {
        viewModelScope.launch { repository.setMonitoringEnabled(enabled) }
    }
}
