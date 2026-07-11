# MediaPipe hand adapter

This module adapts MediaPipe Tasks Vision Gesture Recognizer output into analyzer-neutral hand frames.

Expected model asset:

```text
mediapipe/gesture_recognizer.task
```

The module is specifically for the Gesture Recognizer `.task` bundle. It does not download models at runtime and does not treat a Hand Landmarker model as interchangeable. `GestureRecognizerModelAssetValidator` throws `MissingGestureRecognizerModelException` when the asset is absent.

`GestureRecognizerResultAdapter` reads hands by index across `landmarks()`, `worldLandmarks()`, `handedness()`, and `gestures()`. Malformed normalized landmark lists are excluded. Malformed world landmark lists are ignored without invalidating valid normalized geometry. Open_Palm and Closed_Fist scores are exposed as observation metadata only; karate-analyzer-core remains responsible for judging karate lesson steps.
