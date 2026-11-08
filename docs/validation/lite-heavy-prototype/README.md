# Lite/Heavy prototype — preliminary paired phone evidence

Recorded 2026-11-08. Owner: [prototype task 002](../../backlog/prioritized/002-lite-heavy-processing-planning.md). Runtime checks supplied by Senior Developer and confirmed in Architect's review; BA verified copied manifest hashes. No personal video or landmark media is copied here.

## Artifact and input identity

- Implementation: `665d0da4dff36d75426f390af94dbd7e82f49d0d`.
- APK SHA-256: `BFCD607A7ED0F8B9D2C808AD4DAF784D9DE1D603D925D0EC9281FA209A28F72E`.
- Source recording: `e9406fe1-90ba-4d0d-9558-ac6a518cc0db`, 12.604884 seconds; SHA-256 `f87c0c3e93ef1af5d171353028ba47f34d20e9da665aae2062c66b57b13f1e35`.
- Device: Samsung SM-A556B, Android 16 / SDK 36. Same phone, charging, reported thermal status 0; Lite/union-Heavy ran first, comparator second. Detailed model/config/device/provenance fields are in the manifests.

## Results

| Measurement | Lite + union Heavy | Isolated full Heavy |
| --- | --- | --- |
| Lite | 131.448 s | Not used |
| Heavy | 170.395 s | 170.918 s |
| Total | 302.572 s | 171.203 s |
| Heavy frames | 379 | 379 |
| Final intervals | 10 | 10 |

Eleven candidate requests (24.736 s summed) merged into one execution covering the entire 12.604 s source interval. Heavy track hashes and all ten boundaries matched exactly; missing/extra/duplicate intervals were zero. Architect also reports Heavy hashes matched the retained production track. These are source-specific equivalence observations, not independently labelled karate accuracy.

Union removed duplicate Heavy inference, but skipped no source time. Overall Lite/Heavy was 1.767× comparator duration. The -0.523 s Heavy-stage difference is not a general speed claim. This is one ordered pair, not a repeated or counterbalanced benchmark, and grants no performance acceptance or production activation.

Senior reports production DB/WAL/SHM, source video, production MLS and preferences unchanged. No pending directory/crash remained; prior v1 published evidence was preserved, abandoned pending cleaned and keep-awake restored. This does not close the separately recorded earlier movement-player crash.

## Retained evidence

- [Lite/union-Heavy manifest](phone-lite-heavy-manifest-eaa4f893.json), SHA-256 `9265994E6A1527E920D32B2CE10C25474E1842F407783FAA1152F9921582614D`.
- [Full-Heavy comparator manifest](phone-full-heavy-manifest-65565a9d.json), SHA-256 `EDA832F656720F5D3FA45A34A56EE8A07E23F43F92A484F610FFAAB466B65691`.

These copies preserve the original manifest bytes outside ephemeral build output. Any further evaluation is separate: Architect recommends a paused/non-personal recording whose candidate union covers less source time. Do not retune padding to force a positive result or automatically rerun personal media.
