# Guided Jodan Clip Recorder

## Purpose

The Guided Jodan Clip Recorder is an app-side session foundation for phone-based
Jodan training. Its MVP guides a practitioner through a 10-punch session, speaks
or counts prompts, records one separate clip per punch, and saves structured
session metadata.

This milestone uses fake services only. It does not include Android camera APIs,
real text-to-speech, real speech recognition, MediaPipe, or analyzer integration.

## App/analyzer boundary

The app owns guided session orchestration:

- guided session flow
- voice prompts
- listening windows
- user commands
- recording control
- clip naming
- session metadata
- stop and pause flow

The analyzer owns technique analysis:

- exact impact frame
- reference points
- measurements
- Jodan analysis
- good / too high / too low result
- debug rendering and reporting

The app package must remain separate from `karate_analyzer`. Guided recorder code
lives under `src/karate_app/` and must not import analyzer internals.

## MVP flow

1. Speak: "Stand in ready position. Place the phone so your full body is visible. Say osu when ready."
2. Wait for `OSU` or `STOP`.
3. On `OSU`, speak "Yoi."
4. Run a fake baseline capture phase.
5. For each of 10 planned strikes:
   - speak the Japanese count
   - record one fake clip
   - save clip metadata
   - speak "Clip X saved."
6. Speak a completion summary, such as "Session complete. 10 clips saved."
7. On `STOP`, return a partial result and speak a stop summary, such as "Session stopped. 4 clips saved."
8. On capture timeouts, speak "Punch not detected. Try again." and retry the same planned strike up to `max_retries_per_strike` times. The default retry limit is 2.
9. After the retry limit, speak "Skipping this strike.", save no clip for that planned strike, and continue deterministically to the next strike.
10. On technical capture failures, return an incomplete result with `stopped_by_user = false` so app UI can distinguish failure from a user stop.

## Session commands

- `OSU`
- `STOP`
- `REPEAT`
- `PAUSE`
- `CONTINUE`
- `UNKNOWN`

## Session states

- `IDLE`
- `SETUP`
- `WAITING_FOR_OSU`
- `YOI`
- `BASELINE_CAPTURE`
- `PROMPTING_STRIKE`
- `CAPTURING_STRIKE`
- `STRIKE_COMPLETE`
- `STOPPED`
- `COMPLETE`

## Clip naming

The Jodan MVP plan records 10 clips with alternating right/left expected sides:

| Strike | Count | Side | File name |
| --- | --- | --- | --- |
| 1 | Ichi | right | `strike_001_right.mp4` |
| 2 | Ni | left | `strike_002_left.mp4` |
| 3 | San | right | `strike_003_right.mp4` |
| 4 | Shi | left | `strike_004_left.mp4` |
| 5 | Go | right | `strike_005_right.mp4` |
| 6 | Roku | left | `strike_006_left.mp4` |
| 7 | Shichi | right | `strike_007_right.mp4` |
| 8 | Hachi | left | `strike_008_left.mp4` |
| 9 | Ku | right | `strike_009_right.mp4` |
| 10 | Ju | left | `strike_010_left.mp4` |

## Recording Adapter Contract

The app has three layers between the guided flow and a saved video file:

1. **Guided session orchestrator**
   Owns the session flow, setup prompt, Japanese counts, retry/stop behavior, and metadata writing.
2. **Strike capture controller**
   Owns clip boundary logic:
   - fixed duration now
   - later MediaPipe movement detection for app-side capture timing only
3. **Recording adapter**
   Owns actual video recording:
   - CameraX later on Android
   - fake adapter now in tests

Text diagram:

```text
GuidedJodanSessionOrchestrator
→ StrikeCaptureController
→ RecordingAdapter
→ MP4 file
```

`RecordingAdapter.start_recording(...)` will map to CameraX video capture start in the future Android app. `RecordingAdapter.stop_recording(...)` will map to CameraX stop/finalize recording. `RecordingAdapter.cancel_recording(...)` will map to cancel/discard the current recording.

The `RecordingAdapter` is intentionally low-level and generic. It may start, stop, or cancel recording, report timestamps, and return saved file details. It does not analyze karate, detect an impact frame, evaluate Jodan height, score technique, use analyzer code, or know karate rules.

## Future real services

Future app service adapters may replace the fake implementations with:

- Android TextToSpeech
- Android speech recognition
- CameraX recorder
- lightweight MediaPipe capture detector
- analyzer adapter

Future app-side MediaPipe usage is only for capture control:

- movement started
- elbow angle increasing
- wrist extension increasing
- full-extension candidate
- post-roll complete

It must not decide:

- exact impact frame
- good / too high / too low
- Jodan score

## Strike Capture Controller

The Strike Capture Controller is an app-side abstraction for recording one clean
clip for each planned strike. It controls when recording starts, when the app is
waiting for movement, when a strike appears to be progressing, when a likely
completion candidate has been reached, how much post-roll to keep, and when to
return a terminal capture result.

The controller may decide when enough video has been captured. It must not decide
the exact impact frame, Jodan height result, good / too high / too low result, or
technique score. Those remain analyzer responsibilities.

The controller contract models:

- capture modes: `FIXED_DURATION`, `LIGHTWEIGHT_POSE`, `MANUAL`, and `FAKE`
- capture states such as `RECORDING_PREROLL`, `WAITING_FOR_MOVEMENT`,
  `STRIKE_IN_PROGRESS`, `POSSIBLE_IMPACT_DETECTED`, `POST_ROLL`, `CLIP_READY`,
  `NO_MOVEMENT_TIMEOUT`, `INCOMPLETE_STRIKE_TIMEOUT`,
  `ACTIVE_STRIKE_TIMEOUT`, `CANCELLED`, and `FAILED`
- capture events such as `PROMPT_STARTED`, `RECORDING_STARTED`,
  `MOVEMENT_STARTED`, `PROGRESS_DETECTED`, `POSSIBLE_IMPACT`,
  `POST_ROLL_COMPLETE`, timeout events, cancel events, and failure events
- capture configuration for fixed duration, per-strike retry limit, movement timeout, active strike
  safety timeout, progress stall timeout, post-roll, and the minimum elbow-extension
  angle for future lightweight pose capture
- capture results with rough timing metadata, cancellation status, and diagnostics

For this milestone, fake services implement only fake/fixed-duration behavior.
The fake capture controller returns `CLIP_READY` with `capture_reason =
"fixed_length_fake_capture"` and can be scripted to return timeout, cancellation,
or failure states in tests. No Android camera, CameraX, real MediaPipe, or
analyzer integration is included.

In the real Android app, `STOP` must be able to cancel an active capture. This is
represented now by `cancel_capture()`, the `CANCELLED` capture state, and
`capture_reason = "cancelled_by_user"`; it is not implemented as real async
camera cancellation yet.

### Future lightweight pose capture logic

A future `LIGHTWEIGHT_POSE` app implementation can use coarse pose signals only
to control clip boundaries:

1. Start recording just before the count prompt.
2. Say the count, such as “Ichi”.
3. Wait for movement. Movement may be elbow angle increasing or wrist extension
   increasing.
4. If no movement starts within `waiting_for_movement_timeout_ms`, return
   `NO_MOVEMENT_TIMEOUT`.
5. Once movement starts, reset into the active strike timer and track progress.
6. Progress means elbow angle increasing toward full extension or wrist extension
   increasing.
7. If progress stalls before completion, return `INCOMPLETE_STRIKE_TIMEOUT`
   after `progress_stall_timeout_ms`.
8. If the active strike never reaches completion before
   `active_strike_timeout_ms`, return `ACTIVE_STRIKE_TIMEOUT` as an overall
   safety timeout.
9. Treat completion as a candidate when elbow angle is near 160–180 degrees, wrist
   extension is near peak, or there is a plateau / turning point.
10. After the completion candidate, record `post_roll_ms`, stop the clip, and
    return `CLIP_READY`.

The analyzer later finds the exact impact frame and Jodan result.
