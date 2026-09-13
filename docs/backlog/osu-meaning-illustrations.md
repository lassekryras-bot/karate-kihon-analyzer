# Osu meaning illustration assets

Status: OPEN

## Purpose

The complete `Osu — Meaning & Use` picture-choice interaction is implemented
with neutral icon placeholders behind the semantic `OsuIllustration` mapping.
This deliberately allows lesson flow, card proportions, selection feedback and
accessibility to be reviewed before production artwork is approved.

## Final assets still needed

Provide approved, localization-safe artwork for these exact logical slots:

- `sensei_speaking`
- `boy_heard_you`
- `boy_thinking_pizza`
- `boy_ready_guard`
- `boy_thinking_sleep`
- `boy_one_more`
- `boy_thinking_home`

The Sensei must remain calm and supportive in a white gi with a black belt. The
Boy must remain the same beginner character in a white gi with an orange belt.
Speech bubbles must be blank and all instructional text must remain in Android
string resources.

Candidate source SVGs already exist under `input/images/`, but they are not wired
into the activity by this placeholder-first implementation. Confirm their final
role, crop and visual approval before changing only `OsuIllustrationAssets`.

## Acceptance

- Replacing placeholders requires only resource import/conversion and changes to
  `OsuIllustrationAssets`, not presentation state, question logic or layout.
- Artwork preserves the current slot aspect ratios and padding at supported text
  and display sizes.
- Selected, incorrect and correct card states remain readable without relying
  on color alone.
- TalkBack descriptions continue to describe the meaning of each picture.
