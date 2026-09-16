# Development Backlog

This folder stores small, concrete development requirements that came out of short design/debug chats and are **not yet implemented or verified**.

Future work sessions should use this folder as the repository-owned list of outstanding development tasks.

## Task format

Each task is a Markdown file with:

- `Status: OPEN | IN PROGRESS | DONE`
- a short purpose/context section;
- the agreed requirement;
- verification/acceptance criteria;
- optional image or other visual references.

If a chat includes screenshots or mockups that should be retained, store them under `docs/backlog/assets/<task-slug>/` and reference them from the task file.

When starting work on a task, first verify the current implementation. Do not assume the task is still missing simply because it is in the backlog. If already satisfied, document the verification and mark it `DONE`.

## Open tasks

- [Recording Results Page v1](recording-results-page-v1.md) — three-card results hierarchy (recording summary, session analysis, movements list); phased from UI shell with current segmentation data through thumbnail generation, measurement population, baseline comparison, and movement detail; explicitly separates UI contract from backend availability.

- [Queue Manager Tray v1](queue-manager-tray-v1.md) — app-shell presentation of recent recording jobs with phase-aware rows, collapse/hide behavior, overflow, and result acknowledgement; implementation is local and physical-device validation remains pending.

- [Segmenter Integration v1](segmenter-integration-v1-requirements.md) — connect persisted landmark processing to retrospective segmentation, durable movement records, and recording-level segment inspection; implemented in local source; automated build and physical-device boundary validation remain pending. See the [validation plan](segmenter-integration-v1-validation.md) and [starter prompt](../prompts/segmenter-integration-v1-start-prompt.md).

- [Shared Camera Capture backend](shared-camera-capture-backend.md) — implemented locally for shared video/photo capture; physical-device acceptance pending.

- [Record & Analyze assisted capture](record-and-analyze-assisted-capture.md) — all three bundled slices implemented locally (capture, durable queue, Performance browser); physical acceptance pending.

- [Karate training database foundation](karate-training-database.md) — Room foundation and pipeline integration in local source; physical-device acceptance remains pending.

- [Osu meaning illustration assets](osu-meaning-illustrations.md) — replace the completed lesson’s semantic icon placeholders with approved recurring-character artwork.
- [Body conditioning / knuckle push-up learning path](body-conditioning-knuckle-pushup-learning-path.md) — later progression that separates knuckle conditioning from the basic strength learning path.

## Completed tasks

- [Permission status UI verification and fix](permission-status-ui.md) — completed 2026-09-13; Android-backed status, consistent check/X visuals, settings fallback, and lifecycle refresh are implemented and verified.
