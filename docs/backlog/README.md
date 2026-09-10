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

- [Permission status UI verification and fix](permission-status-ui.md) — verify Android camera/microphone permission state, status visuals, settings flow, and lifecycle refresh.
- [Body conditioning / knuckle push-up learning path](body-conditioning-knuckle-pushup-learning-path.md) — later progression that separates knuckle conditioning from the basic strength learning path.
