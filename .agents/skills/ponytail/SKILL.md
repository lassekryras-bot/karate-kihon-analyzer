---
name: ponytail
description: Simplify implementation and architecture choices by preferring existing capabilities and the smallest design that satisfies current requirements. Use when a change proposes new machinery, generalization, or future-proofing.
---

# Ponytail

Cut unnecessary machinery while preserving the intended architecture and the current requirement.

## Test the justification

State the concrete requirement and the evidence that it exists now. A hypothetical future Karate Coach need is not sufficient justification for complexity in the shared backend or Kihon Analyzer.

Before creating a new layer, service, abstraction, extension point, configuration system, dependency, or local framework, check whether the requirement can be met by:

1. an existing project capability;
2. a platform or standard-library capability;
3. an existing dependency; or
4. a smaller local implementation.

Reuse is valuable when concepts genuinely share meaning and behavior. Do not merge distinct concepts merely to reduce file or type counts. Do not remove evidence, versioning, explainability, abstention, or authoritative analytical ownership in the name of simplicity.

## Prefer a clean present design

Choose the least complex option that satisfies the current need, remains testable, and respects decided boundaries. Preserve an extension path when it is cheap and concrete; do not implement the extension in advance.

If added complexity is necessary, name the present constraint that requires it and the simpler options considered. If it is not necessary, remove or avoid it rather than documenting speculative value.

Record a durable decision only when the tradeoff is likely to recur or materially affects architecture.
