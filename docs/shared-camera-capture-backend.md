# Shared Camera Capture backend

Status: implemented in local Android source on 2026-09-15; physical-device
acceptance remains pending.

This implementation follows the supplied `shared-camera-capture-backend-guide.md`
and its companion UI guide. It establishes one CameraX and persistence path for
Record & Analyze video and the Ready? — Osu front-camera photo. The current work
implements the backend and integrates the existing caller screens; a broader
visual redesign of Record & Analyze remains separate.

## Contract and ownership

`sharedcapture/SharedCaptureContract.kt` defines independent capture type,
trigger, cue, camera, countdown, quality, auto-stop and semantic prompt choices.
It also defines a separate UI capability policy, allowing structured callers to
preset, lock or hide options. `SELF_CUED` and `FREE_AUTO_COUNT` are present but
return a clear start block until their engines exist.

`SharedCameraCaptureBackend` is the only production class that owns
`ProcessCameraProvider`, preview, `VideoCapture`, `ImageCapture`, lens selection,
quality selection, zoom, tap focus, AE lock, target rotation and CameraX cleanup.
The legacy video adapter and Ready? — Osu runner both delegate to it.

`CapturePersistenceCoordinator` creates canonical app-private media names before
hardware starts, saves the request/session association, validates finalized media,
commits the durable outcome, and returns `PersistedCaptureResult`. The primary
caller contract is capture/session identity and outcome; raw paths remain only as
compatibility details for older guided code.

## Lifecycle

Record & Analyze remains video-only and does not request microphone permission.
It reflects Android's active local media route and offers a Connect audio device
handoff to Android Bluetooth settings without persisting a selected device.
Its normal finite `APP_CUED` set emits and persists cue timestamps, then stops at:

`last cue timestamp + 1.5 × requested cadence`

The first manual Stop persists `STOP_REQUESTED` and uses the same graceful tail.
A second touch persists `FORCE_STOP_REQUESTED` and finalizes promptly. The voice
stop hook supports graceful stop only. Back, background, lock and unexpected
CameraX finalization are persisted as interruption boundaries; usable finalized
media remains evidence with an interrupted outcome.

Ready? — Osu requests `PHOTO + VOICE + FRONT`, while voice recognition remains
owned by its activity runner. A recognized command triggers the shared backend,
which writes `files/training/photos/<capture-id>.jpg` and records a durable photo
result. The shared Activity Shell remains modality-neutral and passive/Ready pages
keep camera and microphone hardware off.

## Processing boundary

Recorder success ends after finalized media and Room metadata commit. The camera
adapter and persistence coordinator contain no WorkManager or processing-queue
dependency. They publish a durable-media event; the processing subsystem schedules
independently and discovers eligible finalized videos from Room. Photos never
create landmark jobs. Landmark or analysis failure cannot turn a saved capture
into a failed recording.

## Remaining acceptance

Automated JVM tests cover request validation, unsupported future modes, app-cued
timing, graceful/force stop, durable photo identity, failed structural media,
interrupted outcomes, Room migration and architecture boundaries. Native camera,
orientation, Bluetooth-route, interruption and actual media checks still require
a connected representative Android device.
