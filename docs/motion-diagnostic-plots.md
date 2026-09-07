# Full-video motion diagnostic plots

App-facing interactive explanations use the separate, renderer-neutral
[measurement presentation contract](measurement-presentation-contract.md).
The Matplotlib plots in this document remain developer diagnostics.

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

It also writes one compact event plot per detected punch, named like
`motion-diagnostics-event-02-right.png`. These figures use timestamp-based,
shared x-limits and three coordinated panels:

1. production shoulder-to-wrist reach and signed outward wrist velocity;
2. signed punching-elbow displacement and elbow forward velocity;
3. cross-body opposition distance and its signed opening/closing velocity.

Two additional experimental panels describe wrist-path straightness without
classifying technique:

4. signed perpendicular wrist deviation from a straight start-to-impact line;
5. signed movement-direction error at 50, 100, and 200 ms look-back intervals.

The path uses punching-shoulder-relative analysis-image coordinates divided by
the same fixed whole-video shoulder-width scale. Its start is the latest
repetition-relative minimum-reach plateau before outward onset when that plateau
is observable. If wrist visibility is lost in chamber, the path begins at the
first reliable wrist sample after the last occlusion and records
`first_visible_wrist_after_occlusion`; it never draws a trajectory across the
unobserved gap. This may leave only a short visible path and too few samples for
the longer direction intervals, which remains explicit in the JSON.

The companion data records the reference line, start frame/time/reason, each
signed deviation and direction error, actual timestamp interval used, direct and
travelled distance, path-efficiency ratio, maximum and RMS deviation, deviation
sign changes, and per-interval valid counts and error summaries. These are
camera-plane diagnostic measurements for later repetition aggregation, not
single-punch coaching flags.

For every event the helper also writes a compact `-biomechanics.png` figure. Its
first panel places the observed wrist and elbow journeys in punching-shoulder-
relative coordinates and shows the direct wrist reference line. Its second panel
compares wrist-path deviation with mirror-invariant elbow displacement from the
changing shoulder-to-wrist line over normalized start-to-impact time. Missing
elbow samples remain gaps rather than interpolated biomechanics.

## Replaceable diagnostic length scale

All current distances use one `diagnostic_length_scale_v1` record whose output
unit is the fixed median observed shoulder width. The record explicitly says
that this is not a physical measurement and exposes the analysis-pixels-per-unit
value and strategy in companion JSON. This is the replacement boundary for a
future user calibration based on a known anatomical length.

A manually measured forearm length could later help establish centimetre output,
but only when paired with a video-specific pixel observation of the same segment
and safeguards for foreshortening, camera distance, and view angle. A centimetre
value entered during onboarding cannot by itself convert arbitrary later pixels
to centimetres. The current plots therefore retain shoulder-width units until a
validated physical calibration contract exists.

Each event view also logs neutral punch and hikite measurements. Punch values
include peak outward wrist velocity and its frame/time, outward-onset-to-impact
duration, peak-velocity-to-impact duration, velocity at impact, terminal reach,
and terminal extension ratio. Hikite is sampled from the opposite arm at the
punch's theoretical-impact frame and records signed elbow displacement,
elbow velocity and angle, forearm-to-torso angle and deviation from 90 degrees,
wrist-to-shoulder and wrist-to-hip distances, wrist distance from the torso axis,
and projected position along that axis.

Hikite availability is `available` only when its core elbow displacement,
forearm angle, and wrist-to-hip distance are all present. A subset is `partial`;
no usable values is `unavailable`. `elbow_is_behind_centerline` is a descriptive
sign check, not a quality judgment. No universal pass/fail thresholds are
attached to these measurements.

The event view shades the braking phase and bounded terminal-confirmation
window. Vertical markers retain candidate peak, peak positive wrist velocity,
braking onset, theoretical impact, elbow arrival, cross-body arrival, retraction
onset, selected analysis frame, and a distinct snapshot frame. Labels include
both timestamp and frame number. Coincident markers remain separate records in
the companion JSON even when their vertical lines overlap visually.

Unavailable events still receive an event view. The title states the unavailable
reason, and no theoretical-impact marker is invented. Confirmation is reported
as `confirmed`, `partial`, `contradicted`, or `not_assessed`, together with the
supporting and available signal counts.

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

The machine-readable companion JSON retains the complete full-video series and
adds `event_views`. Each event view records its clipped plotted window, ordered
marker roles with frame numbers and timestamps, phase/confirmation intervals,
confirmation status and counts, unavailable reason, signal versions, coordinate
space, derivative convention, and the fixed-scale provenance. Plotting remains
an optional diagnostic dependency: production detection imports no Matplotlib,
and companion data is written before Matplotlib is loaded.

## Detector concern exposed by event-level validation

A fresh run of the ten-punch validation clip exposed a boundary-condition concern
for the first detected event. Its measurement window begins with the punching arm
already near terminal extension. Small wrist-reach fluctuations inside that hold
were sufficient to produce a positive local velocity peak, while elbow and
cross-body geometry were already terminal; the current estimator therefore
reported a confirmed theoretical impact even though the full outward approach
was not observed in the event window.

This is an estimator concern, not a reason to alter or cosmetically reinterpret
the plot. The diagnostic output retains the estimator result and makes the
missing approach visually inspectable. A separate detector change should require
evidence that outward onset represents a material approach from a non-terminal
state, while preserving valid events whose candidate window starts late. No such
production change is included with the event-view work.

These camera-plane signals do not measure metres, force, physical contact, or
clinical joint motion. Acceleration is especially sensitive to landmark noise;
it should be used to diagnose the event estimator and compare like-for-like
recordings, not as a universal performance threshold.
