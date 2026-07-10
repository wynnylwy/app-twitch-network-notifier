# Settings "Connecting…" Dialog & Immediate Connect Feedback

**Date:** 2026-07-10
**Status:** Approved design

## Problem

When the user changes the channel name or client secret on the Settings screen
and saves, the new values are written to DataStore but no Twitch call happens
right then. The running background service only picks up the change on its next
poll, and if it enters a retry/backoff cycle the wait is invisible. The user is
given no feedback about whether the new credentials work — they leave Settings
and passively wait for the history log on Home to update.

## Goal

Give the user immediate, explicit feedback after saving new settings:

1. Show a non-cancellable **"Connecting…"** dialog and run a check right away.
2. On success, show a non-cancellable **"Connected"** dialog with a 3-second
   countdown, then dismiss it and return to Home, where the history log shows
   the recovered ("back online") status.
3. On failure, surface the problem immediately and keep the user on Settings to
   fix and re-save, while the background monitor continues retrying.

## Decisions

- **Trigger:** the connect flow runs only when monitoring is currently **ON**.
  Saving with monitoring OFF keeps the existing behavior (persist + "Saved"
  toast, no dialog).
- **Success = reached Twitch.** Both `LIVE` and `OFFLINE` count as "Connected"
  (the credentials are valid and Twitch answered). Only a `Failure`
  (`CONNECTION_ISSUE`) is treated as a failed connect.
- **Single immediate attempt.** The dialog decision is made on **one** API call
  with **no** internal retry/backoff wait. Retries are purely the background
  monitor service's job.
- **Failure path fails closed.** On a failed connect, dismiss "Connecting…"
  immediately (no ~12s/70s wait), show a dismissible error dialog, and keep the
  user on Settings. The running monitor keeps polling in the background, so a
  later recovery still lands in the history log without blocking the user.
- **History dedup preserved.** The check writes a transition event via the
  existing `applyStatus` logic. A repeated failure while already in
  `CONNECTION_ISSUE` adds **no** new row (consistent with current behavior).
- **Home does not re-check.** Only the Settings-initiated check runs. Home
  (`MainFragment` / `MainViewModel`) merely observes the shared repository's
  `currentStatus` and `history`, which it already does — no new check is
  triggered on Home.
- **Countdown exactness.** If the screen rotates during the 3-second countdown,
  the countdown restarting is acceptable.

## Flow

### Save with monitoring OFF
Unchanged. Persist settings, show the "Saved" toast, no dialog, no navigation.

### Save with monitoring ON
1. Persist channel name / client id / client secret to DataStore. This must
   complete before the check, so the check uses the new credentials.
2. Enter the `Connecting` state → fragment shows a non-cancellable
   **"Connecting…"** dialog. Immediately run a single Twitch check
   (`StreamRepository.checkNow()` — one API call, no retry wait).
3. Branch on the single result:
   - **`LIVE` or `OFFLINE`** → enter `Connected` state → fragment swaps to a
     non-cancellable **"Connected"** dialog with a 3-second countdown. When the
     countdown finishes, **dismiss the dialog first, then `popBackStack()`** to
     Home.
   - **`CONNECTION_ISSUE`** → enter `ConnectFailed` state → fragment dismisses
     "Connecting…" and shows a dismissible error dialog
     ("Could not connect — check your credentials"). On dismiss, return to
     `Idle`; the user stays on Settings. The background monitor service keeps
     polling on its normal loop.
4. The check records its transition event through `applyStatus`, so Home
   re-renders the updated status and history automatically.

## Components

### `StreamRepository`
- Add `private val checkMutex = Mutex()`.
- Add `suspend fun checkNow(): StreamStatus` — reads current settings, performs
  a **single** `apiClient.getStreamStatus(...)` call (no `performCheck` retry
  loop), maps the result to `StreamStatus`, runs `applyStatus`, and returns the
  new status. Guarded by `checkMutex`.
- Wrap the existing `checkOnce()` body in `checkMutex.withLock { ... }` so the
  Settings-initiated `checkNow()` and the service loop's `checkOnce()` never
  overlap (no double history rows / clobbered status).
- `checkOnce()` (with retry/backoff) remains the method the background service
  loop calls; its behavior is otherwise unchanged.

### `SettingsViewModel`
- Inject the shared repository via `AppContainer.getRepository(application)`.
- Add a sealed `SaveFlowState`: `Idle`, `Connecting`, `Connected`,
  `ConnectFailed` (optionally carrying a reason), exposed as a
  `StateFlow<SaveFlowState>`.
- `save(...)` becomes: persist settings → if `monitoringEnabled` is true, emit
  `Connecting`, call `repository.checkNow()`, then emit `Connected`
  (`LIVE`/`OFFLINE`) or `ConnectFailed` (`CONNECTION_ISSUE`); if monitoring is
  off, keep the existing persist + `saveResults` toast path.
- The in-flight check runs in `viewModelScope` so it survives configuration
  changes.

### `SettingsFragment`
- Observe `saveFlowState` and **render the dialog from state** (so a rotation
  re-shows the correct dialog rather than losing it):
  - `Connecting` → non-cancellable "Connecting…" dialog
    (`setCancelable(false)`, no buttons).
  - `Connected` → non-cancellable "Connected" dialog with a `CountDownTimer`
    updating a 3-second countdown; on finish, dismiss then `popBackStack()`.
  - `ConnectFailed` → dismissible `AlertDialog` with an OK button; on dismiss,
    reset the state to `Idle`.
- Guard navigation so a redraw during the countdown cannot `popBackStack()`
  twice.

### `MainFragment` / `MainViewModel`
- No changes. They already observe `currentStatus` and `history` from the shared
  repository and never trigger checks.

## Error Handling & Edge Cases

- Non-cancellable dialogs use `setCancelable(false)` and expose no dismiss
  affordance except the error dialog's OK button.
- The check lives in `viewModelScope`; on config change the fragment re-derives
  the visible dialog from `saveFlowState`.
- Navigation is guarded against double-pop during the countdown.
- Monitoring toggled off mid-flow is treated as out of scope (not expected in
  practice).

## Testing

- `SettingsViewModel` unit tests with a fake repository:
  - monitoring ON + success (`LIVE`/`OFFLINE`) → `Connecting → Connected`.
  - monitoring ON + failure (`CONNECTION_ISSUE`) → `Connecting → ConnectFailed`.
  - monitoring OFF → no connect flow; settings persisted and existing toast path
    used.
- `StreamRepositoryTest`:
  - `checkNow()` performs a single API call and returns the mapped status.
  - `checkNow()` records the transition event (and does not duplicate when
    already in the same state).
  - `checkOnce()` behavior unchanged.
