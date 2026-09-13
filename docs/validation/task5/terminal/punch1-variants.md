# Phase 3: Punch 1 Bounded Replay Variants

Diagnostic evaluation of Punch 1 in a bounded replay window (frames 0–76; 0–1267 ms).
Proposed movement: 24–56 (400–933 ms). Proposed terminal hold: 56–76 (933–1267 ms).

| Variant | Relationship | Required Regions | Settling Begins | Quiet Dwell Available | Blocked by Cov | Blocked by Sim | Completed | Final State |
|---|---|---|---|---|---|---|---|---|
| p1-diff-wholebody | DIFFERENT_STABLE_POSE | ALL (whole-body) | False (—) | False | True | False | False | MOVING |
| p1-same-wholebody | SAME_AS_START | ALL (whole-body) | False (—) | False | True | True | False | MOVING |
| p1-mirror-wholebody | MIRRORED_START | ALL (whole-body) | False (—) | False | True | True | False | MOVING |
| p1-diff-candidate | DIFFERENT_STABLE_POSE | TORSO,RIGHT_ARM | True (1167) | False | False | False | False | MOVING |
| p1-same-candidate | SAME_AS_START | TORSO,RIGHT_ARM | True (1167) | False | False | True | False | MOVING |
| p1-mirror-candidate | MIRRORED_START | TORSO,RIGHT_ARM | True (1167) | False | False | True | False | MOVING |
