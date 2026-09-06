# Full-video motion diagnostic plots

The motion plot helper visualizes frame-selection evidence across the complete
video. It is a developer validation surface, not a coaching report.

```bash
karate-analyzer plot-motion-diagnostics \
  --landmarks output/video_landmarks.json \
  --events output/punch_event_landmarks.json \
  --output output/motion-diagnostics.png \
  --data-output output/motion-diagnostics.json
```

The helper writes separate `motion-diagnostics-left.png` and
`motion-diagnostics-right.png` figures. Each contains nine signals for only that
arm:

1. 2D elbow angle in degrees.
2. Wrist distance from the observed body centerline, in shoulder widths.
3. Torso-relative wrist speed, in shoulder widths per second.
4. Signed outward wrist velocity, in shoulder widths per second.
5. Signed outward wrist acceleration, in shoulder widths per second squared.
6. Shoulder-to-wrist extension ratio.
7. Signed punching-elbow displacement from the body centerline.
8. Signed forward velocity of the punching elbow.
9. Cross-body opposition distance from the opposite elbow to the punching wrist.

## Shoulder-width reference

Normalized image landmarks are first converted to analysis-image pixels so the
video aspect ratio is respected. The helper measures the pixel distance between
the two Pose shoulder landmarks on every sufficiently visible frame and uses the
**median of those distances as one fixed scale for the whole video**.

It does not divide by a new shoulder width on every frame. A changing denominator
would convert shoulder rotation, foreshortening, landmark jitter, and temporary
occlusion into false wrist speed and acceleration. The median is robust to brief
bad detections and makes all frames within this recording use the same scale.

This is still observed camera-plane shoulder width, not anatomical shoulder
width in centimetres. It is suitable for within-video diagnostics. Comparisons
between sessions require a similar camera angle and setup, or a later calibrated
or Pose-world scale.

The body centerline runs from shoulder midpoint to hip midpoint. Wrist positions
are expressed relative to the torso center and divided by the fixed shoulder
reference before a centered median filter and time derivatives are calculated.
The signed outward-velocity panel uses the same pure smoothing and
timestamp-derivative helper as the production theoretical-impact estimator; the
plot is therefore a view of the selection signal, not an independently defined
look-alike. The default three-frame smoothing window matches production and is
recorded in the companion JSON.

Resultant wrist speed is always non-negative. Signed outward velocity is the
time derivative of shoulder-to-wrist reach: positive values mean extension,
values near zero mean a hold or turning point, and negative values mean
retraction. Its signed acceleration is positive while outward velocity grows and
negative during outward braking or acceleration into retraction. Velocity must
be read alongside acceleration because negative acceleration does not by itself
mean the wrist is already moving backward.

The acceleration graph always uses symmetric positive and negative limits, so
zero remains at the vertical center. Positive regions are shaded green and
negative regions red. The colors describe mathematical direction only; they are
not good/bad coaching classifications.

## Experimental elbow and cross-body signals

Signed elbow displacement is the perpendicular distance from the current
shoulder-midpoint-to-hip-midpoint torso line. The sign is oriented separately
for each arm so the side containing that arm's terminal wrist is positive. This
makes forward/backward meaning stable when a recording is mirrored: positive is
in the punch direction and negative is behind the torso. The velocity panel is
the timestamp derivative of the smoothed displacement.

Cross-body opposition distance pairs the punching wrist with the opposite
elbow: right wrist to left elbow for a right punch, and left wrist to right elbow
for a left punch. It is unsigned and should often approach a maximum when the
punching arm is extended and the other arm performs hikite. These three signals
are secondary theoretical-impact confirmation evidence. They never move the
earliest wrist-selected arrival later: a bounded look-ahead can confirm that
arrival while retaining its original time. Missing opposite-arm evidence lowers
confidence but does not make a valid wrist event unavailable. They depend on a
visible torso and opposite elbow, and they can be influenced by torso rotation
and camera perspective.

## Extension-ratio graph

The shoulder-to-wrist extension ratio is:

`straight shoulder-to-wrist distance / (upper-arm length + forearm length)`

All three lengths use the same analysis-image pixel frame, so the ratio is
dimensionless. A value near `1.0` means the shoulder, elbow, and wrist appear
nearly collinear in the image. A lower value means the visible elbow is more
bent. During a punch, the ratio normally rises toward a terminal plateau and
falls during retraction, making it useful for checking candidate peaks and late
analysis-frame selection.

It does not prove anatomical full extension, hyperextension, physical impact, or
correct technique. A 2D projection can make a 3D-bent arm appear straighter, and
occlusion or landmark error can change the ratio.

Markers keep event concepts separate:

- orange triangle: detector candidate peak;
- green circle: theoretical-impact estimate;
- purple X: selected analysis frame.

These camera-plane signals do not measure metres, force, physical contact, or
clinical joint motion. Acceleration is especially sensitive to landmark noise;
it should be used to diagnose the event estimator and compare like-for-like
recordings, not as a universal performance threshold.
