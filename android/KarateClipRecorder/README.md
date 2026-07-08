# Karate Clip Recorder Android MVP

This folder contains the first Android phone milestone for the Karate Clip Recorder. It is a standalone Kotlin/Gradle Android project that proves the phone camera recording path for the future Android-side `RecordingAdapter` implementation.

## What the MVP does

- Launches as a simple Android app with package `dk.lasse.karatecliprecorder`.
- Requests the `CAMERA` permission at startup.
- Shows a live CameraX preview from the back camera.
- Provides a **Record 4 seconds** button.
- Starts a video recording when the button is tapped.
- Automatically stops recording after 4 seconds.
- Saves an MP4 file using sequential names such as:
  - `strike_test_001.mp4`
  - `strike_test_002.mp4`
  - `strike_test_003.mp4`
- Displays the recording status, saved file name, saved file path, and saved URI.

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
6. Tap **Record 4 seconds**.
7. Wait for the app to stop recording automatically and display the saved clip details.

## Permissions

The app requests only:

- `CAMERA`

Audio is intentionally disabled in this MVP, so `RECORD_AUDIO` is not requested.

## Where clips are saved

Clips are saved in the app-private external movies directory returned by:

```kotlin
getExternalFilesDir(Environment.DIRECTORY_MOVIES)
```

On a device, this is typically under a package-specific path similar to:

```text
Android/data/dk.lasse.karatecliprecorder/files/Movies/
```

The exact path is displayed in the app after each successful recording.

## Android-side architecture

The MVP includes a small CameraX recording adapter:

- `MainActivity.kt` requests permission, builds the simple UI, starts preview, starts a fixed-duration recording, and updates screen text.
- `CameraXRecordingAdapter.kt` binds the CameraX preview, starts/stops video capture, saves MP4 files, and reports status/results through callbacks.
- `RecordingState.kt` defines the simple recording states: `IDLE`, `PREPARING`, `RECORDING`, `SAVED`, and `FAILED`.
- `RecordingResult.kt` carries the saved file name, path, and URI.

## Intentionally out of scope

This PR does not add:

- 10-punch guided session logic
- Japanese count prompts
- TextToSpeech
- Voice commands or speech recognition
- MediaPipe
- Pose detection
- Automatic punch detection
- Analyzer integration
- Jodan scoring
- Debug overlays

## Confirmed phone test

This Android MVP was confirmed running on a real phone:

- Preview visible.
- **Record 4 seconds** button works.
- `strike_test_001.mp4` saved in the app-private Movies directory.

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
