package com.example.twitchnetworknotifier.monitor.data

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Test
import org.junit.runner.RunWith
import kotlin.test.assertEquals

@RunWith(AndroidJUnit4::class)
class SettingsStoreTest {

    @Test
    fun defaultsAreEmptyAndDisabled() = runBlocking {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val store = SettingsStore(context)

        val settings = store.settings.first()

        assertEquals("", settings.channelName)
        assertEquals(false, settings.monitoringEnabled)
    }

    @Test
    fun updateChannelNameAndMonitoringEnabledPersist() = runBlocking {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val store = SettingsStore(context)

        store.updateChannelName("myChannel")
        store.setMonitoringEnabled(true)

        val settings = store.settings.first()
        assertEquals("myChannel", settings.channelName)
        assertEquals(true, settings.monitoringEnabled)
    }
}
