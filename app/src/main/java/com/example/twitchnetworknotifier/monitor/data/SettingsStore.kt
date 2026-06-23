package com.example.twitchnetworknotifier.monitor.data

import android.content.Context
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

private val Context.settingsDataStore by preferencesDataStore(name = "settings")

class SettingsStore(private val context: Context) {

    private object Keys {
        val CHANNEL_NAME = stringPreferencesKey("channel_name")
        val CLIENT_ID = stringPreferencesKey("client_id")
        val CLIENT_SECRET = stringPreferencesKey("client_secret")
        val MONITORING_ENABLED = booleanPreferencesKey("monitoring_enabled")
    }

    val settings: Flow<Settings> = context.settingsDataStore.data.map { prefs ->
        Settings(
            channelName = prefs[Keys.CHANNEL_NAME] ?: "",
            clientId = prefs[Keys.CLIENT_ID] ?: "",
            clientSecret = prefs[Keys.CLIENT_SECRET] ?: "",
            monitoringEnabled = prefs[Keys.MONITORING_ENABLED] ?: false
        )
    }

    suspend fun updateChannelName(value: String) {
        context.settingsDataStore.edit { it[Keys.CHANNEL_NAME] = value }
    }

    suspend fun updateClientId(value: String) {
        context.settingsDataStore.edit { it[Keys.CLIENT_ID] = value }
    }

    suspend fun updateClientSecret(value: String) {
        context.settingsDataStore.edit { it[Keys.CLIENT_SECRET] = value }
    }

    suspend fun setMonitoringEnabled(value: Boolean) {
        context.settingsDataStore.edit { it[Keys.MONITORING_ENABLED] = value }
    }
}
