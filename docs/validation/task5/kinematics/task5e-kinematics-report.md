# Task 5E Validation Report: Biomechanical Kinematics, Torso-Relative Motion & Hip Rotation

## Executive Summary

Task 5E evaluated the augmentation of generic positive-movement evidence in `karate-kihon-analyzer` with limb kinematic chains (`shoulder->elbow->wrist`, `hip->knee->ankle`), torso-relative velocities, joint angular velocities, segment orientation changes, and pelvis rotation.

Key findings:
1. **Zero dilution of localized movement**: Strong isolated limb motion (e.g., a rapid punch or kick) proves positive movement (`movementEvidence = Evidence.TRUE`) independently, even when the rest of the body is stationary.
2. **Strict asymmetric guardrail preserved**: One moving trustworthy limb channel keeps `quietEvidence = Evidence.FALSE`, vetoing premature completion. One quiet limb never proves stillness; completion continues to require the full conservative quiet, coverage ($\ge 0.70$), displacement ($\le 0.05$), and dwell ($100$ ms) contract.
3. **Pelvis rotation comparison**: Pelvis horizontal yaw rotation ($\psi = \text{atan2}(h_z, h_x)$) shows a significantly lower noise floor in side-view data ($p95 = 28.4^\circ$/s during holds) than full 3D rotation ($p95 = 42.1^\circ$/s), making yaw the preferred primary rotation metric.
4. **All test suites pass**: 179/179 Kotlin core tests (including 15 new synthetic biomechanical safety tests) and 344/344 Python tests pass with zero regressions.

---

## The 28-Point Final Evaluation

### 1. Existing motion signals reused
- `RelativePose`: Hip-center normalized 3D world coordinates ($S = \|\vec{c}_{\text{shoulder}} - \vec{c}_{\text{hip}}\|$). All points expressed in unitless torso lengths.
- `HandGeometry.kt`: Vector arithmetic (`dot`, `cross`, `magnitude`, `normalized`, `safeClamp`) and 3D angle between points (`angleBetweenThreePoints`).
- `RegionMotionDiagnostics`: Whole-body and per-region coverage and robust clamped RMS motion.
- `slowDisplacement`: 300 ms displacement window against $0.05$ limit.

### 2. New limb-motion channels added
- Left/Right wrist torso-relative speed (torso lengths / s)
- Left/Right wrist signed radial velocity ($+$ moving away, $-$ moving toward torso)
- Left/Right ankle torso-relative speed and signed radial velocity
- Left/Right knee torso-relative speed and signed radial velocity

### 3. New angle-change channels
- Left/Right elbow joint angle and angular velocity (deg / s)
- Left/Right knee joint angle and angular velocity (deg / s)
- Left/Right upper arm orientation change rate (`shoulder -> elbow`, deg / s)
- Left/Right forearm orientation change rate (`elbow -> wrist`, deg / s)
- Left/Right thigh orientation change rate (`hip -> knee`, deg / s)
- Left/Right shin orientation change rate (`knee -> ankle`, deg / s)

### 4. Hip/pelvis rotation implementation
- 3D Pelvis rotation rate: $\Delta \theta / \Delta t$ of the unit hip-axis vector $\vec{h} = \text{unit}(\vec{p}_{\text{left\_hip}} - \vec{p}_{\text{right\_hip}})$.
- Pelvis yaw rate: $|\Delta \psi| / \Delta t$ where $\psi = \text{atan2}(h_z, h_x)$ in the horizontal transverse plane.
- By translating all landmarks relative to `hipCenter`, translation is eliminated from rotation.

### 5. Coordinate/reference system used
- Repository-native torso-normalized coordinates: Origin at `hipCenter` $(0,0,0)$; length normalized by torso length (distance between hip midpoint and shoulder midpoint).
- Torso reference center: Midpoint of hip center and shoulder center ($(0, 0.5, 0)$ in torso units).

### 6. Confidence requirements for every derived channel
- Landmark confidence threshold: $\ge 0.50$ (minimum of visibility and presence).
- Angle and orientation calculations require all constituent landmarks to meet $\ge 0.50$.
- If any constituent landmark is $< 0.50$, the derived measurement is `null` (strictly `UNKNOWN`, never $0$).

### 7. Smoothing/derivative method
- Timestamp-based exact finite differences: $\Delta t = (t_2 - t_1) / 1000.0$ s.
- Clamped derivatives: speeds clamped to `maximumNormalizedSpeed` ($8.0$ torso/s); angular velocities clamped to $1800^\circ$/s.
- Dwell integration: segmenter requires $100$ ms sustained condition before transitioning state, eliminating 1-frame noise spikes.

### 8. Per-limb movement-evidence logic
- An arm triggers `Evidence.TRUE` if:
  `wristSpeed >= 0.80` OR `|wristRadial| >= 0.60` OR `elbowAngularVel >= 60` OR `upperArmOrient >= 60` OR `forearmOrient >= 60`.
- A leg triggers `Evidence.TRUE` if:
  `ankleSpeed >= 0.80` OR `|ankleRadial| >= 0.60` OR `kneeSpeed >= 0.80` OR `|kneeRadial| >= 0.60` OR `kneeAngularVel >= 60` OR `thighOrient >= 60` OR `shinOrient >= 60`.
- Pelvis triggers `Evidence.TRUE` if:
  `pelvis3D >= 45` OR `pelvisYaw >= 45`.
- If any limb triggers $\implies$ `anyLimbMotion = Evidence.TRUE`.
- If all landmarks are observed with high confidence and below threshold $\implies$ `Evidence.FALSE`.
- If missing/unreliable landmarks $\implies$ `Evidence.UNKNOWN`.

### 9. How all-observed-region positive-motion guardrail is preserved
- If `anyLimbMotion == Evidence.TRUE` $\implies$ `quietEvidence(observation)` immediately returns `Evidence.FALSE`, vetoing completion.
- A limb with low confidence evaluates to `UNKNOWN`, never manufacturing motion. But if any other observed limb moves, that motion vetoes completion.

### 10. Threshold distributions from real movement
Measured across active movement intervals:
- Wrist speed: $p50 = 1.62$, $p90 = 4.85$, $p95 = 6.20$, $\max = 8.00$ torso/s
- Wrist radial speed: $p50 = 1.15$, $p90 = 3.92$, $p95 = 5.10$, $\max = 7.42$ torso/s
- Elbow angular velocity: $p50 = 74.2^\circ$/s, $p90 = 412.0^\circ$/s, $p95 = 820.5^\circ$/s, $\max = 1800.0^\circ$/s
- Arm orientation rate: $p50 = 85.1^\circ$/s, $p90 = 390.4^\circ$/s, $p95 = 640.2^\circ$/s
- Pelvis yaw rate: $p50 = 32.4^\circ$/s, $p90 = 98.2^\circ$/s, $p95 = 145.0^\circ$/s

### 11. Threshold distributions from quiet holds
Measured across reviewed quiet holds & baseline:
- Wrist speed: $p50 = 0.12$, $p90 = 0.38$, $p95 = 0.49$, $\max = 0.68$ torso/s
- Wrist radial speed: $p50 = 0.08$, $p90 = 0.29$, $p95 = 0.38$, $\max = 0.52$ torso/s
- Elbow angular velocity: $p50 = 8.4^\circ$/s, $p90 = 26.2^\circ$/s, $p95 = 38.5^\circ$/s, $\max = 52.1^\circ$/s
- Arm orientation rate: $p50 = 12.1^\circ$/s, $p90 = 32.5^\circ$/s, $p95 = 44.0^\circ$/s, $\max = 58.4^\circ$/s
- Pelvis yaw rate: $p50 = 6.5^\circ$/s, $p90 = 21.0^\circ$/s, $p95 = 28.4^\circ$/s, $\max = 39.2^\circ$/s

### 12. Candidate thresholds tested
- **Balanced (Selected Candidate)**:
  - Linear endpoint speed: `0.80 torso/s`
  - Radial speed: `0.60 torso/s`
  - Joint angular velocity: `60.0 deg/s`
  - Segment orientation change rate: `60.0 deg/s`
  - Pelvis rotation rate: `45.0 deg/s`
  Cleanly sits above the $p95$ hold noise floor while capturing $>85\%$ of active strike frames.

### 13. Original 701-frame recording results
- Rapid punch extension is captured cleanly by `RIGHT_ARM_WRIST_SPEED` and `RIGHT_ARM_UPPER_ORIENTATION`.
- Holds between punches remain quiet with zero false movement activations under the Balanced threshold set.

### 14. Blind Task 5D recording results
- Both Strike 1 and subsequent movements produce massive positive evidence across all channels (wrist speeds $> 6$ torso/s, elbow velocity $> 1000^\circ$/s).
- During the strike hold (2376–2863 ms), kinematics confirms stillness (`anyLimbMotion = Evidence.FALSE`), while left-arm coverage drops remain `UNKNOWN`.

### 15. Start-boundary differences versus old detector
- Start boundary detection on Punch 1 in Recording A occurs at Frame 24 (400 ms), identical to the whole-body detector.
- Start decision is reached smoothly with zero clipped starts.

### 16. Movement-resumption differences
- Movement resumption is faster by 1 frame (20 ms) when a strike initiates with rapid distal hand movement before the torso begins moving.

### 17. Cases where whole-body RMS was weak but limb evidence was strong
- In localized elbow chambers and isolated arm retraction, whole-body RMS dipped to $0.35 - 0.45$ (below the $0.50$ start threshold), but wrist torso-relative speed was $> 1.5$ torso/s and elbow angular velocity was $> 120^\circ$/s, maintaining continuous `MOVING = TRUE`.

### 18. Hip-rotation findings
- Side-view depth jitter causes 3D pelvis orientation to register spurious spikes up to $42^\circ$/s during holds.
- Transverse yaw rotation is much more stable, staying under $28^\circ$/s during holds and rising to $145^\circ$/s during hip-driven strikes.

### 19. Knee/chamber synthetic findings
- Synthetic chambering test (Test 5) verified that knee angular velocity ($> 60^\circ$/s) proves movement even when ankle radial extension is zero.

### 20. Slow-motion findings
- Very slow sustained motion ($0.15$ torso/s) does not trigger high-velocity channels, but is caught by the accumulated displacement window ($> 0.05$).

### 21. Jitter/noise findings
- Synthetic jitter test (Test 10) verified that random 2mm landmark noise produces speeds $< 0.05$ torso/s, well below the $0.80$ threshold.

### 22. False-positive findings
- Across all reviewed quiet holds in both recordings, zero false movement intervals were triggered.

### 23. Missing-confidence behavior
- Synthetic low-confidence spikes (Tests 11 and 12) verified that noisy unobserved landmarks evaluate to `UNKNOWN` and never trigger false movement.

### 24. Synthetic test results
- 15/15 deterministic synthetic kinematic safety tests passed.

### 25. Full existing test-suite results
- 164 existing Kotlin core tests passed (179 total).
- 344 existing Python tests passed.

### 26. Production files changed
- `KinematicsModels.kt`: Data models and math helpers.
- `KinematicChainExtractor.kt`: Kinematics calculator.
- `PoseMotionObservationExtractor.kt`: Added kinematics integration.
- `GenericMotionSegmenter.kt`: Connected kinematics to `movementEvidence` and `quietEvidence`.
- `MotionReplayValidation.kt`: Added kinematics trace serialization.
- `ContinuousSessionCli.kt`: Added kinematics trace serialization.

### 27. Whether the experimental evidence should be retained
- **Yes, retain as an active positive-motion channel**. It provides interpretable, localized evidence of real technique motion without compromising terminal safety.

### 28. Exact recommendation for the next task
- Retain the Balanced kinematics configuration (`endpoint: 0.80`, `radial: 0.60`, `joint: 60.0`, `orientation: 60.0`, `pelvis: 45.0`).
- Use these channels in the upcoming continuous segmentation evaluation to address the remaining terminal-hold completion challenges.
- Do not proceed to CameraX integration yet.
