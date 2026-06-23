package com.example.twitchnetworknotifier.monitor.data

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.example.twitchnetworknotifier.monitor.model.StatusEvent
import com.example.twitchnetworknotifier.monitor.model.StreamStatus
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import kotlin.test.assertEquals

@RunWith(AndroidJUnit4::class)
class HistoryStoreTest {

    @Before
    fun clearState() = runBlocking {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        HistoryStore(context).clear()
    }

    @Test
    fun addEventPrependsNewestFirst() = runBlocking {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val store = HistoryStore(context)

        store.addEvent(StatusEvent(1_000L, StreamStatus.UNKNOWN, StreamStatus.LIVE))
        store.addEvent(StatusEvent(2_000L, StreamStatus.LIVE, StreamStatus.OFFLINE))

        val events = store.events.first()
        assertEquals(2, events.size)
        assertEquals(2_000L, events[0].timestampMillis)
        assertEquals(1_000L, events[1].timestampMillis)
    }

    @Test
    fun cappedAt50Events() = runBlocking {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val store = HistoryStore(context)

        repeat(55) { index ->
            store.addEvent(StatusEvent(index.toLong(), StreamStatus.LIVE, StreamStatus.OFFLINE))
        }

        val events = store.events.first()
        assertEquals(50, events.size)
        assertEquals(54L, events[0].timestampMillis)
    }
}
