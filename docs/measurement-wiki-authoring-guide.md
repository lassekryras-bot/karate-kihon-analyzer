# Measurement wiki authoring guide

Status: version 2 authoring decisions, implemented locally for the wrist-path page. This guide defines the intended consistency
of measurement pages; it is not a claim that every reusable component already
exists. Read alongside [current implementation](app-measurement-wiki.md) and the
[data contract](measurement-presentation-contract.md).

## Purpose and measurement boundaries

Explain a training question so a practitioner can understand the values and see
what produced them. Group related measurements on one page when they share the
same movement, reference and graph. RMS and maximum wrist deviation belong on
one wrist-path page with separate values and one calculation link. Use the same presentation
concept for a packaged wiki example and, later, a selected exercise result.

Keep these concepts separate:

| Concept | Measurement |
| --- | --- |
| Directness | Path efficiency |
| Lateral wandering | RMS wrist deviation |
| Largest excursion | Maximum absolute wrist deviation |
| Directional smoothness | Short-window direction changes |
| Elbow organization | Deviation from an explicitly defined elbow reference path |
| Repeatability | Variation across comparable punches |

Do not combine them into a straightness score. Speed, reach and hikite geometry
are separate measurements too. A straight start-to-end path does not establish
that the endpoint is the correct karate target. An assumed elbow reference path
must not be described as a validated ideal.

## The two-page pattern

### Visual explanation

Use this sequence, maintaining the app's theme colors, rounded card surfaces,
text hierarchy, spacing conventions and Back navigation:

1. **Title:** a short, practitioner-facing measurement name.
2. **Purpose:** one sentence saying what question the number answers.
3. **Example identity:** clearly say this is an example punch, or identify the
   selected exercise punch when exercise integration exists. Do not call a demo
   an ideal or correct punch.
4. **Upper-body figure:** shoulder, upper arm, elbow, forearm and wrist; torso
   polygon; circular head without face landmarks. Omit legs and hip point dots.
   Draw the far arm behind the filled torso and the near arm in front.
5. **Figure explanation:** directly below the figure; describe the visible
   motion, reference and overlays in one or two short, everyday sentences.
6. **Graph:** coordinated with the figure by timestamp; label time and units.
   Signed values have a visible zero line and match the visual sign convention.
7. **Graph explanation:** directly below the graph; explain axes, zero, meaning
   of excursions and how to drag, using short training-focused copy. Name only
   colors actually drawn; avoid technical detail in the caption.
8. **Controls and value:** graph dragging, accessible slider, play/pause at half
   speed, selected frame/time, measurement value with explicit units, and a short
   interpretation. Highlight the relevant sample when it explains the value.
9. **Method link:** “How is this calculated?” opens the paired calculation page.

Red is the current wrist trail and measurement curve; the dashed foreground line
is the reference. Red is not automatically an error flag. Distinguish elements
with line style, labels and markers as well as color. Add another color only
when it has a clear role explained in the text.

The guide line and measurement overlays remain in front of the torso. Preserve
image aspect ratio and use one fixed display transform throughout playback.
For the wrist-path page, draw the body in the fixed camera view, keep the dashed
start-to-end line stationary and leave each trail point where the wrist was
recorded. Do not lock the moving shoulder to the screen or translate historical
trail points with it. Compute RMS, maximum, graph and marker from that same
fixed-camera path. Begin at the observed start so playback shows the full punch;
Show maximum seeks to the selected excursion.

This reference choice applies to wrist-path deviation, not every future metric.
An explicitly body-relative measurement may require another reference, but its
pose, overlays, graph and values must agree. Coordinate reference and length
scale are separate decisions. Reuse the original shared motion data.

Pause playback when leaving the page or losing focus. Returning from mathematics
preserves the source punch and position. Respect unavailable data instead of
showing zero or silently substituting a different example. Camera-near is not a
permanent synonym for right arm; use visibility, depth provenance and event side.

### Calculation page

Use a short plain-language explanation first: what we follow, what we compare
it with, and how we select or summarize the distance. This optional page can be
a little more detailed than the captions, but it should still serve training.
Formulas are optional, not a required mathematical derivation. If used, explain
the symbols and meaning in ordinary language. Keep RMS distinct from an
arithmetic average; do not teach an incorrect shortcut to simplify the text.

A short worked example may use the selected result. Do not invent numbers.
Keep detailed sampling, missing-data rules, scale, calibration and method/version
provenance in the data contract and engineering documentation. Do not copy that
technical checklist into the end-user calculation page. General camera setup,
arm-length calibration and estimation explanations belong in separate learning
content, outside this page and this task unless the user asks for them.

## Wording and accessibility

Write short connected sentences suitable for reading aloud. Prefer “wrist” to
“landmark” in introductory copy. Explain the quantity before implementation
terms. Avoid universal good/bad thresholds, unsupported coaching conclusions,
clinical claims, or claims of force from camera-plane geometry.

Say “smaller means closer to this reference” when justified, rather than “smaller
is better karate.” Show a brief unavailable state when needed; do not repeat
general estimation disclaimers in every caption.
Do not use “average” for RMS without explaining the difference. Units must come
from the data: current distance values are shoulder widths. Estimated centimetres
require an implemented calibration strategy and provenance. Do not relabel them.

Support large text, theme contrast, meaningful control labels, heading semantics,
and a slider alternative to graph dragging. Do not make a moving graph the only
way to understand the value. Native screen checks include TalkBack.

## Architecture and extension points

Paths below are relative to the repository root:

| Responsibility | Location |
| --- | --- |
| Measurement computation and diagnostic samples | `src/karate_analyzer/diagnostics/motion_plots.py` |
| Portable presentation export | `src/karate_analyzer/presentation/punch_path.py` |
| Reference example-selection policy | `src/karate_analyzer/presentation/catalogue.py` |
| Native wiki and initial renderer | `android/KarateClipRecorder/app/src/main/java/dk/lasse/karatecliprecorder/wiki/MeasurementWikiView.kt` |
| Discoverable wiki entries and copy | `android/KarateClipRecorder/app/src/main/assets/wiki/catalogue.json` |
| Installed example bundles | `android/KarateClipRecorder/app/src/main/assets/wiki/examples/` |
| Navigation | `MainActivity.kt` and `profile/ProgressScreenView.kt` under the app Java package |
| Export and asset regression tests | `tests/presentation/` |

The analyzer owns measurement values, selection evidence, windows, quality,
scale and provenance. Production detection must not import plotting code.
The app owns drawing, interaction, text and navigation. The presentation contract
is `karate_measurement_presentation_v2`: `motions` holds reusable semantic poses;
`presentations` holds measurement-specific overlays, graphs, summaries and a
`motion_id`. Synchronize with absolute `timestamp_ms`, not graph-local progress.
Keep candidate, theoretical impact, analysis and snapshot frames distinct.
Do not alter impact detection merely to improve a demonstration.

The wiki index enumerates the installed catalogue. Adding a supported entry
creates its navigation item; adding an arbitrary renderer ID does not create a
renderer. Register native drawing support explicitly. No downloaded executable
code or independent list of wiki pages is needed.

### Current limits to account for

The shared renderer shows RMS and maximum together for the wrist-path entry.
Its primary presentation contains both summaries and `maximum_marker`. It
requires `fixed_analysis_camera` data and explicit fixed-camera overlays. Other
measurements and units still need their own supported renderer behavior; this is
not a generic arbitrary-metric grouping system.

Catalogue entries currently contain `measurement_id`, `renderer_id`, `title`,
`description`, `figure_text`, `graph_text`, `method_text`, and optional
`wiki_example`. The description appears in both the index and detail pages as the purpose sentence. Text is currently English;
localization infrastructure and wiki-specific activity restoration remain future
work. Exercise-result entry into the native wiki renderer is not implemented.

## Default demonstration and per-measurement overrides

The user approved pose-only right-hand punch 5 as the initial common demo. Use
`wiki/examples/basic-right-punch.json`; do not bundle the personal video. Treat
new personal footage/data separately from this existing authorization.

A motion can be shared by multiple measurements, but each needs its own matching
measurement presentation. A new measurement ID must not resolve to the RMS
presentation merely because it uses the same punch. Give it a distinct
presentation ID and reference the shared `motion_id`.

Currently `default_example` selects both an asset and one presentation ID.
For a second metric in the same bundle, supply `wiki_example` selecting that
metric's presentation. For future many-metric defaults, evolve resolution to
select by measurement ID explicitly and add migration tests; do not assume that
behavior already exists.

RMS and maximum currently share the primary `punch_path:event:5` presentation on
one page. A separate `punch_path_maximum:event:5` presentation remains available
for explicit metric resolution; it is not registered as a separate wiki page.

A later acted-out example may point to a different bundle. Resolve its graph and
motion together. Overrides apply to wiki examples; exercise views must continue
to use the selected exercise result. Validate version, measurement identity,
presentation identity and motion reference, with a clear unavailable state.

## Workflow and verification

1. Read this guide, the current implementation and contract; inspect the branch
   and relevant PR before editing. Preserve unrelated work.
2. Specify the quantity, formula, reference frame, window, units, source summary,
   quality rules, and visual feature that explains the number.
3. Reuse existing measured data where it represents the same quantity. Add an
   adapter or new computation only when necessary; never derive a different
   quantity silently in the UI.
4. Add the matching presentation, catalogue entry, text and renderer support.
   Use a shared motion or an explicit complete demonstration override.
5. Verify identity resolution, units, timestamps, sign/coordinate alignment,
   summary recomputation, missing data, and relevant edge cases. For maxima,
   test magnitude and frame/timestamp selection, including tied maxima.
6. Run the relevant Python tests and Android compilation/tests. For code changes,
   run the complete Python suite, compilation and `git diff --check`. For prose-only
   changes, check links and diff formatting; do not add implementation-mirroring tests.
7. Review native rendering: foreground overlays, body occlusion, figure/graph
   alignment, half-speed playback, scrubbing, method return, large fonts, themes,
   TalkBack and unavailable states. Distinguish tests run from blocked checks.
8. For visual work, provide a playable in-chat preview using the selected data,
   with a concise summary of changes for discussion. Automated tests do not
   establish that the motion is understandable. Distinguish preview behavior
   from native app behavior; carry accepted changes into the existing app too.
9. Report the page added or extended, displayed value/unit, example source, files changed,
   validation and remaining limitations. Follow the user's current publication
   instructions; a guide does not create an additional approval requirement.

## Current wrist-path implementation

`build_fixed_camera_path_presentation` uses the existing observed path window
and shared raw wrist poses to compute the camera-relative path. It recomputes
RMS, maximum, graph and path efficiency in the same coordinates and preserves
the existing scale record. `build_maximum_deviation_presentation` supplies the
largest sample and perpendicular projection. The primary presentation carries
that marker too, so both values share one native page.

Overlays use `camera_wrist` and `camera_reference_point` in fixed-camera output
units. Exact magnitude ties select the earliest timestamp, then lowest frame
number. Invalid evidence leaves the presentation unavailable. The native page
starts at the observed beginning and provides Show maximum. Camera transforms
and past positions remain fixed during replay; detector and impact selection
are unchanged. Native build and device verification are still unverified in
this workspace, although the source and installed assets are updated.

## Agreed short copy for the wrist-path page

- Animation: “The red trail follows your wrist. The dashed line shows a straight
  path from start to finish. The circle marks the largest deviation.”
- Graph: “See how the distance changes during your punch. Drag along the graph
  or tap Show maximum.”
- Values: “Typical deviation (RMS)” and “Maximum deviation”.
- Calculation: “We draw a straight line from your wrist’s starting position to
  its finishing position. Maximum deviation is the greatest distance from that
  line. RMS summarizes the distances across the punch, giving larger deviations
  more weight.”

Use the [starter prompt](prompts/new-measurement-wiki-entry.md) for the next task.

## Maximum markers and speed page

Reveal a maximum marker in the body animation when the selected frame reaches
its timestamp. Keep it on the completed trail afterwards; hide it again when
scrubbing earlier or restarting. Do not place a future marker in empty space
before the wrist gets there. The graph can retain its maximum marker throughout,
and Show maximum selects the corresponding moment directly.

The native renderer also supports the camera-relative wrist-speed page. It uses
the same recorded motion, a nonnegative speed graph, maximum speed and no path
reference line. Speed units come from its exported scale: upper-arm lengths/s
or m/s. Keep scale/setup instructions out of user-facing copy. Explain smoothing
briefly behind How is this calculated?, including that the animation is unchanged
and a short peak may be softened or shifted. Detailed method and scale selection
belong in the presentation contract.

For hikite, omit the elbow trail and draw both arms neutrally. Show selected
position lines only at the finish; draw the forearm/torso angle at the wrist.
When there is no historical trail, show the body maximum ring only at the peak
sample so it does not hang in empty space. Trim unnecessary idle lead-in from
playback and graph together, retaining a brief view before movement. A display
trim must preserve computed measurements and their original sampling provenance.

## Shared animation player

Present every wiki animation as a familiar compact media player. Put a scrubber
directly below the figure. Under it, keep play, pause or replay centered, place a
single Jump to selector on the left, and a speed selector on the right. Standard
speed choices are ×1, ×0.75, ×0.50, ×0.25 and ×0.10. Start and Finish are always
available jump points; add only measurement-specific highlights such as Maximum
speed or Maximum deviation. Do not add separate buttons for those same frames.

For measurements that change with the selected frame but do not need another
graph, use the shared table-like metric list below the player. Give each row a
short measurement name, live value and separate unit. A selectable geometry row
may choose its figure overlay without becoming a large button above the figure.
Unavailable measurements remain explicit in the same list.

Use one stable head size throughout a pose-only replay. When the source head
point is effectively nose-biased, place the simple head circle on the current
torso centre line while retaining its recorded vertical level.
