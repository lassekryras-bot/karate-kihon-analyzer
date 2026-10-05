# Movement Debug Report and Activity Override

**Status:** OPEN  
**Source:** Movement Detail debug review, 2026-10-05  
**Area:** Android app / Movement Detail / Analysis Debug

## Goal

Make movement-analysis failures inspectable without screenshots and allow a developer to test whether downstream analysis works when the activity prerequisite is missing.

The current observed case reaches useful lower-level evidence and body/target geometry, but the straight-punch analysis reports `ACTIVITY_PLAN_UNAVAILABLE`, leaves the active arm unknown, does not evaluate terminal analysis, and consequently exposes unavailable key results.

## Requirements

### 1. Debug-only activity override

In debug mode, provide an Activity override on Movement Detail.

- Show the original/detected activity state.
- Allow the developer to select an activity, initially including at least `STRAIGHT_PUNCH`, or clear the override.
- The override supplies only the missing activity prerequisite and then reruns the normal production downstream analysis path.
- Do not manufacture active-arm, impact, terminal, target, or measurement results.
- Do not create a second debug analyzer or duplicate authoritative analyzer mathematics.
- Production/non-debug behavior must remain unchanged.
- Make provenance explicit so an overridden run cannot be mistaken for normally detected evidence.

At minimum expose:

```text
Activity original: <value/unavailable>
Activity override: <none or value>
Activity effective: <value/unavailable>
Activity source: <normal source or DEBUG_OVERRIDE>
```

The main diagnostic question is: **If activity detection/planning had succeeded, would the existing downstream analyzer have produced valid evidence and measurements?**

### 2. Structured Movement Debug Report

Introduce one structured `MovementDebugReport` (name may follow existing project conventions) as the source for diagnostic output. The report must be built from diagnostic/domain state, not by scraping rendered Compose text.

Include available information such as:

- movement ID and logical/retained/playback bounds;
- analysis/analyzer result and abstention reason;
- analyzer/segmenter/model versions and provenance;
- original, override, effective activity and activity source;
- active-arm/selected-weapon state;
- limb-motion evidence for each limb;
- terminal/impact evidence;
- evidence availability/coverage and relevant abstention reasons;
- BodyHeightModel evaluation/window, anchors and geometry;
- target-height/target-geometry calculations;
- measurements and key results;
- relevant evidence/sample timestamps.

Unavailable fields should remain explicitly unavailable rather than being omitted in a way that hides why analysis stopped.

### 3. Clipboard actions

In debug mode:

- Each major diagnostic section/card has a small Copy action.
- Copy uses structured plain text generated from the corresponding diagnostic model/report section.
- Provide a **Copy debug report** action for the complete report.
- Clipboard output must preserve exact enum names, versions, timestamps, numeric values, provenance and abstention reasons where available.

### 4. Markdown report export

Provide an **Export debug report** action that creates a human-readable Markdown (`.md`) snapshot of the same `MovementDebugReport`.

The Markdown should be suitable for attaching to a GitHub/Jira issue or providing to ChatGPT without requiring a screenshot.

Suggested sections:

```text
Movement
Analysis
Debug Overrides
Limb Motion
Terminal / Impact Evidence
Body Height Model
Target Geometry
Measurements / Key Results
Provenance
```

Clipboard and Markdown export are presentation adapters over the same structured report; they must not independently recalculate analysis values.

## Provenance rule

A debug override must preserve both original and effective state. For example, an unavailable activity overridden to `STRAIGHT_PUNCH` must not later appear as if the normal pipeline detected the activity.

Reports created after an override must clearly state `DEBUG_OVERRIDE` and retain the original state/reason.

## Scope

This task is developer tooling. It does not change technique scoring semantics, activity detection/planning, limb characterization, terminal detection, BodyHeightModel mathematics, target-height mathematics, or production analysis policy.

Start with Activity as the only analyzer prerequisite override. Additional overrides (for example active arm or impact frame) require separate justification rather than turning this into an unrestricted generic override system.

## Acceptance criteria

- A developer can set/clear a straight-punch Activity override from Movement Detail in debug mode.
- Applying the override reruns the existing downstream analyzer path and does not inject fabricated downstream evidence.
- Normal builds/normal analysis are unaffected by the override feature.
- Original/effective activity and `DEBUG_OVERRIDE` provenance are visible and included in exported diagnostics.
- Each major debug section can be copied independently.
- The complete structured debug report can be copied.
- The same report can be exported as a Markdown file.
- Clipboard and Markdown representations originate from the same structured diagnostic report rather than Compose-rendered strings.
- Missing/unavailable evidence and abstention reasons remain explicit.
- Tests cover report rendering, override provenance, clearing an override, and isolation from non-debug production behavior.

## Implementation note

Before implementing, inspect the current Movement Detail analysis/debug models and reuse existing evidence/provenance types wherever possible. The report is an inspection/export representation of authoritative state, not a new analysis engine.
