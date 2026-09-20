# Requirements — Dojo Sensei Home v2

Status: DONE (Completed 2026-09-19; verified via automated unit and architecture test suite)

## Purpose

Implement the **second part of the new Home experience** on top of the completed **App Chrome Update v1**.

This task builds the **Dojo Sensei Home / first-run Home v0.1**.

The shared chrome already exists and must be reused. This task must **not** re-implement the header, profile button, bottom navigation, edge-to-edge handling, or progressive navigation system.

For this version, **do not add the final dojo background artwork yet**.

Use the normal app background and build the complete foreground Home composition so the final dojo background can be added later without changing the structure.

---

# 1. Product intent

Home is the Sensei-led entry point to the app.

For a new user, Home should feel simple and guided:

```text
App chrome
↓
Sensei + speech bubble
↓
One current onboarding action
↓
Bottom navigation
```

The user should not see a generic dashboard.

The user should not see permanent Learning progress cards on Home.

Persistent curriculum / Continue Learning UI belongs to the **Learning page**.

Home owns:

- Sensei
- Sensei speech bubble
- one current Coach / onboarding action
- temporary attention guidance
- progressive introduction of app navigation

---

# 2. Existing architecture to reuse

This task assumes App Chrome Update v1 is already present.

Reuse the existing:

- `AppHeaderView`
- `AppHeaderState`
- `ProfileAvatarButton`
- unknown-profile state
- `AppBottomNavigationView`
- `AppNavigationState`
- `AppDestination.HOME`
- `AppDestination.LEARNING`
- attention/discovery animation support
- safe edge-to-edge inset handling
- existing profile flow
- existing back-stack behavior
- centralized strings/resources

Do not duplicate these systems inside the Home screen.

---

# 3. Scope

Implement:

1. New Sensei-led Home content.
2. Sensei artwork container.
3. Sensei speech bubble.
4. Primary onboarding action card.
5. First-run onboarding presentation states.
6. Wiring from Home actions into the existing chrome/profile/navigation states.
7. Responsive layout behavior.
8. Accessibility and tests.

Do **not** implement the final dojo background in this task.

---

# 4. Visual baseline

The page should use the current Karate Analyzer visual language:

- warm off-white / parchment-like app background
- charcoal primary text
- muted secondary text
- coral/red app accent
- rounded white or warm-white surfaces
- subtle borders/shadows
- simple Japanese-inspired decorative details
- no dark teal theme
- no photorealistic styling
- no extra dashboard widgets

Use existing app color resources where possible.

Do not hard-code a new parallel palette.

---

# 5. Sensei asset

A Sensei SVG exists and should be used as the initial foreground artwork if technically suitable.

The Sensei asset should be treated as **replaceable artwork**, not as a structural dependency.

Requirements:

- render independently from the Home background
- preserve transparent background
- preserve aspect ratio
- never stretch non-uniformly
- allow future replacement with a more detailed Sensei illustration without layout changes
- keep the black belt clearly visible
- keep the upper-body / welcoming-hand pose readable
- do not bake speech-bubble text into the artwork

If the project does not already support arbitrary SVG rendering at runtime:

- do not add a heavyweight SVG library only for this asset
- prefer converting the provided SVG to an Android-compatible `VectorDrawable` or equivalent build-time asset
- keep the original SVG as design/source artwork if appropriate
- do not silently rasterize to a low-resolution bitmap

Suggested conceptual resource identity:

```text
sensei_home_welcome
```

The exact filename may follow existing project conventions.

---

# 6. Home layout structure

The Home content should be implemented as a vertical responsive layout underneath the shared header and above the shared bottom navigation.

Conceptually:

```text
┌────────────────────────────────────┐
│ Shared App Header                  │
│ Karate Analyzer            Profile│
│ Welcome                            │
├────────────────────────────────────┤
│                                    │
│  Sensei            Speech bubble  │
│                                    │
│                                    │
│  Primary onboarding action card   │
│                                    │
├────────────────────────────────────┤
│ Shared progressive bottom nav      │
└────────────────────────────────────┘
```

The final dojo background will later sit **behind the Sensei/content hero region**.

The layout must therefore not depend on a background image for spacing or readability.

---

# 7. Sensei hero area

Create a dedicated hero area.

It contains:

- Sensei artwork
- speech bubble
- no baked-in background

The hero should feel like one visual composition, but Sensei and bubble must remain independent UI elements.

## 7.1 Sensei position

For compact portrait phones:

- Sensei should occupy roughly the lower-left / center-left portion of the hero.
- The body may extend below the hero crop if needed.
- Face, torso, black belt, and welcoming hand should remain visible.
- Avoid shrinking Sensei so much that the character becomes decorative rather than central.

## 7.2 Speech bubble position

The bubble should sit beside / above the Sensei's presenting hand.

It should visually read as the **Sensei's voice**.

The speech bubble is the only place where Sensei speaks directly.

Informational UI copy should remain outside the bubble.

---

# 8. Speech bubble component

Create a reusable speech-bubble view for Home.

Requirements:

- rounded warm-white surface
- optional subtle border/shadow
- small directional tail pointing toward Sensei
- supports short multi-line text
- supports emphasis of a short opening phrase if desired
- resizes with content
- no fixed bitmap text
- supports localization
- accessible as text
- does not become a general-purpose card for non-Sensei information

Initial copy:

```text
Osu! Welcome.
Let's start your karate journey.
```

Exact punctuation/localization can live in string resources.

The copy must not be embedded into artwork.

---

# 9. Primary action card

Below the hero, show one action card representing the user's current onboarding step.

Initial state:

```text
GET STARTED

Your first step

I'll guide you through the app step by step.

[ Start ]
```

The visual card may include a small Japanese-inspired decorative icon/mark, but it must remain app UI, not background artwork.

Requirements:

- rounded warm-white card
- title hierarchy clearly stronger than body copy
- large red primary button
- primary button at least 48dp tall
- one primary action only
- no secondary competing CTA in first-run State A

---

# 10. Do not add the old continual-learning card to Home

The previous / existing "Continue Learning" or continual-learning progress card must **not** be placed on Home.

That component belongs on the Learning page.

Home may later recommend Learning through the Sensei, but Home does not permanently duplicate:

- learning-path progress
- current lesson progress
- completed lessons
- upcoming lessons
- continual-learning card

Product ownership:

```text
Home = what Sensei wants attention on now
Learning = persistent learning journey
```

---

# 11. First-run onboarding states

The Home view should render from onboarding state.

The view itself must not become the owner of progression logic.

Use a controller/state holder/repository appropriate to the existing architecture.

## State A — Welcome

Chrome:

```text
Title: Karate Analyzer
Subtitle: Welcome
Profile: UnknownProfile
Bottom nav: HOME only
Selected: HOME
```

Home:

```text
Sensei bubble:
"Osu! Welcome.
Let's start your karate journey."

Action card:
"Your first step"
"I'll guide you through the app step by step."
Button: Start
```

### Start behavior

Tapping Start:

- advances onboarding state
- stays on Home
- must **not** navigate to Learning
- moves to State B

---

# 12. State B — Profile discovery

After Start:

- Home remains selected
- bottom navigation remains HOME only
- unknown profile avatar becomes the current attention target
- use the existing profile pulse/discovery affordance
- Sensei bubble changes to profile guidance

Suggested copy:

```text
Before we continue, I'd like to know a little about you.
Tap your profile above.
```

The main action card may change to an instructional state or be hidden if the speech bubble is sufficient.

Avoid presenting a second large CTA that duplicates the profile icon.

The intended action is:

```text
user taps the actual profile control in the chrome
```

This teaches the user where Profile lives.

---

# 13. Profile flow

Tapping the profile icon opens the existing profile flow.

Do not create a second embedded profile form on Home.

If the user cancels before minimum profile completion:

```text
return to State B
```

If minimum profile setup is completed:

```text
advance to State C
```

The exact definition of "minimum profile complete" should reuse existing profile concepts where possible and must not require unrelated future information such as:

- dojo schedule
- training preferences
- Sensei homework
- advanced body measurements

Those can be introduced later.

---

# 14. State C — Learning unlock

After minimum profile completion:

Chrome updates:

```text
Known profile avatar
Welcome <name>
Bottom nav: HOME + LEARNING
Selected: HOME
Attention: LEARNING
```

Use the existing Chrome v1 unlock behavior:

- Home reflows to its new position
- Learning enters
- Learning receives short discovery attention
- Home remains selected
- no automatic navigation

Home Sensei bubble changes to a Learning instruction.

Suggested copy:

```text
Good. Now let's begin learning.
Open Learning below.
```

The user must tap the actual Learning navigation item.

---

# 15. State D — Waiting for Learning discovery

If the user remains on Home:

- HOME + LEARNING remain visible
- HOME remains selected
- Sensei continues to direct attention to Learning
- no continual-learning card appears on Home
- Learning discovery highlight must not become an endless blinking animation

The attention state may remain semantically pending even after the initial visual pulse has stopped.

---

# 16. State E — Learning opened

When the user taps Learning:

- normal shared navigation selects LEARNING
- onboarding records that Learning has been introduced/opened
- Learning page becomes responsible for the next learning-path content
- persistent Continue Learning / curriculum UI belongs there

Returning Home later must not restore the first-run Start card.

Home can then evolve toward the later Sensei / Coach Action model.

---

# 17. Restart / process recreation

The first-run flow must survive:

- activity recreation
- process death
- app restart

Do not persist purely visual animation state as the authoritative product state.

Persist concepts such as:

```text
onboardingStep
profileComplete
learningUnlocked
learningUnlockAnnouncementSeen
learningIntroduced
```

Use existing project persistence patterns where appropriate.

Do not replay completed first-run steps unnecessarily.

---

# 18. Home presentation API

The Home view should be able to render a state object rather than mutate itself through many unrelated imperative calls.

A possible conceptual model:

```kotlin
data class SenseiHomeState(
    val message: SenseiMessage,
    val action: HomeAction?,
    val onboardingPhase: HomeOnboardingPhase,
)
```

Possible phases:

```text
WELCOME
PROFILE_DISCOVERY
LEARNING_DISCOVERY
NORMAL
```

Exact class names are not binding.

The important requirement is:

> Home renders state. It does not infer progression from child-view visibility.

---

# 19. Future-ready Coach Action boundary

Do not implement the full Coach Decision Engine.

However, avoid designing the Home action area so specifically around onboarding that it has to be replaced later.

Conceptually, the Home action surface should later be able to represent:

```text
Learning recommendation
Practice recommendation
Recording recommendation
Dojo reminder
Profile/setup request
Coach question
Unlock announcement
```

For v2, only onboarding actions need to work.

---

# 20. Background handling for this version

Do not add the final dojo background.

Use:

```text
app_background / existing warm neutral background
```

The hero/container should nevertheless be structured so a future background drawable can be placed behind it.

Do not:

- bake a temporary generated dojo bitmap into the layout
- hard-code spacing around one specific background composition
- place text into a background asset
- merge Sensei + background into one image

Future intended layering:

```text
Layer 1: dojo background
Layer 2: Sensei
Layer 3: speech bubble + app UI
```

This task implements Layers 2 and 3.

---

# 21. Responsive behavior

The page must work across different Android window sizes.

## Compact portrait

Primary target.

- Sensei + bubble can share a hero row/overlap composition.
- Action card sits below hero.
- Avoid content clipping on short devices.
- Page content may scroll if required, while shared bottom navigation remains correctly handled by the shell.

## Short-height / landscape

Do not simply scale the whole page down.

Allowed adaptations:

- reduce hero height
- reduce Sensei display size
- stack bubble differently
- allow content scrolling

Do not hide the primary action.

## Wider windows

Do not scale Sensei indefinitely.

Use a sensible max width/height and preserve composition.

No tablet-specific redesign is required in this task, but the layout must not assume one exact phone pixel size.

---

# 22. Sensei artwork quality

The currently supplied SVG may be visually simpler than the final desired Sensei.

This should **not block implementation**.

Treat it as a v2 asset / placeholder that proves:

- scale
- placement
- transparency
- bubble alignment
- responsive behavior

The final artwork should be replaceable through a resource swap without changing layout or logic.

Do not write layout code tied to specific facial or limb pixel coordinates.

---

# 23. Accessibility

Requirements:

- Sensei decorative artwork should not create noisy TalkBack output unless it conveys necessary information.
- Speech bubble text must be accessible.
- Primary action button must have a meaningful text label.
- Touch targets must remain at least 48 × 48 dp.
- Profile guidance must rely on text + semantic target, not pulse animation alone.
- Learning guidance must rely on text + semantic target, not pulse/color alone.
- Respect disabled system animations.
- Maintain suitable text/background contrast.

---

# 24. Strings

All user-facing copy must use resources.

Suggested additions:

```text
home_sensei_welcome_title
home_sensei_welcome_body

home_first_step_label
home_first_step_title
home_first_step_body
home_first_step_start

home_profile_prompt
home_learning_prompt
```

Do not hard-code English strings inside the custom view.

---

# 25. Likely implementation areas

Exact repo structure must be verified, but likely work includes:

```text
HomeScreenView.kt
new Sensei home child views/components
home onboarding state/controller
strings.xml
drawable/vector resources
Home-related unit / architecture tests
MainActivity wiring only where required
```

Do not make broad changes to:

```text
AppBottomNavigationView.kt
PageHeaders.kt
ProfileAvatarButton.kt
```

unless an actual bug or missing contract from Chrome v1 is discovered.

If Chrome v1 needs changes, keep them minimal and explain why.

---

# 26. Tests

Add focused tests for the Home v2 behavior.

## State A

Verify:

- unknown profile visible
- HOME is the only visible destination
- HOME selected
- welcome Sensei message visible
- Start action visible

## Start

Verify:

- tapping Start does not navigate
- onboarding advances to profile discovery
- profile becomes attention target

## State B

Verify:

- HOME remains the only destination
- Sensei asks user to use Profile
- profile control opens existing profile flow

## Profile incomplete

Verify:

- return remains in Profile discovery state

## Profile complete

Verify:

- known profile state is shown
- welcome can use user name
- LEARNING becomes visible
- HOME remains selected
- no automatic navigation to LEARNING

## Learning discovery

Verify:

- Sensei instructs user to open Learning
- no persistent learning-progress card exists on Home
- user must click Learning

## Learning opened

Verify:

- LEARNING becomes selected
- learningIntroduced/progression state is recorded
- reopening Home does not show the initial Start state

## Restart

Verify representative restart states:

```text
WELCOME
PROFILE_DISCOVERY
LEARNING_DISCOVERY
POST_INTRODUCTION
```

and ensure completed discovery animation does not replay incorrectly.

---

# 27. Explicit exclusions

Do not implement in this task:

- final dojo background
- final high-detail Sensei redesign
- full Learning page redesign
- continual-learning card on Home
- Coach Decision Engine
- 7-day coach memory
- dojo schedule
- training recommendations
- preferences
- Sensei-assigned homework
- widgets
- notifications
- movement analysis
- camera changes
- Compose migration

---

# 28. Definition of Done

The task is complete when a first-time user can experience:

```text
1. Open app

   Karate Analyzer
   Welcome

   Unknown profile
   Sensei welcome
   "Your first step"
   Start

   Bottom nav:
   [ Home ]


2. Tap Start

   Stay on Home.
   Sensei asks for Profile.
   Unknown profile control receives attention.


3. Open and complete minimum Profile

   Return to Home.
   Header now knows user.
   Learning unlocks.

   Bottom nav:
   [ Home ] [ Learning ]

   Home remains selected.
   Sensei says to open Learning.


4. Tap Learning

   User navigates through the real bottom navigation.
   Learning becomes selected.
   Learning owns the persistent learning journey.
```

The same Home structure must be ready for the later dojo background and a higher-quality Sensei asset without architectural rewrite.

---

# 29. Codex implementation instruction

Implement **Dojo Sensei Home v2** as a focused UI/onboarding task on top of the existing state-driven Chrome v1.

Prioritize:

1. correct ownership boundaries,
2. deterministic onboarding state,
3. reuse of shared chrome,
4. responsive Sensei + speech-bubble composition,
5. manual navigation discovery,
6. replaceable artwork,
7. tests.

Do not broaden the task into the future coaching system or final artwork pipeline.
