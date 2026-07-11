# MediaPipe hand adapter

This Android library adapts MediaPipe hand or gesture-recognition observations into the analyzer-neutral `HandFrame` model from `karate-analyzer-core`.

## Model asset

No `.task` model is committed in this repository. Place the downloaded MediaPipe hand landmarker or gesture recognizer model at:

```text
android/KarateClipRecorder/mediapipe-hand-adapter/src/main/assets/mediapipe/gesture_recognizer.task
```

At runtime this is expected as asset path `mediapipe/gesture_recognizer.task` (`EXPECTED_MEDIAPIPE_HAND_MODEL_ASSET_PATH`). The adapter validates the asset and throws a clear error if it is absent. It must not download the model at runtime and must not use placeholder model files.

## Geometry and handedness

The mapper preserves MediaPipe coordinates. Normalized image landmarks remain normalized, world landmarks remain in MediaPipe world-coordinate space, and no Android pixel conversion, guide alignment, rotation, or silent mirroring is applied.

MediaPipe handedness is reported for the input image stream. If an app mirrors a front-camera preview, the visual left/right shown to the user can appear reversed relative to the unmirrored input. Represent mirroring or rotation explicitly in app metadata rather than mutating analyzer geometry in this adapter.
