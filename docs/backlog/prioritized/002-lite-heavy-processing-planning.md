# Lite discovery / Heavy analysis — isolated runnable prototype

Status: DONE — isolated prototype delivered; no performance benefit on the tested full-coverage recording

Owner: Senior Developer. Priority/assignment source: Project Architect messages to Business Analyst, 2026-11-07, task `01a1062b-9248-7d32-adc3-190301a735cd`. Initial Stage 1 planning scope was subsequently expanded by Lasse, relayed explicitly by Architect, to an isolated runnable prototype. Retain this task/path as the authoritative record; no duplicate implementation task is needed.

## Purpose and approved scope

Reduce recording-processing cost while preserving trustworthy movement analysis. Authorized work now includes implementing and running an isolated prototype plus comparing timing and coverage against full-video Heavy. Preserve the full-Heavy production path and history. Production default activation and impact-profile changes are not authorized.

Approved prototype approach: a full-video Lite discovery MLS at the existing cadence identifies padded candidate Heavy windows. Heavy files retain original source timestamps and separate provenance; final boundaries and analysis use Heavy evidence. A window may yield zero, one, or multiple movements. Lite and Heavy tracks remain separate. Production thresholds, fallback and quality/performance targets remain unresolved.

## Current evidence and dependencies

- OBSERVED at `94e7454`: `android/KarateClipRecorder/mediapipe-pose-adapter/src/main/kotlin/dk/lasse/karatecliprecorder/mediapipeposeadapter/PoseLandmarkerConfig.kt` declares Full and Heavy, not Lite.
- Existing [Heavy corrective pass](../movement-detail-target-height-heavy-reprocessing-corrective-pass.md) owns full-recording Heavy reprocessing acceptance. This task owns the new staged optimization; it does not duplicate that acceptance.
- [Geometry v2](../landmark-geometry-core-v2-spec.md) prohibits splicing incompatible model tracks or silently reusing calibration across them.
- The Architect reported the exact earlier ten-punch recording/MLS unavailable to Senior at the latest checkpoint. Availability and device connection were not checked here. Use available evidence and state what cannot be measured; synthetic evidence cannot stand in for a real-device performance baseline.
- [Side correctness](../four-limb-motion-characterizer-requirement-v1.md#current-follow-up--2026-11-07) remains a separate defect. Optimization must not silently change its semantics.

## Prototype acceptance / next deliverable

1. Record the available full-video Heavy baseline: input identity/duration, model/build, hardware, processing timing and output counts. If unavailable, record the exact missing input or device and leave the measurement open.
2. Deliver an isolated runnable prototype with invocation instructions and separate discovery/window tracks, original source timestamps, model identity and result provenance. Demonstrate that full-Heavy production routing/history remains unchanged.
3. Explicitly address padding/context, overlapping windows/duplicates, retries, atomic publication, calibration compatibility and zero/multiple-movement windows.
4. Identify unresolved thresholds, fallback behavior and measurable performance/quality targets. Present options for architectural/product decisions; do not invent approved values.
5. Compare prototype versus full-Heavy timing and coverage on identified inputs/hardware. Report missed/split/merged movements and unmeasurable cases, not only speed. Record tests actually run, device acceptance separately and any missing source assets. Synthetic checks do not establish device performance.
6. Link delivered code/commit, invocation, review and comparison evidence here before marking the prototype DONE. Record unresolved production criteria separately; prototype completion never activates the production default or changes impact-profile policy.

## Verification

2026-11-07 reconciliation: source/record review only. No optimization implementation, fresh benchmark or test execution claimed. Senior task `01a10623-0443-7a92-b77f-a94f50868296` latest accessible summaries did not expose a Stage 1 result; completion remains UNKNOWN.

2026-11-07 scope update: Architect relayed explicit runnable-prototype authorization. BA requested a current implementation/evidence checkpoint directly from Senior; delivery, tests and device comparison remain unverified until evidence arrives. Follow the [development handoffs](../README.md#development-handoffs).

2026-11-07 start: Lasse explicitly instructed BA to start prototype work. BA dispatched implementation to Senior Developer; the task status was observed active. Requested first implementation milestone, blockers and subsequent verification evidence. This confirms dispatch, not delivery or acceptance; status remains IN PROGRESS.

### Senior discovery checkpoint — 2026-11-07

Reported by Senior task `01a10623-0443-7a92-b77f-a94f50868296`; not independently retested by BA:

- `TrainingSessionProcessor` currently uses one full-video `VideoPoseProcessor` track for segmentation and analysis. `SequentialVideoPoseDecoder` preserves source PTS but processes the full file.
- `PoseLandmarkerConfig` supports Full/Heavy, not Lite. Committed app assets contain Full; `input/models` contains Heavy; Lite is absent.
- Reusable capabilities: `LandmarkFiles` atomic write/hash, `CanonicalGeometryStorageAdapter` track binding, the existing segmenter and track provenance.
- Proposed implementation direction: isolated developer-only prototype/manifest, retaining production routing and avoiding a Room migration. This is Senior's direction, not a recorded architectural approval.
- Next milestone: window/provenance contract and deterministic tests, then Android extraction wiring after contract review.
- Device timing dependencies: obtain Lite asset and establish device/input availability. These do not block contract/discovery work. No prototype code, test result or performance result was reported at this checkpoint.

### Architect review outcome — 2026-11-07

APPROVED for prototype only, reported by Architect task `01a1062b-9248-7d32-adc3-190301a735cd`: isolated developer-only runner/manifest reusing MLS, shared segmenter, canonical geometry and decoder; no Room migration or production-default switch. This resolves the approach approval left pending in Senior's checkpoint above.

Required contract/acceptance: distinguish Lite candidate bounds, requested Heavy bounds, actual coverage, final segmentation and model/track identity. Preserve source PTS and history; prohibit implicit calibration reuse; expose initialization/truncation uncertainty explicitly. Implementation, test results and device timing remain pending. No speed improvement is established by this approval.

### Milestone 1 — planner and window extraction, 2026-11-07

Senior reports implementation complete for this slice, uncommitted and not production-activated. BA confirmed the named files are present/changed in the shared working tree; BA did not rerun tests.

- Core `capture/retrospective/LiteHeavyPrototypePlan.kt`: distinct model/track/content/config/file/geometry provenance, requested versus actual coverage, provisional padding, overlap disclosure, explicit full-Heavy fallback for empty discovery, edge/tracking uncertainty, zero/one/many movement outcomes and cross-track/recording/calibration invariants.
- Core `capture/retrospective/VideoPoseProcessor.kt`: optional `WindowedVideoPoseProcessor` contract retaining the full-video interface.
- Adapter `SequentialVideoPoseDecoder.kt`: inclusive source-PTS window extraction; a new landmarker per call resets tracking. Existing `processVideo` path/signature retained, per Senior.
- Senior reports focused planner/window `:karate-analyzer-core:test` and decoder `:mediapipe-pose-adapter:testDebugUnitTest` passed, with 20 Gradle tasks and BUILD SUCCESSFUL. Twenty tasks is not a test-case count. Test files: `LiteHeavyPrototypePlanTest.kt`, `VideoPoseWindowResultTest.kt`, `SequentialVideoPoseDecoderTest.kt`. Cases include padding/clamping/overlap, unknown candidate, empty fallback, incompatible tracks, actual timestamps, empty/nonmonotonic windows and missing source.
- Senior reports junior review identified and resolved P1 contract invariants before runner work. No Room, `TrainingSessionProcessor` or production-default change is reported. This is not resolution of the separate punch-side P1.

Next milestone: standalone artifact/manifest runner with atomic MLS/manifest publication tests. Still outstanding: Lite asset/provenance and real device/input timing and coverage comparison. The full prototype remains IN PROGRESS; no runnable end-to-end completion, device acceptance or speed improvement is claimed.

### Phone-testable delivery requested — 2026-11-07

Lasse asked to test on his phone and explicitly instructed work to start. BA dispatched continuation through the complete phone-testable prototype; Senior task was observed active. Deliver an installable debug APK, a practical developer-only invocation, exact installation/run steps and timing/coverage comparison instructions. Include Lite model/provenance and runner/publication verification. Record actual device checks when available, or the specific missing device/input dependency; build success alone is not device acceptance. Continue independent implementation beyond intermediate milestones. Existing production default, history, impact policy and separate side-correctness scope remain unchanged.

### Milestone 2 — phone-testable debug build, 2026-11-07

Senior reports the isolated runner, typed provenance, full-video Lite discovery, separate padded Heavy MLS windows with tracker reset, original source timestamps, requested/actual coverage, overlap/edge/unknown evidence and atomic manifest publication implemented. Empty discovery explicitly returns `FULL_HEAVY_FALLBACK_REQUIRED`; it does not automatically establish a no-movement result or a completed fallback run.

Senior reports focused tests plus full `:karate-analyzer-core:test`, `:app:testDebugUnitTest`, `:mediapipe-hand-adapter:testDebugUnitTest`, `:mediapipe-pose-adapter:testDebugUnitTest` and `:app:assembleDebug` passed. Senior verified packaged Lite and Heavy assets. BA confirmed the debug APK exists (118,389,835 bytes at this checkpoint), source entry/policy and run documentation; BA did not rerun tests or inspect packaged assets. Implementation remains uncommitted at this checkpoint.

Build: `android/KarateClipRecorder/app/build/outputs/apk/debug/app-debug.apk`. [Phone run instructions](../../../android/KarateClipRecorder/README.md#lite-to-heavy-processing-prototype): install the debug build, enable Developer mode, open Performance > Recordings, select an available recording, expand Recording details and choose Run Lite→Heavy prototype. Keep the app open. The tool is unavailable during processing/deletion; the completion dialog reports timings/windows/intervals and manifest location. Outputs remain under app-private `files/training/prototypes/` and do not replace production analysis.

Senior found no connected ADB device. Physical phone execution, same-input full-Heavy timing/coverage comparison and acceptance therefore remain pending. The missing Lite model/runner dependencies from earlier checkpoints are now resolved. Status stays IN PROGRESS; build readiness is not a speed claim or production activation.

### Architect implementation review — changes required, 2026-11-07

This review supersedes the readiness implication of milestone 2: an APK exists, but the implementation is not architecturally approved. Architect reports CHANGES REQUIRED before push and has requested bounded fixes/tests from Senior:

- P1: partial Lite coverage can leave unobserved source spans without Heavy windows. Correct the treatment of incomplete discovery coverage and verify that missing observation cannot silently exclude spans from analysis.
- P2: bind the manifest to the effective segmentation configuration and source evidence needed to reproduce the run.
- P2: add missing cancellation and Heavy-failure verification, including publication/failure behavior.
- P2: explicitly label the UI interval sum as overlapping/non-deduplicated; do not imply a unique movement count.

Isolation, no production writes and separate model evidence were judged sound. Existing passing tests/build do not resolve these findings. Next: Senior's scoped corrections, verification evidence and Architect re-review before push; then phone acceptance/comparison. Device performance benefit remains unverified. Keep unrelated BA backlog edits outside Senior's scoped implementation commit.

### Architect re-review — approved isolated prototype, 2026-11-07

Architect inspected the corrections and APPROVED the isolated developer prototype for commit/push; prior blocking findings are resolved. Architect reports independent verification of 1,007 core/debug tests with zero failures/errors and a clean diff check. BA records this supplied verification; it did not rerun the suites. This supersedes the preceding changes-required outcome, not the remaining device acceptance.

Explicit limitations: all-or-nothing execution with no resume; provisional gap/padding policy; overlapping, non-deduplicated per-window outputs. Approval does not activate production routing or establish accuracy/performance benefit.

Remaining P3 provenance requirement: the manifest build identity (version/code/debug) is not a unique commit. Pair device benchmark evidence with the exact implementation commit and APK SHA-256, alongside source/model/device identity. Next milestone is Senior's scoped commit/artifact identification, followed by same-input device coverage/timing comparison against full Heavy. Do not reuse the earlier APK size as proof of the corrected artifact. Status remains IN PROGRESS until applicable acceptance criteria are met; preserve unrelated BA edits outside the scoped implementation commit.

### Approved artifact delivered — 2026-11-07

Senior reports scoped implementation pushed to main at `e5737a9cec235652fd2ba6f3f1eb4e3503acb0b1` (following `0afdc3e`); unrelated BA documentation was excluded. BA independently verified local HEAD, APK size and SHA-256 match this delivery:

- APK: `android/KarateClipRecorder/app/build/outputs/apk/debug/app-debug.apk`
- Size: 118,672,209 bytes.
- SHA-256: `83544E989F0A2C19038A7A5D008869483EAF9C2B4F328A3C1505D4EF35DDC233`.
- Senior verification: 1,007 tests, zero failures/errors/skips; `assembleDebug` passed; packaged Lite and Heavy assets confirmed. BA did not rerun these checks.
- Architect confirms the manifest field is now accurately named `appBuildIdentity` (version/code/debug); the exact commit and APK hash above supply the separate artifact identity required for benchmark evidence.

The approved corrected build is available for phone testing using the linked run instructions. Senior reports no ADB device connected. Next: install this identified artifact, record device/source/model identities and compare coverage/timing against full Heavy on the same input. Keep status IN PROGRESS: no device acceptance, production activation or speed/accuracy benefit is established by delivery.

### Phone installation verified — 2026-11-07

Senior reports the exact delivered commit/APK hash verified before successful `adb install -r` on Samsung SM-A556B, serial `RZCY10A22MW`. First-install time remained 2026-07-09 and update time advanced to 2026-11-07 21:34:26; in-place installation retained app data. MainActivity is top-resumed, the process is live, Developer mode was already enabled and the recording detail screen exposes Run Lite→Heavy prototype. Production data remains untouched.

No prototype run yet: multiple saved recordings exist and user selection is pending. Suggested candidate is 15 Sep 2026 20:19, Alternating straight punches, 12.6 seconds, planned/detected 10, production Heavy track `4826f011-499f-4a12-b36a-d9b4b208f6a5`. Existing recorded timings are landmarks 257,152 ms and segmentation 245 ms; these are historical candidate data, not a newly measured comparison baseline. BA asked Lasse to confirm this recording or choose another. Installation/launch readiness is verified per Senior; coverage/timing acceptance remains pending.

2026-11-07 selection confirmed: Lasse selected the 15 Sep 2026 20:19 candidate above. BA instructed Senior to run the isolated prototype and collect source-bound manifest, timing, coverage, uncertainties and preservation evidence. Selection is resolved; actual execution/results are pending. Any comparison against the stored timing must be labelled historical until a matched full-Heavy benchmark is measured.

### First phone run interrupted — 2026-11-07

Senior confirmed selected recording `e9406fe1-90ba-4d0d-9558-ac6a518cc0db`, source SHA-256 `f87c0c3e93ef1af5d171353028ba47f34d20e9da665aae2062c66b57b13f1e35`, and production Heavy track `4826f011-499f-4a12-b36a-d9b4b208f6a5`, SHA-256 `39091b93bba46594ca7ff2a752e86ed2eb762e99d7112ae0cdb4bd54fd2fff54`.

Senior reports Lite and nine Heavy windows completed in pending artifact `7387859e-548c-4c5c-a738-7cae799ad99b`, but the app crashed in the existing `MovementPresentationPlayerView`/MediaPlayer path after MovementDetailActivity opened at 21:46:05. No manifest was published. This is an interrupted attempt, not a completed prototype or valid timing result. Source/production-track hashes remained unchanged; stale pending artifacts are retained for failure evidence. BA has not independently diagnosed the crash.

Rerun awaits Lasse unlocking the phone; keep the app open without entering movement details during the run. Track the player crash under [Movement Detail](../movement-detail-page-v1.md#prototype-phone-test-crash--2026-11-07), not as an assumed segmentation/model defect. Device acceptance and matched timing/coverage remain pending.

### Successful phone execution — 2026-11-07

Senior reports completed prototype `e18607f5-b692-4e9c-ad63-3813d72e3a57` on the selected source above (12,604 ms), using the delivered commit/APK. Device: Samsung SM-A556B/a55xnaeea, `RZCY10A22MW`, Android 16 / SDK 36, arm64-v8a; fingerprint `samsung/a55xnaeea/a55x:16/BP4A.251205.006/A556BXXSIDZI3:user/release-keys`.

| Measurement | Observed result reported by Senior |
| --- | --- |
| Lite | 133,694 ms; 379 frames; actual coverage 0–12,604 ms |
| Heavy windows | 347,842 ms; 11 completed windows; zero failures |
| Total | 482,873 ms (about 8 min 3 sec) |
| Discovery | 10 movement candidates plus 1 unknown-evidence candidate; `WINDOWS_PLANNED` |
| Requested Heavy coverage | Sum 24,736 ms; union 12,604 ms, the entire recording; 1.963× overlap multiplicity |
| Actual Heavy coverage | Summed 24,604 ms; this sum does not prove gap-free coverage of every window |
| Final intervals | 19 per-window intervals, overlapping/non-deduplicated; not 19 unique punches |

Manifest: `android/KarateClipRecorder/build/phone-lite-heavy-manifest-e18607f5.json`, SHA-256 `ED9F46D5C9FAD2D5BC0F57E83CD8AAF5379459540FA6C15246D199FF36BBE602`. BA independently verified this local file hash; full model identities and per-window evidence are retained there. Build-directory evidence must be retained before cleaning build outputs.

Uncertainties include tracker reset per window, narrower actual coverage, clipped start/end padding, segments touching actual ends and recording end without stop evidence. `productionActivated=false`, `calibrationApplied=false`, policy `all-or-nothing-no-resume-v1`. Senior reports unchanged DB/WAL/SHM hashes, production Heavy track hash and source MP4 hash. Temporary USB stay-awake was restored to its original value, 0. These preservation checks were reported by Senior, not independently repeated by BA.

The historical same-source full-Heavy landmarks timing is 257,152 ms (segmentation 245 ms). This run's total is approximately 1.878× that historical landmarks timing, and its Heavy stage approximately 1.353×. This is not a controlled comparison or a general speed claim. Overlapping windows request almost twice the full recording duration, demonstrating no reduction in requested Heavy coverage on this sample.

No fresh isolated full-Heavy comparator is available: the existing reprocess UI would change production results/history, so Senior did not invoke it. Keep IN PROGRESS: execution/publication is demonstrated, but matched baseline, coverage/quality adjudication and performance acceptance remain open. Next decision: review overlap/coverage findings and define the smallest isolated full-Heavy comparison with Architect/Senior, without activating production or silently tuning padding.

The earlier failed artifact `7387859e-548c-4c5c-a738-7cae799ad99b` remains pending (reported 1.852 MB). Crash trace: `android/KarateClipRecorder/build/phone-movement-player-crash-20261107-214605.txt`, SHA-256 `282E8EEC32832ADCA009CCDB8BC5BF54A566CBA766F19493F40C48A717262310`, independently verified by BA. Successful rerun does not close the separately owned player crash.

### Approved follow-up scope — 2026-11-07

Architect approved and assigned these bounded changes to Senior within this isolated prototype:

1. Merge overlapping/touching padded requests into disjoint Heavy execution windows. Retain individual candidate/request provenance and explicit candidate-to-execution mapping; process and segment each union once. Keep padding and thresholds unchanged.
2. Add the smallest isolated full-Heavy comparator using the existing full-video Heavy decoder, writing prototype-only artifacts/manifest. Pair it with the same model/config/source/build; do not mutate production data or use production reprocess as the comparator.
3. Distinguish abandoned pending directories after process death from active/completed runs. Support safe prototype-only discard/cleanup using existing capabilities, without a new queue or deleting active/completed production evidence.

Acceptance evidence for these changes: verify disjoint execution with retained candidate mapping and single segmentation per union; comparator source/model/config/build identity and production isolation; abandoned/active/completed distinctions and scoped cleanup. Compare source-time coverage/bounds and matched actual durations. A first paired run is preliminary and must record run order and device conditions. The current whole-source union skips no idle time, so adding Lite overhead is expected; overlap removal alone does not establish accuracy or speed improvement.

Implementation is assigned; no automatic rerun of Lasse's personal recording is authorized by this follow-up. Request the next device run when the revised artifacts are ready. Player crash remains separately owned. Status remains IN PROGRESS; production activation and padding/threshold retuning remain unapproved.

### Follow-up implemented — awaiting PA review, 2026-11-07

Senior reports the approved follow-up implemented locally, uncommitted/unpushed and not run on Lasse's recording:

- Union overlapping/touching candidate windows before Heavy inference, retaining candidate provenance/links and requested-sum versus union diagnostics.
- Isolated full-video Heavy comparator action/manifest, with source revision, installed APK hash, device conditions and run sequence recorded.
- On next prototype entry, discard abandoned pending prototype directories while preserving current/published artifacts.

Senior reports full suite and `assembleDebug` passed: 1,010 tests, zero failures/errors/skips. BA has not rerun these checks. PA implementation review/explicit approval is pending before scoped commit/push. This checkpoint does not certify cleanup safety or measured performance; review and subsequent device evidence remain required. Existing BA documents were untouched by Senior. No new personal-video run is authorized or reported.

### Follow-up PA review — changes required, 2026-11-07

Architect approves the union/comparator direction but requires bounded fixes before push; Senior has the concrete actions:

1. Version the changed manifest shape and retain the earlier v1 phone evidence with its original interpretation.
2. Guard thermal-status access by supported Android API; minSdk is 26. Verify behavior when the API is unavailable rather than inventing a thermal observation.
3. Make Git revision metadata optional, accurately represent dirty state and preserve builds outside a Git checkout.
4. Clarify/enforce serialized ownership of prototype cleanup and canonical filesystem scope, preserving active/published artifacts and unrelated data.

Next: Senior corrections and targeted verification, then PA re-review before scoped push. The earlier 1,010-test result does not close these findings. Task remains IN PROGRESS; isolated comparator/device pairing is pending. No production activation or personal-video rerun is authorized by this review.

### Follow-up PA re-review — approved, 2026-11-07

Architect APPROVED isolated prototype delivery after verifying the corrective v2 manifest, thermal API guard, optional explicit source revision with honest dirty-state provenance, and serialized cleanup with canonical bounds. All preceding blocking review findings are resolved. Architect reports 1,013 core/debug tests with zero failures/errors; BA has not rerun them.

Senior is authorized for scoped commit/push and an exact-source APK build with truthful dirty-state metadata, preserving unrelated BA changes. Next delivery evidence: implementation commit, APK SHA-256, source/dirty identity and actual build verification. Fresh paired device coverage/timing comparison remains pending and requires the separately agreed phone run. Keep IN PROGRESS: this review does not establish production activation, accuracy or performance acceptance, and does not itself authorize another personal-video run.

### Union/comparator artifact delivered — 2026-11-07

Senior reports approved implementation pushed to main at `665d0da4dff36d75426f390af94dbd7e82f49d0d`. BA independently verified local HEAD and the delivered APK size/hash:

- APK: `android/KarateClipRecorder/app/build/outputs/apk/debug/app-debug.apk`, 118,681,391 bytes.
- SHA-256: `BFCD607A7ED0F8B9D2C808AD4DAF784D9DE1D603D925D0EC9281FA209A28F72E`.
- Embedded provenance reported by Senior: `SOURCE_REVISION=665d0da4dff36d75426f390af94dbd7e82f49d0d`; `SOURCE_DIRTY_STATE=dirty-unrelated-worktree-changes-present`.
- Manifest versions: `lite-heavy-prototype-manifest-v2` and `full-heavy-comparator-manifest-v1`. Earlier v1 phone evidence remains historical evidence, not rewritten under v2.
- Senior reports 1,013 tests passed with zero failures/errors/skips and successful `assembleDebug`. BA did not rerun these checks.

No installation or new personal-recording run was performed for this delivery. Await Lasse's selection/authorization for installation and paired Lite/merged-Heavy versus isolated full-Heavy testing. Record run order, conditions, exact source/model/build identity and coverage with timings. Task remains IN PROGRESS; delivery is not device/performance/accuracy acceptance or production activation.

### Device access checkpoint — 2026-11-08

Senior reports approved main/origin commit `665d0da4dff36d75426f390af94dbd7e82f49d0d` and APK still present. Phone `RZCY10A22MW` is physically detected but repeatedly ADB-unauthorized. Validation paused before installation/data access; no device or production state changed. Unlocking the phone and accepting its USB-debugging prompt is required for access. BA's latest question requesting confirmation to install and run the paired comparison on the 15 Sep 20:19 recording remains unanswered in this task; USB authorization alone does not answer that pending run selection.

### Device access and paired-run authorization resolved — 2026-11-08

Project Architect reports verified ADB authorization for Samsung SM_A556B, serial `RZCY10A22MW`, and explicitly relays that Lasse is ready. Architect instructed Senior to proceed with the isolated paired union Lite/Heavy versus full-Heavy comparison and production preservation checks at local HEAD `665d0da`. This resolves the preceding access/authorization blockers. Installation/execution and comparison results remain pending; instruction to proceed is not a completed run or acceptance claim.

### Paired device comparison completed — 2026-11-08

Senior reports one completed isolated pair on the selected 12.604884-second recording. This is preliminary sample-specific evidence, not broad acceptance. Eleven Lite candidate windows merged into one full-source Heavy execution (requested sum 24.736 s; union 12.604 s).

| Stage | Lite + merged Heavy | Isolated full Heavy |
| --- | --- | --- |
| Lite | 131.448 s | Not used |
| Heavy | 170.395 s | 170.918 s |
| Total | 302.572 s | 171.203 s |

Heavy-stage delta was -0.523 s (-0.31%); total Lite/Heavy time was 1.767× the comparator. Whole-source coverage left no video time to skip, so Lite added overhead on this sample. One pair does not establish a statistically meaningful Heavy-stage speed difference or general performance behavior.

Senior verified matching source/APK/model/pipeline/geometry, byte-identical Heavy tracks (same SHA, 379 frames), and ten exactly matching boundaries with zero missing/extra/duplicate intervals. Equality to full Heavy is not independently labelled karate accuracy. Production DB/WAL/SHM, source video, production MLS and preferences were byte-identical after the runs. No pending directories or crash occurred; prior published prototype was preserved and abandoned pending artifacts cleaned. The separate historical player crash is not closed by a crash-free paired run.

Evidence files (BA independently verified local hashes; timing/behavior verification reported by Senior):

- `android/KarateClipRecorder/build/phone-lite-heavy-manifest-eaa4f893.json` — SHA-256 `9265994E6A1527E920D32B2CE10C25474E1842F407783FAA1152F9921582614D`.
- `android/KarateClipRecorder/build/phone-full-heavy-manifest-65565a9d.json` — SHA-256 `EDA832F656720F5D3FA45A34A56EE8A07E23F43F92A484F610FFAAB466B65691`.

The paired-comparison milestone is complete. Task remains IN PROGRESS pending review of applicable prototype completion criteria and a decision on any further evaluation; do not require positive speed improvement to report a valid negative experiment. No further personal-video run, production activation, threshold/padding retuning or policy change follows automatically. Preserve build-directory evidence before cleanup.

Durable evidence now lives in [the paired phone report](../../validation/lite-heavy-prototype/README.md), with byte-preserving copies of both manifests and their verified hashes. No personal media was copied. Architect confirms same-phone charging/thermal status 0 and Lite-first/comparator-second ordering. Recommended next evaluation, if selected: a paused/non-personal recording with smaller candidate union; not padding retuning to force a win. Broad performance acceptance/default activation remains unapproved.

### Final outcome — DONE, 2026-11-08

Project Architect reviewed acceptance criteria 1–6 and approved closing the isolated prototype scope. The runnable isolated implementation, explicit limitations/provenance, reviewed handling, matched baseline/comparison and durable evidence satisfy delivery; a positive speed result is not required. The two manifests and concise report are retained under `docs/validation/lite-heavy-prototype/`, with verified original-byte hashes and no personal MP4/MLS. Inspection found no user name, device serial or absolute local/source paths in the retained manifests; no sanitization or hash substitution was performed.

Result: on the tested full-coverage recording, Lite/union-Heavy took 302.572 s versus isolated full Heavy 171.203 s, with identical Heavy evidence and ten boundaries. This is a negative performance finding for one pair, not general speed acceptance, calibrated metric/impact accuracy or production activation. Production remains unchanged.

The P1 side-correctness defect and player crash remain under their existing owners. Sparse-video benefit, disjoint-window tracking equivalence and break-even policy are unvalidated future work, to be separately scoped only if Lasse prioritizes it. No additional phone run, threshold/padding change or representative dataset is required to close this prototype, and none is automatically authorized by closure.
