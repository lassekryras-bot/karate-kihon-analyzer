# Starter prompt: add or extend a measurement wiki page

Copy the prompt below into a new coding conversation. Replace the measurement
name; fill any other known fields. Unspecified details should be resolved from
current code and data rather than invented. The repository guide is authoritative
for project conventions and can evolve with the implementation.

---

Work in `lassekryras-bot/karate-kihon-analyzer` and implement a measurement wiki
page for **[MEASUREMENT NAME OR RELATED MEASUREMENTS]**. Extend an existing
page when the measurements share the same movement, reference and graph.

Known requirements:
- Measurement purpose: [the question its number answers, or infer from the name].
- Source metric/formula: [existing field or method, if known].
- Example: use the approved default right-hand punch 5 pose data unless an
  explicitly supplied measurement-specific demonstration is required.
- Publication: prepare a reviewable local change; do not push or merge unless
  authorized in this conversation.

Before editing:
1. Inspect main, current branch, uncommitted work and relevant open PRs. The wiki
   may live on an unmerged branch; continue existing work without duplication.
2. Read `AGENTS.md`, `docs/measurement-wiki-authoring-guide.md`,
   `docs/app-measurement-wiki.md`, `docs/measurement-presentation-contract.md`,
   and relevant architecture/method documentation.
3. Inspect the existing presentation exporter, resolver, native wiki renderer,
   catalogue, example bundle and tests referenced in the guide.
4. Briefly state what is reusable and what the new measurement requires.

Implement the two linked pages: a visual explanation with synchronized upper-body
pose and graph, and a short optional calculation page. Use everyday training language; explain
what the user sees in one or two sentences below each visual. The calculation
page can go slightly deeper, but does not need a formula derivation. Keep setup,
calibration, arm length and general estimation explanations out of this page.
Keep technical details in the contract and engineering documentation. Follow the authoring guide's layout,
color semantics, read-aloud wording and accessibility conventions.

Reuse the shared motion and playback. Keep measurement-specific summaries,
overlays, markers and graph behavior explicit. Match coordinate references and
signs between figure and graph. Preserve half-speed playback, foreground guide
lines, torso occlusion, scrubbing, and position on return from the method page.
Keep unavailable measurements explicit.

Register or extend the page in the catalogue so it appears once in the wiki.
Distinct measurement identities do not require separate user-facing pages.
Give it its own matching measurement/presentation identity even when reusing
punch 5's motion. Check the current resolver: its default selects a single
presentation ID, so a new metric needs an explicit selection or a tested resolver
extension. Support a per-measurement demonstration override that replaces graph
and motion together. Do not apply wiki overrides to current exercise results.

Do not label shoulder-width values as centimetres. Do not introduce universal
karate thresholds, combine metrics into one score, change impact detection to
improve the animation, or commit the source personal video. Do not assume the
initial RMS renderer already supports arbitrary metrics; extend only the
required presentation behavior.

For the wrist-path page, use fixed-camera wrist positions for RMS and maximum.
Keep the display transform, dashed start-to-end line, past trail points and
maximum overlay fixed while the body moves naturally. Start playback at the
observed start and provide Show maximum. Do not subtract shoulder motion for
these values or move the historical trail with the current shoulder. This is a
wrist-path decision, not a universal rule for all future measurements.

When changing a reference, update computation, RMS and maximum summaries,
marker selection, graph, packaged asset and existing native wiki together.
Coordinate reference is separate from unit calibration: do not relabel data.
Provide a playable in-chat preview and a concise summary so the user can discuss
actual motion and wording. Distinguish what is in the preview from what is in
the app; do not substitute automated tests for visual review.

Add meaningful tests for the new metric, asset resolution and visual/data
alignment. Run the guide's applicable checks and report blocked checks honestly.
Update the guide if this changes extension points or removes documented limits.

Finish with the implemented measurement, example and units, files changed,
validation, publication status, and at most three next tasks.

---

## Current baseline: one wrist-path page with RMS and maximum

- Extend the existing RMS page with maximum deviation, not a second wiki page.
- Reuse approved punch 5 motion and show both values together, with one graph,
  one animation, a maximum marker, Show maximum and one calculation link.
- Compute both values from the wrist's fixed-camera start-to-impact path.
- Local native source now uses one catalogue entry and fixed-camera samples
  for both summaries. Preserve this behavior when extending the page.
- The fixed-camera chat preview illustrates the interaction. Native compilation
  and device checks remain unverified; distinguish source changes from deployment.
- Retain exact-tie selection: earliest timestamp, then lowest frame number.
- Use accurate units from the implemented scale; keep calibration discussion
  out of the user-facing measurement page. Do not add calibration work here.
- Follow the guide's short copy and update old shoulder-relative explanations
  in the app and technical documentation to match the implemented reference.
