# Prioritized Development Backlog

This folder contains the **ordered implementation queue** for backlog work that has been explicitly prioritized.

## Ordering rule

- Files are numbered in implementation order: `001-`, `002-`, and so on.
- Continue the selected IN PROGRESS task before taking the lowest numbered OPEN task, unless Lasse changes priority. DONE entries are history, not pending work.
- Prioritizing a task moves its authoritative task file here; do not keep a duplicate authoritative copy in the general backlog.
- For an explicitly selected sequence across existing owners, use a numbered coordination pointer linking those owners instead of moving or duplicating their requirements.
- Before implementation, verify current source and dependencies. Priority does not override architectural prerequisites or acceptance requirements.
- Completed work should be reflected in the main backlog index/validation records according to the repository's existing conventions.

## Queue

1. [Movement Debug Report and Activity Override](001-movement-debug-report-activity-override.md) — DONE 2026-10-13; implementation `2fa8472`.
2. [Lite discovery / Heavy analysis — isolated prototype](002-lite-heavy-processing-planning.md) — DONE 2026-11-08; PA-approved prototype delivery with negative performance finding on the tested full-coverage recording. Durable report/manifests retained; no general performance/accuracy acceptance or production activation.
3. [Resume end-to-end straight-punch analysis](003-end-to-end-punch-analysis-coordination.md) — IN PROGRESS; diagnostic preservation delivered at `96f00de`. Neutral-evidence integration remains design-only: compare live hold versus user-marked postcapture interval and define posture/provenance/lifecycle/UX. Product decision pending; no implementation/mandatory hold or view approval.

003 is the current selected sequence; its linked tasks retain authoritative requirements. Do not reopen Lite optimization or automatically reanalyze production recordings. Follow the [development handoffs](../README.md#development-handoffs) at work and verification milestones.
