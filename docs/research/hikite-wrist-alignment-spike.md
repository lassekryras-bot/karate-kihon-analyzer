# Hikite wrist-alignment experiment

Status: experiment implemented; inference blocked in the current workspace.
No wrist-bend value or straight/bent coaching verdict has been established for
punch 6. Existing saved analysis contains no hand detections (`hand_detector_backend:
none`). The repository already contains the MediaPipe Hand Landmarker model.

## First measurement

Use Pose elbow, Hand wrist (landmark 0), and middle-finger MCP (landmark 9), all
mapped to the same original image pixels. Measure the signed turn from elbow →
Hand wrist to Hand wrist → MCP. Zero is a straight continuation. Do not use finger
tips because a closed fist bends the fingers. Do not mix Hand world coordinates
with Pose world coordinates, or treat handedness score as alignment confidence.

This is a visible 2D bend candidate, not verified anatomical flexion/extension.
Palm/forearm orientation and occlusion can make different wrist movements look
similar. A future warning needs validation and explicit view/quality handling;
this experiment deliberately assigns no good/bad threshold.

## Runnable test

From the repository, with its Python dependencies installed:

```sh
python scripts/experiments/hikite_hand_tracking.py \
  --video /path/to/recording.mp4 \
  --poses /path/to/video_landmarks.json \
  --model android/KarateClipRecorder/app/src/main/assets/mediapipe/hand_landmarker.task \
  --output output/hikite-hand-test \
  --start 285 --end 305 --side right
```

The default frames cover punch 6's selected finish at frame 295 in the current
validation recording. The script decodes sequentially, checks image dimensions,
and runs default-confidence image detection on full frames plus wrist-centred
22%, 30% and 40% crops. Crop landmarks are mapped back to original coordinates
using the existing serializer. Candidate matching compares Hand wrist proximity
to the requested Pose wrist versus the other wrist. The half-upper-arm association
radius is an experimental matching gate, not validated identity evidence.

Output keeps all detection variants, handedness metadata, crop provenance, wrist
gap and visible bend. Inspect disagreement between crop scales and adjacent frames
before selecting a result. Finish crops are for local inspection; do not commit
personal images or pose exports merely because the script generated them.

## Actual execution result

The current MediaPipe runtime cannot load `libEGL.so.1`. System package installation
was blocked by container permissions; a local dependency download was cancelled.
The older local MediaPipe installation also failed to import. No inference ran,
and no detection output was generated. Geometry tests cover straight/bent,
mirrored and degenerate inputs; they do not validate real hand tracking.

Official interface and coordinate reference:
[MediaPipe Hand Landmarker Python guide](https://developers.google.com/edge/mediapipe/solutions/vision/hand_landmarker/python).
