---
name: project-discovery
description: Discover task-relevant repository structure, implementations, dependencies, and runtime facts. Use when current facts are unknown or need verification; do not use for broad archaeology without a concrete question.
---

# Project Discovery

Establish the smallest reliable evidence set needed for the current task.

## Discover in scope

Start with the task's named feature, symbol, path, behavior, or failure. Search outward only as dependencies or contradictions require. Use source and configuration to establish implementation facts, tests to establish encoded expectations, and executed checks to establish observed behavior. Reading a test does not establish that it passes. Use current decisions to establish intended direction.

Apply the five knowledge states defined in `AGENTS.md`. Attach a recheckable source to non-obvious claims and keep a claim's scope within what that evidence establishes.

Do not promote implementation to intended architecture merely because it exists. When evidence conflicts with a decision, report the conflict instead of silently choosing one.

## Produce useful evidence

Answer the concrete discovery question with paths, symbols, dependency relationships, commands, or test evidence that another worker can recheck. Avoid exhaustive inventories and long narrative summaries.

If discovery reveals a material semantic decision rather than a repository fact, stop treating it as discovery. Use `ddd-architect` for domain meaning or `adaptive-interviewing` when user intent is required.

## Harvest durable facts

Update `project-knowledge/PROJECT_STRUCTURE.md` only for durable facts likely to help future tasks. Put product, domain, architecture, terminology, or decision knowledge in its owning file. Do not record transient debugging details, file listings, or guesses.
