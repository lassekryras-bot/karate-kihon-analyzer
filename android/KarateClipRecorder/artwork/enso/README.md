# Enso Background Library v2

## Contents

### `masters/`
The 20 uploaded SVG vector masters, copied without modification.

### `app_ready_15tone/`
The same 20 Enso assets with their grayscale fills normalized to one shared
15-tone vocabulary:

- Tone 01 = darkest / strongest ink
- Tone 15 = lightest / weakest ink
- Background geometry = `fill="none"`

The vector geometry and path ordering are preserved.

## Transparent app rendering

The source vectorization uses layered color regions. In several SVGs, legitimate
brush-tone paths also span the full canvas and use cut-outs/overlaps to form the
final image. Removing those layers as if they were ordinary backgrounds can
damage the artwork.

The safe app format keeps the source geometry but changes background-only layers to `fill="none"`
and standardizes only the brush-tone colors. The canvas is therefore transparent and the Enso is
painted directly onto its containing card. App-ready files must not contain an explicit full-canvas
white rectangle or path.

The v3 raster-rebuilt variants 01 and 03 encode the empty canvas in their lightest quantization
band. That band is transparent in the app-ready SVGs; their remaining pale brush tones are retained.

## App color control

The 15 grayscale values are identifiers/placeholders, not the intended final
visual color.

At runtime or build time:

1. Choose one Enso theme/base color.
2. Derive 15 shades from that color.
3. Replace the 15 canonical SVG grayscale fills with those 15 shades.
4. Give background-only geometry `fill="none"`.

This guarantees that all 20 Enso variants use the same color system while
preserving their individual brush patterns.

See `tone_levels.json` for the exact canonical fills.


## Cleanup in v3
The app-ready versions of `enso_01.svg` and `enso_03.svg` were rebuilt from the normalized raster preview
to remove detached background speckle/noise while preserving the visible Enso artwork and shared 15-tone grayscale system.
The untouched original masters and v2-derived versions remain preserved in `masters/`.
