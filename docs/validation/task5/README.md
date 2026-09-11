# Task 5 real validation — 2026-09-11

Status: DONE (offline validation and proposed review completed; **capture validation did not pass**).

The unchanged `conservative-v1` cannot establish a quiet baseline in this
recording. Both complete-sequence runs remain `BASELINE`, produce no transitions,
and report `NOT_ARMED`. No parameters or production algorithms were changed.
CameraX integration has not begun.

## Source and preservation

The user supplied and authorized committing `input/task5/1000002073.mp4` and
`input/task5/video_landmarks.json` (renamed from `video_landmarks(1).json`). The
user confirms they belong to the same recording. Video metadata reports 701
frames, approximately 59.9966 fps, 1056 × 1354 pixels. Source frame numbers are
0–700; landmark timestamps are 0–11,667 ms. Sample overlays at frames 0, 50, 99,
344, 496 and 650 agree with the visible body ([alignment sheet](alignment.jpg)).
The JSON's historical source string is `input\videos\kihon-test.mp4`; it is
retained unchanged, not used to override the supplied provenance.

The existing importer was run first. An independent field-by-field audit then
confirmed exactly 701 timestamps and 23,133 pose landmarks, preserving every
normalized/world XYZ value and normalized visibility/presence value exactly in
the imported JSON, with matching IDs and OBSERVED provenance. There are no
import warnings, reordered frames, resampling, smoothing, or MediaPipe reruns.
The original export also retains its hands and other metadata; those are outside
the pose replay schema. World-specific confidence is not separately represented
by that schema. Kotlin's existing Point3/landmark models use Float precision;
JSON preservation does not imply bit-exact Double precision inside Kotlin.
SHA-256 values and audit details are in [import-summary.json](import-summary.json).

Requested commit `0c6bf94` could not be resolved locally or on origin. The user
explicitly approved continuing from main `71afca2`, containing importer `a7ccabc`
and harness `a95d450`. This is the baseline used, not a claim to have replayed
an unavailable revision.

## Unchanged replay and concrete failures

Parameters match the existing `MotionReplayValidationTest` conservative-v1:
extractor baseline samples 3; segmenter baseline/start/settling dwell 100 ms;
no-movement timeout 300 ms; start threshold 0.2; quiet threshold 0.1; minimum
coverage 0.7; terminal similarity 0.8; maximum stable displacement 0.1. All other
defaults remain unchanged. The imported fixture specifies DIFFERENT_STABLE_POSE.

1. [Full trace](full.trace.json), [diagnostic plot](full.png): all 701 frames,
   original importer metadata (no arming/cue/deadline). No arming request means
   this run alone cannot assess capture decisions.
2. [Arming-request trace](arm-request.trace.json), [plot](arm-request.png): all
   701 identical frames and parameters, with an explicit diagnostic arming
   request at 0 ms. No artificial cue, labels, deadline, baseline, or reset.
   Still no arming, because the baseline prerequisite never becomes true.

Each run was repeated with fresh extractor/segmenter state and produced identical
trace JSON. The runner processes the entire sequence even though it models one
armed capture, not a repeated-punch controller. No ten-way segmentation capability
is implied by these traces.

Baseline dwell is 0 ms throughout. Every available articulated-motion value is
greater than the 0.1 quiet threshold (minimum 0.115381, maximum 4.834321). Thus
there is no quiet evidence on which to accumulate baseline dwell, even during
visually held poses. The extractor also never builds its own reference (three
qualifying baseline samples at motion ≤0.12 and coverage ≥0.75); both similarity
channels are null on all 701 rows. This is a demonstrated usability failure for
this input, not proof that the visible person never holds still.

Articulated status is VALID on 321 frames, INSUFFICIENT_COVERAGE on 379, and
FIRST_FRAME on one. Numeric articulated estimates remain exported even when
the status is insufficient; they must not be described as absent or reliable
whole-body evidence. Image-space status is VALID on all 700 subsequent frames.
No timestamp gaps or invalid-scale statuses occurred. There are 99 clamped
landmark samples.

Completion coverage is below 0.7 on 377 frames and ranges 0.330–0.870, whereas
region-balanced coverage ranges 0.793–0.971. Low confidence in the obscured left
arm is the main recurring restriction (regional coverage below 0.65 on 380
rows, including the first row); the left leg also drops below 0.65 on 104 rows.
Detecting a pose on every frame is not the same as usable whole-body coverage.
Relaxing to the average would hide these missing-limb intervals.

## Proposed boundaries for the ten numbered punches

These are **PROPOSED**, not human-approved ground truth. They were selected from
video contact sheets and the full motion trace, including preparation,
opposite-arm withdrawal, torso rotation and final settling. Expect approximately
±5 frames (about 83 ms) uncertainty, particularly in subtle onset and settling.
Movement end is the proposed first held full-body terminal pose; the stable
interval extends only through the clearly held portion before the next movement.
Residual landmark noise and small body sway remain. These are not assertions
that the segmenter's quiet/stability conditions were met.

All frame numbers below are zero-based. Exact source timestamps and ambiguity
notes are stored in [proposed-labels.json](proposed-labels.json).

| Punch | Side | Full movement start–end | Visual terminal-stable interval |
|---|---|---|---|
| 1 | Right | 24–56 | 56–76 |
| 2 | Left | 80–110 | 110–123 |
| 3 | Right | 125–158 | 158–175 |
| 4 | Left | 179–210 | 210–224 |
| 5 | Right | 225–257 | 257–275 |
| 6 | Left | 277–307 | 307–324 |
| 7 | Right | 325–357 | 357–375 |
| 8 | Left | 377–407 | 407–425 |
| 9 | Right | 427–457 | 457–475 |
| 10 | Left | 477–507 | 507–525 |

Review evidence: [boundary sheet 1](boundaries-0.jpg),
[boundary sheet 2](boundaries-1.jpg), and
[boundaries over the full trace](proposed-boundaries.png).
The theoretical-impact frames 99, 144, 198, 243, 296, 344, 396, 444 and 496
remain context for punches 2–10 only. They were not fed to the segmenter or used
as full-movement ends. No impact frame was invented for Punch 1.

**Punch 1 is not evidently left-censored under this numbering.** Frames 0–23
show an already held left-punch position, followed by the full right-punch
preparation and extension beginning around frame 24. See the
[two-frame opening review](detail-0.jpg). The movement that produced the initial
left-punch hold is left-censored; it is a preceding movement, not the numbered
right Punch 1. If “Punch 1” instead means the pose already visible at frame zero,
that alternate numbering would be censored and would not match the importer's
side/impact context.

## Capture safety and behavior assessment

| Concern | Observed result and limit |
|---|---|
| Clipped starts | No start emitted for any numbered punch. Start offsets are unavailable; zero clipped-start flags is not a pass. The source contains the proposed onset of all ten. |
| Premature completions | No completion emitted. Cannot assess end offsets or plateau rejection after arming. |
| Abstentions | Neither full run arms; all ten target movements remain uncaptured. This is baseline abstention, not a NO_MOVEMENT_TIMEOUT or deadline event. |
| Extra pre/post roll | No selected capture interval, so detector pre/post-roll durations are unavailable. Relative to proposed Punch 1 onset, source pre-roll is 400 ms. After Punch 10 end at 8,450 ms, the source contains 3,217 ms more data. |
| Coverage | Repeated obscured-arm/leg confidence failures prevent trustworthy terminal evidence; average coverage conceals them. |
| Camera/scale | Image-space motion is 0.00557–1.36293 torso units/s; apparent scale-change rate is 0.000172–0.925541 per second (maximum at frame 331). It is a rate, not a 92.6% zoom. The fixed background appears stationary; torso turn/projection and landmark jitter can change estimated torso scale. This recording does not isolate actual camera zoom or translation. |
| Mirrored similarity | Both similarity channels are entirely unavailable because no extractor reference formed. Alternating sides are visible, but real mirrored discrimination and mirrored completion remain unvalidated. No forced reference or synthetic comparison was substituted. |
| Slow/plateau | Rolling 600 ms displacement reaches 0.7133 and persists through portions of visual holds, above the 0.1 stable limit. This memory can delay stability evidence; there are also residual motion estimates above quiet threshold. The clip shows brisk repeated movements and holds, not a controlled slow-motion challenge. No successful slow-motion or plateau-completion claim is justified. |

The recording contains **three additional complete-looking punches** after the
ten numbered punches: approximately frames 527–557, 577–607 and 627–657, plus
arm lowering near 685–700 (truncated by EOF). These are real additional movements,
not detector false positives or harmless still post-roll. The opening hold and
all later frames remain in both replays. See [full contact sheet 1](contact-0.jpg)
and [sheet 2](contact-1.jpg). The supplied “10-punch” description therefore refers
to a target subset, not the entire visible movement count.

The raw score is 400 / NOT_ARMED. Its zero extra-roll values and absent clipping
flags are sentinel outcomes with no selected interval. Labels are null, so the
score must not be ranked as a ten-punch safety result. The existing scorer also
treats an emitted start with null labels as FALSE_POSITIVE; future labeled runs
must distinguish unavailable review from reviewed no-movement examples.

The concrete baseline and coverage failures warrant a separate calibration
experiment, but a single side-on clip with unapproved boundaries is insufficient
to justify shipping threshold changes. This validation preserves conservative-v1
unchanged and does not weaken coverage to manufacture completions.

## Reproduction and checks

From repository root in PowerShell (Python dependencies from `pyproject.toml`):

```powershell
$env:PYTHONPATH = 'src'
python scripts/experiments/task5_real_validation.py --prepare
$task5Root = (Get-Location).Path
$env:JAVA_HOME = 'C:/Program Files/Android/Android Studio/jbr'
Push-Location android/KarateClipRecorder
./gradlew.bat :karate-analyzer-core:replayMotion "-PreplayInput=$task5Root/output/task5/real-kihon-10-punch.fixture.json" "-PreplayOutput=$task5Root/output/task5/full.trace.json"
./gradlew.bat :karate-analyzer-core:replayMotion "-PreplayInput=$task5Root/output/task5/arm-request.fixture.json" "-PreplayOutput=$task5Root/output/task5/arm-request.trace.json"
./gradlew.bat :karate-analyzer-core:test
Pop-Location
python scripts/experiments/task5_real_validation.py
python -m pytest tests/diagnostics/test_pose_replay_import.py tests/diagnostics/test_replay_motion_plots.py -q
```

The large derived pose fixtures stay under ignored `output/task5/` and are
reproducible from committed source. The traces, plots, audit, proposals and visual
review sheets are committed here. All 149 core JVM tests passed locally; eight Python
importer/plot tests passed. Plots and source-frame sheets were visually inspected.
No Android app build, device validation, deployment or CameraX work is claimed.
