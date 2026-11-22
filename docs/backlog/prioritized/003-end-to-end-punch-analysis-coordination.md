# Resume end-to-end straight-punch analysis

Status: IN PROGRESS — coordination pointer; requirements remain with existing owners

Priority source: Lasse's 2026-11-08 decision, explicitly relayed by Project Architect task `01a1062b-9248-7d32-adc3-190301a735cd`, after closure of prototype 002. Senior is assigned the initial evidence/blocker deliverable. This pointer orders existing work; it is not a duplicate feature specification.

**Current assignment — 2026-11-21:** Lasse explicitly specified implementation of the [full-window paired-arm world-velocity score](../four-limb-motion-characterizer-requirement-v1.md#current-user-specification--2026-11-21), relayed by PA. Senior is dispatched for code/tests, normal Android downstream integration and exact before/after replay. This supersedes conflicting earlier diagnostic-only/episode-opening/paired-inference restrictions for side selection; the dated history below remains evidence, not the current method. Impact/view and calibration gates remain unchanged.

## Selected sequence

1. [Four-limb / P1 striking-side correctness](../four-limb-motion-characterizer-requirement-v1.md): recover and inspect exact ten-punch evidence; establish observed striking side rather than infer it from alternation or retracting-arm opening.
2. [ImpactAnalyzer / view eligibility](../impact-analyzer-requirement-v1.md): explicitly validate straight-punch/activity/view suitability with Architect. User-selected view or human event labels alone do not grant production impact approval.
3. [Impact terminal events and metric evidence](../impact-analyzer-requirement-v1.md), with [target-height consumer](../movement-detail-target-height-heavy-reprocessing-corrective-pass.md): expose supported terminal/per-metric outputs and explicit reasons for legitimate abstention, using shared analytical owners.
4. [Movement Detail](../movement-detail-page-v1.md) and [Recording Results](../recording-results-page-v1.md): verify correct persisted/reopened outputs, provenance and phone behavior.

## First deliverable

Senior supplies recovered exact source/MLS identity, per-movement blocker matrix and smallest corrective proposal. The recent [paired phone evidence](../../validation/lite-heavy-prototype/README.md) resolves the earlier missing-source blocker: source `e9406fe1-90ba-4d0d-9558-ac6a518cc0db`, production Heavy track `4826f011-499f-4a12-b36a-d9b4b208f6a5`. Recheck identity when retrieving evidence; do not assume another similarly named ten-punch recording is equivalent.

For each accepted segment, record observed/reviewer side and uncertainty, current resolver evidence/result, activity/view approval state, terminal/metric eligibility and blocker reason, persistence provenance and the smallest next verification. Distinguish missing evidence, unsupported policy and incorrect interpretation. Link raw evidence and versioned outputs; do not fabricate a complete result for every movement.

## Constraints and completion

Preserve accepted segmentation, the full-Heavy production route and historical results. This priority does not reopen Lite optimization or authorize automatic production reanalysis. Keep impact-profile decisions explicit. The [validation-strategy task](../analysis-validation-evidence-and-test-strategy.md) supplies scoped fixture/label discipline; a full dataset program is not a prerequisite for this bounded repair.

Completion requires supported outputs traceable to correct side, eligible view and actual evidence, explicit legitimate abstentions, persistence/reopen verification and phone evidence. Ten numeric outputs are not a completion requirement. Update each owning task only for its verified scope; this pointer's completion does not close unrelated parts of those specifications.

The [player crash](../movement-detail-page-v1.md#prototype-phone-test-crash--2026-11-07) remains separately tracked; address it within this sequence only if it blocks acceptance. No new architecture/framework is prescribed. BA tracks milestones and priority; Senior supplies implementation/evidence; Architect resolves material semantics and approvals.

## Initial evidence deliverable — 2026-11-08

Senior reports exact source/production Heavy identities verified, all ten accepted boundaries preserved and no personal data added to the repository. Row-by-row matrix and the smallest shared-core semantic proposal were sent to Architect; BA has received the following summary, not independently replayed the recording:

| Movements | Human-review side reported by Senior | Current resolver | Current blockers |
| --- | --- | --- | --- |
| 1, 3, 5, 7, 9 | LEFT | LEFT, matching review | Missing approved activity/view mapping |
| 2, 4, 6, 8 | RIGHT | LEFT, incorrect | Side interpretation and missing approved activity/view mapping |
| 10 | RIGHT | Ambiguous | Side first; activity/view mapping also missing |

The authoritative diagnostic values, episode/window distinctions, coordinate signs and reference definitions now live in the [sanitized exact ten-punch blocker matrix](../../validation/punch-analysis/exact-ten-punch-blocker-matrix-2026-11-08.md). Use that record rather than the earlier chat-level aggregate ranges; do not interchange coordinate signs, episode intervals or reference choices. Far/right wrist evidence is fragmentary/low-confidence except better coverage on movement 10; it cannot be asserted as observed extension. Visible-arm retraction alone does not establish an approved opposite-arm inference rule.

All ten have no approved activity/view mapping and zero `MeasurementResult` rows. Senior reports generic evidence already persists and reopens/displays; this does not establish persistence acceptance for corrected terminal/metric results. Max speed, path deviation and hikite speed are unimplemented UI placeholders, not implemented metrics waiting for the impact gate. Do not silently expand this bounded correction into implementing them.

Initial evidence/proposal has been delivered to PA. Implementation currently awaits body-relative side and activity/view policy decisions, not additional phone access. Next: Architect decision with exact evidence/proposal reference, then the smallest approved repair and scoped verification. Keep IN PROGRESS; no profile approval, production reanalysis or correctness acceptance is implied by this checkpoint.

## PA semantic review — changes required, 2026-11-08

Architect confirms source/MLS/calibration recovered; production holds 20 analysis rows and zero measurement rows. Implemented downstream outputs are target classification, angles, margin and elbow metrics; max speed/path deviation/hikite speed remain separately scoped placeholders. Do not promise those features as part of this bounded repair without an owner scope decision.

CHANGES REQUIRED on the proposed side rule: retracting near arm plus far hand held extended does not establish a far-hand strike. Senior must provide per-episode body-relative trajectory and identity evidence, explicit axis/reference definitions, sensitivity checks and counterexamples. No production analytical semantic approval is granted. A safe initial slice may reject the wrong side while retaining UNKNOWN for RIGHT; this is a possible bounded outcome, not approval of an unspecified rule.

Existing target/angle output still needs independently valid activity/view/profile approval. Observed OTHER/UNKNOWN view labels are not suitability decisions. Preserve explicit abstention and accepted bounds; no forced outputs. Next milestone is revised evidence/proposal and PA review.

## Durable diagnostic delivered — 2026-11-08

Senior supplied the [exact ten-punch blocker matrix](../../validation/punch-analysis/exact-ten-punch-blocker-matrix-2026-11-08.md): replay identities, ten rows, units/anchor/coordinate sign/filter/time policy, reference comparisons, threshold sensitivity, identity limitations and bounded next diagnostic. It owns the detailed findings; this coordination pointer does not duplicate them. Personal MP4/MLS is not included.

Senior reports test-only counterexamples added and passing; BA has not rerun them. This is diagnostic verification, not approval of a production side/view rule. The previous request for a durable detailed record is satisfied; PA semantic decision/review and applicable implementation remain pending. Status stays IN PROGRESS.

## PA diagnostic next step approved — 2026-11-08

Architect reviewed the durable matrix and APPROVED diagnostic work only. Episode-concurrent motion separates the five reviewed LEFT punches from the four false-LEFT results on this recording; this is distinct from the earlier whole-window explanation. Numeric thresholds remain unapproved for production.

Senior's next milestone:

1. Execute an isolated/test-only shared-geometry candidate evaluation with counterexamples.
2. Replay diagnostic impact/target analysis for supported LEFT cases using explicitly hypothetical view input to expose exact downstream blockers.
3. Deliver executable results and a concrete activity/view suitability proposal for review.

Hypothetical diagnostic view input is not runtime `ImpactViewApproval`. Do not fabricate RIGHT evidence, alter production behavior, force metrics or implement the separately unimplemented speed/path/hikite placeholders. Existing target/angle/margin/elbow outputs remain the scoped downstream capabilities. Status remains IN PROGRESS; production side/view semantics and acceptance are still open.

## Executable diagnostic milestone — 2026-11-08

Senior delivered executable results in the [authoritative diagnostic record](../../validation/punch-analysis/exact-ten-punch-blocker-matrix-2026-11-08.md#executable-isolated-diagnostic), including opt-in reproduction command, private result hash, sensitivity probes and synthetic counterexamples. BA read the record but did not rerun the private replay. Personal artifacts remain outside the repository.

At all disclosed probes, the test-only candidate retained the five reviewed LEFT cases, rejected four false LEFT cases and left movement 10 unresolved; it did not infer RIGHT or establish production thresholds. With explicitly hypothetical view input, impact completed only for movements 1/9. Movements 3/5/7 lacked the required continuous 100 ms stable window; a high transition score is not completion evidence.

Movements 1/9 still produced zero measurements: existing bilateral `PunchHeightAnalyzer.processSetup` requires both elbows/wrists alongside body landmarks, while far-wrist visibility is missing, yielding `neutral_body_reference_unavailable`. The [target setup blocker and semantic questions](../../validation/punch-analysis/exact-ten-punch-blocker-matrix-2026-11-08.md#minimum-semantic-decisions-still-owned-by-architectureproduct) remain owned by PA/product: bilateral setup requirement versus selected-side retained-video initialization, independently valid impact/view suitability, and conservatively rejecting unsupported side candidates. Do not weaken stability or fabricate missing landmarks to obtain outputs.

Diagnostic execution is delivered; next milestone is semantic review and the smallest explicitly approved implementation/validation scope. Task remains IN PROGRESS with no runtime side/view approval or production change.

## PA retained-video reference direction — 2026-11-08

Architect APPROVED the direction of versioned retained-video reference eligibility decoupled from unused-arm visibility, within existing geometry/target owners. This is not production activation or approval of the provisional side thresholds/view mapping.

Preserve bilateral torso anchors, independently established visible-side/head semantics, actual earlier stable neutral evidence, selected-arm per-metric requirements and persisted reference provenance. Live setup behavior remains unchanged. Missing neutral evidence must stay unavailable; no impact-frame fallback or substitution of image-height calibration for a neutral reference.

Senior's next deliverable is the smallest reuse proposal and diagnostic reference replay for movements 1/9 before implementation approval/activation. Link results to the existing [target corrective pass](../movement-detail-target-height-heavy-reprocessing-corrective-pass.md) and [Geometry Core v2](../landmark-geometry-core-v2-spec.md); do not create a broad new subsystem task. Task remains IN PROGRESS; unsupported side/view and stable-window cases retain their explicit blockers.

## User-directed side implementation — 2026-11-21

PA relayed Lasse's authoritative new side-score specification and dispatched Senior for implementation and baseline/after replay. The existing [side owner](../four-limb-motion-characterizer-requirement-v1.md#current-user-specification--2026-11-21) holds the exact algorithm/acceptance requirements; do not duplicate a new feature task.

Current next milestone: implementation and tests, normal Android downstream consumption, and exact-source before/after evidence with contributions/uncertainty, including crossing, missing-arm support, repeated sides and return-to-guard contamination over unchanged detected windows. Await Senior evidence and Architect implementation review. The earlier prohibition on paired inference and episode-opening primary approach is superseded for this requirement; historical analysis outputs must not be reinterpreted silently. Impact/view suitability and reference/calibration constraints remain open and unchanged. Preserve existing dirty documentation/diagnostic changes; no completion or activation claim yet.

## Preliminary paired-side replay — 2026-11-21

PA relays that Senior's private candidate replay matches all ten review labels with unchanged windows, including previously wrong movements 2/4/6/8 and ambiguous movement 10. This is preliminary diagnostic evidence, not verification that normal production analysis resolves all ten correctly. Processor integration and tests are pending; BA has not independently replayed the candidate.

Architect approved bounded implementation validation with exact 3D filtering before projection and reliable, independently supported forward-sign checks. Ear-midpoint fallback is not accepted without evidence. The provisional 0.03 m score deadband and new axis gates are experimental choices, not generally validated accuracy thresholds. No final implementation/push approval has been granted. Next: integrated implementation/tests and axis/order evidence for PA review; impact/view gates remain unchanged and task stays IN PROGRESS.

## PA final-review pass 1 — changes required, 2026-11-21

Architect reports the shared formula, 3D-filter ordering and integration direction align with the requested method, but final approval is withheld. The candidate's ten matching labels used injected segmentation; this does not satisfy fresh native-segmenter replay or demonstrate unchanged boundaries in the normal path.

Senior must address and verify:

- Nonfinite confidence handling.
- Axis coverage must not bridge rejected sign/reference frames.
- Paired results must bind to their source track and canonical geometry.
- Fresh native segmentation with pre-change baseline, followed by a full app test rerun.
- Return-to-guard boundary inspection over the actual detected window; preserve segmentation semantics rather than trim to improve scores.
- Distinguish bilateral evidence support from agreement; two available arms do not automatically corroborate one conclusion.

These are implementation/replay acceptance findings, not a reversal of Lasse's specified method. Next milestone is corrected code and executable evidence for PA re-review. Keep IN PROGRESS; no final push approval, production-fixed claim or impact/view gate change.

## PA side implementation accepted — 2026-11-21

Architect APPROVED the scoped side implementation, commit/push and debug delivery after re-review; prior pass-1 findings are resolved. PA reports native segmentation reproduces ten unchanged intervals, and the new normal Android processor plus Room reopen matches all ten review sides. The separately reproduced pre-change native baseline at `30e17d6` produced nine LEFT results and last movement UNKNOWN. The new method corrects movements 2/4/6/8 and 10 on this source while retaining contributions/versioned criteria.

PA verified local suites: core 522, app 482, with three optional skips and zero failures/errors; private exact replay successes were reported separately. BA records PA's verification and did not rerun those suites. Do not describe optional/private replay coverage as all executed by the ordinary suites.

Scoped side implementation acceptance is achieved; exact commit/APK delivery evidence is next. This supersedes the prior withheld-approval checkpoint, not its historical baseline. It is not a general accuracy claim, impact/view/reference approval or phone UI acceptance. Task 003 remains IN PROGRESS with existing impact/view and neutral-reference blockers and downstream acceptance still open.

## Side correction delivered — 2026-11-21

Senior reports the architect-approved implementation pushed to main at `5ee140bf8b780ff39b66cda14fb8811294b187b5` (`Implement paired-arm punch side resolution`); BA verified that commit and local HEAD. The ordinary punch-side correction is complete as a scoped implementation milestone: exact private replay matches 10/10 reviewed sides through native `TrainingSessionProcessor`/production segmentation, with Room persistence/reload covered. This is source-specific evidence, not general accuracy acceptance.

Senior reports core 522 and app 482 unit-suite results (three optional skips), plus successful debug APK build. BA did not rerun tests; an exact APK hash/install and device UI acceptance are not supplied by this checkpoint. No automatic saved-data reprocessing or impact/view gate activation was included.

003 remains IN PROGRESS for independent activity/view suitability, retained-video neutral-reference requirements and resulting terminal/metric persistence/reopen/phone acceptance. Do not leave the delivered side implementation listed as an unresolved coding blocker, or infer that its completion makes those downstream policies valid.

## Side correction verified on phone — 2026-11-21

Senior reports commit `5ee140bf8b780ff39b66cda14fb8811294b187b5` installed on connected Samsung SM-A556B without clearing data. The exact 15 Sep 2026 ten-punch recording was explicitly reanalyzed through the normal safe UI path using its saved landmark stream. Persisted movement detail views show LEFT, RIGHT, LEFT, RIGHT, LEFT, RIGHT, LEFT, RIGHT, LEFT, RIGHT (10/10).

This completes ordinary side-correction phone acceptance for this device/source, as reported by Senior; BA did not independently operate or inspect the phone. Unlike the earlier delivery-only checkpoint, this checkpoint includes a targeted new analysis; it does not imply automatic bulk reprocessing or rewriting historical results. General accuracy is not established from one recording. Impact/reference/view-policy and downstream metric blockers remain independent; 003 stays IN PROGRESS.

PA relays additional Senior observations: the app stayed running and sampled error logs showed no app failure. The normal UI action was Reanalyze with current pipeline and intentionally created new analysis results; do not claim the database remained byte-unchanged. Durable provenance follow-up requested from Senior: new run ID, retained old-run/history verification, unchanged source/MLS hashes, installed APK hash/identity and exact commit push status. These details remain pending; sampled logs are not proof that the separately tracked player crash is fixed.

## Side phone provenance completed — 2026-11-21

Senior supplied the requested durable completion evidence; BA records it as Senior-verified rather than independently querying the phone:

- New current run `3d4044f3-1a76-434f-bfd6-e2272b64eb68`: COMPLETED REANALYSIS, Heavy track `4826f011-499f-4a12-b36a-d9b4b208f6a5`, segmenter `activity_qom_supported_world_v2`, ten movements and ten `four_limb_activity_motion` v3 results.
- History: 20 run rows retained; prior exact run `9a733754-e41d-414a-be2c-a401e2909dec` remains COMPLETED/non-current with ten movements. Database changed intentionally to retain the new analysis, not overwritten history.
- Source MP4 SHA-256 unchanged: `f87c0c3e93ef1af5d171353028ba47f34d20e9da665aae2062c66b57b13f1e35`.
- Source MLS SHA-256 unchanged: `39091b93bba46594ca7ff2a752e86ed2eb762e99d7112ae0cdb4bd54fd2fff54`.
- Installed APK SHA-256 matches local build: `fe559a7fa33e676e2be7d8c7d50e0dddf480053f5afd4b7e06e9eb5969503c69`. HEAD and origin/main are reported at `5ee140bf8b780ff39b66cda14fb8811294b187b5`.
- Reopen/UI path: Performance > Recordings > 15 Sep 2026 20:19 > reopened recording > each Movement detail header; persisted sequence LEFT/RIGHT/LEFT/RIGHT/LEFT/RIGHT/LEFT/RIGHT/LEFT/RIGHT.

No additional reanalysis was performed to supply this checkpoint. The provenance follow-up is satisfied; scoped side acceptance remains source/device-specific. 003 stays IN PROGRESS for independent impact/view/reference work and downstream metric acceptance. This does not close the separately owned player crash or establish general accuracy.

PA subsequently reports read-only verification of the same commit/APK identities, current/retained runs, unchanged source hashes and reopened side sequence, confirming this scoped delivery evidence without further reanalysis.

## Recordings debug side presentation — 2026-11-21

PA relays a small current user fix assigned to Senior: the movement-row Active arm debug label derives side from the first target measurement, yielding UNKNOWN when measurements are absent despite saved v3 side resolution. The existing [Recording Results task](../recording-results-page-v1.md#active-arm-debug-presentation-follow-up--2026-11-21) owns correction/acceptance. Reuse MovementDetail's authoritative persisted-side presentation; no analyzer/impact policy change or reanalysis. Implementation/review pending; this does not invalidate the accepted side-analysis result or close downstream metric blockers.

PA subsequently approved this presentation correction after source/test review (485 test entries, zero failures/errors, three optional skips). Senior reports installed-build device agreement for movements 1–10 without reanalysis. Shared resolution preserves unknown motion and allows historical target fallback only when motion is absent. See the existing owner for APK identity and verification attribution. Await scoped commit/push before recording delivery; independent impact/reference/view-policy blockers remain open.

**Delivered — 2026-11-21:** Active arm presentation follow-up is DONE in approved commit `36735d8cc83a6f230e1fbbfd057ab582d96cc381`, reported pushed to main by Senior and verified locally by BA. App tests/debug build and ten-row phone agreement without reanalysis are recorded in the existing Recording Results owner. 003 remains IN PROGRESS for independent impact/view/reference and downstream measurement acceptance.

## User selected unavailable Key results — 2026-11-21

Lasse identified Key results as the next unavailable-output issue. BA dispatched Senior to inspect exact current labels and persisted blocker reasons, distinguish gated existing metrics from unimplemented placeholders, and deliver the smallest corrective proposal. The [Movement Detail owner](../movement-detail-page-v1.md#current-user-focus-unavailable-key-results--2026-11-21) holds the presentation follow-up; impact/reference/target owners retain their analytical requirements. Continue already-authorized corrections where semantics are established; PA must resolve outstanding eligibility/reference policy before changes. No fabricated results, automatic unsupported-profile activation or silently added analyzers. Results pending; 003 remains IN PROGRESS.

PA approved presentation approach A: hide unimplemented Max speed/Max path deviation/Hikite max speed placeholders; retain Target height and explain the selected persisted abstention reason in plain language, preserving valid/legacy output. Implementation pending. Future metric provider/contract work remains in the Movement Detail owner, not silently closed. This is UI cleanup only; no gate changes or reanalysis, and independent end-to-end analysis blockers remain open.

Senior now reports the three-file presentation implementation complete locally, with 487 app tests (three optional skips) and debug build passing. Current-run gate evidence and ownership are recorded in the [Movement Detail checkpoint](../movement-detail-page-v1.md#key-results-implementation-checkpoint--2026-11-21): Target height remains blocked by `UNSUPPORTED_ANALYSIS_PROFILE`, not by the removed placeholder rows. Phone disconnected before final-build installation; device UI verification and delivery identity are pending. No reanalysis requested/needed; 003 remains IN PROGRESS.

PA subsequently APPROVED the exact implementation diff and verified 487 test entries, zero failures/errors and three skips. Scoped three-file commit/push requested. Next: record commit/artifact delivery, then final phone UI verification after reconnect. Independent analytical blockers remain open.

Key results code delivery now recorded at `d738bdddf6b1276135991bc46f5ef6d9bb2d2d04`, reported pushed to main by Senior and verified locally by BA. No reanalysis performed. Phone installation/screen verification is still pending reconnect; underlying impact/view/reference and future metric scope remain open. See Movement Detail owner for verification/acceptance history.

**Key results presentation follow-up DONE — 2026-11-21:** Senior verified final build on Samsung SM-A556B: retained LEFT header, Target height unavailable with the explicit activity/camera-view explanation, and no unimplemented speed/path/hikite rows. Installed/local APK identities match; current run/count/latest timestamp unchanged, proving no new run for this update. Exact artifact/run evidence is recorded in the existing Movement Detail owner. 003 remains IN PROGRESS for underlying impact/view/reference and downstream analytical outputs; presentation cleanup does not establish those capabilities.

## Renewed Key results request — 2026-11-21

Lasse said “try again” after presentation cleanup still left Target height unavailable. BA dispatched Senior to recheck current phone/run evidence and pursue the substantive analytical blocker with PA; interpretation is to obtain valid supported output, not merely repeat the explanation. If current inspection reveals a display/install mismatch, address that evidence instead.

Resume existing impact/view eligibility and retained-video neutral-reference work, using the prior approved direction and exact diagnostic evidence. First report the current selected persisted reason and smallest evidence-backed corrective action. Do not rerun an unchanged pipeline, bypass unsupported profiles, fabricate measurements or silently expand to unimplemented analyzers. Apply approved semantic corrections with preserved source/history before any meaningful reanalysis. UI cleanup remains complete; analytical003 remains IN PROGRESS.

PA confirmed this assignment: Senior must deliver concrete profile/reference criteria and ownership plus an exact diagnostic replay qualification/abstention matrix, and may advance already-decided shared reference separation where unambiguous. PA will resolve specific semantic proposals against the evidence. No extra cosmetic scope, forced 10/10 output, gate bypass, unchanged reanalysis or new placeholders is authorized.

### Current phone blocker confirmed — 2026-11-21

Senior's read-only recheck confirms session `6d90a774-ebcb-441c-afdc-e9f36235a64b` still selects COMPLETED run `fec07ba7-3914-4c9e-9e9b-ceefac6b1690`, track `4826f011-499f-4a12-b36a-d9b4b208f6a5`. All ten `four_limb_activity_motion` v3 rows are PARTIAL / `UNSUPPORTED_ANALYSIS_PROFILE`; all ten `straight_punch_target` v2 rows are ABSTAINED for the same reason; measurement row count is zero. No reanalysis or user-data change; temporary database copy removed. BA records Senior's observation, not an independent phone query.

Next work is the smallest retained-video reference reuse design and diagnostic M1/M9 replay under prior PA constraints: real earlier stable neutral evidence, bilateral torso anchors, independently established visible-side/head semantics, selected-arm metric requirements and persisted provenance. Unused-arm visibility may be decoupled; live behavior stays unchanged and no impact-frame/calibration fallback is allowed. Production view activation remains separate, pending PA's exact policy decision. This confirms an analytical gate, not an installation/display mismatch.

### Neutral-window diagnostic review — 2026-11-21

Senior reported a 1.5-second neutral jitter failure. PA identified a possible window-enumeration error: pruning spans greater than the duration and rejecting spans shorter than it may admit only exact 1.5-second spans, undercounting windows with irregular timestamps. This is a diagnostic concern, not a confirmed production defect or final recording-ineligibility finding.

PA authorized investigation only: correct/test window enumeration, rerun the baseline, then run a bounded shorter-duration evidence sweep with other thresholds held fixed. Current recording ineligibility remains provisional until the corrected probe is reviewed. No production view/reference relaxation, output guarantee or new resolver groundwork is authorized before evidence supports the semantics. Senior owns the correction/evidence; 003 stays IN PROGRESS.

### Corrected retained-reference probe — 2026-11-21

Senior reports the diagnostic enumeration bug fixed, with a nonuniform-timestamp regression test. Corrected exact replay finds no valid neutral reference for M1 at 1.5, 1.2, 1.0, 0.75 or 0.5 seconds. M9 has four numeric 0.5-second candidates under unchanged 0.025 jitter, but direct retained-video inspection identifies all four as extended-punch/terminal/return spans overlapping M3 or M7, not neutral guard evidence. Numeric stability alone does not establish a neutral reference. BA records Senior's replay/video findings without independently rerunning or viewing the personal recording.

The phone's only other available recording reportedly lacks a source landmark track and activity/view metadata, so it is not independent replay evidence for this decision. No phone reanalysis or data mutation occurred.

Senior recommends retaining 1.5-second/0.025 semantics and this recording's unavailable result, with no production `ImpactViewApproval`; actual future output would require capture-time neutral setup evidence with provenance. This is a recommendation awaiting PA disposition, not authorization for a new capture feature or a claim that neutral evidence alone grants view suitability. No shorter duration is supported by this probe. Next: PA decision on corrected evidence and remaining actionable scope; 003 remains IN PROGRESS.

**PA disposition — 2026-11-21:** Architect approved closing the scoped corrected-probe investigation with no production change. Full 1.5-second baseline and prespecified shorter windows found no independently confirmed neutral reference; only one replayable labelled source is available, so no general policy conclusion follows. Target output remains blocked and 003 stays IN PROGRESS. Senior must retain diagnostics in the existing validation owner and propose the smallest integration reusing/persisting existing live neutral setup evidence, independently of view eligibility approval. No dormant resolver, threshold relaxation or new profile is authorized.

## Future neutral-evidence proposal — 2026-11-21

Senior reports corrected-probe close-out evidence retained in the existing validation owner and a four-slice implementation plan sent to PA. Proposed smallest path: add a live neutral hold after Record & Analyze starts recording, using existing `PunchHeightAnalyzer` setup/initialization rules; persist accepted recording-relative interval plus rule/quality/camera provenance as an append-only `SessionEvent`; retrospective analysis recomputes `BodyReference` from the exact canonical Heavy track over that interval. Do not transfer live coordinates across potentially different preview/video crops. Neutral evidence remains independent of `ImpactViewApproval`.

Senior's discovery identifies gaps: assisted capture currently uses `previewOnly=true` without pose frames; no neutral stage or typed event; no pipeline reference-interval provider. These are reported findings, not BA-independent source verification. Plan review is pending; this checkpoint does not authorize implementation. Senior requested review of a two-file diagnostic/evidence commit only. No production source or phone data change occurred. Existing capture, geometry/reference and target owners retain their scope; no duplicate subsystem task is created.

**PA design review — 2026-11-21:** two-file diagnostic preservation approved after full diff review and verification of four tests with no skips/failures/errors, including exact replay; Senior must correct one documentation wording issue before scoped commit. Commit delivery remains pending. Future neutral-evidence direction approved for design only, not implementation or a mandatory recording hold. Senior must define neutral-posture semantics (stability alone is insufficient), timestamp/geometry/lifecycle/event identity, feasibility and hold/skip/failure UX, and compare live validation reuse against simpler recorded-video validation of an explicit neutral interval before choosing machinery. Activity/view approval remains independent; 003 stays open.

**Diagnostic close-out delivered — 2026-11-21:** Senior reports the approved two-file change pushed to main at corrected commit identity `96f00de432ac7084502c51440779e946c0e19ba1` (`Document retained neutral reference probe`); BA verified the corrected commit locally. Focused diagnostic class passed per Senior; no production source change, phone reanalysis or phone data change. Unrelated BA/project-knowledge edits remained unstaged.

Future neutral-evidence integration remains a proposal: compare a live mandatory hold against a user-marked postcapture neutral interval before a product decision. Neither numeric stability nor a persisted interval/event establishes neutral-posture meaning or `ImpactViewApproval` by itself. Design must satisfy the preceding posture/provenance/lifecycle/UX requirements. 003 stays IN PROGRESS; this closes only diagnostic preservation.

## Explicit manual-view diagnostic authorization — 2026-11-21

Lasse explicitly confirms side view for session `6d90a774-ebcb-441c-afdc-e9f36235a64b`, activity `alternating-straight-punch-v1`, example movement `a3ea4ebe-c2d9-474c-a910-b60754885f82`, and requests actual phone reanalysis. This supersedes earlier no-view-input restrictions for this scoped debug/manual diagnostic, not general camera-view policy or live verification.

Senior dispatched to verify the missing input in current checkout, reuse an existing debug action or add the requested debug-only “Confirm side view for this recording and reanalyze” control, persist `operator_post_capture_view_confirmation` bound to recording/assigned plan, validate and consume it as `ImpactViewApproval` through the real pipeline into a fresh selected run. Use a supported profile if present, otherwise an explicitly diagnostic straight-punch side-view profile confined to the debug path. Distinguish manual confirmation from live-landmark verification in reports.

User authorizes in-place installation and normal reanalysis for this diagnostic. Preserve source/MLS/history and stop before uninstall/data clear on a signing mismatch. Keep side, terminal stability, representative-frame and geometry/calibration checks intact. Success is confirmation saved/consumed and actual downstream outcomes, including legitimate abstention or `neutral_body_reference_unavailable`, not forced target measurements.

Required phone report: installed package/build/update compatibility, new run and retained history, example movement impact status/exact reason/representative timestamp, target measurements or next blocker, and session counts reaching impact evaluation/completing/producing target output. BA verified ADB authorized Samsung SM-A556B `RZCY10A22MW`; implementation/device result pending.

BA source inspection confirmed current recorded-session `TrainingSessionProcessor` omits `viewApproval`; `MovementMotionAnalysis` maps absent scoped approval to `UNSUPPORTED_ANALYSIS_PROFILE`. Installed package `dk.lasse.karatecliprecorder` is debuggable, version 0.1.0/code 1. Senior's initial confirmation-store/real-processor tests passed (three focused tests), with recording+plan/version validation and fresh-run behavior. PA pre-install review requires diagnostic labeling even for successful results, exclusion from ordinary history/baseline measurements, intact machine-reason explanation and actual plan/version mismatch tests. Senior is correcting these findings; no phone run result or signing compatibility conclusion is claimed yet.

**Pre-execution review and stale-run correction — 2026-11-21:** PA approved the diagnostic labels, history/baseline exclusion, reason mapping and plan/version checks. Initial phone action stopped before confirmation or run creation because historical `PROCESSING` rows were mistaken for live work. Senior added session reservations for queued/executing work, retaining historical rows and preventing overlapping processing; PA independently verified 29 focused tests and approved the bounded correction. Full build, in-place install and actual diagnostic outcome remain pending. BA made no implementation changes during this correction; Senior retains execution ownership.

**Manual-view phone diagnostic delivered — 2026-11-21:** Senior and PA report completed current run `b2e23d77-4a1a-4e00-9799-fae0e5cfc242`, consuming confirmation event `7efe1e3e-565a-4d10-9857-ab4de95921ac` for the exact recording and assigned plan. Run count increased 24 to 25; prior runs, stale historical rows and old movement retained; MP4 and MLS hashes unchanged. In-place debug update succeeded. Ten movements reached impact analysis: M1/M9 completed with representative timestamps 1,834,000/10,737,000 microseconds; M3/M5/M7 abstained `NO_STABLE_TERMINAL_WINDOW`; M2/M4/M6/M8/M10 abstained `LIMB_EVIDENCE_UNAVAILABLE`. M1/M9 target analysis then abstained `neutral_body_reference_unavailable`; all ten targets abstained and zero measurements were produced. The original unsupported-profile blocker was removed by supplied input, without altering downstream checks. Diagnostic label remains after cold reopen with Developer Mode off, while debug action is hidden. Full suite reported 495 tests, zero failures/errors, three skips. This completes the authorized diagnostic, not production camera-view validation or task003's remaining target-output work. Exact result matrix belongs in the existing validation report.

## Remaining blocker investigation authorized — 2026-11-21

Lasse requests work on all remaining groups from diagnostic run `b2e23d77-4a1a-4e00-9799-fae0e5cfc242`: two neutral-reference failures (M1/M9), three terminal-stability failures (M3/M5/M7), five limb-evidence failures (M2/M4/M6/M8/M10). Senior owns evidence investigation, scoped corrections, tests and subsequent meaningful phone reanalysis; PA reviews material semantics. BA prioritizes exact selected-arm limb predicates first, terminal-window/boundary evidence second, and usable neutral-reference evidence third. This is investigative order, not a claim that any failure is a defect or permission to weaken quality requirements. Inspect missing observations versus integration defects, preserve history and operator-confirmation provenance, and avoid a new processing run without meaningful changed input or implementation. Existing task003 remains the owner; no duplicate task.

**New horizontal target-height scope — 2026-11-21:** PA relayed explicit user instruction to implement a new versioned frame-local horizontal target-height metric without an earlier-neutral or unused-arm prerequisite, preserving historical angular semantics and current impact/view gates. Exact requirements are recorded in the existing target-height corrective-pass owner and decision log. Senior owns implementation and phone verification for M1/M9; the separate sampling-rate correction remains design/evidence work. No duplicate task created.

**Shoulder-origin angular refinement — 2026-11-21:** PA relayed explicit user requirement to make current-frame shoulder-origin signed angular error the primary target-height UI, using forward reach-circle intersections with existing horizontal guides. Existing corrective-pass owner and decision log now record this supersession. Historical v3 pixel semantics/delivery evidence and old neutral-angular results remain distinct. Senior owns implementation/phone verification; no new task or parallel measurement system. Sampling-rate correction remains separate.

**Offline side-view segmentation handoff — 2026-11-22:** PA relayed user request to implement `karate_qom_team_handoff.zip`. Existing segmenter requirements/validation and decision log now own scoped versioned opt-in implementation and evidence gates; Senior owns source/phone.625frames/21candidates/20accepted vs old17 are archive claims pending reproduction, not ground truth. No duplicate task. Angular v4 remains pending phone acceptance based on latest reported blocked verification; source/test approval alone does not close it. Sampling-rate correction and independent impact-side-target eligibility retain their separate scope within003.

**QoM activation clarification — 2026-11-22:** PA approved explicit persisted recorded processing-plan selection only; no automatic alternating-punch activation and no default change. Option/configuration/version and selection provenance must be retained. Selection expresses segmentation intent, not downstream camera-view approval. Existing segmentation owner and decision log updated; implementation and fixture acceptance remain pending.

**Unresolved-point exclusion decision — 2026-11-22:** Explicit user decision relayed by PA recorded in existing segmenter requirements, validation and decision log. Original replay retained unchanged; new detector excludes unrepaired points with provenance and no cross-gap motion. Three endpoint differences supersede full parity; other37 remain expected equal. Fixture count reported validated, timing/generalization OPEN;2.434–2.901s gap is not observed stillness. Implementation/full suites pending review.

**Offline segmenter implementation reviewed — 2026-11-22:** PA approved code/graph checkpoint and544core+512app tests (zero failures/errors, seven app skips); Senior reports private fixture20/20 with three approved deltas. One normal20-punch phone verification authorized; device acceptance pending. Timing/generalization remain OPEN. Diagnostic source is RECOMPUTED_FROM_RETAINED_RUN from SHA-verified MLS/version-fixed config, not persisted derived-point evidence. No default switch or downstream approval.

**Offline segmenter phone checkpoint verified — 2026-11-22:** Per Senior evidence, run `9a16c7e0-2a8e-444f-8eea-ea3ea7e4b721` on20-punch session reopened with20detected/20planned and approved windows; previous2runs/raw MLS preserved. Exact hashes and attribution recorded in existing segmentation owners. Movement2 has EVIDENCE_GAP_WITHOUT_STOP_EVIDENCE. Scoped phone checkpoint complete, not independent frame-timing/generalization acceptance. No claim of direct phone per-point diagnostic inspection; defaults/downstream gates unchanged.

**Reopened segmenter audit — CHANGES REQUIRED, 2026-11-22:** PA withdrew implementation acceptance for five confirmed issues: padded-vs-logical UI time, inconsistent half-open ownership, reference smoothing across long gaps, missing QoM becoming numeric-zero through serialization/impact, and absent signed-X diagnostics. Existing segmenter owners now list fixes/acceptance gates; Senior owns repairs/tests before new phone run. Preserve factual20-movement phone/replay evidence, but do not treat it as sufficient implementation acceptance. Immutable/enforced version-fixed configuration remains acceptable; parameter snapshot not mandated. Timing/generalization stay OPEN.

**Corrective versioning — 2026-11-22:** PA requires detector v2 and retained immutable v1 run/recomputed-diagnostic semantics; rejected in-place v1 correction despite unchanged fixture boundaries. Existing minimal dispatch/append-only selection only. Senior proposes ImpactAnalyzer v3 / MovementMotionAnalysis v4 for downstream missing-evidence changes; historical rows readable, review pending. CHANGES REQUIRED unchanged.

**Corrective v2 implementation approved; phone pending — 2026-11-22:** PA reviewed fixes and548core+515app tests (zero failures/errors, six app skips). Prior CHANGES REQUIRED code findings addressed; one fresh-build normal phone run authorized. Acceptance requires actual labels/version/missing-evidence checks plus20windows/deltas, not count alone. Preserve v1/history/comparison. Devicev2 acceptance pending; independent timing/generalization remain OPEN.
