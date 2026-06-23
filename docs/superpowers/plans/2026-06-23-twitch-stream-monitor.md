# Twitch Stream Monitor Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Build an Android app (Kotlin, MVVM) that monitors the user's Twitch channel live status every 5 minutes via a foreground service, retries on connection failure, and sends high-priority alerts on state changes — surviving app closure and device reboot.

**Architecture:** A `StreamMonitorService` (foreground service, `START_STICKY`) owns the check/retry loop and talks to a `StreamRepository` (state machine + retry/backoff, pure Kotlin, unit-testable via a fake `TwitchApiClient`). The Repository persists settings and history via two DataStore-backed stores and exposes Kotlin `Flow`s. `MainViewModel`/`SettingsViewModel` only observe those Flows — they never run the loop themselves, so the UI can close without anything breaking. A `BootReceiver` restarts the service after reboot if monitoring was on.

**Tech Stack:** Kotlin (AGP 9.2.1 built-in Kotlin compilation — no separate Kotlin Gradle plugin, no KSP/kapt), AndroidX Lifecycle (`lifecycle-service`, `lifecycle-runtime-ktx`, `lifecycle-viewmodel-ktx`), AndroidX DataStore Preferences, OkHttp (Twitch Helix HTTP calls), `org.json` (built into Android, used for all JSON — no Moshi/Retrofit/Room), Kotlin Coroutines, RecyclerView, Material Components, Navigation Component (already present).

## Global Constraints

- minSdk 24, targetSdk 36, compileSdk 36 (release 36, minor API level 1) — from `app/build.gradle.kts`, do not change.
- No Kotlin Gradle plugin and no KSP/kapt — confirmed incompatible with this project's AGP-built-in-Kotlin setup (`org.jetbrains.kotlin.gradle.plugin.mpp.KotlinJvmAndroidCompilation.getAndroidVariant()` returns null when KSP is applied). Do not introduce Room or any annotation-processor-based library.
- Persistence is DataStore Preferences only, with `org.json` for any structured data (e.g. history list) — no SQLite/Room.
- Check interval is 5 minutes, real cadence (not WorkManager's 15-minute floor) — must run inside the foreground service's own loop.
- Retry on call failure only (not on a confirmed "offline" response): 3 retries with backoff 10s, 20s, 40s, i.e. up to 4 total attempts per check before giving up.
- Alert exactly once per state change (`UNKNOWN`/`LIVE`/`OFFLINE`/`CONNECTION_ISSUE` transitions) — no repeat alerts while a state persists across cycles.
- Exact notification copy (from spec):
  - Welcome (shown once, at toggle-on): `"Welcome to Twitch Network Notifier, your live stream status is now being monitored."`
  - Confirmed offline: `"Your live stream is offline, please check."`
  - Connection issue (all retries failed): `"Could not verify your stream status (connection issue)."`
  - Back live: `"Your streaming is live now."`
  - Toggle-off confirmation dialog: `"You sure to off monitoring?"` with Yes/No.
- Two notification channels: low-importance silent "Monitoring" channel for the persistent foreground notification; high-importance "Alerts" channel (sound + heads-up) for the four messages above.
- `POST_NOTIFICATIONS` is requested at first toggle-on, not at app launch.
- Service uses `START_STICKY`; `BootReceiver` restarts it on `BOOT_COMPLETED` if the persisted toggle was on.
- Package/namespace: `com.example.twitchnetworknotifier` (existing, do not change).
- This repository is not yet a git repository — Task 1 initializes it so subsequent tasks can commit.

---

## Task 1: Initialize git, fix pre-existing compileSdk/AAR-metadata bug, verify baseline build

**Files:**
- Create: `.git/` (via `git init`)
- Modify: `app/build.gradle.kts`

**Interfaces:** None (no app code yet).

The template as scaffolded does not actually produce an installable APK: `androidx.core:core-ktx:1.19.0` (already pinned in `gradle/libs.versions.toml`) requires compiling against API 37, but `app/build.gradle.kts` pins `compileSdk` to release 36 (minor API level 1). `./gradlew :app:compileDebugKotlin` doesn't catch this (it doesn't run AAR metadata checks), but `./gradlew :app:assembleDebug` — which later tasks rely on for verification — fails with `CheckAarMetadataWorkAction` errors. Verified by reproducing the failure against the untouched template and confirming bumping `compileSdk` to release 37 (no `minorApiLevel`) resolves it cleanly with no other side effects.

- [ ] **Step 1: Initialize git repository**

```bash
git init
git add -A
git commit -m "chore: initial Android Studio template"
```

Expected: commit succeeds, `git log --oneline` shows one commit.

- [ ] **Step 2: Reproduce the pre-existing AAR metadata failure**

```bash
./gradlew :app:assembleDebug -q
```

Expected: `BUILD FAILED` with `CheckAarMetadataWorkAction` reporting that `androidx.core:core` and `androidx.core:core-ktx` require compiling against API 37, while `:app` compiles against android-36.1.

- [ ] **Step 3: Fix it by bumping compileSdk to 37**

In `app/build.gradle.kts`, replace:

```kotlin
    compileSdk {
        version = release(36) {
            minorApiLevel = 1
        }
    }
```

with:

```kotlin
    compileSdk {
        version = release(37)
    }
```

- [ ] **Step 4: Verify the fix**

```bash
./gradlew :app:assembleDebug -q
```

Expected: no output, exit code 0. (First run may print SDK Platform 37 license/install messages if it isn't already installed locally — that's expected and one-time; the command still exits 0.)

- [ ] **Step 5: Commit**

```bash
git add app/build.gradle.kts
git commit -m "fix: bump compileSdk to 37 to satisfy core-ktx 1.19.0's AAR metadata requirement"
```

---

## Task 2: Add dependencies (version catalog + app build script)

**Files:**
- Modify: `gradle/libs.versions.toml`
- Modify: `app/build.gradle.kts`

**Interfaces:**
- Produces: Gradle dependency aliases used by every later task — `libs.androidx.lifecycle.runtime.ktx`, `libs.androidx.lifecycle.viewmodel.ktx`, `libs.androidx.lifecycle.service`, `libs.androidx.datastore.preferences`, `libs.okhttp`, `libs.androidx.recyclerview`, `libs.kotlinx.coroutines.android`, `libs.kotlinx.coroutines.test`.

These exact versions were verified to resolve and compile cleanly against this project's AGP 9.2.1 / Gradle 9.4.1 / built-in-Kotlin setup before writing this plan.

- [ ] **Step 1: Add version entries**

In `gradle/libs.versions.toml`, in the `[versions]` block, add these four lines after `navigationUiKtx = "2.9.8"`:

```toml
lifecycle = "2.8.7"
datastorePreferences = "1.1.1"
okhttp = "4.12.0"
recyclerview = "1.3.2"
coroutines = "1.9.0"
```

- [ ] **Step 2: Add library entries**

In the `[libraries]` block, add these lines after the `androidx-navigation-ui-ktx` line:

```toml
androidx-lifecycle-runtime-ktx = { group = "androidx.lifecycle", name = "lifecycle-runtime-ktx", version.ref = "lifecycle" }
androidx-lifecycle-viewmodel-ktx = { group = "androidx.lifecycle", name = "lifecycle-viewmodel-ktx", version.ref = "lifecycle" }
androidx-lifecycle-service = { group = "androidx.lifecycle", name = "lifecycle-service", version.ref = "lifecycle" }
androidx-datastore-preferences = { group = "androidx.datastore", name = "datastore-preferences", version.ref = "datastorePreferences" }
okhttp = { group = "com.squareup.okhttp3", name = "okhttp", version.ref = "okhttp" }
androidx-recyclerview = { group = "androidx.recyclerview", name = "recyclerview", version.ref = "recyclerview" }
kotlinx-coroutines-android = { group = "org.jetbrains.kotlinx", name = "kotlinx-coroutines-android", version.ref = "coroutines" }
kotlinx-coroutines-test = { group = "org.jetbrains.kotlinx", name = "kotlinx-coroutines-test", version.ref = "coroutines" }
```

- [ ] **Step 3: Wire dependencies into the app module**

In `app/build.gradle.kts`, replace the `dependencies { ... }` block with:

```kotlin
dependencies {
    implementation(libs.androidx.activity.ktx)
    implementation(libs.androidx.appcompat)
    implementation(libs.androidx.constraintlayout)
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.navigation.fragment.ktx)
    implementation(libs.androidx.navigation.ui.ktx)
    implementation(libs.material)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.lifecycle.viewmodel.ktx)
    implementation(libs.androidx.lifecycle.service)
    implementation(libs.androidx.datastore.preferences)
    implementation(libs.okhttp)
    implementation(libs.androidx.recyclerview)
    implementation(libs.kotlinx.coroutines.android)
    testImplementation(libs.junit)
    testImplementation(libs.kotlinx.coroutines.test)
    androidTestImplementation(libs.androidx.espresso.core)
    androidTestImplementation(libs.androidx.junit)
}
```

- [ ] **Step 4: Verify it builds**

```bash
./gradlew :app:compileDebugKotlin -q
```

Expected: no output, exit code 0.

- [ ] **Step 5: Commit**

```bash
git add gradle/libs.versions.toml app/build.gradle.kts
git commit -m "build: add lifecycle, datastore, okhttp, recyclerview, coroutines deps"
```

---

## Task 3: Domain models (StreamStatus, StatusEvent, TwitchCheckResult)

**Files:**
- Create: `app/src/main/java/com/example/twitchnetworknotifier/monitor/model/StreamStatus.kt`
- Create: `app/src/main/java/com/example/twitchnetworknotifier/monitor/model/StatusEvent.kt`
- Create: `app/src/main/java/com/example/twitchnetworknotifier/monitor/model/TwitchCheckResult.kt`

**Interfaces:**
- Produces: `enum class StreamStatus { UNKNOWN, LIVE, OFFLINE, CONNECTION_ISSUE }`; `data class StatusEvent(val timestampMillis: Long, val fromState: StreamStatus, val toState: StreamStatus)`; `sealed interface TwitchCheckResult` with `data object Live`, `data object Offline`, `data class Failure(val message: String)`.

These are plain Kotlin with no Android dependency, used by every later task.

- [ ] **Step 1: Create StreamStatus**

`app/src/main/java/com/example/twitchnetworknotifier/monitor/model/StreamStatus.kt`:

```kotlin
package com.example.twitchnetworknotifier.monitor.model

enum class StreamStatus {
    UNKNOWN,
    LIVE,
    OFFLINE,
    CONNECTION_ISSUE
}
```

- [ ] **Step 2: Create StatusEvent**

`app/src/main/java/com/example/twitchnetworknotifier/monitor/model/StatusEvent.kt`:

```kotlin
package com.example.twitchnetworknotifier.monitor.model

data class StatusEvent(
    val timestampMillis: Long,
    val fromState: StreamStatus,
    val toState: StreamStatus
)
```

- [ ] **Step 3: Create TwitchCheckResult**

`app/src/main/java/com/example/twitchnetworknotifier/monitor/model/TwitchCheckResult.kt`:

```kotlin
package com.example.twitchnetworknotifier.monitor.model

sealed interface TwitchCheckResult {
    data object Live : TwitchCheckResult
    data object Offline : TwitchCheckResult
    data class Failure(val message: String) : TwitchCheckResult
}
```

- [ ] **Step 4: Verify it compiles**

```bash
./gradlew :app:compileDebugKotlin -q
```

Expected: no output, exit code 0.

- [ ] **Step 5: Commit**

```bash
git add app/src/main/java/com/example/twitchnetworknotifier/monitor/model
git commit -m "feat: add StreamStatus, StatusEvent, TwitchCheckResult models"
```

---

## Task 4: SettingsStore (DataStore-backed settings)

**Files:**
- Create: `app/src/main/java/com/example/twitchnetworknotifier/monitor/data/Settings.kt`
- Create: `app/src/main/java/com/example/twitchnetworknotifier/monitor/data/SettingsStore.kt`
- Test: `app/src/test/java/com/example/twitchnetworknotifier/monitor/data/SettingsStoreTest.kt`

**Interfaces:**
- Consumes: nothing from earlier tasks (only `android.content.Context`).
- Produces: `data class Settings(val channelName: String, val clientId: String, val clientSecret: String, val monitoringEnabled: Boolean)`; `class SettingsStore(context: Context)` with `val settings: Flow<Settings>`, `suspend fun updateChannelName(value: String)`, `suspend fun updateClientId(value: String)`, `suspend fun updateClientSecret(value: String)`, `suspend fun setMonitoringEnabled(value: Boolean)`. Defaults: empty strings, `monitoringEnabled = false`.

`SettingsStore` requires an Android `Context` (DataStore), so this is verified with an instrumented test rather than a pure unit test.

- [ ] **Step 1: Create the Settings data class**

`app/src/main/java/com/example/twitchnetworknotifier/monitor/data/Settings.kt`:

```kotlin
package com.example.twitchnetworknotifier.monitor.data

data class Settings(
    val channelName: String = "",
    val clientId: String = "",
    val clientSecret: String = "",
    val monitoringEnabled: Boolean = false
)
```

- [ ] **Step 2: Create SettingsStore**

`app/src/main/java/com/example/twitchnetworknotifier/monitor/data/SettingsStore.kt`:

```kotlin
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
```

- [ ] **Step 3: Write the instrumented test**

`app/src/androidTest/java/com/example/twitchnetworknotifier/monitor/data/SettingsStoreTest.kt`:

```kotlin
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
```

- [ ] **Step 4: Run the instrumented test**

```bash
./gradlew :app:connectedDebugAndroidTest --tests "com.example.twitchnetworknotifier.monitor.data.SettingsStoreTest"
```

Expected: `BUILD SUCCESSFUL`, both tests pass. (Requires a connected device/emulator; if none is available, run `./gradlew :app:compileDebugKotlin :app:compileDebugAndroidTestKotlin -q` instead to confirm it compiles, and note in the task review that device verification is pending.)

- [ ] **Step 5: Commit**

```bash
git add app/src/main/java/com/example/twitchnetworknotifier/monitor/data/Settings.kt app/src/main/java/com/example/twitchnetworknotifier/monitor/data/SettingsStore.kt app/src/androidTest/java/com/example/twitchnetworknotifier/monitor/data/SettingsStoreTest.kt
git commit -m "feat: add DataStore-backed SettingsStore"
```

---

## Task 5: HistoryStore (DataStore + org.json backed event history, capped at 50)

**Files:**
- Create: `app/src/main/java/com/example/twitchnetworknotifier/monitor/data/HistoryStore.kt`
- Test: `app/src/androidTest/java/com/example/twitchnetworknotifier/monitor/data/HistoryStoreTest.kt`

**Interfaces:**
- Consumes: `StreamStatus`, `StatusEvent` from Task 3.
- Produces: `class HistoryStore(context: Context)` with `val events: Flow<List<StatusEvent>>` (newest first) and `suspend fun addEvent(event: StatusEvent)`, which prepends and truncates to the most recent 50 entries.

- [ ] **Step 1: Create HistoryStore**

`app/src/main/java/com/example/twitchnetworknotifier/monitor/data/HistoryStore.kt`:

```kotlin
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
```

- [ ] **Step 2: Write the instrumented test**

`app/src/androidTest/java/com/example/twitchnetworknotifier/monitor/data/HistoryStoreTest.kt`:

```kotlin
package com.example.twitchnetworknotifier.monitor.data

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.example.twitchnetworknotifier.monitor.model.StatusEvent
import com.example.twitchnetworknotifier.monitor.model.StreamStatus
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Test
import org.junit.runner.RunWith
import kotlin.test.assertEquals

@RunWith(AndroidJUnit4::class)
class HistoryStoreTest {

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
```

- [ ] **Step 3: Run the instrumented test**

```bash
./gradlew :app:connectedDebugAndroidTest --tests "com.example.twitchnetworknotifier.monitor.data.HistoryStoreTest"
```

Expected: `BUILD SUCCESSFUL`, both tests pass. (If no device is connected, fall back to `./gradlew :app:compileDebugKotlin :app:compileDebugAndroidTestKotlin -q` to confirm compilation.)

- [ ] **Step 4: Commit**

```bash
git add app/src/main/java/com/example/twitchnetworknotifier/monitor/data/HistoryStore.kt app/src/androidTest/java/com/example/twitchnetworknotifier/monitor/data/HistoryStoreTest.kt
git commit -m "feat: add DataStore+JSON backed HistoryStore capped at 50 events"
```

---

## Task 6: TwitchApiClient (Helix API via OkHttp + org.json)

**Files:**
- Create: `app/src/main/java/com/example/twitchnetworknotifier/monitor/network/TwitchApiClient.kt`
- Create: `app/src/main/java/com/example/twitchnetworknotifier/monitor/network/TwitchApiClientImpl.kt`

**Interfaces:**
- Consumes: `TwitchCheckResult` from Task 3.
- Produces: `interface TwitchApiClient { suspend fun getStreamStatus(channelName: String, clientId: String, clientSecret: String): TwitchCheckResult }`; `class TwitchApiClientImpl(httpClient: OkHttpClient = OkHttpClient()) : TwitchApiClient`. This is the only type later tasks (`StreamRepository`, `StreamMonitorService`) depend on for network access — they depend on the `TwitchApiClient` interface, not the impl, so tests can substitute a fake.

No dedicated unit test for `TwitchApiClientImpl` in this task: its only logic worth testing (HTTP/JSON error mapping, token caching) requires a real or mock HTTP server to exercise meaningfully, which is disproportionate for this app's size. `StreamRepository`'s tests (Task 7) cover all the behaviors that matter to the rest of the app using a fake `TwitchApiClient`; `TwitchApiClientImpl` itself is verified by manually running the app against the real Helix API in Task 16.

The impl calls two Helix endpoints: `POST https://id.twitch.tv/oauth2/token` (Client Credentials flow, cached until expiry) and `GET https://api.twitch.tv/helix/streams?user_login=<channel>`. Any `IOException`, non-2xx response, or malformed JSON becomes `TwitchCheckResult.Failure`.

- [ ] **Step 1: Create the interface**

`app/src/main/java/com/example/twitchnetworknotifier/monitor/network/TwitchApiClient.kt`:

```kotlin
package com.example.twitchnetworknotifier.monitor.network

import com.example.twitchnetworknotifier.monitor.model.TwitchCheckResult

interface TwitchApiClient {
    suspend fun getStreamStatus(channelName: String, clientId: String, clientSecret: String): TwitchCheckResult
}
```

- [ ] **Step 2: Implement TwitchApiClientImpl**

`app/src/main/java/com/example/twitchnetworknotifier/monitor/network/TwitchApiClientImpl.kt`:

```kotlin
package com.example.twitchnetworknotifier.monitor.network

import com.example.twitchnetworknotifier.monitor.model.TwitchCheckResult
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.FormBody
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import java.io.IOException

class TwitchApiClientImpl(
    private val httpClient: OkHttpClient = OkHttpClient()
) : TwitchApiClient {

    private var cachedToken: String? = null
    private var tokenExpiryMillis: Long = 0L

    override suspend fun getStreamStatus(
        channelName: String,
        clientId: String,
        clientSecret: String
    ): TwitchCheckResult = withContext(Dispatchers.IO) {
        try {
            val token = ensureValidToken(clientId, clientSecret)
            val request = Request.Builder()
                .url("https://api.twitch.tv/helix/streams?user_login=$channelName")
                .header("Client-Id", clientId)
                .header("Authorization", "Bearer $token")
                .get()
                .build()

            httpClient.newCall(request).execute().use { response ->
                if (!response.isSuccessful) {
                    return@withContext TwitchCheckResult.Failure("Helix streams call failed: HTTP ${response.code}")
                }
                val body = response.body?.string() ?: return@withContext TwitchCheckResult.Failure("Empty response body")
                val data = JSONObject(body).getJSONArray("data")
                if (data.length() > 0) TwitchCheckResult.Live else TwitchCheckResult.Offline
            }
        } catch (e: IOException) {
            TwitchCheckResult.Failure(e.message ?: "Network error")
        } catch (e: org.json.JSONException) {
            TwitchCheckResult.Failure(e.message ?: "Malformed response")
        }
    }

    private fun ensureValidToken(clientId: String, clientSecret: String): String {
        val now = System.currentTimeMillis()
        val token = cachedToken
        if (token != null && now < tokenExpiryMillis) {
            return token
        }
        return fetchAppAccessToken(clientId, clientSecret)
    }

    private fun fetchAppAccessToken(clientId: String, clientSecret: String): String {
        val formBody = FormBody.Builder()
            .add("client_id", clientId)
            .add("client_secret", clientSecret)
            .add("grant_type", "client_credentials")
            .build()
        val request = Request.Builder()
            .url("https://id.twitch.tv/oauth2/token")
            .post(formBody)
            .build()

        httpClient.newCall(request).execute().use { response ->
            if (!response.isSuccessful) {
                throw IOException("Token request failed: HTTP ${response.code}")
            }
            val body = response.body?.string() ?: throw IOException("Empty token response")
            val json = JSONObject(body)
            val token = json.getString("access_token")
            val expiresInSeconds = json.getLong("expires_in")
            cachedToken = token
            tokenExpiryMillis = System.currentTimeMillis() + (expiresInSeconds * 1000L) - 60_000L
            return token
        }
    }
}
```

- [ ] **Step 3: Verify it compiles**

```bash
./gradlew :app:compileDebugKotlin -q
```

Expected: no output, exit code 0.

- [ ] **Step 4: Commit**

```bash
git add app/src/main/java/com/example/twitchnetworknotifier/monitor/network
git commit -m "feat: add TwitchApiClient Helix API client (OkHttp + org.json)"
```

---

## Task 7: StreamRepository (state machine + retry/backoff)

**Files:**
- Create: `app/src/main/java/com/example/twitchnetworknotifier/monitor/StreamRepository.kt`
- Test: `app/src/test/java/com/example/twitchnetworknotifier/monitor/StreamRepositoryTest.kt`
- Test helper: `app/src/test/java/com/example/twitchnetworknotifier/monitor/FakeTwitchApiClient.kt`

**Interfaces:**
- Consumes: `Settings`/`SettingsStore` (Task 4), `StatusEvent`/`HistoryStore` (Task 5), `TwitchCheckResult`/`TwitchApiClient` (Task 3, 6), `StreamStatus` (Task 3).
- Produces: `class StreamRepository(settingsStore: SettingsStore, historyStore: HistoryStore, apiClient: TwitchApiClient, clock: () -> Long = System::currentTimeMillis)` with:
  - `val currentStatus: StateFlow<StreamStatus>`
  - `val monitoringEnabled: Flow<Boolean>`
  - `val history: Flow<List<StatusEvent>>`
  - `val alerts: SharedFlow<StatusEvent>` (emits only on an actual state transition — this is what `StreamMonitorService`, Task 9, collects to post alert notifications)
  - `suspend fun setMonitoringEnabled(enabled: Boolean)`
  - `suspend fun checkOnce(): StreamStatus` (performs one check, with retry/backoff on call failure; updates `currentStatus`, appends to `history`, and emits to `alerts` if and only if the resolved status differs from the previous one)

This is the core logic of the whole app and the only class with a full unit-test suite (pure Kotlin + a fake `TwitchApiClient`, no Android dependency, runs under JVM `kotlinx-coroutines-test` virtual time — no real 10s/20s/40s waits in tests).

- [ ] **Step 1: Write the fake TwitchApiClient test helper**

`app/src/test/java/com/example/twitchnetworknotifier/monitor/FakeTwitchApiClient.kt`:

```kotlin
package com.example.twitchnetworknotifier.monitor

import com.example.twitchnetworknotifier.monitor.model.TwitchCheckResult
import com.example.twitchnetworknotifier.monitor.network.TwitchApiClient

class FakeTwitchApiClient : TwitchApiClient {
    var callCount: Int = 0
    private val queuedResults = mutableListOf<TwitchCheckResult>()

    fun queueResult(result: TwitchCheckResult) {
        queuedResults.add(result)
    }

    override suspend fun getStreamStatus(
        channelName: String,
        clientId: String,
        clientSecret: String
    ): TwitchCheckResult {
        callCount++
        return if (queuedResults.isNotEmpty()) queuedResults.removeAt(0) else TwitchCheckResult.Live
    }
}
```

- [ ] **Step 2: Write the failing tests**

`app/src/test/java/com/example/twitchnetworknotifier/monitor/StreamRepositoryTest.kt`:

```kotlin
package com.example.twitchnetworknotifier.monitor

import com.example.twitchnetworknotifier.monitor.data.HistoryStore
import com.example.twitchnetworknotifier.monitor.data.Settings
import com.example.twitchnetworknotifier.monitor.data.SettingsStore
import com.example.twitchnetworknotifier.monitor.model.StatusEvent
import com.example.twitchnetworknotifier.monitor.model.StreamStatus
import com.example.twitchnetworknotifier.monitor.model.TwitchCheckResult
import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.Test
import kotlin.test.assertEquals

class StreamRepositoryTest {

    private fun buildRepository(
        apiClient: FakeTwitchApiClient,
        settingsStore: SettingsStore,
        historyStore: HistoryStore,
        clock: () -> Long = { 0L }
    ) = StreamRepository(settingsStore, historyStore, apiClient, clock)

    private fun fakeSettingsStore(settings: Settings = Settings(channelName = "mychannel")): SettingsStore {
        val store = mockk<SettingsStore>()
        every { store.settings } returns MutableStateFlow(settings)
        return store
    }

    private fun fakeHistoryStore(): Pair<HistoryStore, MutableList<StatusEvent>> {
        val recorded = mutableListOf<StatusEvent>()
        val store = mockk<HistoryStore>()
        every { store.events } returns MutableStateFlow(emptyList())
        coEvery { store.addEvent(any()) } answers { recorded.add(firstArg()) }
        return store to recorded
    }

    @Test
    fun firstCheckLiveTransitionsFromUnknownAndRecordsHistory() = runTest {
        val api = FakeTwitchApiClient()
        api.queueResult(TwitchCheckResult.Live)
        val (historyStore, recorded) = fakeHistoryStore()
        val repository = buildRepository(api, fakeSettingsStore(), historyStore, clock = { 5_000L })

        val status = repository.checkOnce()

        assertEquals(StreamStatus.LIVE, status)
        assertEquals(StreamStatus.LIVE, repository.currentStatus.value)
        assertEquals(1, recorded.size)
        assertEquals(StreamStatus.UNKNOWN, recorded[0].fromState)
        assertEquals(StreamStatus.LIVE, recorded[0].toState)
        assertEquals(5_000L, recorded[0].timestampMillis)
    }

    @Test
    fun confirmedOfflineDoesNotRetry() = runTest {
        val api = FakeTwitchApiClient()
        api.queueResult(TwitchCheckResult.Offline)
        val (historyStore, _) = fakeHistoryStore()
        val repository = buildRepository(api, fakeSettingsStore(), historyStore)

        val status = repository.checkOnce()

        assertEquals(StreamStatus.OFFLINE, status)
        assertEquals(1, api.callCount)
    }

    @Test
    fun callFailureRetriesThreeTimesWithBackoffBeforeConnectionIssue() = runTest {
        val api = FakeTwitchApiClient()
        repeat(4) { api.queueResult(TwitchCheckResult.Failure("boom")) }
        val (historyStore, _) = fakeHistoryStore()
        val repository = buildRepository(api, fakeSettingsStore(), historyStore)

        val status = repository.checkOnce()
        advanceUntilIdle()

        assertEquals(StreamStatus.CONNECTION_ISSUE, status)
        assertEquals(4, api.callCount)
    }

    @Test
    fun callFailureThenSuccessOnRetryRecoversWithoutConnectionIssue() = runTest {
        val api = FakeTwitchApiClient()
        api.queueResult(TwitchCheckResult.Failure("boom"))
        api.queueResult(TwitchCheckResult.Live)
        val (historyStore, _) = fakeHistoryStore()
        val repository = buildRepository(api, fakeSettingsStore(), historyStore)

        val status = repository.checkOnce()
        advanceUntilIdle()

        assertEquals(StreamStatus.LIVE, status)
        assertEquals(2, api.callCount)
    }

    @Test
    fun repeatedSameStatusDoesNotEmitDuplicateAlert() = runTest {
        val api = FakeTwitchApiClient()
        api.queueResult(TwitchCheckResult.Live)
        api.queueResult(TwitchCheckResult.Live)
        val (historyStore, recorded) = fakeHistoryStore()
        val repository = buildRepository(api, fakeSettingsStore(), historyStore)

        repository.checkOnce()
        repository.checkOnce()

        assertEquals(1, recorded.size)
    }

    @Test
    fun alertsFlowEmitsOnlyOnStateChange() = runTest {
        val api = FakeTwitchApiClient()
        api.queueResult(TwitchCheckResult.Live)
        api.queueResult(TwitchCheckResult.Offline)
        val (historyStore, _) = fakeHistoryStore()
        val repository = buildRepository(api, fakeSettingsStore(), historyStore)

        val emitted = mutableListOf<StatusEvent>()
        val job = launch { repository.alerts.collect { emitted.add(it) } }

        repository.checkOnce()
        repository.checkOnce()
        advanceUntilIdle()
        job.cancel()

        assertEquals(2, emitted.size)
        assertEquals(StreamStatus.LIVE, emitted[0].toState)
        assertEquals(StreamStatus.OFFLINE, emitted[1].toState)
    }
}
```

This test file uses `io.mockk` for `SettingsStore`/`HistoryStore`. Add MockK before running: see Step 2a.

- [ ] **Step 2a: Add MockK test dependency**

In `gradle/libs.versions.toml`, add to `[versions]`:

```toml
mockk = "1.13.13"
```

Add to `[libraries]`:

```toml
mockk = { group = "io.mockk", name = "mockk", version.ref = "mockk" }
```

In `app/build.gradle.kts`, add to `dependencies { ... }`:

```kotlin
testImplementation(libs.mockk)
```

- [ ] **Step 3: Run the tests to verify they fail (StreamRepository doesn't exist yet)**

```bash
./gradlew :app:testDebugUnitTest --tests "com.example.twitchnetworknotifier.monitor.StreamRepositoryTest"
```

Expected: compilation FAILURE — `StreamRepository` is unresolved.

- [ ] **Step 4: Implement StreamRepository**

`app/src/main/java/com/example/twitchnetworknotifier/monitor/StreamRepository.kt`:

```kotlin
package com.example.twitchnetworknotifier.monitor

import com.example.twitchnetworknotifier.monitor.data.HistoryStore
import com.example.twitchnetworknotifier.monitor.data.Settings
import com.example.twitchnetworknotifier.monitor.data.SettingsStore
import com.example.twitchnetworknotifier.monitor.model.StatusEvent
import com.example.twitchnetworknotifier.monitor.model.StreamStatus
import com.example.twitchnetworknotifier.monitor.model.TwitchCheckResult
import com.example.twitchnetworknotifier.monitor.network.TwitchApiClient
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map

class StreamRepository(
    private val settingsStore: SettingsStore,
    private val historyStore: HistoryStore,
    private val apiClient: TwitchApiClient,
    private val clock: () -> Long = System::currentTimeMillis
) {

    companion object {
        private val RETRY_BACKOFF_MILLIS = listOf(10_000L, 20_000L, 40_000L)
    }

    private val _currentStatus = MutableStateFlow(StreamStatus.UNKNOWN)
    val currentStatus: StateFlow<StreamStatus> = _currentStatus.asStateFlow()

    private val _alerts = MutableSharedFlow<StatusEvent>(extraBufferCapacity = 1)
    val alerts: SharedFlow<StatusEvent> = _alerts.asSharedFlow()

    val monitoringEnabled: Flow<Boolean> = settingsStore.settings.map { it.monitoringEnabled }
    val history: Flow<List<StatusEvent>> = historyStore.events

    suspend fun setMonitoringEnabled(enabled: Boolean) {
        settingsStore.setMonitoringEnabled(enabled)
    }

    suspend fun checkOnce(): StreamStatus {
        val settings = settingsStore.settings.first()
        val result = performCheckWithRetry(settings)
        val newStatus = when (result) {
            is TwitchCheckResult.Live -> StreamStatus.LIVE
            is TwitchCheckResult.Offline -> StreamStatus.OFFLINE
            is TwitchCheckResult.Failure -> StreamStatus.CONNECTION_ISSUE
        }

        val previousStatus = _currentStatus.value
        if (newStatus != previousStatus) {
            _currentStatus.value = newStatus
            val event = StatusEvent(clock(), previousStatus, newStatus)
            historyStore.addEvent(event)
            _alerts.emit(event)
        }
        return newStatus
    }

    private suspend fun performCheckWithRetry(settings: Settings): TwitchCheckResult {
        var lastResult = apiClient.getStreamStatus(settings.channelName, settings.clientId, settings.clientSecret)
        if (lastResult !is TwitchCheckResult.Failure) return lastResult

        for (backoffMillis in RETRY_BACKOFF_MILLIS) {
            delay(backoffMillis)
            lastResult = apiClient.getStreamStatus(settings.channelName, settings.clientId, settings.clientSecret)
            if (lastResult !is TwitchCheckResult.Failure) return lastResult
        }
        return lastResult
    }
}
```

- [ ] **Step 5: Run the tests to verify they pass**

```bash
./gradlew :app:testDebugUnitTest --tests "com.example.twitchnetworknotifier.monitor.StreamRepositoryTest"
```

Expected: `BUILD SUCCESSFUL`, all 6 tests pass.

- [ ] **Step 6: Commit**

```bash
git add gradle/libs.versions.toml app/build.gradle.kts app/src/main/java/com/example/twitchnetworknotifier/monitor/StreamRepository.kt app/src/test/java/com/example/twitchnetworknotifier/monitor/StreamRepositoryTest.kt app/src/test/java/com/example/twitchnetworknotifier/monitor/FakeTwitchApiClient.kt
git commit -m "feat: add StreamRepository with state machine and retry/backoff logic"
```

---

## Task 8: Notification strings, channels, and NotificationHelper

**Files:**
- Modify: `app/src/main/res/values/strings.xml`
- Create: `app/src/main/java/com/example/twitchnetworknotifier/monitor/NotificationHelper.kt`

**Interfaces:**
- Consumes: `StreamStatus` (Task 3).
- Produces: `class NotificationHelper(context: Context)` with `fun createNotificationChannels()`, `fun buildMonitoringNotification(): Notification`, `fun showAlert(message: String)`, and companion constants `MONITORING_CHANNEL_ID`, `ALERTS_CHANNEL_ID`, `MONITORING_NOTIFICATION_ID`, `ALERT_NOTIFICATION_ID`, plus `fun messageForStatus(status: StreamStatus): String` mapping a resolved status to its exact alert copy (used by `StreamMonitorService`, Task 9).

- [ ] **Step 1: Add notification string resources**

In `app/src/main/res/values/strings.xml`, add these lines before the closing `</resources>`:

```xml
<string name="notif_monitoring_channel_name">Monitoring</string>
<string name="notif_alerts_channel_name">Alerts</string>
<string name="notif_monitoring_active">Monitoring active</string>
<string name="notif_welcome">Welcome to Twitch Network Notifier, your live stream status is now being monitored.</string>
<string name="notif_offline">Your live stream is offline, please check.</string>
<string name="notif_connection_issue">Could not verify your stream status (connection issue).</string>
<string name="notif_back_live">Your streaming is live now.</string>
<string name="dialog_toggle_off_title">You sure to off monitoring?</string>
<string name="dialog_yes">Yes</string>
<string name="dialog_no">No</string>
```

- [ ] **Step 2: Implement NotificationHelper**

`app/src/main/java/com/example/twitchnetworknotifier/monitor/NotificationHelper.kt`:

```kotlin
package com.example.twitchnetworknotifier.monitor

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import com.example.twitchnetworknotifier.R
import com.example.twitchnetworknotifier.monitor.model.StreamStatus

class NotificationHelper(private val context: Context) {

    companion object {
        const val MONITORING_CHANNEL_ID = "monitoring_channel"
        const val ALERTS_CHANNEL_ID = "alerts_channel"
        const val MONITORING_NOTIFICATION_ID = 1
        const val ALERT_NOTIFICATION_ID = 2
    }

    fun createNotificationChannels() {
        val manager = context.getSystemService(NotificationManager::class.java)

        val monitoringChannel = NotificationChannel(
            MONITORING_CHANNEL_ID,
            context.getString(R.string.notif_monitoring_channel_name),
            NotificationManager.IMPORTANCE_LOW
        ).apply { setSound(null, null) }

        val alertsChannel = NotificationChannel(
            ALERTS_CHANNEL_ID,
            context.getString(R.string.notif_alerts_channel_name),
            NotificationManager.IMPORTANCE_HIGH
        )

        manager.createNotificationChannel(monitoringChannel)
        manager.createNotificationChannel(alertsChannel)
    }

    fun buildMonitoringNotification(): Notification {
        return NotificationCompat.Builder(context, MONITORING_CHANNEL_ID)
            .setContentTitle(context.getString(R.string.app_name))
            .setContentText(context.getString(R.string.notif_monitoring_active))
            .setSmallIcon(R.drawable.ic_launcher_foreground)
            .setOngoing(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .build()
    }

    fun showAlert(message: String) {
        val notification = NotificationCompat.Builder(context, ALERTS_CHANNEL_ID)
            .setContentTitle(context.getString(R.string.app_name))
            .setContentText(message)
            .setSmallIcon(R.drawable.ic_launcher_foreground)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setAutoCancel(true)
            .build()
        NotificationManagerCompat.from(context).notify(ALERT_NOTIFICATION_ID, notification)
    }

    fun messageForStatus(status: StreamStatus): String = when (status) {
        StreamStatus.LIVE -> context.getString(R.string.notif_back_live)
        StreamStatus.OFFLINE -> context.getString(R.string.notif_offline)
        StreamStatus.CONNECTION_ISSUE -> context.getString(R.string.notif_connection_issue)
        StreamStatus.UNKNOWN -> context.getString(R.string.notif_connection_issue)
    }

    fun welcomeMessage(): String = context.getString(R.string.notif_welcome)
}
```

Note: `messageForStatus` is only ever called with the *new* (post-transition) status, which by construction (see `StreamRepository.checkOnce`) is always `LIVE`, `OFFLINE`, or `CONNECTION_ISSUE` — `UNKNOWN` is only ever a `fromState`, never a `toState`. The `UNKNOWN` branch exists only so the `when` is exhaustive; it is unreachable in practice.

- [ ] **Step 3: Verify it compiles**

```bash
./gradlew :app:compileDebugKotlin -q
```

Expected: no output, exit code 0.

- [ ] **Step 4: Commit**

```bash
git add app/src/main/res/values/strings.xml app/src/main/java/com/example/twitchnetworknotifier/monitor/NotificationHelper.kt
git commit -m "feat: add notification channels and NotificationHelper"
```

---

## Task 9: AppContainer (shared StreamRepository instance)

**Files:**
- Create: `app/src/main/java/com/example/twitchnetworknotifier/monitor/AppContainer.kt`

**Interfaces:**
- Consumes: `StreamRepository` (Task 7), `SettingsStore` (Task 4), `HistoryStore` (Task 5), `TwitchApiClientImpl` (Task 6).
- Produces: `object AppContainer { fun getRepository(context: Context): StreamRepository }`. This is how `StreamMonitorService`, `MainViewModel`, and `SettingsViewModel` all get the *same* `StreamRepository` instance application-wide, without a DI framework — keeping the app light, per the spec's explicit lightweight-app goal.

No DI framework (Hilt/Koin) is introduced: a single lazily-initialized singleton is sufficient for one repository shared by a handful of consumers, and avoids the extra build complexity DI frameworks add.

- [ ] **Step 1: Implement AppContainer**

`app/src/main/java/com/example/twitchnetworknotifier/monitor/AppContainer.kt`:

```kotlin
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
```

- [ ] **Step 2: Verify it compiles**

```bash
./gradlew :app:compileDebugKotlin -q
```

Expected: no output, exit code 0.

- [ ] **Step 3: Commit**

```bash
git add app/src/main/java/com/example/twitchnetworknotifier/monitor/AppContainer.kt
git commit -m "feat: add AppContainer singleton for shared StreamRepository"
```

---

## Task 10: StreamMonitorService (foreground service, the check loop)

**Files:**
- Create: `app/src/main/java/com/example/twitchnetworknotifier/monitor/StreamMonitorService.kt`
- Modify: `app/src/main/AndroidManifest.xml`

**Interfaces:**
- Consumes: `StreamRepository`/`AppContainer` (Task 9), `NotificationHelper` (Task 8).
- Produces: `class StreamMonitorService : LifecycleService()` with companion `fun start(context: Context, showWelcome: Boolean)` and `fun stop(context: Context)`. Later tasks (`MainFragment`, `BootReceiver`) call these two companion functions — they never construct or bind to the service directly.

The service starts in the foreground immediately in `onStartCommand` (required within a few seconds of `startForegroundService` on Android 8+), shows the welcome alert only when `showWelcome` is true (i.e. only when the user just flipped the toggle on — not on every reboot restart), and runs a single long-lived loop for the lifetime of the service process: check, then `delay(5 minutes)`, repeat. A second coroutine collects `repository.alerts` and turns each transition into a high-priority notification via `NotificationHelper`.

- [ ] **Step 1: Implement StreamMonitorService**

`app/src/main/java/com/example/twitchnetworknotifier/monitor/StreamMonitorService.kt`:

```kotlin
package com.example.twitchnetworknotifier.monitor

import android.content.Context
import android.content.Intent
import androidx.core.content.ContextCompat
import androidx.lifecycle.LifecycleService
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

class StreamMonitorService : LifecycleService() {

    companion object {
        private const val CHECK_INTERVAL_MILLIS = 5 * 60 * 1000L
        private const val EXTRA_SHOW_WELCOME = "show_welcome"

        fun start(context: Context, showWelcome: Boolean) {
            val intent = Intent(context, StreamMonitorService::class.java)
                .putExtra(EXTRA_SHOW_WELCOME, showWelcome)
            ContextCompat.startForegroundService(context, intent)
        }

        fun stop(context: Context) {
            context.stopService(Intent(context, StreamMonitorService::class.java))
        }
    }

    private lateinit var repository: StreamRepository
    private lateinit var notificationHelper: NotificationHelper
    private var loopJob: Job? = null

    override fun onCreate() {
        super.onCreate()
        notificationHelper = NotificationHelper(this)
        notificationHelper.createNotificationChannels()
        repository = AppContainer.getRepository(this)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        super.onStartCommand(intent, flags, startId)
        startForeground(NotificationHelper.MONITORING_NOTIFICATION_ID, notificationHelper.buildMonitoringNotification())

        if (intent?.getBooleanExtra(EXTRA_SHOW_WELCOME, false) == true) {
            notificationHelper.showAlert(notificationHelper.welcomeMessage())
        }

        if (loopJob == null) {
            loopJob = lifecycleScope.launch {
                launch {
                    repository.alerts.collect { event ->
                        notificationHelper.showAlert(notificationHelper.messageForStatus(event.toState))
                    }
                }
                while (isActive) {
                    repository.checkOnce()
                    delay(CHECK_INTERVAL_MILLIS)
                }
            }
        }

        return START_STICKY
    }

    override fun onDestroy() {
        loopJob?.cancel()
        loopJob = null
        super.onDestroy()
    }
}
```

- [ ] **Step 2: Declare the service in the manifest**

In `app/src/main/AndroidManifest.xml`, add inside `<application>`, after the closing `</activity>` tag:

```xml
<service
    android:name=".monitor.StreamMonitorService"
    android:exported="false"
    android:foregroundServiceType="dataSync" />
```

- [ ] **Step 3: Verify it compiles**

```bash
./gradlew :app:compileDebugKotlin -q
```

Expected: no output, exit code 0. (Manifest permissions for foreground service are added in Task 12; the app will not yet run correctly end-to-end until that task, but this task's code must compile on its own.)

- [ ] **Step 4: Commit**

```bash
git add app/src/main/java/com/example/twitchnetworknotifier/monitor/StreamMonitorService.kt app/src/main/AndroidManifest.xml
git commit -m "feat: add StreamMonitorService foreground service with 5-min check loop"
```

---

## Task 11: BootReceiver (resume monitoring after reboot)

**Files:**
- Create: `app/src/main/java/com/example/twitchnetworknotifier/monitor/BootReceiver.kt`
- Modify: `app/src/main/AndroidManifest.xml`

**Interfaces:**
- Consumes: `StreamMonitorService.start` (Task 10), `SettingsStore` (Task 4).
- Produces: `class BootReceiver : BroadcastReceiver()`, registered for `android.intent.action.BOOT_COMPLETED`.

- [ ] **Step 1: Implement BootReceiver**

`app/src/main/java/com/example/twitchnetworknotifier/monitor/BootReceiver.kt`:

```kotlin
package com.example.twitchnetworknotifier.monitor

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.example.twitchnetworknotifier.monitor.data.SettingsStore
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

class BootReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != Intent.ACTION_BOOT_COMPLETED) return

        val appContext = context.applicationContext
        val pendingResult = goAsync()
        CoroutineScope(Dispatchers.Default).launch {
            try {
                val settings = SettingsStore(appContext).settings.first()
                if (settings.monitoringEnabled) {
                    StreamMonitorService.start(appContext, showWelcome = false)
                }
            } finally {
                pendingResult.finish()
            }
        }
    }
}
```

- [ ] **Step 2: Declare the receiver and permission in the manifest**

In `app/src/main/AndroidManifest.xml`, add `<uses-permission android:name="android.permission.RECEIVE_BOOT_COMPLETED" />` right after the opening `<manifest ...>` tag's attributes (as a sibling of `<application>`, before it), and add inside `<application>`, after the `<service>` entry from Task 10:

```xml
<receiver
    android:name=".monitor.BootReceiver"
    android:exported="true">
    <intent-filter>
        <action android:name="android.intent.action.BOOT_COMPLETED" />
    </intent-filter>
</receiver>
```

- [ ] **Step 3: Verify it compiles**

```bash
./gradlew :app:compileDebugKotlin -q
```

Expected: no output, exit code 0.

- [ ] **Step 4: Commit**

```bash
git add app/src/main/java/com/example/twitchnetworknotifier/monitor/BootReceiver.kt app/src/main/AndroidManifest.xml
git commit -m "feat: add BootReceiver to resume monitoring after reboot"
```

---

## Task 12: Manifest permissions (INTERNET, FOREGROUND_SERVICE, POST_NOTIFICATIONS)

**Files:**
- Modify: `app/src/main/AndroidManifest.xml`

**Interfaces:** None — this task only adds permission declarations needed by Tasks 10–11's components and Task 13's runtime prompt.

- [ ] **Step 1: Add the remaining permission declarations**

In `app/src/main/AndroidManifest.xml`, add these lines as siblings of the existing `<uses-permission android:name="android.permission.RECEIVE_BOOT_COMPLETED" />` (added in Task 11), all before `<application ...>`:

```xml
<uses-permission android:name="android.permission.INTERNET" />
<uses-permission android:name="android.permission.FOREGROUND_SERVICE" />
<uses-permission android:name="android.permission.FOREGROUND_SERVICE_DATA_SYNC" />
<uses-permission android:name="android.permission.POST_NOTIFICATIONS" />
```

The full top of the manifest should now read:

```xml
<?xml version="1.0" encoding="utf-8"?>
<manifest xmlns:android="http://schemas.android.com/apk/res/android"
    xmlns:tools="http://schemas.android.com/tools">

    <uses-permission android:name="android.permission.INTERNET" />
    <uses-permission android:name="android.permission.FOREGROUND_SERVICE" />
    <uses-permission android:name="android.permission.FOREGROUND_SERVICE_DATA_SYNC" />
    <uses-permission android:name="android.permission.POST_NOTIFICATIONS" />
    <uses-permission android:name="android.permission.RECEIVE_BOOT_COMPLETED" />

    <application
```

- [ ] **Step 2: Verify it builds**

```bash
./gradlew :app:assembleDebug -q
```

Expected: no output, exit code 0 — confirms the full manifest (permissions, service, receiver) merges correctly into an installable APK.

- [ ] **Step 3: Commit**

```bash
git add app/src/main/AndroidManifest.xml
git commit -m "feat: declare INTERNET, FOREGROUND_SERVICE, and POST_NOTIFICATIONS permissions"
```

---

## Task 13: HistoryAdapter (RecyclerView) and item layout

**Files:**
- Create: `app/src/main/res/layout/item_status_event.xml`
- Create: `app/src/main/java/com/example/twitchnetworknotifier/ui/main/HistoryAdapter.kt`

**Interfaces:**
- Consumes: `StatusEvent` (Task 3).
- Produces: `class HistoryAdapter : RecyclerView.Adapter<HistoryAdapter.ViewHolder>()` with `fun submitList(newList: List<StatusEvent>)`. Consumed by `MainFragment` in Task 14.

The history list is capped at 50 items (`HistoryStore`, Task 5), so a plain `notifyDataSetChanged()` on submit is acceptable — no `DiffUtil`/`ListAdapter` needed for a list this small, consistent with YAGNI.

- [ ] **Step 1: Create the item layout**

`app/src/main/res/layout/item_status_event.xml`:

```xml
<?xml version="1.0" encoding="utf-8"?>
<TextView xmlns:android="http://schemas.android.com/apk/res/android"
    android:id="@+id/text_event"
    android:layout_width="match_parent"
    android:layout_height="wrap_content"
    android:padding="12dp"
    android:textSize="14sp" />
```

- [ ] **Step 2: Implement HistoryAdapter**

`app/src/main/java/com/example/twitchnetworknotifier/ui/main/HistoryAdapter.kt`:

```kotlin
package com.example.twitchnetworknotifier.ui.main

import android.text.format.DateFormat
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView
import com.example.twitchnetworknotifier.R
import com.example.twitchnetworknotifier.monitor.model.StatusEvent
import com.example.twitchnetworknotifier.monitor.model.StreamStatus
import java.util.Date

class HistoryAdapter : RecyclerView.Adapter<HistoryAdapter.ViewHolder>() {

    private var items: List<StatusEvent> = emptyList()

    fun submitList(newList: List<StatusEvent>) {
        items = newList
        notifyDataSetChanged()
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
        val view = LayoutInflater.from(parent.context).inflate(R.layout.item_status_event, parent, false)
        return ViewHolder(view)
    }

    override fun onBindViewHolder(holder: ViewHolder, position: Int) {
        holder.bind(items[position])
    }

    override fun getItemCount(): Int = items.size

    class ViewHolder(itemView: View) : RecyclerView.ViewHolder(itemView) {
        private val textView: TextView = itemView.findViewById(R.id.text_event)

        fun bind(event: StatusEvent) {
            val time = DateFormat.getTimeFormat(itemView.context).format(Date(event.timestampMillis))
            val description = when (event.toState) {
                StreamStatus.LIVE -> "back online"
                StreamStatus.OFFLINE -> "went offline"
                StreamStatus.CONNECTION_ISSUE -> "connection issue"
                StreamStatus.UNKNOWN -> "status unknown"
            }
            textView.text = "$time — $description"
        }
    }
}
```

- [ ] **Step 3: Verify it compiles**

```bash
./gradlew :app:compileDebugKotlin -q
```

Expected: no output, exit code 0.

- [ ] **Step 4: Commit**

```bash
git add app/src/main/res/layout/item_status_event.xml app/src/main/java/com/example/twitchnetworknotifier/ui/main/HistoryAdapter.kt
git commit -m "feat: add HistoryAdapter for the status event RecyclerView"
```

---

## Task 14: MainFragment + SettingsFragment + nav_graph rewiring

**Files:**
- Create: `app/src/main/java/com/example/twitchnetworknotifier/ui/main/MainViewModel.kt`
- Create: `app/src/main/java/com/example/twitchnetworknotifier/ui/main/MainFragment.kt`
- Create: `app/src/main/res/layout/fragment_main.xml`
- Create: `app/src/main/java/com/example/twitchnetworknotifier/ui/settings/SettingsViewModel.kt`
- Create: `app/src/main/java/com/example/twitchnetworknotifier/ui/settings/SettingsFragment.kt`
- Create: `app/src/main/res/layout/fragment_settings.xml`
- Delete: `app/src/main/java/com/example/twitchnetworknotifier/FirstFragment.kt`
- Delete: `app/src/main/java/com/example/twitchnetworknotifier/SecondFragment.kt`
- Delete: `app/src/main/res/layout/fragment_first.xml`
- Delete: `app/src/main/res/layout/fragment_second.xml`
- Modify: `app/src/main/res/navigation/nav_graph.xml`
- Modify: `app/src/main/res/values/strings.xml`
- Modify: `app/src/main/java/com/example/twitchnetworknotifier/MainActivity.kt`

**Interfaces:**
- Consumes: `StreamRepository`/`AppContainer` (Task 9), `StreamMonitorService.start`/`.stop` (Task 10), `HistoryAdapter` (Task 13), `StreamStatus`/`StatusEvent` (Task 3), `SettingsStore`/`Settings` (Task 4).
- Produces: `class MainViewModel(application: Application) : AndroidViewModel(application)` with `val monitoringEnabled: StateFlow<Boolean>`, `val currentStatus: StateFlow<StreamStatus>`, `val history: StateFlow<List<StatusEvent>>`, `fun setMonitoringEnabled(enabled: Boolean)`. `class MainFragment : Fragment()` and `class SettingsFragment : Fragment()`, replacing `FirstFragment`/`SecondFragment` as the nav graph's two destinations (ids renamed `mainFragment`/`settingsFragment`).

Both fragments and the nav graph are done as one task because the nav graph references both destinations by class name — splitting them would leave the project non-compiling between tasks, which fails the "independently testable deliverable" bar from the planning process.

`MainViewModel` extends `AndroidViewModel` (not plain `ViewModel`) specifically so it can reach `AppContainer.getRepository(application)` without a custom `ViewModelProvider.Factory` — `by viewModels()` already knows how to construct an `AndroidViewModel` via its built-in default factory.

- [ ] **Step 1: Delete the old FirstFragment/SecondFragment template files**

```bash
git rm app/src/main/java/com/example/twitchnetworknotifier/FirstFragment.kt app/src/main/java/com/example/twitchnetworknotifier/SecondFragment.kt app/src/main/res/layout/fragment_first.xml app/src/main/res/layout/fragment_second.xml
```

- [ ] **Step 2: Implement MainViewModel**

`app/src/main/java/com/example/twitchnetworknotifier/ui/main/MainViewModel.kt`:

```kotlin
package com.example.twitchnetworknotifier.ui.main

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.SharingStarted
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
```

- [ ] **Step 3: Create the fragment_main.xml layout**

`app/src/main/res/layout/fragment_main.xml`:

```xml
<?xml version="1.0" encoding="utf-8"?>
<androidx.constraintlayout.widget.ConstraintLayout xmlns:android="http://schemas.android.com/apk/res/android"
    xmlns:app="http://schemas.android.com/apk/res-auto"
    xmlns:tools="http://schemas.android.com/tools"
    android:layout_width="match_parent"
    android:layout_height="match_parent"
    android:padding="16dp"
    tools:context=".ui.main.MainFragment">

    <com.google.android.material.materialswitch.MaterialSwitch
        android:id="@+id/switch_monitoring"
        android:layout_width="wrap_content"
        android:layout_height="wrap_content"
        android:text="@string/monitoring_toggle_label"
        app:layout_constraintTop_toTopOf="parent"
        app:layout_constraintStart_toStartOf="parent" />

    <TextView
        android:id="@+id/text_status"
        android:layout_width="wrap_content"
        android:layout_height="wrap_content"
        android:layout_marginTop="16dp"
        android:textSize="16sp"
        app:layout_constraintTop_toBottomOf="@id/switch_monitoring"
        app:layout_constraintStart_toStartOf="parent" />

    <androidx.recyclerview.widget.RecyclerView
        android:id="@+id/recycler_history"
        android:layout_width="match_parent"
        android:layout_height="0dp"
        android:layout_marginTop="16dp"
        app:layout_constraintTop_toBottomOf="@id/text_status"
        app:layout_constraintBottom_toBottomOf="parent"
        app:layout_constraintStart_toStartOf="parent"
        app:layout_constraintEnd_toEndOf="parent" />

</androidx.constraintlayout.widget.ConstraintLayout>
```

- [ ] **Step 4: Implement MainFragment**

`app/src/main/java/com/example/twitchnetworknotifier/ui/main/MainFragment.kt`:

```kotlin
package com.example.twitchnetworknotifier.ui.main

import android.Manifest
import android.app.AlertDialog
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.ContextCompat
import androidx.fragment.app.Fragment
import androidx.fragment.app.viewModels
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.recyclerview.widget.LinearLayoutManager
import com.example.twitchnetworknotifier.R
import com.example.twitchnetworknotifier.databinding.FragmentMainBinding
import com.example.twitchnetworknotifier.monitor.StreamMonitorService
import com.example.twitchnetworknotifier.monitor.model.StreamStatus
import kotlinx.coroutines.launch

class MainFragment : Fragment() {

    private var _binding: FragmentMainBinding? = null
    private val binding get() = _binding!!

    private val viewModel: MainViewModel by viewModels()
    private val historyAdapter = HistoryAdapter()
    private var isUpdatingSwitchProgrammatically = false

    private val requestNotificationPermission = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { /* no-op: monitoring proceeds regardless; alerts just won't show if denied */ }

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        _binding = FragmentMainBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        binding.recyclerHistory.layoutManager = LinearLayoutManager(requireContext())
        binding.recyclerHistory.adapter = historyAdapter

        binding.switchMonitoring.setOnCheckedChangeListener { _, isChecked ->
            if (isUpdatingSwitchProgrammatically) return@setOnCheckedChangeListener
            if (isChecked) {
                maybeRequestNotificationPermission()
                viewModel.setMonitoringEnabled(true)
                StreamMonitorService.start(requireContext(), showWelcome = true)
            } else {
                setSwitchChecked(true)
                showToggleOffConfirmation()
            }
        }

        viewLifecycleOwner.lifecycleScope.launch {
            viewLifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
                launch { viewModel.monitoringEnabled.collect { setSwitchChecked(it) } }
                launch { viewModel.currentStatus.collect { updateStatusText(it) } }
                launch { viewModel.history.collect { historyAdapter.submitList(it) } }
            }
        }
    }

    private fun setSwitchChecked(checked: Boolean) {
        isUpdatingSwitchProgrammatically = true
        binding.switchMonitoring.isChecked = checked
        isUpdatingSwitchProgrammatically = false
    }

    private fun updateStatusText(status: StreamStatus) {
        binding.textStatus.text = when (status) {
            StreamStatus.UNKNOWN -> getString(R.string.status_unknown)
            StreamStatus.LIVE -> getString(R.string.status_live)
            StreamStatus.OFFLINE -> getString(R.string.status_offline)
            StreamStatus.CONNECTION_ISSUE -> getString(R.string.status_connection_issue)
        }
    }

    private fun showToggleOffConfirmation() {
        AlertDialog.Builder(requireContext())
            .setTitle(R.string.dialog_toggle_off_title)
            .setPositiveButton(R.string.dialog_yes) { _, _ ->
                viewModel.setMonitoringEnabled(false)
                StreamMonitorService.stop(requireContext())
            }
            .setNegativeButton(R.string.dialog_no, null)
            .setCancelable(false)
            .show()
    }

    private fun maybeRequestNotificationPermission() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) return
        val granted = ContextCompat.checkSelfPermission(
            requireContext(),
            Manifest.permission.POST_NOTIFICATIONS
        ) == PackageManager.PERMISSION_GRANTED
        if (!granted) {
            requestNotificationPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
        }
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }
}
```

- [ ] **Step 5: Add the new status/label strings**

In `app/src/main/res/values/strings.xml`, add (and remove the now-unused `first_fragment_label`, `second_fragment_label`, `next`, `previous`, `lorem_ipsum` entries — they belonged to the template placeholder screens):

```xml
<string name="main_fragment_label">Twitch Network Notifier</string>
<string name="settings_fragment_label">Settings</string>
<string name="monitoring_toggle_label">Monitor stream</string>
<string name="status_unknown">Monitoring off</string>
<string name="status_live">Monitoring — Live</string>
<string name="status_offline">Monitoring — Offline</string>
<string name="status_connection_issue">Monitoring — Connection issue</string>
```

- [ ] **Step 6: Update nav_graph.xml to rename FirstFragment → MainFragment**

Replace the entire contents of `app/src/main/res/navigation/nav_graph.xml` with:

```xml
<?xml version="1.0" encoding="utf-8"?>
<navigation xmlns:android="http://schemas.android.com/apk/res/android"
    xmlns:app="http://schemas.android.com/apk/res-auto"
    xmlns:tools="http://schemas.android.com/tools"
    android:id="@+id/nav_graph"
    app:startDestination="@id/mainFragment">

    <fragment
        android:id="@+id/mainFragment"
        android:name="com.example.twitchnetworknotifier.ui.main.MainFragment"
        android:label="@string/main_fragment_label"
        tools:layout="@layout/fragment_main">

        <action
            android:id="@+id/action_mainFragment_to_settingsFragment"
            app:destination="@id/settingsFragment" />
    </fragment>
    <fragment
        android:id="@+id/settingsFragment"
        android:name="com.example.twitchnetworknotifier.ui.settings.SettingsFragment"
        android:label="@string/settings_fragment_label"
        tools:layout="@layout/fragment_settings">

        <action
            android:id="@+id/action_settingsFragment_to_mainFragment"
            app:destination="@id/mainFragment" />
    </fragment>
</navigation>
```

- [ ] **Step 7: Implement SettingsViewModel**

`app/src/main/java/com/example/twitchnetworknotifier/ui/settings/SettingsViewModel.kt`:

```kotlin
package com.example.twitchnetworknotifier.ui.settings

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.SharingStarted.Companion.WhileSubscribed
import androidx.lifecycle.viewModelScope
import com.example.twitchnetworknotifier.monitor.data.Settings
import com.example.twitchnetworknotifier.monitor.data.SettingsStore
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
```

- [ ] **Step 8: Create the fragment_settings.xml layout**

`app/src/main/res/layout/fragment_settings.xml`:

```xml
<?xml version="1.0" encoding="utf-8"?>
<androidx.core.widget.NestedScrollView xmlns:android="http://schemas.android.com/apk/res/android"
    xmlns:app="http://schemas.android.com/apk/res-auto"
    xmlns:tools="http://schemas.android.com/tools"
    android:layout_width="match_parent"
    android:layout_height="match_parent"
    tools:context=".ui.settings.SettingsFragment">

    <androidx.constraintlayout.widget.ConstraintLayout
        android:layout_width="match_parent"
        android:layout_height="wrap_content"
        android:padding="16dp">

        <com.google.android.material.textfield.TextInputLayout
            android:id="@+id/layout_channel_name"
            android:layout_width="match_parent"
            android:layout_height="wrap_content"
            android:hint="@string/settings_channel_name_hint"
            app:layout_constraintTop_toTopOf="parent"
            app:layout_constraintStart_toStartOf="parent"
            app:layout_constraintEnd_toEndOf="parent">

            <com.google.android.material.textfield.TextInputEditText
                android:id="@+id/edit_channel_name"
                android:layout_width="match_parent"
                android:layout_height="wrap_content" />
        </com.google.android.material.textfield.TextInputLayout>

        <com.google.android.material.textfield.TextInputLayout
            android:id="@+id/layout_client_id"
            android:layout_width="match_parent"
            android:layout_height="wrap_content"
            android:layout_marginTop="8dp"
            android:hint="@string/settings_client_id_hint"
            app:layout_constraintTop_toBottomOf="@id/layout_channel_name"
            app:layout_constraintStart_toStartOf="parent"
            app:layout_constraintEnd_toEndOf="parent">

            <com.google.android.material.textfield.TextInputEditText
                android:id="@+id/edit_client_id"
                android:layout_width="match_parent"
                android:layout_height="wrap_content" />
        </com.google.android.material.textfield.TextInputLayout>

        <com.google.android.material.textfield.TextInputLayout
            android:id="@+id/layout_client_secret"
            android:layout_width="match_parent"
            android:layout_height="wrap_content"
            android:layout_marginTop="8dp"
            android:hint="@string/settings_client_secret_hint"
            app:passwordToggleEnabled="true"
            app:layout_constraintTop_toBottomOf="@id/layout_client_id"
            app:layout_constraintStart_toStartOf="parent"
            app:layout_constraintEnd_toEndOf="parent">

            <com.google.android.material.textfield.TextInputEditText
                android:id="@+id/edit_client_secret"
                android:layout_width="match_parent"
                android:layout_height="wrap_content"
                android:inputType="textPassword" />
        </com.google.android.material.textfield.TextInputLayout>

        <com.google.android.material.button.MaterialButton
            android:id="@+id/button_save"
            android:layout_width="wrap_content"
            android:layout_height="wrap_content"
            android:layout_marginTop="16dp"
            android:text="@string/settings_save"
            app:layout_constraintTop_toBottomOf="@id/layout_client_secret"
            app:layout_constraintStart_toStartOf="parent" />

    </androidx.constraintlayout.widget.ConstraintLayout>
</androidx.core.widget.NestedScrollView>
```

- [ ] **Step 9: Implement SettingsFragment**

`app/src/main/java/com/example/twitchnetworknotifier/ui/settings/SettingsFragment.kt`:

```kotlin
package com.example.twitchnetworknotifier.ui.settings

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.fragment.app.Fragment
import androidx.fragment.app.viewModels
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import com.example.twitchnetworknotifier.databinding.FragmentSettingsBinding
import kotlinx.coroutines.launch

class SettingsFragment : Fragment() {

    private var _binding: FragmentSettingsBinding? = null
    private val binding get() = _binding!!

    private val viewModel: SettingsViewModel by viewModels()

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        _binding = FragmentSettingsBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        viewLifecycleOwner.lifecycleScope.launch {
            viewLifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
                val settings = viewModel.settings.value
                binding.editChannelName.setText(settings.channelName)
                binding.editClientId.setText(settings.clientId)
                binding.editClientSecret.setText(settings.clientSecret)
            }
        }

        binding.buttonSave.setOnClickListener {
            viewModel.save(
                channelName = binding.editChannelName.text.toString(),
                clientId = binding.editClientId.text.toString(),
                clientSecret = binding.editClientSecret.text.toString()
            )
        }
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }
}
```

- [ ] **Step 10: Wire the Settings menu action in MainActivity**

In `app/src/main/java/com/example/twitchnetworknotifier/MainActivity.kt`, replace the `onOptionsItemSelected` body:

```kotlin
    override fun onOptionsItemSelected(item: MenuItem): Boolean {
        return when (item.itemId) {
            R.id.action_settings -> {
                findNavController(R.id.nav_host_fragment_content_main)
                    .navigate(R.id.action_mainFragment_to_settingsFragment)
                true
            }
            else -> super.onOptionsItemSelected(item)
        }
    }
```

The floating action button is a template placeholder with no purpose in this app — remove it. In the same file, delete this block from `onCreate` (it currently appears right after `setupActionBarWithNavController(navController, appBarConfiguration)`):

```kotlin
        binding.fab.setOnClickListener { view ->
            Snackbar.make(view, "Replace with your own action", Snackbar.LENGTH_LONG)
                .setAction("Action", null)
                .setAnchorView(R.id.fab).show()
        }
```

Also remove the now-unused `import com.google.android.material.snackbar.Snackbar` line from the top of the file.

In `app/src/main/res/layout/activity_main.xml`, delete the `<com.google.android.material.floatingactionbutton.FloatingActionButton ... />` element (the last child before the closing `</androidx.coordinatorlayout.widget.CoordinatorLayout>` tag), so the file ends with:

```xml
    <include layout="@layout/content_main" />

</androidx.coordinatorlayout.widget.CoordinatorLayout>
```

- [ ] **Step 11: Add the settings screen string resources**

In `app/src/main/res/values/strings.xml`, add:

```xml
<string name="settings_channel_name_hint">Twitch channel name</string>
<string name="settings_client_id_hint">Client ID</string>
<string name="settings_client_secret_hint">Client Secret</string>
<string name="settings_save">Save</string>
```

- [ ] **Step 12: Verify the full app compiles**

```bash
./gradlew :app:assembleDebug -q
```

Expected: no output, exit code 0 — confirms `MainFragment`, `SettingsFragment`, the rewired `nav_graph.xml`, and `MainActivity`'s navigation call all resolve together.

- [ ] **Step 13: Commit**

```bash
git add -A
git commit -m "feat: add MainFragment and SettingsFragment, rewire nav_graph and MainActivity"
```

---

## Task 15: Instrumented UI tests (toggle/confirmation dialog flow, history list rendering)

**Files:**
- Modify: `gradle/libs.versions.toml`
- Modify: `app/build.gradle.kts`
- Create: `app/src/androidTest/java/com/example/twitchnetworknotifier/ui/main/MainFragmentTest.kt`

**Interfaces:**
- Consumes: `MainFragment` (Task 14), `HistoryStore`/`SettingsStore` (Tasks 4, 5).

These two dependency versions (`fragment-testing` 1.8.2, `espresso-contrib` 3.7.0) were verified to resolve and run cleanly against this project's compileSdk 37 setup (post-Task-1 fix) before writing this plan.

- [ ] **Step 1: Add fragment-testing and espresso-contrib dependencies**

In `gradle/libs.versions.toml`, add to `[versions]`:

```toml
fragmentTesting = "1.8.2"
espressoContrib = "3.7.0"
```

Add to `[libraries]`:

```toml
androidx-fragment-testing = { group = "androidx.fragment", name = "fragment-testing", version.ref = "fragmentTesting" }
androidx-espresso-contrib = { group = "androidx.test.espresso", name = "espresso-contrib", version.ref = "espressoContrib" }
```

In `app/build.gradle.kts`, add to `dependencies { ... }`:

```kotlin
debugImplementation(libs.androidx.fragment.testing)
androidTestImplementation(libs.androidx.espresso.contrib)
```

- [ ] **Step 2: Write the instrumented tests**

`app/src/androidTest/java/com/example/twitchnetworknotifier/ui/main/MainFragmentTest.kt`:

```kotlin
package com.example.twitchnetworknotifier.ui.main

import androidx.fragment.app.testing.launchFragmentInContainer
import androidx.test.espresso.Espresso.onView
import androidx.test.espresso.action.ViewActions.click
import androidx.test.espresso.assertion.ViewAssertions.matches
import androidx.test.espresso.matchers.ViewMatchers.hasChildCount
import androidx.test.espresso.matchers.ViewMatchers.isChecked
import androidx.test.espresso.matchers.ViewMatchers.isDisplayed
import androidx.test.espresso.matchers.ViewMatchers.isNotChecked
import androidx.test.espresso.matchers.ViewMatchers.withId
import androidx.test.espresso.matchers.ViewMatchers.withText
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
```

- [ ] **Step 3: Run the instrumented tests**

```bash
./gradlew :app:connectedDebugAndroidTest --tests "com.example.twitchnetworknotifier.ui.main.MainFragmentTest"
```

Expected: `BUILD SUCCESSFUL`, all 3 tests pass. (Requires a connected device/emulator with notification permission grantable; if none is available, run `./gradlew :app:compileDebugKotlin :app:compileDebugAndroidTestKotlin -q` to confirm it compiles, and note in the task review that device verification is pending.)

Note: `togglingOffShowsConfirmationDialog_*` tests turn monitoring on, which calls `StreamMonitorService.start()` — this really starts the foreground service during the test, attempting one real check against whatever (likely blank) Twitch credentials are configured. That's expected and harmless (it resolves to a `CONNECTION_ISSUE`/`OFFLINE` notification at worst, no crash) and is the simplest way to test the toggle/dialog flow without restructuring `MainFragment` to inject a fake service-starter purely for testability — disproportionate for this app's size.

- [ ] **Step 4: Commit**

```bash
git add gradle/libs.versions.toml app/build.gradle.kts app/src/androidTest/java/com/example/twitchnetworknotifier/ui/main/MainFragmentTest.kt
git commit -m "test: add instrumented tests for toggle/confirmation dialog and history list"
```

---

## Task 16: Manual end-to-end verification against the real Twitch API

**Files:** None — this task is manual verification only, no code changes.

**Interfaces:** Exercises the full stack built in Tasks 1–15 end-to-end on a real device.

- [ ] **Step 1: Register a Twitch Developer application**

In the Twitch Developer Console, register a new application to obtain a Client ID and Client Secret for the Client Credentials flow, per the design spec's auth section.

- [ ] **Step 2: Install the app on a real device and enter settings**

```bash
./gradlew :app:installDebug
```

Open the app, navigate to Settings (toolbar menu), enter your Twitch channel name, Client ID, and Client Secret, tap Save.

- [ ] **Step 3: Turn monitoring on and verify the welcome notification**

Return to the main screen, toggle "Monitor stream" on, grant the notification permission prompt. Expected: a high-priority notification reading "Welcome to Twitch Network Notifier, your live stream status is now being monitored." appears, and a persistent low-priority "Monitoring active" notification appears.

- [ ] **Step 4: Verify a real status check resolves correctly**

Within 5 minutes, check the main screen's status text and the history list. Expected: status reflects your channel's actual current live/offline state, and one history entry appears showing the `UNKNOWN → LIVE` or `UNKNOWN → OFFLINE` transition.

- [ ] **Step 5: Verify offline → alert behavior**

If not currently live, start a test stream, wait for the next 5-minute check (or restart the app to trigger sooner during testing). Expected: "Your streaming is live now." alert fires, status text updates, and a new history entry is recorded. Stop the stream and wait for the next check. Expected: "Your live stream is offline, please check." alert fires.

- [ ] **Step 6: Verify connection-issue behavior**

Turn off the device's network connectivity (airplane mode) right before a scheduled check. Expected: after the 3 retries (roughly 70 seconds), "Could not verify your stream status (connection issue)." alert fires. Restore connectivity and verify the next check recovers correctly to the real status with a "back live" or no further alert if it resolves to the same status as before the outage.

- [ ] **Step 7: Verify toggle-off confirmation and reboot persistence**

Toggle "Monitor stream" off, confirm the "You sure to off monitoring?" dialog appears, tap No and verify monitoring stays on, then toggle off again and tap Yes — verify the persistent notification disappears. Toggle back on, then reboot the device (or use `adb shell am broadcast -a android.intent.action.BOOT_COMPLETED` if a physical reboot isn't practical) and verify the persistent "Monitoring active" notification reappears without opening the app.

No commit for this task — it is verification only. If any step reveals a bug, fix it in the relevant earlier task's files and re-run that task's verification step before continuing.
