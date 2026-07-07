# Karate Kihon Analyzer

## What this project is

Karate Kihon Analyzer is an explainable computer-vision prototype for analyzing a narrow karate kihon drill from recorded video.

The project focuses on one deliberately small exercise first: 10 alternating Jodan punches from a fixed side-view camera. The goal is not to produce a black-box score, but to create inspectable strike events, reference points, analysis results, and annotated snapshots that explain what the system detected.

All processing is intended to run locally. The project is built around recorded-video analysis, offline processing, explainability-first outputs, a narrow MVP scope, and visual debugging/coaching snapshots.

## Current MVP

The current MVP can:

- read a recorded kihon video
- extract pose and optional hand/face landmarks through MediaPipe
- detect high-extension arm regions
- build strike event candidates for a 10-punch Jodan drill
- attach karate-facing reference points such as `impact_point` and `jodan_reference`
- run the Jodan height analyzer
- render annotated strike snapshots
- write `summary.json`, `analysis_results.json`, and `report.md`

This is still experimental. The output is useful for development, inspection, and local coaching-debug workflows, but it should not be treated as a final scoring product.

## How to run it

From the project root on Windows PowerShell:

```powershell
py -3.12 -m venv .venv
.\.venv\Scripts\python.exe -m pip install --upgrade pip
.\.venv\Scripts\python.exe -m pip install -e ".[test]"
```

Run the analyzer:

```powershell
.\.venv\Scripts\python.exe -m karate_analyzer.main analyze input/videos/kihon-test.mp4 --output output/run-001
```

On Unix/macOS:

```bash
python3.12 -m venv .venv
source .venv/bin/activate
python -m pip install --upgrade pip
python -m pip install -e ".[test]"
python -m karate_analyzer.main analyze input/videos/kihon-test.mp4 --output output/run-001
```

Replace `input/videos/kihon-test.mp4` with the recorded kihon video you want to analyze. Replace `output/run-001` with a new output directory for each run.

## Output files

A run writes generated files under the selected output directory:

```text
output/run-001/
    video_landmarks.json
    extension_by_frame.json
    extension_by_frame.csv
    candidate_peak_frames.json
    grouped_peak_frames.json
    punch_event_candidates.json
    punch_event_landmarks.json
    analysis_results.json
    summary.json
    report.md
    rendered-strikes/
    extracted-frames/
```

The most useful files are:

- `summary.json` – compact run summary and counts
- `analysis_results.json` – cleaned per-strike analysis output
- `report.md` – human-readable report
- `rendered-strikes/` – annotated strike snapshots
- `punch_event_landmarks.json` – detailed event/debug payload

These files are generated artifacts. They should be reproducible from the source video and analysis steps, not edited as permanent source files.

## Project structure

The source package is grouped by architecture layer:

- `vision/` – low-level landmark extraction from video/image input
- `detection/` – strike and peak detection from landmark timelines
- `references/` – karate-facing reference points such as impact point and Jodan target
- `analyzers/` – technique analyzers that classify or explain karate quality
- `rendering/` – snapshot rendering and visual debugging
- `pipeline/` – orchestration of end-to-end analysis runs
- `reports/` – human-readable and machine-readable output generation

See `docs/architecture.md` for the full architecture outline.

```text
src/karate_analyzer/
├── main.py
├── vision/
├── detection/
├── references/
├── analyzers/
├── rendering/
├── pipeline/
└── reports/
```

Supporting pure-analysis modules such as `angle_analyzer.py`, `punch_sequence.py`, `impact_frame_selector.py`, `synthetic_session.py`, and `session_analyzer.py` remain at the package root while the recorded-video MVP architecture continues to stabilize.

## Architecture principles

The project follows a few important rules:

- Vision modules may know about MediaPipe and raw landmark indices.
- Reference modules convert raw landmarks into karate-facing reference points.
- Technique analyzers consume domain-level references, not raw MediaPipe indices.
- Renderers do not calculate technique status; they visualize precomputed analysis.
- The pipeline orchestrates existing modules but should not contain scoring logic.
- Generated artifacts should be reproducible from the source video and analysis steps.

## Kihon drill assumptions

The MVP assumes a fixed kihon drill:

```text
Yoi

Start:
Left arm extended
Right hand in hikite

Jodan-zuki

1. Right punch
2. Left punch
3. Right punch
...
10. Left punch
```

The arms alternate continuously. The exercise is currently expected to be recorded from a fixed side-view camera with a single practitioner.

## Development setup

Install development dependencies:

```bash
python -m pip install -e ".[test]"
```

Run tests:

```bash
python -m pytest
```

## Testing

The test suite uses unit tests and monkeypatched integration tests so most behavior can be verified without requiring real videos, cameras, or MediaPipe runtime availability.

Recorded-video smoke tests are useful locally, but they are not the only way to validate the code.

## Current limitations

Current known limitations:

- Strike event selection is still being stabilized.
- Some selected frames may not contain a usable hand `impact_point`.
- Jodan height analysis is experimental.
- Face/chin references may be unavailable depending on MediaPipe model support.
- Some result fields still mix normalized and pixel terminology.
- The renderer is currently a debug/coaching-development tool, not a final product UI.

## Future direction

Future work will focus on making the analysis more reliable and more explainable before expanding to more techniques.

Near-term direction:

- stabilize strike event selection
- improve confidence and unknown-reason reporting
- improve debug snapshot readability
- add more technique analyzers only after the current Jodan flow is reliable

## License

MIT License.
