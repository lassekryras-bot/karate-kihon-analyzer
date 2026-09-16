# Implementation prompt — replace generic movement detection with activity-aware QoM segmentation

Implement the replacement movement-segmentation foundation defined in `docs/motion-segmentation-math.md`.

Treat that document as authoritative for the math, coordinate system, filtering order, block definitions, QoM aggregation, rolling area, hysteresis, cue/analysis-window semantics, and current validation evidence.

## Goal

Replace most of the current complex generic motion-evidence path with the simpler validated QoM model.

Do **not** treat the segmenter as a technique classifier. Its job is to isolate the activity-relevant movement window associated with a known activity/cue. Activity-specific analyzers then search inside that bounded window for events such as theoretical impact, terminal extension, target-height events, kick extension, etc.

Keep these three concepts separate:

1. detector movement/analysis window;
2. downstream technique events inside that window;
3. wider human-viewable clip with pre/post-roll.

## Required signal

Implement exactly:

`MediaPipe world landmarks -> subtract current hip midpoint -> activity-specific effective points -> 3-frame trailing coordinate-wise median -> 3-frame trailing mean -> timestamp-based point speed -> confidence-weighted block QoM -> equal block average -> trailing 150 ms trapezoidal area -> START/STOP hysteresis`

Initial gates for this exact world-coordinate formulation:

- START = `0.08`
- STOP = `0.04`

Do not torso-normalize this detector in v1. The adult/child A/B comparison in the math document showed essentially identical curve shapes and no consistent signal-quality gain from torso normalization.

Do not add a deadband in v1.

## Activity selection

Use recording/activity metadata to select participating blocks.

For punches / upper-body strikes:

- left arm = left elbow + composite left hand;
- right arm = right elbow + composite right hand;
- torso = both shoulders + both hips;
- exclude legs and head/face.

For kick/leg activities, use left leg + right leg + torso with composite feet as defined in the math document.

Keep block selection configurable so later activities can supply different relevant block sets without rebuilding the detector.

## Migration

Inspect the current `PoseMotionObservationExtractor`, `BaseMovementSegmenter`, profiles, controller integration, replay tests, and archived Task 5 experiments before editing.

Preserve useful external contracts such as timestamps, cue association, segment/window output, recording identity, pre/post-roll, and downstream analysis integration.

Do **not** preserve old internal complexity only for compatibility. Review old RMS/Top-2/angular/baseline/displacement/kinematic-chain/settling logic and remove or bypass anything whose purpose was only to compensate for the previous motion evidence.

If a legacy safeguard is retained, document the concrete recording/failure mode that still requires it.

Prefer a small implementation that can be replayed and plotted easily.

## Validation

Add deterministic replay tests for the available real-recording fixtures / equivalent captured data covering approximately 30, 33 and 59 FPS.

Validate at minimum:

- clean STILL/MOVING transitions without chatter;
- the known 10-movement recordings still produce 10 isolated movement windows;
- the higher-FPS recording stays separated with the same math;
- head/face and irrelevant activity blocks cannot affect the selected signal;
- equal block weighting is preserved;
- coordinate units and threshold assumptions cannot silently drift;
- detector boundaries stay separate from downstream theoretical-impact/event calculations and human-viewable padding.

Do not add extra safeguards unless a real replay demonstrates why they are needed.

Repository principle:

> Segment relevant movement first. Interpret karate technique afterward.
