# BodyHeightModel — Shared Utility Specification v2.1

**Status:** IN PROGRESS (Implemented locally, addressing review gaps and app integration)  
**Primary platform:** Pure Kotlin analyzer core (`dk.lasse.karateanalyzer.height`)  
**Repository:** `karate-kihon-analyzer`

## Purpose

Create a shared `BodyHeightModel` utility that converts canonical pose landmarks into a reusable description of the person's observed body-height geometry around a selected frame or configurable landmark-stream window.

The utility supports:

- straight-punch analysis
- kick analysis
- guard-height analysis
- chamber-height analysis
- hikite analysis
- tutorials
- debug overlays
- body-coordinate graphs
- future body-relative measurements

`BodyHeightModel` is not an analyzer and does not score technique.

It also does not make unvalidated target-height formulas authoritative.

## 1. Architectural position

Dependency direction:

Canonical pose evidence  
→ `FrameGeometryMath`  
→ `BodyHeightModel`  
→ `TargetHeightEstimator`  
→ movement-specific analyzer  
→ overlay/evidence model  
→ `OverlayCoordinateTransformer`  
→ renderer

### BodyHeightModel may depend on

- canonical upright/unmirrored pose evidence
- `FrameGeometry`
- source-normalized coordinate types
- aspect-correct coordinate types
- `FrameGeometryMath`
- source presentation timestamps
- pose-track identity
- immutable `BodyHeightModelConfig`
- requested frame-window radius

### BodyHeightModel must not depend on

- Compose
- Canvas
- viewport dimensions
- crops
- ContentScale
- thumbnails
- zoom/pan
- screen coordinates
- `OverlayCoordinateTransformer`
- movement scoring
- punch reach
- punch-angle scoring

## 2. Core responsibility

`BodyHeightModel` answers:

> What body geometry is currently observed in this selected sample/window?

It may provide:

- ShoulderCenter
- HipCenter
- TorsoCenter
- HeadAnchor
- current torso direction
- current torso length
- instantaneous torso-relative coordinates
- per-component quality and provenance

It does not answer where the optimal punch should terminate or whether the technique is correct.

## 3. Observed geometry vs target geometry

### ObservedBodyGeometry

Directly derived from current pose evidence:

- ShoulderCenter
- HipCenter
- TorsoCenter
- HeadAnchor
- currentBodyUp
- currentTorsoLength

These values may legitimately move as the person moves.

### TargetHeightEstimate

A versioned hypothesis about Jōdan, Chūdan, Gedan, or another target.

Target estimators may use:

- observed geometry
- canonical pose evidence
- reference geometry
- stabilized transport rules
- versioned anatomical proxies

Target estimation is a separate layer.

## 4. Canonical input space

Inputs must already use the canonical source frame:

**full upright, unmirrored source frame**

with:

- origin top-left
- +X right
- +Y down
- image extent `[0,1] × [0,1]`

`BodyHeightModel` does not resolve encoded-buffer rotation, display mirroring, preview transforms, or crop/display mapping.

## 5. Geometry rules

All operations involving distance, length, axis, projection, angle, or vector normalization must use `FrameGeometryMath`.

Do not perform Euclidean geometry directly in raw source-normalized X/Y.

## 6. Immutable configuration

All behavior affecting reproducibility must be represented by an immutable configuration or configuration identifier.

`BodyHeightModelConfig` must include or identify at least:

- landmark validity thresholds
- HeadAnchor strategy/version
- minimum valid torso length
- requested-window validation policy
- timestamp-gap policy
- landmark-stream cadence contract/source
- aggregation method/version
- bilateral-anchor requirements
- track-consistency policy

Outputs retain:

- configuration ID/version
- BodyHeightModel version

Determinism means:

same canonical pose evidence  
+ same FrameGeometry  
+ same configuration  
+ same model version  
+ same requested window  
→ same result.

### Configuration constraints

- `radius >= 0`
- `minimumTorsoLength` must be finite and strictly greater than zero

Invalid configuration fails explicitly.

## 7. Landmark validity

A landmark is usable only when it satisfies the configured validity policy.

The policy may consider:

- presence
- visibility
- finite coordinates
- source/provenance validity

Thresholds must be configuration-owned rather than hidden constants.

## 8. Required observed anchors

### ShoulderCenter

Requires valid bilateral shoulder evidence in the same sample:

`ShoulderCenter = midpoint(leftShoulder, rightShoulder)`

A single shoulder is not an observed ShoulderCenter.

Any future one-sided reconstruction must be separately named and versioned.

### HipCenter

Requires valid bilateral hip evidence in the same sample:

`HipCenter = midpoint(leftHip, rightHip)`

A single hip is not an observed HipCenter.

### TorsoCenter

When both bilateral centers are valid:

`TorsoCenter = midpoint(ShoulderCenter, HipCenter)`

## 9. Shared evidence requirement for torso geometry

Aggregate torso geometry must use the **same contributing samples** for shoulders and hips.

Do not aggregate ShoulderCenter from one subset and HipCenter from another.

First identify samples where both bilateral ShoulderCenter and bilateral HipCenter are valid. Only this shared torso-valid subset contributes to:

- aggregate ShoulderCenter
- aggregate HipCenter
- TorsoCenter
- currentBodyUp
- currentTorsoLength
- torso-relative projections

HeadAnchor may use a different valid subset.

Each component reports its own evidence provenance.

## 10. HeadAnchor

Support explicit, versioned `HeadAnchorStrategy` implementations.

Each strategy defines a fixed contributing landmark set.

Examples:

- bilateral ear midpoint
- fixed eyes-and-ears composite
- another validated fixed head composite

Do not average whichever head landmarks happen to be available.

Each strategy defines:

- required landmarks
- exact calculation
- validity requirements
- version
- fallback policy

If required evidence is missing, that strategy returns unavailable unless an explicitly named fallback strategy is invoked.

## 11. Current torso geometry

For each valid torso observation, calculate in aspect-correct space.

### currentTorsoVector

`ShoulderCenter - HipCenter`

Direction:

**HipCenter → ShoulderCenter**

### currentTorsoLength

`|currentTorsoVector|`

Units:

**source-frame-height units**

### currentBodyUp

`currentTorsoVector / currentTorsoLength`

Normalization is allowed only when:

`currentTorsoLength >= configuredMinimumTorsoLength`

Otherwise torso normalization is invalid.

## 12. Current torso-relative height

Define:

`currentTorsoHeight(P) = dot(P - currentTorsoCenter, currentBodyUp) / currentTorsoLength`

For the same derived torso geometry:

- HipCenter ≈ `-0.5`
- TorsoCenter = `0`
- ShoulderCenter ≈ `+0.5`

within defined tolerance.

This coordinate is instantaneous. Its origin, direction, and scale follow the observed torso.

Do not call it posture-independent.

## 13. Future reference-height coordinate

A future reference coordinate may define:

- fixed reference origin
- fixed reference axis
- fixed reference length
- calibration identity/version

Possible name:

`referenceBodyHeight`

It remains distinct from `currentTorsoHeight`.

Not required for the first implementation slice.

## 14. Configurable window radius

The caller chooses the requested window radius in **landmark-stream samples**.

| Radius | Requested evidence |
| --- | --- |
| 0 | selected sample only |
| 1 | selected sample ±1, up to 3 samples |
| 2 | selected sample ±2, up to 5 samples |

Initial impact-analysis default:

**radius = 1**

### Selection rule

Select the ±N neighboring landmark-stream samples **before validity filtering**.

Invalid or missing samples reduce the usable window.

Do not search farther outward to replace invalid samples.

This keeps radius temporally meaningful.

## 15. Radius 0 semantics

Radius 0 is an intentional single-sample evaluation.

It is not a degraded fallback.

Report:

`SINGLE_FRAME_REQUEST`

A larger requested window collapsing to one usable observation reports:

`SINGLE_FRAME_FALLBACK`

## 16. Temporal validation

Continuity is validated using timestamps.

Do not assume sample-index adjacency implies temporal validity.

The configuration defines a maximum allowed timestamp gap based on the **landmark stream's sampling contract**, not necessarily capture-video FPS.

Example:

A 60 FPS video may have a 30 FPS MLS.

The configured cadence source and gap policy are retained in provenance.

## 17. Temporal continuity anchored to selected sample

Validate outward from the selected sample on each side:

- selected → previous 1 → previous 2 → ...
- selected → next 1 → next 2 → ...

If a timestamp gap exceeds the configured maximum:

- exclude the sample beyond that gap
- exclude all farther samples on that side

Disconnected evidence must not enter the aggregate.

Excluded samples report the reason.

## 18. Pose-track consistency

All observations combined for a component must refer to the same person/pose track.

Do not aggregate different tracked people.

Use explicit track identity where available.

If continuity cannot be established, exclude conflicting evidence or abstain according to configuration.

## 19. Per-component contributing evidence

Do not expose only one global usable-sample count.

Each major component reports:

- requested radius
- requested sample identities/timestamps where available
- contributing timestamps
- usable count
- excluded observations
- exclusion reasons
- evidence/window state

At minimum track this separately for:

- torso geometry
- HeadAnchor

## 20. Aggregation strategy

For v1:

**component-wise median over usable observations**

For odd count:

use the middle sorted value.

For even count:

use the midpoint/mean of the two central sorted values.

For one usable observation:

pass it through unchanged.

Aggregation must be deterministic.

## 21. Aggregate torso order

1. Identify the shared torso-valid sample subset.
2. Aggregate ShoulderCenter coordinates over that exact subset.
3. Aggregate HipCenter coordinates over that exact subset.
4. Recompute:
   - TorsoCenter
   - currentTorsoVector
   - currentTorsoLength
   - currentBodyUp
   - derived torso-relative coordinates
5. Retain individual per-sample observations for diagnostics.

Do not independently aggregate derived torso quantities.

## 22. Head aggregation

HeadAnchor aggregation uses:

- configured HeadAnchor strategy
- its own valid evidence subset
- the configured aggregation rule unless separately versioned

Do not change landmark membership between contributing observations.

## 23. Window/evidence state precedence

For each component:

1. zero usable samples → `UNAVAILABLE`
2. radius 0 with valid selected sample → `SINGLE_FRAME_REQUEST`
3. radius > 0 with exactly one usable sample → `SINGLE_FRAME_FALLBACK`
4. all requested samples exist and are usable → `FULL_REQUESTED_WINDOW`
5. otherwise → `REDUCED_WINDOW`

Missing neighbors at recording boundaries count as reduced evidence.

Window state describes completeness, not quality.

## 24. Sample count vs quality

Sample count is not confidence.

Multiple observations may disagree or share the same tracking error.

Track separately:

### Evidence completeness

- requested radius
- usable count
- window state

### Estimate quality

May consider:

- landmark validity
- frame-to-frame disagreement
- plausibility
- timestamp regularity
- fallback level

Do not infer high quality solely from sample count.

## 25. Frame disagreement

Retain a disagreement diagnostic for aggregated components, such as:

- max deviation
- median absolute deviation
- range

Exact metric may be configuration/version-specific.

It does not alter anchor coordinates.

## 26. Independent component validity

Components are independently valid.

For example:

- torso geometry valid
- HeadAnchor unavailable

is a valid result.

Do not invalidate the full model because one independent component is unavailable.

## 27. TargetHeightEstimator evidence access

`TargetHeightEstimator` may consume both:

- `ObservedBodyGeometry`
- relevant canonical pose evidence for the same selected sample/window

This is necessary because compatibility target formulas may need landmarks not present in HeadAnchor.

An ear-based HeadAnchor does not contain enough information to reproduce mouth/nose Jōdan.

`BodyHeightModel` must not change its HeadAnchor strategy merely to support a compatibility formula.

## 28. Jōdan compatibility evidence

For:

`JODAN_MOUTH_NOSE_110_V1`

define:

`mouth = midpoint(MOUTH_LEFT, MOUTH_RIGHT)`

Both mouth landmarks are required.

Nose must satisfy the configured landmark validity policy.

For a multi-sample window:

1. derive bilateral mouth midpoint and nose per sample
2. require consistent track identity
3. apply temporal validation
4. use only samples where both mouth and nose are valid
5. aggregate mouth coordinates over that shared subset
6. aggregate nose coordinates over that same subset
7. compute:

`Jōdan = mouth + 1.10 × (mouth - nose)`

Do not combine mouth evidence from one subset with nose evidence from another.

## 29. Compatibility target estimators

Compatibility target formulas remain available for debug/migration only.

They are not automatically authoritative for scoring.

### Chūdan

`CHUDAN_TORSO_RATIO_045_V1`

`C = S + 0.45(H - S)`

Explicitly shoulder-sensitive.

### Gedan lower abdomen

`GEDAN_TORSO_RATIO_080_V1`

`G = S + 0.80(H - S)`

### Gedan hip/groin level

`GEDAN_HIP_LEVEL_V1`

`G = H`

### Jōdan

`JODAN_MOUTH_NOSE_110_V1`

`MouthMidpoint + 1.10 × (MouthMidpoint - Nose)`

All remain versioned anatomical proxies pending validation.

## 30. Target estimator validity

Each target estimator reports its own:

- status
- contributing timestamps
- usable count
- exclusion reasons
- estimator ID/version
- configuration ID
- point/Y when available
- quality/disagreement
- reference identity if applicable

Missing Jōdan evidence does not invalidate Chūdan or Gedan.

## 31. Stabilized target model remains deferred

No authoritative stabilized target-placement rule is defined yet.

A later specification must determine how targets respond to:

- whole-body translation
- torso lean
- shoulder articulation
- reference anatomy
- head movement
- hip movement
- current vs reference torso scale

Compatibility estimators must not silently become authoritative scoring inputs.

## 32. Offset contracts for future estimators

Any future target offset must define:

- scalar vs vector
- coordinate system
- units
- direction
- current vs reference normalization
- reference/calibration identity

No unnamed generic offset is permitted.

## 33. Horizontal target-line behavior

After a target estimator produces a source-frame point/Y, overlays may draw a horizontal line through that target height.

For current v1 semantics:

**horizontal means horizontal in the canonical upright source frame.**

Torso tilt does not rotate the line.

## 34. Source timestamps

All evidence uses the actual landmark sample timestamp in its documented recording-relative timebase.

Preserve timestamps and identities exactly.

Do not substitute assumed FPS-derived or requested extraction times.

## 35. Output model

`ObservedBodyGeometry` conceptually contains:

### Identity

- recording identity where available
- selected/evaluation timestamp
- requested radius
- BodyHeightModel version
- configuration ID

### Torso component

- aggregate ShoulderCenter
- aggregate HipCenter
- TorsoCenter
- currentBodyUp
- currentTorsoLength
- evidence state
- contributing timestamps
- excluded observations/reasons
- disagreement/quality

### Head component

- HeadAnchor
- strategy/version
- evidence state
- contributing timestamps
- excluded observations/reasons
- disagreement/quality

### Per-sample diagnostics

- individual observed anchors
- timestamps
- validity
- track identity

## 36. BodyHeightModel exclusions

It must not calculate:

- striking side
- stable arm reach
- reach circle
- punch target intersection
- ideal endpoint
- actual-vs-optimal angle
- technique score

`FrameGeometryMath` owns generic geometry operations.

Movement analyzers own semantic selection and interpretation.

## 37. Rendering exclusions

`BodyHeightModel` must not know about:

- crop
- viewport
- Fit/Crop
- Canvas
- zoom/pan
- fullscreen

Output remains canonical-source based.

## 38. Numerical tolerances

Derived floating-point geometry uses explicitly defined tolerances.

Examples:

- midpoint equality
- unit-vector length
- projection
- expected ±0.5 torso-height values

Do not require exact binary equality for derived floating-point results.

Identifiers and timestamps remain exact where their types permit.

## 39. Aspect-ratio test clarification

Aspect-correction tests compare **equivalent image geometry represented at different pixel dimensions/aspect-consistent resolutions**.

Do not take identical normalized coordinates and arbitrarily change the aspect ratio; that represents different image geometry.

## 40. Acceptance tests — observed anchors

1. Bilateral ShoulderCenter equals midpoint within tolerance.
2. Missing one shoulder makes ShoulderCenter unavailable.
3. Bilateral HipCenter equals midpoint within tolerance.
4. Missing one hip makes HipCenter unavailable.
5. TorsoCenter is recomputed from aggregate shared-evidence shoulder/hip anchors.
6. currentBodyUp has unit length within tolerance.
7. currentTorsoLength equals ShoulderCenter↔HipCenter distance within tolerance.
8. Degenerate torso geometry is rejected.
9. currentTorsoHeight yields approximately HipCenter −0.5, TorsoCenter 0, ShoulderCenter +0.5.

## 41. Acceptance tests — shared evidence

10. Torso aggregation uses only samples where both bilateral centers are valid.
11. Shoulder-only and hip-only samples are never combined into one torso.
12. HeadAnchor may use a different subset without altering torso provenance.
13. Each component exposes its own contributing timestamps and usable count.

## 42. Acceptance tests — configurable window

14. Radius 0 evaluates only the selected sample.
15. Radius 1 selects at most selected ±1 before filtering.
16. Radius 2 selects at most selected ±2 before filtering.
17. Invalid samples are never replaced by farther samples.
18. Complete valid request produces `FULL_REQUESTED_WINDOW`.
19. Missing/invalid evidence produces `REDUCED_WINDOW`.
20. Larger request reduced to one observation produces `SINGLE_FRAME_FALLBACK`.
21. Radius 0 valid evaluation produces `SINGLE_FRAME_REQUEST`.
22. Zero usable observations produce `UNAVAILABLE`.
23. Recording-boundary missing neighbors count as reduced evidence.

## 43. Acceptance tests — temporal policy

24. Gap validation uses configured landmark-stream cadence.
25. Capture FPS is not implicitly used as landmark-stream cadence.
26. Validation proceeds outward from selected sample.
27. Excessive gap excludes that sample and all farther samples on that side.
28. Disconnected evidence cannot enter aggregation.
29. Different pose-track identities are never aggregated.
30. Contributing timestamps are preserved exactly.

## 44. Acceptance tests — aggregation

31. Component-wise median is deterministic for odd counts.
32. Even counts use midpoint of two central values.
33. One valid sample passes through unchanged.
34. Torso-derived geometry is recomputed after anchor aggregation.
35. Per-sample results remain available.
36. Disagreement is reported separately from sample count.

## 45. Acceptance tests — Jōdan evidence

37. Mouth midpoint requires both mouth landmarks.
38. Mouth and nose use the same contributing subset.
39. Ear-based HeadAnchor does not change to support Jōdan proxy.
40. Missing Jōdan evidence does not invalidate torso geometry.
41. Jōdan provenance lists actual contributing timestamps.

## 46. Acceptance tests — compatibility estimators

42. Chūdan 45% reproduces existing formula.
43. Shoulder elevation moves the compatibility Chūdan as mathematically expected.
44. No shoulder-independence claim is made for that proxy.
45. Gedan 80% reproduces existing formula.
46. Hip-level Gedan equals aggregate HipCenter.
47. Mouth/nose Jōdan reproduces its versioned formula.
48. Target validity is independent by target.
49. Estimator/configuration versions are retained.

## 47. Acceptance tests — reproducibility

50. Same evidence + FrameGeometry + config + model version + radius produces the same result.
51. Landmark-threshold changes are reflected in provenance.
52. HeadAnchor-strategy changes alter strategy/version identity.
53. Timestamp-gap-policy changes alter configuration identity.
54. Crop/viewport/ContentScale changes do not affect BodyHeightModel output.
55. Negative radius is rejected.
56. Non-finite or non-positive minimum torso length is rejected.

## 48. Debug visualization

Analysis Debug should show:

- selected/evaluation timestamp
- requested radius
- torso contributing samples
- head contributing samples
- excluded samples and reasons
- per-sample ShoulderCenter/HipCenter
- aggregate ShoulderCenter/HipCenter
- TorsoCenter
- currentBodyUp
- currentTorsoLength
- HeadAnchor
- disagreement/quality
- compatibility target lines
- compatibility estimator IDs

## 49. First implementation slice

1. immutable configuration model
2. configuration validation
3. landmark-validity policy
4. bilateral per-sample ShoulderCenter
5. bilateral per-sample HipCenter
6. fixed-strategy per-sample HeadAnchor
7. configurable radius API
8. select ±N samples before filtering
9. selected-sample-anchored temporal validation
10. track validation
11. per-component evidence collection
12. shared-evidence torso subset
13. component-wise aggregation
14. recomputed torso geometry
15. currentTorsoHeight
16. evidence/quality/provenance output
17. canonical pose-evidence access for TargetHeightEstimator
18. compatibility target estimators
19. debug visualization
20. validation against existing MLS recordings

## 50. Deferred stabilized target-placement task

The stabilized target specification remains separate and non-blocking.

Observed geometry already supports:

- body-coordinate graphs
- impact-window inspection
- compatibility overlays
- target-model research
- debug validation

Authoritative target scoring waits until the stabilized placement model is separately specified and validated.

## Architectural summary

`BodyHeightModel` is a shared observed-body geometry utility.

The caller chooses the requested temporal radius in landmark-stream samples.

Requested samples are selected before filtering and never backfilled from farther away.

The model validates temporal continuity outward from the selected sample and aggregates only connected, track-consistent evidence.

Torso geometry always uses the same shared shoulder/hip evidence subset.

Head geometry may use its own evidence subset.

Sample count and quality are independent.

Configuration is explicit and reproducible.

`TargetHeightEstimator` may use both observed geometry and canonical pose evidence.

Compatibility Jōdan/Chūdan/Gedan formulas remain versioned debug proxies until a stabilized target-placement model is accepted.
