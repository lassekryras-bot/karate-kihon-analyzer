# Development Backlog

Repository-owned task index. Reconciled 2026-11-07 against HEAD `94e7454`, current source, existing validation records, and the Project Architect onboarding briefing. This was a documentation/source review; no fresh application tests or device acceptance were performed.

## How to use this backlog

- `OPEN`: work or a decision remains; `IN PROGRESS`: implementation/planning exists but acceptance is incomplete; `DONE`: the task has a recorded completion claim. Historical DONE records are retained, not freshly recertified by this review.
- Read the task's latest delivery checkpoint before its original specification. Source existence is not acceptance. Historical test counts are dated evidence, not today's test results.
- The [prioritized queue](prioritized/README.md) records selected work. Lasse owns product priority; a bug severity does not silently reorder an approved assignment.
- Each requirement has one owning task file. Follow-up work stays there when scope fits. Validation plans and superseded specifications are supporting records, not additional tasks.
- Preserve screenshots under `assets/<task-slug>/`. Record implementation evidence, remaining acceptance, dependencies, and approval scope in the owning task.

## Development handoffs

Lasse owns priorities; Business Analyst maintains task/index status; Senior Developer owns implementation and verification evidence; Project Architect owns architectural decisions. At work start, scope changes, architectural review outcomes, and completion/verification milestones, Senior/Architect send a concise update to the Business Analyst task (`01a1b750-14cd-7ff3-9eec-d1a5338e84b7`): owning task, approved scope, result/commit or evidence path, checks actually performed, remaining acceptance/blockers and next action. BA requests missing evidence and updates the existing task plus applicable index entries; do not create a duplicate task or silently reorder competing priorities.

Record proposed versus approved approach, prototype implementation, automated verification, device acceptance and production activation separately. Mark DONE only against the owning task's recorded acceptance criteria; prototype completion does not authorize production activation. Material architecture questions go to Architect; priority conflicts go to Lasse. This is an event-driven handoff through existing tasks, with no recurring monitor.

## Current focus and next actions

| Work | Current state | Next action / dependency |
| --- | --- | --- |
| [Straight-punch side correctness](four-limb-motion-characterizer-requirement-v1.md#current-follow-up--2026-11-07) | IN PROGRESS — open P1 correctness defect | Establish discriminative body-relative evidence; retain abstention and historical provenance. Separate from optimization. |
| [Impact/view suitability](impact-analyzer-requirement-v1.md#delivery-checkpoint--2026-11-07) | IN PROGRESS — shared implementation exists; production output gated | Validated activity/view mapping and reliable selected-side evidence are prerequisites. |
| [Movement inspection acceptance](movement-detail-page-v1.md#delivery-checkpoint--2026-11-07) | IN PROGRESS — retained plots implemented | Phone readability/dark mode and outstanding playback/accessibility acceptance. |
| [Segmentation validation](segmenter-integration-v1-requirements.md#delivery-checkpoint--2026-11-07) | IN PROGRESS — explicit-punch v2 implemented; one recording accepted | Broader corpus and remaining integration acceptance; do not force detected counts. |

## Other implementation and acceptance work

These are not an approved execution order.

- [Landmark Geometry Core v2](landmark-geometry-core-v2-spec.md) — IN PROGRESS; Stages A–C recorded complete. D/E integration and migration remain outstanding in the owning specification; no new stage-completion claim made here.
- [Overlay Coordinate Transformer v2.1](overlay-coordinate-transformer-v2.1.md) — IN PROGRESS; implementation exists, recorded review/integration/MLS acceptance remains to reconcile criterion by criterion.
- [Body Height Model v2.1](body-height-model-v2.1.md) — IN PROGRESS; implementation and shared-core integration exist; remaining review/MLS acceptance is not closed by this pass.
- [Movement Detail, Target Height and Heavy Reprocessing corrective pass](movement-detail-target-height-heavy-reprocessing-corrective-pass.md) — IN PROGRESS; all three formerly listed code blockers now have implementations. Full acceptance remains open.
- [Recording Results Page v1](recording-results-page-v1.md) — IN PROGRESS; recording detail UI exists; full specification acceptance and future-provider distinctions need verification.
- [Queue Manager Tray v1](queue-manager-tray-v1.md) — IN PROGRESS; implemented in `454a139`; physical acceptance not established by this review.
- [Shared Camera Capture backend](shared-camera-capture-backend.md) — IN PROGRESS; existing record says implemented, physical acceptance pending; not freshly tested.
- [Record & Analyze assisted capture](record-and-analyze-assisted-capture.md) — IN PROGRESS; existing record says capture, queue and browser implemented; physical acceptance pending.
- [Karate training database foundation](karate-training-database.md) — IN PROGRESS; Room/pipeline implementation exists; recorded physical acceptance remains pending.

## Open product work — not selected for implementation

- [Trustworthy karate-analysis validation evidence](analysis-validation-evidence-and-test-strategy.md) — OPEN; PA approved scope/validation strategy on 2026-11-07. Classify test claims, reconcile fixture/count evidence, approve reviewed labels and clarify architecture/metric eligibility. Proposed high-priority follow-up; not yet selected for execution.
- [Osu meaning illustration assets](osu-meaning-illustrations.md) — OPEN; approved recurring-character artwork needed.
- [Body conditioning / knuckle push-up learning path](body-conditioning-knuckle-pushup-learning-path.md) — OPEN; later progression.
- [Home vs Learning ownership / onboarding v0.2](home-learning-ownership-onboarding-v0.2.md) — OPEN; no completion claim established in this pass.

## Completed records

- [Lite discovery / Heavy analysis — isolated prototype](prioritized/002-lite-heavy-processing-planning.md) — DONE 2026-11-08, PA-approved closure. Delivered with negative performance finding: 302.572 s versus full Heavy 171.203 s on one full-coverage source; identical Heavy evidence/boundaries. [Durable report](../validation/lite-heavy-prototype/README.md). No general performance/accuracy acceptance or production activation.
- [Movement Debug Report and Activity Override](prioritized/001-movement-debug-report-activity-override.md) — DONE; recorded 2026-10-13, implementation commit `2fa8472`. No longer the next open task.
- [Global Progressive Navigation Ownership Fix](global-progressive-navigation-ownership-fix.md) — DONE per 2026-09-19 record.
- [Analytical Vertical Slice v1: Reanalysis & Straight-Punch Target Height](analytical-vertical-slice-v1-reanalysis-target-height.md) — DONE per 2026-09-19 record. Later production impact gating is tracked separately above; this does not promise current target output for every recording.
- [Dojo Sensei Home v2.1 Corrective Pass](dojo-sensei-home-v2.1-fix-requirements.md) — DONE per 2026-09-19 record.
- [Dojo Sensei Home v2](dojo-sensei-home-v2-requirements.md) — DONE per 2026-09-19 record.
- [App Chrome Update v1](app-chrome-update-requirements.md) — DONE per 2026-09-19 record.
- [Permission status UI verification and fix](permission-status-ui.md) — DONE per 2026-09-13 record.

## Supporting and historical records

- [Segmenter validation plan](segmenter-integration-v1-validation.md) — acceptance checklist owned by the segmentation task.
- [Landmark Geometry Core v1](landmark-geometry-core-v1-spec.md) — superseded by v2; not an independent open task.
- [Four-limb implementation status](../four-limb-motion-implementation-status.md) — dated implementation and validation evidence for the characterizer and downstream integration.
- [Project knowledge](../../project-knowledge/README.md) — durable product/domain/architecture decisions; old specifications and implemented code are not automatic architectural authority.

## Reconciliation limits

Every existing backlog task is now indexed. Recent contradictions were corrected using source/commits; older task acceptance was not exhaustively retested. The Architect briefing (Project Architect task `01a1062b-9248-7d32-adc3-190301a735cd`, 2026-11-07) supplies current assignment and recording-specific user acceptance. The latest Senior Developer task summaries exposed no Stage 1 deliverable, so its completion remains UNKNOWN. No device connection or personal recording availability was freshly checked. Close remaining acceptance only with explicit, scoped evidence.
