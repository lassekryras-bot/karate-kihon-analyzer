---
name: adaptive-interviewing
description: Resolve material product, domain, or architecture uncertainty through a small, evidence-aware user interview. Use only when the answer cannot be reliably discovered and different answers would change the work.
---

# Adaptive Interviewing

Ask for judgment, not discoverable facts.

## Decide whether to ask

First retrieve relevant project knowledge and focused repository evidence. Do not ask the user where code lives, how a current function behaves, or what configuration says when those facts can be inspected.

Ask when all are true:

- the uncertainty concerns intent, meaning, priority, ownership, or an architectural tradeoff;
- plausible answers would materially change the result; and
- available evidence cannot resolve it reliably.

If the uncertainty is harmless or reversible, state a narrow assumption and continue. Do not turn optional context into a blocker.

## Shape the interview

Ask the smallest number of related questions needed for the next decision. Explain the concrete consequence of each choice in plain language. Prefer bounded alternatives when the real tradeoff is known, while leaving room for the user to correct a false premise.

Adapt after each answer: stop when the decision is sufficiently clear, follow up only on remaining material ambiguity, and do not repeat answered questions.

Do not lead the user toward a preferred architecture by hiding costs or treating an inference as settled. Distinguish confirmed answers from your interpretation.

## Harvest the answer

Resume the task after the decision. Record durable knowledge in its authoritative file. Use `DECIDED` only for intent or a choice explicitly established by the user; keep your interpretations `INFERRED` and unresolved questions `UNKNOWN`. A report about existing behavior is evidence to verify, not automatically a design decision. Include a concise source reference for durable answers. Do not preserve conversational detail or personal data that is unnecessary to the decision.
