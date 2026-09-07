# Measurement presentation contract

## Purpose

The analyzer can export renderer-neutral data for interactive measurement
explanations. The first contract supports punch-path straightness. It is the
boundary between measurement computation and the installed application's user
interface; it is not a coaching report or an independent detector.

The analyzer owns:

- measurement values and method identifiers;
- event, frame, timestamp, scale, quality, and provenance data;
- semantic upper-body points needed for an animation;
- the straight start-to-impact reference line;
- spatially aligned graph samples; and
- stable keys linking a visual explanation with its mathematical method page.

The application owns:

- Android drawing and graph interaction;
- scrubbing animation frames by normalized timestamp progress;
- localized visible and spoken text;
- navigation between the visual and mathematical pages;
- selection of a current punch or a bundled wiki example; and
- interpretation and coaching language.

Matplotlib and HTML are not part of this contract. Matplotlib remains an
optional developer diagnostic renderer. The interactive HTML prototype is a
design reference, not the phone runtime. An Android application should render
the contract with its native UI toolkit; a separate UI module may be shared by
multiple application features and compiled into the application package.

## Export

Generate presentations for all detected events:

```shell
karate-analyzer export-measurement-presentations \
  --landmarks output/video_landmarks.json \
  --events output/punch_event_landmarks.json \
  --output output/measurement-presentations.json
```

Use `--event-index` to export one current punch. Export does not import or
require Matplotlib.

The public Python entry point is:

```python
build_punch_path_presentation_bundle(
    diagnostic_companion,
    video_landmarks,
    event_index=None,
)
```

## Contract shape

`karate_measurement_presentation_v1` contains one or more presentations. Each
presentation includes:

- `measurement_id` and `measurement_method_id`;
- event index, side, and semantic punching-wrist role;
- explicit availability, reason, and valid-sample count;
- RMS deviation, maximum deviation, and path efficiency summaries;
- the complete length-scale record;
- semantic head, shoulder, elbow, wrist, and hip points;
- a four-point torso polygon and explicit arm/torso/overlay layer order;
- the wrist start-to-impact reference line;
- graph samples sharing frame number, timestamp, and normalized progress with
  animation frames;
- visual-page and method-page content keys; and
- measurement provenance.

Raw MediaPipe landmark indices are deliberately excluded. Coordinates retain
their explicit analysis-image convention: x increases to screen right, y
increases down, and smaller z is nearer the camera.

## Spatial graph convention

The current adapter recomputes camera-relative distances from recorded poses
and chooses the display sign so that:

- a wrist visually above the straight reference line plots above graph zero;
- a wrist visually below the reference line plots below graph zero; and
- RMS and absolute deviations remain numerically unchanged.

The display sign does not change RMS or maximum magnitudes. The fixed-camera
reference migration itself does change these values; see the method below.

## Two linked pages

The app uses `linked_content` to pair:

1. `punch_path_straightness.visual`: current-punch animation, synchronized
   graph, result, quality, and concise explanation; and
2. `punch_path_straightness.method`: start and impact selection, perpendicular
   distance, RMS formula, scale conversion, limitations, and method version.

An exercise result supplies the current event presentation. The wiki uses the
same renderer with a deliberately selected, anonymized reference bundle. Back
navigation should preserve the event and selected graph position.

## Scale evolution

The current diagnostics still use the fixed robust video median shoulder-width
scale, and every exported unit states that provenance. A later app-calibrated
upper-arm strategy must receive a new scale strategy and unit name rather than
silently changing fields that currently mean shoulder widths.

For a controlled side-view punching activity, the intended future flow is:

1. store the user's measured upper-arm length;
2. observe the camera-near shoulder-to-elbow segment in a calibration pose;
3. derive one fixed session scale from several reliable frames; and
4. export both upper-arm-normalized values and estimated centimetres with
   calibration confidence.

The user-visible centimetre value is an estimate. The normalized source value,
pixel observation, anatomical length, calibration frames, side, and strategy
must remain available for auditing and future recalculation.

## Version 2: shared motion and wiki example selection

Version 2 replaces the initial, unpublished version 1 structure. The exporter now
emits `karate_measurement_presentation_v2`. Consumers must check that version.

`motions` is a map keyed by punch ID. Each entry owns semantic pose frames for
its full event window, event identity, frame-role markers, intervals, and body
layering. Measurements reference it with `motion_id`. Reference lines and wrist
trajectories belong to presentation `overlays`; graphs and summaries remain
measurement-specific. Multiple measurements can reference the same motion.
Playback and graph scrubbing synchronize by absolute `timestamp_ms`; graph-local
normalized progress must not be used as progress through the full motion window.

An app measurement catalogue entry provides `measurement_id`, renderer ID,
localized visual/method content keys and an optional `wiki_example`:

```json
{
  "measurement_id": "punch_path_typical_deviation_rms",
  "renderer_id": "pose_with_path_graph",
  "wiki_example": {
    "bundle_id": "demonstration-curved-punch",
    "presentation_id": "punch_path:event:5"
  }
}
```

The wiki enumerates registered measurement entries, rather than maintaining a
second list of pages. A measurement-specific example overrides the default wiki
example. It can be a purpose-recorded demonstration. The complete example bundle
is packaged with the app and explicitly presented as an example. Personal validation video is never bundled. A user-approved pose-only excerpt
may be packaged as a clearly labelled example; the initial wiki uses right-hand
punch 5 with explicit user authorization.

`presentation.catalogue.resolve_measurement_presentation` is a reference resolver
for this policy. In wiki context it selects the override or default asset and
resolves both measurement and referenced motion. In exercise context it always
uses the selected current result, ignoring wiki overrides. Missing assets,
unsupported contract versions, measurement mismatches and broken motion references
raise errors; the app should surface an unavailable explanation. Returning from
the method page preserves the selected source and timestamp.

This commit supplies the data boundary and reference selection policy. The native
Android renderer, build-time catalogue generation and localized wiki screens are
application integration work, not implemented by the Python exporter.

## Fixed-camera wrist path and grouped summaries

The wrist-path method is now `fixed_camera_start_to_impact_wrist_path_v1` with
`coordinate_reference: fixed_analysis_camera`. This supersedes the earlier
shoulder-relative presentation, while retaining v2 motion/presentation structure
and stable selection IDs. Consumers must check the reference and method, not
only the container version; the native renderer rejects the old reference.

The adapter preserves diagnostic sample identities and their observed window,
then measures raw wrist positions from shared semantic pose frames. Normalized
positions are converted with actual image width/height before division by the
fixed output scale. Shoulder movement is not subtracted. No extra smoothing,
interpolation, time weighting or detector changes are introduced.

RMS is sqrt(mean(d²)); maximum is max(abs(d)), where d is perpendicular distance
from each sampled wrist to the infinite line through observed start and impact.
Graph sign is positive visually above the line; for a vertical reference the
chosen algebraic sign remains deterministic but above/below is geometrically
ambiguous. Path efficiency is also recomputed from the same camera path.

Overlay `coordinate_space` is `fixed_analysis_camera_output_units`.
`trajectory_samples[].camera_wrist` and `reference_line.start/end` hold positions
in those output units. `maximum_marker` carries frame, timestamp, graph sign,
absolute magnitude, `camera_wrist` and `camera_reference_point` (the perpendicular
projection). All use one fixed camera origin. Display transforms must not depend
on the selected frame; overlays cannot follow the current shoulder.

Largest absolute magnitude wins; exact ties choose earliest timestamp then
lowest frame. `maximum_selection` versions that policy. Invalid image geometry,
scale, sample/pose correspondence, non-finite points, non-increasing times or
coincident endpoints produce an unavailable presentation. Partial/unavailable
source quality is preserved; missing values are never replaced with zero.

`punch_path:event:N` retains measurement ID `punch_path_typical_deviation_rms`
and includes both summary fields plus maximum marker. The native wrist-path page
shows them together through this one selection. The exporter retains
`punch_path_maximum:event:N` / `punch_path_maximum_deviation` for separate metric
resolution, using method `maximum_absolute_wrist_deviation_v1` and the fixed
camera source method in provenance. These IDs do not create two wiki pages.

Scale provenance and units remain unchanged by the reference migration. Existing
shoulder-width values cannot be relabelled as centimetres. Calibration changes
are outside this slice; user-facing copy does not repeat setup explanations.

## Camera-relative wrist speed

`wrist_speed:event:N` has measurement ID `camera_relative_wrist_speed`, method
`fixed_camera_local_linear_wrist_speed_v1`, and the same `motion_id` and observed
sample window as the wrist-path page. Graph samples use `speed_output_units`;
summary uses `maximum_speed_output_units`. `maximum_marker` carries the same
speed, frame/time and fixed-camera wrist point. It has no perpendicular projection.

`presentation.wrist_speed.build_wrist_speed_presentation` computes local least
squares slopes of recorded x/y against actual seconds within ±50 ms, then their
Euclidean magnitude. At least three samples per window are required; no synthetic
positions or times are added at boundaries. There is no pose smoothing. Peaks
can be attenuated or shifted by this window. Exact maximum ties choose earliest
time then lowest frame. Units are `upper_arm_lengths_per_second`, or
`meters_per_second` if a positive finite measured `upper_arm_length_m` is passed
to the Python bundle exporter. No CLI or calibration UI is added here.

Speed scale records one median upper-arm pixel length from the initial 100 ms,
minimum three finite nonzero observations with shoulder/elbow visibility ≥0.5.
Camera-near side comes from the shared motion's depth-based arm layering, not a
hardcoded right-side assumption. Side, reference frames, pixel length, optional
measured length and local-slope method are exported. The installed example uses
the preview's reference strategy; it is not represented as validated calibration.
Display camera coordinates scale with the chosen unit so wrist overlays remain
identical in pixels when switching units. The original shared motion is unchanged.

## Frozen hikite example extension

The dedicated `hikite_pose_graph` renderer uses a pose-only approved punch-six
fixture, not a new production analyzer export. Its v2 presentation is
`hikite:event:6` / `hikite_finish`, referencing `motions["punch:6"].samples`.
These renderer-specific samples carry `f` (original frame), `t` (absolute ms),
`p` (semantic camera positions in upper-arm units), `radius`, elbow `point`,
`speed`, shoulder/hip midpoints, perpendicular `foot` and `shoulder_foot`,
`behind`, `shoulder_distance`, and `forearm_angle` in degrees. x points right,
y down. Positive behind is opposite camera-right for this particular recording.
This extension is not accepted by the existing wrist-path renderer.

Method provenance preserves source window 248–295, fixed right upper-arm median
from frames 248–254, and ±50 ms local linear elbow speed. Display starts at 271;
trimming does not recompute scale or speed. `maximum_marker.camera_elbow` contains the peak elbow location; the renderer
uses the matching pose elbow. The frozen fixture contains
no video, face landmarks or measured metric arm length. Hand/wrist bend remains
explicitly unavailable. A general analyzer export and exercise integration are
future work; do not apply these example-specific side/window choices globally.
