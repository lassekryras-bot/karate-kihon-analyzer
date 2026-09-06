# Target-height estimation foundation

## Status and terminology

This is an engineering foundation, not a validated karate-coaching model. A
**target definition** names the curriculum intent; a **target estimate** is one
calculated location for one practitioner/setup; **estimation uncertainty** says
how uncertain that location is; and **coaching tolerance** is the independently
configured acceptable performance zone.

Stable explicit targets are `JODAN_CHIN`, `CHUDAN_SOLAR_PLEXUS`,
`GEDAN_LOWER_ABDOMEN`, and `GEDAN_GROIN_LEVEL`. The broad Jōdan/Chūdan/Gedan
level remains metadata, never a substitute for the explicit target. The first
MVP remains Jōdan. No evidence in the repository selects either Gedan definition
for a future first Gedan activity, so callers must make that choice explicitly.

## Coordinate frames and invariant

The named 2D frames are source-image pixels, displayed-image pixels,
aspect-corrected 2D, gravity/camera-roll-corrected 2D, and
neutral-body-normalized 2D. MediaPipe world coordinates are a separate named
space and are not image coordinates.

MediaPipe normalized image coordinates are transport values, not an analytical
Euclidean space. Before a distance, projection, angle, unit vector, or
perpendicular is calculated, convert with:

```text
x_source_px = x_normalized * source_width_px
y_source_px = y_normalized * source_height_px
```

`FramedPoint2D` enforces like-frame operations. `FrameGeometry` separately maps
source analysis pixels to displayed/saved pixels, retaining crop, affine
rotation, scaling, and mirroring. Display transforms must never feed back into
analysis. Pose world points must never be multiplied by image dimensions.

## Provenance hierarchy

Every estimate carries one of these sources:

1. `INSTRUCTOR_APPROVED_PERSONAL`
2. `APP_DERIVED_PERSONAL_ANATOMY`
3. `CURRICULUM_POPULATION_ESTIMATE`
4. `GENERIC_PROVISIONAL_ESTIMATE`
5. `USER_DEMONSTRATED_BASELINE`

The order is descriptive, not an automatic truth ranking. In particular, a
demonstrated baseline records what the user did; it does not prove correctness.
The initial estimator is generic and provisional and therefore abstains from
coaching even when its landmarks are technically usable.

## Neutral reference and quality

The setup collector median-aggregates bilateral shoulder and hip landmarks from
multiple frames. It records the hip/body origin, shoulder and hip midpoints,
fixed neutral axis, torso pixel scale, source dimensions, interval, frame count,
landmark quality, confidence, and structured invalidation reason. Missing or
low-visibility bilateral landmarks, too few valid frames, degenerate torso
geometry, and excessive setup motion invalidate the reference. Invalid source
dimensions are rejected by `FrameSize`; no raw normalized-y fallback exists.

Camera roll is represented but currently unknown. A production camera-geometry
gate must mark unsupported geometry degraded/invalid rather than guessing.

## Lifecycle and repetition locking

The deterministic states are `UNINITIALIZED`,
`COLLECTING_NEUTRAL_REFERENCE`, `READY`, `LOCKED_FOR_REPETITION`, `DEGRADED`, and
`INVALID`. A valid neutral reference produces a ready estimate. Starting a
repetition locks its target offset and neutral axis. During the repetition, the
target may translate by the change in a stable tracked body origin, but it does
not rotate with instantaneous torso lean and is never relearned. Current torso
axis/lean remains a separate diagnostic.

The recorded-video diagnostic pipeline now selects a setup window before the
first existing strike-region boundary, constructs one session neutral reference,
and creates a separate locked controller for every detected repetition. At the
already-selected analysis frame, only origin translation is applied; the target
axis stays fixed while current torso lean is reported separately. The existing
strike boundaries are the smallest available repetition-start interface and do
not claim to be a separately validated repetition-onset detector. Legacy Jōdan
output remains unchanged.

## Setup-window selection and pipeline integration

Selector v1 scans timestamped Pose frames in frame-number order and only before
the first credible strike region (with a guard frame). It requires one
contiguous run: unusable or missing frame numbers reset the run, rather than
allowing scattered samples to be aggregated. Bilateral shoulders and hips are
converted from normalized values to source pixels before torso geometry is
calculated. A candidate is rejected for landmark quality, degenerate torso,
origin motion, torso-scale range, torso-axis deviation, or wrist motion. The
latter prevents an otherwise stable torso during clear punch motion from being
accepted.

The configurable defaults are deliberately provisional: 5 consecutive frames,
0.5 landmark visibility, 10 px minimum torso length, 0.08 torso-length maximum
origin step, 0.10 torso-scale range, 8 degrees axis deviation, 0.18 torso-length
maximum wrist step, and a 1-frame pre-strike guard. They are engineering gates,
not validated view-angle, anatomy, or coaching thresholds.

The diagnostic sequence is pose timeline → setup selection → median neutral
reference → per-event target lock → existing analysis-frame lookup → translated
target geometry → event diagnostics → optional snapshot overlay. The selected
window ID/version is retained by the neutral reference. The snapshot renders a
filled band whose two boundary lines are perpendicular to the fixed neutral
axis, plus visually distinct neutral and current torso axes, through the
existing source-to-display affine transform.

Every failure is isolated to `target_height_diagnostic`: missing setup, neutral
construction failure, incompatible dimensions, missing configuration/geometry,
or unreliable analysis-frame origin causes structured abstention. It does not
alter strike detection, impact-frame selection, legacy Jōdan analysis, or other
events. Generic estimates are clearly marked `PROVISIONAL_DIAGNOSTIC_ONLY`,
carry `GENERIC_PROVISIONAL_ESTIMATE`, and cannot authorize coaching or emit a
too-high/too-low/correct verdict.

### Naming boundary with the legacy Jōdan path

The existing `analysis.jodan_height` object is the legacy, user-visible
reference/angle classifier. The new session and event
`target_height_diagnostic` objects are provisional target-lifecycle diagnostics;
they neither replace nor alias that classifier. `target_estimate`,
`neutral_reference`, and `current_torso_axis` are renderer inputs derived from
the diagnostic object. Keeping the `diagnostic` suffix at both session and event
scope prevents consumers from mistaking this output for a second production
height score.

## Estimator extension points and assumptions

`TargetEstimator` has dedicated Jōdan, Chūdan, and Gedan strategy extension
points. `FaceAssistedJodanEstimator` is only an interface: this slice neither
runs continuous Face inference nor declares a Face Mesh vertex to be the final
chin construction. The provisional Jōdan configuration preserves the existing
15% torso-scale coaching tolerance as a named legacy-compatible value. Its
separate 10% estimation uncertainty is explicitly provisional and disables
coaching. No new Chūdan or Gedan body ratios are supplied.

Debug payloads expose target ID, centre/bounds, coordinate frame, provenance,
confidence, uncertainty, tolerance, neutral-reference identity, warnings, and
abstention. Optional snapshot diagnostics draw the target zone, neutral axis
(cyan), and current torso axis (orange) through `FrameGeometry`; normal legacy
results do not gain this extra panel unless the payload is present.

## Unresolved research and curriculum decisions

- Which explicit Gedan target the first Gedan activity teaches.
- The validated Face Mesh construction for Jōdan chin height.
- Validated Chūdan and Gedan torso relationships.
- Final coaching target-zone widths.
- Supported camera/view-angle envelope and roll policy.
- Validated landmark, neutral-stability, estimator-confidence, and coaching
  thresholds.

These require curriculum ownership and/or validation data; they must not be
answered by silently adding precise constants.
