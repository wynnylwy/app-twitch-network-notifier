package com.example.twitchnetworknotifier.ui.main

import androidx.fragment.app.testing.launchFragmentInContainer
import androidx.test.espresso.Espresso.onView
import androidx.test.espresso.action.ViewActions.click
import androidx.test.espresso.assertion.ViewAssertions.matches
import androidx.test.espresso.matcher.ViewMatchers.hasChildCount
import androidx.test.espresso.matcher.ViewMatchers.isChecked
import androidx.test.espresso.matcher.ViewMatchers.isDisplayed
import androidx.test.espresso.matcher.ViewMatchers.isNotChecked
import androidx.test.espresso.matcher.ViewMatchers.withId
import androidx.test.espresso.matcher.ViewMatchers.withText
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.example.twitchnetworknotifier.R
import com.example.twitchnetworknotifier.monitor.data.HistoryStore
import com.example.twitchnetworknotifier.monitor.data.SettingsStore
import com.example.twitchnetworknotifier.monitor.model.StatusEvent
import com.example.twitchnetworknotifier.monitor.model.StreamStatus
import kotlinx.coroutines.runBlocking
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class MainFragmentTest {

    @Before
    fun clearState() = runBlocking {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        SettingsStore(context).setMonitoringEnabled(false)
        HistoryStore(context).clear()
    }

    @Test
    fun togglingOffShowsConfirmationDialog_noKeepsItOn() {
        launchFragmentInContainer<MainFragment>(themeResId = R.style.Theme_TwitchNetworkNotifier)

        onView(withId(R.id.switch_monitoring)).perform(click())
        onView(withId(R.id.switch_monitoring)).check(matches(isChecked()))

        onView(withId(R.id.switch_monitoring)).perform(click())
        onView(withText(R.string.dialog_toggle_off_title)).check(matches(isDisplayed()))
        onView(withText(R.string.dialog_no)).perform(click())

        onView(withId(R.id.switch_monitoring)).check(matches(isChecked()))
    }

    @Test
    fun togglingOffShowsConfirmationDialog_yesTurnsItOff() {
        launchFragmentInContainer<MainFragment>(themeResId = R.style.Theme_TwitchNetworkNotifier)

        onView(withId(R.id.switch_monitoring)).perform(click())
        onView(withId(R.id.switch_monitoring)).perform(click())
        onView(withText(R.string.dialog_toggle_off_title)).check(matches(isDisplayed()))
        onView(withText(R.string.dialog_yes)).perform(click())

        onView(withId(R.id.switch_monitoring)).check(matches(isNotChecked()))
    }

    @Test
    fun historyListRendersSeededEvents() = runBlocking {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val historyStore = HistoryStore(context)
        historyStore.addEvent(StatusEvent(1_000L, StreamStatus.UNKNOWN, StreamStatus.LIVE))
        historyStore.addEvent(StatusEvent(2_000L, StreamStatus.LIVE, StreamStatus.OFFLINE))

        launchFragmentInContainer<MainFragment>(themeResId = R.style.Theme_TwitchNetworkNotifier)

        onView(withId(R.id.recycler_history)).check(matches(hasChildCount(2)))
    }
}
