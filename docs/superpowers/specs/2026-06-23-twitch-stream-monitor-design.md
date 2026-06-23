# Twitch Stream Monitor — Design

## Problem

The user live-streams on Twitch to earn money. When their network or PC goes
down mid-stream, they have no way of knowing until a viewer/friend texts
them. They want an Android app that watches their Twitch channel's live
status in the background and alerts them promptly if it goes down, even if
the app's UI is closed or the phone has rebooted.

## Goals

- Check live status roughly every 5 minutes, with true 5-minute cadence
  (not WorkManager's 15-minute floor).
- Survive the app being closed/killed and the phone rebooting.
- Distinguish "couldn't reach Twitch" (connection issue) from "Twitch
  confirms you're offline" (confirmed offline), since these are the two
  things actually knowable from the phone's side — root cause (PC power,
  home network, Twitch servers) is not distinguishable from outside and is
  not attempted.
- Alert once per state change, not on every repeated check.
- Be lightweight: minimal persistent UI footprint, modest battery cost.
- Kotlin, MVVM.

## Non-goals

- Determining the literal root cause (PC powered off vs. router down vs.
  Twitch server outage) — not technically knowable from a phone polling the
  Helix API.
- Multi-channel monitoring — single channel (the user's own) only.
- Any server-side component — single APK, on-device credentials.

## Architecture

```
UI Layer
  MainActivity/Fragment (toggle, status, history list)
  SettingsActivity/Fragment (channel name, Client ID/Secret)
        |observes Flow            |writes
        v                         v
  MainViewModel              SettingsViewModel
        |                         |
        v-------------------------v
Repository Layer (single source of truth, survives UI lifecycle)
  StreamRepository
    - monitoringEnabled: Flow<Boolean>
    - currentStatus: Flow<StreamStatus>      // LIVE / OFFLINE / CONNECTION_ISSUE / UNKNOWN
    - history: Flow<List<StatusEvent>>
    - setMonitoring(on/off), recordCheck(result)
        |                  |                  |
        v                  v                  v
  TwitchApiClient    HistoryStore         SettingsStore
  (Helix API,        (DataStore;          (DataStore; channel name,
   app token mgmt)    JSON list of         Client ID/Secret,
                       StatusEvent,         toggle state)
                       capped at 50)
        ^
        | called every 5 min by
StreamMonitorService (Foreground Service)
  - START_STICKY; persistent low-importance "Monitoring active" notification
  - 5-min loop: check -> on call failure, retry at 10s/20s/40s -> if all
    fail, transition to CONNECTION_ISSUE
  - on Twitch response "not live", transition immediately to OFFLINE
    (no retry — Twitch's answer is definitive)
  - on state change: updates Repository + posts high-importance alert
    notification
        ^ started by
BootReceiver (restarts service after reboot if toggle was on)
```

MVVM is used at the UI layer (ViewModel observes Repository Flows for
rotation-safe, lifecycle-aware UI state), but the actual check/retry engine
lives in the foreground Service, since ViewModels are destroyed when the UI
is gone and this app must keep working without any UI present.

Alternatives considered: MVI (rejected — adds Intent/reducer boilerplate
with no benefit for a single-screen app) and Clean Architecture Use Cases
(rejected — extra indirection layer not justified at this scope). Both
discussed with the user and MVVM-as-diagrammed was confirmed.

## Status checking

- **Source of truth**: Twitch Helix API, `GET /streams?user_login=<channel>`.
- **Auth**: App access token via OAuth Client Credentials flow, using a
  Client ID + Client Secret the user registers once in the Twitch Developer
  Console and enters in the Settings screen. Token is cached and refreshed
  on expiry. Acceptable to keep the secret on-device since this is a
  personal-use app installed only by the user, not distributed publicly.
- **Channel name**: entered once in Settings, stored via DataStore.

## State machine

States: `UNKNOWN` (initial, before first check), `LIVE`, `OFFLINE`,
`CONNECTION_ISSUE`.

Transition rules:
- Helix call succeeds, stream data present → `LIVE`.
- Helix call succeeds, no stream data (channel not live) → `OFFLINE`
  immediately, no retry — this is a definitive answer from Twitch.
- Helix call fails/times out → retry up to 3 times with backoff 10s, 20s,
  40s (all within ~70s of the first failure, finishing well inside the
  5-minute cycle). If all retries fail → `CONNECTION_ISSUE`. If any retry
  succeeds, proceed per the rules above based on that result.

Alerting: a high-priority notification fires **only when the resolved
state differs from the previously recorded state** (e.g. `LIVE`→`OFFLINE`,
`OFFLINE`→`LIVE`, `LIVE`→`CONNECTION_ISSUE`). Repeated checks landing on the
same state produce no further notification — avoids spamming the same
alert every 5 minutes during a single ongoing outage. Every state
transition (including non-alerting ones, if any are added later) is
recorded as a `StatusEvent` row in the local history.

## Notifications

Two notification channels:
- **Monitoring (low importance, silent)**: the persistent foreground-service
  notification shown the whole time monitoring is on (e.g. "Monitoring
  active"). No sound, minimal visual weight.
- **Alerts (high importance, sound + heads-up)**: used for all four user-
  facing alert messages:
  - First toggle-on: "Welcome to Twitch Network Notifier, your live stream
    status is now being monitored."
  - `LIVE`/`UNKNOWN` → `OFFLINE`: "Your live stream is offline, please
    check."
  - `LIVE`/`UNKNOWN` → `CONNECTION_ISSUE`: "Could not verify your stream
    status (connection issue)."
  - `OFFLINE`/`CONNECTION_ISSUE` → `LIVE`: "Your streaming is live now."

Toggle-off confirmation is an in-app `AlertDialog` ("You sure to off
monitoring?" Yes/No) — not a notification. Yes stops the service and
persists the off state; No leaves monitoring running unchanged.

## Permissions & lifecycle

- `POST_NOTIFICATIONS` (Android 13+): requested at first toggle-on, not at
  app launch — contextual to when it's actually needed. If denied, show an
  in-app explanation that alerts won't work without it; toggle still
  enables monitoring (status still recorded in history) but the user is
  informed alerts are disabled.
- `FOREGROUND_SERVICE`, `FOREGROUND_SERVICE_DATA_SYNC` (type), `INTERNET`,
  `RECEIVE_BOOT_COMPLETED`: declared in the manifest, no runtime prompt
  needed (boot receiver is a normal permission).
- Service uses `START_STICKY` so Android restarts it after a resource-
  pressure kill.
- `BootReceiver` listens for `BOOT_COMPLETED`; if the persisted toggle state
  is "on", it restarts `StreamMonitorService`.

## Data model

- `StatusEvent` (plain data class): `timestamp`, `fromState`, `toState`.
  Persisted as a JSON array (via `org.json`, built into Android — no new
  dependency) inside a DataStore Preferences string entry, capped at the
  most recent 50 events. Originally specced as a Room entity, but this
  project has no Kotlin Gradle plugin applied (AGP's built-in Kotlin
  compilation is used instead), which is incompatible with KSP/kapt
  annotation processing that Room requires. A bounded JSON list in
  DataStore gives the same `Flow<List<StatusEvent>>` shape to the
  Repository with zero annotation-processing dependency — appropriate
  given the small, capped data size.
- Settings (DataStore): `channelName`, `clientId`, `clientSecret`,
  `monitoringEnabled`.

## Testing

- Unit tests (pure Kotlin, no Android deps) covering:
  - State machine transitions and the "alert only on change" rule.
  - Retry/backoff sequencing in `StreamRepository`, using a fake
    `TwitchApiClient` that can simulate success / offline / failure
    responses without hitting the real Helix API.
- Instrumented/UI tests (Espresso) covering:
  - Toggle on/off flow, including the confirmation dialog's Yes/No paths.
  - History list rendering from a seeded set of `StatusEvent` rows.

## Open items for implementation plan

- Exact Room schema/migrations strategy (new DB, so no migration concerns
  yet).
- Token refresh edge cases (expired token mid-retry sequence).
- Whether `CONNECTION_ISSUE` due to the *phone's* own network being down
  (not Twitch's) should suppress alerting until the phone's connectivity
  itself is confirmed restored — left as an implementation detail since the
  retry/backoff already mitigates the common case of brief phone-side
  network blips.
