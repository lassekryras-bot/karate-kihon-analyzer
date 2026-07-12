# MediaPipe Gesture Recognizer hand adapter

This module adapts MediaPipe Tasks Vision Gesture Recognizer output into analyzer-neutral hand frames.

Expected model asset:

```text
mediapipe/gesture_recognizer.task
```

The module is specifically for the Gesture Recognizer `.task` bundle. It does not download models at runtime and does not treat a Hand Landmarker model as interchangeable. `GestureRecognizerModelAssetValidator` throws `MissingGestureRecognizerModelException` when the asset is absent.

`GestureRecognizerResultAdapter` reads hands by index across `landmarks()`, `worldLandmarks()`, `handedness()`, and `gestures()`. Malformed normalized landmark lists are excluded. Malformed world landmark lists are ignored without invalidating valid normalized geometry. Open_Palm and Closed_Fist scores are exposed as observation metadata only; karate-analyzer-core remains responsible for judging karate lesson steps.


## Coordinate and acceptance contract

- Normalized landmark coordinates are preserved exactly as MediaPipe returns them.
- The adapter does not mirror, rotate, convert normalized coordinates to pixels, or align landmarks with tutorial guides.
- World coordinates are optional observation metadata; malformed or missing world landmarks do not change valid normalized landmark geometry.
- Front-camera preview mirroring belongs to the Android presentation layer, not this adapter.
- Canned MediaPipe gesture scores are observation metadata only and are never used to accept karate lesson steps. The karate-analyzer-core verifiers remain the only source of step acceptance.
