# Karate Clip Recorder Android MVP

This folder contains the Android phone milestone for the Karate Clip Recorder. It is a standalone Kotlin/Gradle Android project that proves the CameraX preview and fixed-duration recording path for guided kihon capture.

## What the guided Jodan session does

- Launches as a simple Android app with package `dk.lasse.karatecliprecorder`.
- Requests the `CAMERA` permission at startup.
- Shows a live CameraX preview from the back camera.
- Provides a **Start Jodan Session** button as the main recording path.
- Guides the user through a fixed 10-strike Jodan session with visible prompts:
  - setup / ready
  - `Yoi`
  - Japanese counts from `Ichi` through `Ju`
  - current strike number
  - expected strike side
  - recording state
  - saved clip count
- Records 10 separate fixed-duration MP4 clips.
- Records each clip for 4 seconds, with a short pause between clips.
- Alternates expected sides, starting with right and then left.
- Saves a session metadata JSON file after the session completes.
- Shows a completion summary with the expected clip count, saved clip count, and metadata path.

Text-to-speech is intentionally not included in this PR; prompts are visible text only.

## Expected clip filenames

A guided session writes deterministic filenames for the 10-clip plan:

```text
strike_001_right.mp4
strike_002_left.mp4
strike_003_right.mp4
strike_004_left.mp4
strike_005_right.mp4
strike_006_left.mp4
strike_007_right.mp4
strike_008_left.mp4
strike_009_right.mp4
strike_010_left.mp4
```

The app overwrites the guided-session clip file for a strike if the same deterministic filename already exists from an earlier run.

## Metadata JSON

After the guided session, the app writes:

```text
guided_jodan_session_metadata.json
```

The metadata schema is:

```json
{
  "schema_version": "android-guided-jodan-session-v1",
  "session_type": "jodan_fixed_duration_clip_session",
  "expected_strike_count": 10,
  "fixed_clip_duration_ms": 4000,
  "clips": [
    {
      "strike_index": 1,
      "japanese_count": "Ichi",
      "expected_side": "right",
      "file_name": "strike_001_right.mp4",
      "saved": true,
      "path": "..."
    }
  ],
  "completed": true,
  "successful_clip_count": 10
}
```

## How to open in Android Studio

1. Open Android Studio.
2. Choose **File > Open**.
3. Select this directory:

   ```text
   android/KarateClipRecorder
   ```

4. Let Android Studio sync the Gradle project.

## How to run on a phone

1. Connect a real Android phone with USB debugging enabled.
2. In Android Studio, select the phone as the run target.
3. Press **Run**.
4. When prompted on the phone, grant the camera permission.
5. Confirm that the live camera preview is visible.
6. Tap **Start Jodan Session**.
7. Follow the visible `Yoi`, count, strike number, and expected side prompts.
8. Wait for the app to record all 10 clips automatically and display the session completion summary.

## Permissions

The app requests only:

- `CAMERA`

Audio is intentionally disabled in this MVP, so `RECORD_AUDIO` is not requested.

## Where clips are saved

Guided-session clips are saved in the app-private external movies directory returned by:

```kotlin
getExternalFilesDir(Environment.DIRECTORY_MOVIES)
```

The guided session uses a child folder:

```text
Movies/guided_jodan_session/
```

On a device, this is typically under a package-specific path similar to:

```text
Android/data/dk.lasse.karatecliprecorder/files/Movies/guided_jodan_session/
```

The exact metadata path is displayed in the app after the session completes. Clip paths are also included in the metadata JSON.

## Where metadata is saved

The session metadata file is saved beside the guided clips:

```text
Android/data/dk.lasse.karatecliprecorder/files/Movies/guided_jodan_session/guided_jodan_session_metadata.json
```

## Android-side architecture

The Android app includes:

- `MainActivity.kt` requests permission, builds the simple UI, starts preview, starts/cancels the guided session, and updates screen text.
- `GuidedJodanSessionController.kt` creates the 10-strike Jodan plan, schedules each fixed-duration clip, collects saved results, and writes metadata JSON.
- `GuidedStrikePlan.kt` defines each strike index, Japanese count, expected side, and deterministic filename.
- `StrikeSide.kt` defines `RIGHT` and `LEFT` sides with metadata values.
- `GuidedSessionState.kt` defines guided-session UI states.
- `GuidedSessionResult.kt` carries completion and saved-clip summary data.
- `CameraXRecordingAdapter.kt` binds the CameraX preview, starts/stops video capture, saves MP4 files, accepts caller-provided output filenames, and reports status/results through callbacks.
- `RecordingState.kt` defines the low-level recording states: `IDLE`, `PREPARING`, `RECORDING`, `SAVED`, and `FAILED`.
- `RecordingResult.kt` carries the saved file name, path, and URI.

## Intentionally out of scope

This PR does not add:

- MediaPipe
- Pose detection
- Automatic punch detection
- Analyzer integration
- Jodan scoring
- Voice commands or speech recognition
- Text-to-speech
- Debug overlays

## Troubleshooting

### AndroidX property is not enabled

If Gradle reports:

```text
Configuration ':app:debugRuntimeClasspath' contains AndroidX dependencies, but the android.useAndroidX property is not enabled
```

Fix it by creating `android/KarateClipRecorder/gradle.properties` with:

```properties
android.useAndroidX=true
android.nonTransitiveRClass=true
android.enableJetifier=false
```

### JVM target mismatch

If Gradle reports:

```text
Inconsistent JVM-target compatibility detected for tasks compileDebugJavaWithJavac and compileDebugKotlin
```

Fix it by setting Java `compileOptions` and Kotlin `compilerOptions.jvmTarget` to JVM 17 in `app/build.gradle.kts`.
