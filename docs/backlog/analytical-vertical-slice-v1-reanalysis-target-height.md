# Requirements — Analytical Vertical Slice v1
## Reanalysis + Current Segmenter + Three-Height Straight-Punch Target Analysis

**Status:** DONE (Implemented and verified locally 2026-09-19)  
**Primary platform:** Android / Kotlin Views + existing training pipeline  
**Repository:** `karate-kihon-analyzer`

---

## 1. Purpose

Build the first useful end-to-end analytical slice of Karate Analyzer around an existing straight-punch recording.

The user must be able to:

**Record once → segment → analyze → inspect measurements → improve code → reanalyze the same recording with the current pipeline → inspect the new result.**

This slice intentionally prioritizes the analytical development loop over additional Home/Learning UI work.

The first real technique analysis in this slice is **straight-punch target height**.

Jōdan, Chūdan, and Gedan are **not separate straight-punch techniques or separate analyzers**. A straight punch is analyzed once against all three target heights, and the analyzer reports which target direction the observed punch is closest to.

---

# 2. User outcome

For an existing recording with a valid persisted landmark stream, the user can open:

**Recording Results → Recording details → Reanalyze with current pipeline**

and the app will:

1. reuse the existing persisted MLS / landmark track,
2. run the **current** retrospective segmenter again,
3. create a fresh segmentation result,
4. run the **current straight-punch target analyzer** against every newly detected movement,
5. calculate target geometry for Jōdan, Chūdan, and Gedan,
6. determine the closest observed target for each movement,
7. persist the new measurements with provenance,
8. make the new successful run the default result shown by Recording Results,
9. retain the previous successful result if the new run fails.

No new video recording is required.

---

# 3. Current repository state confirmed before this task

The current repository already contains most of the required building blocks.

## Existing processing pipeline

`TrainingSessionProcessor.kt` already performs:

**recording → landmarks → retrospective segmentation → optional analyzers**

and already reuses an existing compatible landmark track where possible.

## Existing straight-punch plan

`RecordingProcessingPlans.STRAIGHT_PUNCH_SEGMENTS` currently has:

- landmarks required,
- segmentation required,
- `analyzers = emptyList()`.

Therefore normal assisted straight-punch recordings currently stop after segmentation.

## Existing Android height analyzer

`PunchHeightAnalyzer.kt` already contains:

- `JodanTargetModel`
- `ChudanTargetModel`
- `GedanTargetModel`

and understands:

- `PunchHeightTargetType.JODAN`
- `PunchHeightTargetType.CHUDAN`
- `PunchHeightTargetType.GEDAN`.

## Existing adapter

`AndroidPunchMovementAnalyzer` currently persists:

- `PUNCH_HEIGHT_ERROR_TORSO_RATIO`
- `PUNCH_ELBOW_ANGLE`

but the adapter currently evaluates only Jōdan and is effectively coupled to the legacy `guided_jodan_session` context.

## Existing persistence behavior that blocks reanalysis

`TrainingRepository.saveSegmentation(...)` currently enforces:

> segmentation is saved once and existing movement identities are reused.

`TrainingSessionProcessor.process()` also skips segmentation when movements already exist.

That is correct for **Retry**, but it means a recording cannot currently be deliberately resegmented using a newer segmenter.

Similarly, the analyzer is skipped when `preferredAnalysis(...)` already finds an accepted analysis for the existing movement.

This coding exercise must introduce a separate **Reanalysis** behavior rather than changing Retry semantics.

---

# 4. Core distinction: Retry vs Reanalysis

These are different operations and must remain different.

## Retry

Purpose:

> Continue/recover the same intended processing attempt after failure or interruption.

Retry should preserve the existing identity/idempotency rules where appropriate.

It must not silently reinterpret an already-successful recording with a newer segmenter.

## Reanalyze with current pipeline

Purpose:

> Deliberately reinterpret existing durable evidence using the software's current segmentation and analysis versions.

Reanalysis must:

- reuse the existing valid MLS,
- rerun segmentation even when old movements already exist,
- create new movement identities for the new segmentation,
- run current analyzers regardless of older analyses,
- preserve provenance,
- publish the new run only after successful completion.

## Future — Regenerate landmarks and reanalyze

Not part of this task.

A later debug action may rerun MediaPipe / landmark extraction before segmentation.

This v1 must **not** regenerate landmarks.

---

# 5. Reanalysis UI

Add a debug/developer-only action inside the existing **Recording details** disclosure on the Recording Results page.

Label:

**Reanalyze with current pipeline**

Do not call this action `Retry`.

## Availability

Show/enable the action only when:

- debug/developer functionality is enabled according to the app's existing debug policy,
- the recording exists,
- a valid completed MLS / landmark track is available,
- the recording is not currently being processed by a conflicting job.

If the landmark evidence is missing/corrupt, the action should not pretend that reanalysis can proceed.

## Confirmation

Before starting, show concise explanatory copy such as:

> Reuse the existing landmark stream and run the current segmentation and analysis again. The original video and previous successful result will not be changed unless the new run completes successfully.

Exact copy may be localized/refined.

---

# 6. Reanalysis processing flow

Required flow:

**Existing Master MP4**

→ **Existing completed MLS**

→ **Current retrospective segmenter**

→ **New segmentation run**

→ **New SessionMovement identities**

→ **Current straight-punch target analyzer**

→ **Persisted MovementAnalysis + MeasurementResult**

→ **Publish new run as current result**

The master video and MLS remain unchanged.

---

# 7. Reanalysis must actually run the newest segmenter

Do not reuse old `SessionMovement` rows for an explicit reanalysis.

The current logic:

```text
if existingMovements.isEmpty()
    run segmentation
else
    reuse movements
```

must remain valid for ordinary Retry/idempotent processing, but explicit reanalysis must bypass that reuse behavior.

A reanalysis of a video segmented with:

```text
segmenter v1
```

after installing:

```text
segmenter v2
```

must produce movements owned by `segmenter v2`.

The new result may contain:

- a different movement count,
- different logical boundaries,
- different retained playback boundaries,
- different cue associations,
- different analysis results.

That is expected.

---

# 8. Processing-run identity and history

Do not destructively overwrite the previous successful segmentation merely to make reanalysis possible.

Introduce a durable processing/reanalysis-run identity or equivalent data model.

A clean conceptual model is:

```text
RecordingSession
  ├── ProcessingRun A
  │     ├── Segments A1...
  │     └── Analyses...
  │
  └── ProcessingRun B
        ├── Segments B1...
        └── Analyses...
```

Exact class/table names are implementation choices.

At minimum each run must retain:

- run identity,
- session/recording identity,
- creation timestamp,
- mode: initial / reanalysis,
- source landmark track ID,
- processing plan key/version,
- segmenter version,
- analyzer key/version(s),
- terminal state,
- failure reason if any.

`SessionMovement` must be attributable to the segmentation/processing run that created it.

Analyses remain attributable to their movement.

---

# 9. Current/default result selection

Recording Results should normally display the **newest successfully published processing run**.

Publication must be atomic from the user's perspective.

## Successful reanalysis

After all required processing reaches a usable terminal state:

```text
old successful run = retained
new successful run = current/default
```

Refresh the open Recording Results page to the new data.

## Failed reanalysis

If the new reanalysis fails:

```text
old successful run remains current/default
failed new run remains available as provenance/debug information
```

Do not leave a previously usable recording with no usable result.

A failure banner/message may state that the latest reanalysis failed while the previous successful result remains displayed.

---

# 10. Queue behavior

Reuse the existing recording-processing queue/work infrastructure.

Do not create a second standalone reanalysis execution system.

For reanalysis from an existing MLS, the effective phases are:

**Queued → Segmentation → Analysis → Ready**

Landmark extraction is skipped.

The run must still record the exact `sourceLandmarkTrackId`.

Do not fabricate landmark-processing time when landmarks were reused.

---

# 11. Straight punch is the technique

The analysis model for this slice is:

```text
Technique: STRAIGHT_PUNCH
Observed target: JODAN | CHUDAN | GEDAN
```

Do not create separate technique analyzers such as:

```text
JODAN_PUNCH_ANALYZER
CHUDAN_PUNCH_ANALYZER
GEDAN_PUNCH_ANALYZER
```

The same analyzer evaluates all target heights.

Likewise, do not require three separate recording categories simply to analyze target height.

---

# 12. Analysis-frame ownership

The target analyzer must consume the recording pipeline's canonical terminal/theoretical-impact analysis frame.

Do not create a second competing impact-frame selector inside the target-height analyzer.

Use the repository's established terminology/contract where available.

The intended ownership is:

```text
movement / theoretical-impact logic
    -> canonical analysis frame
target analyzer
    -> consumes that frame
```

Persist the measurement timestamp/frame provenance.

If the current Android slice does not yet expose a canonical analysis frame for segmented movements, add the smallest explicit boundary needed and persist it.

Do not silently fall back to an unrelated arbitrary frame without provenance.

---

# 13. Shoulder-centered target geometry

The new target classification should be based on **terminal arm direction**, not merely the vertical distance from the fist to a target line.

At the canonical terminal/theoretical-impact frame:

- identify the active/striking shoulder,
- identify the actual terminal fist/wrist point,
- obtain a usable full arm-reach radius,
- construct the Jōdan target line,
- construct the Chūdan target line,
- construct the Gedan target line,
- calculate an ideal fully extended endpoint for each target,
- compare actual terminal arm direction to each ideal target direction.

---

# 14. Coordinate frame

Do not perform this geometry directly in distorted normalized MediaPipe image coordinates.

Use the same explicit body/analysis coordinate-frame principles already documented by the target-height foundation.

The target lines should be body-relative lines perpendicular to the locked neutral body vertical axis, not blindly:

```text
screen y = constant
```

Display/crop transforms must not feed back into analysis.

All points participating in one geometric calculation must be in a compatible coordinate frame.

---

# 15. Shoulder center and actual ray

For a movement's striking arm at the analysis frame:

```text
S = shoulder position
F = actual fist/wrist position
```

Define the actual terminal arm direction:

```text
actualRay = F - S
```

The analyzer should retain which body side/arm was analyzed.

If the striking arm cannot be determined with sufficient evidence, abstain rather than choosing an arbitrary side.

---

# 16. Arm-reach radius

The ideal-target construction needs a full arm reach:

```text
R
```

Do **not** define `R` as the instantaneous shoulder-to-wrist straight-line distance at terminal impact, because elbow flexion would shrink the reference radius and hide part of the extension error.

Prefer a stable anatomical/reference estimate derived from usable evidence, conceptually:

```text
R ≈ shoulder→elbow segment length
    + elbow→wrist/fist segment length
```

using an appropriate stable reference/calibration strategy.

The exact implementation may reuse existing `PunchHeightAnalyzer` / `BodyReference` infrastructure if it already provides equivalent geometry.

Requirements:

- provenance for how `R` was obtained,
- no arbitrary fixed human arm length,
- reject/abstain on degenerate or unavailable geometry,
- keep target-direction error separate from extension/reach error.

A future personal body calibration may replace/refine this estimate without changing the analyzer contract.

---

# 17. Ideal target endpoint

For each target `T`:

```text
JODAN
CHUDAN
GEDAN
```

obtain that target's body-relative target-height line from the existing target model/foundation.

Conceptually, with the shoulder as circle center:

```text
circle center = S
circle radius = R
target line = target-specific body-relative height
```

Find the physically forward intersection of the target line and reach circle.

That intersection is the ideal fully-extended endpoint for that target:

```text
I_T
```

Then:

```text
idealRay_T = I_T - S
```

If the target line does not intersect the reach circle, that target has invalid/unreachable geometry for that calculation and must not produce a fabricated angle.

---

# 18. Target-angle error

For every valid target:

```text
angleActual = direction(actualRay)
angleTarget = direction(idealRay_T)

error_T = signedAngle(angleActual, angleTarget)
```

Persist target-angle error in degrees.

Define and test one consistent sign convention in the body-relative frame.

Recommended semantic convention:

```text
positive = actual punch direction is above the ideal target ray
negative = actual punch direction is below the ideal target ray
```

The implementation may use the opposite mathematical sign internally only if the persisted/public meaning is normalized consistently.

---

# 19. Analyze all three targets every time

For every analyzable straight-punch movement, attempt:

```text
Jōdan target-angle error
Chūdan target-angle error
Gedan target-angle error
```

Do not stop after the first plausible target.

Do not choose target type from the activity key before analysis.

All three use the same:

- movement,
- analysis frame,
- shoulder,
- arm reach,
- body reference.

This should remain computationally lightweight relative to MediaPipe inference and segmentation.

---

# 20. Closest observed target

Among the valid target-angle results:

```text
observedTarget = target with minimum abs(error_T)
```

Example:

```text
Jōdan   +15.3°
Chūdan   +2.1°
Gedan   -16.8°
```

Result:

```text
closest observed target = CHUDAN
target-angle error       = +2.1°
```

Use language such as **Closest target** / **Observed closest target**.

Do not claim that this proves what the user intended.

---

# 21. Classification margin

Persist enough information to calculate how decisive the target classification was.

Recommended:

```text
classificationMarginDeg =
    secondSmallestAbsoluteError
    - smallestAbsoluteError
```

Example:

```text
Jōdan  6.0°
Chūdan 6.4°
margin = 0.4°
```

This is useful future confidence evidence.

For v1, do **not** invent a precise validated ambiguity threshold unless one already exists in repository-owned configuration/research.

The UI may still display the mathematically closest target while internal state/provenance makes clear that this is an observed closest target, not a validated coaching verdict.

---

# 22. Keep target direction separate from reach/extension

Do not combine these into one opaque score.

The geometry intentionally allows:

```text
correct direction
+
incomplete extension
```

to be represented separately.

For this slice:

**Required**
- target-angle calculations,
- closest target classification.

**Existing and allowed**
- elbow angle.

**Optional if already trivial to expose**
- radial reach ratio/error.

Do not delay the slice to invent a full extension-quality model.

---

# 23. New measurement semantics

Do not silently change the meaning of the existing legacy:

```text
PUNCH_HEIGHT_ERROR_TORSO_RATIO
```

Introduce explicit measurement keys for the new analyzer version.

Recommended semantic set:

```text
PUNCH_CLOSEST_TARGET
    categorical: JODAN | CHUDAN | GEDAN

PUNCH_TARGET_ANGLE_ERROR_DEG
    signed angular error to the selected closest target

PUNCH_JODAN_TARGET_ANGLE_ERROR_DEG
PUNCH_CHUDAN_TARGET_ANGLE_ERROR_DEG
PUNCH_GEDAN_TARGET_ANGLE_ERROR_DEG
    signed angular errors for each valid target

PUNCH_TARGET_CLASSIFICATION_MARGIN_DEG
    numeric separation between best and second-best target
```

Retain:

```text
PUNCH_ELBOW_ANGLE
```

if the existing analyzer can provide it without compromising the new frame/geometry contract.

Exact key spelling may be refined, but meanings must remain stable and documented.

---

# 24. Analyzer identity/versioning

Create a clear current analyzer identity/version for this new behavior.

Conceptually:

```text
analyzerKey = straight_punch_target
analyzerVersion = 2
```

Exact version naming is implementation-owned.

The version must distinguish this three-target shoulder-ray geometry from the legacy Jōdan-only height result.

`AnalyzerPolicy` should explicitly prefer the new approved version when displaying current results.

Do not compare analyzer version strings lexically.

---

# 25. Analyzer must remain context-independent

The core analyzer answers:

> What did this straight punch most closely target?

It does **not** need to know whether the movement came from:

- free Record & Analyze,
- a future Learning lesson,
- guided practice,
- a Sensei recommendation.

The core result is the same.

Design the API so a future caller may optionally provide:

```text
requestedTarget = JODAN | CHUDAN | GEDAN | null
```

but do not make the calculation depend on that requested value.

---

# 26. Future guided-practice compatibility

Not implemented in this slice, but the contract must support:

```text
Cue/requested target = JODAN

Analyzer independently computes:
Observed closest target = CHUDAN
Jōdan error = ...
Chūdan error = ...
Gedan error = ...

Comparison layer:
requested != observed
```

This preserves the architecture:

```text
Technique analyzer:
    What happened?

Activity/Learning context:
    What was requested?

Comparison:
    Did they agree?
```

Do not bake lesson/cue semantics into the target geometry.

---

# 27. Processing-plan update

The normal straight-punch processing plan should now include the new target analyzer.

Today:

```text
STRAIGHT_PUNCH_SEGMENTS
    analyzers = emptyList()
```

After this slice, the straight-punch plan should reach:

```text
landmarks
→ segmentation
→ straight-punch target analysis
→ Ready
```

Update plan key/version if required by the repository's versioning contract.

Do not leave a plan named/versioned as "segments only" while silently changing its terminal semantics without appropriate version/provenance treatment.

---

# 28. Recording Results — minimum measurement UI

The first analytical slice must make the new data visible.

Do not stop at database persistence.

## Session Analysis card

Show a small useful summary based only on real persisted results.

Minimum suggested content:

```text
Target height

Closest targets
Jōdan:   N
Chūdan:  N
Gedan:   N

Average target-angle error: X.X°
```

`Average target-angle error` should use the absolute error to each movement's closest target unless a different clearly documented definition is chosen.

No overall session score.

No fabricated historical comparison.

If there are no valid measurements:

```text
Target analysis unavailable
```

with appropriate reason/debug detail where available.

## Movement rows

For each movement with valid target analysis, show something compact such as:

```text
Closest: Chūdan
2.4° high
```

or:

```text
Chūdan · +2.4°
```

Use readable user-facing wording for signed direction.

Do not show all three raw target errors in the normal compact movement row.

---

# 29. Debug measurement detail

Because this slice is specifically valuable for analyzer development, debug mode should make the underlying values inspectable.

For one movement, debug/detail information should expose at least:

- analysis frame timestamp/frame index,
- active arm,
- shoulder point provenance,
- arm-reach value/provenance,
- Jōdan ideal target angle/error,
- Chūdan ideal target angle/error,
- Gedan ideal target angle/error,
- closest target,
- classification margin,
- analyzer key/version,
- segmenter version,
- source landmark track ID.

This may initially be text/debug presentation rather than polished production UI.

---

# 30. Recording Details provenance

Expand the existing Recording Details debug information to make reanalysis understandable.

Show where available:

```text
Landmarks
<landmark pipeline/model version>
<landmark track identity>

Segmentation
<segmenter version>

Analysis
<analyzer key/version>

Processed
<timestamp>
```

When the displayed run reused landmarks, make that clear rather than implying MediaPipe was rerun.

---

# 31. Legacy compatibility

Existing historical recordings/results must remain readable.

Do not delete or reinterpret old `PUNCH_HEIGHT_ERROR_TORSO_RATIO` values as if they were generated by the new target-angle analyzer.

The Results UI must tolerate:

- old segmentation-only recordings,
- old legacy Jōdan analysis,
- new three-target analysis,
- failed/partial/abstained new analysis.

---

# 32. Abstention / invalid geometry

The analyzer must abstain or produce partial results rather than fabricate geometry when required evidence is unusable.

Examples:

- missing striking shoulder,
- missing wrist/fist,
- striking side cannot be determined,
- invalid body coordinate frame,
- invalid/degenerate arm reach,
- target-line construction unavailable,
- no valid circle intersection for a target,
- canonical analysis frame unavailable,
- insufficient landmark quality.

One invalid target does not necessarily invalidate the other valid target calculations.

If no target produces valid geometry:

```text
PUNCH_CLOSEST_TARGET = abstained
```

with a structured reason.

---

# 33. No coaching verdict yet

This first slice measures and classifies.

It does not establish validated coaching thresholds such as:

```text
perfect
good
bad
too inaccurate
```

It may say:

```text
Closest target: Chūdan
3.2° above target direction
```

It should not invent a karate-quality score or pass/fail threshold.

---

# 34. Reanalysis publication safety

A reanalysis must not mutate the current/default result incrementally as individual phases complete.

Preferred behavior:

```text
create new run
↓
segment
↓
analyze
↓
validate terminal state
↓
publish as current
```

If analysis fails partway through, the previous successful run remains the normal displayed result.

Partial/failed new runs remain useful for debugging/provenance but do not silently replace a better successful result.

---

# 35. Zero-movement result

A valid reanalysis may legitimately find zero movements.

This is a successful segmentation outcome, not automatically a crash/failure.

The run should reach an appropriate usable terminal state with:

```text
0 movements
0 target analyses
```

Recording Results should state that no movements were detected.

---

# 36. Physical development loop acceptance scenario

The developer must be able to perform this exact workflow:

1. Record a straight-punch session on the phone.
2. Wait for normal processing.
3. Open Recording Results.
4. See detected movements.
5. See real target-height measurements.
6. Change the segmenter and/or target analyzer implementation.
7. Install the new build.
8. Open the **same old recording**.
9. Open Recording details.
10. Tap **Reanalyze with current pipeline**.
11. Confirm no new MediaPipe landmark pass is performed.
12. Observe the current segmenter produce a fresh movement set.
13. Observe the current target analyzer run on those new movements.
14. View the newly published measurements.
15. Verify the original MP4 and MLS remained unchanged.

This workflow is the primary success criterion of the coding exercise.

---

# 37. Automated tests — reanalysis

Add focused tests proving:

### Existing movements do not suppress explicit reanalysis

Given:

```text
existing successful segmentation
+
valid MLS
```

When:

```text
Reanalyze with current pipeline
```

Then:

```text
current segmenter executes again
new movement identities are created
```

### MLS is reused

Verify the reanalysis path does not invoke `VideoPoseProcessor.processVideo()` when a valid source landmark track is available.

### Segmenter version provenance

New movements/run contain the current `SEGMENTATION_VERSION`.

### Analyzer reruns

Existing preferred analyses from the old run must not suppress analysis on the new movements.

### Publish-on-success

New successful run becomes current/default.

### Failure fallback

Failed reanalysis leaves the previous successful run current/default.

### Zero detections

Zero movement detections publish as a valid result.

---

# 38. Automated tests — target geometry

Create deterministic unit tests independent of Android UI.

Cover at minimum:

### Exact Jōdan ray

Actual terminal ray equals ideal Jōdan ray:

```text
Jōdan error ≈ 0°
Jōdan selected
```

### Exact Chūdan ray

```text
Chūdan error ≈ 0°
Chūdan selected
```

### Exact Gedan ray

```text
Gedan error ≈ 0°
Gedan selected
```

### Between two targets

Verify the target with the smaller absolute angular deviation is selected.

### Signed direction

Verify above/below sign convention.

### Bent/reduced terminal reach

Changing actual shoulder→fist radius while preserving direction should not by itself create a large target-angle error.

This confirms target direction is not being conflated with extension.

### Invalid circle intersection

A target line outside the usable reach circle produces an invalid/abstained target result, not NaN or fabricated geometry.

### Invalid arm

Missing/low-quality required landmarks produce structured abstention.

### All three evaluated

Ensure one selected target does not prevent the other target errors from being calculated/persisted where valid.

---

# 39. Automated tests — persistence/results

Verify:

- new measurement definitions are registered,
- categorical target values persist correctly,
- all three target-angle measurements attach to the correct `MovementAnalysis`,
- analysis run references the correct MLS,
- old analysis data remains intact,
- current-result queries select the newest successfully published run,
- session summary uses only the current run,
- movement rows use measurements belonging to their own movement/run,
- legacy recordings remain readable.

---

# 40. Manual verification

Test on a physical Android device with at least:

- one existing old straight-punch recording,
- one newly recorded straight-punch session,
- multiple punch heights if practical,
- at least one recording with imperfect segmentation.

For an existing recording:

1. note current movement count/bounds,
2. run current-pipeline reanalysis,
3. verify a new segmentation result is created,
4. inspect target classifications,
5. verify re-opening the app preserves the new current result.

Also verify that reanalysis is fast relative to a full landmark regeneration because MediaPipe extraction is skipped.

---

# 41. Explicit exclusions

Do **not** add in this task:

- new MediaPipe model selection,
- landmark regeneration action,
- final target coaching tolerances,
- overall scores,
- historical personal baseline comparisons,
- full movement-detail visualization,
- full path-deviation analysis,
- punch speed,
- hikite speed,
- kinetic-chain analysis,
- Learning-path integration,
- guided-practice UI,
- Coach Decision Engine,
- final Home/dojo artwork.

Those can build on this slice later.

---

# 42. Suggested implementation order

Implement in this order:

1. Add durable processing/reanalysis-run ownership.
2. Make current-result selection run-aware.
3. Add explicit reanalysis entry point that reuses MLS.
4. Force fresh segmentation for reanalysis.
5. Refactor/extend straight-punch target analyzer to evaluate all three targets.
6. Implement shoulder-centered target-ray geometry.
7. Register/persist new measurement keys and analyzer version.
8. Add analyzer to the straight-punch processing plan.
9. Populate Recording Results with real target measurements.
10. Add Recording Details reanalysis action + provenance.
11. Add regression/unit tests.
12. Validate on physical device with an old recording.

---

# 43. Definition of Done

The slice is complete when this statement is true:

> I can record straight punches once, see real movement target-height data, change the segmenter or target-analysis code, install a new build, reanalyze the same stored MLS using the current pipeline, and immediately inspect the new segmentation and target results without making another recording.

And for every analyzed movement, the app can answer:

```text
Which of Jōdan, Chūdan, and Gedan was the terminal punch direction closest to?

How many degrees above/below that ideal target direction was it?

What were the corresponding angular errors for the other two targets?

Which exact landmark track, segmenter version, analyzer version, and analysis frame produced this result?
```

No overall score is required.

That is the first complete analytical vertical slice.

---

# 44. Implementation & Verification Summary (2026-09-19)

### Implemented Capabilities:
1. **Target Geometry & Canonical Frame Selection (`karate-analyzer-core`)**:
   - `StraightPunchTargetCalculator.kt`: evaluates punches against Jōdan, Chūdan, and Gedan target rays centered at the active shoulder in the neutral body frame, computes signed error (positive = above target), classification margin, and elbow angle.
   - `CanonicalAnalysisFrameSelector.kt`: selects canonical analysis frame based on peak reach, minimum wrist speed, and highest strike confidence within movement bounds.
2. **Durable ProcessingRun & Schema (`KarateTrainingDatabase`)**:
   - Upgraded Room DB to version 7 with `MIGRATION_6_7`.
   - Added `ProcessingRun` entity/table tracking `runId`, `sessionId`, `mode` (`INITIAL`/`REANALYSIS`), `state`, `isCurrent`, timestamps, duration, and provenance.
   - Associated `SessionMovement` with `runId` and `analysisFrameUs`.
3. **Target Analyzer Adapter & Processor Pipeline (`training`)**:
   - `StraightPunchMovementAdapter.kt`: bridges segmentation and `StraightPunchTargetCalculator`, persisting 7 measurement results (`PUNCH_CLOSEST_TARGET`, `PUNCH_TARGET_ANGLE_ERROR_DEG`, `PUNCH_JODAN_TARGET_ANGLE_ERROR_DEG`, `PUNCH_CHUDAN_TARGET_ANGLE_ERROR_DEG`, `PUNCH_GEDAN_TARGET_ANGLE_ERROR_DEG`, `PUNCH_TARGET_CLASSIFICATION_MARGIN_DEG`, `PUNCH_ELBOW_ANGLE`).
   - `TrainingSessionProcessor.kt`: implemented `processReanalysis(sessionId, runId)` reusing existing MLS without MediaPipe decode, creating fresh movement records, and publishing atomically on success.
4. **Recording Results UI (`RecordingsActivity.kt`)**:
   - Session Analysis card displays closest targets breakdown (Jōdan, Chūdan, Gedan) and average error.
   - Movement rows display compact closest-target chip (`Chūdan · +2.4° high`) with developer-mode debug panel showing canonical frame timestamp/index, arm side, reach, all 3 target errors, and track IDs.
   - Details disclosure indicates reused MLS provenance, segmenter/analyzer versions, and provides "Reanalyze with current pipeline" confirmation action.

### Verification:
- `StraightPunchTargetCalculatorTest`: 9 unit tests passing.
- `CanonicalAnalysisFrameSelectorTest`: 4 unit tests passing.
- `TrainingProcessorTest`: synthetic continuous session, real fixture, and 3 reanalysis lifecycle tests passing.
- `TrainingSchemaTest`: migrations 1->7, 2->7, 4->7, and 6->7 passing.
- `RecordingsActivityTest`: UI card hierarchy, error handling, and playback dialogs passing.
- Full test suite: `:karate-analyzer-core:test` (100% pass), `:app:testDebugUnitTest` (61 tasks, 100% pass), `pytest` (345 passed).

