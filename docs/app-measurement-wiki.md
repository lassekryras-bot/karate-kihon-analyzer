# App measurement wiki

For new work, follow the [authoring guide](measurement-wiki-authoring-guide.md)
and [starter prompt](prompts/new-measurement-wiki-entry.md).

## Entry and behavior

Open **Train → Measurement wiki → Wrist path deviation**. One page shows
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
