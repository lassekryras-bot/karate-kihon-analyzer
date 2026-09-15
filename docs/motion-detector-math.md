# Activity-Aware Quantity-of-Motion Detector — Math Definition

Status: **NORMATIVE DESIGN FOR IMPLEMENTATION**

This document is the source of truth for the next movement-segmentation implementation. It replaces the current detector's Top-2 translation/angular decision model as the preferred production direction once implementation and replay validation are complete.

Historical Task 5 kinematic experiments remain useful research material, but they are not requirements for the new detector. The guiding rule remains: build the smallest detector that works on real recordings, then add safeguards only when real evidence requires them.

---

## 1. Purpose

The detector answers one narrow question:

> Is the activity-relevant body currently moving or still?

It does **not** classify technique quality, identify impact, estimate punch type, score biomechanics, or decide whether a detected movement is a correct karate technique.

The recording already has an activity identity. That activity identity determines which body blocks matter for movement detection.

For punching activity, leg motion is ignored by the detector.

For kicking / leg activity, arm motion is ignored by the detector.

The torso participates in both.

Head and face landmarks are excluded from movement segmentation. They are not required for punching or kicking detection and MediaPipe's dense face representation would otherwise overweight the head.

---

## 2. Core pipeline

The default causal pipeline is:

**activity tag → relevant effective body points → hip-relative coordinates → 3-sample median → 3-sample mean → point velocity → block Quantity of Motion → equal block aggregation → 150 ms rolling area → high/low hysteresis → MOVING / STILL**

Clip/view padding is applied **after** logical movement detection and must never influence detector state.

No deadband is part of the default detector.

No RMS, Top-K, angular-rate gate, pose-similarity gate, slow-displacement gate, or extra quiet dwell is required by the v1 decision core.

---

## 3. Inputs

For frame `i` at timestamp `t_i`, MediaPipe provides world-space landmark position

`p(i,l) = [x, y, z]`

for landmark `l`, with visibility and presence values.

Define landmark confidence as:

`c(i,l) = clamp(visibility, 0, 1) × clamp(presence, 0, 1)`

Use the actual timestamps supplied with each frame. Do not derive velocity from nominal FPS.

If timestamps are non-increasing, reject/reset the sequence rather than computing an invalid derivative.

---

## 4. Body-relative coordinate frame

For every frame, define the hip midpoint:

`H_i = (LEFT_HIP_i + RIGHT_HIP_i) / 2`

Convert each selected world landmark to body-relative coordinates:

`r(i,l) = p(i,l) - H_i`

This removes global camera-frame translation of the body and keeps the detector focused on articulated karate movement.

Important: the validated experimental thresholds below were produced from MediaPipe world coordinates after hip-centering, **without dividing coordinates by torso length or upper-arm length**. Do not silently reuse the current torso-normalized `RelativePose` values with the same numeric gates; that would change the signal scale.

A future whole-body-translation channel may be added if an activity needs it, but it is not part of this detector definition.

---

## 5. Composite extremity points

Hands and feet are represented by one effective point per side so MediaPipe does not give an extremity extra votes simply because several nearby landmarks exist.

### Composite hand

For each side use:

- wrist
- thumb
- index
- pinky

The composite hand center is the confidence-weighted mean:

`HAND_i = Σ(c_j × p_j) / Σ(c_j)`

where `j` is the four hand landmarks for that side.

If every confidence is zero but finite positions are available, fall back to their arithmetic mean for geometry while reporting weak confidence.

Composite hand confidence is the arithmetic mean of the four constituent confidences.

### Composite foot

For each side use:

- ankle
- heel
- foot index / toe

The composite foot center uses the same confidence-weighted mean.

Composite foot confidence is the arithmetic mean of those three constituent confidences.

---

## 6. Activity-specific body blocks

The detector aggregates **blocks**, not every MediaPipe landmark independently.

Each selected block gets one QoM value and each active block gets one equal vote in the final aggregate.

### Punch / upper-body-strike profile

Use three blocks:

**Left arm**

- left elbow
- left composite hand

**Right arm**

- right elbow
- right composite hand

**Torso**

- left shoulder
- right shoulder
- left hip
- right hip

Do not include legs or head.

### Kick / lower-body-strike profile

Use three blocks:

**Left leg**

- left knee
- left composite foot

**Right leg**

- right knee
- right composite foot

**Torso**

- left shoulder
- right shoulder
- left hip
- right hip

Do not include arms or head.

Shoulders and hips deliberately belong to the torso block rather than being duplicated into arm/leg blocks. This prevents torso landmarks from receiving extra weight.

### Unknown activity

Do not silently guess punch versus kick from the observed motion. The recording/activity metadata should resolve the profile before segmentation starts.

If a future generic profile is needed, define and validate it explicitly rather than combining every body part by default.

---

## 7. Causal spike-resistant position filtering

Noise is reduced **before differentiation**, because differentiation amplifies position jitter.

The default filter is two simple causal stages.

### Stage A — trailing 3-sample coordinate-wise median

For effective point `l` at frame `i`:

`m(i,l) = median(r(i-2,l), r(i-1,l), r(i,l))`

Take the median independently for x, y, and z.

At startup, use the available one or two samples until three exist.

Purpose: reject isolated one-frame landmark jumps.

### Stage B — trailing 3-sample arithmetic mean

Then smooth the median-filtered positions:

`s(i,l) = mean(m(i-2,l), m(i-1,l), m(i,l))`

Again use the available startup samples until three exist.

Purpose: reduce ordinary small frame-to-frame landmark jitter while retaining a very short causal history.

This is deliberately frame-count based. Its main job is suppressing landmark jumps, not defining movement duration. Real-time duration enters later through the timestamp-derived velocity and 150 ms rolling integral.

Do not use centered/future-looking samples in the live detector.

---

## 8. Point velocity

For each effective point:

`Δt_i = t_i - t_(i-1)`

`v(i,l) = ||s(i,l) - s(i-1,l)|| / Δt_i`

Units are meters per second when MediaPipe world coordinates are metric.

For confidence weighting between two frames use:

`w(i,l) = min(c_smooth(i-1,l), c_smooth(i,l))`

The confidence stream may use the same short causal median/mean treatment, or an equivalent conservative implementation, provided low-confidence samples cannot gain influence through smoothing.

---

## 9. Block Quantity of Motion

For block `b` containing effective points `L_b`, define:

`QoM_b(i) = Σ[w(i,l) × v(i,l)] / Σ[w(i,l)]`

for `l ∈ L_b`.

This is a confidence-weighted arithmetic mean of point speeds.

It is intentionally **not RMS**. No squaring is used. A single unusually fast or noisy point therefore has less ability to dominate the complete body signal.

If no point in a block has usable finite confidence/velocity, mark that block unavailable instead of inventing zero motion.

Coverage/confidence should remain visible in diagnostics. It must not recreate the old required-region deadlocks where an occluded region prevented an otherwise obvious movement from completing.

---

## 10. Equal block aggregation

For the activity's active block set `B`, define:

`QoM(i) = mean(QoM_b(i))` for available `b ∈ B`

For punch this is conceptually:

`QoM = (LeftArm + RightArm + Torso) / 3`

when all three are available.

For kick:

`QoM = (LeftLeg + RightLeg + Torso) / 3`

when all three are available.

The purpose of block aggregation is structural fairness: a region does not become more important because MediaPipe supplies more landmarks there.

If a block is unavailable, average only available valid blocks and expose that reduced coverage diagnostically. Define a minimum-valid-block policy through validation rather than requiring every block unconditionally. Do not treat an unavailable block as confirmed stillness.

---

## 11. Rolling Quantity of Motion area

The main detector signal is accumulated QoM over the previous **150 ms**:

`A(t_i) = ∫[t_i - 0.150 s, t_i] QoM(t) dt`

Use actual timestamps and trapezoidal integration over the samples in the trailing window.

Because QoM is in m/s, the rolling area is in meters.

The 150 ms area performs two useful jobs:

1. isolated frame noise contributes little total area;
2. real movement produces a broad, clean movement envelope.

The rolling window is time-based and therefore keeps the same semantic duration at 30 and 60 FPS.

### Window experiments

The explored values included 0.10, 0.12, 0.15, 0.18, 0.20, 0.30, 0.40 and 0.50 s.

For the tested punch recordings, 0.15 s provided the best working balance between a smooth movement envelope and preserved start/finish separation.

- 0.10 s retained more internal notches/noise.
- 0.20 s was still clean but broader.
- 0.30–0.50 s increasingly held history after real movement and blurred gaps.

Therefore **150 ms is the v1 default**, not an immutable physical constant.

---

## 12. Hysteresis movement state

Use two gates on the rolling-area signal rather than one threshold.

Initial experimentally useful punch values:

- `START_GATE = 0.08 m`
- `STOP_GATE = 0.04 m`

State transition rule:

**If STILL and `A >= START_GATE`: enter MOVING.**

**If MOVING and `A <= STOP_GATE`: enter STILL and close the logical movement interval.**

**If the signal is between the two gates: keep the current state.**

This hysteresis prevents rapid STILL/MOVING chatter near the baseline.

The first implementation should not add an extra dwell/persistence timer unless replay validation shows a real failure that hysteresis and rolling integration do not already solve.

The numeric gates are provisional experimental defaults. Preserve them exactly for initial replay comparison, then calibrate only from diverse real recordings. In particular, validate hooks, close-range punches, slow controlled strikes, kicks and noisy stillness before deciding that one pair of gates is universal.

Do not add a movement deadband in v1. A deadband produced a visually flat baseline in experiments but could erase legitimate low-amplitude techniques such as hooks or close-body punches.

---

## 13. Logical boundaries versus viewable video

Detector boundaries and playback boundaries are different concepts.

Logical movement:

`logicalStart = high-gate crossing`

`logicalEnd = low-gate crossing`

Viewable/retained interval:

`retainedStart = max(0, logicalStart - preRoll)`

`retainedEnd = min(recordingEnd, logicalEnd + postRoll)`

Current useful defaults remain:

- pre-roll: 150 ms
- post-roll: 200 ms

Padding makes clips comfortable to inspect. It must not delay detector rearming, next-cue timing, or the logical end stored for analysis.

---

## 14. Frame-rate behavior observed so far

A 701-frame punch recording at approximately 58.8 FPS was compared against the **same landmark sequence** downsampled to approximately 30.3 FPS.

With the earlier 3-frame position mean, 150 ms QoM area and the same 0.08/0.04 gates:

- both produced 14 movement intervals over the full recording;
- median absolute start-boundary difference was about 33 ms;
- median absolute end-boundary difference was about 16 ms;
- median absolute duration difference was about 33 ms;
- maximum absolute duration difference was about 67 ms.

With the selected median-then-mean position filter on the same comparison:

- both produced 14 intervals;
- median absolute start difference was about 50 ms;
- median absolute end difference was about 17 ms;
- median absolute duration difference was about 17 ms;
- maximum absolute duration difference was about 67 ms.

This is encouraging evidence that the detector state is much more stable across 30/60 FPS than the raw peak amplitude. It is not proof across all devices and techniques.

---

## 15. Why the selected signal replaces the older detector core

The selected signal is intentionally simpler than the accumulated Task 5 motion stack.

It does not need separate Top-2 translation and angular channels to decide MOVING/STILL.

It does not need whole-body evidence for a punch when the activity metadata already says the recording is a punch activity.

It does not need head landmarks.

It does not need pose-similarity matching to prove completion.

It does not need a long slow-displacement window.

It does not need required-region quiet gates that can deadlock on occlusion.

It does not need session q20/q90 normalization to construct its primary signal.

Angular, RMS, Top-K, displacement, pose similarity and detailed kinematics may remain useful as **diagnostics or technique-analysis features**. They are not part of the v1 movement-segmentation decision path unless later recordings demonstrate a concrete need.

---

## 16. Detector version and provenance

Persist enough provenance to reproduce a segmentation result.

At minimum record:

- detector algorithm/version;
- activity/body profile;
- position filter definition;
- rolling-area duration;
- start and stop gates;
- logical boundaries;
- retained pre/post-roll boundaries;
- landmark-track identity/version.

Suggested initial detector version:

`activity_qom_hysteresis_v1`

A change to the body blocks, filtering, QoM aggregation, rolling window or gates is a detector-version change and should not silently reinterpret existing persisted segments.

---

## 17. Non-goals and future extensions

Do not add these to v1 unless a real validation recording demonstrates the need:

- adaptive deadband;
- Kalman filter;
- Savitzky-Golay derivative;
- RMS or Top-K fusion;
- angular-movement fusion;
- peak counting;
- technique-specific punch/kick classification;
- return-to-start-pose matching;
- long refractory periods;
- forced expected repetition counts;
- cue-derived movement boundaries.

Cue timing may be associated with detected movements after segmentation, but cues do not manufacture or move physical movement boundaries.

---

## 18. Mathematical summary

For selected effective points `l`:

`r(i,l) = p(i,l) - hipMid_i`

`m(i,l) = trailingMedian3(r)`

`s(i,l) = trailingMean3(m)`

`v(i,l) = ||s(i,l) - s(i-1,l)|| / Δt_i`

`QoM_b(i) = weightedMean_l(v(i,l))`

`QoM(i) = equalMean_b(QoM_b(i))`

`A(i) = rollingIntegral_150ms(QoM)`

Then:

- STILL → MOVING when `A >= 0.08 m`
- MOVING → STILL when `A <= 0.04 m`
- otherwise retain the current state

For punch: blocks = `{LEFT_ARM, RIGHT_ARM, TORSO}`.

For kick: blocks = `{LEFT_LEG, RIGHT_LEG, TORSO}`.

Head is excluded. Clip padding is separate from detector logic.
