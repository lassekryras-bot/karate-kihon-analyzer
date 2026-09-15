# Activity-Aware QoM Motion Detector — Replacement Implementation Guide

## Status

Implementation target. This guide defines the replacement for the current production movement detector.

The goal is to remove the current multi-channel Top-2 translation/angular decision path from production and replace it with a smaller activity-aware Quantity of Motion (QoM) detector that has already shown clean separation on recorded punch data.

This is not intended to become a second permanent detector running beside the existing one. During implementation it may be temporarily compared against the current detector for validation, but the intended end state is one production detector based on the design below.

## Design principle

Keep the smallest detector that works.

The detector should answer only:

> Is relevant karate movement currently ongoing?

It should not try to classify the technique, assess quality, infer impact, compare poses, or decide whether a detected movement is a valid punch or kick. Those remain downstream concerns.

The detector should use the activity metadata already attached to the recording to decide which body regions matter. A punch detector does not need leg motion. A kick detector does not need arm motion. Head/face landmarks are excluded from movement detection.

## High-level pipeline

For every pose frame:

1. Build activity-relevant effective body points.
2. Convert them to hip-centred body-relative world coordinates.
3. Apply a causal 3-frame coordinate-wise median filter to suppress one-frame landmark jumps.
4. Apply a causal 3-frame moving mean to the filtered positions.
5. Calculate per-point linear speed from the smoothed positions using actual timestamps.
6. Aggregate point speeds into activity-relevant body-block QoM values.
7. Give each selected body block equal weight.
8. Integrate the combined QoM over a trailing 150 ms window.
9. Apply two-gate hysteresis:
   - high gate starts movement;
   - low gate ends movement.
10. Emit detector boundaries immediately. Human-viewable video padding is applied separately.

Pipeline shorthand:

`activity tag -> effective points -> 3-frame median -> 3-frame mean -> speed -> block QoM -> equal block mean -> 150 ms rolling area -> START/STOP hysteresis`

## Activity-aware body blocks

### Punch / upper-body strike

Use exactly three equal-weight blocks:

- Left arm
  - left elbow
  - left composite hand
- Right arm
  - right elbow
  - right composite hand
- Torso
  - left shoulder
  - right shoulder
  - left hip
  - right hip

Do not use knees, ankles, feet, or head/face landmarks for punch movement detection.

### Kick / lower-body strike

Use exactly three equal-weight blocks:

- Left leg
  - left knee
  - left composite foot
- Right leg
  - right knee
  - right composite foot
- Torso
  - left shoulder
  - right shoulder
  - left hip
  - right hip

Do not use elbows, hands, or head/face landmarks for kick movement detection.

### Generic / whole-body activity

Only if an activity has no more specific motion profile, use five equal-weight blocks:

- left arm
- right arm
- left leg
- right leg
- torso

This is a fallback, not the preferred configuration for known activities.

The activity definition should expose a motion profile or equivalent tag. Do not infer the profile from activity names or UI text.

## Composite hand and foot points

### Composite hand

For each side, combine:

- wrist
- thumb
- index
- pinky

Use a confidence-weighted position average when confidence is available. If all weights are unusable, fall back to a simple mean only when all required coordinates are still finite; otherwise mark the composite point unavailable.

Composite confidence is the mean confidence of the contributing landmarks.

### Composite foot

For each side, combine:

- ankle
- heel
- foot index / toe

Use the same confidence-weighted position and composite-confidence rules as the hand.

The purpose of the composite points is to stop the hand and foot from getting multiple votes simply because MediaPipe represents those regions with several nearby landmarks.

## Body-relative coordinates

Use MediaPipe world landmarks.

For each frame:

`hipCenter = (leftHip + rightHip) / 2`

Then:

`relativePoint = worldPoint - hipCenter`

Do not include head/face landmarks.

Do not introduce additional body-size normalization in the first implementation. Reproduce the experimentally validated signal first. If child/adult scale differences later prove meaningful, add normalization only after validation demonstrates the need.

## Position filtering

Filtering happens before velocity calculation because differentiation amplifies landmark jitter.

### Stage 1 — causal 3-frame median

For every effective point and each coordinate independently, use the median of:

`[t-2, t-1, t]`

This suppresses isolated one-frame landmark jumps.

### Stage 2 — causal 3-frame mean

On the median-filtered positions, take the moving mean of:

`[t-2, t-1, t]`

This provides the smoother rounded motion envelope observed in the experiments.

Use a shorter available history for the first two frames of a sequence.

The window remains frame-based in v1. Tests on the same recording at native ~59 FPS and downsampled ~30 FPS produced the same movement count with small boundary differences, so do not add FPS-specific smoothing logic unless later recordings show a real problem.

## Point speed

For each effective point:

`speed = distance(smoothedPoint[t], smoothedPoint[t-1]) / deltaTimeSeconds`

Use the actual timestamps. Do not assume a fixed FPS.

For the pair confidence used during speed aggregation, use the minimum confidence across the two frames.

## Block QoM

For each selected block, calculate the confidence-weighted arithmetic mean of its valid point speeds.

Conceptually:

`blockQoM = sum(confidence_i * speed_i) / sum(confidence_i)`

Use an arithmetic mean, not RMS. Do not square the speeds.

This makes the signal represent overall quantity of movement rather than disproportionately rewarding one extremely fast or noisy point.

Each selected body block then gets exactly one vote:

`combinedQoM = mean(valid selected block QoM values)`

For punch:

`combinedQoM = mean(leftArmQoM, rightArmQoM, torsoQoM)`

For kick:

`combinedQoM = mean(leftLegQoM, rightLegQoM, torsoQoM)`

If a complete selected block is unavailable, expose that in diagnostics. Do not fabricate motion from missing data. The first implementation may average the remaining valid selected blocks, but it must report how many selected blocks contributed so missing coverage is visible during validation.

## Rolling QoM area

Integrate the combined QoM over the previous 150 ms using timestamps.

Use a trailing causal window and trapezoidal integration.

Conceptually:

`motionArea(t) = integral(combinedQoM, t-0.150s .. t)`

This is the primary movement signal.

Do not add a deadband in v1. Straight punches had enough peak headroom to tolerate one, but shorter or closer techniques such as hooks may have lower-amplitude movement. Preserve low-amplitude real motion until broader validation proves a deadband is safe.

## Hysteresis state machine

Use only two thresholds.

Initial experimental values:

- START gate: `0.08`
- STOP gate: `0.04`

These values correspond to the current raw world-coordinate QoM-area implementation and must remain configurable.

State transitions:

- STILL -> MOVING when `motionArea >= START_GATE`
- MOVING -> STILL when `motionArea <= STOP_GATE`
- between the gates: retain the current state

Do not add start dwell, quiet dwell, settling-window accumulation, moving-interruption tolerance, pose matching, or terminal-pose comparison in the first replacement version.

The 150 ms rolling area already provides temporal accumulation, and hysteresis already prevents rapid state chatter near the baseline.

When movement ends, immediately re-arm for the next movement.

## Detector boundaries versus viewable clip boundaries

Keep these separate.

### Detector boundary

The START/STOP hysteresis crossings define the logical movement interval. Use these timestamps for:

- movement state
- cadence
- rearming
- counting coordination
- downstream analysis timing

### Viewable clip boundary

Human-facing clips should add padding independently:

- pre-roll: retain current default of approximately 150 ms
- post-roll: retain current default of approximately 200 ms

Conceptually:

`clipStart = detectorStart - preRoll`

`clipEnd = detectorEnd + postRoll`

Padding must never delay detector rearming or alter the logical movement state.

## What this replaces in production

The new detector is intended to replace the current production motion-evidence path, not sit permanently beside it.

Once validation passes, production movement detection should no longer depend on:

- Top-2 translation evidence
- angular evidence as a required movement channel
- separate start and settling kinematic channels
- required-region settling gates
- pose-similarity matching for movement completion
- slow-displacement completion gates
- local-anchor settling logic
- multi-band dwell logic beyond hysteresis
- baseline/reference-pose logic used only for movement segmentation

Historical research implementations may remain archived for reproducibility, but they should not remain active production decision paths.

The replacement should preserve stable downstream contracts where practical:

- movement start timestamp
- movement end timestamp
- movement number
- completion event
- pre/post-roll clip handling
- continuous-session rearming
- diagnostic trace output

## Suggested production structure

Names are illustrative; fit them to the current module conventions.

### `ActivityMotionProfile`

Defines the selected detector blocks for the activity.

Suggested values:

- `PUNCH`
- `KICK`
- `GENERAL`

The profile comes from activity metadata attached to the recording/session.

### `QomMotionExtractor`

Responsibilities:

- build body-relative effective points
- build composite hands/feet
- maintain 3-frame median history
- maintain 3-frame mean history
- calculate point speeds
- calculate block QoM
- calculate equal-weight combined QoM
- maintain trailing 150 ms QoM integral
- expose diagnostics

This class should not own movement state.

### `QomMovementSegmenter`

Responsibilities:

- maintain `STILL` / `MOVING`
- apply START/STOP hysteresis
- emit movement start/end boundaries
- immediately re-arm after STOP

This class should not know pose geometry.

### `ContinuousMotionController`

Responsibilities:

- read activity metadata
- choose `ActivityMotionProfile`
- feed frames through `QomMotionExtractor`
- feed `motionArea` into `QomMovementSegmenter`
- preserve existing completed-segment and clip-padding integration

## Diagnostics to keep

For each frame, make it possible to inspect:

- activity motion profile
- left/right arm QoM
- left/right leg QoM when relevant
- torso QoM
- number of selected blocks contributing
- combined QoM
- 150 ms rolling area
- START gate
- STOP gate
- current detector state
- start/end transition timestamp

These traces are important for replay validation and should be simple to plot.

## Validation evidence already observed

The punch experiments that led to this design showed:

- very clean movement hills using left arm + right arm + torso only;
- 150 ms rolling area gave a good balance between responsiveness and smoothness;
- two-gate hysteresis produced clean MOVING/STILL states without rapid chatter;
- a 3-frame median before the 3-frame mean provided spike resistance while preserving movement timing;
- adding a deadband flattened the baseline further but shifted boundaries and could risk suppressing shorter/smaller techniques, so it is intentionally excluded from v1;
- on the same punch recording at native ~58.8 FPS and downsampled ~30.3 FPS, the detector produced the same 14 movement intervals;
- median absolute boundary differences were approximately 33 ms at start and 16 ms at end before adding the median spike filter;
- the median+mean spike-resistant version preserved the same movement count and shifted typical boundaries only by about one frame.

These are promising validation results, not universal calibration proof.

## Required validation before removing the old detector

Run the replacement detector against existing replay fixtures and several real recordings covering at least:

- straight punches
- hooks / short close-range punches
- alternating punches
- intentionally slow punches
- kicks
- short kicks / chambers
- combinations with little stillness
- side-on occlusion
- noisy stillness / posture adjustments
- 30 FPS
- 60 FPS
- children and adults where available

For every recording compare:

- movement count
- start boundary
- end boundary
- movement duration
- false starts during stillness
- missed low-amplitude movements
- rapid back-and-forth state chatter
- behaviour when one selected block has poor confidence

Do not tune specifically to one punch recording.

## Replacement sequence

1. Add activity motion-profile metadata mapping.
2. Implement composite hands and feet.
3. Implement the causal median + mean position filter.
4. Implement block QoM and equal block aggregation.
5. Implement the 150 ms rolling area.
6. Implement the minimal two-gate segmenter.
7. Add replay/debug traces.
8. Run the new detector side-by-side with the current detector only during validation.
9. Validate punch and kick recordings at 30/60 FPS and low-amplitude techniques.
10. Switch production `ContinuousMotionController` to the QoM detector.
11. Remove the old Top-2/angular production decision path and obsolete configuration from production code.
12. Keep historical research code only in archive/test scope if still useful for reproducibility.

## Non-goals for v1

Do not add these unless a real validation recording demonstrates a need:

- deadband / noise-floor subtraction
- adaptive thresholds
- Kalman filtering
- Savitzky-Golay filtering
- angular QoM fusion
- peak detection
- peak prominence
- technique classification
- impact detection
- pose matching
- long quiet dwell
- FPS-specific smoothing profiles

The first production replacement should remain deliberately small and explainable.

## Initial acceptance criteria

The replacement is ready to become the production detector when:

- punch activities use only left arm + right arm + torso;
- kick activities use only left leg + right leg + torso;
- head/face never contributes to motion detection;
- composite hand and foot points are used;
- filtering is causal 3-frame median followed by causal 3-frame mean;
- the primary signal is equal-weight block QoM integrated over 150 ms;
- movement state uses only configurable START/STOP hysteresis gates;
- detector timing and clip padding are separate;
- 30 and 60 FPS replays produce materially equivalent segmentation on the same movement sequence;
- straight and short-range techniques are both detected;
- no production dependency remains on the retired Top-2/angular movement decision path;
- replay diagnostics make every transition explainable.

## Current starting parameters

Use these only as the initial implementation baseline:

- position spike filter: trailing 3-frame median
- position smoother: trailing 3-frame mean
- rolling QoM area: 150 ms
- START gate: 0.08
- STOP gate: 0.04
- clip pre-roll: 150 ms
- clip post-roll: 200 ms

Treat the gates as configuration, not universal constants. Validate before broad rollout.
