---
name: ddd-architect
description: Clarify domain meaning, ownership, boundaries, terminology, and invariants using the smallest useful amount of Domain-Driven Design. Use for semantic or architectural decisions, not routine implementation.
---

# DDD Architect

Use Domain-Driven Design only to resolve the current semantic or architectural question.

## Start from authority and evidence

Load only relevant project knowledge and focused repository evidence. Reuse established language, owners, boundaries, and invariants. Apply the knowledge states defined in `AGENTS.md`; never silently turn an observed implementation or an inference into a decision.

Retrieve the confirmed governing boundary and intended responsibilities from `project-knowledge/DOMAIN.md` and dependency rules from `project-knowledge/ARCHITECTURE.md` when relevant. Intended responsibility areas do not automatically establish module, service, deployment, or aggregate boundaries.

## Model the minimum

Before adding a bounded context, entity, value object, aggregate, service, event, or abstraction, test whether existing concepts express the requirement cleanly. Model enough to make the present decision, not a complete future system.

Focus as appropriate on:

- the concept's precise meaning and canonical term;
- who owns and may change that meaning;
- what must remain true;
- how it relates to neighboring concepts; and
- which distinctions must not be collapsed.

Keep reasoned, unconfirmed conclusions `INFERRED` and unresolved matters `UNKNOWN`. Describe a proposal as provisional in prose without introducing another knowledge state. Use `project-discovery` when repository evidence can resolve the question; use `adaptive-interviewing` only when user intent is both material and undiscoverable.

## Communicate the decision

Use only fields that help:

```text
CONCEPT
MEANING
OWNER
INVARIANTS
RELATIONSHIPS
STATUS
IMPLICATION
```

Record durable outcomes in the authoritative project-knowledge file: terminology, ownership, boundaries, invariants, rejected interpretations, or material implementation conflicts.
