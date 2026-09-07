# Karate app logo

`app_logo_source_original.svg` is the untouched canonical source supplied in
`karate_app_logo_codex.zip`. `app_logo_adaptive_foreground.svg` preserves that exact path with the
package's launcher-safe transform. Android uses the package's corresponding transparent PNG because
the full source path exceeds Android's single compiled-vector string limit. A uniform 4dp runtime
inset gives the Enso a little additional breathing room under launcher masks.

Launcher colors are intentionally stable across app themes:

- Foreground: `#0E1C19`
- Background: `#F5EFE4`

Do not redraw, simplify, stretch, or add symbols or text to the logo.
