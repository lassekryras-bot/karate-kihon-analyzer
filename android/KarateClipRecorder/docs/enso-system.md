# Ensō background system

The app includes the complete 20-variant Ensō library as reusable background artwork. Runtime SVGs live in `app/src/main/res/raw/enso_01.svg` through `enso_20.svg`. The untouched source masters and package metadata are preserved under `artwork/enso/` and are not used at runtime.

## Theme color and tones

All artwork colors are centralized in `EnsoThemeTokens` in `enso/EnsoTheme.kt`. `ensoBaseColor` controls product-accent Ensō artwork such as the Continue card. `ensoPracticeBaseColor` and `ensoTestBaseColor` control the semantic learning-artwork palettes. Change these tokens to retune every use without editing an SVG.

`EnsoTonePalette` derives the required 15 ink tones from that base color. The source gray levels represent how much white is mixed into the base, so their relative light/dark hierarchy remains intact. Pure white source layers stay white.

## Selection lifecycle

Use `EnsoLibrary.createInstance()` once when a new visible learning-card or page instance is created, then retain that `EnsoInstance` with the screen's state. Recomposition, redraw, layout, and data refreshes must reuse its `variant`.

Pass the previous instance to `createInstance(previous)` when replacing one card to prevent an immediate repeat. For a collection of cards, use `newShuffleBag()`; each 20-item cycle uses every variant once and also avoids a repeat at the cycle boundary.

## Rendering

Place `EnsoBackgroundView` behind the foreground learning content in a `FrameLayout` or equivalent layered container, then call `setArtwork(instance.variant)`. Selection never occurs inside the renderer. The view parses the packaged SVG path and rectangle geometry, recolors its 15 tones at draw time, preserves aspect ratio, centers the art, and uses a default 90% scale.

Keep educational diagrams, technique illustrations, labels, and controls in separate foreground views. They should never be merged into the Ensō asset.

## Debug gallery

In a debuggable build, open Home → Settings to view all 20 variants in canonical order with temporary labels `01` through `20`. Those identifiers exist only for inspection and do not appear in product UI. In release builds, Settings retains the existing placeholder behavior.
