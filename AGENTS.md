# Repository task guides

For work on measurement wiki entries, explanatory animations, graphs, or their
presentation contracts, read these before editing:

1. `docs/measurement-wiki-authoring-guide.md` — page structure, wording, architecture,
   example selection, and validation.
2. `docs/app-measurement-wiki.md` — current native implementation and known limits.
3. `docs/measurement-presentation-contract.md` — analyzer-to-app data contract.

A reusable task prompt is in `docs/prompts/new-measurement-wiki-entry.md`.
Keep the guide aligned when implementation changes these conventions. It records
the current baseline and distinguishes planned capabilities from implemented ones.

## Learning activity authoring

For work on learning activities, `ActivityShellView`, learning-path activity
routes, or activity progress, read these before editing:

1. `docs/activity-shell-authoring-guide.md` — learning objectives, activity
   selection, page patterns, wording, accessibility, and authoring workflow.
2. `docs/activity-shell-contract.md` — normative shell/runner ownership,
   lifecycle, navigation, progress, permission, recovery, and cleanup rules.
3. `docs/app-activity-shell.md` — current Android implementation, concrete
   activities, placeholder behavior, integration points, and known limits.

Use `docs/prompts/new-learning-activity.md` to start a net-new activity. Begin
with the Karate Basics template, state the learning objective and evidence, and
record every intentional deviation from the default lifecycle. Prefer deviations
inside the activity presentation model or runner. Do not add Japanese counting,
voice, camera, MediaPipe, analysis, or technique-specific behavior to the shared
shell.

The repository-owned Codex skill is
`.codex/skills/karate-activity-authoring/SKILL.md`. Use it when an installed
Codex environment supports skills; the documents above remain authoritative.

Keep passive and Ready pages hardware-off. Microphone, camera, recording,
recognition, and analysis may start only after an explicit learner action. Keep
pathway position, activity progress, completion, assessment result, mastery,
voice verification, and device verification distinct. Error is a recoverable
branch, not a mandatory learning stage. Update `docs/app-activity-shell.md` when
implemented behavior changes, and add transition, navigation, lifecycle,
permission, accessibility, and architecture-boundary tests as applicable.

## Wiki authoring decisions

- Write for a person training, not a technical reviewer. Keep animation and graph
  captions short; the optional calculation explanation can go slightly deeper.
- Keep camera setup, calibration, arm-length and general estimation explanations
  out of measurement pages. These belong in separate learning content. Preserve
  accurate units and provenance in data; do not relabel existing values.
- Extend an existing page when related measurements share the same movement,
  reference and graph. RMS and maximum wrist deviation belong on one wrist-path
  page, with separate values, not a combined score.
- For this wrist-path page, use fixed-camera wrist positions for BOTH RMS and
  maximum. Keep the camera transform, dashed start-to-end reference, and already
  recorded trail points fixed during playback. Do not drag past positions with
  the current shoulder. Other measurements must explicitly choose their own
  appropriate coordinate reference; this is not a universal camera-frame rule.
- A coordinate-reference change changes the measurement. Update computation,
  both summaries, graph, markers, assets, existing wiki renderer and documentation
  together. A corrected chat preview alone does not update the app.
- For visual iteration, provide a playable in-chat preview and a concise account
  of what changed. Automated tests do not replace visual review with the user.
- Track agreed behavior separately from shipped behavior. The grouped RMS/maximum
  page and fixed-camera calculation are now implemented in local source. Native
  compilation and device checks remain blocked/unverified; do not claim deployment.
- In the body animation, reveal a maximum marker only when the wrist reaches
  that sample. Keep it afterwards; hide it on earlier scrubbing/restart. The
  graph may show its maximum throughout. Explain speed smoothing briefly in
  the calculation page, never by altering the displayed motion.
