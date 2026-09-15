# Record & Analyze bundled validation

Date: 2026-09-14. Platform: Windows host, Android Studio JBR, Android SDK 35.

## Scope

All three requested slices are implemented in local source: assisted capture
lifecycle, durable MLS queue/resource policy, and Performance recordings/calendar.
The [current implementation contract](../record-and-analyze-assisted-capture-v1.md)
records defaults and boundaries. No new technique analysis or segmentation is added.

## Automated checks

Focused capture/training run: **56 passed, zero failures or skips**.

| Final full run | Tests | Passed | Failed |
| --- | ---: | ---: | ---: |
| App | 265 | 263 | 2 pre-existing |
| Analyzer core | 254 | 254 | 0 |
| Hand adapter | 23 | 23 | 0 |
| Pose adapter | 4 | 4 | 0 |
| Total | 546 | 544 | 2 pre-existing |

No tests were skipped. `git diff --check` passed using the repository's normal
Windows line-ending configuration. Source whitespace checks found no trailing
spaces in the new capture, queue or browser files.

The focused capture/training suite covers controller timing, graceful and forced
stop, interruption, cue cycling, storage/MLS integrity/reuse, FIFO/promotion,
capture cancellation and restart, serial heavy-work exclusion, deletion publication
races, battery/background policy, activity context, browser ordering/filtering,
calendar dates and global queue status. Room export migrations cover v1→v4 and
v2→v4, including preserved legacy evidence and queue backfill with unknown context.

The native recording-detail test uses Robolectric with a persisted failed queue
item. It verifies the exact detail, planned count, Retry and independent playback
action, no queue promotion from viewing, no invented movements, cancellation of
the delete dialog, and confirmed removal through the queue/repository route.
This test does not establish real video decoding or screen appearance.

The UI test initializes WorkManager explicitly because Robolectric does not run
the manifest's AndroidX Startup provider. Production retains that provider in its
merged manifest. Dialog assertions drain the main looper before checking dismissal.

Full command:

```powershell
:app:testDebugUnitTest :karate-analyzer-core:test `
  :mediapipe-hand-adapter:testDebugUnitTest `
  :mediapipe-pose-adapter:testDebugUnitTest `
  :app:assembleDebug :app:assembleDebugAndroidTest --continue
```

Logs are under `android/KarateClipRecorder/build/`: `bundled-step1.log`,
`bundled-step2-final.log`, `bundled-step3.log`, `bundled-final-focused.log`,
and `bundled-full-validation.log`. Reports are in each module's
`build/reports/tests/` and `build/test-results/` directories.

The full run retains two pre-existing, unrelated architecture assertion failures:

- `HomeStartupArchitectureTest.trainModeChooserIsPassiveAndSeparateFromLearningCatalogue`
  expects the old placeholder callback count.
- `LearningArtworkArchitectureTest.continueCardUsesTheLearningPathsStableEnsoAndResponsiveProgressLayout`
  expects the old Continue learning literal.

These assertions were not weakened or removed. Debug app and instrumentation APKs
build successfully; assembling instrumentation is not the same as running it.

## Device acceptance still open

`adb devices -l` returned no attached device during this continuation. The earlier
Galaxy A55 technical results in [assisted v1 validation](assisted-capture-v1.md)
apply to the previous build, not this bundled implementation. No new APK was
installed on that phone and no real recording was started in this continuation.

Required device checks:

1. Upgrade existing data to v4; inspect preserved recording/MLS/event relationships.
2. Check rear lenses, quality fallback, zoom/focus, orientation, audio route,
   system insets, compact/large-text layout and TalkBack.
3. Record A/B/C consecutively; verify video-only MP4s, actual cue/stop/interruption
   timeline, immediate Saved/Record another, and capture preemption of native MLS.
4. Check background On/Off, explicit foreground processing, app displacement,
   battery start threshold, charging recovery and low-storage boundaries.
5. Kill/reopen during extraction and publication; verify FIFO reconstruction,
   completed MLS reuse and staging rejection without redundant successful decodes.
6. Review filtered calendar/day navigation, exact post-capture link, full MP4
   playback in queued/processing/failed states, debug overlay and scrubbing.
7. Delete a queued and an actively processing recording; verify no stale MLS
   publication and continued processing of the next item.

Android controls exact WorkManager execution timing, OEM camera availability and
foreground service eligibility. Frame-rate preferences and technical tests do not
establish sustained hardware FPS, acoustic onset or native overlay alignment.

## Implementation map

Paths below are relative to `android/KarateClipRecorder/app/`.

| Area | Main files |
| --- | --- |
| Capture state and page | `src/main/java/dk/lasse/karatecliprecorder/assisted/AssistedCaptureController.kt`, `AssistedCaptureActivity.kt` |
| Camera setup/start/finalization | `src/main/java/dk/lasse/karatecliprecorder/CameraXRecordingAdapter.kt`, `captureprofile/AssistedCameraOptions.kt` |
| Room queue/schema/repository | `src/main/java/dk/lasse/karatecliprecorder/training/TrainingModels.kt`, `TrainingRows.kt`, `TrainingDao.kt`, `TrainingRepository.kt`, `KarateTrainingDatabase.kt`, exports `schemas/**/1.json`–`4.json` |
| Worker and policy | `src/main/java/dk/lasse/karatecliprecorder/training/RecordingQueue.kt`, `ProcessingCoordinator.kt`, `ProcessingPolicy.kt`, `TrainingApplication.kt`, `ProcessingSettingsView.kt` |
| MLS publication/reuse | `src/main/java/dk/lasse/karatecliprecorder/training/TrainingServices.kt`, `TrainingSessionProcessor.kt`, `LandmarkFiles.kt` |
| Performance browsing | `src/main/java/dk/lasse/karatecliprecorder/training/RecordingBrowser.kt`, `recordings/RecordingsActivity.kt`, `recordings/QueueStatusView.kt`, `profile/ProgressScreenView.kt` |
| App integration | `src/main/AndroidManifest.xml`, `src/main/java/dk/lasse/karatecliprecorder/MainActivity.kt`, `SettingsScreenView.kt`, `build.gradle.kts` |
| New/extended tests | `src/test/kotlin/dk/lasse/karatecliprecorder/assisted/`, `src/test/kotlin/dk/lasse/karatecliprecorder/training/`, `src/androidTest/java/dk/lasse/karatecliprecorder/training/TrainingMigrationTest.kt` |

The working tree also includes the preceding uncommitted training-foundation and
assisted-capture implementation. Those changes were preserved; no commit was made.
