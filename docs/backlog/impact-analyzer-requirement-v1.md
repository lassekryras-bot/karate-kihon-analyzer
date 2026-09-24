# ImpactAnalyzer — Shared Analyzer Requirement v1

**Status:** Design baseline for implementation  
**Scope:** Offline analysis of one already-segmented, analysis-suitable movement  
**Primary use:** Locate the terminal event of a known strike and identify the stable terminal window that follows it

---

## 1. Purpose

`ImpactAnalyzer` locates the terminal event of a **known, already-segmented movement** using a preselected striking weapon and its associated limb articulation.

It answers:

1. **When does the selected weapon transition from active strike motion into its terminal state?**
2. **What stable terminal window follows that transition?**
3. **Which observed frame/sample is the best clean representative of that terminal state?**

It does **not** attempt to detect arbitrary impact from arbitrary human motion.

The analyzer is intentionally constrained to recordings where the activity, side, weapon and camera setup are already known and sufficiently measurable.

---

## 2. Architectural position

Authoritative dependency direction:

`MP4 + MLS`
→ canonical pose evidence
→ retrospective movement segmentation
→ selected movement
→ selected weapon / limb semantics
→ shared geometry + motion utilities
→ `ImpactAnalyzer`
→ impact result + stable terminal evidence
→ movement-specific analyzers
→ overlays / plots / measurements

For straight-punch analysis:

`Segmenter`
→ `ImpactAnalyzer`
→ `BodyHeightModel`
→ `TargetHeightEstimator`
→ `PunchTargetAngleDeviationAnalyzer`

`ImpactAnalyzer` owns **terminal-event semantics**.

It must not duplicate generic geometry, filtering, angle, coordinate-transform or body-scale mathematics that belongs in shared utilities.

---

## 3. Hard scope assumptions

The caller must already know:

- there is exactly one relevant movement/repetition in the supplied logical segment;
- the activity or movement family;
- the striking side;
- the weapon to monitor;
- the limb family used for articulation;
- the camera/view profile is approved for this measurement;
- the supplied pose track has sufficient quality for analysis.

Examples:

- left-hand straight punch;
- right-hand hook;
- left-foot front kick;
- right-leg roundhouse kick.

The analyzer must **not**:

- segment the full recording;
- discover multiple repetitions;
- classify the technique;
- choose left versus right;
- decide which hand/foot/knee is the weapon;
- infer an unsuitable camera view into a usable one;
- manufacture a result when projected motion is not measurable enough.

---

## 4. Measurability contract

The app should request recording views that make the intended movement measurably strong in the available pose representation.

This is an upstream activity/capture responsibility.

Examples:

- a straight Chūdan punch should normally use a side or useful oblique view where weapon travel and arm extension are clearly projected;
- a hook or roundhouse may support other views when its dominant motion remains measurable;
- a technique/view pair should not be enabled merely because Pose returns landmarks.

The analyzer may assume an approved analysis profile, but it must still abstain when the actual segment contains insufficient measurable evidence.

Initial abstention reason:

`INSUFFICIENT_MEASURABLE_MOTION`

The goal is **high-quality comparable data**, not universal impact detection under arbitrary viewpoints.

---

## 5. Inputs

### 5.1 Required movement input

A bounded logical movement segment containing:

- canonical timestamps;
- canonical pose landmarks / MLS track;
- small pre/post evidence where available;
- recording / landmark-track identity;
- actual frame geometry.

The segment is expected to contain one outbound strike ending in a terminal pose.

If the same selected weapon substantially retracts again inside the same logical movement, that movement profile requires separate validation before this algorithm may be assumed valid.

### 5.2 Required semantic configuration

The caller supplies an `ImpactAnalysisProfile` containing at minimum:

- activity/profile ID;
- striking side;
- weapon point definition;
- limb family: `UPPER_LIMB` or `LOWER_LIMB`;
- approved capture/view profile;
- algorithm/config version.

The profile may later provide validated per-technique configuration, but v1 should avoid unnecessary technique-specific formulas.

### 5.3 Upstream QoM evidence

When available, the analyzer receives the **existing production QoM timeline** for the supplied movement.

It must reuse the authoritative segmenter evidence rather than recalculate a competing full-body QoM implementation locally.

Required contextual values are preferably:

- movement logical start;
- movement logical end;
- QoM rolling-area samples;
- principal QoM peak inside the movement;
- segmenter version/provenance.

The QoM signal is contextual evidence. It is not itself the impact answer.

---

## 6. Shared-utility requirements

Before implementation, generic operations must be mapped to shared utilities.

### Existing shared ownership

`FrameGeometryMath` owns:

- aspect-correct coordinate conversion;
- distances;
- angles;
- axis projection;
- generic geometry primitives.

The analyzer must not implement local aspect-ratio math or duplicated angle formulas.

### Required reusable motion utility

The QoM detector already has a validated short causal noise-reduction pattern:

`position`
→ trailing 3-sample coordinate median
→ trailing 3-sample arithmetic mean
→ timestamp-derived velocity

This filtering/velocity pattern should be factored into or exposed through a reusable shared motion utility if `ImpactAnalyzer` needs the same operation.

Do not copy the QoM filtering implementation into `ImpactAnalyzer`.

### Required body-scale source

The spatial deadband is intended to be body-normalized.

Initial experimental baseline:

`spatialMotionDeadband = 0.02 × bodyHeight`

Production code must obtain body height/body scale from an authoritative shared utility or explicit upstream input.

`ImpactAnalyzer` must not invent its own approximate full-body-height formula.

If authoritative body scale is unavailable, the body-normalized deadband remains blocked rather than silently approximated.

---

## 7. Selected weapon trajectory

The weapon is chosen upstream.

Examples:

- composite hand;
- wrist;
- knee;
- composite foot.

The analyzer operates on one timestamped selected-weapon trajectory.

If composite weapon construction is required, it must use a shared, explicit aggregation utility/profile rather than analyzer-local ad hoc landmark averaging.

For image-plane impact analysis, weapon points are converted through the authoritative `FrameGeometry` / `FrameGeometryMath` path into aspect-correct analysis coordinates.

---

## 8. Translation evidence

For consecutive weapon samples:

`stepDistance(i) = distance(P(i), P(i-1))`

using aspect-correct analysis geometry.

Apply the spatial motion deadband:

`effectiveStep(i) = 0` when `stepDistance(i) < spatialMotionDeadband`

otherwise:

`effectiveStep(i) = stepDistance(i)`

Then:

`accumulatedTravel(i) = Σ effectiveStep`

and:

`weaponSpeed(i) = effectiveStep(i) / Δt(i)`

Use actual timestamps. Never derive velocity from nominal FPS.

### 8.1 Travel progress

Because the analyzer receives one bounded offline repetition, travel progress may initially be normalized by the selected weapon's observed meaningful travel in that repetition:

`travelProgress ∈ [0,1]`

This normalization is valid only for validated movement profiles in which the selected weapon travels toward a terminal state and does not perform a substantial second movement before the segment closes.

---

## 9. Limb articulation evidence

The analyzer uses two angles for the selected limb.

### 9.1 Upper limb

For a hand/arm weapon:

1. **Proximal angle**
   - current body-up axis ↔ shoulder→elbow

2. **Joint angle**
   - shoulder→elbow ↔ elbow→hand/wrist
   - interpreted as elbow articulation

### 9.2 Lower limb

For a knee/foot weapon:

1. **Proximal angle**
   - current body-up axis ↔ hip→knee

2. **Joint angle**
   - hip→knee ↔ knee→ankle/foot
   - interpreted as knee articulation

### 9.3 Wrapped angular differences

All temporal angle differences must use wrapped-angle math.

A ±180° representation boundary must not create a false large movement.

This operation belongs in shared geometry/math utilities.

---

## 10. Angular noise floor

Very small frame-to-frame angle changes are treated as pose-estimation noise rather than meaningful articulation movement.

Initial experimental baseline:

`angularMotionDeadband ≈ 3.6° per sample`

This is a **measurement-noise threshold**, not “2% of full extension” and not an anatomical truth.

Do not derive the angular deadband from 180° or from a technique's expected range.

It remains provisional until validated across more recordings, views, ages, frame rates and techniques.

---

## 11. Articulation progress

For each angle, determine progress away from its stable pre-movement/start state toward its largest meaningful excursion within the bounded repetition.

Normalize each component independently:

`proximalProgress ∈ [0,1]`

`jointProgress ∈ [0,1]`

For diagnostics, preserve the intuitive additive form:

`additiveArticulationProgress = proximalProgress + jointProgress`

Range:

`0 … 2`

For combination with 0–1 signals:

`articulationProgress = additiveArticulationProgress / 2`

The additive representation is preferred over Euclidean combination because it remains easy to interpret:

- one moving component contributes approximately one unit;
- two strongly progressing components can contribute approximately two units.

---

## 12. Angular motion rate

After applying the angular noise floor, calculate timestamp-based angular velocities for the two articulation components.

Conceptually:

`proximalAngularVelocity = |ΔproximalAngle| / Δt`

`jointAngularVelocity = |ΔjointAngle| / Δt`

Combined diagnostic angular motion:

`angularSpeed = sqrt(proximalAngularVelocity² + jointAngularVelocity²)`

Angular speed answers:

> Is the selected limb articulation still changing?

It is distinct from articulation progress, which answers:

> How far has the limb configuration progressed through this repetition?

---

## 13. Role of the segmenter's QoM signal

The production QoM segmenter answers:

> Where is the bounded period of relevant body movement?

For a punch it currently uses:

- left arm;
- right arm;
- torso;
- hip-relative MediaPipe world coordinates;
- short causal position filtering;
- point velocities;
- equal block aggregation;
- a 150 ms rolling QoM area;
- hysteresis.

This signal intentionally includes more than the selected striking weapon.

Therefore:

**Do not replace selected-weapon motion with whole-body QoM.**

Instead, reuse QoM for:

1. movement bounds;
2. a search anchor;
3. optional supporting evidence.

### 13.1 Principal QoM peak

Within the already-selected movement, locate the principal rolling-area QoM peak.

For the validated straight-punch use case, `ImpactAnalyzer` should normally search for terminal transition **on or after the principal QoM peak**.

This removes early acceleration/buildup regions from consideration.

The exact peak-selection policy must be versioned if profiles with multiple meaningful QoM peaks are later supported.

---

## 14. Separate event detection from stable-window detection

One signal must not be forced to answer both questions.

The analyzer has two distinct outputs:

### A. Terminal transition

A sharp event:

> The selected striking limb transitions from active motion into terminal settling.

### B. Stable terminal window

A sustained state:

> The selected weapon and articulation remain sufficiently quiet after the terminal transition.

The stable window is **not** defined as the maximum of the terminal-event score.

---

## 15. Terminal-transition signal

### 15.1 Selected-limb motion envelope

Normalize the selected weapon's linear speed and the selected limb's angular speed to robust segment-relative scales.

Initial exploratory normalization:

- robust high-percentile speed scale, e.g. p95;
- clamp normalized motion to `[0,1]`.

Then combine them as a union-like motion envelope:

`motionEnvelope = 1 - (1 - linearMotion) × (1 - angularMotion)`

This becomes high when either selected-weapon translation or selected-limb articulation is actively changing.

The exact robust normalization method is versioned and provisional.

### 15.2 Progress gate

Require the strike to have substantially progressed before a terminal event is allowed.

Initial combination:

`progressGate = sqrt(travelProgress × articulationProgress)`

This prevents early pauses/noise from being mistaken for impact.

### 15.3 Motion-collapse evidence

Calculate the falling edge of the selected-limb motion envelope:

`motionFall = max(0, -d(motionEnvelope)/dt)`

Normalize the falling-edge magnitude using the configured robust scale.

A strong `motionFall` means the selected limb has rapidly changed from active motion toward stillness.

### 15.4 Offline post-event confirmation

`ImpactAnalyzer` is offline and may use a short future confirmation window.

A terminal candidate should be stronger when immediately following samples remain quiet.

Use actual elapsed time rather than a hard dependency on frame count.

Initial provisional confirmation duration:

`~100 ms`

### 15.5 Primary event score

Conceptually:

`terminalTransitionScore`
`= progressGate`
`× normalizedMotionFall`
`× postTransitionStability`

Only candidates inside the permitted post-QoM-peak search region are eligible.

The exact normalization details remain configurable/versioned.

---

## 16. QoM falling edge as supporting evidence

The 150 ms rolling QoM area has useful context, but it also contains trailing history and therefore has expected temporal delay.

Its falling edge may be retained as **supporting diagnostic evidence**.

It may:

- increase confidence when it agrees with the selected-limb motion collapse;
- help resolve close candidates;
- appear in debug plots.

It must not override missing selected-weapon/limb terminal evidence.

A whole-body QoM stop is not synonymous with weapon impact.

---

## 17. Stable terminal window

After the terminal transition, search for sustained quiet selected-limb motion.

The stable state requires both:

1. selected-weapon translation below its spatial motion deadband;
2. selected-limb articulation changes below their angular motion deadband.

A stable window begins at the **earliest** timestamp after the terminal transition for which those conditions remain satisfied for at least the configured minimum duration.

Initial provisional minimum confirmation:

`~100 ms`

The stable window continues until:

- meaningful selected-weapon motion resumes; or
- meaningful articulation motion resumes; or
- the supplied analysis segment ends.

Use time-based duration, not “exactly N frames,” so semantics remain comparable at 30 and 60 FPS.

### Important

The exploratory continuous “stable confidence” score is useful for plotting/debugging but is **not authoritative** for stable-window boundaries.

Production stable-window boundaries come from sustained deadbanded motion state.

---

## 18. Representative terminal sample

The analyzer returns both:

- the terminal-transition timestamp;
- a representative observed sample from the stable terminal window.

The representative sample should prioritize:

1. being inside the confirmed stable window;
2. good required-landmark quality;
3. proximity to the stable-window onset unless a later sample is materially cleaner.

Selection policy must be deterministic and versioned.

A downstream analyzer must be able to distinguish:

- theoretical/continuous event estimate if one exists;
- selected actual MLS sample;
- stable-window representative sample.

---

## 19. Result model

Suggested conceptual result:

`ImpactAnalysisResult`

Fields should include at minimum:

- `status`;
- `movementId` / movement identity;
- `weaponId`;
- `side`;
- `limbFamily`;
- `viewProfile`;
- `terminalTransitionTimestampUs`;
- `selectedObservedTimestampUs`;
- `stableWindowStartTimestampUs`;
- `stableWindowEndTimestampUs`;
- `stableRepresentativeTimestampUs`;
- stable representative weapon point;
- stable representative articulation state;
- travel progress at transition;
- articulation progress at transition;
- selected-weapon speed evidence;
- angular-speed evidence;
- terminal-transition score;
- QoM peak timestamp/reference;
- QoM supporting evidence if used;
- quality/confidence diagnostics;
- landmark-track identity;
- frame-geometry provenance;
- analyzer version;
- configuration version;
- abstention/failure reason.

Persist enough provenance to reproduce the decision.

---

## 20. Abstention behavior

The analyzer must prefer abstention over a fabricated terminal event.

Candidate reasons include:

- `INSUFFICIENT_MEASURABLE_MOTION`
- `INSUFFICIENT_ARTICULATION_CHANGE`
- `WEAPON_TRACK_UNAVAILABLE`
- `LIMB_GEOMETRY_UNAVAILABLE`
- `BODY_SCALE_UNAVAILABLE`
- `INVALID_TIMESTAMPS`
- `FRAME_GEOMETRY_UNAVAILABLE`
- `NO_POST_PEAK_TERMINAL_TRANSITION`
- `NO_STABLE_TERMINAL_WINDOW`
- `TRACKING_QUALITY_INSUFFICIENT`
- `UNSUPPORTED_ANALYSIS_PROFILE`

Abstention reasons are part of the public analyzer contract and must be versioned if semantics change.

---

## 21. Non-goals

`ImpactAnalyzer` v1 does not:

- classify punch/kick type;
- decide left/right;
- detect repetitions;
- replace the QoM movement segmenter;
- calculate target height;
- calculate punch-target deviation;
- score karate quality;
- estimate physical contact force;
- claim a universal biomechanical definition of impact;
- compensate for arbitrary bad camera placement;
- use body-center-line lateral position as a generic impact formula.

Body-center-line trajectory may remain a useful **diagnostic visualization** for confirming the expected weapon behavior in specific techniques.

---

## 22. View handling

Do not create separate “front impact math” and “side impact math” in v1.

Instead, activity configuration declares which view/profile is measurable enough for that movement.

The same analyzer structure is used once the recording passes that upstream constraint.

If future validation demonstrates that a technique requires different evidence weighting by view, add that as an explicit versioned `ImpactAnalysisProfile` rather than hidden conditional behavior.

---

## 23. Debug evidence and plots

For development/research, the analyzer should expose enough evidence to plot:

1. production QoM rolling area;
2. selected weapon 2D trajectory;
3. meaningful accumulated weapon travel;
4. selected weapon speed;
5. proximal angle;
6. joint angle;
7. additive articulation progress;
8. angular speed;
9. selected-limb motion envelope;
10. selected-limb motion falling edge;
11. progress gate;
12. post-transition stability;
13. terminal-transition score;
14. stable-window state;
15. selected terminal and representative timestamps.

All plots must derive from the same authoritative values used by the analyzer.

Debug visualization must not independently recalculate analyzer math.

---

## 24. Initial experimental parameters

These values are **starting calibration values only**.

| Parameter | Initial value | Status |
|---|---:|---|
| Spatial motion deadband | `2% of body height` | Provisional |
| Angular motion deadband | `~3.6° per sample` | Provisional |
| QoM search anchor | principal production rolling-area peak | Initial v1 behavior |
| Post-transition confirmation | `~100 ms` | Provisional |
| Stable minimum duration | `~100 ms` | Provisional |
| Motion normalization | robust segment-relative scale such as p95 | Provisional |
| Articulation progress combination | additive two-angle progress | Preferred v1 |
| Translation/articulation progress gate | geometric mean | Preferred v1 |

Changing a calibrated parameter or signal definition requires configuration/analyzer versioning.

---

## 25. Current empirical observations

On the first examined straight punch:

- production QoM identified the movement interval cleanly;
- the principal QoM rolling-area peak occurred around `1.70 s`;
- selected-limb terminal-transition evidence peaked later, around `1.87 s`;
- the visually useful terminal hold followed that transition.

This supports the intended interpretation:

`movement buildup`
→ `QoM peak`
→ `terminal deceleration / motion collapse`
→ `terminal transition`
→ `stable terminal window`

These timestamps are **recording-specific observations**, not algorithm thresholds.

A second punch was also visually replayed with the event timeline and supported continuing with the same architecture, but broader replay validation is still required before locking calibration.

---

## 26. Validation requirements

Before declaring v1 stable, replay validation should include:

- multiple straight punches from the same recording;
- alternating left/right punches;
- additional users;
- 30 FPS and 60 FPS recordings;
- side and approved oblique views;
- slower controlled punches;
- faster punches;
- short endpoint holds;
- noisy landmark periods;
- temporary extremity-confidence loss;
- different body sizes;
- at least one validated lower-limb technique;
- roundhouse/front-kick profiles when suitable recordings exist.

For each segment, compare:

- detected QoM peak;
- terminal-transition timestamp;
- stable-window start/end;
- stable representative frame;
- human visual review of the pose/video.

Do not tune parameters to one repetition at the expense of the wider dataset.

---

## 27. Deterministic tests

At minimum provide tests for:

- identical input produces identical event timestamps;
- left/right mirrored cases;
- wrapped ±180° angle changes;
- timestamp jitter / real timestamp deltas;
- 30/60 FPS behavior;
- spatial motion just below/above deadband;
- angular motion just below/above deadband;
- early false pause before substantial progress;
- substantial progress followed by clear stop;
- no stable hold;
- insufficient motion;
- missing weapon landmarks;
- missing body-scale reference;
- QoM peak before terminal transition;
- resumed motion after stable onset;
- correct provenance persistence.

---

## 28. Acceptance criteria

Implementation is acceptable when:

1. `ImpactAnalyzer` receives one pre-segmented known movement and does not perform segmentation/classification.
2. The selected weapon and side are supplied by the caller.
3. Generic math is reused from shared utilities rather than duplicated.
4. Production QoM is consumed as upstream/contextual evidence, not reimplemented locally.
5. Terminal transition and stable terminal window are separate concepts.
6. The terminal event is driven by progress-confirmed **selected-limb motion collapse**.
7. Stable-window boundaries are driven by sustained deadbanded quiet motion, not by the maximum of a confidence score.
8. Actual timestamps are used for all rates and time windows.
9. View/measurability constraints are explicit.
10. Poor evidence produces an explicit abstention.
11. Debug plots come from the same computed evidence used by the analyzer.
12. Analyzer/config/provenance information is persisted sufficiently for deterministic replay.
13. Existing punch replays continue to place the terminal event and stable window where visual review supports them.
14. Calibration values remain clearly labeled provisional until broader validation is complete.

---

## 29. Design summary

The core idea is:

**The segmenter tells us where the movement is.  
The selected weapon tells us how far the strike has travelled.  
The limb angles tell us how far articulation has progressed.  
Linear and angular motion tell us whether the strike is still moving.  
The falling edge tells us where the terminal transition occurs.  
Sustained deadbanded quiet tells us where the stable terminal window is.**

`ImpactAnalyzer` is therefore a constrained, explainable terminal-event detector for known, high-quality, pre-segmented movement evidence — not a universal impact classifier.
