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
- `app/.../training/StraightPunchMovementAdapter.kt` accepts an `ImpactAnalysisResult`: completed results select the stable representative MLS sample, while impact abstention is preserved. Existing production callers currently omit this input; production activation is blocked on authoritative image-plane body-scale evidence.

### Android training/evidence persistence

**Status: OBSERVED — verified 2026-09-23**

- Maintained data-model reference: [Room database logical model](../docs/app-training-database-logical-model.md). This owns the schema overview, entity catalog, relationships, and repository invariant descriptions; other documents link to it.
- Implementation: `android/KarateClipRecorder/app/src/main/java/dk/lasse/karatecliprecorder/training/`. `KarateTrainingDatabase.kt` registers the database and migrations; `TrainingRows.kt` defines Room constraints; `TrainingModels.kt` defines persisted fields; `TrainingDao.kt` defines queries; `TrainingRepository.kt` provides transactional operations and validation.
- Exported schemas: `android/KarateClipRecorder/app/schemas/dk.lasse.karatecliprecorder.training.KarateTrainingDatabase/`. These and the source provide recheckable implementation evidence for the model.
- These classes currently live in the Android `app` module. Their location and database relationships do not establish shared-platform persistence ownership or domain aggregate boundaries.

## Material conflicts

_Record observed implementation that conflicts with a `DECIDED` product, domain, or architecture direction. Do not resolve the conflict by rewriting the decision._
