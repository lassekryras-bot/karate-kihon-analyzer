# Motion replay validation

The capture-motion validation path is deliberately offline and deterministic:

```text
pose-motion-replay-v1 JSON
  -> PoseReplayJson
  -> PoseMotionObservationExtractor
  -> GenericMotionSegmenter
  -> MotionReplayTraceJson
  -> optional Python diagnostic plot
```

`MotionReplayRunner` creates fresh extractor and segmenter instances for every
fixture/configuration pair. `CaptureSafetyScorer` keeps severe clipping and
premature-completion failures separate from boundary offsets. `sweep` accepts a
curated list of named `MotionReplayParameterSet` values; it is intentionally not
an optimizer or workflow framework.

## Fixture and trace formats

`PoseReplayJson` reads and writes the narrow `pose-motion-replay-v1` schema. It
preserves normalized and world coordinates, visibility, presence, landmark
source, timestamps, cue/arming/deadline metadata, expected end-pose relationship,
reviewed labels, intervals, and notes. The Kotlin models remain the normative
schema for this development-only format.

`MotionReplayTraceJson` exports `motion-replay-trace-v1`. Each row contains the
two motion channels, slow displacement, coverage alternatives, similarities,
extractor status, regional diagnostics, segmenter state, dwell values, and event
flags. The trace also includes transition decision/boundary timestamps, labels,
final outcome, and the asymmetric score.

Render a trace with:

```bash
python -m karate_analyzer.diagnostics.replay_motion_plots trace.json plot.png
```

The plotter visualizes exported decisions but does not calculate motion or run
the segmenter.

## Fixtures and privacy

Committed tests currently use synthetic pose sequences only. They exercise
camera translation/scale, arm-only and slow motion, mirroring, multi-burst
movement, occlusion, timestamp gaps, and outlier handling without containing a
person's video or captured landmarks.

Private or consented local fixtures can be stored under `input/`; that directory
is ignored except for its placeholders. Decode them with `PoseReplayJson.decode`
and pass the result to `MotionReplayRunner`. Write generated traces and plots
under `output/`, which is also ignored. Do not commit video or captured landmark
data without an explicit repository privacy decision.

The current parameters and results are provisional until representative,
manually reviewed real sequences are available. Live CameraX/audio integration
is outside this harness.

## Importing an existing analyzer landmark export

`karate_analyzer.diagnostics.pose_replay_import` converts the analyzer's
`video_landmarks.json` shape directly to `pose-motion-replay-v1`; it does not run
MediaPipe, resample, reorder, or smooth observations. It maps MediaPipe's numeric
landmark order to `PoseLandmarkId`, retains normalized/world coordinates and
integer millisecond timestamps, and marks cached MediaPipe values as
`LandmarkSource.OBSERVED`. If visibility or presence is absent, the importer
uses `0.0` and records a warning so missing confidence becomes UNKNOWN evidence
rather than fabricated stillness.

For the private 10-punch fixture, run:

```bash
python -m karate_analyzer.diagnostics.pose_replay_import \
  'input/private/video_landmarks(1).json' \
  output/task5/real-kihon-10-punch.fixture.json \
  --sequence-id real-kihon-10-punch-1000002073-v1 \
  --summary output/task5/import-summary.json \
  --review-proposal output/task5/proposed-labels.json
```

The optional proposal contains the known theoretical-impact frames only as
review context. Movement boundaries remain empty and `review_status` remains
`PROPOSED`; human video/trace review must establish full movement start, end,
terminal-stable, censoring, and ambiguity labels before a score is treated as a
validation result.

The full `video_landmarks(1).json` / `1000002073.mp4` asset is not committed. If
it is unavailable in a working copy, real replay results, plots, ten-punch
boundaries, and confirmation that both names identify the same recording cannot
be produced honestly. The converter and synthetic contract tests remain safe to
commit independently of that private asset.
