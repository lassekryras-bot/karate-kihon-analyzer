# App measurement wiki

For new work, follow the [authoring guide](measurement-wiki-authoring-guide.md)
and [starter prompt](prompts/new-measurement-wiki-entry.md).

## Entry and behavior

The wiki uses the shared white subpage header with an integrated Back arrow.
Its index presents each measurement title and description together in a flat
settings-style navigation card. Detail pages place the measurement title in the
shared header, group animation and playback controls in one flat card, and put
the graph and its explanation in another. Hikite keeps its selectable geometry
values with the animation. The calculation link uses a settings-style row and
opens a text card with shared Back navigation. Playback uses the existing player
icons with a flat accent-tinted button. The user-approved build passes; phone
visual review of this batch remains pending.

Open **Performance → Measurement wiki → Wrist path deviation**. One page shows
**Typical deviation (RMS)** and **Maximum deviation** from the same wrist path,
with one animation, signed graph and short calculation explanation.

The upper body moves in the fixed camera view. One display transform is chosen
for the replay: the dashed start-to-end line, past wrist positions and maximum
marker stay stationary. The trail grows as the punch plays. The far arm is drawn
behind the filled torso; the near arm and measurement overlays are in front.

Playback starts at the observed beginning and runs at half speed. Graph dragging,
an accessible SeekBar and Show maximum control the shared timestamp cursor.
Returning from the calculation page preserves position. Playback pauses when the
view detaches, hides or loses window focus.

## Data and registration

Both bundled examples include a `body_measurements_v1` snapshot with the example
owner's confirmed forearm length of 0.30 m. The source is a fixed example-owner
measurement, not the active viewer's profile. All installed wiki presentations
now use metres and metres per second, with angles remaining degrees and ratios
remaining dimensionless. This local data migration awaits an approved rebuild.
The earlier shoulder-width and upper-arm values documented below are historical.

`presentation.metric_scale.metric_bundle` uses a fixed median visible forearm
observation from the first 100 ms of the example's displayed analysis window.
The wrist pages use the camera-near elbow and wrist in aspect-correct pixels;
Hikite uses its recorded right elbow and wrist in the frozen fixture's coordinate
system. The measured 0.30 m forearm fixes the scale. Samples, references, markers,
distances and speeds scale together; angles, ratios, timing and rendered motion
are preserved. These are estimated camera-plane physical distances, not recovered
3D distances. The scale stores the reference side, frames and measured length.
Export callers can pass a body-measurement snapshot to the punch-path bundle
builder to produce metric presentations through the same converter.

New video recordings save a sibling `<clip>.body-measurements.json` snapshot of
the recording owner's forearm length, lower-leg length and height, in metres.
Guided sessions freeze one snapshot at session start for all clips. Missing
measurements remain null; there is no lookup of the current viewer on replay.
This recording metadata code is local and awaits a user-approved Android build
and tests. Existing recordings are not retroactively assigned measurements.

The installed catalogue has one wrist-path entry using `punch_path:event:5`.
That presentation now contains both summaries and the exported `maximum_marker`.
The exporter also retains a distinct maximum presentation for explicit metric
resolution; it does not create a second wiki navigation item. This is support
for two summaries of this path, not a generic arbitrary-metric grouping engine.

The approved pose-only right-hand punch 5 remains the example. Its motion is
unchanged; no source video is bundled. Default and per-entry example selection
still resolve the entire presentation and its referenced motion together.
Exercise selection remains independent of wiki overrides.

## Fixed-camera measurement

`build_fixed_camera_path_presentation` uses the diagnostic path sample identities
only to preserve the observed window. It reads each wrist's raw semantic pose,
converts normalized x/y with the actual image dimensions, then divides by the
existing fixed output scale. It does not subtract shoulder movement. It recomputes
RMS, maximum, graph, path efficiency and maximum projection together.

The exported reference is `fixed_analysis_camera`; overlays explicitly use
`fixed_analysis_camera_output_units`, with `camera_wrist` and
`camera_reference_point` coordinates. The native renderer rejects older
shoulder-relative presentations instead of drawing inconsistent values.

The installed example gives RMS **0.063692**, maximum **0.135156**, with maximum
at frame **229**, **3.817 seconds**. Units remain the bundle's existing shoulder
width unit; no unit conversion or calibration has been implemented by this change.
The page contains no calibration or setup explanation.

## Validation and remaining limits

Regression checks cover the installed catalogue and shared motion, both summary
recomputations, sign and pose alignment, fixed-camera versus shoulder movement,
mirroring, maximum ties and invalid data. Native compilation/tests are attempted
through Gradle; this workspace cannot download the Gradle 9.3.0 distribution
(network unreachable), so compilation and device rendering remain unverified.

Before release, inspect playback, scrubbing, stationary overlays, method return,
large fonts, light/dark themes, TalkBack and unavailable states on Android.
Wiki-specific activity restoration, localization and exercise-result entry into
this native renderer remain future work. Local source changes are not a deployed
app update.

## Wrist speed

A second catalogue page, **Wrist speed**, selects `wrist_speed:event:5` and reuses
exactly the same motion as the path page. It shows the camera-relative wrist
trail and a nonnegative speed graph, maximum speed and Show maximum speed.
No dashed path reference or perpendicular distance connector appears on this page.

For both pages, the animation's maximum marker appears only when the selected
frame reaches the maximum timestamp, stays on the completed trail, and disappears
when scrubbing back before that moment. The graph retains its maximum marker as
a navigation/reference point throughout.

Speed is estimated by fitting x and y against timestamps within ±50 ms of each
sample, then taking the velocity magnitude. At least three samples are required;
the start/end use available frames. The pose and drawn wrist trail are unchanged.
The calculation page briefly explains smoothing and its effect on short peaks.

The packaged speed example uses one median camera-near upper-arm image length
from the initial 100 ms, with selected side/frames recorded in provenance. This
is the approved preview's reference strategy, not a completed calibration flow.
It produces **11.974932 upper-arm lengths/s**, frame **237**, **3.950 seconds**.
The Python export API optionally accepts `upper_arm_length_m` to provide m/s;
no measurement has been supplied for the installed example, and there is no new
calibration UI or CLI option in this change. Scale and sample failures are explicit.

## Hikite phone preview

Performance → Measurement wiki → Hikite now loads the approved pose-only punch 6
(right hikite arm nearest the camera) with a dedicated native Canvas renderer.
Playback and the graph show frames 271–295, trimming only idle presentation time.
The original speed window and fixed scale remain unchanged. The peak remains
14.716118 upper-arm lengths/s at frame 289. Both arms are neutral, with no elbow
trail or elbow-angle overlay. The elbow maximum ring appears only at its sample.
Shoulder-line distance, forearm-to-torso angle at the wrist, and wrist-to-torso
position are selectable; their lines appear only at the finish. Initial view is
the finish; Play restarts at the shortened beginning. The calculation page
preserves selection and pauses playback.

Wrist bend is explicitly unavailable. The separate hand-tracking experiment is
included for investigation; it has not produced inference results on this clip.
Phone rendering, large text, TalkBack and Android build still require verification.

## Shared animation controls

All installed wiki animations now use one native player control. The progress
bar sits directly below the figure. A centred icon changes between play, pause
and replay; Jump to on the left always includes Start and Finish and adds the
page's maximum highlight; the selector on the right offers ×1, ×0.75, ×0.50,
×0.25 and ×0.10. The old separate maximum and playback buttons are removed.

Hikite shows live frame values in a quiet three-column list below the controls:
measurement, value and unit. Its geometry rows select the corresponding
finish-only overlay. Elbow speed updates in the same list, and wrist bend remains
explicitly Not measured. The head circle uses one median size for the replay and
is horizontally placed on the current shoulder-to-hip centre line instead of
following the nose-biased pose point.
