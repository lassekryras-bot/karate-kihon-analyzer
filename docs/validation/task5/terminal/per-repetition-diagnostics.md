# Phase 4: Per-Repetition Bounded Diagnostic Replays

Tag: `OFFLINE_PER_REPETITION_DIAGNOSTIC`
Common configuration. Fresh extractor/segmenter per punch. No labels or impact frames injected into runtime.
Candidate contract: `real-sideview-terminal-contract-v1` (required regions: TORSO, RIGHT_ARM; DIFFERENT_STABLE_POSE).
Control contract: `conservative-v1` (whole-body coverage; DIFFERENT_STABLE_POSE).

| Punch | Side | Mode | Window (frames) | Ref ms | Arm ms | Start / Offset (ms) | Term Boundary / Decision / Offset (ms) | Clipped | Premature | Outcome |
|---|---|---|---|---|---|---|---|---|---|---|
| 1 | RIGHT | control | 0–76 | 150 | 183 | 400 / 0 | — / — / — | False | None | ARMED_NOT_COMPLETED |
| 1 | RIGHT | candidate | 0–76 | 150 | 183 | 400 / 0 | — / — / — | False | None | ARMED_NOT_COMPLETED |
| 2 | LEFT | control | 65–124 | — | — | — / — | — / — / — | None | None | BASELINE_NOT_ARMED |
| 2 | LEFT | candidate | 65–124 | — | — | — / — | — / — / — | None | None | BASELINE_NOT_ARMED |
| 3 | RIGHT | control | 112–175 | — | — | — / — | — / — / — | None | None | BASELINE_NOT_ARMED |
| 3 | RIGHT | candidate | 112–175 | 2783 | — | — / — | — / — / — | None | None | BASELINE_NOT_ARMED |
| 4 | LEFT | control | 162–225 | 3617 | — | — / — | — / — / — | None | None | BASELINE_NOT_ARMED |
| 4 | LEFT | candidate | 162–225 | 2783 | — | — / — | — / — / — | None | None | BASELINE_NOT_ARMED |
| 5 | RIGHT | control | 212–276 | 3617 | 3650 | 3733 / -17 | — / — / — | False | None | ARMED_NOT_COMPLETED |
| 5 | RIGHT | candidate | 212–276 | 3617 | 3650 | 3733 / -17 | — / — / — | False | None | ARMED_NOT_COMPLETED |
| 6 | LEFT | control | 262–325 | 5283 | — | — / — | — / — / — | None | None | BASELINE_NOT_ARMED |
| 6 | LEFT | candidate | 262–325 | 4400 | — | — / — | — / — / — | None | None | BASELINE_NOT_ARMED |
| 7 | RIGHT | control | 310–376 | 5200 | 5283 | 5383 / -34 | — / — / — | False | None | ARMED_NOT_COMPLETED |
| 7 | RIGHT | candidate | 310–376 | 5200 | 5283 | 5383 / -34 | — / — / — | False | None | ARMED_NOT_COMPLETED |
| 8 | LEFT | control | 360–425 | — | 7017 | — / — | — / — / — | None | None | ARMED_NOT_COMPLETED |
| 8 | LEFT | candidate | 360–425 | — | 6167 | 6250 / -33 | — / — / — | False | None | ARMED_NOT_COMPLETED |
| 9 | RIGHT | control | 410–476 | — | 7017 | 7033 / -84 | — / — / — | False | None | ARMED_NOT_COMPLETED |
| 9 | RIGHT | candidate | 410–476 | 7767 | 7017 | 7033 / -84 | — / — / — | False | None | ARMED_NOT_COMPLETED |
| 10 | LEFT | control | 460–525 | 8733 | — | — / — | — / — / — | None | None | BASELINE_NOT_ARMED |
| 10 | LEFT | candidate | 460–525 | 7767 | 7783 | 7950 / 0 | — / — / — | False | None | ARMED_NOT_COMPLETED |
