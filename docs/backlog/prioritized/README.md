# Prioritized Development Backlog

This folder contains the **ordered implementation queue** for backlog work that has been explicitly prioritized.

## Ordering rule

- Files are numbered in implementation order: `001-`, `002-`, and so on.
- The lowest numbered OPEN task is the next backlog task to consider.
- Prioritizing a task moves its authoritative task file here; do not keep a duplicate authoritative copy in the general backlog.
- Before implementation, verify current source and dependencies. Priority does not override architectural prerequisites or acceptance requirements.
- Completed work should be reflected in the main backlog index/validation records according to the repository's existing conventions.

## Queue

1. [Movement Debug Report and Activity Override](001-movement-debug-report-activity-override.md) — OPEN; debug-only activity prerequisite override plus structured clipboard diagnostics and Markdown debug-report export with explicit original/effective provenance.
