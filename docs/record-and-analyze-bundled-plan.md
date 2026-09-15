# Record & Analyze — bundled next implementation plan

## Purpose

This document supersedes the older single-slice implementation task by keeping its proven storage/database/MLS contracts and folding in the decisions made afterward.

Implement this as **three sequential slices**. Each slice should leave the app in a usable, testable state and should extend the existing code rather than create parallel recording, storage, or database subsystems.

1. **Capture UX and recording lifecycle**
2. **Durable processing queue and resource policy**
3. **Performance recordings/history browser**

Do not implement hands-free framing/readiness, movement technique analysis, coaching, rolling history, TrainingPeriod/ExerciseBlock grouping, or full retrospective movement classification in these three slices unless explicitly called out as a narrow placeholder.

---

# Shared architecture rules

Preserve the existing Android/training foundation and inspect the repository before editing. Keep the repository/domain boundary authoritative; UI and analyzers must not call DAOs directly. Extend the existing CameraX, training repository/services, Room, permission/settings, counting audio, landmark generation, and test paths rather than building duplicates.

One recording attempt that reaches CameraX creates one continuous **video-only MP4**. The MP4 is master evidence; later the user-facing hierarchy should evolve toward **activity/exercise context → recording evidence → movements → analyses**.

The recording does not own the activity definition. It stores a snapshot of expected activity context supplied by the caller. In Record & Analyze the fresh-entry default is **Alternating straight punches**. `Record another` keeps the current activity/setup, but leaving and returning later resets activity to that default while remembering repetition count, cadence, and spoken counting.

Requested repetition count is always planned/expected count, never detected count. Before segmentation, UI may show `Planned: 10`; later it can switch to actual confirmed movement count.

Persist media-relative event timestamps as integer microseconds from recording start. Cue timestamps are app playback/emission request times, not measured acoustic onset. Persist spoken cues as `SessionEvent`s. Also preserve exact timestamps for stop requested, force-stop requested, and interruption detected, separately from actual recording end/finalization.

Storage remains app-private:

- `files/training/recordings/<recordingUuid>.mp4`
- `files/training/landmarks/<landmarkTrackUuid>.mls`
- staging: `files/training/landmarks/<landmarkTrackUuid>.mls.tmp`

Use UUID filenames only, never reuse names, and keep semantic meaning in Room rather than filenames.

Canonical landmark format remains **Movement Landmark Stream (MLS)**, extension `.mls`, internal format id `movement_landmark_stream_v1`. Preserve staged/validated publication: write `.mls.tmp`, finish/flush, validate, verify integrity/checksum, publish to `.mls`, then mark LandmarkTrack complete. Inspect the existing `karate_pose_track_v1` writer/reader before any incompatible byte-format change; never silently reinterpret incompatible bytes.

---

# Step 1 — Capture UX and recording lifecycle

## Goal

Make Record & Analyze a polished assisted-capture flow that creates a finalized MP4 and accurate timeline provenance without waiting for landmark processing.

## Setup screen

Show live rear-camera preview, large Record button, planned repetition count, cadence/pace, spoken-counting toggle, current Android media output route, selected rear lens/zoom, selected/automatic quality, and expected activity.

Do **not** show Saved Recordings on this page. Saved recordings belong under Performance.

Remember repetition count, cadence, and spoken counting across visits. `Record another` keeps the full current setup including activity. Audio output is runtime Android state and is not persisted.

## Audio route

Spoken cues use the current Android media route. Show where cues will play before recording, such as Phone speaker or a connected Bluetooth/headset device. If practical, allow choosing among currently available outputs. Do not persist a preferred headset/device identity.

## Camera rules

- Rear camera only.
- Automatic quality preference: `1080p 60 fps` → `720p 60 fps` → `1080p 30 fps`.
- Advanced quality selector may expose supported alternatives.
- Default rear lens/zoom is 1×; allow another available rear lens during setup, then lock lens/zoom once countdown begins.
- Rotation may change during setup; lock capture orientation once countdown starts.
- Allow tap-to-focus/expose during setup. If untouched, let autofocus/autoexposure settle automatically; stabilize/lock as much as supported once recording begins.
- Keep the screen awake during countdown and recording.
- Do not add live pose framing, readiness detection, body-in-frame gates, or pose camera coaching in this slice. Those belong to future hands-free mode and can later be reused here.

## Record transition

When Record is tapped:

1. perform required database/session/master-recording preparation;
2. hide setup controls and most navigation chrome;
3. expand camera preview to near full-screen;
4. show visual-only `3 – 2 – 1`;
5. start CameraX at `0`, never before;
6. keep MP4 video-only, with no microphone audio;
7. if spoken counting is enabled, emit first cue at `+0.5 s` after actual recording start;
8. emit later cues at selected cadence.

Counts above 10 repeat the existing 1–10 cycle.

During recording show only essential state: near-full preview, recording indicator, elapsed time, current count if useful, and a large Stop button. Do not show technique analysis, landmark quality, movement count, coaching, or framing validation.

## Two-stage Stop

First Stop tap means graceful finish:

- persist `STOP_REQUESTED` at the exact media-relative timestamp;
- stop issuing future cues immediately;
- change button to **Stop now**;
- show `Finishing…` if useful;
- continue CameraX until the graceful target end.

Graceful target end is anchored to the **last emitted cue**:

**last cue timestamp + 1.5 × cadence**

Example: last cue 10.0 s, cadence 1.0 s, Stop tapped 10.4 s → planned end about 11.5 s. If Stop is tapped after that target has already passed, do not manufacture another delay; finalize normally.

If spoken counting is disabled and there is no cue anchor, define and document a deterministic fallback based on stop-request time and cadence.

Second tap on **Stop now**:

- persist `FORCE_STOP_REQUESTED`;
- stop/finalize immediately.

## Interruption

Back, manual phone lock, app displacement/backgrounding that invalidates active capture, or system/camera interruption should produce an interruption event/reason and stop/finalize as safely as Android permits. The recording is marked interrupted but is still valid saved evidence if MP4 finalization succeeds.

Do not require invasive phone-state permissions merely to identify phone calls. Coarse lifecycle/system interruption reasons are enough. A notification/text that does not actually displace the app should not stop recording.

Later segmentation should use these events as evidence: a final movement may still be valid if it completed and settled before the boundary; motion overlapping force-stop/interruption may be rejected as incomplete. Earlier valid movements remain unaffected.

## Finalization and success state

CameraX finalization is authoritative. Only after successful finalization should the UI claim **Recording saved**.

Do not wait for MLS generation before returning control to the user.

Show:

**Recording saved ✓**

Primary: **Record another**

Secondary: **View recording**

`Record another` returns to setup with the same current configuration. `View recording` deep-links to this exact recording under Performance once Step 3 exists.

## Step 1 tests

Cover navigation, persisted count/cadence/counting, fresh-entry activity reset, Record-another preservation, countdown timing, CameraX start at zero, video-only MP4, +0.5 s first cue, repeated 1–10 cycle, cue events, stop-request timestamp, graceful target calculation, no future cues after Stop, Stop→Stop now transition, force-stop event, interruption state/event, screen-awake behavior, orientation lock, rear-camera-only path, quality fallback order, lens lock, finalized MP4 reopen/discovery, and physical-device CameraX checks.

---

# Step 2 — Durable processing queue and resource policy

## Goal

Decouple successful capture from expensive landmark generation. A finalized MP4 is immediately a successful saved recording. MLS generation becomes durable, serial, restart-safe work.

The user must be able to record A, B, C without waiting for A’s landmark extraction.

## Queue

Room/database state is authoritative. Do not rely on an in-memory-only queue. Reconstruct pending work after process death.

Use existing repository/service boundaries and add an Android-durable scheduler appropriate to the architecture, preferably WorkManager unless repository inspection reveals a better existing durable mechanism.

Process one heavy recording at a time. No parallel MediaPipe/MLS jobs in this slice.

### Capture priority

CameraX capture wins over background processing. If recording starts while MLS work is active, pause/yield/cancel-to-retry the landmark job safely and resume/restart afterward. Job-level restart is acceptable; do not implement per-frame checkpoints yet.

### Queue order

Default FIFO/oldest-first. Explicit **Retry** or **Process now** may promote an item. Merely opening or watching a recording does not reshuffle work.

## Processing states

Persist/expose durable states sufficient for UI:

- Queued
- Processing
- Ready
- Failed / Retry

A successful MP4 remains playable in every MLS state.

## Background processing setting

Add:

- **Background processing** On / Off
- **Minimum battery** slider, hard minimum 5%

Background processing Off disables automatic/background queue execution, but explicit **Process now** / **Retry** may run while the app is open. If the user leaves the app while such foreground processing is active and Background processing is Off, pause/cancel safely and leave the work queued.

## Battery policy

The threshold is a **start boundary**, not an emergency cutoff.

Below the chosen threshold while not charging:

- do not start a new recording;
- do not start a new processing job.

But:

- an active recording may finish;
- an active processing job may finish.

Charging overrides the threshold for starting new work.

If the user taps Retry/Process now below threshold and not charging, prompt them to connect a charger rather than silently ignoring or bypassing the setting.

After a recording ends below the threshold, Record another remains disabled until battery recovers or a charger is connected.

## Low storage

Use two levels:

1. warn when storage is getting low;
2. hard-block starting a new recording only when there is clearly not enough safe space.

Do not start a capture already likely to fail because storage is critically low.

## Processing status UX

Detailed status belongs in **Performance → Recordings**. Also expose a subtle global status such as `2 recordings processing`.

## Retry / Process now

Failed recordings remain playable. Retry/Process now may promote that item in the queue and obey the battery/charging rule.

## Delete while queued/processing

For V1, Delete recording removes the recording and derived data after confirmation. If queued or processing:

- cancel that recording’s work immediately;
- delete safely;
- continue with the next queue item;
- ensure a stale worker cannot republish MLS after deletion.

Keep the lower-level architecture capable of media-only deletion later, but do not expose that complexity yet.

## MLS contract

For every finalized MP4, create/reuse the correct LandmarkTrack according to repository rules, run authoritative offline MediaPipe, write one full-recording MLS, preserve model/MediaPipe/decoder/configuration provenance, verify integrity, publish atomically, and mark complete only after verified publication.

Do not regenerate a valid completed MLS unnecessarily after restart. Handle stale `.mls.tmp` explicitly.

## Step 2 tests

Cover A/B/C non-blocking capture, serial execution, FIFO, promotion, capture preemption/yield, restart reconstruction, job-level restart, completed-MLS reuse, temp-file rejection, failed MLS preserving MP4, background On/Off, foreground Process now, leaving app with background Off, battery start gates, ongoing work continuing below threshold, charging override, charger prompt, low-storage warning/block, deletion cancellation, stale-worker deletion race, and accurate global queue status.

---

# Step 3 — Performance recordings/history browser

## Goal

Move saved-recording discovery out of Record & Analyze and into Performance. Build a compact evidence/history browser, not a gallery.

## Recent recordings

Default Performance → Recordings view shows latest **3–5** recordings, newest first, as dense list rows similar to file-browser list mode.

No thumbnail is required.

A row may show date/time, expected activity, duration if useful, planned count before segmentation, confirmed movement count once segmentation exists, and processing state.

Before segmentation:

`14 Sep · 17:42`

`Alternating straight punches · Planned 10 · Processing`

Later:

`14 Sep · 17:42`

`Alternating straight punches · 9 movements · Ready`

Do not clutter the main row with unrecognized/rejected candidate counts.

## Calendar

Provide a **month view**. Days containing relevant recordings are highlighted. A small count may be shown if readable, but is not required.

Selecting a day replaces the Recent list with a scrollable list for that day. Provide a clear way back to Recent/Today.

Week/day modes are optional future extensions.

## Filters

Filters affect both the visible recording list and calendar markers. If filter = Punches, days containing only kicks should not remain marked as relevant.

Only filter on context actually stored; do not invent inferred technique labels.

## Recording detail

Opening a row opens that exact recording.

Long-term emphasis is on **movements inside the recording**, not the raw master video.

Before real segmentation exists, detail can show recording metadata/context, processing state, Retry/Process now, and **Watch full recording** as a secondary action. Optional cue-derived playback slices may help development/navigation, but they must never be persisted as `SessionMovement`s or treated as detected movements.

Later, true segmentation can add confirmed movements, unrecognized candidates, movement-level frames/thumbnails, movement playback intervals, and analyses.

Unknown/unconfirmed candidates should be inspectable in detail but excluded from normal technique history/analysis by default. Do not show their count in the compact main history row unless future UX justifies it.

## Full MP4 role

The master MP4 remains available for playback, debugging, editor/boundary work, and reprocessing, but should remain secondary in the normal athlete workflow.

## Deep link after capture

Record & Analyze’s **View recording** must open this exact recording immediately, even while MLS is Queued, Processing, or Failed. Playback must never be blocked by MLS readiness.

## Failure and deletion

Failed processing keeps the row/video and shows Retry in detail.

V1 **Delete recording** → confirmation → remove recording plus derived data. Step 2 queue cancellation applies.

## Activity context and future grouping

Display expected activity as context, not analyzer-confirmed truth. Record & Analyze defaults to Alternating straight punches; later learning/practice activities supply their own expected activity.

Do not equate `recording = activity/session`.

Do **not** implement TrainingPeriod / ExerciseBlock grouping here yet. Those remain deferred until segmentation/history work, where multiple sets/recordings can be grouped correctly.

## Step 3 tests

Cover Saved Recordings removed from Record & Analyze, Performance navigation, latest-first ordering, dense row metadata, Planned count before segmentation, durable processing state, day selection, filters affecting list+calendar, deep-link opening exact recording, playback while MLS is Queued/Processing/Failed, Retry/Process now hooks, delete confirmation/cancellation, full-video secondary action, and no fake SessionMovement rows from cue slices.

---

# Explicitly deferred after these three steps

Do not let these slices expand into:

- hands-free start/stop;
- live body framing/readiness;
- pose-based camera setup gates;
- automatic camera-side recommendations;
- full retrospective movement segmentation unless handled as a separate next slice;
- punch/kick classification;
- technique classification;
- impact/theoretical-impact detection;
- MeasurementResults;
- straightness/target-height analysis;
- coaching;
- fatigue/rhythm;
- cadence-utilization analysis;
- rolling 100/1000;
- TrainingPeriod / ExerciseBlock;
- automatic workout grouping;
- per-movement exported MP4 clips;
- generic artifact framework.

The later segmenter may reuse cue events, stop events, interruption events, recording end, expected activity context, MLS, and cadence configuration. It must determine movement completeness from motion evidence rather than assuming each cue produced a valid movement.

---

# Overall definition of done

After all three slices, the reliable flow is:

**caller → Record & Analyze assisted setup → full-screen rear-camera capture → timestamped cues/events → graceful or interrupted finalization → immediately saved MP4 → durable serial MLS queue → user can record again immediately → Performance history/calendar → open/play/delete/retry/process saved recording independently of MLS readiness.**

The next major slice can then start from the saved MLS and perform retrospective segmentation into real `SessionMovement` records, followed later by movement-level analysis/history.
