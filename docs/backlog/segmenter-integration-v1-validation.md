# Segmenter Integration v1 — Validation

## Evidence checkpoint — 2026-11-07

See the [owning task](segmenter-integration-v1-requirements.md#delivery-checkpoint--2026-11-07) for current implementation and scoped acceptance. One ten-punch recording has reported user boundary acceptance; [QoM v2 replay evidence](../motion-detector-math.md#20-supported-world-v2-evidence-policy-and-production-selection) records additional behavior and corpus limits. The checklist below is not wholly unexecuted, nor wholly satisfied. No fresh device validation ran during backlog reconciliation.

## Purpose

Validate that segmentation works on real recordings and that the persisted
movement intervals represent the movements we actually want future analyzers to
consume.

Automated tests alone are not sufficient.

## Automated validation

Cover at least:

- existing landmark track → segmentation succeeds;
- processing continues automatically from landmark-ready state;
- movement records are persisted;
- persisted segments survive repository/database reopen;
- logical start and end are valid;
- retained start and end contain the logical interval;
- retained intervals may overlap without changing logical ownership;
- actual detected count may differ from planned repetitions;
- zero movements does not fabricate results;
- segmentation failure preserves upstream recording/landmark evidence;
- retries do not create duplicate movements;
- only appropriate cue events reach cue association;
- processing stage/state transitions are valid; and
- processing timing is recorded.

## Real recording validation

Use real continuous straight-punch recordings. Do not tune only against
synthetic fixtures.

For each recording, inspect:

- full master video;
- detected movement count;
- each logical start;
- each logical end;
- retained playback interval;
- false movements;
- missed movements;
- merged movements; and
- split movements.

The planned repetition count is reference information only. It must not be used
as ground truth by the segmenter.

## Boundary review

For each detected movement, answer the following questions.

### Start

- Is the body already moving before the detected start?
- Is unrelated preparation incorrectly included?
- Is the beginning of the punch missing?

### End

- Has the meaningful movement finished?
- Is the end cut off?
- Does the segment include excessive stillness?
- Is motion toward the next repetition being included?

## Suggested diagnostic representation

For each movement, expose something similar to:

```text
Movement 4

Logical start: 8.470 s
Logical end: 9.020 s
Duration: 0.550 s

Playback start: 8.170 s
Playback end: 9.320 s

Segmentation version: …
Associated cue: optional
Preferred/peak frame: optional
```

The exact UI is not prescribed. The information must be accessible enough to
debug the segmenter.

## Count mismatch cases

Explicitly test:

- Planned 10 / Detected 10
- Planned 10 / Detected 9
- Planned 10 / Detected 11

The system must preserve the detected result. Do not trim or add movements to
force the requested count.

## Completion

Do not mark the implementation validated merely because ten movements were
found. Validation requires reasonable movement boundaries.

The output is intended to become the canonical input for future punch analyzers.

## New offline side-view option — pending acceptance, 2026-11-22

For the [new scoped option](segmenter-integration-v1-requirements.md#offline-side-view-alternating-punch-option--2026-11-22), reproduce the archive replay before declaring parity:625 frames,21 candidates,20 accepted, all40 CSV endpoints with microsecond disagreement reporting. Inspect shared2.801-second boundary; verify reported old movement11 splits into12/13 and old17 into19/20. Plot detected output before using cue overlays. Cue and expected-count independence must remain testable.

Validate missing reference samples, insufficient observations, zero quiet range, irregular timing, long tracking gaps, interpolation bounds and per-point provenance; unresolved evidence must not become stillness. Verify shared-boundary half-open ownership independently from candidate scoring, explicit supported-profile selection, legacy replay preservation, raw-file integrity and retained run history. Record independent-recording results or their absence, especially repair-side selection and camera scope. CSV parity is regression/replay acceptance only. Exact video annotation and broader timing/karate-accuracy acceptance remain OPEN. No new result has been independently executed by BA during this reconciliation.

### Revised acceptance for unresolved-point exclusion — 2026-11-22

The explicit user decision in the owning requirements supersedes full endpoint parity for the new production-path option. Retain unchanged original replay as comparison (21 candidates/20 windows). Expected exclusion output:20 candidates/20 windows, with37 unchanged endpoints and only:

| Boundary | Original replay us | Exclusion us | Difference us |
| --- | ---: | ---: | ---: |
| Punch2 end | 2801000 | 2434000 | -367000 |
| Punch3 start | 2801000 | 2901000 | +100000 |
| Punch19 end | 19407000 | 19374000 | -33000 |

Test that233 ms anchor spans exceed the200 ms repair cap, produce explicit UNRESOLVED provenance/reasons, and contribute no motion. Verify unaffected usable landmarks still contribute, affected histories reset, no cross-gap velocity is generated, and missing evidence alone never confirms quiet. Prioritize independent video annotation at2.434–2.901 seconds: this467 ms gap must not be labeled observed stillness. Fixture count is reported validated by PA; exact onset/end accuracy and generalization remain OPEN. Implementation and full suites are still pending review. Preserve the original and revised results without relabeling either as human timing ground truth.

### Reviewed implementation and fixture checkpoint — 2026-11-22

PA reports direct implementation/graph review and fresh XML verification:544 core +512 app tests, zero failures/errors, seven app skips. Senior reports private fixture20/20 with the three approved boundary deltas. These support scoped implementation/replay acceptance; one normal phone run is authorized but device acceptance is pending. Independent frame-level video annotations and generalization remain OPEN.

Diagnostic reports must identify `RECOMPUTED_FROM_RETAINED_RUN` and their SHA-verified raw MLS plus version-fixed configuration. Derived-point provenance is reconstructed, not a claim that derived points were persisted. Verify this distinction during phone/report inspection, alongside retained history, unchanged default and independent downstream gates.

### Device result attribution — 2026-11-22

VERIFIED per Senior's device evidence, not BA-direct inspection: SM-A556B run `9a16c7e0-2a8e-444f-8eea-ea3ea7e4b721`, session `e38f86cd-9e95-4cfb-80ff-45c176241c01`, one explicit selection/one new run, previous two runs retained, reopened Detected20 / Planned20.20 windows match approved exclusion output with only the three agreed deltas. MLS hash unchanged `499d6fd3beb0acf6a21680bad4c0461274a526f22673b3b0afcf4c673a2d4a15`; installed/build APK hash identical `CEC3727E577C8304A5248D71700EAD772021A22D0518A8607FCC3661F1C05392`.

Movement2 end is `EVIDENCE_GAP_WITHOUT_STOP_EVIDENCE`, not a confirmed stop. Independent video annotation of the2.434–2.901-second interval and generalization remain OPEN. Do not claim direct inspection of phone-recomputed per-point diagnostics: the reported evidence was private-MLS and retained-run replay tests. Scoped device checkpoint is complete; broader timing acceptance is not.

### Acceptance withdrawn after reopened audit — 2026-11-22

Current status: CHANGES REQUIRED. PA withdrew implementation acceptance while retaining the factual20-punch fixture/phone result above. Before renewed acceptance, verify consistent UI logical time/duration, core/app half-open ownership at nonshared ends, no centered-reference smoothing across long gaps, missing QoM availability preserved through serialization and impact consumption rather than numeric-zero stillness, and signed-X diagnostic retention. Senior owns focused fixes/tests before any new phone run. Immutable/enforced version-fixed configuration remains acceptable without requiring a parameter snapshot. The earlier count checkpoint is successful but insufficient; timing/generalization remain OPEN.

### Versioned correction acceptance — 2026-11-22

Verify corrected detector v2 is distinct from retained historical v1, including recomputation of saved v1 diagnostics with original semantics. Do not accept equal fixture boundaries as proof that algorithms are interchangeable. Exercise existing version dispatch, append-only selection and readability of historical rows/payloads. Senior's proposed downstream ImpactAnalyzer v3 / MovementMotionAnalysis v4 version changes require review alongside missing-evidence propagation tests. CHANGES REQUIRED remains current until review/tests establish acceptance.

### Corrective v2 implementation checkpoint approved — 2026-11-22

PA directly reviewed version separation/ownership propagation/missing QoM/gap isolation and verified548 core +515 app tests with zero failures/errors and six app skips. Prior CHANGES REQUIRED code findings addressed; one new normal phone verification authorized after fresh build. Device v2 acceptance PENDING. Required device evidence: logical versus playback labels,20 windows/approved deltas, new version provenance and correct missing-evidence handling. Count alone is insufficient. Preserve v1 historical run and original comparison; timing/generalization stay OPEN.
