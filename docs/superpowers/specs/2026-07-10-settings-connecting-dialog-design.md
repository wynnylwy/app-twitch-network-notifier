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
   fix and re-save, while the background monitor continues retrying. Once new
   credentials are saved, any in-flight old-credential retry must not overwrite
   the fresh result and must not pop a stale retry notification for the old
   credentials.

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
- **New credentials invalidate old in-flight checks.** Saving bumps a credential
  generation. Any check that used older credentials is **aborted as soon as
  possible** (a retry sleeping in backoff wakes and exits immediately) and is
  discarded in any case — it cannot change status, write history, or fire a
  notification. See "Concurrency & stale-result handling" below.
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

## Concurrency & stale-result handling

Both the Settings-initiated `checkNow()` and the background service loop's
`checkOnce()` run against the same shared repository, so two guarantees are
required:

1. **Serialization.** A `Mutex` in the repository ensures state mutations
   (`_currentStatus`, `problemNotificationCount`, history writes, alert
   emissions) never interleave — no double history rows, no clobbered status.
2. **Credential-generation guard.** The repository tracks a monotonically
   increasing `credentialGeneration` (observable, e.g. a `MutableStateFlow`)
   that bumps whenever settings are saved. Each check captures the generation
   of the credentials it read. Before applying its result (status update,
   history write, **or alert/notification emission**), it verifies the captured
   generation is still current. If a newer save has occurred, the result is
   **discarded silently** — no status change, no history row, no notification.
3. **Abort-on-save (early exit).** A stale retry is not merely discarded at the
   end — it is interrupted as soon as the save happens. The retry loop's
   backoff sleep races the generation signal instead of sleeping blindly:

   ```kotlin
   // replaces delay(backoffMillis) in the retry loop
   val invalidated = withTimeoutOrNull(backoffMillis) {
       credentialGeneration.first { it != myGen }
   } != null
   if (invalidated) return /* stale — abort, apply nothing */
   ```

   - Backoff elapses normally → continue retrying as today.
   - Save lands mid-sleep → the wait wakes **immediately**, the check aborts
     without applying anything and releases the mutex, so the just-saved
     `checkNow()` acquires it within milliseconds.
   - The generation is also re-checked before each retry API call, so a save
     landing during an API call aborts before the next call starts.

**Net effect:** saving new credentials invalidates any in-flight
old-credential check. The old check cannot overwrite the fresh new-credential
result and cannot pop a stale retry notification for the old credentials. The
new `checkNow()` result is authoritative. The two checks are never observably
parallel — the stale one is aborted (or, at latest, thrown away at its apply
step).

**Residual wait:** a save that lands while the old check is mid-HTTP-call
cannot yank the socket; the abort happens right after that call returns.
"Connecting…" therefore lasts at most roughly one API call/timeout (a few
seconds), not the full retry backoff (~70s in production).

## Components

### `StreamRepository`
- Add `private val checkMutex = Mutex()`.
- Add a `credentialGeneration` counter as a `MutableStateFlow<Long>` that bumps
  on every settings save (incremented via a `bumpCredentialGeneration()` call
  the save path invokes). A `StateFlow` (not a plain counter) so the retry
  loop's backoff sleep can race it and wake immediately on save. Persisted
  state is not required; in-memory is sufficient.
- Add `suspend fun checkNow(): StreamStatus` — bumps/reads the generation for
  the just-saved credentials, reads current settings, performs a **single**
  `apiClient.getStreamStatus(...)` call (no `performCheck` retry loop), maps the
  result to `StreamStatus`, and applies it **only if** the generation is still
  current, then returns the new status. Guarded by `checkMutex`.
- Wrap the existing `checkOnce()` body in `checkMutex.withLock { ... }` and add
  the same generation check before it applies its result, so a stale
  old-credential loop check is discarded once new credentials are saved.
- In `performCheck()`'s retry loop, replace `delay(backoffMillis)` with the
  interruptible race shown above, and re-check the generation before each retry
  API call. On invalidation the check aborts and applies nothing — `checkOnce()`
  returns the current (unchanged) status, writes no history, emits no alert —
  and the service loop simply continues on its normal interval.
- `checkOnce()` (with retry/backoff) otherwise remains the method the background
  service loop calls; its behavior is unchanged for the current-generation case.

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
  - `checkOnce()` behavior unchanged for the current-generation case.
  - **Stale-result guard:** a check whose captured generation is older than the
    current one applies nothing — no status change, no history row, no alert
    emitted. (Simulate by bumping the generation between a check's API call and
    its apply step, e.g. via a fake API client that saves/generation-bumps
    mid-call.)
  - **Abort-on-save:** with a check sleeping in retry backoff (use a test
    dispatcher / virtual time), bumping the generation wakes it immediately —
    the check exits without further API calls and applies nothing, and a
    subsequent `checkNow()` is not blocked for the remainder of the backoff.
