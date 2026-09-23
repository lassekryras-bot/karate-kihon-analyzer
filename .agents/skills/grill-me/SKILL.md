---
name: grill-me
description: Conduct a deliberate, dependency-first interview to resolve multiple related user-owned design decisions one question at a time. Use when explicitly requested or when architecture-escalation identifies a material decision tree; do not use for discoverable repository facts or one small clarification.
---

# Grill Me

Build shared understanding by traversing the relevant design tree one dependency at a time. Explore before asking, recommend without assuming, and preserve durable decisions.

## Use the right interview mode

Use `grill-me` when several material decisions about product intent, domain meaning, requirements, priorities, tradeoffs, or developer preferences depend on one another. It may be explicitly requested or selected by `architecture-escalation` after the Architect finds a user-owned decision tree.

Use `adaptive-interviewing` instead when one small ambiguity blocks the immediate task. Do not use either skill for facts that focused repository discovery can establish.

Keep the interview deep enough to resolve the current design, but bounded to its material branches. Do not expand it into a general product interrogation.

## Prepare the decision tree

Before asking:

1. state the goal and the decision boundary;
2. retrieve only relevant `project-knowledge/`;
3. use `project-discovery` for existing types, behavior, configuration, contracts, conventions, and implementation facts;
4. identify the unresolved user-owned decisions and their dependencies; and
5. choose the highest upstream decision whose answer changes later branches.

Map the tree internally. Do not dump the whole questionnaire on the user.

Use `ddd-architect` when the tree depends on domain meaning, ownership, terminology, boundaries, conceptual relationships, or invariants. Apply `ponytail` to remove speculative branches and choices that add machinery without a current requirement.

## Interview loop

For each unresolved material branch:

1. Recheck whether repository evidence or current project knowledge can answer it. Discover the answer instead of asking when possible.
2. Ask exactly one question.
3. Explain briefly what the choice controls when that is not obvious.
4. Offer concise, mutually exclusive options phrased as answers the user could naturally choose.
5. Put the recommended option first and mark it `(Recommended)`.
6. Give each option its material consequence or tradeoff without steering through hidden costs.
7. Allow a free-form answer or correction of the premise.
8. Stop and wait for the answer before asking the next question.
9. Treat the answer as a new constraint, prune invalid branches, and select the next highest upstream unresolved decision.

Prefer two or three real options. Avoid trivial yes/no questions when actionable alternatives communicate the decision more clearly.

The recommendation must reflect the best available combination of:

- explicit user goals and earlier answers;
- `DECIDED` project knowledge;
- focused repository evidence;
- current product and architectural direction; and
- relevant engineering practice.

A recommendation is advice, not consent. Never continue as though the user selected it.

## Question interface

Prefer a structured question UI when one is available. The workflow must not depend on any vendor-specific question tool.

When using normal chat, ask one concise question, list the options with `(Recommended)` first, and wait. Do not combine dependent questions in one message or hide a second question in explanatory prose.

## Dependency-first ordering

Resolve foundational choices before their consequences. A typical order is:

```text
purpose / required outcome
        -> invariants / non-negotiables
        -> meaning / ownership / boundary
        -> lifecycle / data model
        -> contract / flow
        -> failure semantics
        -> presentation / peripheral choices
```

Adapt this ordering to the actual tree. Do not ask downstream questions whose options depend on an unresolved upstream answer.

## Knowledge discipline

Distinguish what the user directly establishes as `DECIDED` from the Architect's or engineer's `INFERRED` interpretation. Repository observations remain `OBSERVED`; prior documents remain `DOCUMENTED` until revalidated.

If an answer conflicts with existing project knowledge, surface the conflict. Do not silently rewrite either side. If a new answer reveals that repository evidence is missing, pause the interview branch and route the factual question to `project-discovery`.

## Stop conditions

Stop when:

- every user-owned decision required for the current design is resolved;
- remaining unknowns can safely remain unknown;
- remaining choices are ordinary implementation details owned by engineering; or
- the user asks to stop or defer the interview.

Do not continue asking questions merely to exhaust the original tree. Earlier answers may make later branches irrelevant.

## Resolution

Return a compact result:

```text
GRILL-ME RESOLUTION

DECIDED CONSTRAINTS
Direct user-owned decisions established by the interview.

ARCHITECT / ENGINEER INTERPRETATION
Conclusions derived from those decisions, clearly marked as interpretation.

STILL UNKNOWN
Only unresolved items that may materially matter later.

NEXT
The decision, plan, or engineering work that can now proceed.
```

When invoked from `architecture-escalation`, return this resolution to the Architect so it can complete the Architect Decision Contract. Do not replace the architectural decision with the interview transcript.

## Knowledge sync

Persist durable outcomes in the single appropriate owner under `project-knowledge/`: product intent in `PRODUCT.md`, domain meaning or invariants in `DOMAIN.md`, dependency direction in `ARCHITECTURE.md`, canonical language in `TERMINOLOGY.md`, and durable choices in `DECISIONS.md`.

Do not store the conversation, discarded incidental options, or personal detail that future work does not need. Record direct user decisions separately from derived interpretations.

## Governing principles

- Explore before asking.
- One question at a time.
- Resolve dependencies before consequences.
- Recommend clearly; never assume acceptance.
- Ask for user judgment, not repository facts.
- Prune the tree as answers make branches irrelevant.
- Preserve durable decisions, not interview transcripts.
