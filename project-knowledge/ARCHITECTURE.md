# Architecture

## Intended information flow

**Status: DECIDED**

```text
Raw recording and pose evidence
        -> canonical evidence model
        -> movement detection and segmentation
        -> shared geometry and body models
        -> technique analyzers
        -> analysis presentation contract
        -> history and comparison
        -> product-specific behavior and UI
```

The arrows show conceptual information flow, not code imports, a mandatory execution sequence, or a required chain of modules. Consumers depend on the shared capabilities and contracts they use. Shared geometry must not depend on an application. Technique analyzers must not depend on result screens, coaching, or curriculum. Coaching and applications may consume analyzer results.

These responsibility areas do not prescribe separate services or deployments. The term shared backend does not establish a remote server requirement; runtime topology remains unknown.

## Authoritative analytical core

**Status: DECIDED**

Each analytical concept should have one authoritative implementation. Products may display or use results differently, but should not fork formulas, segmentation logic, coordinate systems, impact detection, or other analytical truth.

## Analytical principles

The shared platform should be:

- **Deterministic** — the same evidence and analyzer version produce the same result.
- **Evidence based** — results remain traceable to their source frames, landmarks, and calculations.
- **Versioned** — analyzer and measurement versions keep historical results interpretable.
- **Explainable** — important results can expose supporting frames, overlays, plots, or values.
- **Reusable** — genuinely shared mathematics lives below technique-specific analyzers.
- **Conservative** — insufficient evidence can produce abstention instead of invented certainty.

## Current implementation

**Status: UNKNOWN**

No current package, service, database, API, UI, or runtime topology is asserted here. Record verified structural facts in `PROJECT_STRUCTURE.md`; record material gaps between evidence and intended direction here without silently redefining the intent.
