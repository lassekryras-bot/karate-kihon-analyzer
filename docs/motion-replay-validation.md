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
