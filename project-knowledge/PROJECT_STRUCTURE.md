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

### Android training/evidence persistence

**Status: OBSERVED — verified 2026-09-23**

- Maintained data-model reference: [Room database logical model](../docs/app-training-database-logical-model.md). This owns the schema overview, entity catalog, relationships, and repository invariant descriptions; other documents link to it.
- Implementation: `android/KarateClipRecorder/app/src/main/java/dk/lasse/karatecliprecorder/training/`. `KarateTrainingDatabase.kt` registers the database and migrations; `TrainingRows.kt` defines Room constraints; `TrainingModels.kt` defines persisted fields; `TrainingDao.kt` defines queries; `TrainingRepository.kt` provides transactional operations and validation.
- Exported schemas: `android/KarateClipRecorder/app/schemas/dk.lasse.karatecliprecorder.training.KarateTrainingDatabase/`. These and the source provide recheckable implementation evidence for the model.
- These classes currently live in the Android `app` module. Their location and database relationships do not establish shared-platform persistence ownership or domain aggregate boundaries.

## Material conflicts

_Record observed implementation that conflicts with a `DECIDED` product, domain, or architecture direction. Do not resolve the conflict by rewriting the decision._
