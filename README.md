Karate Kihon Analyzer

Offline Python engine for analyzing karate kihon videos using MediaPipe Pose.

The MVP focuses on one specific exercise:

- 10 alternating Jodan punches
- Side-view camera setup
- Maximum extension (impact frame) analysis
- Angle-based scoring against the practitioner's own chin height
- Annotated snapshot generation for every punch

All processing runs locally on the user's computer.

---

Vision

The long-term goal is to build a computer vision engine that can help karate practitioners improve their technique using only a smartphone or webcam.

The project starts with a small, well-defined MVP to validate that pose estimation can reliably analyze traditional karate kihon exercises.

Future versions may support:

- Chudan punches
- Gedan punches
- Speed drills
- Random targets
- Kicks
- Kata analysis
- Live mobile applications

---

MVP Scope (Locked v1.0)

The MVP supports:

- Jodan-zuki only
- Side-view video only
- Single practitioner
- Fixed camera position
- Offline analysis of recorded videos
- 10 alternating punches
- Left/right arm detection
- Impact frame extraction
- Angle-based scoring
- Annotated snapshot generation

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

Kihon Exercise

The supported drill is:

Yoi

Start:
Left arm extended
Right hand in Hikite

Jodan-zuki

1
Right punch
Left hand to Hikite

2
Left punch
Right hand to Hikite

3
Right punch
Left hand to Hikite

...

10
Left punch
Right hand to Hikite

The arms continuously alternate without resetting between punches.

---

Impact Frame Definition

The impact frame is defined as:

«The frame where the active punching arm reaches maximum extension and its forward movement stops.»

This frame becomes the single source of truth for:

- Angle analysis
- Snapshot generation
- Session reports
- Debugging

---

Scoring Model

The ideal Jodan target is the practitioner's own chin height.

The system creates an ideal line:

Active Shoulder → Chin

The actual punch line is:

Active Shoulder → Active Wrist

The angular difference determines the score.

Scoring Thresholds

Deviation| Result
0–3°| Perfect
3–7°| Good
7–12°| Acceptable
12°+| Miss

The analysis also determines whether the punch was:

- Too high
- Too low

---

Technology Stack

Language

- Python 3.12+

Computer Vision

- MediaPipe Pose

Video Processing

- OpenCV

Mathematical Operations

- NumPy

Image Rendering

- Pillow

CLI

- Typer

Data Models

- Pydantic

Progress Bars

- tqdm

Testing

- pytest

---

Project Structure

karate-kihon-analyzer/

├── input/
│   └── kihon-test.mp4
│
├── output/
│   ├── session.json
│   └── snapshots/
│       ├── punch-001-right-jodan.png
│       └── ...
│
├── src/
│   └── karate_analyzer/
│       ├── main.py
│       ├── video_reader.py
│       ├── pose_engine.py
│       ├── landmark_model.py
│       ├── punch_detector.py
│       ├── angle_analyzer.py
│       ├── snapshot_renderer.py
│       └── report_writer.py
│
└── tests/
    ├── test_angle_analyzer.py
    └── test_punch_detector.py

---

Planned Output

Session Report

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

Snapshot Images

Every punch produces an annotated image showing:

- Body landmarks
- Chin reference point
- Ideal punch line (green)
- Actual punch line (red)
- Deviation angle
- Result classification

---

Running the Analyzer

Planned command:

python -m karate_analyzer analyze input/kihon-test.mp4 --output output/

Example output:

Punch 1: Right Jodan - Good (4° low)
Punch 2: Left Jodan - Perfect (2° high)
...

Session complete

Perfect: 4
Good: 5
Acceptable: 1
Miss: 0

---

Development Roadmap

Phase 1 (Current MVP)

- Offline video analysis
- 10 alternating Jodan punches
- Impact frame extraction
- Angle-based scoring
- Snapshot generation

Phase 2

- Chudan support
- Gedan support
- Random targets
- Speed drills

Phase 3

- Real-time webcam analysis
- Desktop application
- Mobile application

---

Success Criteria

The MVP is considered successful if it can reliably:

1. Detect 10 alternating Jodan punches.
2. Identify the correct punching arm.
3. Capture the impact frame at maximum extension.
4. Calculate angular deviation from the ideal punch line.
5. Generate annotated snapshots.
6. Produce a complete session report.
7. Run entirely offline.

---

License

MIT License
