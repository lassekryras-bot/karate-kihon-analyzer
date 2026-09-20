# Requirements — Global Progressive Navigation Ownership Fix

## Purpose

Fix the architectural inconsistency discovered after the Home v2.1 corrective pass:

> Progressive top-level navigation is currently resolved correctly on Home, but older top-level pages still render their own navigation configuration.

This causes the bottom chrome to change depending on which page the user is viewing.

That is incorrect.

Top-level navigation is a **global app-shell concern**, not a Home concern and not a per-page concern.

The visible destination set for the active training profile must remain consistent across every normal app page that uses the shared chrome.

---

# 1. Core architectural rule

There must be **one global owner of top-level navigation state**.

Individual pages must not decide which destinations exist.

The rule is:

```text
Active profile
+
app/progression state
↓
Global App Navigation State
↓
Shared App Chrome
↓
Every top-level page
```

Pages only declare:

```text
which destination am I?
```

They do not declare:

```text
which destinations are visible?
```

---

# 2. Current problem

The current implementation allows Home to resolve profile/progression state and configure bottom navigation correctly.

However, when navigating to older pages, those pages still use their own bottom-navigation setup.

This creates inconsistent states such as:

```text
Home:
[ Home ] [ Learning ]

Learning:
[ Home ] [ Training ] [ Performance ] [ Settings ]
```

or other mismatched combinations.

This breaks the progressive-navigation model.

It also means:

- locked destinations can reappear,
- newly unlocked destinations disappear on other pages,
- selected-state logic differs between pages,
- profile-specific navigation is not universal,
- future unlocks would require updating multiple screens.

---

# 3. Desired result

If the active profile currently has:

```text
[ Home ] [ Learning ]
```

then every normal top-level page must show:

```text
[ Home ] [ Learning ]
```

The only difference between pages is the selected destination.

Example:

```text
Home page:
[ HOME ] [ Learning ]

Learning page:
[ Home ] [ LEARNING ]
```

Later, if Practice is unlocked:

```text
Home:
[ HOME ] [ Learning ] [ Practice ]

Learning:
[ Home ] [ LEARNING ] [ Practice ]

Practice:
[ Home ] [ Learning ] [ PRACTICE ]
```

The visible set and ordering are identical everywhere.

---

# 4. Global ownership

Introduce or formalize one application-level owner for navigation state.

Conceptually:

```kotlin
AppNavigationStateRepository
```

or:

```kotlin
AppChromeStateRepository
```

The exact class name is not binding.

It owns/resolves:

```text
visible destinations
unlocked destinations
canonical ordering
active profile relationship
unlock-announcement state
attention/discovery state where appropriate
```

It must not be owned by `HomeScreenView`.

---

# 5. State flow

Recommended conceptual flow:

```text
ProfileRepository
       +
Progression / unlock state
       +
App-level navigation rules
       ↓
AppNavigationStateResolver
       ↓
AppNavigationState
       ↓
Shared AppBottomNavigationView
```

Individual pages contribute only:

```text
selectedDestination
```

or equivalent route identity.

---

# 6. Navigation state model

A global model should continue to support concepts such as:

```kotlin
data class AppNavigationState(
    val visibleDestinations: List<AppDestination>,
    val selectedDestination: AppDestination,
    val newlyUnlockedDestinations: Set<AppDestination>,
    val attentionDestination: AppDestination?
)
```

The exact API is not binding.

Important:

- `visibleDestinations` comes from global/profile/progression state.
- `selectedDestination` comes from current navigation location.
- pages must not construct the destination list themselves.

---

# 7. One shared bottom-navigation instance

Preferred architecture:

```text
MainActivity / AppShell
├─ AppHeaderView
├─ ContentHost
└─ AppBottomNavigationView
```

The bottom navigation should be a single shared instance for normal top-level app pages.

Top-level navigation should replace/update the content host instead of each page constructing its own bottom navigation.

Conceptually:

```text
AppShell
    Header
    Current Page Content
    Shared Bottom Navigation
```

---

# 8. Remove per-page bottom-navigation ownership

Existing pages that currently instantiate or configure their own bottom navigation must be migrated.

Likely candidates include existing:

```text
Home
Learning
Training
Performance
Settings
```

or their current equivalents.

Each page must stop doing things like:

```kotlin
AppBottomNavigationView(
    items = ...
)
```

or:

```kotlin
setNavigationState(
    visibleDestinations = pageSpecificList
)
```

Instead, the shell owns the bar.

---

# 9. Page responsibility after refactor

Each top-level page should expose only its destination identity.

Examples:

```text
HomeScreenView
→ AppDestination.HOME

LearnScreenView
→ AppDestination.LEARNING

TrainingScreenView
→ AppDestination.TRAIN

PerformanceScreenView
→ AppDestination.PROGRESS

SettingsScreenView
→ AppDestination.SETTINGS
```

The exact destination names follow the existing enum.

The page must not determine whether neighboring destinations are visible.

---

# 10. Selected-state behavior

When navigation changes from Home to Learning:

```text
visible destinations remain unchanged
selectedDestination changes HOME → LEARNING
```

Do not rebuild destination availability simply because the content page changed.

Expected:

```text
Before:
visible = [HOME, LEARNING]
selected = HOME

After:
visible = [HOME, LEARNING]
selected = LEARNING
```

---

# 11. Profile-specific navigation

Navigation availability belongs to the active training profile where applicable.

Example:

```text
Profile A:
HOME + LEARNING + TRAIN

Profile B:
HOME + LEARNING
```

Switching active profile must cause the global navigation state to update.

The update must apply regardless of which top-level page is currently open.

---

# 12. Profile switch safety

If the current destination is no longer visible after switching profile:

Example:

```text
Current page = TRAIN
Profile A supports TRAIN

Switch to Profile B
Profile B does not support TRAIN
```

Required behavior:

```text
global navigation re-resolves
current destination becomes invalid
shell safely navigates to HOME
```

Do not leave the user on a hidden or inaccessible destination.

---

# 13. Unlock behavior

Unlocking a destination must update the one shared global navigation state.

Example:

```text
visible before:
[HOME]

Learning unlocks

visible after:
[HOME, LEARNING]
```

The same bar instance performs:

- reflow,
- entrance animation,
- attention/discovery pulse.

When the user later navigates to Learning, the bar persists.

Do not recreate the unlock animation simply because the page changed.

---

# 14. Unlock acknowledgement

`unlockAnnouncementSeen` or equivalent must remain global/profile-scoped state.

It must not be owned by a page instance.

Navigation between pages must not replay an already-acknowledged unlock animation.

---

# 15. Chrome visibility vs navigation availability

Keep these concepts separate:

```text
destination unlocked
destination visible in current profile/app state
chrome visible on this screen
destination selected
```

A camera or immersive flow may hide the entire chrome temporarily.

Example:

```text
TRAIN is unlocked
Bottom nav normally contains TRAIN
Recording screen hides bottom chrome
```

This must not mean TRAIN became locked.

---

# 16. Screens allowed to hide global chrome

Normal top-level pages should use the shared shell.

Certain immersive/detail flows may explicitly hide chrome.

Examples:

```text
camera recording
full-screen playback
guided practice
other immersive training flows
```

These screens must use an explicit chrome-visibility contract.

They must not create an independent alternate bottom bar.

---

# 17. Header ownership

This task primarily fixes bottom navigation.

However, verify the same architectural principle for shared header state.

The app shell should own the shared chrome frame where practical.

Pages may provide:

```text
title
subtitle
leading action
trailing action
```

but they should not create conflicting parallel chrome implementations.

Do not broaden this task into a full second header rewrite unless required.

---

# 18. Home changes

Home should stop being the source of truth for navigation visibility.

Home may still resolve its own Sensei content state:

```text
NO_PROFILE
ACTIVE_PROFILE_LEARNING
ACTIVE_PROFILE_TRAINING
```

But Home must consume the same global navigation state as every other top-level page.

Home may trigger progression events that cause a global navigation update.

Example:

```text
Profile created
→ progression changes
→ global navigation exposes LEARNING
```

Home does not directly own the bar configuration.

---

# 19. Learning-page changes

Learning must use the global shared bottom navigation.

When Learning is selected:

```text
selectedDestination = LEARNING
```

The visible destination list must be identical to the active profile's global list.

Learning must not restore the old hardcoded:

```text
HOME / TRAINING / PERFORMANCE / SETTINGS
```

configuration.

---

# 20. Legacy page migration

Audit all existing app pages for:

```text
AppBottomNavigationView construction
bottom-nav item list creation
hardcoded nav visibility
hardcoded HOME/TRAIN/PROGRESS/SETTINGS lists
selected-state logic
page-local destination filtering
```

Replace page-local ownership with the shared shell/global navigation state.

Do not leave duplicate legacy code paths.

---

# 21. Backward compatibility

The existing legacy constructor in `AppBottomNavigationView` may remain temporarily if needed for non-main surfaces or tests.

However, normal top-level app navigation must no longer depend on it.

If the legacy constructor becomes unused after migration, prefer removing it in a later cleanup task rather than expanding this fix unnecessarily.

---

# 22. Suggested shell architecture

Preferred conceptual structure:

```kotlin
class AppShellView(...) {
    val header: AppHeaderView
    val contentHost: ViewGroup
    val bottomNavigation: AppBottomNavigationView

    fun renderChrome(state: AppChromeState)
    fun showPage(destination: AppDestination, content: View)
}
```

Possible state:

```kotlin
data class AppChromeState(
    val header: AppHeaderState,
    val navigation: AppNavigationState,
    val visibility: AppChromeVisibility
)
```

Exact implementation is not binding.

The architectural requirement is the important part:

> one shell, one bottom navigation instance, one navigation state owner.

---

# 23. Navigation event flow

Expected event flow:

```text
User taps destination
↓
shared bottom bar emits AppDestination
↓
MainActivity / navigation coordinator handles navigation
↓
content host changes
↓
global navigation state updates selectedDestination
↓
same bottom bar re-renders selected state
```

The page itself should not intercept the event and rebuild navigation.

---

# 24. Startup behavior

On app startup:

1. resolve active training profile,
2. resolve progression/unlocks,
3. build global navigation state,
4. build Home state,
5. render shared shell,
6. show correct starting page.

The destination set must already be globally correct before the user leaves Home.

---

# 25. Process recreation

After activity recreation / app restart:

- global destination availability is rebuilt from authoritative profile/progression state,
- current destination is restored only if still valid,
- selected state is restored,
- unlock animations are not replayed if acknowledged,
- pages do not restore their own obsolete nav list.

---

# 26. Canonical order

Keep one canonical order in the global navigation system.

Current technical enum may remain:

```text
HOME
LEARNING
TRAIN
PROGRESS
SETTINGS
```

Only the unlocked/visible subset is rendered.

Pages must never reorder the subset.

Example:

```text
Unlocked:
HOME, LEARNING, PROGRESS

Rendered:
HOME, LEARNING, PROGRESS
```

not page-specific permutations.

---

# 27. Tests — global consistency

Add tests verifying that the same visible destination list persists across page changes.

Example:

```text
global visible = [HOME, LEARNING]

open Home
→ [HOME, LEARNING]

open Learning
→ [HOME, LEARNING]
```

Only selected destination changes.

---

# 28. Tests — multiple destination counts

For each global state:

```text
[HOME]
[HOME, LEARNING]
3 destinations
4 destinations
5 destinations
```

navigate through every visible top-level destination.

Verify:

- destination count stays constant,
- order stays constant,
- selected state changes correctly,
- no old hardcoded nav configuration appears.

---

# 29. Tests — profile switch

Test:

```text
Profile A visible destinations:
[HOME, LEARNING, TRAIN]

Current selected:
TRAIN

Switch Profile B:
[HOME, LEARNING]
```

Expected:

```text
TRAIN becomes unavailable
shell falls back to HOME
bar renders [HOME, LEARNING]
```

---

# 30. Tests — unlock while app is running

Test:

```text
Start:
[HOME]

unlock LEARNING

bar becomes:
[HOME, LEARNING]

navigate to Learning

bar remains:
[HOME, LEARNING]

navigate back Home

bar remains:
[HOME, LEARNING]
```

Unlock animation must not replay on each page transition.

---

# 31. Tests — immersive chrome hiding

Verify that hiding chrome for an immersive screen:

- hides the shared bar,
- does not mutate unlocked destinations,
- restores the same destination set afterward.

---

# 32. Physical-device acceptance

On device, verify:

1. Home and Learning show the same destination set.
2. Selected highlight moves correctly.
3. Switching pages does not cause the bar to visually jump to an old configuration.
4. Learning unlock persists after entering Learning.
5. Profile switching updates the bar globally.
6. No duplicate bottom bars appear.
7. Android gesture/navigation insets remain correct.
8. Unlock animation plays once, not per page.

---

# 33. Explicit exclusions

Do not implement in this task:

- final Home background,
- Learning Path redesign,
- Coach Decision Engine,
- new analytics,
- new camera logic,
- new destination taxonomy,
- Compose migration,
- broad routing rewrite,
- new profile system.

This task is specifically about **global ownership and consistency of existing progressive navigation**.

---

# 34. Definition of Done

The task is complete when:

```text
Active profile/progression determines one global nav list.

Every normal top-level page shows that same list.

Pages only change selected destination.

No page owns a separate destination set.
```

Example final behavior:

```text
Profile state:
visible = [HOME, LEARNING]

Home:
[ HOME ] [ Learning ]

Learning:
[ Home ] [ LEARNING ]

Back to Home:
[ HOME ] [ Learning ]
```

Later:

```text
visible = [HOME, LEARNING, TRAIN]

Home:
[ HOME ] [ Learning ] [ Train ]

Learning:
[ Home ] [ LEARNING ] [ Train ]

Train:
[ Home ] [ Learning ] [ TRAIN ]
```

with one shared bottom navigation and no per-page hardcoded navigation configuration.

---

# 35. Implementation priority

Fix in this order:

1. identify all page-local bottom-nav ownership,
2. introduce/confirm global navigation-state owner,
3. move shared bottom bar into app shell,
4. migrate Home to consume global state,
5. migrate Learning and other legacy top-level pages,
6. add profile-switch/fallback behavior,
7. remove obsolete per-page nav setup,
8. run regression + physical-device acceptance.

The architectural contract should be treated as non-negotiable:

> **Top-level navigation is universal app chrome. It is not page content.**
