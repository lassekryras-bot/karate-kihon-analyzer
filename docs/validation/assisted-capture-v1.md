# Assisted capture v1 validation

Date: 2026-09-14. Implemented locally on the existing uncommitted training
database foundation. Debug build subsequently installed on a Galaxy A55;
full physical recording acceptance is still pending.

## Implementation by layer

- **UI:** Skill Coach Record & Analyze opens a dedicated camera page with
  remembered count/cadence/counting settings, persistent touch Record/Stop,
  explicit statuses, saved-recording selection, full MP4 playback and a debug
  landmark overlay. Content scrolls on compact screens.
- **Recording:** shared CameraX adapter commits session/master before countdown,
  starts at zero, anchors cues to actual Start +0.5 seconds, repeats 1–10 and
  records until Stop. Immediate successful sound requests become cue events.
  Stop/background/finalize cancel cue scheduling. No microphone is requested or
  enabled. Finalized MP4 verification rejects audio tracks and unreadable video.
- **Storage:** private UUID-only MP4/MLS names and relative training references.
  Explicit legacy absolute references remain readable/deletable. Successful MP4
  evidence remains available after extraction failure.
- **Room:** v2 migration preserves the v1 graph and adds nullable session setup
  snapshots and stream format metadata. Existing user identity is reused.
- **MLS:** new self-identifying version/interpretation header; existing frame
  payload preserves image/world positions, confidence, identity/source and time.
  The shared processor has an extraction-only entry that creates no movements,
  labels, analyses or measurements. Valid persisted tracks avoid another decode.
- **Tests:** timing/state tests, storage/reopen/recovery tests, binary validation,
  compatibility, preference and architecture checks; existing foundation tests
  retained. The unsupported-version test now uses version 3 because 2 is current.

## Results

| Check | Result |
| --- | --- |
| Focused assisted/training/profile/guided suite | **55 passed**, no failures/skips |
| Full app unit suite | **246 executed**, 244 passed, 2 pre-existing failures |
| Analyzer core tests | **254 passed**, no failures/skips |
| MediaPipe hand adapter tests | **23 passed**, no failures/skips |
| MediaPipe pose adapter tests | **4 passed**, no failures/skips |
| Combined full run | **527 executed**, 525 passed, 2 pre-existing failures |
| Debug app APK | Built successfully |
| Debug instrumentation APK | Built successfully |
| v1→v2 exported-schema migration | Passed under Robolectric; user/session/master/legacy-track graph retained |
| Whitespace | `git diff --check` passed; new text files checked separately |
| Device instrumentation | **1 migration test passed** on Galaxy A55 / Android 16 |
| Physical recording acceptance | Pending; technical checks completed first at user request |

The focused run preceded a layout-only change that made content scrollable and
kept Record/Stop fixed. The subsequent full run tested and built that final source;
all 55 focused tests also passed within it.

The two full-suite failures are unchanged from the
[foundation validation](training-database-v1.md):

- `HomeStartupArchitectureTest.trainModeChooserIsPassiveAndSeparateFromLearningCatalogue`
  expects two old Skill Coach placeholder callbacks; the baseline already has
  the concrete Skill Coach workspace.
- `LearningArtworkArchitectureTest.continueCardUsesTheLearningPathsStableEnsoAndResponsiveProgressLayout`
  expects a `Continue learning` literal absent from the baseline HomeScreenView.

Neither assertion was weakened or deleted. The aggregate Gradle command exits
unsuccessfully because of these two failures; both APK tasks still succeed with
`--continue`.

An initial new-test run hit Windows SQLite WAL path-length failures in two long
Robolectric test names. Shortening the test-local database name resolved them;
production database settings were unchanged.

## Migration and compatibility

Room version **1→2** adds:

- `RecordingSession.cadenceUs`, `spokenCounting`, `firstCueDelayUs` (nullable).
- `LandmarkTrack.formatId`, `formatVersion` (nullable).

V1 schema identity: `96f1ba384ee93b74a37fadffe1692bb3`.
V2 schema identity: `a26efcf7da4b26753b1cc564c0a391d5`.
Both exports remain present. No destructive fallback or schema rewrite is used.
Legacy rows keep unknown new fields null; no settings/provenance is invented.

`movement_landmark_stream_v1` is a **real header transition**, not a byte alias
for `karate_pose_track_v1`. A magic-dispatched legacy reader retains old fixture
support; Room format metadata is checked against the actual header. The exact
old/new byte layouts and staging lifecycle are in the
[MLS contract](../movement-landmark-stream-format.md).

Tests verify successful publication, checksum/format rejection, normalized/world
coordinates and confidence preservation, timestamp order, public rejection of
staging files, recovery after publish-before-DB-complete, stale temporary tracks,
corruption detection, multiple tracks, MP4 preservation/deletion independence,
remembered settings, count cycling, no automatic stop, cancellation and no
phantom cue on sound failure. Reopen produces zero extra fake-decoder calls.
Native decoding remains a hardware acceptance check.

## Commands and artifacts

From `android/KarateClipRecorder`, using Android Studio JBR as `JAVA_HOME`:

```text
gradlew :app:testDebugUnitTest \
  --tests dk.lasse.karatecliprecorder.assisted.* \
  --tests dk.lasse.karatecliprecorder.training.* \
  --tests dk.lasse.karatecliprecorder.profile.ProfileRepositoryTest \
  --tests dk.lasse.karatecliprecorder.GuidedJodanSessionControllerTest \
  :app:assembleDebug :app:assembleDebugAndroidTest

gradlew :app:testDebugUnitTest :karate-analyzer-core:test \
  :mediapipe-hand-adapter:testDebugUnitTest \
  :mediapipe-pose-adapter:testDebugUnitTest \
  :app:assembleDebug :app:assembleDebugAndroidTest --continue
```

Local build logs: `build/assisted-focused-final.log`, `build/assisted-full.log`.
APKs: `app/build/outputs/apk/debug/app-debug.apk` and
`app/build/outputs/apk/androidTest/debug/app-debug-androidTest.apk`.

All changed/new files, including the earlier foundation work still in this
workspace, are listed in the [file manifest](assisted-capture-v1-files.md).

## Connected-device technical checks

Later on 2026-09-14, the user connected a Samsung Galaxy A55 (SM-A556B), Android
16, and selected **Run technical checks first**. The debug app and instrumentation
APK installed successfully without clearing existing app data.

- `TrainingMigrationTest`: **OK (1 test)** on the real device. The seeded v1
  user/session/master/legacy-track graph migrated to v2 successfully.
- Home → Skill Coach → Record & Analyze opened the camera preview and reached
  Ready to record. The initial controls showed 10, 1.0 seconds and counting on.
- Changed settings to 11, 1.1 seconds and counting off. All three survived a
  force-stop, debug APK update and UI reopen. Restored 10, 1.0 seconds and on.
- Production `karate-training.db` reported `user_version=2`, the new setup
  columns, and zero foreign-key violations. Four existing profile identities
  were represented as training users. Sessions, masters, cues, tracks and
  movements remained empty: entering/leaving the page created no recording.
- Camera service logs confirmed disconnection on leaving the capture page.
- Visual review exposed system-bar overlap with Back and Record on Android 16.
  Added system-bar/display-cutout insets to the page padding, rebuilt and
  reinstalled. A second screenshot confirmed both controls clear the bars.
  After correction, Back occupied y=110–236 and Record y=2004–2172 on the
  1080×2340 display. The **11 assisted tests passed** after this layout fix.

The final build log is `build/assisted-device-insets.log`. Local screenshots
are `build/assisted-device-ready.png` (before) and
`build/assisted-device-insets-fixed.png` (after); they are not checked in because
they include the camera view of the user's surroundings. The app was left on
Skill Coach with the camera off. No recording was started.

## Not completed

The required physical recording acceptance sequence is **not complete**:
capture of another person; observed countdown/start/cue timing and
speaker routing; MP4 native finalization and audio-track inspection; native
MediaPipe extraction; device Room inspection; full playback/overlay alignment;
process-kill/reopen without redundant decode; permission/background/error flows;
and large-text/TalkBack review. The normal portrait layout and migration have
now been checked on hardware. Remaining checks are in the
[acceptance backlog](../backlog/record-and-analyze-assisted-capture.md).

The extraction job uses the application serial queue, not a foreground service.
If Android kills it, reopening the capture page resumes from persisted evidence.
The existing decoder still resolves source timestamps to milliseconds and holds
the full landmark sequence in memory; MLS microseconds do not increase temporal
resolution. No segmentation/analysis was added to this slice.
