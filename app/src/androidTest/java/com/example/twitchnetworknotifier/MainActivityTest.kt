package com.example.twitchnetworknotifier

import androidx.test.core.app.ActivityScenario
import androidx.test.espresso.Espresso.onView
import androidx.test.espresso.action.ViewActions.click
import androidx.test.espresso.assertion.ViewAssertions.matches
import androidx.test.espresso.matcher.ViewMatchers.isChecked
import androidx.test.espresso.matcher.ViewMatchers.isDisplayed
import androidx.test.espresso.matcher.ViewMatchers.withId
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.example.twitchnetworknotifier.monitor.data.HistoryStore
import com.example.twitchnetworknotifier.monitor.data.SettingsStore
import kotlinx.coroutines.runBlocking
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class MainActivityTest {

    @Before
    fun clearState() = runBlocking {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        SettingsStore(context).setMonitoringEnabled(false)
        HistoryStore(context).clear()
    }

    @Test
    fun tappingSettingsTabSwitchesScreenAndBack() {
        ActivityScenario.launch(MainActivity::class.java).use {
            onView(withId(R.id.switch_monitoring)).check(matches(isDisplayed()))
            onView(withId(R.id.mainFragment)).check(matches(isChecked()))

            onView(withId(R.id.settingsFragment)).perform(click())
            onView(withId(R.id.edit_channel_name)).check(matches(isDisplayed()))
            onView(withId(R.id.settingsFragment)).check(matches(isChecked()))

            onView(withId(R.id.mainFragment)).perform(click())
            onView(withId(R.id.switch_monitoring)).check(matches(isDisplayed()))
            onView(withId(R.id.mainFragment)).check(matches(isChecked()))
        }
    }

    @Test
    fun reselectingActiveTabDoesNotCrashOrReload() {
        ActivityScenario.launch(MainActivity::class.java).use {
            onView(withId(R.id.mainFragment)).perform(click())
            onView(withId(R.id.mainFragment)).perform(click())
            onView(withId(R.id.switch_monitoring)).check(matches(isDisplayed()))
            onView(withId(R.id.mainFragment)).check(matches(isChecked()))

            onView(withId(R.id.settingsFragment)).perform(click())
            onView(withId(R.id.settingsFragment)).perform(click())
            onView(withId(R.id.edit_channel_name)).check(matches(isDisplayed()))
            onView(withId(R.id.settingsFragment)).check(matches(isChecked()))
        }
    }
}
