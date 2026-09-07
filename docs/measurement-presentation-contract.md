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

The stored diagnostic signed distance follows the analysis coordinate
calculation. The presentation adapter converts only its display sign so that:

- a wrist visually above the straight reference line plots above graph zero;
- a wrist visually below the reference line plots below graph zero; and
- RMS and absolute deviations remain numerically unchanged.

This is a presentation transform, not a change to the straightness algorithm.

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
is packaged with the app and explicitly presented as an example. No personal
validation video or exported personal pose data is committed as a wiki asset.

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
