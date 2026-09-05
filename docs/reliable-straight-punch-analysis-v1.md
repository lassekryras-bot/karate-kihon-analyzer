# Reliable Explainable Straight-Punch Analysis v1

## Current-state assessment

The repository already decodes video frames, runs MediaPipe on each decoded BGR
frame, preserves normalized Pose image landmarks and separate Pose world
landmarks, detects extension regions, selects an analysis frame, calculates an
elbow angle and extension velocity, derives task-specific Jodan references, and
renders an annotated copy of the same numbered frame. Strike events retain a
movement window rather than only a terminal snapshot.

The important gap was that identical analysis and saved-frame geometry was an
assumption. Landmark drawing multiplied normalized coordinates by whatever image
the renderer received, and output did not record dimensions or transformations.
That was correct only while both decoders happened to return identical frames.
Measurement fields also currently coexist with interpretation fields in a loose
JSON structure; trajectory samples and repetition aggregates are not yet a
stable domain model.

## Coordinate and frame contract

`FrameGeometry` is the boundary between the vision provider and image-space
consumers. It names:

- analysis-frame pixel dimensions;
- saved/source-frame pixel dimensions;
- an invertible analysis-pixel to saved-pixel affine transform; and
- its computed inverse.

The conversion path for an observed MediaPipe image landmark is explicitly:

```text
normalized analysis point
  -> analysis pixel point
  -> analysis_to_saved affine transform
  -> saved-frame pixel point
```

The current Python video path analyzes the exact frame returned by OpenCV and
later extracts the same zero-based frame from the same source video. It applies
no resize, crop, rotation, mirroring, or letterboxing, so it records an identity
transform and rejects changing decoded dimensions. Frame timestamps are
MediaPipe inputs derived from decoder `POS_MSEC`, with frame-number/FPS fallback;
snapshot selection and extraction are keyed by zero-based frame number. The
renderer verifies the extracted dimensions against the recorded contract.

Pose world landmarks remain a separate raw stream for future 3D biomechanical
measurements. They must not enter `FrameGeometry`, be multiplied by image
dimensions, or be drawn without an explicit calibrated world-to-image
projection. Camera preview coordinates are also a distinct space; an Android
preview integration must publish its own explicit saved/analysis-to-preview
transform rather than reuse this contract implicitly.

## Proposed architecture and data flow

```text
decoded source frame + frame number/timestamp
  -> MediaPipe image landmarks + separate Pose world landmarks
  -> FrameGeometry + raw landmark timeline
  -> strike event window and selected terminal frame
  -> measurement records
  -> optional derived flags
  -> task/style interpretation
  -> coaching feedback
  -> 2D render primitives in saved-frame coordinates
  -> snapshot renderer
```

The four semantic layers **measurement -> derived flag -> interpretation ->
coaching feedback** must remain independently serializable. For example,
`terminal_elbow_angle_degrees` remains useful when a later style profile changes
how it is interpreted. No universal pass/fail threshold should be attached to
this measurement.

The next small domain additions should be:

1. `StraightPunchMeasurements`, containing optional values plus provenance,
   units, frame/time window, landmark confidence and coordinate space;
2. a sensor-independent timeline input containing explicitly named normalized
   analysis points and Pose world points; and
3. renderer-neutral 2D primitives (`Point`, `Line`, `Polyline`, `Arc`, `Label`)
   already mapped to saved-frame pixels.

## Focused implementation sequence

### Phase A — completed first slice

Record and serialize `FrameGeometry`, propagate it with strike-event landmark
output, use it for snapshot landmark/reference mapping, validate saved-frame
dimensions, and unit-test identity, transformed, mirrored and inverse mapping.

### Phase B — measurement model

Introduce the typed measurement envelope without changing current coaching
outputs. Migrate the existing terminal elbow angle, execution time (strike-region
start to terminal timestamp), and wrist/extension velocity as descriptive
measurements with units and confidence.

### Phase C — endpoint and trajectory

Define a task-provided image-space target and calculate signed endpoint error.
Preserve wrist samples across the event window, then add path-length/direct-
distance ratio and perpendicular deviation. Report values and uncertainty, not
population thresholds.

### Phase D — explainable snapshot

Generate renderer-neutral primitives from the measurements: observed arm
landmarks, target line/point, endpoint, shoulder-to-wrist line, trajectory,
elbow-angle arc, deviation marker, and metric labels. The renderer only paints
these primitives.

### Phase E — repetition validation

Aggregate like-for-like task repetitions into endpoint mean/dispersion and
timing/velocity consistency. Compare with the current session and personal
baseline; do not invent normative cutoffs.

## Validation rule

Before trusting a new biomechanical metric, save the exact selected source frame
with observed image landmarks and optional `FrameGeometry` debug text. A fixture
with known transformed points must pass round-trip coordinate tests. Real-video
validation should also compare decoded analysis and extracted frame number,
dimensions and timestamp and visually inspect high-motion frames for decoder
seek mismatch.

## Non-goals for v1

This milestone excludes calibrated 3D projection, hand-to-Pose world
registration, detailed wrist alignment, hyperextension diagnosis, kinetic
weight distribution, full segment sequencing, kicks and an overall karate
score.
