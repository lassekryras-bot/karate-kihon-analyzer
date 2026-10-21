# Room database logical model — schema v10

Status: **code-derived logical model** of the Android Room training/evidence database.

Source of truth used for this model:

- `training/KarateTrainingDatabase.kt` — entity registration, schema version and migrations
- `training/TrainingRows.kt` — Room entities, primary keys, foreign keys and indexes
- `training/TrainingModels.kt` — persisted domain fields and enums
- `training/TrainingDao.kt` — query paths and application-level relationship usage

This document describes the current logical structure. It intentionally distinguishes:

1. **Room-enforced relationships** — declared as foreign keys or uniqueness constraints.
2. **Application-level logical relationships** — IDs are used as references by repository/DAO code but Room does not currently enforce them as foreign keys.

The database is `karate-training.db`, currently **schema version 10**, with **19 Room entities**.

---

## 1. High-level domain model

The model has five main areas:

1. **Identity and capture**
   - user
   - recording session
   - master media
   - landmark evidence

2. **Processing lineage**
   - durable processing queue state
   - immutable processing/reanalysis runs
   - current-run selection

3. **Movement evidence**
   - segmented movement intervals
   - observation/orientation context
   - session events
   - labels

4. **Analysis evidence**
   - analyzer executions
   - measurement results
   - exact landmark-track provenance

5. **Physical reference snapshots**
   - append-only user body measurements
   - append-only calibrations
   - exact references bound to sessions and analyses

The intended evidence chain is:

```text
TrainingUser
  -> RecordingSession
      -> MasterRecording
          -> LandmarkTrack
      -> ProcessingRun
          -> SessionMovement
              -> MovementAnalysis
                  -> MeasurementResult
```

The real model is richer because events, labels, body measurements and calibrations attach alongside that main chain.

---

## 2. Logical ER model

```mermaid
erDiagram
    TrainingUser ||--o{ RecordingSession : owns
    TrainingUser ||--o{ UserBodyMeasurement : has_versions
    TrainingUser ||--o{ UserCalibration : has_versions

    RecordingSession ||--|| MasterRecording : captures
    RecordingSession ||--o| RecordingProcessing : queue_state
    RecordingSession ||--o{ ProcessingRun : processing_history
    RecordingSession ||--o{ SessionMovement : contains
    RecordingSession ||--o{ SessionEvent : timeline
    RecordingSession ||--o{ SessionBodyMeasurement : binds_snapshot

    MasterRecording ||--o{ LandmarkTrack : produces

    LandmarkTrack o|--o{ ProcessingRun : source_track
    LandmarkTrack o|--o{ SessionMovement : segmentation_track
    LandmarkTrack ||--o{ MovementAnalysis : analysis_evidence

    ProcessingRun o|--o{ SessionMovement : produces_logically

    SessionMovement ||--o| ObservationContext : observed_as
    SessionMovement ||--o{ MovementSessionEvent : event_links
    SessionEvent ||--o{ MovementSessionEvent : movement_links

    SessionMovement ||--o{ MovementLabel : label_links
    Label ||--o{ MovementLabel : classifies

    SessionMovement ||--o{ MovementAnalysis : analyzed_by
    MovementAnalysis ||--o{ MeasurementResult : produces

    MovementAnalysis ||--o{ AnalysisBodyMeasurement : uses
    UserBodyMeasurement ||--o{ AnalysisBodyMeasurement : referenced_by

    MovementAnalysis ||--o{ AnalysisCalibration : uses
    UserCalibration ||--o{ AnalysisCalibration : referenced_by

    UserBodyMeasurement ||--o{ SessionBodyMeasurement : captured_for
```

> The `ProcessingRun -> SessionMovement` edge is logically used through `SessionMovement.runId`, but it is **not currently declared as a Room foreign key**.
>
> `RecordingProcessing.sourceLandmarkTrackId` is also a logical provenance reference without a declared Room foreign key.

---

## 3. Entity catalog

### TrainingUser

**Primary key:** `userId`

Purpose: durable identity used by the training/evidence database.

Key fields:

- `userId`
- `createdAtMs`

Relationships:

- 1 -> many `RecordingSession`
- 1 -> many `UserBodyMeasurement`
- 1 -> many `UserCalibration`

Deletion is restricted while dependent recording/reference rows exist.

---

### RecordingSession

**Primary key:** `sessionId`

**Foreign key:** `userId -> TrainingUser.userId`

Purpose: top-level capture/training event.

Important fields include:

- timing: `startedAtMs`, `endedAtMs`
- activity context: `activityKey`, `expectedActivity`, `expectedCategory`
- requested plan context: `expectedRepetitions`, `requestedView`
- capture/cue context: `captureTrigger`, `cueMode`, `audioCuePackageVersionId`
- lifecycle: `state`, `reason`, `interruptionReason`, `captureOutcome`
- external/request lineage: `callerId`, `parentId`

Index:

- `(userId, startedAtMs)`

Logical role: aggregate root for a captured training session.

---

### RecordingProcessing

**Primary key:** `sessionId`

**Foreign key:** `sessionId -> RecordingSession.sessionId` with cascade delete.

Purpose: durable queue/current processing status for the session.

Important fields:

- `queuedAtMs`
- `promotedAtMs`
- `state`
- `phase`
- `manual`
- `planKey`, `planVersion`
- `sourceLandmarkTrackId`
- `segmentationVersion`
- phase durations
- `error`

This is **not processing history**. It is the durable queue/status row for the recording.

Logical-only reference:

- `sourceLandmarkTrackId -> LandmarkTrack.landmarkTrackId`

Room does not currently declare that foreign key.

---

### MasterRecording

**Primary key:** `recordingId`

**Foreign key:** `sessionId -> RecordingSession.sessionId` with cascade delete.

**Unique index:** `sessionId`

Purpose: authoritative captured media attached to the session.

The unique session index makes this effectively **one master recording per recording session**.

Important fields:

- `filePath`
- media timing/dimensions: `durationUs`, `frameRate`, `width`, `height`
- device/camera provenance
- `captureType`, `mimeType`
- `sourceState`
- `rotation`
- `canonicalGeometryJson`

---

### LandmarkTrack

**Primary key:** `landmarkTrackId`

**Foreign key:** `recordingId -> MasterRecording.recordingId` with cascade delete.

Purpose: versioned pose/landmark evidence derived from one master recording.

Important fields:

- pipeline identity: `pipelineKey`, `pipelineVersion`, `configuration`
- `filePath`
- `state`, `sourceState`
- quality/hash information
- `formatId`, `formatVersion`
- `canonicalGeometryJson`

A recording may have multiple landmark tracks, supporting reprocessing and model/pipeline upgrades.

---

### ProcessingRun

**Primary key:** `runId`

**Foreign keys:**

- `sessionId -> RecordingSession.sessionId` with cascade delete
- `sourceLandmarkTrackId -> LandmarkTrack.landmarkTrackId` with `SET_NULL`

Purpose: immutable-ish processing/reanalysis lineage for a session.

Important fields:

- `mode`: initial, reanalysis or landmark reprocess
- `planKey`, `planVersion`
- source landmark track
- segmenter/analyzer versions
- state and timing
- `isCurrent`
- errors

Indexes:

- `(sessionId, createdAtMs)`
- `(sessionId, isCurrent)`
- `sourceLandmarkTrackId`

Important invariant:

- repository code clears previous current runs and publishes one current run transactionally.
- Room does **not** enforce “maximum one current run per session” with a unique constraint.

---

### SessionMovement

**Primary key:** `movementId`

**Foreign keys:**

- `sessionId -> RecordingSession.sessionId` with cascade delete
- `segmentationTrackId -> LandmarkTrack.landmarkTrackId` with deferred `NO_ACTION`

Purpose: detected movement interval within a recording session.

Important fields:

- logical bounds: `startUs`, `endUs`
- retained playback bounds: `playbackStartUs`, `playbackEndUs`
- `state`
- segmenter source/version/confidence
- `segmentationTrackId`
- `runId`
- `analysisFrameUs`

Indexes include:

- `(sessionId, startUs)`
- `segmentationTrackId`
- unique `(movementId, sessionId)`
- `runId`

Important logical-only relationship:

- `runId -> ProcessingRun.runId`

The DAO uses `runId` to retrieve movements for a processing run, but Room does not currently define a foreign key for it.

---

### ObservationContext

**Primary key:** `movementId`

**Foreign key:** `movementId -> SessionMovement.movementId` with cascade delete.

Purpose: persisted observation geometry/context for one movement.

Important fields:

- `view`
- `nearerSide`
- `bodyToCameraDegrees`
- `confidence`
- `evidence`

Because `movementId` is both PK and FK, this is a **0..1 to 1 extension row** for a movement.

---

### SessionEvent

**Primary key:** `sessionEventId`

**Foreign key:** `sessionId -> RecordingSession.sessionId` with cascade delete.

Purpose: timestamped session-level events on the recording timeline.

Important fields:

- `type`
- `timestampUs`
- `data`
- `timingSource`

Index:

- `(sessionId, timestampUs)`

---

### MovementSessionEvent

**Composite primary key:** `(movementId, sessionEventId)`

Purpose: many-to-many association between movement intervals and session events.

It carries `sessionId` so composite foreign keys can enforce that both linked rows belong to the same session.

Composite foreign keys:

- `(movementId, sessionId) -> SessionMovement(movementId, sessionId)`
- `(sessionEventId, sessionId) -> SessionEvent(sessionEventId, sessionId)`

This is a strong ownership-integrity design: an event from one recording session cannot be attached to a movement in another.

---

### Label

**Primary key:** `labelId`

**Unique index:** `machineKey`

Purpose: reusable classification/context vocabulary.

Important fields:

- `machineKey`
- `displayText`
- `category`

Labels are not owned by movements and survive movement deletion.

---

### MovementLabel

**Composite primary key:** `(movementId, labelId)`

Foreign keys:

- movement -> `SessionMovement` with cascade delete
- label -> `Label` with restrict delete

Purpose: many-to-many classification of movement evidence.

Additional field:

- `source`

The source field keeps the distinction between contextual labeling and independently inferred technique classification.

---

### MovementAnalysis

**Primary key:** `analysisId`

Foreign keys:

- `movementId -> SessionMovement.movementId` with cascade delete
- `landmarkTrackId -> LandmarkTrack.landmarkTrackId` with deferred `NO_ACTION`

Purpose: one analyzer execution/version against one movement using exact landmark evidence.

Important fields:

- `analyzerKey`
- `analyzerVersion`
- `landmarkTrackId`
- `state`
- `createdAtMs`
- `reason`
- `geometryJson`

Multiple analyses may exist for the same movement. History is retained rather than overwritten.

---

### MeasurementResult

**Primary key:** `measurementResultId`

**Foreign key:** `analysisId -> MovementAnalysis.analysisId` with cascade delete.

Purpose: individual output from a movement analysis.

Important fields:

- `measurementKey`
- `calculationVersion`
- numeric or categorical value
- `side`
- `role`
- `state`
- confidence/uncertainty
- occurrence time/frame
- abstention/failure reason

Indexes:

- `analysisId`
- `measurementKey`

This is the historical metric/evidence layer used by history queries.

---

### UserBodyMeasurement

**Primary key:** `bodyMeasurementId`

**Foreign key:** `userId -> TrainingUser.userId` with restrict delete.

Purpose: append-only versioned physical measurements for a user.

Important fields:

- `type`
- `value`
- `canonicalUnit`
- `measuredAtMs`
- `source`

Index:

- `(userId, type, measuredAtMs)`

---

### UserCalibration

**Primary key:** `calibrationId`

**Foreign key:** `userId -> TrainingUser.userId` with restrict delete.

Purpose: append-only versioned calibration evidence.

Important fields:

- `type`
- `measuredAtMs`
- `version`
- `source`
- `state`
- `data`

Index:

- `(userId, type, measuredAtMs)`

---

### AnalysisBodyMeasurement

**Composite primary key:** `(analysisId, bodyMeasurementId)`

Foreign keys:

- analysis -> `MovementAnalysis` with cascade delete
- body measurement -> `UserBodyMeasurement` with restrict delete

Purpose: binds an analysis to the exact physical measurement version(s) used.

This prevents later profile edits from silently reinterpreting historical analysis.

---

### AnalysisCalibration

**Composite primary key:** `(analysisId, calibrationId)`

Foreign keys:

- analysis -> `MovementAnalysis` with cascade delete
- calibration -> `UserCalibration` with restrict delete

Purpose: binds an analysis to the exact calibration version(s) used.

---

### SessionBodyMeasurement

**Composite primary key:** `(sessionId, bodyMeasurementId)`

Foreign keys:

- session -> `RecordingSession` with cascade delete
- body measurement -> `UserBodyMeasurement` with restrict delete

Purpose: records which physical measurement versions were captured/bound to the recording session.

---

## 4. Main lifecycle view

### Capture

```text
TrainingUser
  -> RecordingSession
      -> MasterRecording
```

The session is the durable capture aggregate. The master recording is one-to-one with it.

### Landmark extraction

```text
MasterRecording
  -> LandmarkTrack v1
  -> LandmarkTrack v2
  -> ...
```

Landmark evidence is versioned and can coexist.

### Processing / reprocessing

```text
RecordingSession
  -> RecordingProcessing       current queue/status
  -> ProcessingRun A           historical run
  -> ProcessingRun B           historical run, possibly current
```

`RecordingProcessing` and `ProcessingRun` therefore solve different problems:

- `RecordingProcessing` = durable operational queue state.
- `ProcessingRun` = durable processing lineage/history.

### Segmentation

```text
ProcessingRun
  -> SessionMovement*
```

This relationship currently exists at the application level through `SessionMovement.runId`.

Each movement also identifies the exact landmark track used for segmentation through `segmentationTrackId`.

### Analysis

```text
SessionMovement
  -> MovementAnalysis*
      -> MeasurementResult*
```

Each analysis stores the exact analyzer version and landmark track used.

Body measurement and calibration join rows bind exact physical-reference versions to the analysis.

---

## 5. Important logical invariants

### One master recording per session

Enforced by the unique `MasterRecording(sessionId)` index.

### Zero or one durable queue row per session

Enforced because `RecordingProcessing.sessionId` is both PK and FK.

### Multiple processing runs per session

Explicitly supported.

### One current processing run per session

Implemented transactionally in repository code using:

1. clear current flags for the session
2. set the selected run to `isCurrent = true`

This is **not enforced by a unique database constraint**.

### Movement ownership

A movement belongs to exactly one recording session.

### Movement -> processing run lineage

Used by the application through `SessionMovement.runId`, but **not FK-enforced**.

### Exact evidence provenance

Analyses retain exact:

- movement ID
- analyzer key/version
- landmark track
- physical measurement IDs
- calibration IDs

This supports historical reproducibility and reanalysis.

### Append-only user physical references

Body measurements and calibrations are versioned rows rather than mutable “current value” records.

### Cross-session event protection

The composite FKs in `MovementSessionEvent` enforce that a movement and event association cannot cross session boundaries.

---

## 6. Room-enforced vs application-enforced references

| Reference | Logical meaning | Room FK? | Notes |
|---|---|---:|---|
| `RecordingSession.userId` | session owner | Yes | RESTRICT on user deletion |
| `MasterRecording.sessionId` | session media | Yes | unique session index |
| `LandmarkTrack.recordingId` | track source media | Yes | cascade |
| `RecordingProcessing.sessionId` | queue state for session | Yes | one row max |
| `RecordingProcessing.sourceLandmarkTrackId` | queue provenance | **No** | application-level only |
| `ProcessingRun.sessionId` | run owner | Yes | cascade |
| `ProcessingRun.sourceLandmarkTrackId` | run source track | Yes | SET_NULL |
| `SessionMovement.sessionId` | movement owner | Yes | cascade |
| `SessionMovement.segmentationTrackId` | segmentation evidence | Yes | deferred NO_ACTION |
| `SessionMovement.runId` | producing processing run | **No** | indexed/queryable logical reference |
| `MovementAnalysis.movementId` | analyzed movement | Yes | cascade |
| `MovementAnalysis.landmarkTrackId` | analysis evidence | Yes | deferred NO_ACTION |
| `RecordingSession.parentId` | request/session lineage | **No** | opaque logical ID |
| `RecordingSession.callerId` | caller provenance | **No** | opaque logical ID |

---

## 7. Architectural reading

The schema is fundamentally an **evidence/history model**, not a “latest calculated value” database.

The strongest part of the design is the separation between:

- captured media,
- derived landmark evidence,
- processing-run lineage,
- segmented movements,
- analyzer executions,
- individual measurements,
- physical-reference versions.

That means reprocessing can create new evidence without erasing the evidence used by earlier results.

The central aggregate is still `RecordingSession`, while `ProcessingRun` provides a second axis for reproducible derivation history.

A useful conceptual dependency direction is:

```text
TrainingUser
  -> RecordingSession
      -> MasterRecording
          -> LandmarkTrack
      -> ProcessingRun
          -> SessionMovement
              -> ObservationContext
              -> labels/events
              -> MovementAnalysis
                  -> MeasurementResult
                  -> exact body/calibration references
```

---

## 8. Model observations worth keeping visible

1. **`SessionMovement.runId` is not FK-enforced.**  
   This is currently the biggest difference between the logical model and the physical Room constraint graph.

2. **`RecordingProcessing.sourceLandmarkTrackId` is not FK-enforced.**  
   It behaves as provenance metadata rather than a constrained relationship.

3. **“One current run” is an application invariant.**  
   The `(sessionId, isCurrent)` index is not unique.

4. **Analysis provenance is strong.**  
   Each analysis references an exact movement, analyzer version and landmark track, with versioned body/calibration references.

5. **Processing queue state and processing history are correctly separate concepts.**  
   `RecordingProcessing` should not be merged conceptually with `ProcessingRun`.

6. **The existing `docs/app-training-database.md` is older than the current schema.**  
   Its header/ER section still describes schema v6 and omits newer v7-v10 concepts such as `ProcessingRun`, `analysisFrameUs`, `geometryJson`, audio cue package versioning and canonical geometry fields. This logical model uses the current schema-v10 code as authority.

---

## 9. Source files

Current implementation paths:

```text
android/KarateClipRecorder/app/src/main/java/dk/lasse/karatecliprecorder/training/
  KarateTrainingDatabase.kt
  TrainingRows.kt
  TrainingModels.kt
  TrainingDao.kt
  TrainingRepository.kt
```

The model should be updated whenever the Room schema changes.
