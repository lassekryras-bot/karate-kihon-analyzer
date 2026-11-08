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

## Executable isolated diagnostic

The opt-in test-only harness is `ExactPunchPipelineDiagnosticTest`. It is not reachable from production code and skips the private exact replay when its environment inputs are absent. It uses the existing canonical geometry conversion, exact Heavy MLS parser, four-limb characterizer, impact analyzer, target adapter, current calibration/body scale, and accepted movement bounds.

Reproduction command from `android/KarateClipRecorder` (private paths intentionally represented by placeholders):

```powershell
$env:JAVA_HOME = 'C:\Program Files\Android\Android Studio\jbr'
$env:KARATE_PUNCH_DIAGNOSTIC_JSON = '<private-exact-replay-input.json>'
$env:KARATE_PUNCH_DIAGNOSTIC_MLS = '<private-production-heavy.mls>'
$env:KARATE_PUNCH_DIAGNOSTIC_OUTPUT = '<private-executable-result.json>'
.\gradlew.bat :app:testDebugUnitTest --tests 'dk.lasse.karatecliprecorder.training.ExactPunchPipelineDiagnosticTest'
```

Experimental configuration `episode_concurrent_distal_v1_experimental` requires observed wrist plus both shoulders and hips at confidence 0.55, at least three samples, 60% episode coverage, and no gap over 100,000 µs. It uses the existing frozen recording scale `e5d2fd39-039e-4f73-89c0-1d534ff357f1` (`manual-upright-image-height-v1`); it creates no calibration. Signed and radial displacement are normalized by that frozen aspect-correct image height. Disclosed provisional body-height-ratio sweeps are 0.05, 0.10, and 0.15. They are sensitivity probes, not validated thresholds.

The private result artifact produced by the recorded run is 10,588 bytes with SHA-256 `b4c76d8ed53e85fce43eaa017478a52f9c0e656013484ca0e43a3096c760e3a6`. The hash makes the reported aggregate replay recheckable without committing the personal input or result artifact.

On the exact replay, all three provisional sweep values retain reviewed LEFT movements 1/3/5/7/9, reject the current false LEFT candidates on 2/4/6/8, and leave 10 unresolved because it has no selected episode. Identity status remains `ANATOMICAL_LANDMARK_LABEL_WITHOUT_INDEPENDENT_CONTINUITY_PROOF`; the harness does not infer RIGHT.

The always-on synthetic portion checks:

- subject scale with a compatible frozen body-height scale;
- same-aspect frame-size changes and a different frame aspect;
- rigid translation;
- horizontal reflection, explicitly as a mathematical transform rather than evidence for an independent opposite-camera capture;
- extension followed by return;
- a stationary wrist with a moving body anchor;
- missing evidence; and
- a static far hand while the other hand retracts.

## Hypothetical view replay through current impact and target code

The harness supplies `hypothetical-exact-replay-side-view-v1` with source `diagnostic-only-not-production-approval`. This is deliberately not a production `ImpactViewApproval` mapping.

| Movement | Impact status | Terminal / stable / representative evidence (µs) | Target adapter | Measurements |
|---:|---|---|---|---:|
| 1 | `COMPLETED` | terminal 1,734,000; stable 1,834,000–2,000,000; representative 1,834,000 | `neutral_body_reference_unavailable` | 0 |
| 3 | `NO_STABLE_TERMINAL_WINDOW` | peak transition score 0.989 at 3,734,000; stable samples at 3,534,000 and 3,801,000–3,834,000, never 100,000 µs continuously | not run | 0 |
| 5 | `NO_STABLE_TERMINAL_WINDOW` | peak transition score 0.970 at 6,035,000; one stable sample | not run | 0 |
| 7 | `NO_STABLE_TERMINAL_WINDOW` | peak transition score 0.952 at 8,436,000; stable samples at 8,269,000 and 8,569,000–8,603,000, never 100,000 µs continuously | not run | 0 |
| 9 | `COMPLETED` | terminal 10,670,000; stable 10,737,000–10,870,000; representative 10,737,000 | `neutral_body_reference_unavailable` | 0 |

Each case has 100% usable impact-sample coverage. The existing 100,000 µs stable-window requirement explains why a high transition score alone is insufficient for 3/5/7.

For 1 and 9, `PunchHeightAnalyzer.processSetup` returns `NO_BODY` for every frame examined before the representative timestamp (56 frames for 1; 323 for 9). Its current `referenceCandidate` requires both shoulders, both hips, both elbows, and both wrists simultaneously observed. The exact side/oblique recording lacks the far/right wrist on most frames, so target category, target-angle errors, classification margin, and elbow-angle persistence never begin even when impact completes.

This establishes that “impact completed” is not currently sufficient for the implemented target contract. The target setup was designed around bilateral setup visibility, while the retained recording is analyzed using partial one-sided evidence.

## Minimum semantic decisions still owned by architecture/product

1. After validation on independent recordings, may an elbow-opening candidate with insufficient concurrent same-anatomical-wrist displacement be rejected conservatively, producing abstention without assigning the opposite side?
2. For retained-video target category/angle/margin/elbow outputs, is simultaneous bilateral arm visibility a required phenomenon, or may the analyzer initialize from the explicit activity-selected side plus head, shoulders, hips, same-side elbow/wrist, and the existing persisted calibration?
3. For impact only, what measurable occlusion/orientation conditions—separate from target suitability—make selected-side composite-hand motion and articulation interpretable? `OTHER`/`UNKNOWN` orientation metadata and landmark confidence are not approval.

Threshold tuning, filtering, and stable-window calibration remain evidence questions, not owner preference questions. Independent opposite-camera and mirrored captures are follow-up acceptance evidence; mathematical reflection is not a substitute.
