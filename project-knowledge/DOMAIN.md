# Domain

## Governing boundary

**Status: DECIDED**

Karate and movement-analysis knowledge belongs in the shared analytical platform. Product experience belongs in applications.

The analytical platform answers what evidence exists, where movement occurred, how it can be described, and what happened during a karate movement. Applications decide how results are presented and how users move through product workflows. Coaching may decide what to train next, but it consumes analysis rather than reimplementing it.

## Confirmed responsibility areas

These are intended domain responsibilities, not claims about current modules or packages:

- **Capture and evidence** — raw recording, timing, landmarks, confidence, camera/view information, metadata, and provenance.
- **Movement detection and segmentation** — meaningful movement windows, boundaries, event frames, stable periods, selected body parts, and segmentation confidence.
- **Shared movement geometry** — canonical coordinate systems, body-relative measures, distances, angles, vectors, velocity, trajectories, normalization, and body reference models.
- **Technique analysis** — technique-specific interpretation of segmented evidence using shared geometry and definitions.
- **Analysis presentation contract** — measurements, units, confidence, evidence references, overlays, graph data, explanation metadata, versions, and abstention reasons.
- **Training history** — prior analytical results, trends, averages, technique/side history, and analyzer-version provenance.
- **Coaching** — later decisions about useful training based on analysis, history, learning, and user context.
- **Learning and curriculum** — later curriculum structure and progression; it may consume authoritative measurements but does not own their calculation.

## Core distinctions

- Analytical result is not presentation.
- Historical comparison is not coaching recommendation.
- Learning progression is not today's training recommendation.
- Evidence is not interpretation.
- Existing code structure is not automatically the intended domain model.

## Impact-analysis body scale

**Status: DECIDED — 2026-09-24**

The spatial deadband used by terminal-event analysis consumes explicit body-scale evidence expressed
in the same aspect-correct source-frame-height units as weapon travel. The upstream shared body-scale
provider owns how that evidence is derived and versions its meaning; `ImpactAnalyzer` does not derive
or approximate it.

Physical stature (for example centimeters), current torso length, source-frame height, and observed
image-plane body height are distinct concepts and are not interchangeable. Until an authoritative
provider is available, missing compatible scale evidence produces `BODY_SCALE_UNAVAILABLE`.

## Unknowns

Exact bounded-context names, module boundaries, aggregates, persistence ownership, APIs, and integration mechanisms remain `UNKNOWN` until a task requires them to be discovered or decided.

The production provider for aspect-correct image-plane body-scale evidence remains `UNKNOWN`.
