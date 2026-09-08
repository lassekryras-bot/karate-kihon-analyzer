# App Activity Shell

Status: current implementation map for the Android Views/AppCompat application.
Use the [authoring guide](activity-shell-authoring-guide.md) for learning-design
decisions and the [contract](activity-shell-contract.md) for normative behavior.
This file distinguishes implemented behavior from placeholders and planned work.

## Current source map

Paths are relative to `android/KarateClipRecorder/app/src` unless stated otherwise.

| Responsibility | Location |
| --- | --- |
| Shared shell and shell state | `main/java/dk/lasse/karatecliprecorder/learningactivity/ActivityShellView.kt` |
| Temporary unfinished-activity scaffold | `main/java/dk/lasse/karatecliprecorder/learningactivity/DraftPlaceholderActivityView.kt` |
| Counting practice View/presentation | `main/java/dk/lasse/karatecliprecorder/learningactivity/JapaneseCountingPracticeView.kt` and `JapaneseCountingPracticePresentation.kt` |
| Counting test View/presentation | `main/java/dk/lasse/karatecliprecorder/learningactivity/JapaneseCountingTestView.kt` and `JapaneseCountingTestPresentation.kt` |
| Counting controllers/audio/recognition | `main/java/dk/lasse/karatecliprecorder/learning/` and `MainActivity.kt` |
| Terminology presentations and Views | `main/java/dk/lasse/karatecliprecorder/learningactivity/KarateTerminologyPresentations.kt` and `KarateTerminologyViews.kt` |
| Generic live speech and terminology prompts | `main/java/dk/lasse/karatecliprecorder/learning/LiveSpeechRecognizer.kt`, `ShortVoiceCommand.kt`, and `TerminologySpeechPlayer.kt` |
| Ready? — Osu front-camera capture | `main/java/dk/lasse/karatecliprecorder/learning/ReadyOsuSelfieCamera.kt` |
| Karate Basics catalogue | `main/res/raw/karate_basics_path.json` |
| Draft pathway parsing/resolution | `main/java/dk/lasse/karatecliprecorder/learningpath/DraftLearningPathModels.kt` |
| Routing and host lifecycle | `main/java/dk/lasse/karatecliprecorder/MainActivity.kt` |
| Profile/activity status | `main/java/dk/lasse/karatecliprecorder/profile/ProfileRepository.kt` |
| Presentation tests | `test/kotlin/dk/lasse/karatecliprecorder/learningactivity/` |
| Architecture tests | `test/kotlin/dk/lasse/karatecliprecorder/architecture/` |

## Implemented shared shell

`ActivityShellView` currently owns:

- `SubPageHeader` with Back callback and pathway-position trailing label;
- context/section and category line;
- activity title and subtitle;
- replaceable `runnerSlot`;
- optional replaceable `progressSlot`;
- fixed bottom `actionBar` inside navigation-bar insets; and
- one required primary action plus an optional secondary action.

Its enum currently contains `READY`, `ACTIVE`, `COMPLETE`, `RESULT`, and `ERROR`.
The shell itself does not interpret the enum; concrete presentations map their
domain state to it. It does not contain Japanese lesson data, camera preview,
microphone permission, MediaPipe, or app bottom navigation.

The current heading and action controls are native Views. The shell sets a
content description for its context line. A complete common status-announcement
API, pane-title/focus policy, exit guard policy, and explicit safety-action slot
are not implemented yet.

## Implemented Japanese Counting Practice

`JapaneseCountingPracticePresentation` maps:

```text
READY -> ACTIVE (numbers 1–10) -> COMPLETE
```

Implemented behavior:

- opens Ready without starting its controller;
- explicit Start practice action;
- ten learner-controlled steps;
- example audio and Replay;
- Previous and Next/Finish navigation;
- pathway position (`1 / 2`) separate from number progress;
- completion/restart behavior; and
- explicit `cameraRequired = false` and `microphoneRequired = false`.

It intentionally has no Result page because it is guided practice. It does not
request camera or microphone permission and does not use MediaPipe or recording.

Current limitation: presentation copy and default path position are partly tied
to the standalone two-activity counting path. Host/path metadata should remain
the source when this activity is embedded elsewhere.

## Implemented Japanese Counting Test

`JapaneseCountingTestPresentation` currently maps `CountTrainingPhase` as:

| Training phase | Shell state |
| --- | --- |
| `IDLE`, `READY` | `READY` |
| `LISTENING`, `FINALIZING` | `ACTIVE` |
| `RESULT` | `RESULT` |
| `ERROR` | `ERROR` |

The test uses `JapaneseCountingTestView` and the shared shell rather than the
camera-heavy training surface. It has an explicit Start test action and a Stop
listening action. Recognition is bounded to the expected ten-number sequence.
The test does not use camera preview.

Current gaps relative to the contract:

- its Error mapping predates the formal recovery contract and needs UI review for
  retry, fallback, and pathway exit;
- recognition result is not yet modelled as separate durable voice evidence;
- a dedicated Complete presentation is not represented by
  `CountTrainingPhase`; and
- its page styling, actions, recovery, and resume behavior still need redesign to
  match the counting-practice experience and the new contract.

These gaps do not change the current implemented logic during this documentation
slice.

## Placeholder-only behavior

`DraftPlaceholderActivityView` states in its source that it is a temporary
developer shell for unfinished Karate Basics activities. It uses:

```text
INTRO -> ACTIVITY -> RESULT -> COMPLETE
```

and exposes developer controls such as Mark complete, Reset activity, Previous
stage, and Next stage.

These controls and stages are not intended end-user UI and do not define the
normative lifecycle. A concrete activity replaces the placeholder route and owns
its own presentation/runner. Do not preserve development controls when doing so.

## Pathway configuration and routing

`karate_basics_path.json` is the current structured Karate Basics catalogue. It
defines the pathway whose purpose is to prepare the learner for a first
hands-free ten-punch session. Many entries remain placeholders.

`DraftLearningPathModels.kt` currently recognizes conditional-profile, the three
terminology activities, Japanese-counting-practice, Japanese-counting-test, and
placeholder activity types. This draft enum is routing infrastructure, not the
complete pedagogical taxonomy from the authoring guide.

`MainActivity.openKarateBasicsActivity` routes the terminology and counting
activity types to dedicated Views and all unfinished entries to the placeholder.
The host supplies pathway position and uses profile state/prerequisites to
resolve availability and completion.

The repository does not currently contain a separate active roadmap document for
the Activity Shell. The branch, Karate Basics JSON, and implementations above are
the operative baseline.

## Navigation and lifecycle

Implemented host behavior includes:

- stopping active training before opening counting practice/test;
- explicit entry into counting practice before its controller starts;
- returning from practice/test to the appropriate learning-path context;
- cancelling recognition restart and stopping training/counting audio in
  `MainActivity.onStop`;
- cancelling an in-flight counting test when the activity becomes stopped; and
- releasing recognizer/audio/camera/analysis resources in `onDestroy`.

Current limitations:

- activity runner disposal is coordinated through `MainActivity` rather than a
  formal common runner interface;
- interruption currently maps an active counting recognition attempt to an error
  instead of an explicit interrupted/resume checkpoint;
- no common exit-guard policy distinguishes unsaved work from safe immediate exit;
- the old training hub still contains camera-heavy behavior and is not the target
  Activity Shell design; and
- real-device background, call, rotation, permission-revocation, and late-callback
  behavior need contract-specific validation.

## Progress and persistence

`ProfileRepository` currently saves broad `LearningStatus` values, active activity
identity, completion timestamp, and recency. `touchActiveLearningActivity`
preserves completed status while updating recency.

Implemented persistence does not yet provide distinct durable records for:

- a stable within-activity checkpoint;
- an attempt result;
- skill mastery;
- voice verification;
- camera/capture/device verification; or
- review/repeated-practice history.

The contract defines these conceptual distinctions; it does not require a data
migration in this documentation slice. Future implementation must not overload
`LearningStatus.COMPLETED` to claim any of them.

## Current tests

`JapaneseCountingPracticePresentationTest` verifies Ready-without-start, first
active item, Previous state, ten-item completion, pathway-position stability, and
restart.

`JapaneseCountingTestPresentationTest` verifies Ready/Active/Result/Error shell
mapping, listening/finalizing differences, and bounded recognized progress.

`JapaneseCountingActivityShellArchitectureTest` verifies generic shell regions,
absence of Japanese/camera/microphone/bottom-navigation coupling, hardware-free
practice entry, camera-free test routing, and the updated 1–10 activity catalogue.

`ActivityShellContractArchitectureTest` adds the shared action-boundary,
placeholder-is-not-contract, passive-view modality isolation, and host cleanup
invariants from the formal contract.

`KarateTerminologyPresentationTest` covers the passive lesson sequence, Osu
variant recognition, selfie capture/result evidence, unverified completion, Stop
methods, count completion, and interruption recovery. `ShortVoiceCommandMatcherTest`
verifies narrow command matching, and `KarateTerminologyActivityArchitectureTest`
protects concrete routing, passive hardware boundaries, front-camera ownership,
permission entry points, and Stop cleanup.

These source-level architecture tests are fast safeguards, not substitutes for
compiled View tests, accessibility tests, screenshots, or real-device validation.

## Implemented terminology activities

The Karate Terminology & Voice Interaction section now has three concrete routes:

| Activity | Category | Implemented lifecycle | Important deviation |
| --- | --- | --- | --- |
| Osu — Meaning & Use | Learn | Ready → Active → Complete | No Result or microphone. |
| Ready? — Osu | Practice | Ready → Active ↔ Error → Result → Complete | Model/camera/prompt/listen/check/capture are runner substates; a recognized `Osu` triggers a front-camera selfie. |
| Stop the Session | Practice | Ready → Active ↔ Error → Complete | The app counts 1–10 in Japanese while a narrow Stop recognizer listens; persistent tappable Stop is always available. |

Implemented behavior:

- all three use `ActivityShellView`; only Ready? — Osu uses a camera, while none
  uses MediaPipe or video recording;
- Ready pages and the passive model start no camera or recognition; front-camera
  and microphone permissions follow the labelled `Start hands-free selfie`
  action;
- Ready? — Osu finishes prompt playback before recognition starts and treats a
  recognizer match as intent verification, not pronunciation quality;
- Ready? — Osu opens a front-camera preview, automatically captures a mirrored
  selfie after a recognized response, stops both modalities, and shows the image
  on a distinct Result page;
- the selfie remains in memory for the current result only and is discarded on
  retry, exit, or destruction; the activity separately records `voiceVerified`,
  `selfieCaptured`, and `selfiePersisted=false` evidence;
- Osu examples use two packaged voice recordings and make a fresh random sample
  choice for each playback; the English Ready prompt continues to use device
  text-to-speech;
- Stop the Session intentionally uses a runner-level barge-in exception so Stop
  can interrupt the Japanese count; the exact-token matcher ignores count words;
- tapping Stop immediately cancels both count playback and recognition and still
  completes the activity without a voice-verification claim;
- permission and service failures offer button-only completion rather than
  trapping the pathway;
- activity completion is stored in learning progress, while voice verification,
  attempt count, and Stop method are stored separately in a training-session
  result payload; and
- backgrounding cancels audio/listening and presents an interrupted recovery
  state that requires a new learner action.

### Ready? — Osu hands-free selfie definition

- **Objective:** respond to the app's Ready prompt with Osu and experience that
  acknowledgement starting the next action without touching the phone.
- **Conditions:** after an explicit learner action, the front camera provides a
  framing preview and the microphone listens only after the Ready prompt ends.
- **Completion:** review the captured selfie or the truthful no-photo result,
  then explicitly finish the practice.
- **Evidence:** activity completion, recognized Osu intent, and successful selfie
  capture are independent facts.
- **Evidence exclusions:** the photo and recognizer do not assess pronunciation,
  readiness quality, appearance, or karate skill.
- **Transfer:** prepares the learner for later hands-free prompts where Osu means
  `I heard the instruction and I am ready to continue`.

Adding a Result page and front-camera capture intentionally replaces the earlier
camera-free/no-Result design. The Result is justified as immediate experiential
feedback showing that the spoken acknowledgement caused an action. It is not an
assessment result.

Current limitations:

- the packaged Osu recordings and cultural wording still require karate-
  instructor/content review before release;
- speech recognition may use the device's configured online service;
- front-camera availability, preview mirroring, shutter timing, permission
  revocation, and selfie orientation still require representative-device tests;
- Stop barge-in needs real-device testing across speakers, recognizers, noise,
  volumes, and Bluetooth routes to assess false positives and masking; and
- TalkBack, large-text, narrow-screen, interruption, and permission-revocation
  behavior still need physical-device validation.

## Known contract gaps and next implementation order

1. Use the contract to redesign Japanese Counting Test without changing its
   recognition algorithm unnecessarily.
2. Add common accessibility/status and exit/cleanup behavior only when a concrete
   activity proves the reusable API; do not pre-load the shell with modality UI.
3. Review the packaged Osu recordings and remaining device-generated English
   prompts with an instructor/content owner before release.
4. Validate short-command recognition and Stop barge-in on representative real
   devices without connecting it to the old guided-session MVP.
5. Validate the Ready? — Osu front-camera capture and hands-free timing on
   representative devices without persisting its session-only selfie.
6. Add a typed durable activity-attempt/evidence model when more activities need
   to query voice evidence; the current training-session JSON keeps completion
   and verification separate without a premature migration.

Before claiming release readiness, run native compilation, View/presentation
tests, screenshot/layout review, TalkBack and large-text checks, and real-device
voice/camera/interruption matrices.
