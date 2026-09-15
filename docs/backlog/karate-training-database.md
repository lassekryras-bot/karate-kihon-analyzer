# Karate training database foundation

Status: IN PROGRESS

## Agreed requirement

Establish Room version 1 before real punch history accumulates. Store structured
user/session/master-recording/movement/analysis/result evidence and its labels,
cues, observed geometry, body-measurement and calibration provenance. Keep MP4
and dense landmarks as files. Use integer recording-relative microseconds,
append-only analysis/reference history, deliberate foreign keys, transactions,
exported schemas and a non-destructive migration path. History and coverage
are derived; do not add bucket tables, coverage caches, cloud sync, per-movement
MP4s or a generalized artifact/event framework.

## Implemented locally

The schema, repository, recording/segmentation integration, landmark retention,
versioned analyzer-result persistence, history policy and tests are described in
[Android training evidence database](../app-training-database.md).
The [validation record](../validation/training-database-v1.md) records 32 passing
focused tests, successful APK builds, and the two pre-existing full-suite failures.

The existing Android punch-height analyzer is integrated with explicit
partial/abstained evidence. Python wrist-path analyzers are not ported, and no
provisional result is relabelled as centimetres or a valid dynamic assessment.

## Acceptance still requiring a device

- Record one continuous ten-punch drill through CameraX and run MediaPipe.
- Inspect 1 user, 1 session, 1 master recording, a usable landmark track, cues,
  actual movements, observations, labels, analysis runs and measurement rows.
- Confirm the UI derives its count and retrieve displayed Punch 5 via
  `movementEvidence(sessionId, 5)` with the correct video interval and sources.
- Verify source deletion preserves historical results and retained landmarks.
- Kill the process at recording finalization, landmark publication,
  segmentation and analysis checkpoints; inspect recovery without duplicated
  movement identities or lost completed results.
- Run the instrumentation migration baseline and check Stop/background/late
  callbacks on hardware.

There is no connected Android device in the current implementation session.
Keep this task IN PROGRESS until these checks are completed; automated fixtures
do not constitute a new physical ten-punch recording.
