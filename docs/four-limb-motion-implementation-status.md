# Four-limb motion implementation status

**2026-10-04 — App integration implemented; device/production acceptance pending.**

The supplied [requirement](backlog/four-limb-motion-characterizer-requirement-v1.md) and user handover set the sequence: generic limb characterization, activity-aware starting-side resolution, then selected-weapon/impact integration. The user subsequently asked to pause further validation and proceed with starting-side implementation. No acceptance or production-calibration claim follows from that pause.

## Implemented surface

The shared Kotlin core now contains:

- `motion/FourLimbMotionCharacterizer.kt` and `FourLimbMotionModels.kt`: one elbow/knee pipeline, canonical aspect-correct `LandmarkRelations.jointAngle`, observed-only confidence gating, explicit contiguous evidence blocks, reference windows, angular metrics, timing, independent abstention, activity shares and exact debug samples.
- `motion/AngularMotionEvidence.kt`: shared offline excursion hysteresis, reused by the characterizer. QoM segmentation is unchanged. Impact v2 can consume the characterizer’s retained joint angles and meaningful deltas.
- `core/ActivityStartingSideResolver.kt`: explicit plan-provided straight-punch or chamber-extension-kick interpretation. It consumes profiles, never rederives angles, preserves ambiguity, and retains the complete source result/configuration. Both relevant limbs must be measurable without gaps; active motion requires confirmed onset. Expected alternation never substitutes for observed evidence.

The product supplies an `ActivitySideContext` projection containing plan identity/version, known activity, alternation and optional target identity. Existing `RecordingProcessingPlan` and `GuidedStrikePlan` remain product-owned. Their current contracts are insufficient to infer every new field safely, so no automatic adapter or string-based exercise guessing has been added. A sequence is scoped to one recording/track, with explicit zero-based repetition indices. Missing indices retain parity; a missing or ambiguous first repetition does not produce a guessed starting side.

App routing now supplies explicit activity plans, saves generic limb/side/impact evidence, and exposes it in movement inspection. Calibration/revocation use existing append-only session events; no Room migration or new segmentation/playback boundaries were introduced. See integration notes below.

## Provisional measurement policy

- The experimental 3.6-degree number is used as an **excursion amplitude**, not a per-frame cutoff. Small same-direction increments accumulate until confirmed; reversals require a full excursion on both sides. This avoids rejecting a slow movement merely because it has more samples. This is an implementation interpretation requiring real-recording calibration, not a validated replacement threshold.
- The reusable causal median/mean filter is available, with identity windows by default to avoid adding frame-count-dependent timing lag. Optional non-identity settings have not been validated here.
- Angles are bounded 2D interior angles, not circular headings or reconstructed anatomical 3D angles. Signed changes therefore use ordinary subtraction.
- Default onset support is 60 ms of observed moving intervals; final settling needs a contiguous quiet suffix of at least 100 ms after observed movement. A gap is never quiet evidence. Absent confirmation yields null timing and a quality flag.
- Supplied pre/post windows are independently checked for observed coverage, continuity, duration and raw angular spread. Valid references use median raw angles. Without them, first/last valid logical samples are explicitly flagged boundary references. The initial settling search is limited to logical bounds; extending it into validated post-evidence remains outstanding.
- Travel, extrema, ranking and event traces concern the logical segment. Reference-window samples are retained separately in the same trace, with null motion deltas outside logical bounds. No interpolation is performed.
- Travel shares are relative to measurable limbs. A primary limb is only emitted with all four limbs measurable and a separated leading travel; multiple active candidates remain available. Stable zero-travel profiles have no invented peak timestamp or shares.
- Side thresholds (15-degree minimum excursion/travel, 75% directional share, and 2:1 kick/support travel ratio) are explicit provisional configuration. Focused resolver tests cover punch/hikite, ambiguity, kick reversal, missing first repetition, omitted repetition parity and mismatch preservation.

## App integration

New capture plans explicitly identify alternating straight punch or front kick. Unknown/legacy recordings do not gain an invented plan from their display name. Processing keeps the authoritative QoM boundaries and pipeline, then saves generic limb evidence, resolves side, selects the hand/foot, and calls the shared ImpactAnalyzer. New target-height results require completed impact evidence and use its representative sample and resolved side. Historical analyses are retained. New analysis plan version is 2; legacy direct adapter calls remain compatible.

The normal movement screen exposes limb travel/net change, onset/peak/settling, coverage/quality, side/alternation and impact/abstention reasons. Angle plots show the saved samples; disconnected blocks are separate plots. Terminal transition, stable start/end and measurement sample are separate named events. Raw samples, QoM, versions, identity and calibration are retained in the analysis payload.

Recordings offer **Calibrate image height & reanalyze**. On Android 9+, select an actual video frame, tap head top then floor, and confirm upright visibility plus unchanged camera/zoom/distance across the recording. FIT-display taps use the shared inverse transform. The fixed scale is vertical head-to-floor distance in canonical frame-height units. No physical-height re-entry or face-span inference occurs. **Invalidate image height calibration** revokes the current reference and reruns analysis; prior results remain stored. A changed landmark track requires new calibration.

## Validation checkpoint

Focused core checks passed: **36 tests** (18 characterizer/side, 1 sparse real-MLS, 3 calibration, 14 impact including shared-articulation integration). Focused app checks passed: **19 tests** (3 motion/calibration adapter, 2 target adapter, 7 processing/persistence/reanalysis, 7 shared capture UI). `:app:assembleDebug` succeeded. `git diff --check` passed. No full regression suite or device test was run. Thresholds remain provisional and device/manual acceptance has not been performed.

APK: `android/KarateClipRecorder/app/build/outputs/apk/debug/app-debug.apk` (113,012,923 bytes). SHA-256: `5568d370f57cfb1dcb993422c77c48e3c1363e3ac4c19de0badee314c075674c`. Built with Android Studio JBR 21. No installation or commit performed.

The initial sparse-MLS test failure is diagnosed: the committed 50-frame file is a subset for geometry tests with multi-second gaps. Production segmentation spanned 8067–10917 ms, with only three samples and 16 ms of connected evidence. Right-arm sample coverage alone was misleading; temporal coverage correctly rejected it. The test now asserts abstention without weakening gates and exports diagnostics before assertions.

The original 701-frame recording/export were traced and imported. A separate desktop MediaPipe Heavy regeneration completed, preserving 701 timestamps and source geometry. One original full-stream Kotlin replay completed. These are not Android equivalence or production-threshold validation. Comparing the old/new full streams remains explicitly deferred.

## Manual verification

1. Record a new known activity through Record & Analyze: alternating straight punch or front kick. Include an upright, fully visible moment at the performance position, keeping camera, zoom and distance unchanged.
2. Open a movement before calibration. Check four-limb evidence and explicit unavailable body-scale/side reason, with no fabricated target result.
3. In recording details choose **Calibrate image height & reanalyze**. Select the upright frame, mark head top and floor, confirm conditions and save.
4. Open the newly analyzed movement. Inspect limb plots/timings, observed side and terminal/stable/sample events. Results may still abstain for inadequate tracking, ambiguous side or unconfirmed terminal stability.
5. Reopen the app and confirm evidence persists. Invalidate calibration and verify the subsequent analysis abstains from scale-dependent impact. Previous runs must remain stored.
6. Check portrait/landscape and rotated recordings on device: preview must match the canonical frame; mismatches must reject calibration. No device result is claimed yet.

## Remaining limits

- Real-recording threshold calibration and on-device visual acceptance remain required. The handover’s overly permissive stable formula and mixed-coordinate second experiment are not validation targets.
- Manual calibration currently covers the whole recording and relies on explicit unchanged-condition confirmation. No automatic camera/depth-change detector exists.
- Existing recordings without explicit plan events expose generic evidence but cannot resolve activity-specific side. Use a new capture with a selected known activity for full pipeline verification.
- View handling is explicitly provisional; body-scale calibration does not validate camera-view suitability.
- Stable settling/reference search remains within the logical evidence supplied. Evidence-based pre/post clip boundaries and coordination scoring are deferred; descriptive timestamps are available.
