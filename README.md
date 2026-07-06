# Karate Kihon Analyzer

Karate Kihon Analyzer is the beginning of a reusable computer-vision analysis engine for karate training.

It currently starts as a pure Python analysis-engine prototype for a deliberately narrow kihon drill: 10 alternating Jodan punches represented by synthetic landmark data. The current code validates core angle analysis, punch sequencing, impact-frame selection, and session analysis without requiring video files, cameras, MediaPipe, or OpenCV. Full recorded-video analysis, snapshot rendering, and report writing are planned next steps.

All processing is intended to run locally on the user's computer.

---

## Product Vision

This project is not just a one-off script. It is intended to grow into a reusable karate analysis engine that can power multiple product experiences.

The project starts with an offline Python analyzer because that is the smallest practical way to validate the core computer-vision workflow. Over time, the same engine should be usable by:

- Recorded video analysis tools
- Desktop or webcam-based training tools
- Mobile apps
- Live coaching experiences

The MVP stays deliberately narrow so the project can prove the core analysis model before expanding into more techniques, devices, and user experiences.

Future versions may support:

- Chudan punches
- Gedan punches
- Speed drills
- Random targets
- Kicks
- Kata analysis
- Live mobile applications

---

## MVP Scope (Locked v1.0)

The MVP supports or targets:

- Jodan-zuki only
- Side-view video only
- Single practitioner
- Fixed camera position
- Pure analysis of synthetic landmark sessions now
- Offline analysis of recorded videos later
- 10 alternating punches
- Left/right arm sequence validation
- Impact frame extraction from landmark sequences
- Angle-based scoring
- Annotated snapshot generation later

The MVP intentionally excludes:

- Real-time analysis
- Mobile applications
- Chudan or Gedan targets
- Kicks
- Power measurement
- Guard hand analysis
- Hip rotation analysis
- Historical statistics

---

## Architecture

The project is organized around two conceptual layers: the Analysis Engine and the Product Layer.

### 1. Analysis Engine

The Analysis Engine owns karate-specific computer-vision and scoring behavior. Its responsibilities include:

- Angle-based scoring
- Punch sequence validation
- Impact-frame selection
- Synthetic session generation
- Session analysis
- Pose and landmark processing later
- Snapshot rendering later
- JSON/report generation later

Important rule: **the Analysis Engine must stay independent of UI technology.**

It should be usable from:

- CLI commands
- Desktop apps
- Mobile apps
- Automated tests

### 2. Product Layer

The Product Layer owns the user experience around the engine. Its responsibilities include:

- User interaction
- Training flow
- Camera setup guidance
- Displaying feedback
- Showing reports and snapshots

Important rule: **the Product Layer must not contain karate analysis logic.** It should call the Analysis Engine instead of reimplementing detection, scoring, or reporting rules.

### Architecture Diagram

```text
Synthetic Landmarks now
Recorded Video / Camera later
      │
      ▼
Pose Detection / Landmark Extraction later
      │
      ▼
Karate Analysis Engine
      │
      ├── Session Analysis
      ├── Snapshot Rendering
      └── JSON Report
      │
      ▼
Product Layer
      │
      ├── CLI
      ├── Desktop App
      └── Mobile App
```

---

## Design Principles

- Keep modules small.
- Keep analysis logic pure where possible.
- Separate input, analysis, and output.
- Keep UI dependencies out of the engine.
- Keep MediaPipe/OpenCV dependencies out of pure math modules.
- Synthetic tests should work without a camera or video files.

---

## Kihon Exercise

The supported drill is:

```text
Yoi

Start:
Left arm extended
Right hand in Hikite

Jodan-zuki

1. Right punch; left hand to Hikite
2. Left punch; right hand to Hikite
3. Right punch; left hand to Hikite
...
10. Left punch; right hand to Hikite
```

The arms continuously alternate without resetting between punches.

---

## Impact Frame Definition

The impact frame is defined as:

> The frame where the active punching arm reaches maximum extension and its forward movement stops.

This frame becomes the single source of truth for:

- Angle analysis
- Snapshot generation
- Session reports
- Debugging

---

## Scoring Model

The ideal Jodan target is the practitioner's own chin height.

The system creates an ideal line:

```text
Active Shoulder → Chin
```

The actual punch line is:

```text
Active Shoulder → Active Wrist
```

The angular difference determines the score.

`deviation_degrees` uses a signed value to show direction relative to the ideal shoulder-to-chin line:

- Positive values mean the actual punch line is above the ideal line, so the punch is too high.
- Negative values mean the actual punch line is below the ideal line, so the punch is too low.
- `0` means the actual punch line matches the ideal line.

The scoring thresholds use the absolute value of `deviation_degrees`, while the sign preserves whether the punch was high or low.

| Deviation | Result |
| --- | --- |
| 0° <= deviation <= 3° | Perfect |
| 3° < deviation <= 7° | Good |
| 7° < deviation <= 12° | Acceptable |
| deviation > 12° | Miss |

The analysis also determines whether the punch was too high or too low.

---

## Testing Strategy

Testing is intended to grow in stages:

1. Pure unit tests for math and sequence logic.
2. Synthetic session tests for full analyzer logic.
3. Synthetic snapshot rendering tests.
4. Recorded video tests with MediaPipe.
5. Real-world mobile/live testing later.

Synthetic sessions are important because they make the project testable without:

- Camera
- Phone
- MediaPipe
- OpenCV
- Real videos

This keeps the core engine easy to test in local development, CI, and AI-assisted coding workflows.

---

## Current Development Status

Implemented:

- Angle analyzer
- MVP punch sequence
- Impact frame selector
- Synthetic session generator
- Session analyzer
- Placeholder CLI

Not yet implemented:

- Snapshot renderer
- JSON report writer
- Video reader
- MediaPipe pose extraction
- Real recorded video pipeline
- Full CLI pipeline

---

## Technology Stack

- **Language:** Python 3.12+
- **Computer Vision:** MediaPipe Pose planned for real video landmark extraction
- **Video Processing:** OpenCV planned for video input
- **Mathematical Operations:** NumPy
- **Image Rendering:** Pillow planned for annotated snapshots
- **CLI:** Typer
- **Data Models:** Pydantic
- **Progress Bars:** tqdm
- **Testing:** pytest

---

## Project Structure

### Current active implementation

These are the modules and tests that contain the current working pure analysis-engine prototype.

```text
karate-kihon-analyzer/
├── README.md
├── pyproject.toml
├── src/
│   └── karate_analyzer/
│       ├── main.py
│       ├── angle_analyzer.py
│       ├── punch_sequence.py
│       ├── impact_frame_selector.py
│       ├── synthetic_session.py
│       └── session_analyzer.py
└── tests/
    ├── test_angle_analyzer.py
    ├── test_punch_sequence.py
    ├── test_impact_frame_selector.py
    ├── test_synthetic_session.py
    └── test_session_analyzer.py
```

`main.py` currently contains a placeholder CLI command. It does not run the full analysis pipeline yet.

### Planned later

These files and capabilities are planned for future implementation and should not be treated as complete product behavior yet. Some may exist only as placeholders.

```text
src/karate_analyzer/
├── snapshot_renderer.py
├── report_writer.py
├── video_reader.py
├── pose_engine.py
├── landmark_model.py
└── punch_detector.py

tests/
└── test_punch_detector.py
```

---

## Planned Output Layer

### Session Report

```json
{
  "video": "kihon-test.mp4",
  "total_punches": 10,
  "punches": [
    {
      "number": 1,
      "expected_arm": "right",
      "detected_arm": "right",
      "result": "good",
      "deviation_degrees": -4.2,
      "snapshot": "snapshots/punch-001-right-jodan.png"
    }
  ]
}
```

### Snapshot Images

Every punch should produce an annotated image showing:

- Body landmarks
- Chin reference point
- Ideal punch line (green)
- Actual punch line (red)
- Deviation angle
- Result classification

---

## Development Setup

Create and activate a Python 3.12 virtual environment:

```bash
python3.12 -m venv .venv
source .venv/bin/activate
python -m pip install --upgrade pip
```

Install project dependencies:

```bash
python -m pip install -e .
```

Run the test suite:

```bash
python -m pytest
```

---

## Running the Analyzer

The CLI exists, but the full recorded-video pipeline is not implemented yet.

Current useful command:

```bash
python -m pytest
```

Planned future command:

```bash
python -m karate_analyzer analyze input/kihon-test.mp4 --output output/
```

Example future output:

```text
Punch 1: Right Jodan - Good (-4° low)
Punch 2: Left Jodan - Perfect (+2° high)
...

Session complete

Perfect: 4
Good: 5
Acceptable: 1
Miss: 0
```

---

## Development Roadmap

### Phase 1A: Pure Analysis Engine - Current

Implemented or in progress:

- Angle analyzer
- Punch sequence
- Impact frame selector
- Synthetic session generator
- Session analyzer

Goal:

Prove that the core analysis logic works without camera, video files, MediaPipe, or OpenCV.

### Phase 1B: Output Layer - Next

Planned:

- Snapshot renderer
- JSON report writer
- CLI command that runs a synthetic session and writes output

Goal:

Create visible/debuggable outputs from the existing pure analysis engine.

### Phase 1C: Real Video Input

Planned:

- Video reader
- MediaPipe pose engine
- Landmark extraction
- Real recorded video analysis

Goal:

Replace synthetic input with real pose landmarks from recorded side-view karate videos.

### Phase 2: Expanded Kihon Analysis

Planned:

- Chudan support
- Gedan support
- Random target calls
- Speed drills

### Phase 3: Product Experiences

Planned:

- Desktop/webcam trainer
- Mobile app
- Live coaching workflows

---

## Success Criteria

The current pure analysis-engine milestone is considered successful if it can reliably:

1. Validate a 10-punch alternating Jodan sequence from synthetic landmarks.
2. Identify the expected punching arm for each punch.
3. Select the impact frame at maximum extension.
4. Calculate angular deviation from the ideal punch line.
5. Run entirely offline without camera, video files, MediaPipe, or OpenCV.

Future MVP milestones add annotated snapshots, complete session reports, and recorded-video analysis.

---

## License

MIT License.
