---
name: architecture-escalation
description: Escalate a material architecture, domain, ownership, terminology, or invariant decision into a compact Architect request. Use only after focused evidence and current project knowledge cannot safely resolve the issue; do not use for investigation or routine engineering.
---

# Architecture Escalation

Escalate decisions, not investigation. Prepare a small, high-quality request for an Architect pass, then return its decision to normal engineering. Do not send raw repository context, investigation transcripts, large diffs, logs, or speculative discussion across the Architect boundary.

## Escalation gate

Escalate only when the current task exposes a material unresolved question about:

- architecture or analytical ownership;
- domain meaning, terminology, or conceptual relationships;
- responsibility boundaries or dependency direction;
- invariants or the authoritative implementation of a concept;
- conflict between decided architecture and observed implementation; or
- a consequential new abstraction or architectural mechanism.

Do not escalate code location, ordinary implementation choices, normal API use, compiler or routine test failures, mechanical refactoring, task complexity by itself, or questions current project knowledge already answers.

## Apply Ponytail first

Before escalating a proposed mechanism, use `ponytail` to test whether existing architecture or concepts already express the current requirement. Reject complexity justified only by a hypothetical future Karate Coach need. If the present architecture handles the requirement cleanly, continue engineering without escalation.

## Discover before escalating

Use `project-discovery` to establish only the repository facts needed for the decision. Load only relevant `project-knowledge/` files. Classify important claims using the repository states `DECIDED`, `OBSERVED`, `DOCUMENTED`, `INFERRED`, and `UNKNOWN`; do not silently reconcile conflicts or promote implementation into intent.

The Architect does not perform repository archaeology. If a missing repository fact blocks the decision, route it back through focused discovery.

## Compress the handoff

Apply these ADHD/Caveman-inspired rules:

- **Delta only:** include only what is new to the Architect and material to this decision.
- **Result first:** state the conflict or decision before its supporting evidence.
- **Bounded evidence:** prefer a few strong facts; normally include at most five observations.
- **Lossless islands:** preserve exact identifiers, names, signatures, schema fields, configuration values, invariant wording, and short syntax-sensitive excerpts.
- **Reference instead of reproduce:** prefer `BodyHeightModel.kt -> currentBodyReference()` over copying its implementation.
- **Raw evidence stays below the boundary:** keep searches, logs, full files, large diffs, and investigation transcripts available to engineering, but do not send them automatically.

Remove any fact that cannot change the decision.

## Evidence Packet

Default to an L0 packet of approximately 300–600 words. Keep any packet below 1,000 words unless the Architect requests a focused expansion.

```text
ARCHITECTURE DECISION REQUEST

TASK
What current work exposed this decision?

ESCALATION REASON
Why this is architectural rather than ordinary implementation.

ESTABLISHED KNOWLEDGE
Only directly relevant DECIDED rules or terminology.

OBSERVED FACTS
Maximum five important repository observations.

CONFLICT / UNCERTAINTY
What cannot safely be resolved by implementation alone?

DECISION NEEDED
One primary decision; maximum three when inseparable.

OPTIONS
Viable alternatives only.

WORKER / ENGINEER RECOMMENDATION
Non-authoritative recommendation and short rationale.

EVIDENCE
Focused paths, symbols, contracts, schemas, or tests.

CONFIDENCE
HIGH / MEDIUM / LOW
```

Do not include full reasoning transcripts.

## Progressive evidence

- **L0 — Decision Packet:** the default 300–600 word handoff, normally without source code.
- **L1 — Focused Evidence:** only when exact implementation materially affects the decision; at most about 1,000 words and 50 lines of code total, with no excerpt over 25 lines.
- **L2 — Raw Investigation:** never send automatically to the Architect.

When more evidence is needed, the Architect asks one precise question. Engineering uses `project-discovery`, then returns the answer as a new L0 or L1 packet. Default to one evidence round; a second should be exceptional.

Use this request shape:

```text
FOCUSED EVIDENCE REQUEST

BLOCKING QUESTION
Why it matters to the decision.

EVIDENCE NEEDED
The smallest information required.

DO NOT INVESTIGATE
Anything explicitly outside scope.
```

## Architect reasoning

Choose only the reasoning needed for the decision:

- Use `ddd-architect` for meaning, ownership, terminology, boundaries, conceptual relationships, or invariants.
- Apply **Architectural Simplicity** before accepting new machinery: test whether fewer concepts within the existing architecture satisfy the requirement.
- Apply **Steelman Alternatives** when multiple options are genuinely viable: present the strongest reasonable case for each, not a preferred option against weak decoys.
- Apply **Critical Challenge** to substantial proposals: probe hidden assumptions, duplicated concepts, competing ownership, speculative future-proofing, boundary leakage, failure modes, and unnecessary complexity. Do not manufacture objections ceremonially.

The Architect decides meaning and constraints; it does not implement the solution.

## Route unresolved input

Route missing repository facts back to `project-discovery`; do not ask the user for discoverable facts.

If one small user-owned ambiguity about intent, meaning, priority, or a genuine tradeoff blocks the decision, use `adaptive-interviewing` and ask the minimum bounded question set.

If several user-owned decisions depend on earlier answers and form a real decision tree, use `grill-me` to traverse them one question at a time. Do not substitute a large questionnaire. If `grill-me` is unavailable, report the missing capability instead of pretending to have used it or collapsing the tree into guesses.

After user input, finish the Architect decision and distinguish the user's `DECIDED` intent from any `INFERRED` interpretation.

## Architect Decision Contract

Return a compact contract to engineering, not the Architect's full reasoning:

```text
ARCHITECT DECISION

DECISION
The selected architectural interpretation or constraint.

MEANING
What relevant concepts mean, when needed.

OWNER
Who owns the behavior or knowledge, when relevant.

INVARIANTS
Rules that must remain true.

RATIONALE
Why this fits the current product, domain, and architecture.

REJECTED ALTERNATIVES
Only rejections useful to preserve.

IMPLEMENTATION CONSTRAINTS
Boundaries engineering must respect without prescribing unnecessary code structure.

KNOWLEDGE UPDATE
What durable project knowledge should change.

CONFIDENCE
HIGH / MEDIUM / LOW
```

Engineering owns code, tests, debugging, repository changes, and mechanical documentation. Continue implementation from the contract unless the user's task ends at the decision.

## Knowledge sync

Every expensive decision should make the same escalation less likely to recur. After the decision or its implementation, update only the authoritative owner in `project-knowledge/`:

- `PRODUCT.md` for product intent or priority;
- `DOMAIN.md` for meaning, ownership, boundaries, and invariants;
- `ARCHITECTURE.md` for dependency or structural direction;
- `TERMINOLOGY.md` for canonical language;
- `DECISIONS.md` for durable choices and useful rejected alternatives; or
- `PROJECT_STRUCTURE.md` for verified implementation structure.

Do not duplicate the same outcome across files. If implementation contradicts the decision, record the conflict rather than rewriting the decision to match code.

## Governing principles

- Escalate decisions, not investigation.
- Architect context is scarce; delta beats history.
- Focused evidence beats raw context.
- Exact evidence stays exact where precision matters.
- Steelman viable alternatives before rejecting them.
- Challenge architectural complexity before accepting it.
- Ask the user about intent, not discoverable facts.
- The Architect owns meaning; engineering owns implementation.
- Durable decisions belong in project knowledge.
