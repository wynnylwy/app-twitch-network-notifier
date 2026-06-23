package com.example.twitchnetworknotifier.monitor

import android.content.Context
import com.example.twitchnetworknotifier.monitor.data.HistoryStore
import com.example.twitchnetworknotifier.monitor.data.SettingsStore
import com.example.twitchnetworknotifier.monitor.network.TwitchApiClientImpl

object AppContainer {
    @Volatile
    private var repository: StreamRepository? = null

    fun getRepository(context: Context): StreamRepository {
        return repository ?: synchronized(this) {
            repository ?: StreamRepository(
                settingsStore = SettingsStore(context.applicationContext),
                historyStore = HistoryStore(context.applicationContext),
                apiClient = TwitchApiClientImpl()
            ).also { repository = it }
        }
    }
}
