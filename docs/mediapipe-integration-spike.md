# MediaPipe Integration Spike Design

## Purpose

This document defines the first MediaPipe Pose integration spike for the Karate Kihon Analyzer.

The spike must validate whether MediaPipe Pose can provide the landmarks required by the current analysis engine before the project invests in a full recorded-video pipeline.

The design principle for this integration is:

> MediaPipe is an input provider, not the karate engine.

MediaPipe should feed existing analysis modules. It must not replace or duplicate the karate-specific logic already owned by:

- `angle_analyzer`
- `impact_frame_selector`
- `session_analyzer`
- `snapshot_renderer`

## Pipeline Bridge

The current proven pipeline is:

```text
synthetic landmarks
→ session analyzer
→ snapshot renderer
```

The target future pipeline is:

```text
recorded video
→ MediaPipe Pose
→ landmark adapter
→ session analyzer
→ snapshot renderer
```

The first spike should build confidence in the bridge between these two worlds:

```text
MediaPipe raw landmarks
→ MediaPipe-to-internal landmark adapter
→ existing Point2D / PunchAnalysis-compatible data
```

The spike should produce debug output first, not final scoring or production analysis.

## Questions the Spike Must Answer

The spike must answer the following questions:

1. Can MediaPipe reliably detect a side-view karate practitioner?
2. Can MediaPipe identify the relevant arm landmarks?
   - left shoulder
   - right shoulder
   - left wrist
   - right wrist
3. Can the project estimate a usable Jodan reference point?
   - chin if available from another future detector
   - otherwise a nose-, mouth-, or head-derived proxy from MediaPipe Pose landmarks
4. Can wrist extension be tracked across frames?
5. Can MediaPipe-derived data be converted into data compatible with the existing analysis model?
6. Can the same snapshot renderer eventually visualize analysis results derived from real video?

## Scope

The spike should remain small, local, and experimental.

### In Scope

The spike should support:

- One still image **or** one short recorded video
- Side-view only
- Single person only
- Local execution only
- Debug output only

### Out of Scope

The spike should not support yet:

- Mobile app integration
- Real-time camera input
- Full punch sequence detection
- Automatic calibration
- Chudan or Gedan analysis
- Production scoring
- Production report generation

## Proposed Files for Later Implementation

The specification proposes the following future files, but this document does not implement them:

```text
src/karate_analyzer/mediapipe_pose_spike.py
src/karate_analyzer/mediapipe_landmark_adapter.py
tests/test_mediapipe_landmark_adapter.py
```

Optional local directories for manual spike inputs and outputs:

```text
input/
output/mediapipe-debug/
```

These directories should be treated as local/debug workflow locations unless the project later decides to commit sample fixtures.

## MediaPipe Integration Responsibilities

The MediaPipe integration layer should be responsible only for:

- Reading image or video frames
- Running MediaPipe Pose detection
- Extracting raw pose landmarks
- Recording landmark visibility/confidence values
- Saving debug output

The MediaPipe integration layer should not be responsible for:

- Karate scoring
- Impact-frame selection as production logic
- Session analysis
- Snapshot rendering rules
- Punch sequence interpretation
- Classification such as `perfect`, `good`, `acceptable`, or `miss`

For the spike, it may compute temporary debug-only extension values to inspect whether real landmark motion looks usable. Any production extension selection should remain owned by `impact_frame_selector`.

## Future Landmark Adapter Responsibilities

A future `mediapipe_landmark_adapter` should convert MediaPipe landmarks into the project's internal point model.

The adapter should eventually provide frame-level data containing:

- `shoulder: Point2D`
- `chin_or_head_reference: Point2D`
- `wrist: Point2D`
- `side: PunchSide`
- `frame_number`
- `timestamp_seconds`
- visibility/confidence values for source landmarks

The adapter's most important responsibility is to hide MediaPipe-specific details from the rest of the analysis engine. Downstream modules should not know MediaPipe landmark indexes, names, visibility semantics, or image-coordinate conventions.

### Side Selection

The adapter should accept or derive the punch side as a `PunchSide`.

For the first spike, side selection should be explicit or debug-configured rather than inferred automatically. Side-view video can make left/right labels unreliable, so automatic side inference should be deferred until there is evidence that the landmarks are stable enough.

### Jodan Reference Point

The existing Jodan analyzer needs a shoulder, chin/reference point, and wrist. MediaPipe Pose does not expose a direct chin landmark.

The spike should test head-derived alternatives in this order:

1. A future chin point if another detector is later added.
2. Nose landmark as a simple first proxy.
3. Midpoint or weighted point derived from visible nose, mouth, eye, or ear landmarks if available and stable.
4. A documented fallback head reference only if confidence is acceptable.

The adapter should label this value as a chin-or-head reference until the project has a true chin detector.

## Coordinate System Handling

MediaPipe image coordinates use image-space orientation:

```text
x increases right
y increases downward
```

The current synthetic analyzer assumes logical geometry where:

```text
x increases right
positive y appears upward
```

The adapter should convert MediaPipe image coordinates into the internal coordinate system before passing points to existing analysis modules.

Recommended normalized conversion:

```text
internal_x = normalized_mediapipe_x
internal_y = -normalized_mediapipe_y
```

Equivalent conversions are acceptable if they preserve the invariant that the analysis engine receives right-increasing, upward-positive logical coordinates.

The analysis engine should not need to know about MediaPipe's image-coordinate `y`-down behavior.

## First Spike Output

The first MediaPipe spike should produce debug artifacts, not final analysis reports.

### Still Image Output

For an input image or single selected video frame, output:

- Detected landmarks summary
- Visibility/confidence values for required landmarks
- One debug image with landmarks drawn
- Optional JSON debug file containing raw and adapted points

### Video Output

For a short video, output:

- Per-frame wrist and shoulder positions
- Per-frame visibility/confidence values
- Per-frame debug extension values
- Candidate max-extension frame
- Debug image for the candidate frame
- Optional JSON debug file with per-frame raw and adapted landmark data

The candidate max-extension frame is a debug finding in this spike. It should be used to evaluate whether the real landmark stream can later feed `impact_frame_selector`.

## Compatibility with Existing Analysis Model

The spike should evaluate whether MediaPipe-derived frame data can be shaped into the same conceptual inputs already used by the current analyzer:

```text
frame_number
timestamp_seconds
side
shoulder: Point2D
chin/head reference: Point2D
wrist: Point2D
extension
```

For a future full pipeline, this shape should be sufficient to construct data that can feed session-level analysis and eventually produce `PunchAnalysis` results.

The goal is reuse, not replacement. MediaPipe should provide real landmarks; existing modules should continue to own scoring, impact-frame selection, session orchestration, and rendering.

## Snapshot Renderer Compatibility

The snapshot renderer should eventually be able to visualize real-video analysis results because it should receive completed analysis data rather than raw MediaPipe output.

The future rendering path should be:

```text
MediaPipe Pose
→ landmark adapter
→ session analyzer
→ PunchAnalysis
→ snapshot renderer
```

A later renderer enhancement may draw on top of a real video frame, but the renderer should still not run MediaPipe, calculate angles, select impact frames, or perform karate scoring.

## Success Criteria

The spike is successful if it can show:

1. MediaPipe detects the practitioner in a side-view karate frame.
2. Required landmarks are present with acceptable visibility.
3. Wrist extension can be tracked over time.
4. A candidate impact frame can be identified from real landmarks.
5. Extracted points can be mapped into the same shape needed by `PunchAnalysis`.

## Risks

The spike should explicitly track these risks:

- Side-view poses may reduce landmark visibility.
- Left/right arm labels may be unreliable from side view.
- Chin is not a direct MediaPipe Pose landmark.
- Wrists may be occluded during hikite or crossing motion.
- Camera angle and distance may strongly affect landmark stability.
- Image-coordinate `y` axis differs from the current synthetic coordinate system.

## Recommended Development Order After This Spec

After this specification, development should proceed in this order:

1. Still-image MediaPipe landmark spike
2. Short-video MediaPipe landmark spike
3. MediaPipe-to-internal `Point2D` adapter
4. Real-frame extension tracking
5. Candidate impact-frame extraction
6. Connect real extracted frames to the existing session analyzer
7. Render real-video debug snapshots

## Non-Goals for the First Spike

The first spike should not attempt to prove the full product workflow. It should not score karate technique, detect a full 10-punch sequence, or produce user-facing results.

Its only job is to prove whether MediaPipe Pose can provide useful enough raw landmarks to become the recorded-video input provider for the existing karate analysis engine.
