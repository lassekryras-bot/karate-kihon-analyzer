# Decisions

This file records durable decisions that do not already have a clearer authoritative owner. Keep entries concise and append new decisions; do not use it as a task log.

The seed records below preserve the reason and consequence of the initial choices. Current policy lives in the linked owning files. Source and date interpretation: [seed provenance](README.md#seed-provenance).

## 2026-09-23 — Product development order

**Status:** DECIDED

Develop the shared Karate Analysis Platform backend and the free/lightweight Karate Kihon Analyzer together first. Build Karate Coach later on the proven platform.

**Consequence:** A hypothetical future Coach requirement alone does not justify current complexity.

**Current policy:** [Product](PRODUCT.md).

## 2026-09-23 — Analytical ownership

**Status:** DECIDED

Karate and movement-analysis knowledge belongs in the shared platform; product experience belongs in applications. Analytical concepts have one authoritative implementation.

**Consequence:** Applications consume analysis and may present it differently, but do not fork analytical calculations.

**Current policy:** [Domain](DOMAIN.md) and [Architecture](ARCHITECTURE.md).

## 2026-09-23 — Fresh knowledge baseline

**Status:** DECIDED

Begin with a fresh project-knowledge layer. Existing implementation and old documentation are evidence, not automatic current authority.

**Consequence:** Repository structure and implementation facts must be established by focused discovery. Material conflicts with current intent are surfaced rather than silently reconciled.

**Current policy:** [AGENTS.md](../AGENTS.md#knowledge-policy).

## 2026-10-15 — Supported-world QoM v2 production scope

**Status:** DECIDED

Select `activity_qom_supported_world_v2` only when stored activity evidence explicitly identifies straight-punch processing. Keep kick and activity-unknown routes on `activity_qom_hysteresis_v1` pending separate validation. Preserve the selected version on processing runs and movements; reanalysis must not reinterpret historical runs.

**Consequence:** The evidence-validity improvement can ship for its validated use case without becoming a global detector default or adding feature-flag machinery. Generic detected movement remains distinct from downstream punch validation.

**Current policy and validation:** [Activity-Aware Quantity-of-Motion Detector](../docs/motion-detector-math.md#20-supported-world-v2-evidence-policy-and-production-selection).

## 2026-11-21 — Paired-arm world-velocity straight-punch side method

**Status:** DECIDED — explicit Lasse specification, relayed by Project Architect to Business Analyst on 2026-11-21; implementation/validation pending.

Use the full detected movement window and hip-relative, fixed-axis paired-arm world-velocity evidence for alternating straight-punch side selection. The [existing side task](../docs/backlog/four-limb-motion-characterizer-requirement-v1.md#current-user-specification--2026-11-21) owns the precise roster, filtering, confidence/support integration, sign, uncertainty and verification requirements. Elbow interpretation is diagnostic; alternation is postdecision and must not force repeated-side results.

**Supersession:** this explicitly replaces conflicting earlier episode-opening-primary and conservative paired-inference restrictions for this method. Earlier observations/counterexamples and historical result versions remain valid evidence of what was tested; they are not erased. Side implementation is now requested rather than diagnostic-only. No impact/view approval, calibration relaxation or segmentation change follows from this decision.

## 2026-11-21 — Frame-local horizontal target height

**Status:** DECIDED — explicit user requirement relayed by Project Architect to BA; implementation/verification pending.

Introduce a new versioned horizontal target-height result at the exact completed-impact representative frame. Canonical-pixel signed error is targetY minus fistY; independently supported Jodan chin, Chudan 45% shoulder-to-hip midpoint interval, and Gedan hip-midpoint targets do not require an earlier neutral pose or the unused arm. Preserve old angular semantics/history. Closest is distinct from on-target, ties are ambiguous, and partial support is explicit. The [existing target-height owner](../docs/backlog/movement-detail-target-height-heavy-reprocessing-corrective-pass.md#current-horizontal-target-height-requirement--2026-11-21) holds the requirements and verification scope. Impact/view gates and diagnostic exclusions remain; frame-rate correction is separate.

## 2026-11-21 — Shoulder-origin angular target result as primary presentation

**Status:** DECIDED — explicit user refinement relayed by Project Architect; implementation/phone verification pending.

Use current representative-frame anatomical striking shoulder-to-composite-fist direction and a forward reach-circle intersection with each supported horizontal target guide to produce aspect-correct signed angular high/low error. Declared target remains authoritative without substitution; open-target selection uses closest reachable angular error with ambiguous ties. Persist frame and line/arc provenance under new version/keys. This replaces pixels as primary UI for new results, not historical horizontal v3 semantics or older neutral-based angular results. The [existing target-height owner](../docs/backlog/movement-detail-target-height-heavy-reprocessing-corrective-pass.md#current-shoulder-origin-angular-presentation--2026-11-21) holds the current scope; no separate analytical system is introduced.

## 2026-11-22 — Scoped offline side-view segmentation option

**Status:** DECIDED scope, implementation/acceptance pending — user request relayed by PA.

Add a versioned opt-in offline side-view alternating-punch segmentation option in the shared analytical core, preserving legacy profiles and immutable raw landmarks with explicit derived-repair provenance. Cue association follows detection; side/impact/target eligibility remains independent. The [existing segmentation owner](../docs/backlog/segmenter-integration-v1-requirements.md#offline-side-view-alternating-punch-option--2026-11-22) records archive scope and evidence limits. Fixture CSV parity is not independently annotated timing truth; fixture-tuned gates and left-only repair do not establish generalization.

### Offline side-view option activation clarification — 2026-11-22

**Status:** DECIDED by PA; implementation pending. Require explicit persisted recorded processing-plan selection with option/configuration/version and selection provenance for the experimental side-view alternating-punch segmenter. Preserve the default. Existing operator-selected requestedView and post-segmentation ObservationContext are not authoritative pre-segmentation side-view evidence. Selection declares segmentation intent only, not downstream camera-view approval or evidence eligibility. See the existing segmentation owner above.

### Unresolved-point exclusion for the new offline detector — 2026-11-22

**Status:** DECIDED by user, relayed by PA; implementation/full-suite review pending.

The new detector excludes points that cannot be repaired within the declared200 ms anchor-span limit, retaining raw observations and explicit unresolved reasons. Other supported landmarks remain usable; reset affected histories and forbid cross-gap velocity or missing-as-quiet interpretation. Preserve the original replay unchanged for comparison. Predictable repair-limit enforcement motivates this decision, not proven timing accuracy. The existing segmenter requirements/validation own the three expected endpoint differences (37 unchanged),20/20 revised candidate/window counts versus21/20 original, and independent annotation obligations. Fixture count validation does not establish the467 ms inter-window gap as stillness or close frame-timing acceptance.

### Offline detector configuration provenance clarification — 2026-11-22

PA confirms that immutable, enforced version-fixed configuration mapping is acceptable provenance for the new offline option; a separate parameter snapshot is not mandatory. Reopened audit withdrew implementation acceptance for evidence/interval propagation defects, not this configuration approach. Current repair status and preserved checkpoint evidence belong to the existing segmenter requirements/validation owners.

### Preserve persisted detector v1; version the correction as v2 — 2026-11-22

**Status:** DECIDED by PA; implementation review/tests pending.

The offline detector corrective pass must use a new v2 version. Preserve v1 historical run and recomputed-diagnostic semantics because a real v1 run is persisted; identical fixture boundaries do not imply identical algorithms. Reuse minimal version dispatch and append-only selection, without new framework machinery. Historical payloads remain readable. Proposed downstream ImpactAnalyzer v3 and MovementMotionAnalysis v4 version bumps remain subject to review. Existing segmentation owner retains CHANGES REQUIRED status.
