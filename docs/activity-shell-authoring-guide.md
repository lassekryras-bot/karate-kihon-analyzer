# Activity Shell authoring guide

Status: version 1 authoring baseline. This guide defines how to design a learning
activity for Karate Kihon Analyzer. It does not claim that every activity type or
reusable runner is implemented. Read it with the [normative contract](activity-shell-contract.md)
and the [current implementation map](app-activity-shell.md).

## Purpose

Create short, coherent mobile learning experiences that prepare ordinary karate
learners—including beginners, children, adults, and people training without an
instructor immediately present—for useful karate practice.

The starting architecture is:

> Activity = shared `ActivityShellView` + activity-specific presentation model
> and runner/content.

The shell supplies orientation, navigation, predictable actions, and common
styling. The runner supplies the actual lesson, question, audio turn, voice
attempt, physical drill, camera setup, capture, analysis, or coaching. Do not
make the shell aware of a particular modality, language, or technique.

## Start with the learning objective

Before choosing screens or controls, write:

1. **Objective:** what the learner will understand or do.
2. **Conditions:** what prompts, examples, equipment, permissions, or assistance
   are available.
3. **Completion:** what the learner must do to finish this activity.
4. **Evidence:** what the activity can truthfully say was demonstrated.
5. **Transfer:** where this learning will be used later in the pathway.

Keep one clear objective per activity. Split content when the learner changes
from acquiring to independently demonstrating a skill, when a new permission or
physical setup begins, when failure changes meaning, or when the completion
evidence changes.

Examples:

- Hearing and repeating Japanese numbers 1–10 is guided practice.
- Counting 1–10 while the microphone checks the sequence is a separate test.
- Learning what “Osu” means is separate from practising a spoken response.
- Camera setup is separate from the technique activity that uses the camera.

Do not use a universal minute, word, card, or concept limit. Prefer meaningful,
learner-paced segments. A page usually has one immediate instruction or question,
one main object of attention, and one primary next action. Validate density and
duration with the intended learners and target devices.

## Choose the learning mode

Learner-facing categories should stay small and understandable:

| Category | Promise to the learner | Typical behavior |
| --- | --- | --- |
| **Learn** | Understand a term, idea, context, or safety rule. | Explanation and modelling; no performance claim. |
| **Practice** | Rehearse with cues, replay, support, or retry. | Low stakes; feedback helps the next attempt. |
| **Test** | Demonstrate independently under stated conditions. | Reduced coaching during the attempt; result afterwards. |
| **Skill Coach** | Use observed movement or a saved result for focused feedback. | Capability and evidence validity are explicit. |
| **Challenge** | Integrate several previously learned skills. | Clear prerequisites, conditions, safety, and outcome. |

Internally, describe the runner and policies separately instead of creating a
learner-facing category for every technology combination:

- runner: paged content, guided media, voice response, knowledge questions,
  physical timer, camera setup, camera capture, analysis, or result review;
- capabilities: audio output, microphone, camera, movement, local analysis, or
  network-dependent service;
- completion and outcome policies;
- resume/checkpoint policy;
- safety and Stop policy.

## Shape the learning sequence

Across the pathway, provide:

1. purpose;
2. explanation or model;
3. guided attempt;
4. independent attempt when assessment is intended;
5. informative feedback;
6. a cue showing where the skill transfers to karate practice; and
7. later review or retrieval.

One activity does not need every element. Informational lessons and guided
practice commonly omit Result. A test or valid technique analysis commonly uses
Result. Complete reconnects the learner to the pathway; it is not a second score
screen.

### Explanation and modelling

- Name the target in ordinary language before implementation or anatomical terms.
- Demonstrate the action or sound before expecting a first attempt.
- Use text with essential audio or video, and allow replay/pause.
- Put cultural nuance, troubleshooting, and technical detail behind progressive
  disclosure. Do not hide the core instruction, safety requirement, permission
  purpose, or completion rule.

### Guided practice

- Make examples, replay, hints, Previous, and retry available when useful.
- Reduce support only when the learner is ready for an independent attempt.
- Treat mistakes as information for the next attempt, not loss or punishment.
- Permit repetition without erasing an already completed activity.

### Assessment

- Say what is being assessed and what evidence counts before the attempt.
- Do not silently add hints during a test or silently score a practice activity.
- A result can be descriptive and criterion-based; it does not need a number.
- A recognizer match is not a pronunciation assessment. A valid camera capture
  is not proof of technique mastery.

### Feedback

Feedback should communicate what the system observed, the validity or uncertainty
of that observation, and one controllable next action. Avoid feedback whose only
content is a color, praise, punishment, or score.

Give immediate feedback when it prevents practising the wrong sequence, resolves
a knowledge response, or protects safety. Summarize after a movement when live
correction would interrupt performance. Instructor and learner testing determine
the appropriate timing for each technique.

## Default pages and valid deviations

The default learner lifecycle is:

> Ready → Active → optional Result → Complete

Error/Recovery branches from the relevant state and returns to retry, fallback,
or the pathway. See the contract for transitions and required controls.

| Page | Learner question | Typical content |
| --- | --- | --- |
| Ready | What will happen, and am I prepared? | Objective, task, completion rule, time/steps when useful, safety, setup, and permission rationale. |
| Active | What do I do now? | One current task, current status, runner controls, and truthful activity progress. |
| Result | What did this attempt show? | Valid evidence, uncertainty, useful interpretation, and next action. |
| Error/Recovery | What could not happen, and what can I do? | Plain cause, saved-work consequence, retry, fallback, and exit. |
| Complete | What did I finish, and what is next? | Objective recap, accurate evidence label, next pathway recommendation. |

Allowed deviations include:

- omit Result when no assessment or durable evidence occurred;
- merge Ready into the first passive content page for a brief review when a Start
  action adds no preparation, safety, or privacy value;
- use runner substates for prompting, playing, listening, checking, positioning,
  countdown, performing, analysing, and retry;
- reduce nonessential chrome during movement while preserving Back and Stop;
- use a checklist, repetitions, steps, or no activity progress instead of a
  percentage;
- place a persistent safety Stop inside the runner rather than making it a
  changing secondary action.

Every deviation needs a one-line learner or safety rationale in the activity
specification and a corresponding test where behavior is consequential.

## Actions and navigation

- Keep Back available. From Ready and Complete it normally exits immediately.
  From Active, stop modality work before leaving. Confirm only when exit would
  discard meaningful unsaved work or finalized evidence.
- Use one prominent shell primary action and at most one shell secondary action.
  Put small contextual controls beside their content without making them compete
  with the primary action.
- Label actions with a verb and outcome: `Start practice`, `Begin test`,
  `Stop listening`, `Check answer`, `Try again`, `Continue to counting test`, or
  `Return to Karate Basics`.
- Avoid `OK`, `Done`, and an unexplained `Continue`.
- Previous and Next are for ordered learner-paced segments, not universal shell
  controls.
- Stop makes an active physical, recording, or listening state safe. Cancel/Back
  leaves the activity. Do not use those labels interchangeably.
- If an action is disabled, explain why nearby. During automatic work, show a
  status and a meaningful Stop/Cancel when possible instead of a mysterious
  disabled button.

## Progress, completion, and evidence

Keep these distinct in copy, models, and persistence:

| Concept | Meaning |
| --- | --- |
| Pathway position | Where this activity sits, such as `Activity 4 of 20`. |
| Activity progress | Steps, items, rounds, or repetitions inside this activity. |
| Activity completion | The learner reached the defined end condition. |
| Assessment result | Evidence from one attempt. |
| Skill mastery | Longitudinal confidence from suitable evidence and review. |
| Voice verification | The recognizer matched the expected speech under the attempt conditions. |
| Device verification | Camera, framing, or analysis was technically valid. |
| Review/repetition | A completed activity was practised again. |

Technical incompatibility must not permanently block the learning pathway when a
truthful alternative exists. A fallback can complete learning without claiming
voice verification, valid capture, analysed technique, or mastery.

## Voice authoring

Design explicit turn-taking:

1. app says or plays the prompt;
2. playback completes;
3. the interface changes to `Your turn`;
4. recognition starts and visibly announces `Listening`;
5. the learner can tap `Stop listening`;
6. checking reports a match, uncertainty, or technical recovery.

The recognizer must not hear the app's prompt. Distinguish no speech, no match,
timeout, service busy/unavailable, unsupported language, and permission denial.
Use neutral copy such as “The app couldn't confirm that” rather than “You said it
wrong.” After repeated failed collections, offer another input or a clean exit;
do not loop indefinitely.

Explain ordinary-user privacy before requesting microphone access. Do not promise
on-device processing unless the selected recognizer confirms it. Provide a touch
alternative when voice is not itself the assessed skill; when voice is the target,
allow learning completion without falsely recording verification.

## Camera and physical activity authoring

- Ready is camera-free and explains space, placement, safety, permission, and the
  non-camera alternative.
- Request camera access only after `Set up camera` or `Start camera practice`.
- Give one positioning cue at a time; separate lighting/frame/device problems
  from technique feedback.
- Pause a countdown when the learner leaves frame. During performance, stop or
  invalidate the attempt according to the activity's documented safety policy.
- Keep a persistent reachable Stop action during countdown, movement, capture,
  and hands-free simulation.
- Show stable status during analysis. Ignore late results after exit or retry.
- Show one understandable coaching point first. Put raw measurements behind
  details only when they are valid and interpretable.
- Passive lessons, voice-only practice, and result review never start camera,
  recording, MediaPipe, or analysis.

## Accessibility and inclusive design

Accessibility is part of every activity, not a separate variant:

- meaningful View labels, roles, heading semantics, and logical focus order;
- important dynamic statuses announced without moving focus unnecessarily;
- no repeated announcement of partial speech hypotheses or every camera frame;
- text equivalents for essential audio and video; media can pause, stop, and replay;
- at least 48dp touch targets, including Back, Replay, and Stop;
- status uses text/shape as well as color;
- large text and display scaling without clipped actions;
- reduced unnecessary movement and no avoidable time pressure;
- touch alternatives to voice, and appropriate alternatives/accommodations for
  camera and movement;
- usable recovery in noisy/shared environments and after calls, notifications,
  backgrounding, or another person interrupting.

Test TalkBack, Voice Access/Switch Access where available, large fonts, narrow
screens, and the actual dynamic runner on physical devices.

## Authoring workflow

1. Read `AGENTS.md`, this guide, the contract, implementation map, pathway
   configuration, related presentations/views, and relevant tests.
2. Inspect the branch and uncommitted changes. Preserve unrelated work.
3. Write objective, conditions, completion, evidence, transfer, and audience needs.
4. Choose category, runner, capabilities, progress, result, resume, and safety policies.
5. Make a state/action table covering Back, interruption, recovery, fallback,
   accessibility announcement, saved checkpoint, and cleanup.
6. Start from `ActivityShellView`; list intentional deviations and rationale.
7. Write final learner copy, including failure and completion states. Do not ship
   placeholder controls or infer intended UI from `DraftPlaceholderActivityView`.
8. Obtain instructor/content review for terminology, cultural nuance, physical
   safety, acceptable variants, and coaching claims.
9. Implement presentation logic separately from modality control where practical.
10. Add transition, navigation, permission, cleanup, persistence, accessibility,
    and architecture-boundary tests appropriate to the activity.
11. Run Android tests/compilation and `git diff --check`; report blocked checks.
12. Review the activity on representative devices and update
    `docs/app-activity-shell.md` to distinguish newly implemented behavior from plans.

## Definition of done

- The objective, completion, and evidence are explicit and agree with the UI.
- The shell remains modality- and technique-neutral.
- Every state has a valid forward, recovery, or exit path.
- Ready/passive states are hardware-off; permissions follow intentional actions.
- Back, Stop, interruption, restoration, and cleanup are specified and tested.
- Progress meanings are not conflated.
- Copy is final, respectful, readable aloud, and accessible.
- Intentional deviations are documented and tested.
- Pathway routing, prerequisites, completion, and next recommendation work.
- Automated checks pass and real-device checks are reported honestly.
