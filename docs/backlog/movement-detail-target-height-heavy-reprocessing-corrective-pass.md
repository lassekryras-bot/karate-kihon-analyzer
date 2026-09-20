# Movement Detail + Target Height + Pose Heavy Reprocessing — Corrective Pass

## Status

IN PROGRESS — Addressing 3 remaining acceptance blockers:
1. Removing impact-frame neutral fallback in `StraightPunchMovementAdapter` (`neutral_body_reference_unavailable`).
2. Transporting anatomical target via stable body-origin translation (`currentHipCenter - neutralHipCenter`) in `StraightPunchTargetCalculator`, decoupling target height from striking shoulder.
3. Enforcing an explicit clipping viewport container for vertical-only cropping in `MovementVideoViewport`, `MovementPresentationPlayerView`, and `MovementExpandedInspectionDialog`.

This task combines the agreed fixes for:

1. movement playback / replay behavior,
2. vertically tighter movement presentation,
3. target-height overlay simplification,
4. target-height analyzer geometry correctness,
5. offline Pose Landmarker Heavy adoption,
6. a new full landmark-reprocessing workflow,
7. provenance, publication safety, and regression coverage.

The changes belong together because the Movement Detail page is now the primary place to inspect the exact movement, canonical impact frame, landmark-derived evidence, and analyzer output.

---

# 1. Primary goal

Make Movement Detail behave like a movement-inspection tool rather than a generic video player, while correcting the underlying target-height geometry and adding an explicit way to regenerate landmarks with the current offline pose model.

The intended high-level pipeline remains:

Master MP4
→ landmark stream (MLS)
→ retrospective segmentation
→ movement analysis
→ Movement Detail / Recording Results

Two reprocessing levels must be available:

### Reanalyze
Existing MLS
→ fresh segmentation
→ fresh analysis

### Reprocess landmarks & reanalyze
Master MP4
→ NEW MLS using current offline pose model
→ fresh segmentation
→ fresh analysis

Do not collapse these into one ambiguous retry operation.

---

# 2. Architectural rules

## 2.1 Master recording remains immutable evidence

Do not physically crop, trim, rewrite, or replace the source MP4 for these presentation changes.

The original master recording remains authoritative evidence.

## 2.2 Landmark streams are durable evidence

A newly generated Heavy-model MLS must not overwrite or mutate an existing Full-model MLS.

A recording may legitimately own multiple landmark tracks produced by different model/configuration versions.

## 2.3 Reanalysis must remain cheap

The existing reanalysis path must continue to reuse a specific existing MLS and must not invoke MediaPipe video decoding.

This is the normal path when only segmentation/analyzer code has changed.

## 2.4 Full landmark reprocessing is explicit

The new full reprocessing operation must explicitly create a new landmark track from the MP4 even if a compatible completed track already exists.

It must not silently reuse an existing Heavy track.

## 2.5 Publish only complete successful results

A newly generated run becomes current only after its required phases complete successfully.

Previous successful current results remain available and current if a new run fails.

A newly generated MLS that completed successfully should remain preserved even if later segmentation or analysis fails.

---

# 3. Movement playback semantics

## Problem

Movement Detail opens on the canonical impact/analysis frame, but pressing Play currently continues through the post-impact retained buffer.

That post-impact tail is useful evidence, but it is not the useful default replay experience.

## Required behavior

### Initial state

When Movement Detail opens:

- seek to the real canonical impact/analysis frame,
- remain paused,
- show a **Replay** control rather than ordinary Play.

The canonical impact frame is the visual resting state of the movement.

### Replay

When Replay is pressed:

- seek to the normal replay start,
- begin playback,
- play forward toward impact,
- stop at the canonical impact frame,
- settle precisely on the canonical impact frame,
- remain paused,
- return the control to Replay.

Conceptually:

movement replay start
→ acceleration
→ extension
→ canonical impact
→ STOP

Do not continue automatically through the post-impact retained buffer.

### Manual inspection

The complete retained interval must remain manually inspectable.

If the user scrubs:

- before the impact frame: paused control may show normal Play,
- at or after the impact frame: central action should represent Replay,
- beyond impact is inspection material, not the default replay endpoint.

### Graph mode

Graph playback must use equivalent presentation semantics where appropriate and must not create a second conflicting timeline model.

---

# 4. Separate retained evidence bounds from presentation bounds

Do not change the persisted meaning of existing movement bounds.

Keep:

- logical movement bounds,
- retained playback bounds,
- canonical analysis/impact timestamp.

Introduce presentation/replay semantics separately.

Conceptually:

retainedStart ───── replayStart ───── impact ───── retainedEnd

Normal Replay:
replayStart → impact

Manual inspection:
retainedStart → retainedEnd

Do not reinterpret `playbackEndUs` as impact.

---

# 5. Canonical impact must not be fabricated

The current presentation mapper may fall back from a missing canonical analysis timestamp to playback start.

That fallback is acceptable for safe UI positioning, but it must not become a fake impact event.

Introduce an explicit distinction between:

- a real canonical impact/analysis timestamp,
- a generic fallback initial timestamp.

Replay-to-impact behavior must only use a real canonical event.

If no trustworthy impact endpoint exists, fall back to a safe normal playback behavior rather than pretending the movement has a known impact.

---

# 6. Replay control state

The player needs more than a binary `isPlaying` state.

The UI must distinguish at least:

- Playing,
- Paused before impact → Play,
- Paused at/after impact → Replay.

Use a circular replay arrow icon for Replay.

The compact Movement Presentation Player and expanded inspection player must use the same semantics.

Do not implement separate behavior in each view.

Prefer putting the shared decision in the timeline/presentation state layer.

---

# 7. Vertical-only movement viewport

## Goal

Use pose evidence to remove unnecessary ceiling and floor from the Movement Detail presentation so the athlete appears larger and the movement is easier to inspect.

## Critical constraint

**Do not crop horizontally.**

Always preserve:

left = 0
right = 1

The current recordings already have adequate horizontal breathing room, and horizontal cropping risks removing the striking hand, hikite, or lateral movement.

## Vertical crop

Calculate one stable viewport per movement:

topY
bottomY

Use pose landmarks across the relevant movement/replay interval.

### Top bound

Derive the top from reliable face/head landmarks plus explicit headroom.

MediaPipe Pose does not provide a literal top-of-skull point, so do not falsely treat nose/eyes/ears as the head boundary.

Use a derived head estimate and padding.

### Bottom bound

Use reliable ankle/heel/foot evidence plus floor margin.

### Stability

The crop must be fixed for the movement.

Do not recalculate the crop every frame.
Do not pan or zoom during playback.

### Landmark robustness

Use multiple frames rather than one instantaneous frame.

Reject obvious low-confidence/outlier values.

If a reliable vertical envelope cannot be produced, fall back to the existing full-frame presentation.

---

# 8. Shared video/overlay transform

The vertical viewport must be one shared presentation transform.

Video and analysis overlay must use exactly the same crop mapping.

This applies to:

- compact player,
- expanded inspection player,
- Video mode,
- Analysis mode.

Do not zoom/crop the video independently from `MovementAnalysisOverlayView`.

A normalized landmark at `(x, y)` must continue to render at the matching pixel location after the crop.

Display transforms must never feed back into analyzer calculations.

---

# 9. Show only one target ray in normal analysis

## Current behavior

Persisted straight-punch geometry may contain valid Jōdan, Chūdan, and Gedan rays, and the overlay currently draws all valid rays.

## Required behavior

The analyzer should continue evaluating all configured targets internally.

Persistence/debug data should continue to contain the complete target set where useful.

But the **normal Movement Detail Analysis overlay** must render only the likely/closest target:

`isClosest == true`

Example:

Closest target = CHUDAN
→ draw one Chūdan target ray only.

Do not display secondary dashed Jōdan/Gedan candidate rays in the normal movement drawing.

All-target values remain useful in Analysis Debug.

---

# 10. Target-height analyzer — fix the geometry, not just the drawing

The strange target rays are not only a rendering issue.

Correct the analysis geometry before tuning visual appearance.

---

# 11. Target reference translation bug

## Problem

Chūdan/Gedan target positions are derived from a previously created `BodyReference`, while the ideal target ray originates from the current striking shoulder at the canonical impact frame.

This can effectively compare:

old target image location
minus
current shoulder image location

If the athlete translated, leaned, or repositioned, the target can appear at an implausible angle.

## Required model

The target's body-relative height/offset is locked from the neutral/reference state.

During the movement, transport that target with a stable current body origin.

The neutral target should translate with the person.

It must not remain nailed to an old absolute normalized image coordinate.

---

# 12. Locked neutral axis

The target-height foundation already treats the neutral body axis as a locked reference.

Preserve that behavior for recorded movement analysis.

The target geometry should not rotate with instantaneous torso lean at the impact frame.

Use:

- locked neutral body vertical axis,
- locked/reference torso scale,
- current body translation.

Current torso lean may remain a diagnostic, but it must not silently redefine the target frame.

---

# 13. Aspect-correct analytical geometry

## Problem

Do not calculate Euclidean geometry directly in raw normalized MediaPipe image coordinates.

For portrait video, normalized X and normalized Y have different physical pixel scales.

Example:

0.1 normalized X on 1080 px width = 108 px
0.1 normalized Y on 1920 px height = 192 px

Treating those as equal distorts:

- lengths,
- angles,
- unit vectors,
- perpendicular directions,
- arm reach,
- circle intersections,
- target-ray direction.

## Requirement

Before performing 2D Euclidean geometry, convert to a compatible aspect-correct analytical coordinate frame.

Prefer one of the repository-approved explicit frames such as:

- source-image pixels,
- aspect-corrected 2D,
- gravity/camera-roll-corrected 2D when supported,
- neutral-body-normalized 2D when mathematically appropriate.

All points in one calculation must be in the same coordinate frame.

Raw normalized points remain valid transport/overlay evidence, but not Euclidean analysis space.

---

# 14. Stable arm reach

## Problem

Current ideal-target geometry derives arm reach from shoulder→elbow + elbow→fist at the impact frame.

That makes ideal target direction sensitive to one-frame landmark noise, perspective effects, and pose variation.

## Requirement

Use a stable anatomical/reference arm-reach estimate.

Conceptually:

R ≈ stable shoulder→elbow length
  + stable elbow→wrist/fist length

Prefer a multi-frame/reference estimate.

A later personal body calibration may replace or improve this estimate without changing the analyzer contract.

Keep provenance for how reach was obtained.

Do not use arbitrary fixed human dimensions.

If stable reach cannot be determined reliably, abstain/mark partial rather than fabricate geometry.

---

# 15. Target-height line and ideal endpoint

For each candidate target:

Jōdan
Chūdan
Gedan

construct a target-height line in the locked body-relative frame.

With:

S = current striking shoulder
R = stable arm reach
target line = transported body-relative target height

Find the physically forward intersection:

circle(center=S, radius=R)
×
target-height line

The forward intersection is the ideal fully extended fist endpoint.

Then:

idealRay = idealEndpoint - S

Compare actual shoulder→fist direction with this ideal ray.

Do not confuse the anatomical target location on the torso with the ideal forward fist endpoint.

---

# 16. Chūdan constant

Do not attempt to fix the visual problem merely by tuning the current `0.45 × torsoLength` Chūdan scalar.

First fix:

- coordinate frame,
- neutral-reference transport,
- locked axis,
- stable reach,
- intersection geometry.

The exact Chūdan/Gedan curriculum/body relationship remains a separate validation question.

---

# 17. Debug geometry visualization

Add developer-only debug evidence that makes bad geometry diagnosable.

Useful optional elements:

- current striking shoulder,
- neutral/reference body origin,
- current transported body origin,
- locked neutral vertical axis,
- current torso axis/lean,
- target anatomical point,
- target-height line,
- stable reach circle,
- ideal forward endpoint,
- actual fist point,
- actual shoulder→fist ray,
- target classification,
- target error,
- coordinate-frame/provenance details.

Normal users should still see only the simple single closest target line.

---

# 18. Offline Pose model upgrade: Full → Heavy

Use the Pose Landmarker Heavy model for the **offline Record & Analyze landmark extraction path**.

The Heavy model asset has already been added locally; reuse the repository asset rather than adding duplicate model files.

Make the model choice explicit in configuration.

Do not simply rename the current global Full-model constant and accidentally switch all pose use cases.

---

# 19. Keep live and offline model ownership separate

This task changes the offline processing model.

Do not automatically force Pose Heavy onto:

- Live Understanding,
- Live Guided Practice,
- camera setup,
- other latency-sensitive live features.

Those modes may continue using their existing model until a separate model-strategy decision is implemented.

Introduce a clean model/configuration boundary so offline and live pose consumers can select different model assets intentionally.

---

# 20. Landmark provenance

Every newly generated landmark track must clearly identify the exact producer configuration.

Include enough durable identity to distinguish at least:

- model family/variant: Heavy,
- model asset/hash,
- MediaPipe Tasks version,
- running mode: VIDEO,
- delegate,
- decoder version,
- relevant detection/presence/tracking settings,
- MLS format/version.

Do not leave a Heavy-generated MLS labelled as `pose_full`.

Model identity must be derived from the configuration actually used.

---

# 21. New developer action: Reprocess landmarks & reanalyze

Add a new action beside the existing developer reanalysis action on Recording Details.

Recommended label:

**Reprocess landmarks & reanalyze**

Existing action remains:

**Reanalyze with current pipeline**

---

# 22. Meaning of the two actions

## Reanalyze with current pipeline

Existing MLS
→ fresh segmentation
→ fresh analysis
→ publish on success

Must not invoke video landmark generation.

## Reprocess landmarks & reanalyze

Original MP4
→ force NEW current-model MLS
→ fresh segmentation from that new MLS
→ fresh analysis from that new MLS
→ publish on success

Must not reuse an existing MLS, even if it was generated by the same model/configuration.

---

# 23. Full reprocessing confirmation dialog

Use clear wording similar to:

**Reprocess landmarks and analysis?**

Run the current pose model again on the original video, create a new landmark stream, then rerun segmentation and analysis. Existing landmark streams and successful results will be preserved.

Actions:

Cancel
Reprocess

Do not describe this as destructive.

---

# 24. Availability rules

The full reprocessing action requires the original master MP4 to be available and readable.

If the MP4 is unavailable:

- do not start landmark reprocessing,
- disable/hide the action with a clear explanation.

The existing MLS-based Reanalyze action may still be available when the MP4 is gone but a valid landmark track remains.

---

# 25. Processing phases

The full operation should use the existing processing phase model:

Landmarks
→ Segmentation
→ Analysis
→ Ready

Do not create a parallel progress architecture.

The queue/tray and Recording Details status should identify the current phase consistently.

---

# 26. ProcessingRun / run mode

Extend run provenance so a run can distinguish whether it:

- reused an existing landmark track,
- generated a new landmark track.

Do not overload `REANALYSIS` so heavily that provenance becomes ambiguous.

Use a clear run mode / source strategy, for example conceptually:

INITIAL
REANALYSIS
LANDMARK_REPROCESS

or an equivalent explicit field.

The exact schema design is implementation-owned, but the persisted state must make the distinction queryable.

---

# 27. Force-new landmark generation API

Do not implement full reprocessing by deleting old tracks or by tricking the existing `loadLandmarks()` cache.

Add an explicit processor contract for the intent.

Conceptually:

load/reuse specific landmark track
vs
load/reuse compatible track
vs
force generate new landmark track

The force-new path must:

- require master media,
- create a new landmark-track UUID,
- never overwrite an existing MLS,
- use the current configured offline model,
- publish the new MLS atomically after successful extraction.

---

# 28. Failure behavior

## Landmark extraction fails

- new track/run marked failed appropriately,
- existing tracks/results untouched,
- previous current run remains current.

## Landmark extraction succeeds but segmentation fails

- preserve the new valid MLS,
- new run fails,
- previous successful run remains current.

## Landmark + segmentation succeed but analysis fails

- preserve the new valid MLS,
- preserve useful failed/partial run provenance,
- previous successful run remains current unless repository policy explicitly allows a better partial result.

## Complete success

- new run becomes current atomically,
- old runs and old MLS tracks remain readable for history/debugging.

---

# 29. Recording Details provenance

Make it easy to see which evidence produced the current result.

Show where available:

Landmarks
- Pose model variant
- model/pipeline version/hash
- landmark track ID

Segmentation
- segmenter version

Analysis
- analyzer key/version

Run
- mode/source strategy
- processed timestamp

For a reused MLS, indicate that landmarks were reused.

For a full reprocess, indicate that new landmarks were generated from the master video.

---

# 30. Movement Detail evidence ownership

Movement Detail must continue loading the exact landmark track referenced by the preferred/current analysis.

Do not simply load "latest MLS for this recording."

When the current Heavy-model run is published, its movements and analyses must point to the Heavy track.

Historical Full-model runs must continue pointing to their original Full track.

---

# 31. Tests — replay semantics

Add focused tests covering:

- initial timestamp is canonical impact when available,
- initial control is Replay at impact,
- Replay seeks to replay start before playing,
- playback stops at impact rather than retained playback end,
- decoder overshoot settles back to impact,
- post-impact retained range remains manually seekable,
- paused before impact displays Play,
- paused at/after impact displays Replay,
- compact and expanded player use identical semantics,
- no-impact fallback behaves safely.

---

# 32. Tests — vertical viewport

Cover:

- horizontal bounds always remain full width,
- top/bottom crop derives from reliable pose evidence,
- headroom and floor margin are preserved,
- outlier landmark frames do not cause extreme crop,
- one stable crop is used for whole movement,
- insufficient evidence falls back to full frame,
- overlay and video use identical viewport transform,
- portrait video remains geometrically aligned.

---

# 33. Tests — target overlay

Cover:

- all target results may remain persisted,
- normal overlay renders only `isClosest == true`,
- no secondary candidate rays appear,
- Analysis Debug can still expose all target values.

---

# 34. Tests — target analyzer geometry

Add deterministic core tests for:

### Aspect ratio

The same physical geometry represented at different portrait/landscape source dimensions must produce equivalent analytical target angles after coordinate conversion.

### Body translation

Translate the entire athlete in the image while preserving pose.

Target classification/error should remain effectively unchanged.

### Instantaneous torso lean

With a locked neutral reference, impact-frame torso lean must not rotate the target axis.

### Stable reach

One noisy impact-frame elbow/wrist sample must not radically change the ideal target ray when stable reference evidence exists.

### Target transport

The target line moves with current body origin translation.

### Exact targets

Maintain deterministic exact Jōdan/Chūdan/Gedan tests.

### Invalid geometry

Continue abstention/partial behavior for unusable evidence.

---

# 35. Tests — Heavy landmark reprocessing

Add tests proving:

- full reprocess requires master MP4,
- full reprocess invokes `VideoPoseProcessor.processVideo()`,
- normal Reanalyze does not invoke `processVideo()`,
- force-new processing creates a new landmarkTrackId,
- previous landmark tracks remain intact,
- Heavy track provenance contains Heavy model identity/hash,
- segmentation uses the newly generated track,
- analyses reference the newly generated track,
- successful full run becomes current only at terminal publication,
- failed full run leaves previous run current,
- a successfully generated new MLS survives later segmentation/analysis failure,
- repeat full reprocess creates another new track rather than reusing the previous Heavy track.

---

# 36. Regression coverage

Do not regress:

- CameraX master recording ownership,
- MLS file-format contract,
- existing reanalysis behavior,
- retry identity rules,
- atomic current-run publication,
- zero-detection success semantics,
- developer-only Analysis Debug,
- bounded manual playback,
- exact analysis/landmark provenance,
- old recordings using Full-model MLS,
- legacy recordings/results readability.

---

# 37. Suggested implementation order

1. Introduce explicit offline pose model configuration and wire Heavy for Record & Analyze.
2. Add full landmark-reprocess processor intent / run provenance.
3. Add developer Recording Details action and confirmation.
4. Add force-new MLS generation and end-to-end Heavy → segmentation → analysis publication.
5. Add regression tests for reanalysis vs full landmark reprocessing.
6. Correct target analyzer coordinate-frame handling.
7. Correct neutral target transport and locked-axis behavior.
8. Introduce stable arm-reach estimate.
9. Add analyzer/debug geometry tests.
10. Change normal overlay to render only the closest target ray.
11. Add replay/presentation endpoint semantics.
12. Add Replay icon/state handling in compact and expanded players.
13. Add stable vertical-only movement viewport.
14. Apply the shared viewport transform to video and overlays.
15. Run complete automated test suite.
16. Validate on a physical device using an existing recording and a newly Heavy-reprocessed version.

---

# 38. Physical acceptance scenario

Use one existing real recording that already has Full-model landmarks.

### Baseline

1. Open Recording Results.
2. Note current landmark track/model.
3. Note movement count.
4. Open several Movement Detail pages.
5. Note current target classifications and odd/easy-to-see target rays.

### Analyzer-only reanalysis

6. Run **Reanalyze with current pipeline**.
7. Confirm MediaPipe does not rerun.
8. Confirm the existing MLS is reused.
9. Confirm fresh segmentation/analysis uses the corrected target geometry.

### Full Heavy reprocessing

10. Run **Reprocess landmarks & reanalyze**.
11. Confirm a new Heavy landmark track is generated from the same MP4.
12. Confirm the old Full MLS still exists.
13. Confirm fresh segmentation uses the Heavy MLS.
14. Confirm fresh analysis references the Heavy MLS.
15. Confirm the new run becomes current only after successful completion.

### Movement Detail

16. Open a movement.
17. Confirm it opens paused at impact.
18. Confirm the center control is Replay.
19. Replay the punch and confirm it stops at impact.
20. Scrub after impact and confirm retained evidence is still available.
21. Confirm the athlete is vertically framed more tightly.
22. Confirm the image is never horizontally cropped.
23. Confirm only one closest target ray is shown.
24. Confirm the target ray visually aligns with sensible target-height geometry.
25. Use Analysis Debug to inspect the underlying target/reference/reach geometry.

---

# 39. Definition of Done

This corrective pass is complete when all of the following are true:

- Movement Detail opens on the real canonical impact frame when available.
- Replay starts earlier and stops at impact.
- Post-impact evidence is preserved but no longer the default playback tail.
- Replay state is visually distinct from Play.
- Movement video is cropped only vertically and remains stable.
- Video and overlays remain perfectly aligned after cropping.
- Normal Analysis shows exactly one closest target ray.
- Target-height math no longer uses distorted normalized-coordinate Euclidean geometry.
- Target geometry follows body translation without rotating with instantaneous lean.
- Stable arm reach replaces single-frame reach for ideal-ray construction.
- Offline Record & Analyze uses Pose Heavy with explicit provenance.
- Existing Full-model MLS files remain valid historical evidence.
- Reanalyze reuses MLS and remains fast.
- Reprocess landmarks & reanalyze creates a new MLS from the MP4 and then runs segmentation + analysis.
- Failed new processing never destroys or silently replaces a previous successful current result.
- The developer can compare old Full-based results with new Heavy-based results on the same master recording.

---

# 40. Explicit non-goals

Do not expand this task into:

- final coaching thresholds,
- overall movement/session scores,
- speed/path/hikite analyzer implementation,
- final Chūdan/Gedan curriculum validation,
- live Pose model strategy redesign,
- Face Mesh integration,
- new video files containing cropped movements,
- deleting old MLS evidence,
- replacing the queue architecture,
- unrelated Home/Learning UI work.

Keep this corrective pass focused on evidence quality, geometry correctness, replay/presentation behavior, and explicit full landmark reprocessing.
