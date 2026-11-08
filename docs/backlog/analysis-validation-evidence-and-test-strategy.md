# Establish trustworthy karate-analysis validation evidence

Status: OPEN

Created: 2026-11-07 from Lasse's supplied weekly review. Request: turn the review into actionable backlog work. Not selected ahead of the active Lite/Heavy prototype. Suggested priority: high validation follow-up; execution order remains Lasse's decision.

Ownership: Senior Developer investigates tests and implements agreed validation changes; Project Architect resolves event semantics and evidence ownership; Lasse or an agreed karate reviewer approves human labels; Business Analyst tracks scope and acceptance.

## Outcome

Distinguish algorithm consistency from demonstrated karate accuracy. Preserve useful contract/replay coverage while establishing a small reviewed acceptance dataset and an unambiguous architectural description. Do not tune movement counts to recording names or present provisional annotations as approved truth.

## Evidence and limitations at intake

- OBSERVED: [Task 5 report](../validation/task5/README.md) calls labels PROPOSED, not human-approved ground truth, with approximately five-frame uncertainty. It documents three additional complete-looking punches and a truncated arm-lowering movement after the numbered subset. These are report claims, not video observations independently verified in this intake.
- OBSERVED: [QoM implementation prompt](../prompts/implement-qom-motion-segmenter.md) says known ten-movement recordings should retain ten isolated windows. [Current detector documentation](../motion-detector-math.md) describes multiple recordings and later v2 acceptance. UNKNOWN: whether the exact-ten requirement and Task 5 claim refer to identical source media, trims and fixtures. Establish identity before declaring a count contradiction or changing expectations.
- OBSERVED: [Task 5G report](../validation/task5/coherence/task5g-kinematics-report.md) uses ground-truth accuracy language, while [Task 5H](../validation/task5/filtered-kinematics/task5h-filtered-kinematics-report.md) says review intervals are not independently adjudicated. Trace label provenance and reconcile these claims.
- The supplied review's latest-commit claim is stale for this checkout: intake HEAD is `94e7454`, after `e8a72de` and `394ca7d`. Its historical branch/date window was not re-audited.
- No application, Gradle, MediaPipe or recording replay ran for this intake. Process-death recovery concerns and external release/version claims remain unverified. Creating this task approves investigation, not new domain semantics, production activation or an SDK upgrade.

## Work sequence and deliverables

### 1. Audit test roles and fixture identity

Inventory the relevant segmentation, side, impact and retained-plot tests using existing documentation/test conventions. Classify assertions as contract, regression replay or labelled acceptance; a test can contain more than one type if the distinction is explicit.

- Contract: timestamps, geometry/frame identity, confidence/unknown evidence, forbidden gaps, rendering and historical provenance. Keep strict synthetic checks.
- Regression: exact outputs for an identified fixture/version. State that these detect changes, not necessarily accuracy.
- Labelled acceptance: compare against independently reviewed, versioned annotations with stated uncertainty and metric eligibility.

Map media identity/hash, trim, source PTS/frame correspondence, MLS/model provenance and evaluated subset for every disputed count. Document whether the apparent contradiction is real or involves different recordings. Repair misleading specifications/assertions at their authoritative owners; do not substitute fourteen for ten or delete useful replay tests without evidence. Account for missed, extra, merged and split movements and their boundaries.

### 2. Approve annotation semantics and a small dataset

Start from retained Task 5 assets; preserve original proposals and reviewer history. Proposed scope is 20–30 punches spanning front/side views, 30/60 FPS, clean tracking and occlusion. Dataset size and coverage are proposals pending review, not already-approved acceptance limits. Keep paired frame-rate variants traceable to their common source, not falsely independent examples.

Label generic QoM movement separately from punch/technique identity. Cover the entire declared evaluation interval, including non-punch motion and quiet/unknown intervals as negative/control cases, not just selected punches. Legitimate non-punch movement is not automatically a QoM false positive; detections or missed movements outside the selected punches must remain visible in the evaluation. Predeclare matching and counting rules for missed, extra, merged and split outcomes before scoring.

For each selected movement record source identity and timestamps, annotation status/reviewer/version, uncertainty, onset, terminal arrival, stable-interval start/end, representative observed measurement frame, punching side, observed camera/view and visibility, unobservable wrist/elbow intervals and per-metric eligibility/reason. An unobservable event may remain unknown; do not require a precise label by guessing.

Observed camera/view labels are separate from approved activity/view measurement suitability. Record an expert suitability recommendation separately for the owning policy/Architect decision. Human annotation approval does not create production `ImpactViewApproval`. Keep unknown ground truth distinct from supported/unsupported production policy.

Resolve with Architect and human reviewer which event each consumer calls the end of a punch. Preserve distinct movement boundary, terminal arrival, stable interval and measurement frame; do not collapse them into one timestamp. Existing [ImpactAnalyzer](impact-analyzer-requirement-v1.md) semantics are evidence to review, not permission to redefine events here.

### 3. Run and report labelled acceptance

Use existing replay/test tooling where possible. Report boundary error, side correctness, terminal-event error, abstention behavior and result stability across explicitly identified processing/model versions. Include denominators, unsupported cases and annotation uncertainty, not only aggregate scores. Keep acceptance examples distinguishable from threshold-tuning examples.

Store source-recording groups for the tuning/evaluation split: every trim and FPS variant of one recording belongs in the same group, preventing leakage between tuning and evaluation. Predeclare matching, tolerances and eligibility before scoring. Report error alongside resolved/eligible coverage, abstentions on observable eligible cases and correct abstentions on unobservable/unsupported cases. Never drop abstentions from denominators so an always-abstaining implementation appears accurate. Report unknown labels separately rather than treating them as policy-unsupported or silently excluding their existence.

Two source frames for clean terminal events is an initial engineering proposal, not an accepted target; record equivalent source-time error at each cadence. Agree tolerances after label uncertainty is known. Occluded/unsupported cases should reward justified abstention rather than precise-looking guesses. Record commands, build/commit, fixtures, actual results and remaining failures; do not rewrite labels to match output.

### 4. Clarify architecture scope and evidence eligibility

Review [docs/architecture.md](../architecture.md) against [current architecture policy](../../project-knowledge/ARCHITECTURE.md). Label the actual scope of Python/CLI descriptions, preserve useful tools, and clearly describe Android master recording, separate landmark tracks, processing runs, retrospective segmentation and per-movement results. Avoid declaring one physical track mandatory: the approved [Lite/Heavy prototype](prioritized/002-lite-heavy-processing-planning.md) preserves separate evidence sources and provenance.

Assess existing shared evidence-window, continuity, impact and activity/view contracts before proposing additional machinery. Define per-metric available/unavailable/unsupported reasons with examples, including occlusion and forced/truncated boundaries. Architect decides whether existing contracts suffice and where any missing rule belongs. No global movement-quality score, universal forced-boundary rejection, new service, or parallel analyzer is authorized by this task. Product UI consumes shared results.

## Related checks to triage, not silently bundle

- Processing resilience: inspect current execution ownership, then test interruption/process death and restart on a suitable device. Persisted status alone does not demonstrate resumption. Record observed recovery, duplicates, partial publication and history preservation. Link findings to existing [queue](queue-manager-tray-v1.md), [capture](record-and-analyze-assisted-capture.md) or processing tasks; create a defect only if evidence establishes one. This is a validation target, not a confirmed failure.
- MediaPipe: the supplied review's release/version statements have not been verified. If separately prioritized, verify official Android artifact availability and compatibility, then compare schema, landmark parity, timing, memory and failures on identical videos. No upgrade or accuracy claim follows from a release number. This optional experiment is not a completion dependency of the validation task.
- The quoted scheduled-task question is source text, not Lasse's instruction to enable, disable or change an automation. No scheduling changes are part of this task.

## Existing owners and dependencies

- [Segmentation integration/validation](segmenter-integration-v1-requirements.md): consumes corrected fixture scope and boundary evidence; its one-recording acceptance does not certify all Task 5 material.
- [Four-limb and P1 side correctness](four-limb-motion-characterizer-requirement-v1.md): owns the production side fix; use this dataset to evaluate it, without duplicating that defect.
- [Impact/view suitability](impact-analyzer-requirement-v1.md): owns production profile approval. Dataset labels or this task's completion do not automatically enable output.
- [Geometry Core v2](landmark-geometry-core-v2-spec.md): existing geometry/evidence ownership; reuse rather than introduce competing mathematics.
- [Movement Detail](movement-detail-page-v1.md): owns plot/device readability acceptance, independent of mathematical accuracy.

## Done criteria

1. Disputed fixture identities/subsets are mapped and count/ground-truth claims reconciled with evidence; no assumed total from a recording name.
2. Relevant assertions have clear contract/regression/acceptance roles and link to fixture/version provenance.
3. A reviewer-approved dataset and event/eligibility definitions exist, with adjudicated sample labels and explicit uncertainty/status. Unresolved labels are excluded from accuracy claims but reported separately. Final dataset size/coverage, source-recording split, matching rules and tolerances are recorded before scoring; negative/control coverage is included.
4. Labelled acceptance runs produce a reproducible report against agreed criteria, including failures, abstention denominators, resolved/eligible coverage and uncertainty. This task may finish with failing product acceptance results if the validation deliverables are complete and failures link to owned defects. DONE means validation delivery complete, not product acceptance or profile activation.
5. Architecture descriptions have explicit scope; Architect's evidence-eligibility decision is recorded at its authoritative owner and any implementation follow-up is clearly tracked.
6. Processing-resilience concern is evidence-triaged into an existing owner or a justified follow-up. No unverified SDK research or device result is presented as fact.

Initial next action when scheduled: fixture/assertion audit and a compact annotation sample for human/Architect review. Do not begin by adding a validation framework or changing production algorithms.

## Architectural review

2026-11-07: Lasse requested Project Architect review. Architect task `01a1062b-9248-7d32-adc3-190301a735cd` returned CHANGES REQUIRED: bounded clarifications, with overall scope/ownership/sequence sound. BA incorporated the requested generic-movement versus technique distinction, full-interval negative/control coverage and matching rules, observed-view versus approved-suitability separation, abstention/coverage denominators, grouped tuning/evaluation split and validation-delivery versus product-acceptance distinction. Confirmation sent to Architect; this records addressed findings, not a new approval or implementation start. Active Lite/Heavy prototype priority is unchanged.

User-owned decisions still open: execution priority, human-label approver, intended initial activity/view coverage and acceptable outcome tradeoffs. Dataset size/tolerances should follow sample uncertainty and coverage evidence. These decisions do not prevent the discoverable fixture audit when this task is scheduled; no interview is required merely to revise the task.

2026-11-07 re-review: Project Architect read the revised task and APPROVED its backlog scope and validation strategy; all requested P2/P3 clarifications are addressed, with no remaining blocking architectural findings. Status remains OPEN and priority unchanged. Execution still requires the reproducible identity/assertion audit, adjudicated labels, declared evaluation protocol and report specified above. This approval does not assert tests ran, endorse numeric thresholds, approve production profiles or authorize execution ahead of Lite/Heavy. User-owned decisions remain deferred as described.
