package com.example.twitchnetworknotifier.monitor.data

import android.content.Context
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import com.example.twitchnetworknotifier.monitor.model.StatusEvent
import com.example.twitchnetworknotifier.monitor.model.StreamStatus
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import org.json.JSONArray
import org.json.JSONObject

private val Context.historyDataStore by preferencesDataStore(name = "history")

class HistoryStore(private val context: Context) {

    private object Keys {
        val EVENTS_JSON = stringPreferencesKey("events_json")
    }

    companion object {
        private const val MAX_EVENTS = 50
        private const val KEY_TIMESTAMP = "ts"
        private const val KEY_FROM = "from"
        private const val KEY_TO = "to"
    }

    val events: Flow<List<StatusEvent>> = context.historyDataStore.data.map { prefs ->
        parseEvents(prefs[Keys.EVENTS_JSON] ?: "[]")
    }

    suspend fun addEvent(event: StatusEvent) {
        context.historyDataStore.edit { prefs ->
            val current = parseEvents(prefs[Keys.EVENTS_JSON] ?: "[]")
            val updated = (listOf(event) + current).take(MAX_EVENTS)
            prefs[Keys.EVENTS_JSON] = serializeEvents(updated)
        }
    }

    suspend fun clear() {
        context.historyDataStore.edit { it.clear() }
    }

    private fun parseEvents(json: String): List<StatusEvent> {
        val array = JSONArray(json)
        return (0 until array.length()).map { index ->
            val obj = array.getJSONObject(index)
            StatusEvent(
                timestampMillis = obj.getLong(KEY_TIMESTAMP),
                fromState = StreamStatus.valueOf(obj.getString(KEY_FROM)),
                toState = StreamStatus.valueOf(obj.getString(KEY_TO))
            )
        }
    }

    private fun serializeEvents(events: List<StatusEvent>): String {
        val array = JSONArray()
        events.forEach { event ->
            val obj = JSONObject()
            obj.put(KEY_TIMESTAMP, event.timestampMillis)
            obj.put(KEY_FROM, event.fromState.name)
            obj.put(KEY_TO, event.toState.name)
            array.put(obj)
        }
        return array.toString()
    }
}
