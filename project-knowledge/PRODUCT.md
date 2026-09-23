# Product

## Current priority

**Status: DECIDED**

The current development phase is the shared Karate Analysis Platform backend together with the free/lightweight Karate Kihon Analyzer.

Kihon Analyzer is a real product, not a disposable prototype. It is the first production consumer and proving ground for the shared analytical platform. Its core value is to let a user record karate, receive evidence-backed analysis, understand performance, and compare it with personal history.

Karate Coach is a later, cleaner, richer, paid product. It may add coaching, learning progression, daily guidance, exercise selection, and longer-term training planning while reusing the same authoritative analytical platform.

## Product sequence

```text
NOW
Shared Karate Analysis Platform + Karate Kihon Analyzer
    -> prove analysis, evidence, explanation, recording, and comparison

LATER
Proven shared platform + Karate Coach
    -> add coaching, learning, progression, and premium experience
```

## Decision rule

When choosing between solving a concrete backend or Kihon Analyzer need and generalizing for a hypothetical Coach need, solve the current need cleanly. Preserve a reasonable extension path without implementing speculative machinery.

## Unknowns

Current feature completeness, target platforms, commercial details, and repository implementation state are `UNKNOWN` until established by focused discovery or a new decision.
