# Karate technique icon SVG cleanup guide

This guide defines the repository standard for cleaning SVGs that were converted
from approved raster technique artwork. The cleanup step is intentionally separate
from image generation and from karate-technique review.

## Workflow

1. Generate and visually approve a raster technique image.
2. Convert that raster image to SVG outside this cleanup task.
3. Give the converted SVG to the cleanup agent.
4. Clean and normalize the SVG without redesigning the pose.
5. Produce a preview PNG for visual review before the asset is adopted in the app.

The converted SVG is source material, not authority for karate correctness. If the
pose appears technically wrong, preserve it and flag the concern instead of silently
changing anatomy or technique.

## Canonical visual language

Technique icons are a coherent family of large app illustrations:

- faceless karate practitioner;
- bold flat single-color silhouette;
- app coral red `#EF4444` for all positive artwork;
- white/transparent negative-space cutouts for gi lapels, belt, folds, limb
  separation, hands, feet, and overlapping forms;
- clean stencil / relief-like appearance;
- no facial features, skin colors, secondary colors, gradients, shadows, texture,
  lighting, 3D treatment, text, labels, scenery, or decorative motion effects;
- simplified but believable anatomy and clearly readable karate clothing;
- side/profile view by default for technique illustrations unless the source asset
  intentionally establishes another view.

The cleanup agent must preserve approved pose geometry. It is not an illustration
agent and must not invent a more dramatic pose or reinterpret the technique.

## SVG output contract

Every cleaned icon should:

- use `viewBox="0 0 1024 1024"`;
- have a transparent background;
- use `#EF4444` for visible positive shapes;
- preserve negative-space cutouts as actual transparent/empty geometry where
  practical;
- contain no raster `<image>` payloads;
- contain no gradients, filters, drop shadows, blur, blend modes, or editor-only
  effects;
- avoid masks and clipping paths when equivalent clean path geometry is practical;
- avoid unnecessary strokes; prefer filled path geometry;
- remove hidden objects, duplicate paths, empty groups, background rectangles,
  metadata junk, and conversion leftovers;
- remove tiny stray islands and accidental holes that do not contribute to the
  visible design;
- simplify excessive nodes only when the visible silhouette and negative-space
  linework remain materially unchanged;
- keep hands, feet, elbows, knees, belt ends, and extended limbs clearly readable;
- keep comfortable safety margin around the complete figure;
- remain legible both as a large learning-path illustration and at reduced preview
  size.

Do not collapse meaningful negative-space separators merely to reduce node count.
Visual fidelity takes priority over file-size minimization.

## Composition normalization

Normalize the artwork inside the 1024 x 1024 viewBox without changing the pose:

- center by perceived visual mass rather than blindly centering the path bounds;
- keep approximately consistent practitioner scale across the icon family;
- preserve enough margin for long punches and kicks so extremities never touch the
  canvas edge;
- do not stretch non-uniformly;
- do not rotate or mirror unless explicitly requested;
- preserve the intended side/profile orientation.

When comparing against another approved technique icon, match perceived body scale
and negative-space weight rather than forcing identical raw bounding boxes.

## Color handling

The app technique-icon red is `#EF4444`. Normalize alternate reds, black trace
fills, or other conversion colors to this value unless the user explicitly requests
another app token.

White in the raster reference normally represents negative space, not white paint.
Prefer transparent cutouts/compound geometry so the icon works on supported app
surfaces without embedding a white background.

## What the cleanup agent must flag

Report, but do not silently repair, any issue that could change karate meaning:

- incorrect guard or hikite position;
- suspicious joint direction or limb anatomy;
- wrong striking surface;
- stance or supporting-foot geometry that appears technically wrong;
- accidental mirroring that changes the intended side;
- missing limb detail caused by raster conversion;
- source artwork that is too damaged to clean without redrawing.

Also flag if cleanup would require a material redraw instead of path normalization.

## Naming

Use lowercase Android-safe names:

`karate_<japanese_technique>_<english_description>.svg`

Examples:

- `karate_hiza_geri_knee_kick.svg`
- `karate_mawashi_geri_roundhouse_kick.svg`
- `karate_oi_zuki_chudan_side_punch.svg`

Keep established romanization in an existing asset unless the user explicitly asks
to rename terminology.

## Validation checklist

Before returning an asset:

1. SVG parses successfully.
2. Canvas is 1024 x 1024 and background is transparent.
3. Positive artwork uses `#EF4444` only.
4. No embedded raster data remains.
5. No unwanted gradients, filters, shadows, metadata, or hidden conversion debris
   remain.
6. Pose, orientation, and important negative-space separators match the approved
   source.
7. Figure scale and margins are visually consistent with the technique-icon family.
8. Render a preview PNG and inspect it at both full and reduced size.
9. Report any karate/anatomy concern separately rather than silently redrawing it.
