# Queue Manager Tray v1 — Requirements and Implementation Prompt

Status: IN PROGRESS

## Purpose and milestone

Implement the app-level **Queue Manager Tray** for the Karate Kihon Analyzer
Android application. It is the user-facing presentation of the existing recording
processing queue, not another queue or coordinator:

> Recording/processing system → Queue Manager → Queue Manager Tray

The milestone is complete when a learner can record, continue navigating, observe
that one recording move through its configured processing phases, receive Ready
only at the plan's terminal usable state, select View, and inspect the persistent
recording result.

## Product behavior

The tray is a persistent application-shell element below the top header and above
page content. It communicates whether recent user-requested work is waiting,
active, ready, incomplete, or failed without blocking top or bottom navigation.
It is not a snackbar and must not float over important controls.

Use **Queue Manager Tray** in user-facing and architectural documentation. `QTray`
may remain internal shorthand. Do not cosmetically rename unrelated queue classes.

### One recording, one job

A recording has one user-visible processing job. Landmark generation,
segmentation, target analysis, speed, deviation, hikite, and future analyzers are
phases, not separate tray rows. A row evolves, for example:

```text
Straight punches — Waiting
Straight punches — Processing landmarks
Straight punches — Finding movements
Straight punches — Analyzing movements
Straight punches — Ready
```

The activity's processing-plan snapshot determines the terminal usable state. The
tray must not define segmentation as universally terminal. Under Segmenter
Integration v1, the straight-punch plan requires landmarks and segmentation with
no analyzers, so **Segments ready · View** is correct.

View means the selected plan has reached a usable terminal result. Intermediate
landmark or segmentation completion must not expose it when configured work
remains. The underlying recording remains manually accessible during processing;
its persistent result UI should show the corresponding incomplete state.

### Queue Manager contract

The Queue Manager should expose, without UI reconstruction from unrelated views:

- job, recording, and session identities;
- activity identity and display name;
- planned repetitions when known;
- durable job state and meaningful phase;
- queue position or real progress when meaningful;
- terminal/readiness outcome and useful failure reason; and
- whether the job is recent user work or quiet maintenance.

Never fabricate percentage progress. A truthful stage such as **Finding
movements** is preferable to an invented percentage.

The Queue Manager owns durable jobs, phases, processing truth, completion, and
failure. The Queue Manager Tray owns open/collapsed state, animation/gesture
state, temporary contextual hiding, visible-row selection, overflow copy, and
acknowledgement presentation. Neither may take over the other's responsibilities.

## Expanded and collapsed presentation

When expanded, show at most three individual rows. Each row gives activity or
recording identity, current meaningful state, optional concise detail, and an
action only when meaningful. Additional jobs use an informative summary such as:

> +5 more · 3 ready · 1 processing · 1 waiting

Order deterministically: active processing, ready, failed/incomplete, then
waiting; within a group prefer the most recently relevant item and a stable ID
tie-breaker. Prefer a Queue Manager-defined priority if one is introduced later.
One recording never appears twice as its phase changes.

Swipe up collapses the tray; the tray retracts behind the header and leaves a
minimum 48 dp handle. Tapping the handle or swiping down expands it. The handle
may show a compact processing, count, or ready indicator, but not row details.
Never display a nonfunctional drag handle.

User collapse is intentional presentation state. New work or completion must not
force expansion. During actual camera recording, hide the tray contextually so it
cannot interfere. Restore it afterward without changing whether the user had
collapsed it. Countdown alone is not actual recording.

Use existing dark navy/charcoal surfaces, coral/red accent, white and muted text,
borders, typography, spacing, icons, and corner treatment. Keep hierarchy
secondary to page content and avoid default gray dialogs, a new blue processing
theme, nested card clutter, excessive shadow, or distracting animation.

## Completion, acknowledgement, and dismissal

A ready result remains in the current app-session tray until the user acknowledges
it by selecting View or manually opening that same ready recording. View navigates
to the persistent recording result—Segments for the current plan—not a tray-owned
result store. Acknowledgement removes presentation only and never deletes the
recording, landmarks, segments, analyses, measurements, or processing history.

Optional swipe dismissal is allowed only for completed/ready notifications and
has the same presentation-only meaning. Do not add an X to every row. Active work
must not be casually dismissible or accidentally treated as cancelled.

On fresh launch, reconstruct durable active/waiting work. Do not repopulate the
tray with every old ready recording as a permanent inbox; those results remain in
Performance/Recordings. Failed work with inspectable upstream results may expose
**Processing incomplete · View**. Completely unusable failure follows the existing
retry/details path. Never delete upstream evidence because a later phase fails.

## Maintenance boundary

The tray answers: “What am I waiting for from work I recently asked the app to
do?” Historical idle reanalysis, cache generation, database cleanup, migration,
quality upgrades, and processing optimizations are normally excluded. The Queue
Manager may mark or derive recent-user versus maintenance audience. Do not build
the future maintenance scheduler or processing-version comparison here, and do
not let tray code decide whether old evidence needs reanalysis.

## Accessibility

Rows, View actions, and collapse/expand controls require meaningful labels,
logical focus order, at least 48 dp touch targets, readable text scaling, and
non-color-only state communication. Expose expanded/collapsed state to
accessibility services. Ready, processing, and failure meaning must be textual.

## Architecture and implementation instructions

Start from the current repository state and inspect recording finalization,
processing persistence/queue, landmarks, segmentation, activity/plan terminal
logic, recording-result navigation, shared app headers/navigation, existing queue
status UI, and camera recording state before changing architecture.

Priorities:

1. Make a clean Queue Manager observer/presentation contract over real durable
   processing truth.
2. Connect a functional app-shell tray.
3. Add interaction, styling, and accessibility.

Extend existing recording-processing infrastructure. Do not create another queue.
The Segmenter Integration v1 lifecycle is the first concrete input:

```text
Record & Analyze → saved → queued → Processing landmarks → Finding movements
→ segments persisted → Segments ready → View → Recording/Segments
```

Do not duplicate or alter segmentation in the tray task. Do not redesign
Performance, recording results, camera capture, or the overall shell beyond the
small changes required to place and navigate the tray.

## Testing requirements

Cover at least:

- zero jobs, one waiting job, one active job, stage transitions, ready, incomplete,
  and failed states;
- at most three rows, informative overflow, and deterministic ordering;
- user collapse and no forced reopen on a new completion;
- actual-recording hide and correct open/collapsed restoration;
- View and manual-navigation acknowledgement;
- acknowledgement/dismissal preserving durable evidence;
- active jobs not being dismissible or cancelled;
- durable active work reconstructed after restart without stale ready flooding;
- maintenance jobs excluded from normal rows; and
- accessibility labels/state where practical.

Reuse existing queue tests. Run affected queue, training, recording, and app-shell
tests, `git diff --check`, and the debug APK build.

## Physical-device validation

When a device is available, verify header and bottom-navigation clearance,
expand/collapse gestures and handle state, three-row and overflow layout, recording
hide/restore, live phase updates, terminal View navigation, manual acknowledgement,
large text, accessibility, and natural back navigation. Exercise one real Record &
Analyze flow through landmarks, movement finding, Segments ready, and the correct
recording Segments result. If unavailable, keep physical acceptance explicitly
pending.

## Out of scope

Do not implement segmentation or landmark algorithm changes, technique analyzers,
historical reanalysis, idle scheduling, full version migration/comparison, cloud
sync, active-job cancellation, broad app-shell redesign, or unrelated UI polish.

## Acceptance criteria

1. Real Queue Manager state drives the tray; there is no mock/parallel queue.
2. One recording remains one item through all internal phases.
3. Meaningful stages, ready, incomplete, and failed outcomes are represented.
4. Expanded state shows at most three deterministic rows and useful overflow.
5. Collapse/reopen works and user collapse survives job changes.
6. Actual recording temporarily hides the tray and restores prior intent.
7. View appears only at the configured terminal usable state and opens the
   persistent recording result.
8. View or manual result navigation acknowledges the ready notification without
   deleting evidence; active work cannot be dismissed as cancellation.
9. Fresh launch restores active truth without flooding old ready notifications.
10. Maintenance work is normally excluded.
11. Existing visual and accessibility language is used.
12. Automated tests and debug build pass, and physical validation is completed or
    explicitly pending.

## Final implementation report

Report the implementation summary, Queue Manager source, one-job model, readiness
rule, tray states, acknowledgement/dismissal semantics, maintenance filtering,
major files, exact test/build results, exact physical-device status, remaining
limitations, commit SHA, and PR details.
