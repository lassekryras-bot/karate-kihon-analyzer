# AGENTS.md

## Purpose

This repository uses Codex Desktop as its primary development environment.

Work from the current task, discover the repository as needed, and improve durable project knowledge through real work. This file is the repository-level operating constitution; keep detailed procedures in focused skills and durable facts or decisions in `project-knowledge/`.

## Current product priority

Build these together first:

1. the shared Karate Analysis Platform backend; and
2. the free/lightweight Karate Kihon Analyzer application.

Kihon Analyzer is a real product and the first production consumer and proving ground for the shared platform. Karate Coach is a later, richer, paid product built on the proven platform.

Solve current backend and Kihon Analyzer requirements cleanly. Do not add abstractions, services, configuration, extension points, or other machinery solely for hypothetical future Coach needs.

## Architectural direction

Karate and movement-analysis knowledge belongs in the shared analytical platform. Product experience belongs in applications.

Maintain one authoritative implementation of analytical concepts. Applications may present and use results differently, but must not create competing movement-analysis calculations.

Keep code dependencies directed from consumers toward the reusable capabilities they use. Applications consume shared analytical results; the analytical core must not depend on product UI, coaching, learning, or workflow logic. Information flows toward products, while code dependencies point toward shared capabilities.

Treat this as intended architecture. Verify the repository before assuming the current implementation follows it.

## Knowledge policy

This repository is starting a fresh knowledge layer. Old documentation and existing code are evidence, not automatic architectural authority. Do not import old assumptions merely because they are documented or implemented.

Use these states when they clarify a claim:

- `OBSERVED` — confirmed from current repository or runtime evidence.
- `DOCUMENTED` — stated in existing material but not revalidated.
- `DECIDED` — explicitly established as current intent.
- `INFERRED` — a reasoned interpretation, not authority.
- `UNKNOWN` — not established.

Implementation existence does not make a design `DECIDED`. Surface material conflicts instead of silently reconciling them.

Use `project-knowledge/` as the durable knowledge layer. Load only what is relevant to the current task. Update the appropriate file when work establishes durable product, domain, architecture, terminology, decision, or structural knowledge.

## Working approach

Discover repository facts instead of asking the user for information that can be reliably established from the repository. Keep discovery proportional to the task.

Use repository skills when their activation criteria match:

- `project-discovery` for focused repository facts and implementation relationships;
- `ddd-architect` for domain meaning, ownership, boundaries, terminology, and invariants;
- `ponytail` for simplicity and avoidance of unnecessary machinery; and
- `adaptive-interviewing` for material intent or semantic uncertainty that evidence cannot resolve.

When engineering exposes a material architecture, domain, ownership, terminology, or invariant question that cannot be resolved from focused repository evidence and current project knowledge, use `architecture-escalation` rather than guessing.

Prefer one stable term for one domain concept. Before adding a concept or abstraction, check whether the current model or an existing capability can express the requirement cleanly.

Ask only when unresolved intent, semantics, product direction, or architectural meaning materially affects the task. Batch closely related questions and record durable answers.

Validate work in proportion to its risk. Do not claim checks that were not performed. Update the authoritative owner of changed behavior, contracts, architecture, or durable knowledge instead of duplicating the same rule.

## Governing principles

- Discover before asking.
- Retrieve before restating.
- Meaning before machinery.
- Existing capability before new abstraction.
- Unknown is better than invented.
- Current implementation is evidence, not automatic authority.
- Build the shared backend and Kihon Analyzer first.
- Do not let future Coach requirements drive speculative complexity.
- Preserve one authoritative analytical core.
