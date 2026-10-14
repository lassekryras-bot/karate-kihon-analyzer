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

## Four-limb evidence and starting-side interpretation

**Status: DECIDED — 2026-10-04 user handover**

The Four-Limb Motion Characterizer describes elbow/knee articulation in an already-segmented logical movement. It does not classify techniques, select the striking weapon, or choose impact. Known-activity interpretation downstream resolves the observed starting side, retaining ambiguity when the evidence is inadequate. Expected alternation validates observations; it must not manufacture an observed side. QoM remains authoritative for logical movement boundaries.

Source: supplied [requirement](../docs/backlog/four-limb-motion-characterizer-requirement-v1.md) and implementation handover. Implementation order is characterization, starting-side interpretation, then selected-weapon/impact integration. Calibration is provisional; source-code existence does not establish acceptance.

### Straight-punch side under partial visibility

**Status: DECIDED — 2026-10-14 architectural review**

Side-view occlusion of the opposite arm is a normal operating condition. Under an explicit straight-punch plan, one anatomical arm's quality-gated projected elbow-angle opening episode may resolve the observed side when the opposite arm is unavailable. Unavailable evidence is unknown, not contradictory, and must not be fabricated. Resolution requires one unique supported anatomical side over the complete authoritative QoM interval; qualifying opening evidence for both arms, or usable but complex unsupported opposite-arm evidence, remains ambiguous. Extension followed by return may support side without making whole-window net direction authoritative.

The result describes observed side from provisional 2D projected evidence. It does not validate the technique, physical reversal count, capture view, weapon, or impact. Expected alternation never supplies missing side. Confidence gates, hysteresis, and QoM bounds remain independently owned and are not adjusted to force a resolution.

## Remaining unknowns

Exact bounded-context names, module boundaries, aggregates, persistence ownership, APIs, and integration mechanisms remain `UNKNOWN` until a task requires them to be discovered or decided.

### Confirmed image body-height calibration (2026-10-04)

- **DECIDED (user):** Impact scale comes from an actual upright, fully visible frame at the performance position. The user confirms head top and floor at the feet. Facial landmarks and torso spans are not substitutes for full image body height.
- **DECIDED:** Freeze vertical image height through movement. Require explicit unchanged camera/zoom/subject-depth confirmation; recalibrate or invalidate when these conditions change. No automatic change detector is promised.
- **DECIDED:** Percentage-of-body-height thresholds use the image reference. Existing profile stature is not sufficient to derive image scale and does not need another entry field.
- **OBSERVED:** `ImageBodyScaleCalibration` binds endpoints, frame index/time, recording/track/canonical geometry, validity interval, confirmation and method version. The provider rejects identity/interval mismatches and returns aspect-correct source-frame-height units. App calibration/revocation events are append-only; analysis retains its calibration snapshot and source ID.
- **OBSERVED:** New recording analyses use explicit capture-plan identifiers, never display-label inference. Missing plans retain generic limb evidence and abstain from activity/weapon/target interpretation. Historical results remain stored. Manual calibration currently requires Android 9+ and unchanged conditions across the entire recording.

### Impact camera-view approval

**Status: DECIDED precondition; supported mapping UNKNOWN — 2026-10-04 architectural review**

Impact analysis requires explicit evidence that the known activity and capture view are supported. An observed view estimate, an operator-selected marker, and valid body-scale calibration do not establish measurement suitability. Until an activity/view approval mapping is explicitly accepted, production retains generic limb and observed-side evidence but abstains from impact and impact-dependent target output with `UNSUPPORTED_ANALYSIS_PROFILE`. Historical results remain stored.

### Post-capture activity declaration

**Status: DECIDED — 2026-10-04 architectural review**

For a saved recording with no activity-plan event, an operator may explicitly declare one supported activity for the entire recording and request a new analysis. This is product-owned interpretation context, not observed technique classification. The declaration is append-only, uses the existing versioned `analysis_activity_plan` event with distinct `operator_post_capture_confirmation` provenance, and never comes from a display label. Malformed, unsupported or duplicate existing plan events are conflicts rather than missing context. Old runs and results remain immutable; retry reuses the same declaration. The declaration supplies neither observed side, body scale nor approved camera view.
