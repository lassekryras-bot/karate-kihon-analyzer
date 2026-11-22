# Segmenter Integration v1

Status: IN PROGRESS

## Delivery checkpoint — 2026-11-07

OBSERVED: integration exists; `d8d0539` selects QoM v2 for explicit straight punches while kick/unknown activity retain v1. See [current routing decision](../../project-knowledge/DECISIONS.md#2026-10-15--supported-world-qom-v2-production-scope) and [replay evidence](../motion-detector-math.md#20-supported-world-v2-evidence-policy-and-production-selection).

The Architect briefing on 2026-11-07 reports Lasse accepted all ten windows of one recording as one punch each. This is recording-specific boundary acceptance, not universal validation or proof of side/impact correctness. The old blanket claim that automated build and all physical boundary validation remain pending is stale; no fresh build/device check ran here.

Remaining: broader corpus and outstanding [integration validation](segmenter-integration-v1-validation.md). Preserve actual counts, versioned runs and evidence gaps. Full acceptance is not established; retain IN PROGRESS. Original v1 requirements below are historical baseline where later routing decisions apply.

## Purpose

Connect the existing persisted landmark-processing pipeline to the existing
retrospective movement segmenter so that a completed Record & Analyze recording
becomes a set of persisted, inspectable movement segments.

This is the first end-to-end processing slice after landmark extraction.

The goal is:

> Recording → Landmarks → Segmentation → Segments ready → View recording
> segments

No technique analyzer is required in v1.

## Core user outcome

After recording a straight-punch activity, the user can wait for processing to
complete and then open the recording and inspect the individual movements
detected by the segmenter.

The user must be able to see:

- how many movements were detected;
- each movement as a separate segment;
- the logical start and end of each movement;
- the corresponding video interval; and
- enough diagnostic information to validate whether segmentation is correct.

The system must report what was actually detected. Do not manufacture the
planned number of repetitions.

For example, `Planned: 10 punches / Detected: 9 movements` is valid.

## Existing architecture

Before implementation, inspect the current repository and reuse the existing
architecture.

The repository already contains concepts for:

- finalized master recordings;
- persisted Movement Landmark Stream data;
- recording/session activity identity;
- processing state;
- retrospective segmentation;
- `SessionMovement` persistence;
- recording history; and
- background processing.

Do not build a second segmentation pipeline. Do not decode the video through
MediaPipe again if a compatible persisted landmark track already exists. The
persisted landmark track is the authoritative source for segmentation.

## Processing pipeline

For the first supported activity, use straight punches.

The v1 processing plan is:

1. Finalized recording
2. Landmark processing
3. Retrospective segmentation
4. Persist movement segments
5. Processing complete

There are no configured movement analyzers in v1. Therefore, segmentation is
the terminal processing stage for this first processing plan.

## Activity-linked processing

The recording already carries, or must resolve, an activity identity. Introduce
only the minimum processing-plan concept required for this task.

For v1, straight punches:

- requires landmarks;
- requires segmentation; and
- has no analyzers.

Do not build the complete future reanalysis/version-upgrade system in this task.
However, avoid hard-coding the processing coordinator so that straight punches
can never gain analyzers later.

The activity configuration should eventually be able to define required
foundational processing, configured analyzers, versions, and the terminal state
for the recording.

## Processing-plan snapshot

When processing begins, retain enough information to know what processing was
requested for that recording. For v1, the plan may be very small.

The important rule is:

> Processing is complete when the processing plan selected for that recording
> has completed.

Do not define completion globally as “landmarks ready” or permanently as
“segmentation ready.” Future activity plans will continue from segmentation
into analyzers.

## Segment ownership of landmarks

Do not copy landmarks into separate per-movement landmark files. Keep one
authoritative master landmark timeline. Each persisted movement references a
time interval or range within that source timeline.

Conceptually:

```text
Master landmark track

→ Movement 1: start → end
→ Movement 2: start → end
→ Movement 3: start → end
```

The movement's logical start and end define which frames belong to that movement
for future analysis.

## Logical versus retained intervals

Keep these concepts separate.

### Logical movement interval

The segmenter's detected `start → end` is the canonical movement boundary.
Future technique analysis uses this interval unless an analyzer explicitly
defines a narrower analysis window.

### Retained/playback interval

The retained interval may include pre-roll and post-roll around the logical
movement. This exists to make video inspection easier.

Retained intervals may overlap. That must not cause ambiguity about which
landmarks canonically belong to a movement.

## Cue handling

Cue timing is not part of movement-boundary detection in this version. Do not
force movement count or boundaries from expected cues.

If the existing retrospective segmenter associates cues with movements,
preserve that behavior but validate the input. Only genuine cue/count emission
events should enter the cue timeline. Do not accidentally treat unrelated
session events, such as recording state changes or Stop events, as movement
cues.

Cue, reaction, and cadence analysis are outside this v1 task.

## Processing trigger

A successfully finalized Record & Analyze video should automatically become
eligible for its configured processing pipeline. The user should not need to
press Analyze after explicitly using Record & Analyze.

The existing recording-processing queue should own execution. Do not create
separate user-visible jobs for landmarks, segmentation, or future analyzers.
They are phases of one recording-processing job.

## QTray relationship

QTray represents the recording-processing job as a whole. For this v1 processing
plan, it may progress through statuses such as:

- Queued
- Processing landmarks
- Finding movements
- Segments ready

Landmarks being complete does not make the QTray job ready to view. For this
activity's current plan, **Segments ready** is the terminal usable state. Only
then should the QTray expose its normal completed-result View action. Future
activity plans may continue beyond segmentation into analysis before becoming
Ready.

Do not make QTray responsible for running processing. QTray observes processing
state.

## Opening a recording while processing

The underlying recording may still be opened manually from the recording or
history area while processing is underway. If segmentation is unfinished, the
Segments area should show an appropriate processing state.

The QTray View action has a different meaning: the requested processing pipeline
has reached its usable terminal state.

## Recording Segments page

Add a Segments subpage or view to an individual recording. This becomes the
persistent location for segmentation results after the temporary QTray entry
disappears.

At minimum, show:

- activity;
- planned repetitions, if known;
- detected movement count;
- processing/segmentation status; and
- one entry per detected movement.

Each movement entry should expose enough information for validation, including:

- movement number;
- logical start;
- logical end;
- duration;
- retained playback start;
- retained playback end; and
- the ability to inspect or play that movement interval.

Do not make the initial page into a polished coaching or results page. Its first
purpose is segment validation.

## Diagnostic support

Provide a practical way to inspect segmentation quality. A developer or
diagnostic presentation may additionally expose:

- source landmark-track identity;
- segmentation version;
- timestamps;
- logical start and end;
- retained start and end;
- frame count;
- associated cue, if one exists; and
- preferred or peak frame, if already available.

If practical, a simple timeline visualization showing detected intervals against
the full recording is desirable.

The diagnostic tooling should make it easy to answer:

- Did the segment start too early?
- Did it start too late?
- Did it end too early?
- Did it include the full movement?
- Were two movements merged?
- Was one movement split?
- Was unrelated motion detected as a movement?

## Persistence

Detected movements must survive:

- leaving the recording page;
- app restart;
- process death; and
- reopening the recording.

Do not make segmentation only an in-memory UI result. Reuse the existing Room
movement/session model where appropriate. Repeated processing must not
accidentally create duplicate copies of the same segmentation run.

## Failure behavior

### Landmark failure

Do not run segmentation.

### Segmentation failure

Retain the recording and landmark evidence. Mark processing as incomplete or
failed with a meaningful reason. Do not delete useful upstream evidence.

### Zero detected movements

Treat this as a valid segmentation outcome unless the existing architecture
explicitly distinguishes it as a failure. Show the result clearly to the user.
Do not invent movements to satisfy the planned count.

## Processing timing

Instrument the duration of at least landmark processing and segmentation. The
purpose is to establish actual processing cost before deciding whether
segmentation or future analyzers need separate execution queues.

Do not create a second heavy-processing queue in v1.

## Out of scope

Do not implement in this task:

- Chūdan target analysis;
- Jōdan or Gedan analysis;
- punch-height measurement;
- speed analysis;
- directness or deviation analysis;
- hikite analysis;
- theoretical-impact analysis;
- reaction-time coaching;
- cadence scoring;
- automatic historical reanalysis;
- idle maintenance processing;
- full processing-plan upgrade/version comparison;
- polished QTray redesign; or
- automatic reprocessing of old recordings.

The architecture may leave room for these features. They are not acceptance
requirements for Segmenter Integration v1.

## Acceptance criteria

Segmenter Integration v1 is complete when:

1. A finalized supported recording automatically enters landmark processing.
2. Successful landmark processing continues into retrospective segmentation.
3. Segmentation consumes the persisted landmark track rather than redundantly
   rebuilding it.
4. Detected movements are persisted.
5. Logical and retained intervals remain distinct.
6. The actual detected count is preserved independently from the planned count.
7. Reopening the recording shows the same persisted segments.
8. The recording has a Segments page or view.
9. Individual movements can be inspected against the video.
10. Processing state exposes the segmentation phase for QTray.
11. The recording job becomes Ready only after its configured v1 plan is
    complete.
12. Segmenter failure preserves recording and landmark evidence.
13. Genuine cue events are not confused with unrelated session events.
14. Processing durations are measurable.
15. Automated tests cover normal, zero-movement, failure, restart/persistence,
    and planned-count mismatch cases.
16. At least one real-device straight-punch recording is used to visually
    validate the detected boundaries.

## Implementation principle

Keep this task vertical. Do not solve every future analysis problem.

The milestone is:

> We can record one continuous training session and reliably turn its persisted
> landmark timeline into inspectable, persisted individual movements.

See the companion [validation plan](segmenter-integration-v1-validation.md) and
[starter prompt](../prompts/segmenter-integration-v1-start-prompt.md).

## Offline side-view alternating-punch option — 2026-11-22

**Status: implementation and validation pending.** User implementation request relayed by PA; Senior owns code, source evidence and phone execution. BA inspected `Downloads/karate_qom_team_handoff.zip` / `START_HERE.md` as proposal and fixture evidence. This extends this existing segmentation owner under coordination task003, not a duplicate task or replacement of legacy profiles.

Implement a versioned opt-in shared-core detector restricted to the supported offline side-view alternating straight-punch profile. Preserve immutable raw MLS and separate derived repair provenance (observed/interpolated/unresolved, anchor timestamps and reliability). Reference fixture uses left-arm length outlier detection (median ±3 scaled MAD), bounded timestamp interpolation (200 ms anchor span), smoothed body-centroid-relative arm coordinates, world-X travel proxy, trailing150 ms QoM integration and quiet-range adaptive candidate detection. Its8× quiet-range acceptance factor is fixture-tuned; left-arm repair is the only demonstrated repair side. Do not silently generalize to bilateral repair, other camera views, adaptive axes or physical calibration.

No spoken count, expected count or CSV boundary enters detection. Associate cues afterward. Adjacent logical intervals may share endpoints; frame ownership is half-open with a shared sample assigned to the later movement, while candidate scoring retains its boundary-sample convention. Viewing padding remains separate. Downstream side, impact and target analyzers retain independent evidence/view requirements; segmentation success grants no downstream eligibility. Legacy processing and historical versions remain preserved.

Fixture: session `e38f86cd-9e95-4cfb-80ff-45c176241c01`, track `d7d06fdc-c247-429c-8af3-8d8c89f25e16`; archive states MLS SHA-256 `499d6fd3beb0acf6a21680bad4c0461274a526f22673b3b0afcf4c673a2d4a15`. Reference claims625 frames,21 candidates,20 accepted vs prior17. These are DOCUMENTED until reproduced. The CSV contains detector output, not independently annotated onset/end truth. The archive states the MP4 was unavailable. Exact video boundaries and generalization remain pending; see the existing validation owner for required evidence.

### Explicit activation decision — 2026-11-22

DECIDED by PA: activate this experimental option only through explicit recorded processing-plan selection for side-view alternating straight punches. Persist the selected option, configuration/version and selection provenance. Keep the existing default unchanged; never select it automatically for all alternating-punch recordings.

Current `operator_selected` requestedView and post-segmentation ObservationContext do not establish authoritative pre-segmentation side-view evidence. Explicit plan selection declares segmentation intent only. It is not ImpactViewApproval or camera suitability approval and must not unlock downstream side, impact or target evidence gates. Implementation and fixture acceptance remain pending.

### Replay centroid roster clarification — 2026-11-22

PA corrected an earlier review assertion: the actual replay includes bilateral FOOT_INDEX landmarks. Its suffix check sees INDEX, so foot/toe indices are not excluded. Use an explicit landmark-ID roster matching the executable replay, including both FOOT_INDEX points. This corrects the interpretation, not the intended reference output. BA found no existing foot-index exclusion claim in the current backlog/project-knowledge text; this clarification prevents such an assumption from entering implementation requirements. Fixture parity and independent validation remain separate evidence obligations.

### Unresolved-point exclusion supersedes exact replay parity — 2026-11-22

DECIDED by user, relayed by PA: exclude unresolved points in the production pipeline of the new detector. Preserve the original replay unchanged as the regression comparison. Predictable enforcement of the repair limit is the rationale; this is not a claim of more accurate movement timing. Failed-length points whose anchor span is233 ms exceed the200 ms interpolation limit and remain UNRESOLVED with a recorded reason and retained raw observations. Other usable landmarks may contribute. Reset affected histories; never compute velocity across the unsupported gap. This is unresolved-point exclusion, not removal of FOOT_INDEX from the centroid roster.

Exact parity with all40 reference endpoints is superseded by three explicitly expected differences: punch2 end2,801,000→2,434,000 us; punch3 start2,801,000→2,901,000 us; punch19 end19,407,000→19,374,000 us. The other37 endpoints remain expected to match. Original replay has21 candidates/20 accepted windows; exclusion variant has20/20. Preserve both sets as distinct evidence. The467 ms interval between2.434 and2.901 seconds is NOT established stillness. Independent video annotation of punch2 end and punch3 onset is still required. PA reports fixture count validated; frame timing remains OPEN. Implementation/full-suite acceptance remains pending review.

### Reviewed implementation checkpoint — 2026-11-22

PA approved the explicit-profile implementation after direct code review, graph inspection and fresh test XML:544 core tests plus512 app tests, zero failures/errors and seven app skips. Senior separately reports private fixture replay20 candidates/20 windows with the three approved endpoint differences. PA authorized one normal phone verification for session `e38f86cd-9e95-4cfb-80ff-45c176241c01`, preserving raw evidence and history. Device acceptance remains PENDING; independent frame-timing and generalization acceptance remain OPEN. Default selection and downstream impact/view eligibility are unchanged.

Diagnostic provenance is `RECOMPUTED_FROM_RETAINED_RUN`: reconstruct diagnostics from SHA-verified raw MLS and the version-fixed configuration. Do not describe derived-point diagnostics as persisted observations or a persisted derived track. Required repair/unresolved reasons and anchor provenance must remain distinguishable in the recomputed evidence.

### Device checkpoint verified by Senior — 2026-11-22

Senior reports successful normal phone verification on Samsung SM-A556B: one explicit profile selection and one new run `9a16c7e0-2a8e-444f-8eea-ea3ea7e4b721` for session `e38f86cd-9e95-4cfb-80ff-45c176241c01`. Reopened UI shows Detected20 / Planned20; all20 windows match the approved exclusion variant, with only the three approved reference deltas. Both previous runs remain retained. Raw MLS SHA-256 is unchanged: `499d6fd3beb0acf6a21680bad4c0461274a526f22673b3b0afcf4c673a2d4a15`. Installed/build APK SHA-256 matches: `CEC3727E577C8304A5248D71700EAD772021A22D0518A8607FCC3661F1C05392`.

Movement2 ends with `EVIDENCE_GAP_WITHOUT_STOP_EVIDENCE`; the467 ms interval is not confirmed stillness. This closes the scoped device checkpoint on Senior's evidence, distinct from PA's direct local code/test/graph review. It does not establish exact video boundary timing or generalization. Per-point diagnostics recomputed on the phone were not reported directly inspected; private MLS and retained-run replay tests passed instead. Default selection and downstream approval remain unchanged.

### Reopened audit — CHANGES REQUIRED, 2026-11-22

PA withdrew implementation acceptance after a reopened audit. This supersedes the earlier approval status, without erasing the factual20-movement replay/phone checkpoint. Count/output parity was successful but insufficient for implementation acceptance.

Senior owns focused repairs and regression tests for:

- UI Time currently uses padded playback bounds while duration uses logical bounds; align the displayed interval semantics.
- New-profile half-open frame ownership differs between shared core and app for nonshared ends; make ownership consistent.
- Centered reference smoothing bridges long tracking gaps; preserve gap boundaries.
- Missing QoM is represented as numeric zero and loses availability through serialization/ImpactAnalyzer; preserve unknown evidence end to end.
- Signed world-X diagnostics are omitted; retain the promised diagnostic evidence separately from absolute motion.

Repairs/tests precede another phone run. Existing version-fixed configuration mapping is acceptable if immutable and enforced; a parameter snapshot is not newly mandatory. Timing/independent annotation/generalization remain OPEN. Prior run/history and observed count evidence remain valid records, not proof these defects are resolved.

### Corrective versioning decision — 2026-11-22

DECIDED by PA: deliver the corrected detector as v2; do not change v1 in place. A v1 phone run is already persisted, and deterministic recomputed diagnostics depend on immutable version semantics. Preserve historical v1 processing/recomputed diagnostic behavior. Equal fixture boundaries do not establish algorithm equivalence. Use existing minimal version dispatch and append-only selection, without a new framework.

Senior proposed ImpactAnalyzer v3 and MovementMotionAnalysis v4 for changed downstream missing-evidence semantics; these downstream version numbers are PROPOSED pending review, not a completed delivery. Keep historical payloads/rows readable. Overall status remains CHANGES REQUIRED pending implementation review and tests.

### Corrective v2 reviewed; device verification pending — 2026-11-22

Current status supersedes CHANGES REQUIRED for the reviewed implementation checkpoint: PA approved corrected v2 for ONE fresh-build normal phone verification. Direct review covered version separation, ownership propagation, missing QoM and gap isolation. Fresh XML:548 core +515 app tests, zero failures/errors, six app skips. Prior audit findings are addressed in reviewed code; this is not yet device acceptance.

Senior must verify actual logical-versus-playback labels,20 windows and approved deltas, new version provenance and missing-evidence behavior on device, not count alone. Preserve historical v1 run/recomputed diagnostic meaning and original replay comparison. Independent frame timing and generalization remain OPEN.
