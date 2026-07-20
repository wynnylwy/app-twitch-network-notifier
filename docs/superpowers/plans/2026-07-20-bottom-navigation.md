# Bottom Navigation Bar Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Replace the toolbar's "3 dots" overflow menu (currently the sole way to reach Settings) with a `BottomNavigationView` that switches between Home (`MainFragment`) and Settings (`SettingsFragment`), highlights the active tab, and safely no-ops when the active tab is re-tapped.

**Architecture:** Single-Activity app using Jetpack Navigation Component. `MainActivity` hosts a `NavHostFragment` (`nav_graph.xml`, destinations `mainFragment` and `settingsFragment`, unchanged). Wire a `BottomNavigationView` to the existing `NavController` via `NavigationUI`'s `setupWithNavController` extension — this is the standard library-provided integration and gives correct tab highlighting, state save/restore across tab switches, and safe re-tap behavior for free. Remove the now-redundant overflow menu (`menu_main.xml`, `action_settings`) and the `MainActivity` code that managed it.

**Tech Stack:** Kotlin, AndroidX Navigation Component (`navigation-fragment-ktx` / `navigation-ui-ktx` 2.9.8, already a dependency), Material Components (`com.google.android.material`, 1.14.0, already a dependency — `BottomNavigationView` auto-adopts Material 3 styling under the app's `Theme.Material3.DayNight.NoActionBar` theme), ViewBinding, Espresso for instrumented UI testing.

## Global Constraints

- minSdk 24, targetSdk 36 (from `app/build.gradle.kts`) — any API used must be available at minSdk 24. `BottomNavigationView` and `NavigationUI.setupWithNavController` have no such floor.
- Follow existing file split: `activity_main.xml` owns the `CoordinatorLayout`/`AppBarLayout`/toolbar; `content_main.xml` (included, no `android:id` on the `<include>`) owns the content area below the toolbar. New views for this feature belong in `content_main.xml`, not `activity_main.xml` — see Task 1 rationale.
- Reuse existing string resources where they already say the right thing (`settings_fragment_label` = "Settings") instead of duplicating.
- No Compose, no filled/outline icon state variants, no more than two tabs (per approved spec `docs/superpowers/specs/2026-07-20-bottom-navigation-design.md`).

---

### Task 1: Add bottom nav resources, layout, and MainActivity wiring

**Files:**
- Create: `app/src/main/res/drawable/ic_home_24.xml`
- Create: `app/src/main/res/drawable/ic_settings_24.xml`
- Create: `app/src/main/res/menu/menu_bottom_nav.xml`
- Modify: `app/src/main/res/values/strings.xml`
- Modify: `app/src/main/res/layout/content_main.xml`
- Modify: `app/src/main/java/com/example/twitchnetworknotifier/MainActivity.kt`
- Delete: `app/src/main/res/menu/menu_main.xml`

**Interfaces:**
- Produces: view id `R.id.bottom_nav` (a `com.google.android.material.bottomnavigation.BottomNavigationView`), reachable via `findViewById<BottomNavigationView>(R.id.bottom_nav)` from `MainActivity` (consistent with the existing `findFragmentById(R.id.nav_host_fragment_content_main)` pattern — `content_main.xml`'s views are not exposed through `ActivityMainBinding` since the `<include>` has no id). Menu item ids `R.id.mainFragment` / `R.id.settingsFragment` intentionally reuse the nav graph's destination ids so `NavigationUI` can resolve them directly. Task 2's test consumes this id and these two menu item ids.

**Note on placement:** The design doc said "add a `BottomNavigationView` to `activity_main.xml`". Implementing it, the constraint `nav_host_fragment_content_main`'s bottom → `bottom_nav`'s top only resolves within a single `ConstraintLayout` — so `bottom_nav` must live in `content_main.xml` alongside the nav host, not in `activity_main.xml`'s outer `CoordinatorLayout`. `activity_main.xml` itself needs no changes. Same outcome the spec described (a bottom bar the content area is properly constrained above), correct file for it to work.

- [ ] **Step 1: Create the home icon**

`app/src/main/res/drawable/ic_home_24.xml`:

```xml
<?xml version="1.0" encoding="utf-8"?>
<vector xmlns:android="http://schemas.android.com/apk/res/android"
    android:width="24dp"
    android:height="24dp"
    android:viewportWidth="24"
    android:viewportHeight="24">
    <path
        android:fillColor="#FF000000"
        android:pathData="M10,20v-6h4v6h5v-8h3L12,3 2,12h3v8z"/>
</vector>
```

(No `android:tint` — `BottomNavigationView`'s own `itemIconTint` recolors it for selected/unselected state.)

- [ ] **Step 2: Create the settings icon**

`app/src/main/res/drawable/ic_settings_24.xml`:

```xml
<?xml version="1.0" encoding="utf-8"?>
<vector xmlns:android="http://schemas.android.com/apk/res/android"
    android:width="24dp"
    android:height="24dp"
    android:viewportWidth="24"
    android:viewportHeight="24">
    <path
        android:fillColor="#FF000000"
        android:pathData="M19.14,12.94c0.04,-0.3 0.06,-0.61 0.06,-0.94c0,-0.32 -0.02,-0.64 -0.07,-0.94l2.03,-1.58c0.18,-0.14 0.23,-0.41 0.12,-0.61l-1.92,-3.32c-0.12,-0.22 -0.37,-0.29 -0.59,-0.22l-2.39,0.96c-0.5,-0.38 -1.03,-0.7 -1.62,-0.94L14.4,2.81c-0.04,-0.24 -0.24,-0.41 -0.48,-0.41h-3.84c-0.24,0 -0.43,0.17 -0.47,0.41L9.25,5.35C8.66,5.59 8.12,5.92 7.63,6.29L5.24,5.33c-0.22,-0.08 -0.47,0 -0.59,0.22L2.74,8.87C2.62,9.08 2.66,9.34 2.86,9.48l2.03,1.58C4.84,11.36 4.8,11.69 4.8,12s0.02,0.64 0.07,0.94l-2.03,1.58c-0.18,0.14 -0.23,0.41 -0.12,0.61l1.92,3.32c0.12,0.22 0.37,0.29 0.59,0.22l2.39,-0.96c0.5,0.38 1.03,0.7 1.62,0.94l0.36,2.54c0.05,0.24 0.24,0.41 0.48,0.41h3.84c0.24,0 0.44,-0.17 0.47,-0.41l0.36,-2.54c0.59,-0.24 1.13,-0.56 1.62,-0.94l2.39,0.96c0.22,0.08 0.47,0 0.59,-0.22l1.92,-3.32c0.12,-0.22 0.07,-0.47 -0.12,-0.61L19.14,12.94zM12,15.6c-1.98,0 -3.6,-1.62 -3.6,-3.6s1.62,-3.6 3.6,-3.6s3.6,1.62 3.6,3.6S13.98,15.6 12,15.6z"/>
</vector>
```

- [ ] **Step 3: Add the Home tab string, remove the now-unused overflow string**

In `app/src/main/res/values/strings.xml`, replace:

```xml
    <string name="action_settings">Settings</string>
```

with:

```xml
    <string name="nav_home">Home</string>
```

(leave `settings_fragment_label` — "Settings" — as is; it's reused for the Settings tab label.)

- [ ] **Step 4: Create the bottom nav menu resource**

`app/src/main/res/menu/menu_bottom_nav.xml`:

```xml
<?xml version="1.0" encoding="utf-8"?>
<menu xmlns:android="http://schemas.android.com/apk/res/android">
    <item
        android:id="@+id/mainFragment"
        android:icon="@drawable/ic_home_24"
        android:title="@string/nav_home" />
    <item
        android:id="@+id/settingsFragment"
        android:icon="@drawable/ic_settings_24"
        android:title="@string/settings_fragment_label" />
</menu>
```

- [ ] **Step 5: Add the BottomNavigationView to content_main.xml**

Replace `app/src/main/res/layout/content_main.xml` entirely with:

```xml
<?xml version="1.0" encoding="utf-8"?>
<androidx.constraintlayout.widget.ConstraintLayout xmlns:android="http://schemas.android.com/apk/res/android"
    xmlns:app="http://schemas.android.com/apk/res-auto"
    android:layout_width="match_parent"
    android:layout_height="match_parent"
    app:layout_behavior="@string/appbar_scrolling_view_behavior">

    <androidx.fragment.app.FragmentContainerView
        android:id="@+id/nav_host_fragment_content_main"
        android:name="androidx.navigation.fragment.NavHostFragment"
        android:layout_width="0dp"
        android:layout_height="0dp"
        app:defaultNavHost="true"
        app:layout_constraintBottom_toTopOf="@id/bottom_nav"
        app:layout_constraintEnd_toEndOf="parent"
        app:layout_constraintStart_toStartOf="parent"
        app:layout_constraintTop_toTopOf="parent"
        app:navGraph="@navigation/nav_graph" />

    <com.google.android.material.bottomnavigation.BottomNavigationView
        android:id="@+id/bottom_nav"
        android:layout_width="0dp"
        android:layout_height="wrap_content"
        app:menu="@menu/menu_bottom_nav"
        app:layout_constraintBottom_toBottomOf="parent"
        app:layout_constraintEnd_toEndOf="parent"
        app:layout_constraintStart_toStartOf="parent" />

</androidx.constraintlayout.widget.ConstraintLayout>
```

(Only the `FragmentContainerView`'s bottom constraint changed, from `parent` to `@id/bottom_nav`; the new `BottomNavigationView` block was added.)

- [ ] **Step 6: Wire the bottom nav in MainActivity, remove the overflow menu code**

Replace `app/src/main/java/com/example/twitchnetworknotifier/MainActivity.kt` entirely with:

```kotlin
package com.example.twitchnetworknotifier

import android.os.Bundle
import androidx.appcompat.app.AppCompatActivity
import androidx.activity.enableEdgeToEdge
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.navigation.findNavController
import androidx.navigation.fragment.NavHostFragment
import androidx.navigation.ui.AppBarConfiguration
import androidx.navigation.ui.navigateUp
import androidx.navigation.ui.setupActionBarWithNavController
import androidx.navigation.ui.setupWithNavController
import com.example.twitchnetworknotifier.databinding.ActivityMainBinding
import com.google.android.material.bottomnavigation.BottomNavigationView

class MainActivity : AppCompatActivity() {

    private lateinit var appBarConfiguration: AppBarConfiguration
    private lateinit var binding: ActivityMainBinding

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()

        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        ViewCompat.setOnApplyWindowInsetsListener(binding.main) { v, insets ->
            val systemBars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            v.setPadding(systemBars.left, systemBars.top, systemBars.right, systemBars.bottom)
            insets
        }
        setSupportActionBar(binding.toolbar)

        val navHostFragment =
            supportFragmentManager.findFragmentById(R.id.nav_host_fragment_content_main) as NavHostFragment
        val navController = navHostFragment.navController

        appBarConfiguration = AppBarConfiguration(setOf(R.id.mainFragment, R.id.settingsFragment))
        setupActionBarWithNavController(navController, appBarConfiguration)

        findViewById<BottomNavigationView>(R.id.bottom_nav).setupWithNavController(navController)
    }

    override fun onSupportNavigateUp(): Boolean {
        val navController = findNavController(R.id.nav_host_fragment_content_main)
        return navController.navigateUp(appBarConfiguration)
                || super.onSupportNavigateUp()
    }
}
```

Changes from the original: removed `optionsMenu` field, `onCreateOptionsMenu`, `onOptionsItemSelected`, and the `addOnDestinationChangedListener` block (all existed only to manage the overflow item); removed now-unused `android.view.Menu`/`android.view.MenuItem` imports; widened `AppBarConfiguration` to treat both fragments as top-level (no back arrow on either); added the `setupWithNavController` call and its two imports.

- [ ] **Step 7: Delete the obsolete overflow menu resource**

```bash
git rm app/src/main/res/menu/menu_main.xml
```

- [ ] **Step 8: Build and confirm no compile/resource errors**

Run: `./gradlew assembleDebug`
Expected: `BUILD SUCCESSFUL`

- [ ] **Step 9: Commit**

```bash
git add app/src/main/res/drawable/ic_home_24.xml app/src/main/res/drawable/ic_settings_24.xml \
  app/src/main/res/menu/menu_bottom_nav.xml app/src/main/res/values/strings.xml \
  app/src/main/res/layout/content_main.xml app/src/main/java/com/example/twitchnetworknotifier/MainActivity.kt
git commit -m "feat: replace overflow menu with bottom navigation bar"
```

---

### Task 2: Instrumented test + manual verification

**Files:**
- Create: `app/src/androidTest/java/com/example/twitchnetworknotifier/MainActivityTest.kt`

**Interfaces:**
- Consumes: `R.id.bottom_nav`, `R.id.mainFragment`, `R.id.settingsFragment` (from Task 1), `R.id.switch_monitoring` (existing, `fragment_main.xml`), `R.id.edit_channel_name` (existing, `fragment_settings.xml`).

- [ ] **Step 1: Write the instrumented test**

`app/src/androidTest/java/com/example/twitchnetworknotifier/MainActivityTest.kt`:

```kotlin
package com.example.twitchnetworknotifier

import androidx.test.core.app.ActivityScenario
import androidx.test.espresso.Espresso.onView
import androidx.test.espresso.action.ViewActions.click
import androidx.test.espresso.assertion.ViewAssertions.matches
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

            onView(withId(R.id.settingsFragment)).perform(click())
            onView(withId(R.id.edit_channel_name)).check(matches(isDisplayed()))

            onView(withId(R.id.mainFragment)).perform(click())
            onView(withId(R.id.switch_monitoring)).check(matches(isDisplayed()))
        }
    }

    @Test
    fun reselectingActiveTabDoesNotCrashOrReload() {
        ActivityScenario.launch(MainActivity::class.java).use {
            onView(withId(R.id.mainFragment)).perform(click())
            onView(withId(R.id.mainFragment)).perform(click())
            onView(withId(R.id.switch_monitoring)).check(matches(isDisplayed()))

            onView(withId(R.id.settingsFragment)).perform(click())
            onView(withId(R.id.settingsFragment)).perform(click())
            onView(withId(R.id.edit_channel_name)).check(matches(isDisplayed()))
        }
    }
}
```

- [ ] **Step 2: Run the instrumented tests**

Run: `./gradlew connectedDebugAndroidTest --tests "com.example.twitchnetworknotifier.MainActivityTest"`
Expected: `BUILD SUCCESSFUL`, both tests pass. (Requires a connected device or running emulator — if none is available in this environment, use the `run` skill to launch the app on an emulator and perform Step 3 manually instead; don't skip verification entirely.)

- [ ] **Step 3: Manual verification on a running app**

Using the `run` skill, launch the app and confirm:
- The toolbar no longer shows a "3 dots" overflow button.
- A bottom bar with "Home" and "Settings" tabs is visible, Home selected by default.
- Tapping Settings switches screens and highlights the Settings tab; tapping Home returns and highlights Home.
- Re-tapping the already-active tab does nothing visible (no flicker, no crash).
- On the Settings screen, completing a successful save (existing countdown-dialog flow) still auto-returns to Home with the Home tab correctly highlighted.

- [ ] **Step 4: Commit**

```bash
git add app/src/androidTest/java/com/example/twitchnetworknotifier/MainActivityTest.kt
git commit -m "test: verify bottom nav tab switching and safe reselect"
```
