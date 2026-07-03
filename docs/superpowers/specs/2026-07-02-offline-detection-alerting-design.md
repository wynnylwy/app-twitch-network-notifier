# Offline-Detection Alerting — Design

**Date:** 2026-07-02
**Status:** Approved (pending implementation plan)

## Goal

Change the monitoring flow so the app reliably tells the user when a Twitch
streamer's stream **drops (goes offline)** and when it **comes back online**,
while avoiding false alarms from transient blips and avoiding notification spam.

This is a behavior change to the existing polling loop and notification logic.
It does not add new screens or settings.

## Summary of changes vs. current behavior

| Aspect | Today | New |
|---|---|---|
| Check interval | 5 min | **2 min** |
| Retry trigger | On connection `Failure` only | On any **non-live** result (Offline or Failure) — but only when *entering* a problem state |
| Retry backoff | 10s / 20s / 40s | 10s / 20s / 40s (unchanged) |
| Notifications | Only on status *change* (deduped) | **Repeat up to 3×** per problem streak, then quiet; plus a recovery alert |
| Connection error | Folded into generic alert | **Separate track** ("connection issue"), same 3×-then-quiet cap |

## Definitions

A single API check returns one of three results:

- **Live** — streamer is streaming → status `LIVE`
- **Offline** — streamer is not streaming → status `OFFLINE`
- **Failure** — the device cannot reach Twitch (own network down or Twitch
  outage) → status `CONNECTION_ISSUE`

`OFFLINE` and `CONNECTION_ISSUE` are both "problem" states. `LIVE` is the
"healthy" state. `UNKNOWN` is the initial state before the first check.

## Behavior

### The loop

Every **2 minutes**: run one check cycle, then wait 2 minutes, repeat.
(`CHECK_INTERVAL_MILLIS` in `StreamMonitorService`.)

### Confirming a problem (the 3 retries)

The 3-attempt retry confirmation exists to rule out a transient blip before
alerting. It runs **only on the healthy → problem transition**:

- If the previous confirmed status is `LIVE` or `UNKNOWN`, and the first check
  of the cycle returns a **non-live** result, retry up to 3 times with backoff
  **10s → 20s → 40s**.
  - If any retry returns `Live`, treat the cycle result as `Live` (it was a
    blip) and stop retrying.
  - Otherwise, take the final non-live result (Offline or Failure).
- If the previous confirmed status is **already** a problem state
  (`OFFLINE` or `CONNECTION_ISSUE`), do **not** retry. Each such cycle is a
  **single quick check** — we already know it's in a problem state, so we just
  see whether it recovered or continues.

Worst case to raise the *first* offline/connection alert: ~70s of retries within
that cycle. Subsequent same-state cycles are fast (single check).

### Notifications

State is tracked with the confirmed status plus a `problemNotificationCount`
that resets to 0 whenever the confirmed status changes.

Per cycle, after determining `newStatus`:

- **newStatus == LIVE**
  - If previous status was `OFFLINE` or `CONNECTION_ISSUE` → send
    **"back online"** notification (recovery).
  - If previous status was `LIVE` → no notification.
- **newStatus == OFFLINE**
  - If `problemNotificationCount < 3` → send **"stream offline"** notification,
    increment count. Else stay quiet.
- **newStatus == CONNECTION_ISSUE**
  - If `problemNotificationCount < 3` → send **"connection issue"**
    notification, increment count. Else stay quiet.
- **newStatus == UNKNOWN** — not produced by a check; no notification.

Because the count resets on any status change, transitioning between problem
states (e.g. `OFFLINE` → `CONNECTION_ISSUE`) starts a fresh set of 3, and a new
offline streak later also gets a fresh 3.

"Back online" fires only when the streamer is confirmed `LIVE`. A recovered
network that still shows the streamer offline does **not** fire "back online" —
it transitions `CONNECTION_ISSUE` → `OFFLINE` and begins the offline reminders.

### Example timeline (offline then recovery)

```
8:00  check -> offline; retry 10/20/40s -> confirmed OFFLINE   notify #1 "offline"
8:02  single check -> offline                                  notify #2 "offline"
8:04  single check -> offline                                  notify #3 "offline"
8:06  single check -> offline                                  (quiet, cap reached)
...   single checks every 2 min, silent
10:30 single check -> LIVE                                     notify "back online"
      count reset; a later offline streak gets 3 fresh alerts
```

## History log

The history store continues to record **only real status transitions**
(`newStatus != previousStatus`), not the repeated reminders, so the history
screen stays uncluttered.

## Components affected

- **`StreamMonitorService`** — `CHECK_INTERVAL_MILLIS`: `5 * 60 * 1000` →
  `2 * 60 * 1000`. The alert collection mechanism (`repository.alerts` →
  `notificationHelper.showAlert(...)`) is unchanged.
- **`StreamRepository`** — the core change:
  - Add in-memory `problemNotificationCount` (reset on status change).
  - `checkOnce()`: read `previous` status; do one API check; run the
    retry-confirm only on the healthy → problem transition; map to `newStatus`;
    apply the notification rules above; record history only on real transitions;
    update `_currentStatus`.
  - Replace `performCheckWithRetry` (Failure-only) with a confirm helper that
    retries a non-live result and returns early on `Live`.
  - Continue emitting alerts via the existing `_alerts` SharedFlow. The emitted
    `StatusEvent`'s `toState` drives the message; for a repeat reminder,
    `fromState == toState` is acceptable (message still resolves correctly).
- **`NotificationHelper`** — expected unchanged; existing messages already map
  `LIVE` → "back live", `OFFLINE` → "offline", `CONNECTION_ISSUE` →
  "connection issue". Revisit wording only if the recovery message reads poorly.

## Testing

Update `StreamRepositoryTest` and add cases for:

- Healthy → offline runs the retry confirmation; a retry returning `Live`
  cancels the alarm (no offline alert, stays LIVE).
- Confirmed offline sends exactly 3 offline notifications across consecutive
  cycles, then stays quiet while remaining offline.
- Recovery to `LIVE` after offline sends one "back online" alert and resets the
  count (a subsequent offline streak yields 3 fresh alerts).
- `CONNECTION_ISSUE` is a separate track with its own 3-notification cap.
- While already in a problem state, cycles do a **single check** (no retry
  delays incurred).
- History records only transitions, not repeated reminders.

## Out of scope

- No new user-facing settings (interval and caps are constants).
- No changes to the settings screen, main UI, or Twitch API client.
- Notification-count state is in-memory only; it need not survive a service
  restart.
