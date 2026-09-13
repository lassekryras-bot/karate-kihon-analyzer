# Task 5D: Blind Exercise-Agnostic Continuous Segmentation Report

## 1. Executive Summary

This report delivers the **frozen blind validation results** for `20260911_223447.mp4` evaluated under the frozen Task 5B/5C motion calibration and required-region terminal contract.

- **Sequence ID**: `blind-20260911_223447`
- **Source Video**: `input/task5/20260911_223447.mp4` (1080x1920, 49.18 fps, 763 frames, 15495 ms)
- **Detected Movements Completed**: **0**
- **Movements Started**: **1** (Movement 1 detected at 2030 ms / Frame 99)
- **Final Session State**: `MOVING` at EOF (Frame 762 / 15495 ms)
- **Completed Clips Rendered**: **0** (No movement satisfied the completion contract; as mandated by the safety rules, no artificial boundary was manufactured)
- **Diagnostic Clip Rendered**: `uncompleted-movement-001.mp4` (Interval: 2030 ms to 15495 ms)

---

## 2. Frozen Configuration & Safety Contract

All parameters were preserved strictly unchanged from Task 5B and Task 5C without tuning against this video:

| Parameter | Frozen Value | Role & Safety Constraint |
|:---|:---|:---|
| `extractor.baselineMaximumArticulatedMotion` | `0.30` | Stricter reference formation limit |
| `segmenter.quietMotionThreshold` | `0.50` | Settling quiet stillness limit |
| `segmenter.startMotionThreshold` | `0.50` | Movement start threshold |
| `extractor.slowDisplacementWindowMs` | `300 ms` | Accumulated displacement integration window |
| `segmenter.maximumStableDisplacement` | `0.05` | Slow displacement decay limit |
| `segmenter.baselineDwellMs` | `100 ms` | Minimum stillness required to arm |
| `segmenter.movementStartDwellMs` | `100 ms` | Minimum motion required to trigger MOVING |
| `segmenter.settlingDwellMs` | `100 ms` | **Unchanged**: Minimum terminal stillness required |
| `segmenter.minimumCoverage` | `0.70` | Threshold below which required evidence is `UNKNOWN` |
| `requiredRegions` | `TORSO, LEFT_ARM, RIGHT_ARM` | Approved side-neutral upper-body required set |
| `endPoseRelationship` | `DIFFERENT_STABLE_POSE` | Must be dissimilar from initial starting reference (< 0.80) |
| `positiveMotionGuardrail` | `ENABLED` | Any moving observed limb vetoes completion |

---

## 3. Detected Movement Inventory

| Mvt # | Start Boundary | Start Decision | Terminal Boundary | Decision Time | Pre-Roll | Settling Resumptions | Coverage UNKNOWN | Final State / Reason |
|:---:|:---:|:---:|:---:|:---:|:---:|:---:|:---:|:---|
| **1** | 2030 ms (F99) | 2132 ms (F104) | *None* | *None* | 1015 ms | 13 | 457 frames | `MOVING` (INCOMPLETE_AT_EOF) |

### Transition Log (Movement 1)

```
0000 ms (F000): BASELINE initiated
1015 ms (F049): BASELINE -> ARMED (trigger: armed, baseline dwell satisfied)
2030 ms (F099): [Estimated Start Boundary]
2132 ms (F104): ARMED -> MOVING (trigger: sustained_motion, motion > 0.50 for 100ms)
3493 ms (F171): MOVING -> SETTLING (trigger: quiet_candidate)
3513 ms (F172): SETTLING -> MOVING (trigger: movement_resumed)
3533 ms (F173): MOVING -> SETTLING (trigger: quiet_candidate)
3574 ms (F175): SETTLING -> MOVING (trigger: movement_resumed)
3594 ms (F176): MOVING -> SETTLING (trigger: quiet_candidate)
3614 ms (F177): SETTLING -> MOVING (trigger: movement_resumed)
3635 ms (F178): MOVING -> SETTLING (trigger: quiet_candidate)
3757 ms (F184): SETTLING -> MOVING (trigger: movement_resumed)
3797 ms (F186): MOVING -> SETTLING (trigger: quiet_candidate)
3899 ms (F191): SETTLING -> MOVING (trigger: movement_resumed)
3919 ms (F192): MOVING -> SETTLING (trigger: quiet_candidate)
3939 ms (F193): SETTLING -> MOVING (trigger: movement_resumed)
5259 ms (F258): MOVING -> SETTLING (trigger: quiet_candidate)
5320 ms (F261): SETTLING -> MOVING (trigger: movement_resumed)
5341 ms (F262): MOVING -> SETTLING (trigger: quiet_candidate)
5442 ms (F267): SETTLING -> MOVING (trigger: movement_resumed)
5483 ms (F269): MOVING -> SETTLING (trigger: quiet_candidate)
5584 ms (F274): SETTLING -> MOVING (trigger: movement_resumed)
5605 ms (F275): MOVING -> SETTLING (trigger: quiet_candidate)
5666 ms (F278): SETTLING -> MOVING (trigger: movement_resumed)
7087 ms (F348): MOVING -> SETTLING (trigger: quiet_candidate)
7108 ms (F349): SETTLING -> MOVING (trigger: movement_resumed)
7128 ms (F350): MOVING -> SETTLING (trigger: quiet_candidate)
7290 ms (F358): SETTLING -> MOVING (trigger: movement_resumed)
7311 ms (F359): MOVING -> SETTLING (trigger: quiet_candidate)
7351 ms (F361): SETTLING -> MOVING (trigger: movement_resumed)
15495 ms (F762): EOF reached while in MOVING
```

---

## 4. Root Cause Analysis: Why Did Zero Movements Complete?

The analyzer operated strictly under the frozen safety contracts. The diagnostic trace reveals that the session remained in `MOVING` due to **two distinct, safe failure mechanisms**:

### Failure Mode 1: Tracking Coverage Drop on Far/Occluded Arm (`Evidence.UNKNOWN`)
- **When**: Strike 1 hold (2376 ms to 2863 ms) and later strike holds (8468 ms to 13809 ms).
- **Observation**:
  - During the terminal hold of Strike 1, the participant came to a complete stop: articulated motion decayed to $0.18 - 0.37$ (well below the $0.50$ quiet threshold) and accumulated displacement decayed to $0.034$ (below the $0.05$ limit).
  - The pose was clearly distinct from the starting stance (`sameAsStartSimilarity` $pprox 0.60 < 0.80$).
  - Torso coverage was $0.999$ and Right Arm coverage was $0.998$.
  - **However, Left Arm coverage dropped to $0.54 - 0.63$** (below the minimum required coverage of $0.70$).
  - Later in the video (from 8468 ms onward), Left Arm coverage dropped to $0.333$ (1 of 3 landmarks detected).
- **Contract Effect**:
  - Because `LEFT_ARM` was designated as a required region in the side-neutral contract (`TORSO, LEFT_ARM, RIGHT_ARM`), `observation.coverage` fell below $0.70$.
  - Under the safety rule *"Missing required evidence remains `UNKNOWN`"*, `quietEvidence` evaluated strictly to `Evidence.UNKNOWN`.
  - Terminal dwell time could not advance while evidence was `UNKNOWN`. Thus, the strike could not complete.

### Failure Mode 2: Return-to-Stance Rejected by `DIFFERENT_STABLE_POSE` (`Evidence.FALSE`)
- **When**: Return holds at 3635–3736 ms (101 ms dwell) and 7128–7270 ms (142 ms dwell).
- **Observation**:
  - Following strikes, the participant returned to the initial ready posture and held motionless for $>100$ ms.
  - Articulated motion was quiet ($< 0.50$).
  - Accumulated displacement was minimal ($< 0.035$).
  - Tracking coverage was excellent ($> 0.95$ across all limbs, including the left arm).
  - **However, `sameAsStartSimilarity` was $pprox 0.93$ ($\ge 0.80$)**.
- **Contract Effect**:
  - The segmenter was configured with `endPoseRelationship = DIFFERENT_STABLE_POSE` (which requires proving the terminal hold is a *different* technique pose, i.e., `sameAsStartSimilarity < 0.80`).
  - Because the participant returned to the start posture, similarity was high ($0.93$), causing the terminal check to evaluate to `Evidence.FALSE`.
  - The segmenter correctly refused to terminate upon returning to the ready stance.
  - Soon after, the participant resumed motion ($3757$ ms and $7290$ ms), kicking the state back to `MOVING`.

---

## 5. Adherence to Task 5D Safety Rules

1. **No threshold tuning**: All thresholds (`0.50`, `0.30`, `0.05`, `100 ms`, `0.70`) remained frozen.
2. **No manufactured boundaries**: Because no movement satisfied the completion contract, zero completed clips were output. No artificial split or end was forced.
3. **No technique assumptions**: The controller did not assume punch types, sides, or intended repetitions.
4. **Preservation of uncertainty**: The 457 frames with incomplete left-arm tracking were strictly treated as `UNKNOWN`.

---

## 6. Deliverables Index

- **Timeline Plot**: [`timeline.png`](timeline.png)
- **Contact Sheet**: [`contact-sheet.png`](contact-sheet.png)
- **JSON Result**: [`blind-segmentation.json`](blind-segmentation.json)
- **Diagnostic Video**: [`uncompleted-movement-001.mp4`](uncompleted-movement-001.mp4) (Full detected moving span: 2030 ms to 15495 ms)

---

## 7. Status: Frozen for Ground Truth Reveal

The blind results are now completely frozen and saved. We await the user's ground-truth reveal (actual movement count, intended sequence, boundaries, and speed variations) before proceeding to accuracy scoring.
