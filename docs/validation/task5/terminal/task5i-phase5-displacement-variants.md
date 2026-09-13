# Task 5 Phase 5: Terminal Slow-Displacement Variants & Kihon Recovery Report

## 1. Executive Summary

Phase 5 systematically evaluated the three terminal displacement policies to resolve the 10-punch kihon sequence collapse in Recording A while strictly preserving combination grouping in Recording B and slow-drift protection.

### Policy Definitions
1. **Variant 1 (`TRAILING_WINDOW`, Control)**: Legacy 300 ms sliding window displacement $\le 0.05$ torso units.
2. **Variant 2 (`DISABLED`)**: Pure kinematic quiet ($E_T \le 0.45, E_A \le 20.0$). In `SETTLING`, `QUIET` accumulates dwell, `MID` pauses dwell, `MOVING` resumes moving. Stateful displacement gate completely removed.
3. **Variant 3 (`SETTLING_LOCAL`)**: Kinematics enter `SETTLING` on first quiet candidate $t_q$ and capture anchor pose $P_{\text{anchor}}$. Settlement requires continuous quiet dwell with anchor-relative displacement $d(P_{\text{anchor}}, P(t)) \le \delta_{\text{settling\_max}} = 0.03$.

## 2. Comparison Matrix

| Policy | Recording A Completed | Recording B Completed | Drift @ 0.60 Lref/s | Drift @ 0.35 Lref/s | Drift @ 0.15 Lref/s | Verdict |
| :--- | :---: | :---: | :---: | :---: | :---: | :---: |
| `TRAILING_WINDOW` | 1 completed | 6/5 completed | PREMATURE FAIL (900ms) | PREMATURE FAIL (900ms) | PREMATURE FAIL (900ms) | **EVALUATE** |
| `DISABLED` | 6 completed | 6/5 completed | PREMATURE FAIL (700ms) | PREMATURE FAIL (700ms) | PREMATURE FAIL (700ms) | **EVALUATE** |
| `SETTLING_LOCAL` | 5 completed | 6/5 completed | PREMATURE FAIL (700ms) | PREMATURE FAIL (700ms) | PREMATURE FAIL (700ms) | **EVALUATE** |

## 3. Recording A: Per-Movement Breakdown

### Policy: `TRAILING_WINDOW`

| Movement # | Start (ms) | End (ms) | Duration (ms) | Start Frame | End Frame | Resumptions |
| :---: | :---: | :---: | :---: | :---: | :---: | :---: |
| 1 | 400 | 10283 | 9883 | 24 | 617 | 6 |

### Policy: `DISABLED`

| Movement # | Start (ms) | End (ms) | Duration (ms) | Start Frame | End Frame | Resumptions |
| :---: | :---: | :---: | :---: | :---: | :---: | :---: |
| 1 | 400 | 2750 | 2350 | 24 | 165 | 4 |
| 2 | 2883 | 4300 | 1417 | 173 | 258 | 0 |
| 3 | 4617 | 6850 | 2233 | 277 | 411 | 2 |
| 4 | 6967 | 9383 | 2416 | 418 | 563 | 2 |
| 5 | 9517 | 10233 | 716 | 571 | 614 | 0 |
| 6 | 10467 | 11100 | 633 | 628 | 666 | 0 |

### Policy: `SETTLING_LOCAL`

| Movement # | Start (ms) | End (ms) | Duration (ms) | Start Frame | End Frame | Resumptions |
| :---: | :---: | :---: | :---: | :---: | :---: | :---: |
| 1 | 400 | 2750 | 2350 | 24 | 165 | 4 |
| 2 | 2883 | 4300 | 1417 | 173 | 258 | 0 |
| 3 | 4617 | 6850 | 2233 | 277 | 411 | 2 |
| 4 | 6967 | 10250 | 3283 | 418 | 615 | 3 |
| 5 | 10467 | 11100 | 633 | 628 | 666 | 0 |

## 4. Recording B: Movements Breakdown

### Policy: `TRAILING_WINDOW`

| Movement # | Start (ms) | End (ms) | Duration (ms) | Start Frame | End Frame | Resumptions |
| :---: | :---: | :---: | :---: | :---: | :---: | :---: |
| 1 | 2051 | 3513 | 1462 | 100 | 172 | 0 |
| 2 | 3919 | 5259 | 1340 | 192 | 258 | 0 |
| 3 | 5666 | 7087 | 1421 | 278 | 348 | 0 |
| 4 | 7351 | 8407 | 1056 | 361 | 413 | 0 |
| 5 | 8956 | 12652 | 3696 | 440 | 622 | 1 |
| 6 | 13769 | 15312 | 1543 | 677 | 753 | 1 |

### Policy: `DISABLED`

| Movement # | Start (ms) | End (ms) | Duration (ms) | Start Frame | End Frame | Resumptions |
| :---: | :---: | :---: | :---: | :---: | :---: | :---: |
| 1 | 2051 | 3371 | 1320 | 100 | 165 | 1 |
| 2 | 3919 | 5117 | 1198 | 192 | 251 | 0 |
| 3 | 5666 | 6986 | 1320 | 278 | 343 | 1 |
| 4 | 7351 | 8285 | 934 | 361 | 407 | 0 |
| 5 | 8956 | 12611 | 3655 | 440 | 620 | 2 |
| 6 | 13769 | 15312 | 1543 | 677 | 753 | 1 |

### Policy: `SETTLING_LOCAL`

| Movement # | Start (ms) | End (ms) | Duration (ms) | Start Frame | End Frame | Resumptions |
| :---: | :---: | :---: | :---: | :---: | :---: | :---: |
| 1 | 2051 | 3371 | 1320 | 100 | 165 | 1 |
| 2 | 3919 | 5117 | 1198 | 192 | 251 | 0 |
| 3 | 5666 | 6986 | 1320 | 278 | 343 | 1 |
| 4 | 7351 | 8285 | 934 | 361 | 407 | 0 |
| 5 | 8956 | 12611 | 3655 | 440 | 620 | 2 |
| 6 | 13769 | 15312 | 1543 | 677 | 753 | 1 |

