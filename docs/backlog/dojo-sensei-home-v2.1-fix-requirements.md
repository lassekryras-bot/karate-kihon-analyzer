# Requirements — Dojo Sensei Home v2.1 Corrective Pass

## Purpose

Fix the issues found during physical-device testing of **Dojo Sensei Home v2** without expanding the task into the full Learning Path or Coach Decision Engine.

This corrective pass addresses four concrete problems:

1. Home currently ignores an already-active training profile and can trap an existing user in first-run profile onboarding.
2. The no-profile flow goes through the wrong profile surface instead of entering profile creation directly.
3. The Sensei SVG / SVG renderer produces a visible white spike/line artifact.
4. The Home hero composition is too small and leaves excessive unused screen space.

The result should support the correct Home behavior for both a brand-new user and an existing training profile.

---

# 1. Core startup rule

The real active training profile is authoritative.

Home must **not** choose first-run onboarding solely from persisted `homeOnboardingPhase`.

Startup resolution must begin with the actual profile repository:

```text
Resolve active training profile
        |
        +-- none
        |      -> NO_PROFILE_HOME
        |
        +-- active profile
               |
               +-- app-learning path incomplete
               |      -> ACTIVE_PROFILE_LEARNING_HOME
               |
               +-- app-learning path complete
                      -> ACTIVE_PROFILE_TRAINING_HOME
```

Persisted Home onboarding state may refine presentation inside a branch, but it must never make an existing active profile appear unknown.

---

# 2. Two visual Home modes, three behavioral states

There are two principal visual versions of Home:

## A. No active training profile

First-run / profile-creation Home.

## B. Active training profile

Known-user Sensei Home.

The active-profile version has two behavioral states:

```text
Learning incomplete
Training-ready
```

These should reuse the same Sensei Home layout rather than become separate screens.

---

# 3. State A — No active training profile

When no active training profile exists:

## Header

```text
Title: Karate Analyzer
Subtitle: Welcome
Trailing profile: UnknownProfile
```

## Bottom navigation

```text
HOME only
HOME selected
```

## Sensei

Welcome message appropriate for a new user.

Suggested copy:

```text
Osu! Welcome.
Let's get you ready to begin.
```

## Primary action

The primary action should be:

```text
Create profile
```

This is a real action, not merely an instruction to find the profile icon.

### Critical navigation requirement

Tapping **Create profile** must open the **new-profile creation form directly**.

Required:

```text
Home
→ Create profile
→ New Profile form
```

Not:

```text
Home
→ Profile landing / profile chooser
→ Create profile
→ New Profile form
```

The first-run Home should remove this unnecessary intermediate step.

---

# 4. Unknown profile avatar behavior

The unknown profile avatar remains useful as a visible indication that no profile exists.

It may also remain clickable.

However:

- the primary onboarding CTA is `Create profile`,
- first-run success must not depend on discovering the top-right profile icon,
- clicking the unknown profile icon should enter the same appropriate creation flow or an intentionally equivalent route,
- the user must not become trapped on Home because the active-profile lookup failed.

---

# 5. Profile completion

After successful creation of the first / new active training profile:

1. the real profile repository is updated,
2. Home re-resolves from repository state,
3. the header displays the active profile,
4. `Welcome <name>` may be shown,
5. Home enters the active-profile branch.

Do not rely on a stale persisted onboarding flag when the repository already has a valid active profile.

---

# 6. State B — Active profile, app-learning incomplete

When an active profile exists but the app's "how to use Karate Analyzer" learning path is not complete:

## Header

```text
Title: Karate Analyzer
Subtitle: Welcome <name>
Trailing profile: Active profile avatar
```

## Navigation

Learning must be available if its unlock prerequisite is already satisfied.

At minimum:

```text
HOME + LEARNING
```

Home remains selected while the user is on Home.

## Sensei message

Sensei greets the known user and directs them toward Learning.

Suggested copy:

```text
Osu, <name>.
Let's continue learning how to use Karate Analyzer.
```

## Action

Use a contextual Learning action.

Example:

```text
Continue Learning
```

The persistent curriculum/progress card still belongs on the Learning page.

Home must not recreate the continual-learning card.

---

# 7. Learning completion source of truth

Introduce or consume one explicit semantic state:

```text
hasCompletedAppLearningPath
```

The exact name may differ.

This must represent:

> The user has completed the initial learning path that teaches how to use the app.

It is **not** equivalent to:

```text
Learning destination unlocked
Learning destination opened once
Learning introduction acknowledged
```

These are different states.

If the Learning subsystem already exposes a completion state, reuse it.

If it does not yet exist, add the smallest temporary boundary needed so Home can consume it without embedding Learning progression logic in `HomeScreenView`.

The Learning system should become the final owner of this value later.

---

# 8. State C — Active profile, app-learning complete

When:

```text
activeProfile != null
AND
hasCompletedAppLearningPath == true
```

Home becomes the simple training-ready version.

## Sensei message

Suggested temporary copy:

```text
Osu, <name>.
Ready for some training?
```

## Primary action

For now, use one fixed training recommendation:

```text
Record 30 straight punches
```

The exact repetition count must be centralized and easy to change later.

Recommended v2.1 temporary value:

```text
30
```

Do not scatter `30` as a magic number across UI and navigation code.

## Action behavior

Tapping the action must enter the existing **Record & Analyze** flow for straight punches with the intended repetition plan preselected where supported.

Do not implement Coach Decision Engine logic.

This fixed recommendation is a temporary bridge to the future coaching system.

---

# 9. Home action model

The action card should support at least these v2.1 actions:

```text
CREATE_PROFILE
CONTINUE_LEARNING
RECORD_STRAIGHT_PUNCHES
```

Prefer a typed action model rather than branching on button label text.

Conceptual example:

```kotlin
sealed interface HomeAction {
    data object CreateProfile : HomeAction
    data object ContinueLearning : HomeAction
    data class RecordStraightPunches(val repetitions: Int) : HomeAction
}
```

Exact naming is not binding.

---

# 10. Fix startup resolution

Current faulty behavior:

```text
Existing active profile
→ Home still shows UnknownProfile
→ Home says "Tap your profile above"
→ user is stuck in first-run flow
```

Required behavior:

```text
Existing active profile
→ repository resolves profile
→ active avatar shown
→ Welcome <name>
→ learning/training Home chosen from real progression state
```

Add regression coverage specifically for:

```text
App already contains an active profile
+
Home onboarding preference still says PROFILE_DISCOVERY
```

Expected:

```text
active-profile Home wins
```

The stale onboarding state must be reconciled or ignored.

---

# 11. SVG white-spike defect

Physical-device testing shows a thin white spike / line extending from the Sensei artwork.

This must be diagnosed before applying a visual workaround.

## Step 1 — determine source

Open/render the exact `sensei_home_welcome.svg` outside the custom Android renderer.

### If the artifact is present outside the app

The SVG path data is malformed.

Fix the offending path/control point in the asset.

### If the SVG is correct outside the app

The defect is in:

```text
FullColorSvgRepository / FullColorSvgView
```

Fix the shared renderer.

Because this renderer is also used by Skill Coach, do not hide a renderer defect by clipping or painting over the Home asset.

## Required regression

Add a focused test or fixture covering the path command / shape behavior responsible for the spike if the shared renderer is the cause.

---

# 12. No clipping workaround for the spike

Do not "fix" the white spike by:

- cropping the Sensei tighter,
- placing a solid rectangle over it,
- moving Sensei off-screen,
- changing the Home background to hide it,
- selectively skipping an unexplained SVG path.

The underlying malformed asset or renderer behavior must be corrected.

---

# 13. Hero composition — current problem

The physical-device screenshot shows:

- Sensei is too small,
- speech bubble is too small relative to the screen,
- Sensei and bubble occupy only the upper part of the content area,
- a very large unused area remains between hero and bottom navigation,
- Sensei reads as a small illustration beside text rather than the main Home character.

v2.1 must recompose the hero.

---

# 14. Hero sizing target

For a normal compact portrait phone:

## Hero region

Target approximately:

```text
55–65% of available Home content height
```

where available content excludes shared top chrome and bottom navigation.

This is a design target, not a hard fixed-height constant.

Use responsive constraints rather than a screen-pixel assumption.

## Sensei width

Target approximately:

```text
42–48% of available screen/content width
```

subject to sensible min/max dimensions.

Sensei should remain visually dominant.

## Sensei visibility

Keep visible:

- face,
- upper body,
- welcoming/presenting hand,
- black belt.

Do not shrink Sensei until the black belt or gesture becomes unreadable.

---

# 15. Sensei placement

On compact portrait screens:

- anchor Sensei toward lower-left / center-left of hero,
- allow the lower body to approach the bottom edge of the hero,
- avoid large unused margins above/below the character,
- preserve aspect ratio,
- no non-uniform stretching.

The layout must not depend on exact facial or hand pixel coordinates so the artwork remains replaceable.

---

# 16. Speech bubble proportions

The speech bubble should read as an integrated part of the Sensei composition.

Target approximately:

```text
45–52% of available content width
```

where appropriate.

Requirements:

- place bubble around Sensei head/chest/presenting-hand height,
- visually associate the tail with the Sensei,
- avoid excessive horizontal separation,
- allow multi-line localized text,
- allow the bubble to grow vertically for longer copy,
- do not make the bubble a tiny floating card in a large empty region.

---

# 17. Hero + action-card composition

The page should use the available vertical space intentionally.

Preferred hierarchy:

```text
Shared Header

Sensei + speech bubble
Sensei + speech bubble
Sensei + gesture
Sensei / belt

Current action card

Shared Bottom Navigation
```

Avoid:

```text
tiny Sensei + tiny bubble

large unused area

large unused area

Bottom navigation
```

---

# 18. Adaptive action-card behavior

When a Home state has a primary action card, it should use the lower portion of the available content area.

States:

```text
NO_PROFILE
    -> Create profile action card

LEARNING_INCOMPLETE
    -> Continue Learning action card

TRAINING_READY
    -> Record 30 straight punches action card
```

Do not reserve a large invisible action-card region when no action is shown.

The hero may expand if a future state legitimately has no lower card.

---

# 19. Background for v2.1

Do **not** add the final dojo background as part of this fix.

Use the normal warm application background.

The page must look compositionally correct on a plain background.

This is an explicit acceptance criterion:

> If Home only looks balanced after the final dojo artwork is added, the v2.1 layout is not complete.

The future layer contract remains:

```text
Layer 1: dojo background
Layer 2: Sensei
Layer 3: speech bubble / action UI
```

---

# 20. Dark-theme behavior

The physical-device screenshot currently produces a very dark/black empty Home canvas.

Verify whether this is:

- intended app dark theme behavior,
- inherited system dark mode,
- or an unintended background from the hero/root layout.

For v2.1:

- use existing app theme resources,
- do not hard-code black as the Home hero background,
- ensure the hero/root surface follows the intended Karate Analyzer theme,
- ensure Sensei, bubble, and cards retain appropriate contrast.

If the application intentionally supports dark theme, Home must still have a deliberate dark-theme composition rather than an accidental empty black field.

Do not redesign the entire app theme in this task.

---

# 21. Responsive behavior

## Tall compact phone

Use additional space to make the hero feel generous.

Do not leave the additional height entirely empty.

## Short phone

Allowed:

- slightly reduce hero height,
- slightly reduce Sensei size,
- allow vertical scrolling if necessary.

The primary action must remain reachable.

## Landscape / short-height window

Prefer reflow/scrolling over shrinking Sensei and text to unusable sizes.

## Wider window

Cap Sensei and bubble dimensions.

Do not let both scale indefinitely simply because more width exists.

---

# 22. State resolution API

Prefer a small resolver above the view.

Conceptually:

```kotlin
fun resolveHomeState(
    activeProfile: Profile?,
    appLearningComplete: Boolean,
    ...
): SenseiHomeState
```

Expected priority:

```text
if activeProfile == null:
    NO_PROFILE
else if !appLearningComplete:
    ACTIVE_PROFILE_LEARNING
else:
    ACTIVE_PROFILE_TRAINING
```

The view renders this state.

`HomeScreenView` must not independently query child visibility to determine which user state exists.

---

# 23. Reconcile obsolete persisted onboarding state

The current v2 persistence includes values such as:

```text
homeOnboardingPhase
isProfileSetupComplete
hasAcknowledgedLearningUnlock
```

Do not allow these values to override real repository facts.

When loading Home:

```text
ProfileRepository active profile
>
derived profile setup state
>
legacy Home onboarding preference
```

If persisted state contradicts authoritative profile state, reconcile it safely.

Example:

```text
active profile exists
homeOnboardingPhase = PROFILE_DISCOVERY
```

Result:

```text
do NOT show PROFILE_DISCOVERY
resolve active-profile branch
```

---

# 24. Profile switching

Home must re-resolve when the active training profile changes.

Examples:

```text
Profile A = learning complete
→ Training-ready Home

switch to Profile B = learning incomplete
→ Learning Home
```

If profile progression is not yet profile-scoped in the current implementation, document the temporary limitation instead of silently treating all users as the same.

---

# 25. Navigation ownership

Reuse shared Chrome v1 navigation.

Home must not create its own alternative navigation system.

Expected actions:

```text
Create profile
→ existing direct create-profile route

Continue Learning
→ Learning destination / relevant Learning entry point

Record 30 straight punches
→ existing Record & Analyze flow
```

Do not duplicate destination screens inside Home.

---

# 26. Tests — state resolution

Add tests for:

### No profile

```text
activeProfile = null
→ NO_PROFILE Home
→ UnknownProfile shown
→ Create profile action
```

### Existing profile + stale first-run state

```text
activeProfile != null
persisted phase = PROFILE_DISCOVERY
→ active-profile Home
→ never UnknownProfile onboarding
```

### Active profile + learning incomplete

```text
→ known avatar
→ Welcome <name>
→ Continue Learning action
```

### Active profile + learning complete

```text
→ known avatar
→ training-ready Sensei message
→ Record 30 straight punches action
```

### Profile switch

Verify Home re-resolves after active-profile change where supported.

---

# 27. Tests — navigation

Verify:

- Create profile skips the profile landing/chooser page.
- Profile creation success returns into correct active-profile Home state.
- Continue Learning goes to Learning.
- Learning recommendation does not add continual-learning UI to Home.
- Record straight punches opens the existing recording flow with the expected activity/repetition context.
- Home remains compatible with shared bottom navigation.

---

# 28. Tests — SVG

At minimum:

1. render/parse `sensei_home_welcome.svg`,
2. verify the known bad geometry no longer appears through the responsible layer,
3. if renderer bug: cover the relevant SVG command/path case in `FullColorSvgView` tests,
4. verify Skill Coach SVG rendering does not regress.

A physical-device visual check is still required.

---

# 29. Tests — visual/layout

Manual device acceptance should include:

- Sensei no longer visually tiny,
- black belt clearly visible,
- welcoming hand clearly visible,
- bubble visually associated with Sensei,
- no white spike,
- no excessive unused vertical region,
- action card visible and reachable,
- correct active avatar,
- no unintended black hero background,
- tall portrait phone,
- shorter portrait window,
- one landscape/short-height check.

---

# 30. Explicit exclusions

Do not implement:

- final dojo background,
- high-detail final Sensei artwork,
- Coach Decision Engine,
- 7-day coach memory,
- dynamic training recommendations,
- dojo schedule,
- preference weighting,
- full Learning Path architecture,
- new analysis metrics,
- camera/MediaPipe changes,
- Compose migration.

The fixed straight-punch recommendation is intentionally temporary.

---

# 31. Definition of Done

## Scenario A — fresh app / no profile

```text
Open app
→ Karate Analyzer / Welcome
→ Unknown profile
→ large Sensei hero
→ Create profile
→ tap Create profile
→ direct new-profile form
```

No unnecessary profile landing screen.

## Scenario B — existing active profile, learning incomplete

```text
Open app
→ active avatar is immediately recognized
→ Welcome <name>
→ Sensei greets known user
→ Continue Learning
```

The user is never sent through first-run profile discovery.

## Scenario C — active profile, learning complete

```text
Open app
→ active avatar
→ Welcome <name>
→ Sensei greets user
→ Record 30 straight punches
→ existing Record & Analyze flow
```

## Visual acceptance

On the physical test phone:

- Sensei + bubble use the majority of the hero area,
- no white SVG spike is visible,
- the page does not contain a huge accidental empty black region,
- the composition works without the final dojo background.

---

# 32. Implementation priority

Fix in this order:

1. **Active-profile state resolution**
2. **Direct create-profile route**
3. **SVG spike diagnosis and root fix**
4. **Hero proportions / unused-space correction**
5. **Learning-incomplete active-profile Home**
6. **Temporary training-ready Home with 30 straight punches**
7. **Regression tests + physical-device acceptance**

Do not spend time polishing final artwork until these structural issues are correct.
