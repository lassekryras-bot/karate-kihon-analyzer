# MediaPipe Gesture Recognizer asset

Expected runtime asset path:

`app/src/main/assets/mediapipe/gesture_recognizer.task`

Use the official MediaPipe Gesture Recognizer float16 v1 task bundle:

`https://storage.googleapis.com/mediapipe-models/gesture_recognizer/gesture_recognizer/float16/1/gesture_recognizer.task`

The model recognizes these canned gesture categories: `None`, `Closed_Fist`,
`Open_Palm`, `Pointing_Up`, `Thumb_Down`, `Thumb_Up`, `Victory`, and `ILoveYou`.

If the binary is not committed because the current environment cannot fetch or
redistribute it, install it with checksum validation instead of using a
placeholder:

```bash
python3 scripts/download_gesture_recognizer_model.py --sha256 <verified-sha256>
```

Record the model source, version, license/distribution permission, SHA-256,
expected categories, and file size whenever the binary is added or updated.
The app intentionally reports a readable missing-model error until a compatible
real `.task` file exists at the expected path.
