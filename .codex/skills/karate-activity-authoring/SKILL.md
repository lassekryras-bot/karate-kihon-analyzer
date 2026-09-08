---
name: karate-activity-authoring
description: Design, implement, review, or repair learning activities and Activity Shell behavior in the Karate Kihon Analyzer repository. Use for new Karate Basics activities, activity lifecycle/UI changes, activity runners, pathway integration, learning progress, or voice/camera activity boundaries; do not use for unrelated analyzer measurements or measurement-wiki pages.
---

# Karate Activity Authoring

Work from the repository's maintained Activity Shell documents instead of
reconstructing its conventions from placeholder screens or prior chat context.

## Required context

Locate the `karate-kihon-analyzer` repository root, then read completely:

1. `AGENTS.md`
2. `docs/activity-shell-authoring-guide.md`
3. `docs/activity-shell-contract.md`
4. `docs/app-activity-shell.md`

For a net-new activity, also read and follow
`docs/prompts/new-learning-activity.md`. Inspect the active pathway definition,
related presentation models and runners, `MainActivity` routing/lifecycle,
profile persistence, and relevant tests before editing.

If one of these files is missing, report the missing repository setup and use
the remaining documents without inventing replacement policy.

## Authoring decisions

Begin with the learner objective, conditions, completion rule, evidence claim,
evidence exclusions, and transfer target. Distinguish implemented behavior,
placeholder behavior, agreed intentions, and new recommendations.

Use the existing boundary:

> Activity = shared `ActivityShellView` + activity-specific presentation model
> and runner/content.

Keep modality- and technique-specific deviations in the presentation model or
runner. Do not add Japanese counting, terminology, voice, camera, MediaPipe,
analysis, or technique logic to the shared shell.

Treat `READY -> ACTIVE -> [RESULT] -> COMPLETE` as the default. Treat `ERROR` as
a recoverable branch. Record and test intentional deviations. Do not copy
development controls or mandatory stages from `DraftPlaceholderActivityView`.

Preserve these non-negotiable boundaries:

- passive and Ready pages start no microphone, camera, recording, recognition,
  MediaPipe, or analysis;
- permissions follow an explicit learner action and denial never traps the learner;
- Back remains available, and active physical/recording/hands-free runners have
  an immediately effective persistent Stop;
- pathway position, activity progress, completion, result, mastery, voice
  verification, and device verification remain distinct; and
- accessibility, interruption, cleanup, fallback, and late-callback behavior are
  part of the activity definition and tests.

Ask for instructor or product decisions when cultural wording, physical safety,
accepted speech variants, assessment evidence, or coaching truth is unresolved.
Do not present technical recognition or valid capture as pronunciation or
technique mastery.

## Completion

Implement only the scope the user authorized. Add focused state, navigation,
permission, lifecycle, evidence, accessibility, and architecture-boundary tests
that match the change. Update `docs/app-activity-shell.md` for implemented
behavior; update the guide or contract only for genuinely reusable decisions.

Run applicable Android tests/compilation and `git diff --check`. Report device,
network, or build limitations honestly. Do not push, merge, publish, or start a
larger activity unless the user authorized that action.
