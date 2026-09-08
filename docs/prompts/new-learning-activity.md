# Starter prompt: create a learning activity

Copy the prompt below into a new coding conversation. Replace the bracketed
activity information. Leave an item unknown rather than inventing curriculum,
assessment, cultural, safety, or recognition truth.

---

Work in `lassekryras-bot/karate-kihon-analyzer` and design and implement the
Karate Kihon Analyzer learning activity **[ACTIVITY NAME]** in the existing
Android Views/AppCompat application.

Known activity specification:

- Learner-facing category: [Learn / Practice / Test / Skill Coach / Challenge].
- Learning objective: [one observable objective].
- Conditions/support: [examples, prompts, equipment, permissions, instructor help].
- Completion rule: [what finishes the activity].
- Evidence claim: [what this activity can truthfully say was demonstrated].
- Evidence exclusions: [what completion or technical verification does not prove].
- Pathway/prerequisites: [Karate Basics activity ID, prerequisites, next activity].
- Transfer target: [where the learner uses this later].
- Runner/capabilities: [content, audio, voice, questions, movement, camera, analysis].
- Intended deviations from the default: [none, or reasoned list].
- Instructor/content decisions already approved: [copy, variants, safety, technique].
- Publication: prepare a reviewable local change; do not push or merge unless
  authorized in this conversation.

Before editing:

1. Inspect the current branch, uncommitted changes, and relevant open work. Do not
   overwrite unrelated changes or recreate work already present.
2. Read `AGENTS.md`, `docs/activity-shell-authoring-guide.md`,
   `docs/activity-shell-contract.md`, `docs/app-activity-shell.md`, the Karate
   Basics pathway configuration, related activity Views/presentations/controllers,
   `MainActivity` routing/lifecycle, profile persistence, and relevant tests.
3. Distinguish implemented behavior, placeholder-only controls, prior intentions,
   and new behavior proposed by this task. Do not treat
   `DraftPlaceholderActivityView` as intended end-user UI.
4. Briefly summarize the reusable architecture and identify unresolved content,
   safety, recognition, assessment, or fallback decisions. Ask only questions
   that materially change the activity.

Use the existing architecture:

> Activity = `ActivityShellView` + activity-specific presentation model and
> runner/content.

Keep the shell generic. Put modality and learning deviations inside the activity
presentation model or runner. Do not make `ActivityShellView` aware of Japanese
counting, terminology, speech recognition, camera, MediaPipe, recording, pose
analysis, a specific technique, or the old guided-session MVP.

## Design before implementation

Produce a concise activity specification containing:

1. objective, conditions, completion, evidence, exclusions, and transfer;
2. category, runner type, capability, progress, result, resume, and safety policy;
3. a state/action table with:
   - macro shell state;
   - runner substate;
   - learner instruction and visible/programmatic status;
   - primary, secondary, and runner-level actions;
   - Back/Stop behavior;
   - permission or hardware state;
   - stable saved checkpoint;
   - cleanup and late-callback behavior;
4. every intentional deviation from Ready → Active → optional Result → Complete,
   with a learner/safety rationale; and
5. all instructor, product, accessibility, or device decisions still open.

Do not proceed with a consequential cultural, physical-safety, scoring, or
recognition assumption when instructor/product approval is required.

## Page and interaction requirements

- Ready explains the objective, task, completion rule, and relevant preparation.
  It starts no camera, microphone, recognition, recording, MediaPipe, or analysis.
- Active shows one current task and status. Use meaningful learner-paced segments.
- Result is optional. Use it only when attempt evidence needs interpretation; a
  result does not require a score.
- Error is a recoverable branch with retry, truthful fallback, and pathway exit.
- Complete states what was completed, what evidence was or was not recorded, and
  names the recommended next activity.
- Back is always available. Confirm exit only when meaningful work would be lost.
- Use one prominent shell primary action and at most one shell secondary action.
  Put contextual Replay/Previous controls near their content without competing.
- Use verb/outcome labels; avoid `OK` and ambiguous `Done`.
- Previous/Next are not universal.
- Physical, recording, and hands-free simulations have a persistent immediately
  effective Stop action inside the runner/safety layer when necessary.

Keep pathway position, within-activity progress, completion, attempt result,
mastery, voice verification, camera/device verification, and repeated practice
separate in models, persistence, copy, and tests.

## Audio and voice requirements

When this activity uses voice:

- request microphone permission only after an explicit learner action;
- explain purpose and possible system/online processing in ordinary language;
- finish app prompt playback before starting recognition;
- make `Listening`, `Checking`, and stopped states visible and accessible;
- provide `Stop listening` while the microphone is active;
- distinguish no speech, no match, timeout, busy/unavailable service, unsupported
  language, permission denial, and interruption;
- bound retries and offer another way forward;
- keep recognition confidence/verification separate from pronunciation quality;
- provide a non-voice completion route when pedagogically valid without claiming
  voice evidence; and
- cancel/release playback, recognition, restart callbacks, and late results on
  Stop, retry, Back, backgrounding, route replacement, and destruction.

Reuse counting infrastructure only at a capability boundary. Do not force a
short command into the one-to-ten sequence presentation or state model.

## Camera and movement requirements

When this activity uses camera or movement:

- keep Ready non-camera and explain space, placement, safety, permission, and
  alternative/deferred paths;
- request camera permission only from `Set up camera` or `Start camera practice`;
- distinguish preparation, positioning, countdown, performing/capture, analysing,
  feedback, stopped, and unavailable substates in the runner;
- keep a persistent tappable Stop during countdown, movement, capture, and
  hands-free simulation;
- define behavior when the learner leaves frame or conditions are invalid;
- classify light, space, framing, device, capture, and analysis failures as
  technical—not learner-performance feedback;
- show simplified valid coaching before raw measurements; and
- lifecycle-bind/close resources and ignore late callbacks.

Passive learning, voice-only activities, and saved-result browsing must never
start camera, MediaPipe, recording, or analysis.

## Accessibility requirements

- Provide descriptive View labels, roles, states, headings, focus order, and
  accessible alternatives to custom actions.
- Announce important status changes without unnecessary focus movement; throttle
  partial recognition and camera-frame updates.
- Provide visible equivalents for essential audio/video and pause/stop/replay.
- Use at least 48dp touch targets and do not encode status only by color.
- Verify large fonts/display scaling and supported narrow screens.
- Provide touch alternatives to voice and appropriate adapted, no-camera,
  deferred, or instructor-assisted paths for movement/camera activities.
- Handle calls, notifications, backgrounding, noisy/shared spaces, and people who
  cannot or do not want to use speech/camera/movement.

## Implementation and tests

Prefer a pure presentation model that maps activity/domain state to shell state,
copy, progress, actions, and accessibility status. Keep platform services and
hardware lifecycle in a runner/controller boundary. Reuse existing shell and
style components before creating new ones.

Add focused tests for:

- all valid transitions and recovery/exit paths;
- objective-specific completion and evidence semantics;
- one-primary/optional-secondary action behavior;
- Ready/passive hardware-off entry and in-context permission requests;
- Back, Stop, interruption, stable checkpoints/resume, idempotent disposal, and
  ignored late callbacks;
- permission denial/revocation and voice/camera/service failures;
- accessibility labels, focus/status behavior, touch targets, large text, and
  non-color status cues;
- pathway origin, prerequisites, completion persistence, unlock, and next action;
- shell dependency boundary and every intentional deviation.

Do not add implementation-mirroring tests for prose-only details. Preserve a
small source-boundary test where it cheaply prevents camera/microphone/technique
coupling; prefer behavioral presentation/controller tests for state rules.

Run the applicable Android unit tests and compilation, plus `git diff --check`.
Perform or explicitly defer screenshot/layout, TalkBack, large-text, and
real-device modality validation. Do not claim device verification that did not run.

Update `docs/app-activity-shell.md` with the concrete lifecycle, route, evidence,
known limitations, and implemented versus planned status. Update the guide or
contract only when this activity reveals a genuinely reusable rule.

Finish with:

- the objective and lifecycle implemented;
- intentional deviations and rationale;
- completion/evidence/fallback behavior;
- files changed;
- automated and device validation performed or blocked;
- implementation versus placeholder status; and
- at most three next decisions or tasks.

---

## Initial terminology sequence

Use these decisions only when the named activity is being implemented:

### Osu — Meaning & Use

- Category: Learn.
- Intended lifecycle: Ready → Active → Complete.
- Informational/cultural context and app-specific meaning; no microphone.
- No Result because no performance is assessed.
- Requires instructor/content approval of cultural wording.

### Ready? — Osu

- Category: Practice.
- Intended lifecycle: Ready → Active ↔ Error → Complete.
- Short prompt/playback → learner turn → listen → check → feedback/retry cycle.
- Microphone starts only after explicit voice-practice action.
- Completion and voice verification remain separate; no pronunciation claim.

### Stop the Session

- Category: Practice and safety-command simulation.
- Intended lifecycle: Ready → Active ↔ Error → Complete.
- Camera-free; spoken Stop plus a persistent tappable Stop that always works.
- Manual Stop completes the safety simulation but does not claim spoken-command
  verification.
- Do not connect this activity to or redesign the old guided-session MVP.
