# Segmenter Integration v1 — Validation

## Purpose

Validate that segmentation works on real recordings and that the persisted
movement intervals represent the movements we actually want future analyzers to
consume.

Automated tests alone are not sufficient.

## Automated validation

Cover at least:

- existing landmark track → segmentation succeeds;
- processing continues automatically from landmark-ready state;
- movement records are persisted;
- persisted segments survive repository/database reopen;
- logical start and end are valid;
- retained start and end contain the logical interval;
- retained intervals may overlap without changing logical ownership;
- actual detected count may differ from planned repetitions;
- zero movements does not fabricate results;
- segmentation failure preserves upstream recording/landmark evidence;
- retries do not create duplicate movements;
- only appropriate cue events reach cue association;
- processing stage/state transitions are valid; and
- processing timing is recorded.

## Real recording validation

Use real continuous straight-punch recordings. Do not tune only against
synthetic fixtures.

For each recording, inspect:

- full master video;
- detected movement count;
- each logical start;
- each logical end;
- retained playback interval;
- false movements;
- missed movements;
- merged movements; and
- split movements.

The planned repetition count is reference information only. It must not be used
as ground truth by the segmenter.

## Boundary review

For each detected movement, answer the following questions.

### Start

- Is the body already moving before the detected start?
- Is unrelated preparation incorrectly included?
- Is the beginning of the punch missing?

### End

- Has the meaningful movement finished?
- Is the end cut off?
- Does the segment include excessive stillness?
- Is motion toward the next repetition being included?

## Suggested diagnostic representation

For each movement, expose something similar to:

```text
Movement 4

Logical start: 8.470 s
Logical end: 9.020 s
Duration: 0.550 s

Playback start: 8.170 s
Playback end: 9.320 s

Segmentation version: …
Associated cue: optional
Preferred/peak frame: optional
```

The exact UI is not prescribed. The information must be accessible enough to
debug the segmenter.

## Count mismatch cases

Explicitly test:

- Planned 10 / Detected 10
- Planned 10 / Detected 9
- Planned 10 / Detected 11

The system must preserve the detected result. Do not trim or add movements to
force the requested count.

## Completion

Do not mark the implementation validated merely because ten movements were
found. Validation requires reasonable movement boundaries.

The output is intended to become the canonical input for future punch analyzers.
