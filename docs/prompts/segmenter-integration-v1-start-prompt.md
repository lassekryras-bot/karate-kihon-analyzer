# Starter Prompt — Segmenter Integration v1

Implement Segmenter Integration v1 for the Karate Kihon Analyzer Android
application.

Use the accompanying
[`segmenter-integration-v1-requirements.md`](../backlog/segmenter-integration-v1-requirements.md)
and
[`segmenter-integration-v1-validation.md`](../backlog/segmenter-integration-v1-validation.md)
as the authoritative task requirements.

Before changing code, inspect the current repository on `main`.

The project has recently undergone substantial recording, training-database,
landmark-stream, CameraX, retrospective-segmentation, and processing work. Do not
assume older architectural descriptions are still accurate.

First identify and document the current flow from finalized Record & Analyze
video through:

- recording/session persistence;
- processing queue/coordinator;
- landmark processing;
- Movement Landmark Stream persistence;
- existing retrospective segmenter;
- `SessionMovement` persistence; and
- recording/history UI.

Then implement the smallest coherent change that connects the existing persisted
landmark pipeline to retrospective segmentation.

Important constraints:

- Reuse the existing retrospective segmenter.
- Reuse persisted landmark tracks.
- Do not create another MediaPipe video-processing pass when compatible
  landmarks already exist.
- Do not create separate user-visible queue jobs for landmarks and segmentation.
- Treat them as phases of one recording-processing job.
- For the first straight-punch processing plan, processing ends after
  segmentation.
- Persist the actual detected movements.
- Never force the detected count to equal the planned repetition count.
- Keep logical movement boundaries separate from retained video/playback
  buffers.
- Add a recording Segments page or view so persisted results can be inspected.
- Expose processing state suitable for QTray to show **Finding movements** and
  eventually **Segments ready**.
- Do not implement technique analyzers in this task.
- Do not expand this into the complete future versioning/reanalysis system.
- Preserve existing recording, camera, and landmark behavior unless a change is
  required for this integration.

Pay particular attention to cue handling.

Inspect which persisted session events are currently passed into the
retrospective segmenter's cue timeline. Only actual cue/count emission events
should be treated as cues. Do not accidentally convert unrelated session events
into cue events.

Add automated tests covering persistence, retry/idempotency, failure behavior,
count mismatch, zero movements, and processing-state progression.

Add or update documentation explaining the final processing flow.

Run the relevant Android/core test suites and build the debug APK.

If a physical Android device is available, perform real-device validation.
Otherwise, clearly identify the remaining physical-device acceptance checks
rather than claiming they were completed.

Keep the implementation focused on this milestone:

> Record → landmarks → segment → persist movements → inspect segments.

Do not proceed into punch-height, speed, deviation, hikite, impact, cadence, or
coaching analysis.
