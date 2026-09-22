# Development Backlog

This folder stores small, concrete development requirements that came out of short design/debug chats and are **not yet implemented or verified**.

Future work sessions should use this folder as the repository-owned list of outstanding development tasks.

## Task format

Each task is a Markdown file with:

- `Status: OPEN | IN PROGRESS | DONE`
- a short purpose/context section;
- the agreed requirement;
- verification/acceptance criteria;
# Development Backlog

This folder stores small, concrete development requirements that came out of short design/debug chats and are **not yet implemented or verified**.

Future work sessions should use this folder as the repository-owned list of outstanding development tasks.

## Task format

Each task is a Markdown file with:

- `Status: OPEN | IN PROGRESS | DONE`
- a short purpose/context section;
- the agreed requirement;
- verification/acceptance criteria;
- optional image or other visual references.

If a chat includes screenshots or mockups that should be retained, store them under `docs/backlog/assets/<task-slug>/` and reference them from the task file.

When starting work on a task, first verify the current implementation. Do not assume the task is still missing simply because it is in the backlog. If already satisfied, document the verification and mark it `DONE`.

## Open tasks

- [Landmark Geometry Core — specification v2](landmark-geometry-core-v2-spec.md) — IN PROGRESS; overall shared geometry architecture, canonical evidence/persistence first, BodyHeightModel compatibility, explicit reference/window contracts, existing wrist-path/speed method preservation, and staged straight-punch consumer integration. Supersedes the [v1 draft](landmark-geometry-core-v1-spec.md); Stages A, B, and C are completed and verified via automated test suites in `:karate-analyzer-core` and `:app`; Stages D (straight-punch consumer integration) and E (production routing and migration) remain OPEN / OUTSTANDING.

- [Overlay Coordinate Transformer v2.1](overlay-coordinate-transformer-v2.1.md) — IN PROGRESS; canonical source-to-canvas coordinate transformer, aspect-correct Euclidean math, deterministic pixel crop mapping, presentation-mode scaling (Fit/Crop/Alignment/ZoomPan), unclamped mathematical inverse, separate hit-testing (InsideImage, OutsideImage letterbox, OutsideViewport); addressing 6 review gaps, app call sites, and MLS validation.
- [Body Height Model v2.1](body-height-model-v2.1.md) — IN PROGRESS; shared observed body-height geometry utility over canonical pose streams with configurable temporal window radius (±N), outward continuity validation, shared-evidence bilateral shoulder+hip torso aggregation, independent HeadAnchor strategies, compatibility target estimators; addressing 6 review gaps, app call sites, and MLS validation.

- [Movement Detail + Target Height + Pose Heavy Reprocessing — Corrective Pass](movement-detail-target-height-heavy-reprocessing-corrective-pass.md) — IN PROGRESS; addressing 3 remaining acceptance blockers: remove impact-frame neutral fallback in adapter, transport anatomical target via stable body origin (hip midpoint) in target calculator, and enforce clipping viewport container in movement player.

- [Movement Detail Page v1](movement-detail-page-v1.md) — local review remediation: consistent analysis provenance, validated persisted target geometry, playback ownership/rate fixes, explicit unavailable states and regression coverage; manual device acceptance pending. Analyzer plots, recording speed/deviation metrics and structured technique findings remain deferred.
- [Segmenter Integration v1](segmenter-integration-v1-requirements.md) — connect persisted landmark processing to retrospective segmentation, durable movement records, and recording-level segment inspection; implemented in local source; automated build and physical-device boundary validation remain pending. See the [validation plan](segmenter-integration-v1-validation.md) and [starter prompt](../prompts/segmenter-integration-v1-start-prompt.md).

- [Shared Camera Capture backend](shared-camera-capture-backend.md) — implemented locally for shared video/photo capture; physical-device acceptance pending.

- [Record & Analyze assisted capture](record-and-analyze-assisted-capture.md) — all three bundled slices implemented locally (capture, durable queue, Performance browser); physical acceptance pending.


- [Karate training database foundation](karate-training-database.md) — Room foundation and pipeline integration in local source; physical-device acceptance remains pending.

- [Osu meaning illustration assets](osu-meaning-illustrations.md) — replace the completed lesson’s semantic icon placeholders with approved recurring-character artwork.
- [Body conditioning / knuckle push-up learning path](body-conditioning-knuckle-pushup-learning-path.md) — later progression that separates knuckle conditioning from the basic strength learning path.
- [Home vs Learning ownership / onboarding v0.2](home-learning-ownership-onboarding-v0.2.md) — separates Home current-attention/Sensei from persistent Learning progression and establishes the first-run discovery flow.

## Completed tasks

- [Global Progressive Navigation Ownership Fix](global-progressive-navigation-ownership-fix.md) — completed 2026-09-19; single `AppNavigationStateRepository` global owner for progressive top-level navigation state; per-page `AppBottomNavigationView` removal from all 6 screens (Home, Learning, Train, Progress, Settings, SkillCoach); single shared bottom bar hosted in `MainActivity` app shell; profile-switch safe fallback to HOME; unlock acknowledgement persisted without replay; immersive chrome visibility contract; 7 repository-level unit tests and 3 updated architecture tests.
- [Analytical Vertical Slice v1: Reanalysis & Straight-Punch Target Height](analytical-vertical-slice-v1-reanalysis-target-height.md) — completed 2026-09-19; end-to-end analytical slice with durable `ProcessingRun` model (Room migration 6->7), MLS reuse bypassing MediaPipe decode, shoulder-centered neutral body frame target-ray geometry evaluated against Jōdan, Chūdan, and Gedan, canonical analysis frame selection, closest-target classification and margin, Recording Results UI with target breakdown and developer inspection, and "Reanalyze with current pipeline" confirmation action.
- [Dojo Sensei Home v2.1 Corrective Pass](dojo-sensei-home-v2.1-fix-requirements.md) — completed 2026-09-19; authoritative active profile startup resolution, direct profile creation route from unconfigured Home, root fix for SVG Path 7 leading command spike, balanced hero composition (~55–65% height), and 3 behavioral states (No Profile, Learning Incomplete, Training Ready with 30 straight punches).
- [Dojo Sensei Home v2](dojo-sensei-home-v2-requirements.md) — completed 2026-09-19; Sensei-led Home experience, isolated Sensei vector asset, dynamic speech bubble view with directional tail, onboarding action card, deterministic onboarding state controller (States A through E) with durable AppPreferences persistence, and full test suite.
- [App Chrome Update v1](app-chrome-update-requirements.md) — completed 2026-09-19; state-driven progressive bottom navigation supporting 1 to 5 destinations with canonical ordering, reflow transitions, discovery pulses, unified stable-geometry header, and 3 explicit profile avatar states.
- [Permission status UI verification and fix](permission-status-ui.md) — completed 2026-09-13; Android-backed status, consistent check/X visuals, settings fallback, and lifecycle refresh are implemented and verified.
