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
