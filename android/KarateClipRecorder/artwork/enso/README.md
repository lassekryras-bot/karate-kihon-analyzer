# Enso Background Library v2

## Contents

### `masters/`
The 20 uploaded SVG vector masters, copied without modification.

### `app_ready_15tone/`
The same 20 Enso assets with their grayscale fills normalized to one shared
15-tone vocabulary:

- Tone 01 = darkest / strongest ink
- Tone 15 = lightest / weakest ink
- Background = pure white

The vector geometry and path ordering are preserved.

## Why the white background remains

The source vectorization uses layered color regions. In several SVGs, legitimate
brush-tone paths also span the full canvas and use cut-outs/overlaps to form the
final image. Removing those layers as if they were ordinary backgrounds can
damage the artwork.

The safe v2 format therefore keeps the true canvas path white and standardizes
only the brush-tone colors.

## App color control

The 15 grayscale values are identifiers/placeholders, not the intended final
visual color.

At runtime or build time:

1. Choose one Enso theme/base color.
2. Derive 15 shades from that color.
3. Replace the 15 canonical SVG grayscale fills with those 15 shades.
4. Leave `#FFFFFF` unchanged.

This guarantees that all 20 Enso variants use the same color system while
preserving their individual brush patterns.

See `tone_levels.json` for the exact canonical fills.


## Cleanup in v3
The app-ready versions of `enso_01.svg` and `enso_03.svg` were rebuilt from the normalized raster preview
to remove detached background speckle/noise while preserving the visible Enso artwork and shared 15-tone grayscale system.
The untouched original masters and v2-derived versions remain preserved in `masters/`.
