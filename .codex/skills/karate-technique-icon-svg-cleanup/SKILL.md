---
name: karate-technique-icon-svg-cleanup
description: Clean, normalize, review, and prepare converted SVG karate technique/stance artwork for the Karate Kihon Analyzer icon family. Use after raster artwork has already been approved and converted to SVG. Preserve the approved pose; do not redesign karate technique or perform raster-to-vector conversion.
---

# Karate Technique Icon SVG Cleanup

This skill cleans SVGs that were converted from approved raster technique artwork.
It does not generate the raster art and does not decide karate technique correctness.

## Required context

Locate the `karate-kihon-analyzer` repository root, then read completely:

1. `AGENTS.md`
2. `docs/technique-icon-svg-cleanup-guide.md`

Inspect any existing approved technique icons that are supplied with the task and
use them as visual references for perceived scale, negative-space weight, and
naming. If no reference assets are available, follow the guide and report that
cross-icon visual comparison could not be performed.

## Input boundary

The expected input is an SVG produced by an external raster-to-vector conversion
step. Do not trace a raster image as part of this skill unless the user explicitly
changes the scope.

Treat the source pose as approved artwork. Preserve its pose, side, orientation,
limb geometry, and composition unless the user explicitly asks for a redesign.
If something appears anatomically or technically wrong for karate, flag it in the
result instead of silently fixing it.

## Cleanup contract

Normalize the asset to the repository technique-icon standard:

- `viewBox="0 0 1024 1024"`;
- transparent background;
- visible positive artwork in app coral red `#EF4444`;
- white reference areas converted to transparent/negative-space geometry where
  practical;
- no embedded raster `<image>` payloads;
- no gradients, filters, shadows, blur, blend effects, extra colors, text, or
  scenery;
- remove background rectangles, duplicate/hidden objects, empty groups, editor
  metadata, conversion leftovers, tiny stray islands, and accidental holes;
- prefer filled path geometry over cosmetic strokes;
- avoid masks and clipping paths when clean path geometry can represent the same
  result without changing appearance;
- simplify excessive path nodes only when silhouette and meaningful internal
  separators remain visibly unchanged;
- preserve gi lapels, belt, folds, limb separation, hands, feet, elbows, knees,
  and other negative-space details that make the pose readable;
- preserve aspect ratio; never stretch non-uniformly;
- center by perceived visual mass and keep safe margins around extended limbs;
- match the perceived figure scale of supplied reference icons rather than forcing
  identical raw bounds.

Do not reduce file size at the expense of visible fidelity.

## Technique-protection rule

Never "correct" karate during SVG cleanup. Specifically, do not silently alter:

- guard or hikite position;
- chamber or extension geometry;
- stance width or supporting-foot direction;
- striking surface;
- joint orientation;
- left/right side or mirroring.

If cleanup requires a material redraw, stop and report that the source should be
regenerated or manually redrawn.

## Naming

Use lowercase Android-safe names:

`karate_<japanese_technique>_<english_description>.svg`

Examples:

- `karate_hiza_geri_knee_kick.svg`
- `karate_mawashi_geri_roundhouse_kick.svg`
- `karate_oi_zuki_chudan_side_punch.svg`

Preserve established romanization in existing assets unless the user asks to
change terminology.

## Validation

Before completing the task:

1. Parse/render the cleaned SVG successfully.
2. Confirm 1024 x 1024 viewBox and transparent background.
3. Confirm positive artwork is `#EF4444` and no unintended colors remain.
4. Confirm no raster image payload is embedded.
5. Confirm no unwanted gradients, filters, shadows, hidden objects, or conversion
   debris remain.
6. Compare pose, orientation, negative-space separators, scale, and margins with
   the approved source/reference.
7. Render a preview PNG and inspect it at full size and reduced icon size.
8. Report any karate/anatomy concern separately; do not modify it without approval.

## Completion report

Return the cleaned SVG and preview PNG, list the final filename, and summarize only
material cleanup changes. Mention any unresolved pose/anatomy concern or any part
that could not be normalized safely. Do not claim technique correction unless the
user explicitly requested and approved that separate work.
