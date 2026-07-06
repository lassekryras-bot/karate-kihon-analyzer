# Snapshot Renderer Design

## Purpose

The Snapshot Renderer is the first presentation layer of the Karate Analysis Engine.

Its responsibility is visualization only.

It must never perform karate analysis, pose calculations, scoring, or any mathematical processing.

It receives completed analysis results from the Analysis Engine and renders them into visual output.

---

## Design Philosophy

The renderer is intentionally separated from the analysis engine.

```text
Video
      │
      ▼
Pose Detection
      │
      ▼
Landmark Extraction
      │
      ▼
Session Analyzer
      │
      ▼
PunchAnalysis
      │
      ├──────────► Snapshot Renderer
      ├──────────► JSON Report
      ├──────────► Flutter UI
      └──────────► Desktop UI
```

Every consumer should receive the same analysis data.

The renderer must never recompute or modify analysis results.

---

## Responsibilities

The Snapshot Renderer is responsible for:

- Drawing landmarks
- Drawing reference lines
- Drawing actual punch lines
- Displaying text
- Producing PNG images
- Future rendering targets (SVG, Canvas, PDF, etc.)

The Snapshot Renderer is not responsible for:

- Angle calculations
- Punch detection
- Impact-frame selection
- Classification
- MediaPipe
- Video processing

---

## Renderer Input

The renderer should receive a fully analyzed `PunchAnalysis` object.

The renderer should not require additional calculations.

Everything needed to render the snapshot should already exist inside the analysis result.

---

## First Rendering Target

The MVP renderer will generate a PNG image.

The image will use a simple synthetic canvas.

No real camera frame is required for the first implementation.

---

## MVP Rendering Elements

Draw:

- Shoulder point
- Chin point
- Wrist point
- Ideal punch line (shoulder → chin)
- Actual punch line (shoulder → wrist)

Display text:

- Punch number
- Expected punch
- Classification
- Deviation
- Direction
- Impact frame number

Nothing else.

---

## Rendering Layers

The renderer should internally be organized into layers.

Suggested layers:

```text
Background Layer

Pose Layer
    Shoulder
    Chin
    Wrist

Reference Layer
    Ideal line

Analysis Layer
    Actual line

Text Layer
    Score
    Classification
    Deviation

Debug Layer
    Optional future overlays
```

Keeping these layers separate makes future rendering easier.

---

## Future Rendering Targets

The renderer should eventually support multiple outputs.

Possible targets:

- PNG
- SVG
- Flutter Canvas
- HTML Canvas
- PDF
- Desktop debug window

The rendering API should remain independent of the output format.

---

## Design Rules

The renderer should always:

- Consume analysis results
- Never modify analysis results
- Never perform karate calculations
- Never depend on MediaPipe
- Never depend on OpenCV
- Be deterministic

---

## Long-Term Vision

The Snapshot Renderer is the first visualization component of the Karate Analysis Engine.

Future user interfaces—including the desktop application and Flutter mobile app—should use the same analysis objects and present the same information in different ways.

The goal is that every presentation layer shares a single source of truth: the analysis produced by the engine.
