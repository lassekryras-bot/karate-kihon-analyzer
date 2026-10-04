# Project Structure

## Baseline

**Status: UNKNOWN**

This file intentionally contains no preloaded claims about the Karate Analyzer repository.

Add only durable, task-relevant facts verified from the current repository, such as:

- the authoritative location of a capability;
- important module or package responsibilities;
- relevant dependency direction;
- test and build entry points;
- generated versus source-owned files; and
- confirmed runtime or persistence boundaries.

For each non-obvious entry, include enough evidence to recheck it: a repository-relative path, symbol, configuration key, or command. Prefer a compact map over a full file inventory.

## Verified map

### Shared Android analytical core

**Status: OBSERVED — verified 2026-09-24**

- Pure Kotlin shared analysis code is in `android/KarateClipRecorder/karate-analyzer-core/src/main/kotlin/dk/lasse/karateanalyzer/`; its Gradle entry point is `android/KarateClipRecorder/karate-analyzer-core/build.gradle.kts` and the module targets JVM 17.
- Canonical pose/MLS sample models are in `core/PunchHeightModels.kt`; canonical frame geometry and provenance are in `geometry/FrameGeometry.kt` and `geometry/CanonicalGeometry.kt`.
- Production retrospective movement bounds and QoM evidence are owned by `capture/retrospective/RetrospectiveSessionSegmenter.kt` and `capture/qom/`. `RetrospectiveSessionResult.qomTimeline` exposes the production QoM timeline used by downstream analysis.
- Shared aspect-correct geometry and wrapped-angle operations are owned by `geometry/FrameGeometryMath.kt`. The reusable causal median→mean timestamped motion filter is `motion/CausalCoordinateMotionFilter.kt`; production QoM and impact analysis both consume it.
- Terminal-event analysis contracts and implementation are in `impact/ImpactAnalysisModels.kt` and `impact/ImpactAnalyzer.kt`. The result carries authoritative per-sample debug evidence, abstention, calibration, and source/version provenance.
- `app/.../training/StraightPunchMovementAdapter.kt` accepts an `ImpactAnalysisResult`: completed results select the stable representative MLS sample, while impact abstention is preserved. `MovementMotionAnalysis.kt` is the production adapter, but it now requires an explicit activity-scoped `ImpactViewApproval`; current production capture supplies none, so impact-dependent output abstains until a view-suitability policy is accepted.
- `app/.../training/ImpactAnalysisEvidenceCodec.kt` stores the complete impact result, actual profile, explicit view approval, quality diagnostics, structured provenance and component debug evidence inside the append-only `MovementAnalysis.geometryJson` payload. The codec is versioned independently of Kotlin `toString()`.

### Android training/evidence persistence

**Status: OBSERVED — verified 2026-09-23**

- Maintained data-model reference: [Room database logical model](../docs/app-training-database-logical-model.md). This owns the schema overview, entity catalog, relationships, and repository invariant descriptions; other documents link to it.
- Implementation: `android/KarateClipRecorder/app/src/main/java/dk/lasse/karatecliprecorder/training/`. `KarateTrainingDatabase.kt` registers the database and migrations; `TrainingRows.kt` defines Room constraints; `TrainingModels.kt` defines persisted fields; `TrainingDao.kt` defines queries; `TrainingRepository.kt` provides transactional operations and validation.
- Exported schemas: `android/KarateClipRecorder/app/schemas/dk.lasse.karatecliprecorder.training.KarateTrainingDatabase/`. These and the source provide recheckable implementation evidence for the model.
- These classes currently live in the Android `app` module. Their location and database relationships do not establish shared-platform persistence ownership or domain aggregate boundaries.

### Four-limb characterization and side interpretation

**Status: OBSERVED — 2026-10-04, initial implementation; acceptance incomplete**

- Shared core `motion/FourLimbMotionCharacterizer.kt` consumes segmented canonical pose evidence; `FourLimbMotionModels.kt` retains per-limb profiles, trace samples, gaps, configuration and geometry provenance. `AngularMotionEvidence.kt` owns its provisional excursion-based angular hysteresis.
- Shared core `core/ActivityStartingSideResolver.kt` interprets those profiles using an explicit known-activity context and validates indexed alternation. It does not infer the activity or replace ambiguous observations with expected sides. Production processing saves generic limb and observed-side evidence; view approval remains a separate prerequisite for impact analysis.
- Validation state and unresolved measurement-policy details are owned by [four-limb implementation status](../docs/four-limb-motion-implementation-status.md). This initial implementation is not production-accepted.

## Material conflicts

_Record observed implementation that conflicts with a `DECIDED` product, domain, or architecture direction. Do not resolve the conflict by rewriting the decision._

- `geometry/ImageBodyScaleCalibration.kt`: shared manual image-height evidence contract and provider.
- App `training/MovementMotionAnalysis.kt`, `MotionActivityPlans.kt`, `ImageBodyScaleStore.kt`: plan projection, pipeline adapter, retained evidence/calibration events.
- App `recordings/BodyScaleCalibrationDialog.kt`: actual-frame head/floor selection with shared inverse display mapping.
- App `movement/MovementMotionInspection.kt`: persisted limb plots, side/terminal reasons and calibration provenance in normal movement inspection.
