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

## Superseding paired-arm implementation replay — 2026-11-21

Status: `OBSERVED` exact replay of the versioned implementation candidate. This section supersedes the earlier episode-only “bounded next diagnostic” and RIGHT-side restriction below where they conflict. Impact/view approval remains unchanged and is still required independently.

`StraightPunchPairedArmAnalyzer` scores the complete accepted logical window in hip-relative WORLD coordinates. It freezes one face-signed anatomical torso normal for the movement, causally smooths each landmark's three WORLD coordinates with the shared trailing-three median then trailing-three mean, derives actual-timestamp signed forward velocities, normalizes the two arm terms per interval, and then integrates. The fixed roster is elbow/wrist/thumb/index/pinky for each arm. Missing landmarks contribute zero roster weight; missing evidence resets only that landmark's filter and is never bridged.

The exact replay passed through the production `RetrospectiveSessionSegmenter`, `TrainingSessionProcessor`, `MovementAnalysisPipeline`, `MovementMotionAnalysis`, Room persistence, database close/reopen, and retained JSON inspection. Native `activity_qom_supported_world_v2` segmentation reproduced all ten accepted boundaries and end kinds exactly; review labels were comparators only. No boundary was trimmed or extended to obtain a result. A separate detached-worktree replay of pre-change commit `30e17d6` freshly reproduced the old resolver outputs through the same native processor route.

| # | Logical bounds (µs) | Review | Old side | New side / reason | Score (m) | Left contribution / travel (m) | Right contribution / travel (m) | Left / right support (µs) |
|---:|---:|---|---|---|---:|---:|---:|---:|
| 1 | 1,500,000–2,000,000 | LEFT | LEFT | LEFT / `PAIRED_ARM_SCORE` | −0.3637 | −0.2640 / +0.4292 | −0.0996 / −0.2437 | 500,000 / 167,000 |
| 2 | 2,300,000–2,701,000 | RIGHT | LEFT | RIGHT / `PAIRED_ARM_SCORE` | +0.1984 | +0.1953 / −0.1898 | +0.0031 / +0.0093 | 401,000 / 134,000 |
| 3 | 3,534,000–4,034,000 | LEFT | LEFT | LEFT / `PAIRED_ARM_SCORE` | −0.2683 | −0.2114 / +0.2661 | −0.0569 / −0.1501 | 500,000 / 134,000 |
| 4 | 4,668,000–5,068,000 | RIGHT | LEFT | RIGHT / `PAIRED_ARM_SCORE` | +0.0997 | +0.1037 / −0.1037 | −0.0040 / −0.0119 | 400,000 / 133,000 |
| 5 | 5,835,000–6,169,000 | LEFT | LEFT | LEFT / `PAIRED_ARM_SCORE` | −0.2053 | −0.1696 / +0.2448 | −0.0358 / −0.1037 | 334,000 / 133,000 |
| 6 | 7,102,000–7,536,000 | RIGHT | LEFT | RIGHT / `PAIRED_ARM_SCORE` | +0.1060 | +0.1035 / −0.0975 | +0.0025 / +0.0072 | 434,000 / 167,000 |
| 7 | 8,269,000–8,603,000 | LEFT | LEFT | LEFT / `PAIRED_ARM_SCORE` | −0.1276 | −0.0853 / +0.1274 | −0.0423 / −0.1309 | 334,000 / 134,000 |
| 8 | 9,370,000–9,737,000 | RIGHT | LEFT | RIGHT / `PAIRED_ARM_SCORE` | +0.1060 | +0.1045 / −0.1005 | +0.0015 / +0.0046 | 367,000 / 100,000 |
| 9 | 10,437,000–10,870,000 | LEFT | LEFT | LEFT / `PAIRED_ARM_SCORE` | −0.2964 | −0.1631 / +0.2783 | −0.1333 / −0.3317 | 433,000 / 200,000 |
| 10 | 11,504,000–12,604,000 | RIGHT | unresolved | RIGHT / `PAIRED_ARM_SCORE` | +0.1897 | +0.1681 / −0.2028 | +0.0217 / +0.0519 | 1,100,000 / 800,000 |

All ten have reliable frozen-axis coverage 1.0, valid paired-interval coverage 1.0, and simultaneous bilateral support under the versioned criteria. Simultaneous bilateral durations for movements 1–10 are 167,000; 134,000; 134,000; 133,000; 133,000; 167,000; 134,000; 100,000; 200,000; and 800,000 µs. Positive score resolves RIGHT; negative resolves LEFT. A withdrawal can therefore contribute to the opposite arm's punch without expected alternation assigning the answer. The normalized left and right contributions sum to the retained score; signed per-arm travel remains a distinct diagnostic.

Full-window return/cancellation inspection found no exact movement whose return-to-guard cancelled the exchange into the uncertainty band. Movement 4 is the closest case at +0.0997 m: its left-withdrawal contribution is +0.1037 m and its right-arm opposing contribution is only −0.0040 m. The long recording-end window 10 remains +0.1897 m over 1.100 s rather than cancelling. This is recording-specific evidence; the synthetic small triangular extension-return case still correctly abstains below 0.03 m.

The initial decision gates are deliberately provisional and versioned as `straight-punch-paired-arm-v1`: observed finite WORLD confidence at least 0.55, gaps no longer than 100,000 µs, axis and paired coverage at least 0.60 of the whole logical window, face-normal separation at least 0.01 m, frozen-axis deviation no more than 45°, at least 60,000 µs arm support, and absolute score at least 0.03 m. Causal filters use short startup buffers and are not padded beyond the logical end, so end-of-window lag is part of this version's disclosed behavior.

On this recording, absolute score cutoffs 0.02, 0.03, 0.05, and 0.075 m leave all ten results unchanged. At 0.10 m, movement 4 becomes uncertain; at 0.12 m, movements 4, 6, and 8 become uncertain. This demonstrates sensitivity for smaller/slower movement and does not establish physical accuracy from one source. Synthetic acceptance tests additionally cover small-score cancellation, one/no-arm support, fixed-roster confidence normalization, nonuniform timestamps, gaps, brief arm-label swaps, repeated-side punches, oblique whole-body rotation, coordinate reflection, missing face evidence, and forward-axis reversal.

The private post-change normal-path result artifact is 7,239 bytes with SHA-256 `300fa6c100dee9d04d77f6b552f8ae94d430592998c40c21cb5e1a72165cfa05`. The fresh pre-change baseline artifact from detached commit `30e17d6` has SHA-256 `c69bbd48debb68c9db0297ae7344596bc340af687a4105396d4618df6b5f2e47`. Personal MLS/MP4 and result files remain outside the repository.

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

## Corrected retained-reference duration probe — 2026-11-21

This bounded diagnostic tested the Architect-approved direction of a retrospective neutral body reference that does not depend on the unused arm. It did not activate a production view profile or change phone data. Exact inputs were the retained recording SHA-256 `f87c0c3e93ef1af5d171353028ba47f34d20e9da665aae2062c66b57b13f1e35`, heavy landmark stream SHA-256 `39091b93bba46594ca7ff2a752e86ed2eb762e99d7112ae0cdb4bd54fd2fff54`, and private replay input SHA-256 `6e88b3ea34df74160f457312a73b319ff3539f163bb64bae5083d3fce0074b6d`.

The first probe had a sliding-window enumeration defect: after removing the oldest samples until the retained span was no greater than the requested duration, irregular timestamps could leave only exact-duration windows eligible. The corrected diagnostic retains the sample bracketing the inclusive duration cutoff, adds a nonuniform-timestamp regression covering 12 samples over 1.618 seconds, and reports every qualifying span. Its jitter calculation matches `PunchHeightAnalyzer.referenceJitter`: source-normalized XY shoulder/hip midpoints, window means, and maximum two-dimensional shoulder-or-hip displacement divided by each sample's torso length. The maximum gap remains 500 ms, minimum support remains 12 samples, and the maximum jitter ratio remains 0.025. Target calculation still applies the canonical aspect ratio separately.

| Impact-complete movement | Required duration | Evaluated windows | Minimum jitter | Windows at or below 0.025 |
|---:|---:|---:|---:|---:|
| 1 | 1.50 s | 1 | 0.24306607 | 0 |
| 1 | 1.20 s | 10 | 0.21467194 | 0 |
| 1 | 1.00 s | 16 | 0.16080622 | 0 |
| 1 | 0.75 s | 23 | 0.08690936 | 0 |
| 1 | 0.50 s | 31 | 0.03578943 | 0 |
| 9 | 1.50 s | 269 | 0.090047225 | 0 |
| 9 | 1.20 s | 278 | 0.090643995 | 0 |
| 9 | 1.00 s | 284 | 0.089783415 | 0 |
| 9 | 0.75 s | 291 | 0.04173873 | 0 |
| 9 | 0.50 s | 299 | 0.019047149 | 4 |

The four numerical 0.50-second candidates for movement 9 were 3,768,000–4,268,000 µs (16 samples, jitter 0.02142133), 3,801,000–4,301,000 µs (16, 0.019047149), 3,834,000–4,335,000 µs (16, 0.022878816), and 8,469,000–8,970,000 µs (16, 0.019647462). Retained-video inspection of each span's start, midpoint, and end classified all four as extended-punch/terminal or return-transition evidence overlapping movements 3 or 7, not neutral guard evidence. Numeric stability therefore did not establish a neutral reference.

The phone's only other available recording has no retained source landmark track and no accepted activity/view metadata, so it was not an independent replay source. No new analysis was created. The focused diagnostic class passed and produced private result SHA-256 `2734dc352202c8f0fd7228b7badcd2364f187e68b6d5b0d1827ebf74137dc7c9`.

**Conclusion:** this source has no confirmed qualifying neutral reference under the tested policies. The existing 1.50-second/0.025 semantics remain unchanged, shorter durations are not approved, and no production `ImpactViewApproval` follows. This does not prove that no future retrospective method can work. The next bounded design step is reuse and persistence of real live neutral-setup evidence with source, geometry, interval, confidence and stability provenance, with view eligibility decided independently.

## Minimum semantic decisions still owned by architecture/product

1. After validation on independent recordings, may an elbow-opening candidate with insufficient concurrent same-anatomical-wrist displacement be rejected conservatively, producing abstention without assigning the opposite side?
2. For retained-video target category/angle/margin/elbow outputs, is simultaneous bilateral arm visibility a required phenomenon, or may the analyzer initialize from the explicit activity-selected side plus head, shoulders, hips, same-side elbow/wrist, and the existing persisted calibration?
3. For impact only, what measurable occlusion/orientation conditions—separate from target suitability—make selected-side composite-hand motion and articulation interpretable? `OTHER`/`UNKNOWN` orientation metadata and landmark confidence are not approval.

Threshold tuning, filtering, and stable-window calibration remain evidence questions, not owner preference questions. Independent opposite-camera and mirrored captures are follow-up acceptance evidence; mathematical reflection is not a substitute.

## Authorized manual side-view phone run — 2026-11-21

OBSERVED by Senior Developer on Samsung SM-A556B, reviewed by PA: confirmation event `7efe1e3e-565a-4d10-9857-ab4de95921ac`, source `operator_post_capture_view_confirmation`, profile `diagnostic-manual-side-view-v1`, consumed by fresh completed/current run `b2e23d77-4a1a-4e00-9799-fae0e5cfc242` for session `6d90a774-ebcb-441c-afdc-e9f36235a64b` and assigned `alternating-straight-punch-v1`. This is operator diagnostic evidence, not live-landmark verification or production camera-view validation.

| Movements | Impact outcome | Representative timestamp | Target outcome |
| --- | --- | --- | --- |
| M1 | COMPLETED | 1,834,000 us | `neutral_body_reference_unavailable` |
| M9 | COMPLETED | 10,737,000 us | `neutral_body_reference_unavailable` |
| M3, M5, M7 | ABSTAINED: `NO_STABLE_TERMINAL_WINDOW` | None | ABSTAINED |
| M2, M4, M6, M8, M10 | ABSTAINED: `LIMB_EVIDENCE_UNAVAILABLE` | None | ABSTAINED |

All ten persisted `impactEvidence.result`: ten evaluated, two completed, eight abstained. All ten targets abstained; zero target measurements. No reason strings or downstream checks were bypassed.

Old example `a3ea4ebe-c2d9-474c-a910-b60754885f82` remains on historical run `5a4dfd1f-154c-4bf9-85d5-f8c7d178f5aa`. Corresponding new M1 `8370683b-053c-47e0-898a-496ddc967178` has the same 1,500,000–2,000,000 us interval. Terminal arrival: 1,734,000 us; stable interval: 1,834,000–2,000,000 us. New M9 `2481ecc4-6911-436f-9874-58aeda450cd3`: terminal arrival 10,670,000 us; stable interval 10,737,000–10,870,000 us.

Run count increased 24 to 25 with history retained. MP4 SHA-256 unchanged: `f87c0c3e93ef1af5d171353028ba47f34d20e9da665aae2062c66b57b13f1e35`. MLS SHA-256 unchanged and matches persisted hash: `39091b93bba46594ca7ff2a752e86ed2eb762e99d7112ae0cdb4bd54fd2fff54`; track remains `4826f011-499f-4a12-b36a-d9b4b208f6a5`. Debug package `dk.lasse.karatecliprecorder`, version 0.1.0/code 1, updated in place without uninstall/data clear. Cold reopen with Developer Mode off retained diagnostic labeling and hid debug actions. Full suite: 495 tests, zero failures/errors, three skips. Remaining target-reference work is separate from this completed diagnostic.

## Remaining-blocker diagnostics and corrected interpretation — 2026-11-21

Reported by Senior Developer and reviewed by PA; production behavior remains unchanged and no additional phone run was justified.

The five RIGHT movements fail the early impact limb-evidence gate: selected RIGHT_ARM metrics are absent, with JOINT_GEOMETRY_UNAVAILABLE/GAPS. No frame in these movement windows meets the 0.55 right-elbow confidence requirement. Paired-arm side inference does not supply the missing selected-arm articulation evidence.

The first hand-only probe (report SHA-256 `5bb493ca45011a1d7b9466ebb494dd5a0be5c8ca01ad687577a62c892dff2733`) incorrectly reused the production 0.02-body-height per-step deadband to characterize approach. Its broad no-approach conclusion is SUPERSEDED, not evidence that hand-only analysis is impossible or that recapture is required.

Corrected diagnostic report SHA-256: `35595e4a3096b6e0195cfc5d4271cf8c4306933575faef89ce00515d5afef8d8`. It uses timestamp-normalized finite differences of filtered positions, excludes five-sample filter warmup, and uses 0.02 only as a provisional fixed endpoint tolerance. M2/M4/M6/M8 have insufficient post-warmup support (4–6 valid frames total, beginning near extension). M10 has gradual outward motion: post-warmup displacement 0.019786 body heights, peak filtered radial velocity 0.195 body heights/second at 12.004 seconds, and fixed-endpoint stable suffix 12.271–12.604 seconds. Its diagnostic status is OUTWARD_APPROACH_WITHIN_FIXED_TOLERANCE_AT_UNCONFIRMED_RECORDING_END. It is not absent approach. Limits include filtered finite differences, synthetic coverage at 25/50 fps only, inspection of the trailing support block rather than earlier blocks, and unproven identity continuity. PA accepted this bounded diagnostic evidence, not a production impact profile.

Separate frame-rate report SHA-256: `8095985c93e57cde02577eae81202471b79f3427213f31f623a695e928004dcb`. The same synthetic physical trajectory completes at 30 fps and abstains at 60 fps. Observed-frame subsampling of exact M3/M5/M7 changes transition timing/scores but all remain NO_STABLE_TERMINAL_WINDOW. This establishes sampling sensitivity in the tested synthetic trajectory; it does not establish the cause of the three real-recording failures or validate a correction. Existing continuous quiet evidence remains 33 ms / none / 34 ms against the 100 ms requirement. Further production correction requires review and regression evidence; do not relax thresholds merely to clear these examples.

M1/M9 still lack a confirmed qualifying neutral reference under the previously tested policies. Diagnostic progress does not remove that independent requirement.
