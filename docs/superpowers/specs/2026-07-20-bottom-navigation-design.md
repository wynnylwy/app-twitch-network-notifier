# Bottom Navigation Bar (replacing overflow menu)

## Problem

The app currently exposes the Settings screen through a toolbar overflow item (`action_settings`, the "3 dots" button, defined in `menu_main.xml`). This enhancement replaces it with a `BottomNavigationView` that switches between Home (`MainFragment`) and Settings (`SettingsFragment`), showing which tab is active and behaving safely when the active tab is re-tapped.

## Architecture

The app is a single-Activity app using Jetpack Navigation Component (`nav_graph.xml`) with two fragment destinations, `mainFragment` (start destination) and `settingsFragment`. No structural change to the nav graph is needed — both fragments become "top-level" destinations from the `AppBarConfiguration`'s point of view, so neither shows a back arrow when reached via the bottom nav.

Navigation Component's `NavigationUI.setupWithNavController(bottomNavigationView, navController)` is the standard, idiomatic wiring for this exact scenario:
- It attaches an `OnItemSelectedListener` that navigates using `popUpTo(startDestination, saveState=true)` + `launchSingleTop=true` + `restoreState=true`. This means switching tabs preserves each fragment's state, and re-selecting the currently active tab is a no-op (no crash, no reload, no duplicate back stack entries).
- It attaches an `OnDestinationChangedListener` that keeps the bottom nav's selected item in sync with the current destination, regardless of what triggered the navigation (bottom nav tap, or a programmatic `navigate()`/`popBackStack()` call elsewhere).

This second point matters because `SettingsFragment` already calls `findNavController().popBackStack()` after a successful save (to auto-return to Home following a countdown dialog). That call continues to work unchanged and will correctly re-highlight the Home tab.

## Changes

**`res/menu/menu_bottom_nav.xml`** (new) — two items, ids `@id/mainFragment` and `@id/settingsFragment` (matching the nav graph's fragment ids so `NavigationUI` can resolve them directly), titled "Home" and "Settings".

**Icons** (new vector drawables) — the app currently has no icon assets beyond the launcher icon. Add two simple outlined Material-style vector drawables: `ic_home_24.xml` and `ic_settings_24.xml`. A single icon per destination is sufficient (the bottom nav's built-in selected/unselected tinting via `?attr/colorOnSurface` / `?attr/colorPrimary` communicates active state without needing separate filled/outline variants).

**`res/values/strings.xml`** — add `nav_home` ("Home") string for the new Home tab label (distinct from `main_fragment_label`, which stays the toolbar title). Reuse existing `settings_fragment_label` ("Settings") for the Settings tab label. Remove `action_settings` (no longer referenced anywhere once the overflow menu is gone).

**`res/menu/menu_main.xml`** — delete (only used by the overflow menu being removed).

**`res/layout/activity_main.xml`** — add a `com.google.android.material.bottomnavigation.BottomNavigationView` (id `bottom_nav`) anchored to the bottom of the `CoordinatorLayout`, `menu="@menu/menu_bottom_nav"`. `content_main.xml`'s nav host container gets its bottom constraint changed from the parent to `bottom_nav`'s top, so it no longer extends underneath the bar.

No change to edge-to-edge inset handling: `MainActivity` already pads the root `CoordinatorLayout` by the system bar insets, which keeps the bottom nav clear of the gesture/nav bar without additional insets code.

**`MainActivity.kt`**:
- Remove `optionsMenu` field, `onCreateOptionsMenu`, `onOptionsItemSelected`, and the `addOnDestinationChangedListener` block that toggled `action_settings` visibility — all existed solely to manage the overflow menu item being removed.
- Widen `AppBarConfiguration` to `AppBarConfiguration(setOf(R.id.mainFragment, R.id.settingsFragment))` so both are treated as top-level (no back arrow).
- Call `NavigationUI.setupWithNavController(binding.bottomNav, navController)` after `setupActionBarWithNavController`.

## Testing

- Existing `MainFragmentTest` and other instrumented/unit tests don't reference the overflow menu, so they're unaffected.
- Manual verification (via the `run` skill / emulator): tap Settings tab → screen switches, tab highlights, no back arrow in toolbar. Tap Home tab → same. Re-tap the already-active tab → no crash, no visible reload/flicker. Trigger the existing Settings save-success flow (countdown dialog `popBackStack()`) → confirm it still lands on Home with the Home tab correctly highlighted.

## Out of scope

- No changes to `MainFragment`/`SettingsFragment` internals beyond what's listed.
- No filled/outline icon state variants, no badges, no more than two tabs.
- No Compose migration.
