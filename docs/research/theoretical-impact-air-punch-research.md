# Estimating Theoretical Impact in Air Punches

Audience: Karate Analyzer product and engineering team  
Date: 6 September 2026  
Scope: straight air punches recorded from smartphone video and analyzed with MediaPipe Pose. Physical collision detection and broad technique coaching are excluded.

## Executive answer

Keep `impact_frame`, but define it as the video frame nearest an estimated continuous-time `theoretical_impact_time`: the first credible terminal event at which the punch reaches its intended functional endpoint. This is a project-specific operational definition, not a claim of observed physical contact.

Do not choose impact from the latest or visually clearest full-extension frame. Peak fist velocity, theoretical impact, maximum geometric extension, the start of a held terminal pose, and the best analysis image are distinct events. In target-contact studies, contact is established independently by a force target and contact velocity is measured immediately before contact; peak velocity is separately reported. Karate research without a target shows that peak forward velocity can occur late in the movement but still before full extension. These findings support a temporally ordered model rather than a single “peak equals impact” rule.

Recommended sequence:

`punch onset -> acceleration -> peak forward velocity -> terminal deceleration -> estimated theoretical impact -> optional hold -> retraction`

For some punches, peak velocity and theoretical impact may fall in adjacent frames; they should still remain separate fields.

## Definitions

- `theoretical_impact_time`: estimated continuous-time first arrival at the functional endpoint, based on motion geometry; never evidence of physical contact.
- `impact_frame`: decoded frame temporally nearest `theoretical_impact_time`. Landmark quality can lower confidence or make the event unavailable, but must not move this frame later into a clearer terminal pose.
- `impact_point`: estimated striking location on the fist at theoretical impact. Pose alone supplies wrist landmarks, not a reliable knuckle contact surface; this point may therefore be derived or explicitly marked as approximate.
- `target_height` or `target_line`: intended vertical level, such as Jodan. A height line alone does not define depth along the punch direction and cannot prove target arrival.
- `terminal_pose_start`: first frame of a sustained low-motion endpoint interval.
- `measurement_window`: timestamp-bounded samples around the event used for dynamic measurements such as velocity, trajectory, and timing.
- `analysis_frame`: explicitly selected frame used for measurements that require one decoded image. Its timestamp and offset from theoretical impact must be reported.
- `snapshot_frame`: frame chosen for the human-readable annotated image. It may equal the analysis frame, but that relationship must be explicit; visual quality must not rewrite the event or analysis timestamps.
- `physical_contact_status`: tri-state observation (`not_assessed`, `not_detected`, or `detected`) requiring a target, force/acceleration signal, audio/visual contact evidence, or another validated contact detector. `not_assessed` is not evidence that contact did not occur.

## Evidence and interpretation

Contact-based punch biomechanics distinguishes peak velocity from contact velocity. Liu et al. synchronized 200 Hz Vicon data with a Kistler target; contact velocity was the hand velocity in the frame before the fist struck the target, while peak velocity was independently extracted from the velocity curve. Adamec et al. measured hand velocity over the final 10 cm before pad contact. Therefore, a no-target system cannot directly inherit “contact” from the velocity maximum.

Karate punch studies describe total movement times under roughly 400 ms and report fist velocities around 8 m/s, making a 0.5 s late selection biomechanically large rather than a harmless frame adjustment. A 2026 longitudinal motion-capture study of a parallel air punch found expert peak forward velocity around 85-90% of movement duration and within 0.1 s of full extension, while explicitly noting that the absence of an impact target may alter positional accuracy and force generation. The study is highly relevant but small (one expert, four novices), so its numeric timing should guide hypotheses, not become a universal threshold.

MediaPipe Pose provides per-frame image and world landmarks plus visibility/presence estimates, and video mode uses tracking. These outputs permit quality-aware selection, but visibility is not an error bound and monocular 3D depth remains ambiguous. The BlazePose GHUM paper presents a real-time monocular landmark system rather than a contact detector. Numerical differentiation amplifies measurement noise, so velocities and accelerations must be computed from appropriately smoothed trajectories; filtering choices can alter peaks and their timing.

## Recommended detector

### 1. Preprocess and normalize

- Use real frame timestamps, not assumed constant frame intervals.
- Track the striking side through the whole punch; reject side swaps.
- Build shoulder-relative or torso-relative fist coordinates to reduce whole-body translation.
- Prefer the camera-plane forward component for a well-controlled side view; treat monocular world-Z as supporting evidence, not the sole axis.
- Smooth positions before differentiation with a zero-phase offline filter or a local polynomial method. Tune on labeled punch video; never copy a universal cutoff without validation.
- Retain raw and smoothed values, landmark visibility/presence, and missing/interpolated flags.

### 2. Find the punch window

Detect forward motion onset from sustained positive fist progress plus elbow extension, then locate the later retraction onset from sustained negative progress. The candidate search must be confined to this single outward stroke; otherwise a later static pose can win.

### 3. Generate terminal-event candidates

A candidate is created at the earliest frame or sub-frame crossing that satisfies a conjunction of signals:

1. Fist progress is near the local terminal distance or crosses a calibrated target plane when one exists.
2. Elbow extension is near its local functional maximum but below any configured safety/hyperextension exclusion.
3. Forward fist velocity has passed its outward peak and is rapidly decelerating toward a low value, or changes sign into retraction.
4. The candidate is temporally consistent with the subsequent endpoint/hold or retraction for a short confirmation window.
5. Required landmarks meet quality and continuity criteria.

The event timestamp should be the earliest credible arrival, not the center or end of the confirmation window. Confirmation may use future frames without moving the event later.

### 4. Resolve three movement shapes

- Immediate reversal: choose the forward-position turning point, refined between frames if feasible.
- Short endpoint and hold: choose the start of the endpoint plateau, not its clearest middle or last frame.
- Noisy or ambiguous terminal motion: combine position, elbow angle, signed velocity, deceleration, trajectory direction, and quality in a confidence score. If ambiguity remains, label impact as low confidence or unavailable instead of silently selecting a late pose.

### 5. Choose analysis and snapshot frames separately

Define the neighborhood in time, initially about ±75 ms, and convert it to samples using actual decoder timestamps rather than a fixed frame count. Select an analysis frame only when a measurement genuinely requires one image; preserve its time and offset from impact. Dynamic measurements must use the timestamped measurement window, not a later representative frame.

Choose the annotated snapshot independently within the same bounded movement phase. It may reuse the analysis frame when suitable, but a clearer later image must be labeled `snapshot_frame` or `terminal_pose_frame`, with its offset shown. If no acceptable nearby image exists, use the impact frame with a quality warning or omit the snapshot. A frame about 0.5 s later must not supply dynamic impact measurements and must not be described as impact.

## Confidence model

Use an event-level score with auditable components rather than a single opaque selector score:

- kinematic agreement: position, elbow extension, velocity decline, turning/plateau evidence;
- temporal agreement: correct ordering after onset and peak velocity, before retraction;
- landmark quality: visibility/presence, continuity, no side swap, limited interpolation;
- target evidence: calibrated plane crossing when available;
- ambiguity penalty: multiple near-equal terminal candidates, long hold, occlusion, motion blur, or implausible bone-length jumps.

Until confidence has been calibrated against labeled video, report an ordinal level such as `high`, `medium`, or `low` together with the component evidence. If a numeric prototype score is retained internally, name it `uncalibrated_confidence_score` and do not present it as a probability. Do not interpret MediaPipe visibility as event confidence.

## Data model and reporting

Minimum event fields:

- `theoretical_impact_time_ms`, `impact_frame_index`, `impact_estimation_method`, `impact_confidence_level`;
- `peak_velocity_time_ms`, `peak_velocity_frame_index`;
- `terminal_pose_start_ms`, `retraction_start_ms`;
- `measurement_window_start_ms`, `measurement_window_end_ms`;
- `analysis_time_ms`, `analysis_frame_index`, `analysis_frame_reason`;
- `analysis_offset_from_impact_ms`, `analysis_phase`;
- `snapshot_time_ms`, `snapshot_frame_index`, `snapshot_frame_reason`, `snapshot_offset_from_impact_ms`;
- `physical_contact_status` (`not_assessed`, `not_detected`, or `detected`), plus detector and confidence when assessed;
- raw/filtered signal version, coordinate space, FPS/timestamp source, and quality flags.

User-facing wording:

“Estimated theoretical impact at 3.267 s (frame 98, medium confidence). A static measurement uses frame 100, 67 ms later, because wrist visibility was higher. Dynamic measurements use the timestamped event window. Physical contact was not assessed.”

If the display image differs again:

“Annotated snapshot uses frame 99, 33 ms after estimated theoretical impact. It is a visual explanation frame, not a revised impact time.”

For a late held pose:

“Terminal-pose snapshot at 3.73 s. Not used as the impact timestamp or for impact velocity/sequencing.”

## Consequences for punches 7 and 10

The roughly half-second gap is too large to treat as a normal nearby quality substitution. Preserve the early candidate as the provisional theoretical-impact event if it satisfies the revised multi-signal criteria. Reclassify the late selected frames as `terminal_pose_frame` or snapshot-only frames. Recompute execution time, velocity, trajectory, and sequencing from the event-aligned measurement window. Static alignment from the late frame may be displayed separately only with its phase and offset made explicit.

## Validation plan

1. Create a labeled set spanning immediate reversal, short hold, long hold, novice slow punches, fast punches, occlusion, motion blur, and different target heights.
2. Have at least two karate-informed raters mark first functional endpoint and retraction onset frame-by-frame; retain disagreement rather than forcing false precision.
3. Record a smaller ground-truth subset against a lightly instrumented pad or synchronized contact signal. Compare the air-punch proxy logic with true contact trials, but do not assume they are identical tasks.
4. Report median absolute timing error, signed early/late bias, within-one-frame and within-two-frame agreement, failure rate, and inter-rater uncertainty.
5. Tune thresholds only on training/validation subjects and test on held-out people, phones, frame rates, distances, clothing, and lighting.
6. Add regression tests specifically asserting that extending a plateau cannot move `theoretical_impact_time` later.

## Decision

Adopt the conceptual model with one refinement: the canonical object should be `theoretical_impact_event`, containing a continuous timestamp and its temporally nearest `impact_frame`. Keep `impact_frame` in UI and code for convenience. Separate the event, timestamped measurement window, `analysis_frame`, `snapshot_frame`, `terminal_pose_frame`, and `physical_contact_status`. This preserves the karate meaning of impact while preventing measurement or presentation selectors from rewriting movement timing.

## Limitations

No published consensus definition was found for “impact” in an unopposed air punch. The proposed event is therefore an engineering operationalization grounded in contact biomechanics, karate kinematics, reaching/event-detection logic, and pose-estimation constraints. Universal numeric thresholds are not justified by the available studies; they require validation on this app's capture protocol and users.

## Sources and claim ledger

1. Liu, Y. et al. (2022). “Biomechanics of the lead straight punch of different level boxers.” Frontiers in Physiology. https://doi.org/10.3389/fphys.2022.1015154 — synchronized target/contact and Vicon definitions; peak versus contact velocity.
2. Adamec, J. et al. (2021; published online 2020). “Biomechanical assessment of various punching techniques.” International Journal of Legal Medicine, 135(3), 853-859. https://doi.org/10.1007/s00414-020-02440-8 — velocity over the final 10 cm before pad contact.
3. Hofmann, M., Witte, K., & Emmermacher, P. (2008). “Biomechanical analysis of fist punch Gyaku-Zuki in karate.” ISBS Proceedings Archive. https://ojs.ub.uni-konstanz.de/cpa/article/view/1937 — sub-400 ms movement and about 8 m/s fist maxima in three karateka.
4. Suwarganda, E. K. et al. (2009). “Analysis of performance of the karate punch (Gyaku-Zuki).” ISBS Proceedings Archive. https://ojs.ub.uni-konstanz.de/cpa/article/view/3410 — joint-velocity sequencing and 3D analysis at 150 Hz in elite karate athletes.
5. Mele, C. et al. (2026). “A longitudinal study on karate parallel punch and front kick biomechanics.” Journal of Martial Arts Research. https://doi.org/10.25847/jomar.2026.63 — air-punch endpoint, expert late peak-velocity timing, and limitations of no-target collection. Small sample; used cautiously.
6. Google AI Edge (updated 17 August 2026). “Pose landmark detection guide for Android.” https://developers.google.com/edge/mediapipe/solutions/vision/pose_landmarker/android — video timestamps, tracking mode, landmark/world-landmark output, visibility and presence.
7. Grishchenko, I. et al. (2022). “BlazePose GHUM Holistic: Real-time 3D Human Landmarks and Pose Estimation.” arXiv:2206.11678. https://arxiv.org/abs/2206.11678 — monocular on-device 3D landmark system and scope.
8. Crenna, F., Rossi, G. B., & Berardengo, M. (2021). “Filtering Biomechanical Signals in Movement Analysis.” Sensors. https://doi.org/10.3390/s21134480 — effects and selection of filtering for biomechanical signals.

Searches covered karate/boxing punch kinematics, target-defined contact, air-punch endpoint timing, markerless pose constraints, and biomechanical filtering. Research stopped when the main decision had primary support and the remaining gap was clearly a project-specific validation question rather than something another generic search could resolve.
