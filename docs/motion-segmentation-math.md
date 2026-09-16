# Activity-aware QoM motion segmentation — math definition

Status: **authoritative design for the replacement motion-segmentation foundation**.

This document defines the mathematical signal used to isolate the activity-relevant movement window in a recording. It intentionally keeps generic segmentation separate from technique interpretation.

## 1. Responsibility and boundary

The segmenter does **not** decide whether a karate technique is correct and does not prove that a punch, kick, block, or other technique occurred.

The activity already provides context: the expected activity type is known, the recording is tagged with that activity, and in counted exercises the cue timestamp is known.

The segmenter answers the narrower question:

> After the known activity/cue, when did the activity-relevant body movement begin, and when did it settle again?

Its output is a **movement / analysis window** for downstream activity-specific analysis.

Keep three different time concepts separate:

1. **Movement / analysis window** — produced by this QoM segmenter. It bounds the period that downstream analysis should inspect.
2. **Technique events** — found later inside the analysis window by an activity-specific analyzer. Examples include theoretical impact, terminal extension, maximum chamber, or another activity-defined event.
3. **Human-viewable clip** — derived for playback/export and may add deliberate pre-roll and post-roll. It does not need to equal the mathematical analysis window.

For a counted straight punch the conceptual sequence is:

`cue -> reaction -> movement START -> punch movement -> theoretical impact -> recovery -> movement STOP`

The segmenter finds START and STOP. The punch analyzer finds theoretical impact and other punch-specific events inside that bounded window. Playback may begin before START and end after STOP.

Principle:

> **Segment relevant movement first. Interpret karate technique afterward.**

## 2. Coordinate system

Use MediaPipe **3D world landmarks**.

For each frame, compute the hip midpoint:

`H = (leftHip + rightHip) / 2`

Translate every selected world landmark into a body-relative position:

`p_rel = p_world - H`

This removes whole-body translation from the signal while keeping MediaPipe world scale.

### 2.1 Do not torso-normalize in the initial segmenter

The current analyzer also has a second body-relative representation that divides each hip-relative point by the current hip-to-shoulder-centre torso length. That representation is useful elsewhere, but the initial QoM segmenter should **not** use it.

The tested detector therefore uses:

`p_detector = p_world - hipMidpoint`

not:

`p_normalized = (p_world - hipMidpoint) / torsoLength`

This distinction is important because the validated QoM-area gates below are defined in the unscaled world-coordinate formulation.

### 2.2 Coordinate-system validation

The same QoM pipeline was tested with both coordinate systems on three real recordings:

| Recording | Approx. FPS | Hip-relative world q95/q25 | Torso-normalized q95/q25 |
| --- | ---: | ---: | ---: |
| Child recording | 33.3 | 9.6x | 9.4x |
| Adult recording | 58.8 | 15.9x | 15.7x |
| Additional `.mls` recording | 30.3 | 15.4x | 16.3x |

The curve shapes were effectively identical after scale normalization. Torso normalization did not consistently improve baseline cleanliness or movement-to-baseline separation.

The comparison is particularly useful because one recording is from a **12-year-old child** and one is from an **adult**. Both produced clean segmentable signals without torso normalization. This is early evidence that the simpler world-scale signal can work across substantially different body sizes, although broader cross-user validation remains required.

Decision for v1:

- use hip-relative MediaPipe world coordinates;
- do not divide by torso length;
- keep body-size normalization as a future validation question rather than initial detector complexity.

## 3. Landmark selection

Head and face landmarks are excluded from generic karate movement segmentation.

The activity chooses the relevant anatomical blocks. Irrelevant body regions must not contribute merely because MediaPipe provides landmarks for them.

### 3.1 Composite hand

Treat each hand as one effective point rather than allowing MediaPipe's multiple hand-adjacent pose landmarks to give the hand multiple votes.

For each side use:

- wrist
- thumb
- index
- pinky

Let each constituent confidence be `c_i` and position be `p_i`.

Composite hand position:

`hand = sum(c_i * p_i) / sum(c_i)`

If confidence weights are unavailable/degenerate, fall back to the arithmetic mean of available constituents.

Composite hand confidence is the arithmetic mean of the constituent confidences.

### 3.2 Composite foot

For leg/kick activities treat each foot as one effective point using:

- ankle
- heel
- foot index

Use the same confidence-weighted-centre rule as for the hand.

## 4. Causal position filtering

Filtering must be live-compatible. No future frame may be required.

For every effective point, use two sequential trailing filters.

### 4.1 Three-frame trailing coordinate-wise median

At frame `t`, take the current point and up to the previous two point samples and compute the median independently for x, y and z.

Purpose: reject isolated one-frame landmark jumps before differentiation turns them into large velocity spikes.

### 4.2 Three-frame trailing arithmetic mean

Apply a trailing arithmetic mean over the current median-filtered point and up to the previous two median-filtered samples.

Purpose: reduce ordinary frame-to-frame landmark jitter.

The filter order is therefore:

`raw body-relative position -> 3-frame median -> 3-frame mean -> velocity`

No additional EMA, Butterworth, Kalman, Savitzky-Golay, or deadband is required in v1.

The fixed three-frame filter was tested on approximately 30, 33 and 59 FPS landmark recordings. Peak amplitude changes somewhat with frame rate, but the movement boundaries remained stable enough for segmentation.

## 5. Confidence

For a landmark sample use MediaPipe tracking confidence derived from visibility and presence. The tested implementation used:

`c = visibility * presence`

For a velocity sample between frames `t-1` and `t`, use the conservative pair confidence:

`c_velocity = min(c_(t-1), c_t)`

This prevents one well-tracked endpoint from masking a poorly tracked endpoint.

## 6. Point speed

For filtered body-relative position `p_t` and timestamp `T_t`:

`v_t = ||p_t - p_(t-1)|| / (T_t - T_(t-1))`

Use real timestamps rather than assuming a fixed FPS.

The detector uses speed magnitude, not signed direction.

## 7. Activity-aware body blocks

The recording/activity metadata determines which blocks participate.

### 7.1 Punch / upper-body striking

Use three equally weighted blocks:

- **Left arm** = left elbow + composite left hand
- **Right arm** = right elbow + composite right hand
- **Torso** = left shoulder + right shoulder + left hip + right hip

Shoulders belong to the torso block and are not duplicated in the arm blocks.

Legs are excluded from punch segmentation.

### 7.2 Kick / leg activity

Use three equally weighted blocks:

- **Left leg**
- **Right leg**
- **Torso**

The exact leg-point membership should follow the same principle as the punch blocks: a compact set of effective points with feet represented as composites, and equal block weighting so point count does not determine influence.

### 7.3 General rule

An activity may define another relevant block set later. The segmenter should therefore accept the block selection from activity metadata/configuration rather than hard-coding one universal whole-body signal.

## 8. Block Quantity of Motion (QoM)

For one block containing point speeds `v_i` with velocity confidences `c_i`, calculate confidence-weighted arithmetic mean speed:

`QoM_block = sum(c_i * v_i) / sum(c_i)`

Do **not** square velocities and do not use RMS in the replacement detector.

Arithmetic-mean QoM was chosen because it is easier to interpret and less dominated by one very fast or jittery landmark than RMS.

## 9. Equal block aggregation

After each participating anatomical block has produced one QoM value, average blocks equally:

For punch segmentation:

`QoM = (QoM_leftArm + QoM_rightArm + QoM_torso) / 3`

The purpose is anatomical balance. A region must not dominate simply because it contains more MediaPipe points.

## 10. Trailing 150 ms QoM area

Convert instantaneous QoM into short accumulated movement evidence by integrating the QoM curve over the trailing 150 ms.

For each current timestamp `T`:

`A(T) = integral[ T - 0.150 , T ] QoM(t) dt`

Use timestamp-aware numerical integration (trapezoidal integration is sufficient).

The trailing area does three useful things:

- suppresses isolated velocity spikes;
- accumulates persistent genuine motion;
- produces smooth valleys between repetitions without requiring complicated movement semantics.

The 150 ms window was selected experimentally. 100 ms remained more jagged; 200 ms broadened movement regions more. 150 ms was the preferred middle ground in the tested recordings.

## 11. Movement-state hysteresis

Use two gates on the rolling QoM area.

Current tested world-coordinate values:

- **START gate = 0.08**
- **STOP gate = 0.04**

State logic:

- STILL remains STILL while `A < 0.08`.
- STILL -> MOVING when `A >= 0.08`.
- MOVING remains MOVING through the middle band `0.04 < A < 0.08`.
- MOVING -> STILL only when `A <= 0.04`.

This hysteresis prevents threshold chatter near the baseline.

These values are provisional empirical constants. They must remain associated with the exact world-coordinate formulation defined above. Do not silently reuse them after changing coordinate scale, QoM definition, filter order, or integration window.

## 12. No deadband in v1

A block-level deadband was tested and produced extremely flat valleys while retaining the same movement count on the examined recording. However it intentionally discards low-amplitude motion.

That may be unsafe for techniques with smaller excursions, including hooks, close-body punches, controlled movements, or other activity types that do not generate the large peaks seen in straight-punch recordings.

Therefore v1 has **no deadband/noise-floor subtraction**.

If future recordings demonstrate a need for one, re-evaluate it across low-amplitude techniques before enabling it.

## 13. Detector boundary versus playback boundary

The QoM START/STOP timestamps are logical segmentation boundaries. They should be preserved for:

- downstream analysis-window selection;
- movement-finished logic;
- cadence/rearming decisions;
- diagnostics and validation.

The user-facing clip may deliberately be wider:

`viewStart = movementStart - preRoll`

`viewEnd = movementStop + postRoll`

The existing concept of pre/post-roll remains useful because a clip should be human-readable and show context around the technique.

Padding must not change the detector's own state or the activity-specific event timestamps.

## 14. Relationship to the cue

Where a recording/activity provides a cue timestamp, preserve it as independent context.

The cue is not itself the movement start. It establishes when the user was asked to act and allows downstream logic to reason about reaction time and associate the correct movement with the correct requested repetition.

The intended flow is:

`known activity + cue -> QoM movement window -> activity-specific event analysis -> human-viewable clip/result`

## 15. Current real-recording validation

The complete candidate pipeline:

`hip-relative world positions -> composite hands -> 3-frame median -> 3-frame mean -> point speeds -> confidence-weighted block QoM -> equal block mean -> trailing 150 ms area -> 0.08/0.04 hysteresis`

was run on the available real recordings without per-recording retuning.

Observed results:

- **30.3 FPS `.mls` recording:** 10 clean isolated movement intervals.
- **33.3 FPS child landmark recording:** 10 clean isolated movement intervals.
- **58.8 FPS adult landmark recording:** 14 clean upper-body movement intervals; no baseline chatter or unintended merging.

The 59 FPS recording contains more generic upper-body movement intervals than the ten-punch recordings. That is not a segmentation failure: the generic segmenter identifies relevant movement; downstream activity/cue association and technique-specific analysis determine which movement corresponds to the requested repetition and which technique events are present.

A same-recording 59 FPS versus approximately 30 FPS downsample comparison also produced the same number of intervals with boundary differences on the order of only a few frames.

## 16. Replacement guidance for the current detector

This design is intended to become the new movement-evidence foundation, not another layer added on top of the existing complex detector.

Preserve external contracts that remain useful:

- timestamps;
- recording and activity identity;
- cue timing;
- movement-window/segment output;
- downstream analysis integration;
- human-viewable pre/post-roll;
- lifecycle/rearming behavior only where it solves a demonstrated requirement.

Review and remove or bypass old internals whose purpose was compensating for the previous motion evidence, including where applicable:

- whole-body / multi-region weighted RMS motion evidence;
- Top-2 translation evidence;
- angular confirmation used only for generic movement start/stop;
- pose-baseline/reference-pose requirements for segmentation;
- accumulated-displacement checks used only to infer generic motion;
- kinematic-chain confirmation used only to segment motion;
- extra settling/dwell/interruption safeguards that are unnecessary with the new signal.

Do not remove activity-specific analysis simply because similarly named mathematics existed in generic segmentation. For example theoretical-impact detection and punch biomechanics remain downstream punch-analysis responsibilities.

If an old safeguard is retained, document the real recording/failure mode that requires it.

Repository principle:

> Build the smallest recorder/segmenter that works. Add behavior only when a real recording demonstrates why it is needed.

## 17. Implementation acceptance criteria

The replacement is ready for initial integration when:

1. The implementation matches this math definition exactly and uses real timestamps.
2. Activity metadata selects the relevant block set.
3. Head/face and irrelevant blocks cannot influence the selected activity signal.
4. Composite hands/feet do not receive multiple votes from dense MediaPipe landmark representation.
5. Filtering is causal: trailing median then trailing mean.
6. Block QoM uses confidence-weighted arithmetic mean speed, not RMS.
7. Blocks are aggregated with equal weight.
8. The rolling window is timestamp-based 150 ms.
9. Hysteresis has distinct START and STOP gates.
10. Analysis-window boundaries remain separate from technique events and display padding.
11. Replay tests cover the available 30/33/59 FPS real recordings or fixtures derived from them.
12. Any retained legacy safeguard has an explicit documented reason.

## 18. Open validation questions

Do not solve these pre-emptively. Collect recordings first.

- Are the fixed 0.08 / 0.04 world-scale gates stable across a wider range of children and adults?
- Do hooks, short punches, slow controlled techniques, blocks and kicks retain adequate movement-to-baseline separation?
- Does unusual MediaPipe world-scale behaviour at difficult camera distances require normalization or adaptive calibration?
- Does any real noisy-still recording require a deadband or extra persistence?

Until a real recording demonstrates one of these problems, keep the initial detector as defined above.
