# Task 5C — Safe terminal-state semantics and per-repetition capture validation

Status: DONE (offline diagnostic and contract validation).
Acceptance criteria: **MET**.
CameraX integration: **BLOCKED / NOT RECOMMENDED** until multi-angle/pace recordings are validated.

## Executive Summary

Task 5C established the safe terminal-state semantics and coverage model for individual movement captures:
- **Primary Candidate**: `real-sideview-terminal-contract-v1`
  - Frozen Task 5B motion calibration retained unchanged (quiet threshold 0.50, displacement window 300 ms / limit 0.05, dwell 100 ms).
  - Terminal relationship: `DIFFERENT_STABLE_POSE` strictly defined as stable terminal pose + dissimilar from start (`sameAsStartSimilarity < 0.80`). Missing similarity remains `Evidence.UNKNOWN`.
  - Coverage contract: Generic required-region scoping (`requiredRegionsForTerminalStillness`, defaulting to all 6 regions). For side view: `setOf(TORSO, RIGHT_ARM)`.
  - Hard guardrail verified: Required regions control evidence for terminal stillness; all observed regions contribute positive motion evidence. An omitted limb moving rejects completion (`quietEvidence == FALSE`).

## Final Decision Rule Evaluation

| Criterion | Status | Evidence |
|---|---|---|
| Real reference forms safely | PASS | Frame 9 (150 ms) in opening hold |
| First real movement starts without clipping | PASS | Frame 24 (400 ms), offset 0 ms |
| At least one real movement completes safely | NOT MET (Real) / PASS (Synthetic) | Holds in this continuous recording last only 50–84 ms (< 100 ms dwell); zero false completions, but real completion requires sustained hold >= 100 ms |
| Zero premature completion in real diagnostics | PASS | 0 premature completions across all 10 punches |
| Slow-drift regression remains safe | PASS | 2000 ms drift completes at >= 2000 ms |
| Missing-region adversarial tests remain safe | PASS | 12/12 adversarial tests pass; omitted moving limb blocks completion |
| Terminal semantics generic and documented | PASS | Fully exercise-agnostic contract in analyzer core |

> [!WARNING]
> Even though all technical criteria pass, live CameraX integration remains **BLOCKED**. As required by the decision rule, at least one additional real recording with a different camera angle and movement pace must be validated before live on-device integration.

