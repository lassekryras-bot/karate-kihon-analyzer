# Activity Shell contract

Status: version 1 normative contract for Android Views/AppCompat learning
activities. `MUST`, `MUST NOT`, `SHOULD`, and `MAY` describe requirements,
recommendations, and permitted deviations. See the [authoring guide](activity-shell-authoring-guide.md)
for learning-design choices and [current implementation](app-activity-shell.md)
for shipped and placeholder behavior.

## Scope

This contract governs the common page frame, macro lifecycle, shell/runner
boundary, navigation, actions, progress, completion, permissions, interruption,
recovery, accessibility, and cleanup for learning activities.

It does not define karate curriculum truth, recognition grammars, camera
algorithms, pose-analysis measurements, or technique-specific coaching.

## Ownership boundary

### Shared `ActivityShellView`

The shell MUST own:

- Back navigation and pathway/group header;
- labelled pathway position when available;
- learner-facing activity category/context;
- stable title and short purpose/subtitle;
- a primary runner/content slot;
- an optional within-activity progress slot;
- a persistent bottom action region;
- shared visual spacing and styling; and
- accessibility hooks for headings, pane/status changes, and action semantics.

The shell MUST NOT import, instantiate, inspect, or branch on:

- Japanese counting or terminology;
- speech playback or recognition;
- microphone or camera permission launchers;
- camera preview, recording, MediaPipe, or pose analysis;
- a particular technique, measurement, drill, or assessment; or
- the old guided-session training surface.

### Activity presentation model and runner

The activity MUST own:

- the objective, instructions, content, questions, prompts, and coaching;
- activity-specific substates and their mapping to a shell state;
- contextual controls such as Replay or Previous;
- permission rationale and the intentional action that requests a capability;
- modality lifecycle, callbacks, failure mapping, and idempotent disposal;
- activity progress, completion, result, and evidence semantics; and
- every intentional deviation from this contract's defaults.

## Shared regions

| Region | Required behavior |
| --- | --- |
| Header | Back MUST remain available. The title identifies the pathway/group. Path position MUST be labelled as pathway position, not mastery. |
| Context | MUST use a learner-facing category such as Learn, Practice, Test, Skill Coach, or Challenge. Technology is not a category. |
| Heading | Activity title MUST remain stable across substates. Instructions and status change below it. |
| Runner | MUST host activity content without requiring shell modality branches. |
| Progress | MAY be omitted. When present, MUST describe progress inside the current activity and remain distinct from pathway position. |
| Bottom actions | MUST contain exactly one primary action and MAY contain one secondary action when learner action is available. During automatic work it MAY temporarily show only a meaningful Stop/Cancel action and status. |
| Safety action | Physical, recording, and hands-free simulation runners MUST provide a persistent immediately effective Stop control. |
| Status | Important state changes MUST be visible and programmatically available without unnecessarily moving focus. |

Runner-level controls MAY supplement the action bar when they directly manipulate
runner content. They MUST NOT create several equally prominent competing actions.

## Macro states

`ActivityShellState` is a presentation state, not a complete domain state
machine:

| State | Purpose | Required behavior |
| --- | --- | --- |
| `READY` | Orient and prepare before commitment, permission, or physical action. | Hardware MUST be off. Show objective, task, completion rule, preparation/safety, and permission purpose when relevant. |
| `ACTIVE` | Present the current learning task. | Show one current instruction/task, current status, relevant controls, and optional truthful activity progress. |
| `RESULT` | Interpret meaningful evidence from an attempt. | State what was observed, evidence validity/uncertainty, and an actionable next step. A score is optional. |
| `ERROR` | Present a recoverable inability requiring learner choice. | Explain what could not happen, what was saved, and offer retry, fallback, or pathway exit. It MUST NOT be terminal. |
| `COMPLETE` | Confirm the defined activity completion and reconnect to the pathway. | State what was completed, accurately label evidence/fallback, and recommend a next step. |

The default transition sequence is:

```text
READY -> ACTIVE -> [RESULT] -> COMPLETE
```

`ERROR` branches from the state where a recoverable technical or content issue
occurs and returns to `ACTIVE`, a truthful fallback completion, or pathway exit.

Valid default transitions:

- `READY -> ACTIVE` through an explicit primary action;
- `ACTIVE -> ACTIVE` through runner substates/steps;
- `ACTIVE -> RESULT` when evidence needs interpretation;
- `ACTIVE -> COMPLETE` when no distinct result is useful;
- `RESULT -> ACTIVE` for a new attempt;
- `RESULT -> COMPLETE` after acknowledgement/continuation;
- `READY|ACTIVE|RESULT|ERROR|COMPLETE -> pathway` through Back/exit after cleanup;
- `ACTIVE|RESULT|ERROR -> ERROR` only when a full recovery surface is needed;
- `ERROR -> ACTIVE|COMPLETE|pathway` through retry, truthful fallback, or exit.

An activity MUST NOT auto-transition from `ERROR` into a new permission request or
hardware attempt without learner action.

## Optional pages and substates

`READY` SHOULD be used for first entry. It MAY merge into the first content page
only for brief, passive review when a Start action adds no safety, preparation,
privacy, or learning value.

`RESULT` MUST be omitted when no assessment or durable evidence needs
interpretation. `COMPLETE` MAY share a rendered surface with Result only when the
attempt evidence and completion meaning remain explicit and separately persisted.

Prompting, playing, listening, checking, preparing, positioning, countdown,
performing, analysing, feedback, retry, and temporary unavailability SHOULD
remain runner substates. They become separate learner-facing pages only when the
learner's purpose, required information, or actions materially change.

Active microphone, camera, recording, and movement countdown states MUST always
be perceivable to the learner; they cannot be hidden as implementation details.

## Actions and labels

- The shell MUST expose one primary action and no more than one secondary action.
- Labels MUST use a verb and meaningful outcome: `Start practice`, `Begin test`,
  `Stop listening`, `Check answer`, `Try again`, `Continue to …`, or
  `Return to …`.
- `OK` and `Done` MUST NOT be used where they do not communicate the result.
- Previous/Next MUST appear only for ordered learner-controlled segments.
- A disabled action MUST have a nearby reason. Disabled state MUST not rely only
  on opacity/color.
- Destructive actions MUST name the loss, such as `Discard attempt`.
- `Stop` MUST immediately make the active modality safe. `Cancel`, Back, or Exit
  means leave the activity; these semantics MUST NOT be conflated.
- Stop MUST remain available while waiting for recognition/analysis callbacks;
  late callbacks MUST NOT restart or overwrite a stopped/closed attempt.

## Back, exit, and pathway return

- Back MUST be available in every shell state.
- Back from `READY` and `COMPLETE` SHOULD return immediately to the originating
  pathway or context.
- Back from `ACTIVE`, `RESULT`, or `ERROR` MUST first stop/release activity work.
- Exit confirmation MAY appear only when leaving discards meaningful unsaved
  answers, capture, evidence, or substantial progress.
- An activity MUST preserve its originating pathway/context and return there.
- Completion MUST be persisted idempotently before unlock/next recommendations
  are calculated.
- The Complete primary action SHOULD name the suggested destination. A secondary
  action MAY repeat/review or return to the pathway.

## Progress and evidence model

These concepts MUST remain distinguishable in model names, visible labels, and
persistence:

- pathway position;
- progress inside the current activity;
- activity completion;
- assessment result for an attempt;
- longitudinal skill mastery;
- voice verification;
- camera/device/capture verification; and
- optional review/repeated practice.

Activity completion MUST NOT imply mastery. A recognition match MUST NOT be
labelled pronunciation quality. A camera-ready or valid-capture result MUST NOT
be labelled technique skill.

A technical fallback MAY satisfy a learning completion policy. It MUST record
the unavailable/unverified evidence separately and MUST NOT make the evidence
claim that the unavailable modality would have provided.

## Completion policies

Each activity MUST declare one policy:

| Policy | Completion point |
| --- | --- |
| Passive content | Required content meaningfully visited and final action invoked; opening alone is insufficient. |
| Guided sequence | Required steps/rounds completed and final step or explicit Finish invoked. |
| Knowledge check | Required responses submitted; correctness is a separate result. |
| Voice practice/test | Required attempt sequence submitted; verification is separate unless the activity explicitly gates evidence, with a non-trapping fallback policy. |
| Physical drill | Planned or adapted repetitions/time completed; self-completion MUST NOT be labelled measured technique. |
| Camera/analysis | Defined learning flow completed; valid capture/analysis evidence only when technically valid. |

Completion SHOULD require an explicit final action when the last screen contains
audio, movement, or content the learner may reasonably repeat.

## Permission and passive-page boundary

- Passive pages and every `READY` rendering MUST NOT start camera, microphone,
  recording, speech recognition, MediaPipe, or analysis.
- Permission MUST be requested only after an explicit learner action whose label
  communicates the feature being started.
- The activity MUST explain why the capability is needed and what alternative
  remains before requesting it.
- Permission MUST be checked for each protected operation; denial, permanent
  denial, one-time revocation, and unavailable service MUST be recoverable.
- Permission denial MUST NOT block unrelated learning or the whole app.
- The activity MUST NOT repeatedly pressure the learner to change a denial.
- Camera and microphone MUST NOT restart automatically on resume.

## Voice extension

Voice runners MUST:

- separate app playback from learner listening and start recognition only after
  playback completion;
- expose visible and accessible `Listening`, `Checking`, and stopped status;
- provide `Stop listening` while the microphone is active;
- distinguish no speech, no match, timeout, busy, unavailable service,
  unsupported language, and permission denial;
- bound repeated collections and then offer fallback/exit;
- treat confidence as optional recognizer metadata, not pronunciation quality;
- state honestly whether recognition may use online processing; and
- destroy/release recognizer and playback resources on disposal.

Short command and ordered-sequence runners MAY share infrastructure, but MUST NOT
share counting-specific presentation or state assumptions.

## Camera and physical extension

Camera/physical runners MUST:

- keep Ready camera-free;
- explain safe space and device placement before activation;
- visibly distinguish positioning, countdown, recording/capture, analysis, and
  stopped states;
- keep a persistent tappable Stop during countdown, movement, recording, and
  hands-free simulation;
- define frame-loss behavior before and during performance;
- treat space, light, framing, device support, and invalid capture as technical
  readiness—not learner performance;
- separate valid analysis evidence from completion;
- offer retry, review, and an appropriate non-camera/deferred path; and
- bind/release camera work with lifecycle ownership and ignore late analysis.

Passive lessons, voice-only activities, and saved-result review MUST NOT create
camera, recording, or pose-analysis resources.

## Interruption, checkpoint, and resume

Each activity MUST declare whether it is resumable and its last stable
checkpoint. On backgrounding or interruption it MUST:

1. stop/pause active microphone, playback, countdown, recording, camera, and
   callbacks that should not continue unseen;
2. save the last stable checkpoint when meaningful;
3. mark an in-flight utterance/repetition/capture interrupted rather than failed;
4. prevent stale callbacks from changing current state; and
5. require a new learner action before hardware resumes.

On return, the activity SHOULD offer a specific choice such as `Resume at number
6`, `Restart this attempt`, or `Return to path`. A physical or voice attempt
SHOULD restart at its attempt boundary rather than resume mid-action.

## Accessibility requirements

- Interactive Views MUST have descriptive labels, roles, states, and accessible
  actions. Headings and changing runner panes SHOULD expose appropriate semantics.
- Focus order MUST follow visual/task order. Dynamic status MUST NOT move focus
  unless learner action is required there.
- Important status changes MUST be announced programmatically, with throttling
  for partial recognition, frame guidance, timers, and analysis progress.
- Essential audio/video MUST have equivalent visible text or a suitable
  alternative; media MUST be pausable/stoppable/replayable.
- Touch targets MUST be at least 48dp in both dimensions.
- State MUST use text/shape/icon in addition to color.
- Actions and content MUST remain usable with large text/display scaling and on
  supported narrow screens.
- Voice-only input MUST have a touch alternative unless voice is the assessed
  skill; even then, an unverified learning/fallback path MUST prevent trapping.
- Movement/camera activities MUST document appropriate accommodation, adapted,
  no-camera, deferred, or instructor-assisted paths.
- Timing MUST be adjustable unless timing is intrinsic to the objective; safety
  Stop is never time-limited.

## Cleanup contract

Activity runner cleanup MUST be idempotent and invoked on:

- Back/Exit;
- Finish or route replacement;
- application/activity stop when work must not continue unseen;
- retry before a new attempt is created; and
- destruction.

Cleanup MUST cancel pending restart/countdown callbacks, stop playback/listening,
close camera/analysis runners, release recognizers/players, and prevent late
callbacks from rendering into an inactive activity as applicable.

## Permitted deviations

A deviation MUST:

1. improve or protect the activity's learning objective, accessibility, safety,
   evidence integrity, or modality task;
2. stay inside the runner/presentation model whenever possible;
3. state why the default is unsuitable;
4. preserve Back, truthful status, recovery, permission, and cleanup invariants;
   and
5. have a focused test when it changes consequential behavior.

Visual novelty, implementation convenience, or an existing placeholder is not
sufficient justification.

## Testable invariants

At minimum, tests SHOULD verify:

- the shared shell has generic regions and no modality/technique dependencies;
- one required primary and at most one secondary shell action;
- Ready/passive entry creates no hardware or permission request;
- every presentation state maps to forward/recovery/exit behavior;
- Result is optional and Error is recoverable;
- pathway position and activity progress remain separate;
- completion is idempotent and does not fabricate evidence;
- Back/Stop/background/disposal stop the relevant work;
- permission denial/revocation and service failure do not trap the learner;
- accessibility labels, focus/status behavior, touch targets, and non-color cues;
- route origin, completion unlock, and next recommendation; and
- intentional deviations and their rationale-specific behavior.
