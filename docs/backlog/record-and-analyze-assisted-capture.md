# Record & Analyze assisted capture acceptance

Status: IN PROGRESS

## Proposed neutral-evidence follow-up — 2026-11-21

Under [003](prioritized/003-end-to-end-punch-analysis-coordination.md#future-neutral-evidence-proposal--2026-11-21), Senior proposes a live neutral-hold stage after video starts, reusing existing `PunchHeightAnalyzer` setup/initialization rules. Persist the accepted recording-relative interval and rule/quality/camera provenance as an append-only session event; downstream reference geometry must be recomputed from the exact canonical Heavy track, not copied from preview coordinates. Neutral evidence does not grant impact/view suitability.

Reported current gaps: assisted capture is preview-only with no pose frames, neutral stage or typed event. Four-slice plan is with PA; implementation is not yet approved. No production source/phone data changes are part of the diagnostic close-out. This existing capture task owns any later approved capture-flow change; geometry and target analysis keep their existing owners.

PA subsequently approved design exploration only: no implementation or mandatory hold. Before choosing a live path, compare reuse of existing live validation with simpler recorded-video validation of an explicit neutral interval. Specify actual neutral posture separately from stability, timestamp/geometry/lifecycle/event identity, feasibility and hold/skip/failure UX. Diagnostic preservation approval does not approve this capture change.

The three [bundled steps](../record-and-analyze-bundled-plan.md) are now implemented
locally: capture lifecycle, durable queue/resource gates and Performance recordings.
See [current validation](../validation/record-and-analyze-bundled.md). Physical
acceptance remains open; the phone was disconnected during this continuation.
Earlier Galaxy A55 checks below apply only to the preceding assisted-capture build.

The [assisted capture slice](../record-and-analyze-assisted-capture-v1.md) is
implemented locally. Complete physical validation before marking DONE.

- Open Skill Coach → Record & Analyze; verify live preview and touch controls.
- Verify remembered count/cadence/toggle, 3–2–1, start at zero, first cue +0.5 s,
  cadence, repeated 1–10 and continued recording after the last cue.
- Record another person performing arbitrary karate movements and tap Stop.
- Verify finalized video-only MP4, one MLS, Room relationships, no movements or
  analysis rows, and saved/ready statuses.
- Play the full MP4; check debug landmark overlay alignment and scrubbing.
- Close/kill/reopen; verify discovery, validation and no redundant decode.
- Exercise camera denial/settings, preparation/countdown cancellation,
  backgrounding, finalization and extraction failures, process death and retry.
- Review compact/large-text layouts, TalkBack, speaker routing and sound loading.

Galaxy A55 / Android 16 technical checks passed on 2026-09-14: installation,
on-device migration, navigation, preview, settings persistence across restart,
production schema/FKs and camera release. System-bar overlap was found, fixed
and visually rechecked. The user requested technical checks first; no recording
was started. Defaults were restored and the app left on Skill Coach, camera off.
Results are recorded in `docs/validation/assisted-capture-v1.md`.
