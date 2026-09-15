# Activity-Aware QoM Motion Detector — Replacement Implementation Guide

Status: **IMPLEMENTATION PLAN / MIGRATION CONTRACT**

Read together with [`motion-detector-math.md`](motion-detector-math.md). The math document is authoritative for the signal. This document defines how to replace the current production segmentation decision paths without preserving unnecessary historical complexity.

The intended end state is **one shared Quantity-of-Motion movement definition** used wherever the app needs physical movement boundaries, with activity metadata selecting the relevant body blocks.

---

## 1. Goal

Replace the current movement-decision core with a small activity-aware QoM detector that can be reused for both live/causal state and completed-recording segmentation.

For punch activities:

`LEFT_ARM + RIGHT_ARM + TORSO`

For kick / leg activities:

`LEFT_LEG + RIGHT_LEG + TORSO`

Both profiles use the same mathematical pipeline:

`composite points → hip-relative world coordinates → median3 → mean3 → velocities → per-block QoM → equal block mean → 150 ms area → START/STOP hysteresis`

The activity identity already attached to the recording/process plan selects the profile. Do not infer punch versus kick from which limbs happen to move.

---

## 2. Architectural principle

Do **not** keep multiple competing production definitions of “movement.”

A temporary comparison path is acceptable during migration, but after replay validation the QoM signal should become the production movement evidence for both:

- causal/live movement state where needed; and
- retrospective movement segmentation from the persisted MLS landmark timeline.

Detailed kinematics may continue to exist for analysis, coaching, diagnostics and wiki measurements. They should not remain a parallel mandatory segmentation gate merely because the code already exists.

The old Task 5 research stays archived for reproducibility.

---

## 3. Current code that this supersedes

### Causal path

Current production classes include:

- `PoseMotionObservationExtractor`
- `KinematicChainExtractor`
- `BaseMovementSegmenter`
- `BaseRecordingProfile`
- `ContinuousMotionController`

`BaseMovementSegmenter` currently consumes separate translation and angular evidence, classifies each as MOVING / QUIET / MID, applies start dwell, maintains a settling history, accumulates quiet dwell, tolerates moving interruptions, and uses scope-specific settling evidence.

The QoM detector replaces that decision core.

### Retrospective path

Current retrospective classes include:

- `RetrospectiveKinematicsExtractor`
- `RetrospectiveSessionSegmenter`
- `RetrospectiveSegmenterConfig`

The current retrospective extractor uses centered future-looking windows, Top-2 translation, Top-2 angular rate, upper-arm reference scaling, q20/q90 session normalization and combined normalized evidence.

The current retrospective segmenter then applies normalized hysteresis, quiet dwell, start backtracking, duration rules and cadence-based burst merging.

The new QoM detector should replace the **movement evidence and physical boundary detection** in this path as well. Cadence grouping, cue association, persistence models and retained clip padding may remain as separate downstream concerns where they are still useful.

---

## 4. New core types

Names may follow repository conventions, but keep the responsibilities distinct.

### `MotionBodyProfile`

Represents the activity-selected body blocks.

Initial profiles:

- `PUNCH`
- `KICK`

The profile defines effective points and block membership only. It must not contain technique-analysis rules.

### `QomDetectorConfig`

Initial fields:

- detector version: `activity_qom_hysteresis_v1`
- position median samples: `3`
- position mean samples: `3`
- rolling area window: `150 ms`
- start gate: `0.08 m` initially for the validated punch configuration
- stop gate: `0.04 m` initially for the validated punch configuration
- maximum accepted timestamp gap / reset behavior
- pre-roll: `150 ms`
- post-roll: `200 ms`

Do not add deadband, adaptive thresholds, dwell windows, refractory time or pose matching to this initial config.

Kick may begin with the shared gate values for experimental comparison, but do not declare the kick gates validated until real kick recordings have been replayed. Prefer shared parameters unless data demonstrates a reason to diverge.

### `QomMotionEvidenceExtractor`

Owns only the signal from `PoseFrame` to rolling QoM area.

Responsibilities:

1. select activity-relevant raw world landmarks;
2. create composite hand/foot points;
3. subtract hip midpoint;
4. maintain trailing median/mean histories;
5. calculate velocity from real timestamp delta;
6. calculate confidence-weighted QoM for each block;
7. calculate equal mean across active available blocks;
8. maintain the trailing 150 ms integral;
9. return auditable per-frame evidence.

Suggested output fields:

- timestamp;
- profile;
- per-block QoM values;
- aggregate QoM;
- rolling QoM area;
- available/valid block count;
- selected effective-point confidence diagnostics;
- status/reason when evidence is unavailable.

Do not hide missing data by returning numeric zero.

### `QomMovementSegmenter`

Owns the two-state hysteresis decision.

Logical state can remain compatible with existing `ARMED`, `MOVING`, `COMPLETE` outputs if this makes integration easier, but internally the decision is simply STILL versus MOVING plus completion emission.

The core rule is:

- if STILL and area >= START gate → MOVING;
- if MOVING and area <= STOP gate → complete interval and return to STILL/ARMED;
- between the gates → preserve state.

Do not add extra dwell in the first implementation.

---

## 5. Activity metadata routing

The recording/process plan already carries activity identity. Add the minimum mapping from that identity to `MotionBodyProfile`.

Examples:

- straight punch / punching activities → `PUNCH`
- front kick / knee kick / kicking activities → `KICK`

Do not make the segmentation layer depend on UI labels or translated activity names. Resolve a stable activity capability/tag in the domain/configuration layer.

A useful long-term activity capability is conceptually:

`movementProfile = PUNCH | KICK | ...`

The same profile must be persisted or reproducible in the processing-plan snapshot so later reprocessing knows which body blocks were requested.

Unknown activities must not be silently guessed from motion. Either map them explicitly or report that no supported movement profile is configured.

---

## 6. Landmark selection

### Punch profile effective points

- left shoulder
- right shoulder
- left elbow
- right elbow
- left composite hand
- right composite hand
- left hip
- right hip

### Kick profile effective points

- left shoulder
- right shoulder
- left hip
- right hip
- left knee
- right knee
- left composite foot
- right composite foot

### Composite hand

Confidence-weighted center of:

- wrist
- thumb
- index
- pinky

### Composite foot

Confidence-weighted center of:

- ankle
- heel
- foot index

Head/face landmarks are never part of the segmentation point set.

Do not include punch-irrelevant legs in the punch profile or kick-irrelevant arms in the kick profile.

---

## 7. Coordinate contract

Use MediaPipe world landmark coordinates and subtract the current frame's hip midpoint.

The initial gate values were measured on this signal scale.

Do not feed the existing torso-normalized `RelativePose` coordinates into the new detector while keeping 0.08/0.04 gates. Either use the raw world-coordinate contract defined by the math file or deliberately recalibrate/version a normalized variant later.

Keep display coordinates completely separate from detector coordinates.

---

## 8. Filtering implementation

Filtering must be causal and bounded.

For each effective point maintain enough history for:

1. trailing coordinate-wise median of up to 3 raw positions;
2. trailing mean of up to 3 median-filtered positions.

This can be implemented with tiny fixed-size queues/ring buffers. It does not require a general DSP library.

The implementation should be deterministic and allocation-light, but clarity is more important than micro-optimization because the point count is tiny compared with MediaPipe inference cost.

On timestamp gaps beyond the configured safe limit, clear derivative/filter history rather than calculate a huge or stale movement transition.

---

## 9. Confidence and missing data

Keep confidence weighting simple.

Point confidence:

`visibility × presence`

Velocity confidence between adjacent filtered samples should conservatively use the lower confidence of the two endpoints.

Within a block, compute the confidence-weighted mean of valid point speeds.

If a point is absent/unusable, omit it from that frame's weighted block mean.

If a complete block is unavailable, mark it unavailable rather than zero.

For the aggregate, average the available active blocks equally and expose how many were available.

Do not reintroduce the former behavior where one occluded required region prevents otherwise valid physical movement from ending.

Before production acceptance, decide from replay evidence whether a minimum of one, two, or all three valid blocks is required for a trustworthy decision. Keep this as a small explicit validity rule, not a complex region state machine.

---

## 10. Rolling area

Maintain a trailing timestamped history of aggregate QoM covering at least 150 ms plus one preceding interpolation/sample point.

At each frame integrate the portion in `[now - 150 ms, now]` with trapezoidal integration.

Do not approximate the rolling window as a fixed number of frames.

The short position filters remain 3-sample filters; the movement evidence window is time-based.

---

## 11. Segment state and logical boundaries

The first frame/sample where rolling area crosses the high gate is the logical start decision boundary.

The first frame/sample after MOVING where rolling area reaches or falls below the low gate is the logical end boundary.

No backtracking to a quiet threshold is required in v1.

No 50 ms start dwell is required in v1.

No 80/100 ms quiet dwell is required in v1.

No 120 ms settling window is required in v1.

No moving-interruption budget is required in v1.

The rolling integral plus hysteresis already provides temporal stabilization. Add an extra persistence rule only after a real recording demonstrates chatter or premature completion that the core signal does not solve.

Immediately rearm after logical end. Clip post-roll must not block the next physical movement detection.

---

## 12. Logical interval versus retained playback interval

Preserve the repository's existing separation between logical and retained intervals.

Logical boundaries are the detector truth used by downstream movement analysis.

Retained/playback boundaries add viewing context:

- `retainedStart = logicalStart - 150 ms`, clamped to recording start;
- `retainedEnd = logicalEnd + 200 ms`, clamped to recording end.

Retained intervals may overlap. That is fine.

Do not store padded boundaries as if they were physical movement start/end.

---

## 13. Retrospective segmentation migration

The persisted MLS landmark timeline remains authoritative. Do not rerun MediaPipe when a compatible landmark track already exists.

For retrospective processing:

1. read the complete MLS timeline;
2. resolve `MotionBodyProfile` from the recording's activity/processing plan;
3. replay frames in timestamp order through the same QoM evidence extractor;
4. replay evidence through the same hysteresis segmenter;
5. produce logical intervals;
6. optionally perform downstream cadence grouping if the activity explicitly wants combinations;
7. associate cues after physical boundaries are known;
8. create retained intervals with pre/post roll;
9. persist `SessionMovement` references into the master landmark/video timeline.

The retrospective path should not require centered future-looking regression to define physical movement. Using the same causal mathematical definition offline makes live and retrospective traces directly comparable.

If retrospective-only refinements are later proven useful, they must be explicit refinements of a stored base detector result rather than an unrelated second definition of motion.

---

## 14. What to keep from current code

Keep/reuse where it remains useful:

- `PoseFrame` and MLS parsing;
- strict timestamp validation;
- processing queue and processing-plan ownership;
- `SessionMovement` persistence;
- master timeline interval references;
- activity identity/configuration;
- cue timeline association after detection;
- logical vs retained interval distinction;
- pre-roll / post-roll behavior;
- transition/trace diagnostics where useful;
- replay fixtures and validation tooling;
- detailed kinematic extractors used by later technique analysis;
- archived Task 5 research and synthetic cases as historical/adversarial reference material.

Do not delete analysis measurements simply because they are no longer detector gates.

---

## 15. What to retire from the production movement-decision core

After QoM replay acceptance, remove or archive production dependence on:

- Top-2 translation evidence as start/stop truth;
- Top-2 angular evidence as start/stop truth;
- translation/angular OR gating;
- `translationMovingThreshold` / `translationQuietThreshold` as primary segment gates;
- `angularMovingThreshold` / `angularQuietThreshold` as primary segment gates;
- start dwell used only to stabilize noisy motion evidence;
- quiet dwell used only to stabilize noisy motion evidence;
- long settling history used only for movement completion;
- moving-interruption accounting;
- `SettlingEvidenceScope` as the way activity relevance is expressed;
- pose-similarity requirements for physical movement end;
- accumulated slow-displacement requirements for ordinary punch/kick end;
- required-region quiet gating that can deadlock on occlusion;
- retrospective q20/q90 normalization as the primary movement definition;
- centered Top-2 retrospective derivative as the primary movement definition.

Do not maintain these as silent fallback production gates after the migration is accepted. If retained for diagnostics, name and separate them clearly.

---

## 16. Cadence and combinations

Movement detection and movement grouping are separate.

QoM hysteresis answers: “when was there a physical movement burst?”

Cadence/grouping may later answer: “should several bursts be presented as one combination?”

Do not distort the QoM gates or extend the rolling window merely to merge combinations.

If the existing retrospective cadence grouping remains useful, apply it downstream to already detected bursts and keep both the raw burst boundaries and grouped interval provenance available.

For repeated punch/kick training, prefer preserving individual physical movement bursts unless the activity explicitly defines combination grouping.

---

## 17. Cue relationship

Cue timestamps do not define movement boundaries.

Use cues for:

- associating a detected movement with a spoken count;
- measuring reaction timing;
- cadence control;
- selecting the relevant exercise period if needed.

Do not force expected repetition count, move boundaries to cue times, or fabricate a segment when no movement crosses the detector gates.

Extra detected movement outside the intended exercise/cue period should be handled by session context/windowing, not by weakening the physical motion definition.

---

## 18. Tests — mathematical unit tests

Add deterministic tests for at least:

### Coordinate behavior

- adding the same XYZ translation to every landmark leaves hip-relative QoM unchanged;
- a static pose produces approximately zero signal after startup;
- non-increasing timestamps are rejected/reset;
- timestamp gaps reset derivative history safely.

### Composite points

- composite hand equals confidence-weighted wrist/thumb/index/pinky center;
- composite foot equals confidence-weighted ankle/heel/foot-index center;
- low-confidence constituent landmarks have reduced influence;
- zero-confidence fallback does not crash or generate NaN.

### Filtering

- a single-frame position spike is strongly reduced by trailing median3;
- median3 is causal;
- mean3 is causal;
- startup with one/two samples is defined;
- filtering never reads a future frame.

### QoM

- block QoM is a confidence-weighted arithmetic mean, not RMS;
- each active block gets one equal aggregate vote regardless of point count;
- punch profile ignores leg-only motion;
- kick profile ignores arm-only motion;
- torso motion contributes to both;
- head landmarks have no effect;
- unavailable data does not become zero-motion evidence.

### Rolling area

- integration uses real timestamps;
- constant QoM integrates to the expected distance over 150 ms;
- irregular timestamp spacing produces the correct trapezoidal area;
- 30/60 FPS synthetic sampling of the same physical trajectory produces close state boundaries.

### Hysteresis

- high gate starts movement;
- falling below high but above low preserves MOVING;
- low gate ends movement;
- a signal oscillating between gates does not chatter;
- immediate rearm can detect a later separate movement.

### Playback padding

- pre/post roll does not alter logical detector boundaries;
- padding clamps to recording start/end;
- overlapping retained intervals remain valid.

---

## 19. Tests — real replay validation

Replay the detector on full, untrimmed landmark timelines. Do not validate only hand-picked punch windows.

At minimum cover:

### Punches

- the existing ~59 FPS 10-punch recording;
- the same sequence downsampled to ~30 FPS;
- straight full-extension punches;
- hook/close-body punches;
- deliberately slower controlled punches;
- alternating sides;
- torso-heavy punches;
- noisy still holds;
- small posture adjustments between punches;
- side-on partial occlusion.

### Kicks

- front kick;
- knee lift/knee strike if supported;
- chamber → extension → retract;
- left and right side;
- slower controlled kick;
- standing still with arm motion only, which should not trigger a kick profile by itself;
- noisy lower-body landmark tracking.

### Required comparison outputs

For each replay persist/report:

- rolling QoM area timeline;
- block QoM timelines;
- selected activity profile;
- high/low gates;
- MOVING/STILL state;
- logical starts/ends;
- retained starts/ends;
- confidence/availability diagnostics;
- detector version.

Compare boundaries against human-reviewed movement intervals where available, but keep provisional labels clearly marked as provisional.

---

## 20. Existing evidence to preserve

The experiments leading to this guide found:

- upper-body-only signals were cleaner for punching than whole-body signals;
- composite hands retained clean movement envelopes while reducing redundant landmark voting;
- composite feet provide the analogous lower-body abstraction;
- equal block weighting made the detector's meaning clearer;
- 150 ms rolling QoM area produced clean separated movement hills;
- 0.08 high / 0.04 low hysteresis produced stable state changes on tested punch recordings without extra persistence;
- 30 and 60 FPS versions of the same landmark sequence produced the same interval count and close boundaries;
- adding a causal median3 before mean3 reduced isolated jitter with small timing cost;
- a block deadband flattened baseline dramatically but was rejected from v1 because it could suppress lower-amplitude legitimate techniques such as hooks or close-range punches.

These are implementation-direction results, not universal certification.

---

## 21. Migration sequence

### Step 1 — implement QoM math independently

Add the profile/config/evidence extractor and mathematical unit tests without changing the production segmenter.

### Step 2 — add replay comparison

Run current detector and QoM detector side-by-side in test/diagnostic scope on the same persisted landmark tracks.

Export traces and compare logical boundaries.

### Step 3 — validate activity routing

Verify punch recordings use arms+torso and kick recordings use legs+torso from stable activity metadata.

### Step 4 — switch retrospective production segmentation

Replace `RetrospectiveKinematicsExtractor`/normalized Top-2 evidence as the physical movement boundary source with QoM evidence.

Preserve persistence, cue association and logical/retained interval semantics.

### Step 5 — switch causal/live state where used

Replace translation/angular evidence supplied to `BaseMovementSegmenter`, or simplify/replace `BaseMovementSegmenter` with the QoM hysteresis state machine.

### Step 6 — remove competing production detector logic

Once replay/device validation passes, remove the old Top-2/settling decision configuration from the normal production path. Move any uniquely useful diagnostics to explicitly diagnostic/analysis ownership.

Do not leave a permanent hidden fallback that can produce different boundaries from the QoM detector.

### Step 7 — update documentation/backlog

Update Segmenter Integration documentation to reference the QoM math as the authoritative segmentation model and record physical-device validation status.

---

## 22. Acceptance criteria for replacing the old detector

The QoM implementation can become the sole production movement detector when:

- the math matches `motion-detector-math.md` exactly;
- activity metadata deterministically selects PUNCH or KICK profile;
- punch profile contains no leg/head movement contribution;
- kick profile contains no arm/head movement contribution;
- all filtering is causal in the live path;
- retrospective processing uses the same base evidence definition;
- fixed 30/60 FPS comparison does not create materially different movement segmentation;
- straight punches remain clean;
- hook/close-range and slow punches are not suppressed;
- kick recordings are validated separately;
- noisy stillness does not create unacceptable false MOVING intervals;
- logical intervals remain separate from retained clip padding;
- processing does not fabricate expected repetition count;
- current master video/MLS/persistence ownership remains intact;
- automated tests pass;
- at least one physical-device punch session and one physical-device kick session are visually reviewed.

---

## 23. Implementation restraint

Do not rebuild the old complexity on top of the new clean signal preemptively.

If a validation recording fails, first save the recording, plot block QoM and rolling area, identify the exact failure, and change the smallest part of the model that addresses that failure.

Possible future additions such as adaptive gates, an outlier policy beyond median3, a generic movement profile, angular evidence, or slow-motion handling must be justified by a concrete recording and versioned explicitly.

The default assumption is that the QoM detector remains small.
