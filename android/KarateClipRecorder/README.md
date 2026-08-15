# Karate Clip Recorder Android MVP

This folder contains the Android phone milestone for the Karate Clip Recorder. It is a standalone Kotlin/Gradle Android project that proves the CameraX preview and fixed-duration recording path for guided kihon capture.

## What the guided Jodan session does

- Launches as a simple Android app with package `dk.lasse.karatecliprecorder`.
- Requests the `CAMERA` permission at startup.
- Shows a live CameraX preview from the back camera.
- Selects and displays a karate-friendly capture profile when the camera starts.
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
- Saves a session metadata JSON file after the session completes, including the selected camera profile when available.
- Shows a completion summary with the expected clip count, saved clip count, and metadata path.

Japanese prompts use prerecorded audio rather than text-to-speech.

## Punch Heights - Level 1

- **Punch Heights - Level 1** is a separate main-menu practice session; it does not replace the recorded Jodan session, Find Your Weapon, or Japanese count training.
- The back-camera 640 x 480 analysis stream uses `mediapipe/pose_landmarker_full.task` on CPU. Setup checks framing and a sideways stance, initializes a session-only body reference, then practices static Jodan, Chudan, and Gedan in order.
- The overlay shows the body-relative target band, fist and elbow guidance, raise/lower direction, and the 1.2-second hold. Debug mode adds tracked landmarks, torso/chin geometry, confidence information, and session-only Jodan chin-projection controls.
- Calm English Android text-to-speech announces target and guidance changes. Visible instructions remain available when English TTS is unavailable.
- Each accepted pose saves the exact analyzed frame and an annotated JPEG. After all three captures, `session.json` and the six images are published together to app-private external storage at `Pictures/punch_height_level_1/latest`.
- A cancelled or failed practice removes only its staging files, leaving the previous completed `latest` session untouched. The review screen shows all three annotated images and provides **Practice again** and **Close**.

## Japanese count training

- **Level 1** teaches `"1"` through `"10"` one at a time using the short martial-arts pronunciations `ich, ni, san, shi, go, rok, shich, hach, kyu, ju`. `Ichi`, `roku`, `shichi`, and `hachi` remain the standard spellings; their final unstressed vowel is clipped in the prerecorded cues. Each number plays automatically; replay, previous, and next are manual. This level never requests microphone permission or starts speech recognition.
- **Level 2** plays one continuous example, then runs one live Android speech-recognition session configured for `ja-JP`. It does not store microphone audio and does not run an English fallback.
- Final Japanese alternatives are normalized from Arabic digits, kanji, hiragana, katakana, or Japanese romaji. Compact unspaced Japanese output and the common `yon`, `nana`, and `ku` alternatives are supported.
- The live recognizer requests a 15-second minimum session plus a 10–12 second silence window. On Android 13 and newer it also requests segmented-session recognition, allowing Android to return a completed speech segment while keeping the microphone session open for the next number. If the recognition service still ends a pass early, the app retains that transcript and immediately starts another live pass; temporary recognizer-busy responses are retried. Segments are combined without storing audio. An incomplete attempt continues through hesitations until the user taps **Stop listening**. It still stops automatically once ten recognizable counts are present in any order; final alternatives determine the result.
- Result feedback shows the selected raw Japanese transcript. Incorrect positions show the recognized text and normalized number; missing positions explicitly say that no count was recognized.
- The ninth cue reuses the original `ku` recording, renamed to the Android resource `order_kyu.wav` so its filename matches the app's `Kyu` count label.

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

## Capture profile selection

When the back camera is initialized, the app now inspects CameraX video qualities and Camera2 target FPS ranges before building the recorder. The selector favors frame rate over resolution because punch timing depends on temporal detail, while still keeping enough resolution to see the wrist, elbow, shoulder, head, and chin.

Preferred order:

1. FHD / 1080p with a 60 fps-capable range
2. HD / 720p with a 60 fps-capable range
3. FHD / 1080p with a 30 fps-capable range
4. HD / 720p with a 30 fps-capable range
5. SD or another safe fallback only when needed

The current PR detects and stores the preferred FPS range but does not forcibly control Camera2 FPS yet. CameraX recorder setup uses the selected video quality through a `QualitySelector`; FPS forcing is intentionally left for a later, carefully tested Camera2Interop PR.

The selected profile is displayed in the on-screen UI after camera initialization, including the selected quality, target resolution, preferred FPS, and supported FPS ranges. If capability lookup fails, the app falls back to a safe HD / 30 fps profile so preview and recording can continue.

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
  "camera_profile": {
    "selected_quality": "FHD",
    "selected_camerax_quality": "FHD",
    "target_width": 1920,
    "target_height": 1080,
    "preferred_target_fps": 60,
    "selected_fps_range": {
      "min_fps": 30,
      "max_fps": 60
    },
    "supported_qualities": ["FHD", "HD", "SD"],
    "supported_fps_ranges": [
      { "min_fps": 15, "max_fps": 30 },
      { "min_fps": 30, "max_fps": 60 }
    ],
    "selection_reason": "Selected FHD with preferred 60fps support."
  },
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

The app declares:

- `CAMERA`
- `RECORD_AUDIO`

Camera permission is requested for the camera preview. Microphone permission is requested only when the user starts Level 2 Japanese live recognition; Level 1 does not request it.

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

- `MainActivity.kt` requests permission, builds the simple UI, starts preview, shows the selected capture profile, starts/cancels the guided session, and updates screen text.
- `GuidedJodanSessionController.kt` creates the 10-strike Jodan plan, schedules each fixed-duration clip, collects saved results, and writes metadata JSON with the selected camera profile.
- `GuidedStrikePlan.kt` defines each strike index, Japanese count, expected side, and deterministic filename.
- `StrikeSide.kt` defines `RIGHT` and `LEFT` sides with metadata values.
- `GuidedSessionState.kt` defines guided-session UI states.
- `GuidedSessionResult.kt` carries completion and saved-clip summary data.
- `CameraXRecordingAdapter.kt` initializes capture capabilities, builds the Recorder with the selected CameraX quality, binds the CameraX preview, starts/stops video capture, saves MP4 files, accepts caller-provided output filenames, and reports status/results through callbacks.
- `RecordingState.kt` defines the low-level recording states: `IDLE`, `PREPARING`, `RECORDING`, `SAVED`, and `FAILED`.
- `RecordingResult.kt` carries the saved file name, path, and URI.
- `captureprofile/` contains the pure capture profile models/selector plus CameraX/Camera2 capability initialization.
- `learning/CountTranscriptNormalizer.kt` normalizes Japanese live transcripts and selects the strongest final alternative.
- `learning/JapaneseCountSpeechRecognizer.kt` provides live `ja-JP` recognition without storing microphone audio.

## Still out of scope

The current Android milestone does not add:

- Automatic punch detection
- Jodan scoring

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

### MediaPipe Gesture Recognizer model

Find Your Weapon hand-shape capture is prepared for later MediaPipe Tasks Vision Gesture Recognizer integration. Package the Gesture Recognizer `.task` bundle at `mediapipe/gesture_recognizer.task`. The adapter expects Gesture Recognizer results and is not a Hand Landmarker adapter.
