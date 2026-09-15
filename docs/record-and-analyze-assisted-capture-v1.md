# Record & Analyze: assisted capture

Status: all three [bundled slices](record-and-analyze-bundled-plan.md) implemented
in local source; physical acceptance remains pending. This description supersedes
the earlier synchronous extraction and capture-page saved-list design.

## Capture

Skill Coach opens `AssistedCaptureActivity` through an explicit operator action.
It owns rear-camera preview and capture outside Activity Shell. Skill Coach and
learning Ready pages remain hardware-off. This capture route uses no microphone,
recognition or live pose processing. Denial leaves Back and permission recovery.

Fresh entry defaults to **Alternating straight punches**. Callers may supply
`expectedActivity` and `expectedCategory` intent extras. These are expected context,
never analyzer-confirmed labels. Count (default 10), cadence (default 1 second,
range 0.7–10 seconds), and spoken counting (default On) are remembered. Activity
resets on fresh entry; Record another retains the full current setup. Setup
rotation retains the Activity and controls. Audio route is read from Android
at runtime, not persisted; choosing another output uses Android's controls.

Rear lenses exposed through CameraX can be selected before countdown. Zoom
defaults to 1×. Automatic quality preference is 1080p60, 720p60, 1080p30, then
720p30 and supported alternatives. Camera characteristics qualify frame-rate
candidates; binding failures try remaining candidates. These are requests,
not guarantees of measured frame rate. Camera ID, requested zoom/quality and
orientation are retained in master-recording provenance; decoded metadata
provides actual dimensions and reported frame rate. Tap-to-focus/expose is
available in setup; AE lock is requested at recording start where supported.
Lens, zoom and orientation lock from countdown. Active capture keeps the screen on.

Record first reserves capture priority. Native extraction yields at its next
progress/frame boundary; DB/session/master preparation completes before visual
3–2–1. CameraX starts at zero. Battery/storage are checked both on Record and
at this actual start boundary. Existing recording/processing may finish if battery
later drops. CameraX Start anchors cue requests, beginning at +0.5 seconds.
Counts repeat 1–10 and stop after the planned total. A finite app-cued set
auto-finishes at the last emitted cue plus 1.5 times cadence, then finalizes
without requiring Stop.
MP4 is video-only. Media-relative events are integer microseconds, currently
resolved from elapsed-real-time milliseconds; cue times mean playback requests,
not measured acoustic onset.

First Stop persists `STOP_REQUESTED`, cancels future cues and changes the button
to Stop now. End target is last emitted cue + 1.5 × cadence; if already passed,
stop immediately. With no emitted cue, the deterministic fallback is stop-request
time + 1.5 × cadence. Second Stop persists `FORCE_STOP_REQUESTED` and stops now.
Back/background/lock/camera failure records `INTERRUPTION_DETECTED` and a coarse
reason. Boundary timestamps remain separate from actual end. Pre-start cancellation
is retained as a cancelled attempt. Finalized, readable MP4 evidence is authoritative.

The saved state appears after verified CameraX finalization without waiting for
MLS. Record another returns to setup. View recording opens the exact Performance
detail immediately. Saved recordings are not listed on the capture page.

## Processing and resource policy

Room v6 stores shared capture request/result context plus durable
`RecordingProcessing` rows: Queued, Processing, Ready, Failed and Deleting.
Successful assisted finalization publishes a durable-media event and returns to
the caller. The separate worker discovers eligible finalized videos from Room;
the recorder has no WorkManager or queue dependency. WorkManager 2.10.1 wake-ups plus a 15-minute
recovery sweep drive serial processing; Room controls FIFO and explicit promotion.
One shared heavy-work lock excludes parallel legacy/queued native work. Capture
signals cancellation before waiting for that lock. Interrupted jobs restart from
the MP4 using a new track UUID; completed valid MLS files are reused.

Background processing defaults On; Minimum battery defaults 20% with a hard floor
of 5%. Off permits only explicitly requested jobs while an Activity is foreground.
Leaving the app with Off yields to Queued. Charging overrides the battery start
gate. Retry/Process now below threshold explains that a charger is needed. These
settings are in Settings. Low storage warns below 1 GiB and blocks new capture
below 256 MiB; these conservative V1 boundaries do not predict recording length.

`TrainingSessionProcessor.process` reuses or produces the authoritative
full-recording MLS, then passes its persisted frames to the existing retrospective
segmenter. The straight-punch v1 plan ends after it transactionally saves the
actual detected `SessionMovement` rows; no technique analyses or measurements are
configured. Logical landmark-membership bounds remain distinct from buffered,
possibly overlapping playback bounds. See the
[MLS contract](movement-landmark-stream-format.md). Queue or phase failure does
not remove the saved MP4 or valid upstream MLS evidence.

## Performance recordings

Performance → Recordings shows the latest five recordings as compact text rows.
Rows show date/time, expected activity, Planned count until persisted movements
exist, and durable processing state. A month calendar marks days with matching
recordings. Stored category filters affect both calendar and list. Selecting a
day replaces Recent; Recent and Today restore those views.

Detail opens the exact recording, including one outside the recent five. It shows
context, duration, interruption and processing state, Retry/Process now, and a
secondary Watch full recording action. Viewing or playback never promotes work.
Developer mode exposes the existing landmark overlay when data is ready. The
recording's persistent Segments section separately shows planned and detected
counts, processing state, logical and playback bounds, segmenter/track provenance,
and interval playback. A valid completed run with zero detections says so rather
than displaying planned repetitions as detected movements. Deletion requires confirmation and removes the owned recording/evidence graph and
files. Cancellation and a shared publication fence prevent a stale worker from
republishing MLS. Failed deletion remains durable for recovery. The lower-level
media-only deletion API remains available but is not exposed here.

The app-shell Queue Manager Tray distinguishes waiting, landmark processing, and
movement finding, then offers **Segments ready · View** for the completed recording.
It remains one row per recording, shows at most three rows plus useful overflow,
respects user collapse, and hides only while actual camera recording is active. Calendar and
dense-row queries remain read-only. Only emitted count/cue events can be associated
with segments; lifecycle events never become cues or movement boundaries.

## Schema and acceptance

Exports v1–v6 remain checked in. v2 adds cadence/counting/delay and MLS format
metadata; v3 adds expected activity/category and interruption reason; v4 adds
the durable queue and backfills saved assisted recordings; v5→v6 adds processing
plan/phase, timing, landmark-track, and segmenter provenance. Migration preserves
existing evidence and legacy absolute file references.

Technical results and remaining device checks are recorded in
[bundled validation](validation/record-and-analyze-bundled.md). Device testing must
still validate camera combinations, cue timing, real capture A/B/C, audio routing,
native extraction, process loss, battery/charging, lifecycle interruption, playback,
compact/large-text layouts and accessibility. Automated tests cannot certify them.
