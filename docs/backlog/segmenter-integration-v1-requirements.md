# Segmenter Integration v1

Status: IN PROGRESS

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
