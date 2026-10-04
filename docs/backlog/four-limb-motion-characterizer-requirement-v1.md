# Four-Limb Motion Characterizer — Shared Requirement v1

**Status:** Design baseline for implementation
**Scope:** Generic per-segment characterization of left/right arm and left/right leg motion
**Working name:** `FourLimbMotionCharacterizer`

**Delivery status (2026-10-04): IN PROGRESS.** Initial implementation and downstream side resolver are present; acceptance is not complete. See [implementation status and measurement-policy interpretation](../four-limb-motion-implementation-status.md). The supplied requirement below remains the design baseline.

---

## 1. Purpose

`FourLimbMotionCharacterizer` describes **what each of the four limbs did during one already-segmented movement**.

It does not need to know whether the movement is:

- a punch;
- a kick;
- an alternating punch;
- a guard movement;
- hikite;
- a chamber;
- a strike;
- or another karate technique.

Its job is purely descriptive.

For every movement segment it characterizes:

- left arm;
- right arm;
- left leg;
- right leg.

The result tells downstream analyzers which limbs were active, how much they articulated, when they started/stopped moving, whether they predominantly extended or retracted, whether they reversed direction, and which limbs remained stable.

---

## 2. Architectural position

Authoritative dependency direction:

`MP4 + MLS`
→ canonical pose evidence
→ QoM movement segmentation
→ one logical movement segment
→ shared geometry / temporal motion utilities
→ `FourLimbMotionCharacterizer`
→ four `LimbMotionProfile` results
→ activity semantics / side resolution / coordination analysis
→ `ImpactAnalyzer`
→ movement-specific analyzers

The characterizer is a **shared evidence layer**.

It must not duplicate generic angle, timestamp, filtering, deadband or coordinate mathematics already owned by shared utilities.

---

## 3. Core principle

The characterizer must answer:

> What did each limb physically do?

It must not answer:

> What karate technique was this?

Technique semantics belong downstream.

Examples:

The characterizer may report:

- left arm strongly extended;
- right arm strongly retracted;
- both legs stable.

A straight-punch activity may interpret that as:

- left side = punching side;
- right side = hikite.

Another activity may interpret the same evidence differently.

Likewise, the characterizer may report:

- right knee shows a large flexion → extension reversal;
- left knee remains stable.

A kick activity may interpret that as:

- right leg = active/kicking side;
- left leg = support side.

The shared characterizer itself must not make that semantic leap.

---

## 4. Input contract

The component receives:

- one already-segmented logical movement;
- canonical timestamps;
- canonical pose landmarks / MLS track;
- landmark quality/confidence;
- frame geometry where required by shared geometry;
- shared motion/deadband configuration;
- optional stable pre/post evidence when already available.

The segment is expected to contain one meaningful movement episode.

The component does not search the entire recording for repetitions.

---

## 5. The four limb definitions

### 5.1 Left arm

Primary articulation joint:

`LEFT_ELBOW`

Joint angle:

`LEFT_SHOULDER → LEFT_ELBOW → LEFT_WRIST/HAND`

### 5.2 Right arm

Primary articulation joint:

`RIGHT_ELBOW`

Joint angle:

`RIGHT_SHOULDER → RIGHT_ELBOW → RIGHT_WRIST/HAND`

### 5.3 Left leg

Primary articulation joint:

`LEFT_KNEE`

Joint angle:

`LEFT_HIP → LEFT_KNEE → LEFT_ANKLE/FOOT`

### 5.4 Right leg

Primary articulation joint:

`RIGHT_KNEE`

Joint angle:

`RIGHT_HIP → RIGHT_KNEE → RIGHT_ANKLE/FOOT`

The same processing pipeline must be applied symmetrically to all four limbs.

---

## 6. Why elbow and knee are the primary shared signals

Elbow and knee angles provide a simple generic description of limb articulation.

They avoid requiring the shared layer to know whether a limb is punching, guarding, performing hikite, kicking or supporting.

Typical downstream-visible patterns include:

### Arm extension

Elbow angle increases substantially.

### Arm retraction / folding

Elbow angle decreases substantially.

### Stable guard arm

Elbow angle remains within the configured angular noise/stability region.

### Kicking-leg chamber and extension

Knee angle changes strongly in one direction during chamber and then reverses during extension.

### Support leg

Knee angle remains comparatively stable or shows only modest articulation.

These are descriptive motion patterns, not technique classifications.

---

## 7. Shared angular preprocessing

Raw joint-angle samples must use authoritative shared geometry utilities.

The characterizer must not implement independent angle formulas.

Temporal angle changes must:

- use actual timestamps;
- use wrapped-angle-safe operations where applicable;
- pass through the shared angular noise/deadband policy;
- preserve quality/provenance.

Initial experimental angular motion deadband:

`~3.6° per sample`

This remains provisional and is a measurement-noise floor, not an anatomical rule.

The configuration must be versioned.

---

## 8. Per-limb motion profile

The characterizer produces one `LimbMotionProfile` for each limb.

Each profile should contain at minimum:

### Identity

- `limbId`
  - `LEFT_ARM`
  - `RIGHT_ARM`
  - `LEFT_LEG`
  - `RIGHT_LEG`
- primary joint ID;
- source landmark identities;
- landmark-track identity.

### Angle state

- start/reference joint angle;
- terminal/end joint angle;
- minimum angle;
- maximum angle;
- net angle change;
- maximum excursion from start.

### Meaningful angular motion

- total meaningful angular travel;
- total positive angular travel;
- total negative angular travel;
- moving duration;
- stable duration.

### Timing

- first meaningful motion timestamp;
- last meaningful motion timestamp;
- peak angular-speed timestamp;
- maximum excursion timestamp;
- reversal timestamps where relevant.

### Direction / shape

- predominant direction;
- reversal count;
- largest reversal;
- motion-shape classification limited to descriptive categories.

### Quality

- valid sample coverage;
- required-landmark quality;
- gaps / interpolation state;
- profile confidence / quality flags;
- abstention reason when not measurable.

---

## 9. Meaningful angular travel

Net angle change alone is insufficient.

Example:

A kicking knee may:

`start`
→ flex strongly into chamber
→ extend strongly
→ finish near the starting angle.

Its net change can therefore be small even though it was clearly the most active limb.

The characterizer must calculate:

`meaningfulAngularTravel = Σ |meaningful Δangle|`

after the configured angular noise floor.

This captures the total articulation performed by the limb.

Also preserve directional components:

`positiveAngularTravel`

`negativeAngularTravel`

This makes extension/retraction and chamber/extension patterns inspectable.

---

## 10. Net change

For each limb:

`netAngleChange = terminalAngle - startAngle`

For elbow and knee interior angles:

- positive net change generally means the joint finished more open/extended;
- negative net change generally means the joint finished more closed/flexed.

The shared layer should expose the numeric sign and value.

It may provide a descriptive direction such as:

- `PREDOMINANTLY_OPENING`
- `PREDOMINANTLY_CLOSING`
- `MIXED`
- `STABLE`

It must not label those states as:

- punch;
- hikite;
- kick;
- chamber;
- guard.

Those meanings belong downstream.

---

## 11. Maximum excursion

Calculate:

`maxExcursionFromStart = max(|angle(t) - startAngle|)`

and preserve:

- excursion magnitude;
- excursion direction;
- timestamp.

This is particularly important for motions that return toward the starting angle before the segment ends.

---

## 12. Reversal detection

A reversal occurs when meaningful angular motion changes direction after exceeding the configured noise floor.

The characterizer should expose:

- reversal count;
- reversal timestamps;
- angle at reversal;
- pre-reversal direction;
- post-reversal direction.

The first implementation should avoid detecting tiny jitter reversals.

A reversal requires meaningful motion on both sides of the direction change.

This allows downstream activity logic to recognize shapes such as:

`flexion → extension`

without embedding the word “kick” into the shared layer.

---

## 13. Motion onset

For each limb, identify the earliest time when joint-angle movement becomes meaningfully active.

Conceptually:

`limbMotionStart`

is the first timestamp at which deadbanded angular motion becomes active and remains sufficiently supported by nearby samples.

Use time-based confirmation rather than a fixed number of frames where possible.

This timestamp allows downstream comparison of:

- punch arm start vs hikite start;
- active leg vs other limb starts;
- multi-limb coordination.

---

## 14. Motion end / settling

For each limb, identify when meaningful joint-angle motion ceases.

Conceptually:

`limbMotionEnd`

is the beginning of a sustained quiet period after the limb's meaningful movement.

Use the shared angular deadband and a configurable time-based quiet confirmation.

This timestamp is descriptive.

It is not automatically the strike impact timestamp.

`ImpactAnalyzer` remains responsible for terminal-event semantics using the selected weapon plus limb evidence.

---

## 15. Peak angular speed

For each limb:

`angularSpeed = |meaningful Δangle| / Δt`

using actual timestamps.

Return:

- peak angular speed;
- timestamp of peak angular speed.

This supports downstream coordination measurements such as:

- active arm vs retracting arm peak timing;
- punch side vs hikite side onset/settling;
- kick chamber/extension timing.

---

## 16. Descriptive limb-state categories

The characterizer may provide a small set of **motion-descriptive** categories derived from the numeric evidence.

Suggested categories:

- `STABLE`
- `MOSTLY_OPENING`
- `MOSTLY_CLOSING`
- `OPEN_CLOSE_REVERSAL`
- `CLOSE_OPEN_REVERSAL`
- `MULTI_DIRECTIONAL`
- `INSUFFICIENT_EVIDENCE`

These categories describe angle behavior only.

They must not encode karate semantics.

For example:

`CLOSE_OPEN_REVERSAL`

may later be interpreted by a kick profile as chamber → extension.

The shared layer itself does not call it a kick.

---

## 17. Activity ranking across the four limbs

After calculating all four profiles, derive relative limb activity.

Suggested metric basis:

- meaningful angular travel;
- moving duration;
- maximum excursion;
- optionally peak angular speed as supporting evidence.

The primary ranking should not rely on peak speed alone.

Output may include:

- `primaryActiveLimb`;
- `secondaryActiveLimb`;
- stable limbs;
- normalized activity share per limb.

Example:

`LEFT_ARM: 0.48`
`RIGHT_ARM: 0.43`
`LEFT_LEG: 0.04`
`RIGHT_LEG: 0.05`

This means the two arms dominated the articulation in the segment.

It does **not** mean the characterizer has classified the movement as a punch.

---

## 18. Active-limb ambiguity

The characterizer must not force a single winner.

Valid outputs may include:

### One dominant limb

Example:

- right leg strongly active;
- other three comparatively stable.

### Two strongly active limbs

Example:

- left arm extending;
- right arm retracting.

### Several active limbs

Example:

- significant torso-driven or coordinated movement causing several limb joints to articulate.

### No clear active limb

Return ambiguity / insufficient differentiation.

Downstream logic decides whether the observed pattern is valid for the known activity.

---

## 19. Starting-side resolution

The shared characterizer does not own the semantic concept of “starting punch side.”

However, its output should make start-side resolution trivial for activity-specific logic.

Example straight-punch evidence:

`LEFT_ARM`
- meaningful travel: high
- net change: strongly positive
- state: `MOSTLY_OPENING`

`RIGHT_ARM`
- meaningful travel: high
- net change: strongly negative
- state: `MOSTLY_CLOSING`

Downstream alternating-punch logic may resolve:

`startingSide = LEFT`

Example kick evidence:

`RIGHT_LEG`
- meaningful travel: high
- strong close→open reversal

`LEFT_LEG`
- stable

Downstream kick logic may resolve:

`startingSide = RIGHT`

No separate punch-specific angle mathematics should be needed.

---

## 20. Guard, hikite and support patterns

The same shared evidence should support multiple non-active-side patterns.

### Punch + hikite

One arm predominantly opens.

The other arm predominantly closes.

### Punch + stable guard

One arm performs large meaningful articulation.

The other arm remains near its start angle with mostly deadband-level jitter.

### Kick + support leg

One knee shows a large excursion/reversal.

The other knee remains comparatively stable.

These are downstream interpretations of generic limb profiles.

---

## 21. Bilateral coordination evidence

The characterizer should expose enough timing information to compare corresponding limbs.

For arms:

- left/right motion onset difference;
- left/right peak angular-speed difference;
- left/right motion-end difference.

For legs:

- equivalent left/right timing differences.

A separate downstream coordination analyzer may calculate:

`startOffset`

`peakTimingOffset`

`endOffset`

The four-limb characterizer should provide the raw timestamps required for this analysis rather than embed karate quality judgments.

---

## 22. Relationship to weapon motion

`FourLimbMotionCharacterizer` describes **joint articulation**.

It does not identify the physical striking/contact weapon.

Joint articulation alone cannot always distinguish:

- fist strike vs elbow strike;
- foot kick vs knee strike;
- non-striking limb movement.

Weapon/contact-point motion is a separate evidence layer.

Possible candidate weapon points include:

- left/right hand;
- left/right elbow;
- left/right knee;
- left/right foot.

Downstream activity semantics or a separate `WeaponMotionProfile` selects/interprets the appropriate contact point.

---

## 23. Relationship to ImpactAnalyzer

`ImpactAnalyzer` should consume:

- the selected weapon trajectory;
- the relevant `LimbMotionProfile`;
- upstream QoM evidence;
- stable/quality evidence.

`FourLimbMotionCharacterizer` may provide:

- active-limb timing;
- articulation progress;
- angular speed;
- settling evidence.

It must not itself choose impact.

The responsibilities remain:

**Four-Limb Characterizer**
→ what each limb articulated.

**Weapon Motion**
→ what candidate contact points travelled.

**ImpactAnalyzer**
→ when the selected strike reached its terminal event and stable terminal state.

---

## 24. Relationship to QoM segmenter

The QoM segmenter remains authoritative for physical movement segmentation.

The four-limb characterizer runs on the resulting logical segment.

It does not replace QoM segmentation.

The QoM segmenter asks:

> Is there meaningful activity-relevant body movement?

The four-limb characterizer asks:

> Which joints articulated during that movement, how much, in which direction, and when?

These are complementary layers.

---

## 25. Stable pre/post evidence

When stable evidence is available around the logical movement, use it to produce robust start and terminal angle references.

Preferred reference states:

- stable pre-movement angle;
- stable post-movement angle.

Do not use a single noisy boundary frame when a validated stable window exists.

Future segment refinement may provide stable evidence bounds rather than relying solely on fixed pre/post playback buffers.

---

## 26. Quality and abstention

A limb profile must be able to abstain independently.

Candidate reasons:

- `JOINT_GEOMETRY_UNAVAILABLE`
- `TRACKING_QUALITY_INSUFFICIENT`
- `INSUFFICIENT_VALID_SAMPLES`
- `INVALID_TIMESTAMPS`
- `NO_STABLE_REFERENCE`
- `MOTION_TOO_SMALL_TO_CHARACTERIZE`

One bad limb must not automatically invalidate all other limb profiles.

Example:

Right wrist occlusion may make the right-arm elbow profile unavailable while both leg profiles remain valid.

---

## 27. Suggested result model

Conceptually:

`FourLimbMotionResult`

containing:

- movement identity;
- analyzer/version information;
- shared configuration provenance;
- `leftArm: LimbMotionProfile`;
- `rightArm: LimbMotionProfile`;
- `leftLeg: LimbMotionProfile`;
- `rightLeg: LimbMotionProfile`;
- activity ranking;
- primary/secondary active-limb candidates;
- cross-limb timing evidence;
- overall quality flags.

Conceptual `LimbMotionProfile`:

- `limbId`
- `jointId`
- `startTimestampUs`
- `endTimestampUs`
- `referenceStartAngleDeg`
- `referenceEndAngleDeg`
- `netAngleChangeDeg`
- `meaningfulAngularTravelDeg`
- `positiveAngularTravelDeg`
- `negativeAngularTravelDeg`
- `maxExcursionDeg`
- `maxExcursionTimestampUs`
- `motionStartTimestampUs`
- `motionEndTimestampUs`
- `peakAngularSpeedDegPerSec`
- `peakAngularSpeedTimestampUs`
- `reversals`
- `movingDurationMs`
- `stableDurationMs`
- `motionPattern`
- `validSampleCoverage`
- `quality`
- `abstentionReason`

Exact Kotlin naming may differ, but the semantics should remain.

---

## 28. Debug visualization

Development tooling should be able to plot all four primary joint-angle traces on one synchronized movement timeline:

- left elbow;
- right elbow;
- left knee;
- right knee.

Additional optional plots:

- angle delta from stable start;
- deadbanded angular speed;
- accumulated meaningful angular travel;
- detected reversals;
- motion onset;
- peak angular speed;
- motion end;
- QoM movement boundaries;
- ImpactAnalyzer event when available.

Debug plots must use the same persisted/computed evidence as production.

Do not independently recalculate angles inside the UI.

---

## 29. Initial validation cases

Validate at minimum:

### Alternating straight punches

Expected descriptive pattern:

- one arm opening strongly;
- opposite arm either closing strongly or remaining stable;
- legs relatively stable.

### Straight punch with stable guard

Expected:

- one arm large articulation;
- opposite arm low meaningful angular travel.

### Chamber-extension kick

Expected:

- one knee large meaningful angular travel;
- one meaningful direction reversal;
- opposite knee comparatively stable.

### Left/right mirrored movements

Equivalent results should mirror cleanly.

### Tracking jitter

Stable limbs should remain stable after the angular noise floor.

### 30 FPS / 60 FPS

Timing quantities should remain time-based and reasonably consistent.

---

## 30. Non-goals

The v1 component does not:

- classify karate technique;
- decide punch vs kick;
- select the striking weapon;
- calculate contact/impact;
- score technique quality;
- decide whether a guard/hikite/support behavior is correct;
- replace movement segmentation;
- infer activity labels;
- infer target height;
- make coaching judgments.

Its output is **descriptive movement evidence** only.

---

## 31. Acceptance criteria

Implementation is acceptable when:

1. All four limbs are processed through the same generic pipeline.
2. Arms use elbow articulation and legs use knee articulation.
3. Generic angle math comes from shared geometry utilities.
4. Actual timestamps are used for angular speeds and temporal events.
5. Noise-floor handling is shared and versioned.
6. Total meaningful angular travel is available independently of net angle change.
7. Directional angular travel is preserved.
8. Reversals are detected only from meaningful motion.
9. Per-limb onset, peak and settling timestamps are exposed.
10. A limb may be classified descriptively as stable/opening/closing/reversing without technique semantics.
11. The four limbs can be ranked by articulation activity without forcing one winner.
12. Individual limb profiles can abstain independently.
13. Activity-specific interpretation remains downstream.
14. Debug plots come from the exact same evidence used by production.
15. Mirrored left/right test cases behave symmetrically.
16. Existing straight-punch recordings clearly expose the observed extension/retraction patterns without punch-specific code.

---

## 32. Design summary

The shared layer should not ask:

> Was this a punch or a kick?

It should ask:

> What did the left arm, right arm, left leg and right leg actually do?

For every segmented movement it produces four comparable descriptions:

**angle**
→ **meaningful travel**
→ **direction**
→ **excursion**
→ **reversal**
→ **onset**
→ **peak speed**
→ **settling**

Known activity semantics then decide what those observations mean.

This keeps movement evidence reusable, explainable and independent of karate-specific classification.
