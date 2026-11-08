# Exact ten-punch blocker matrix — 2026-11-08

Status: `OBSERVED` diagnostic evidence; not an approved production side or view rule.

## Evidence identity and handling

- Recording: `e9406fe1-90ba-4d0d-9558-ac6a518cc0db`
- Source SHA-256: `f87c0c3e93ef1af5d171353028ba47f34d20e9da665aae2062c66b57b13f1e35`
- Production Heavy landmark track: `4826f011-499f-4a12-b36a-d9b4b208f6a5`
- Landmark-stream SHA-256: `39091b93bba46594ca7ff2a752e86ed2eb762e99d7112ae0cdb4bd54fd2fff54`
- Current run: `9a733754-e41d-414a-be2c-a401e2909dec`
- Calibration: `e5d2fd39-039e-4f73-89c0-1d534ff357f1`
- Activity plan: `alternating-straight-punch-v1`, explicitly persisted by operator post-capture confirmation.

The personal MP4 and MLS remain outside the repository in system temporary storage. This record contains hashes, accepted boundaries, aggregate landmark-derived evidence, and provisional reviewer labels only. Labels are human-review observations, not an input that production analysis may use.

## Current end-to-end result

| # | Accepted logical bounds (µs) | End kind | Review side | View metadata | Current side | Current reason | Concurrent left-wrist evidence | Downstream blocker |
|---:|---:|---|---|---|---|---|---|---|
| 1 | 1,500,000–2,000,000 | `STOP_GATE` | LEFT | OTHER, 58.7° | LEFT | `SINGLE_SUPPORTED_SIDE` | −0.2232 over selected episode | view policy unsupported |
| 2 | 2,300,000–2,701,000 | `STOP_GATE` | RIGHT | OTHER, 52.1° | LEFT (incorrect) | `SINGLE_SUPPORTED_SIDE` | +0.0058 over selected episode; wider-window retraction +0.2019 | side incorrect; view unsupported |
| 3 | 3,534,000–4,034,000 | `STOP_GATE` | LEFT | UNKNOWN, 64.5° | LEFT | `SINGLE_SUPPORTED_SIDE` | −0.1981 over selected episode | view policy unsupported |
| 4 | 4,668,000–5,068,000 | `STOP_GATE` | RIGHT | OTHER, 50.4° | LEFT (incorrect) | `SINGLE_SUPPORTED_SIDE` | +0.0152 over selected episode; wider-window retraction +0.2154 | side incorrect; view unsupported |
| 5 | 5,835,000–6,169,000 | `STOP_GATE` | LEFT | OTHER, 66.6° | LEFT | `SINGLE_SUPPORTED_SIDE` | −0.1524 over selected episode | view policy unsupported |
| 6 | 7,102,000–7,536,000 | `STOP_GATE` | RIGHT | UNKNOWN, 59.8° | LEFT (incorrect) | `SINGLE_SUPPORTED_SIDE` | +0.0027 over selected episode; wider-window retraction +0.1465 | side incorrect; view unsupported |
| 7 | 8,269,000–8,603,000 | `STOP_GATE` | LEFT | OTHER, 61.1° | LEFT | `SINGLE_SUPPORTED_SIDE` | −0.1410 over selected episode | view policy unsupported |
| 8 | 9,370,000–9,737,000 | `STOP_GATE` | RIGHT | UNKNOWN, 57.0° | LEFT (incorrect) | `SINGLE_SUPPORTED_SIDE` | +0.0271 over selected episode; wider-window retraction +0.1753 | side incorrect; view unsupported |
| 9 | 10,437,000–10,870,000 | `STOP_GATE` | LEFT | OTHER, 58.7° | LEFT | `SINGLE_SUPPORTED_SIDE` | −0.2280 over selected episode | view policy unsupported |
| 10 | 11,504,000–12,604,000 | `RECORDING_END_WITHOUT_STOP_EVIDENCE` | RIGHT | UNKNOWN, 60.2° | unresolved | `NO_SUPPORTED_PATTERN` | no selected episode; wider-window retraction +0.1556 | side ambiguous first; view unsupported |

All ten visible-left wrist windows have 100% sample coverage at the MLS cadence and minimum observed confidence 0.905–0.983. Current persistence contains one partial `four_limb_activity_motion` and one abstained `straight_punch_target` analysis per movement, with zero `MeasurementResult` rows. Retained generic motion evidence reopens and displays; terminal, stable-window, representative-sample, and target metrics are not evaluated.

## Exact directional diagnostic definition

The table's signed values are not abstract “outward” values. For every MLS frame within the inclusive interval:

1. require `LandmarkSource.OBSERVED` and `min(visibility, presence) >= 0.55` for left wrist, both shoulders, and both hips;
2. use upright, unmirrored, source-normalized image coordinates from `movement_landmark_stream_v1`;
3. define the instantaneous torso anchor as the arithmetic mean of left/right shoulder and left/right hip source positions;
4. calculate `leftWrist.x - torsoAnchor.x`, in normalized source-image width units, where positive X points to image right;
5. report last minus first observed value, without filtering;
6. “selected episode” is the exact elbow-opening episode retained by the current resolver; “wider window” is the fixed accepted logical movement boundary.

The recorded athlete faces toward decreasing image X, so negative signed motion is consistent with the reviewed left-hand strikes in this recording only. Reflection reverses the sign. This is a recording observation, not a transferable anatomical-forward definition.

## Reference comparison and sensitivity

On the same exact frames, shoulder-midpoint, hip-midpoint, four-point torso-centroid, aspect-correct torso-perpendicular, and world-X anchors agree on direction for the visible left wrist over each full movement window. Their magnitudes differ. Aspect-correct radial distance to the four-point torso centroid also separates the selected episodes on this recording:

- reviewed LEFT episodes: net radial increase 0.0760–0.1341 aspect-correct normalized units;
- incorrectly selected LEFT episodes on reviewed RIGHT movements: net radial change −0.0037–+0.0067, maximum excursion from episode start no more than 0.0164;
- signed source-X net magnitude during reviewed LEFT episodes: 0.1410–0.2280 normalized width;
- signed source-X net magnitude during false LEFT episodes: 0.0027–0.0271; maximum excursion from episode start up to 0.0510.

Sensitivity on this one recording only:

- a signed episode-net magnitude cutoff of 0.03, 0.05, or 0.075 retains all five reviewed LEFT candidates and rejects all four false LEFT candidates;
- using maximum signed excursion rather than net, 0.03 admits two false candidates, 0.05 admits one, and 0.075 rejects all four;
- a radial episode-net cutoff of 0.03 retains the five reviewed LEFT candidates and rejects the four false candidates.

These gaps justify testing a conservative false-candidate rejection. They do not establish a production threshold, anatomical forward axis, view suitability, or RIGHT-side resolution.

## Identity and far-hand limitation

The MLS preserves MediaPipe anatomical landmark labels, track identity, timestamps, and source/confidence metadata. It does not carry an independent per-frame hand-identity continuity proof through occlusion or crossing.

The right wrist has only 4–7 low-confidence observed frames in movements 1–9 (minimum confidence about 0.55–0.61), and its observations do not capture a supported same-hand extension episode on reviewed RIGHT movements. Movement 10 has 24/34 observed right-wrist samples but still lacks an approved same-hand strike interpretation. A visible-left retraction plus a far/right hand held forward could be a static guard or previous extension; it must not manufacture RIGHT-side evidence.

## Bounded next diagnostic

Before a production resolver revision:

1. evaluate body-relative wrist motion only over the current selected elbow episode and retain full provenance (anchor, transform, units, exact samples, confidence, gaps, and filter);
2. test the conservative result “reject unsupported LEFT candidate, otherwise abstain” against the exact ten windows;
3. add counterexamples for rigid torso translation, body rotation, extension-return, horizontal reflection/camera-opposite side, static far hand while the visible hand retracts, and anatomical-label ambiguity at crossing/occlusion;
4. collect additional recordings where the same anatomical striking hand has supported temporal evidence, including mirrored/camera-opposite geometry;
5. keep side-evidence suitability separate from impact/target view suitability.

Production may resolve RIGHT only from supported temporal evidence for the right anatomical hand. Expected alternation remains validation-only. Movement 10 remains abstained unless evidence independently resolves it.

## Implemented output scope

The currently implemented end-to-end punch output contract is target category, target-angle errors, target-classification margin, and elbow angle, with correct side or explicit abstention, persistence, reopen, and presentation. “Max speed,” “Max path deviation,” and “Hikite max speed” are explicit unavailable UI placeholders and require separately scoped analyzer features; they are not existing measurements merely blocked by terminal selection.
