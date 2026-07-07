# Architecture

Karate Kihon Analyzer is organized as a layered MVP pipeline. Each layer should expose domain-oriented data to the next layer instead of leaking lower-level implementation details upward.

```text
Video
  ↓
Vision Provider
  - MediaPipe Pose
  - MediaPipe Hands
  - optional Face Mesh / Face Landmarker
  ↓
Raw Landmark Frames
  ↓
Strike Detection
  - extension signals
  - grouped peak regions
  - expected sequence matching
  ↓
Strike Events
  - event index
  - expected side
  - observed side
  - peak frame
  - analysis frame
  - landmarks
  - references
  ↓
Reference Models
  - impact_point
  - chin_reference
  - jodan_reference
  ↓
Technique Analyzers
  - Jodan height analyzer
  - future straight punch path analyzer
  - future hikite / opposite arm analyzer
  ↓
Rendering
  - debug snapshots
  - coaching snapshots later
  ↓
Reports
  - summary.json
  - analysis_results.json
  - report.md
```

## Source layers

- `vision/` owns low-level landmark extraction from video or image input.
- `detection/` owns strike and peak detection from landmark timelines.
- `references/` owns karate-facing reference points, such as impact point, chin reference, and Jodan target.
- `analyzers/` owns technique analyzers that classify or explain karate quality from domain-level references.
- `rendering/` owns snapshot rendering and visual debugging.
- `pipeline/` owns orchestration of end-to-end analysis runs.
- `reports/` owns human-readable and machine-readable output generation.

## Layer responsibility rules

- MediaPipe-specific index logic belongs only in vision/reference extraction modules.
- Technique analyzers must consume domain-level references, not raw MediaPipe indices.
- Renderers must not calculate technique status. They only visualize precomputed data.

## Current MVP flow

1. The CLI calls the pipeline with an input video and output directory.
2. The vision layer extracts raw landmark frames from MediaPipe Pose, Hands, and optional face landmarks.
3. The detection layer converts landmark timelines into grouped strike candidates and strike events.
4. Reference modules attach karate-facing points such as `impact_point`, `chin_reference`, and `jodan_reference`.
5. Technique analyzers attach status and explanation fields to the event analysis payload.
6. The rendering layer creates debug snapshots from selected analysis frames and precomputed event data.
7. The pipeline writes `summary.json`, `analysis_results.json`, and `report.md` output artifacts.

## Current known limitations

- Strike event selection still needs stabilization.
- Some events can be assigned to the wrong observed side.
- Some Jodan results are unknown because `impact_point` is missing at the selected frame.
- Face/chin reference may be unavailable when face landmarks are not detected.
- Pixel vs normalized result fields still need cleanup.
