package dk.lasse.karatecliprecorder.training

import androidx.room.*

// Room rows stay internal; repository callers use the embedded domain values.
@Entity(tableName = "TrainingUser", primaryKeys = ["userId"])
internal data class TrainingUserRow(@Embedded val value: TrainingUser)

@Entity(tableName = "RecordingSession", primaryKeys = ["sessionId"], foreignKeys = [
    ForeignKey(entity = TrainingUserRow::class, parentColumns = ["userId"], childColumns = ["userId"], onDelete = ForeignKey.RESTRICT)
], indices = [Index(value = ["userId", "startedAtMs"])])
internal data class RecordingSessionRow(@Embedded val value: RecordingSession)

@Entity(tableName = "RecordingProcessing", primaryKeys = ["sessionId"], foreignKeys = [
    ForeignKey(entity = RecordingSessionRow::class, parentColumns = ["sessionId"], childColumns = ["sessionId"], onDelete = ForeignKey.CASCADE)
], indices = [Index(value = ["state", "queuedAtMs"])])
internal data class RecordingProcessingRow(@Embedded val value: RecordingProcessing)

@Entity(tableName = "MasterRecording", primaryKeys = ["recordingId"], foreignKeys = [
    ForeignKey(entity = RecordingSessionRow::class, parentColumns = ["sessionId"], childColumns = ["sessionId"], onDelete = ForeignKey.CASCADE)
], indices = [Index(value = ["sessionId"], unique = true)])
internal data class MasterRecordingRow(@Embedded val value: MasterRecording)

@Entity(tableName = "LandmarkTrack", primaryKeys = ["landmarkTrackId"], foreignKeys = [
    ForeignKey(entity = MasterRecordingRow::class, parentColumns = ["recordingId"], childColumns = ["recordingId"], onDelete = ForeignKey.CASCADE)
], indices = [Index(value = ["recordingId"])])
internal data class LandmarkTrackRow(@Embedded val value: LandmarkTrack)

@Entity(tableName = "ProcessingRun", primaryKeys = ["runId"], foreignKeys = [
    ForeignKey(entity = RecordingSessionRow::class, parentColumns = ["sessionId"], childColumns = ["sessionId"], onDelete = ForeignKey.CASCADE),
    ForeignKey(entity = LandmarkTrackRow::class, parentColumns = ["landmarkTrackId"], childColumns = ["sourceLandmarkTrackId"], onDelete = ForeignKey.SET_NULL)
], indices = [
    Index(value = ["sessionId", "createdAtMs"]),
    Index(value = ["sessionId", "isCurrent"]),
    Index(value = ["sourceLandmarkTrackId"])
])
internal data class ProcessingRunRow(@Embedded val value: ProcessingRun)

@Entity(tableName = "SessionMovement", primaryKeys = ["movementId"], foreignKeys = [
    ForeignKey(entity = RecordingSessionRow::class, parentColumns = ["sessionId"], childColumns = ["sessionId"], onDelete = ForeignKey.CASCADE),
    ForeignKey(entity = LandmarkTrackRow::class, parentColumns = ["landmarkTrackId"], childColumns = ["segmentationTrackId"], onDelete = ForeignKey.NO_ACTION, deferred = true)
], indices = [
    Index(value = ["sessionId", "startUs"]),
    Index(value = ["segmentationTrackId"]),
    Index(value = ["movementId", "sessionId"], unique = true),
    Index(value = ["runId"])
])
internal data class SessionMovementRow(@Embedded val value: SessionMovement)

@Entity(tableName = "ObservationContext", primaryKeys = ["movementId"], foreignKeys = [
    ForeignKey(entity = SessionMovementRow::class, parentColumns = ["movementId"], childColumns = ["movementId"], onDelete = ForeignKey.CASCADE)
])
internal data class ObservationContextRow(@Embedded val value: ObservationContext)

@Entity(tableName = "SessionEvent", primaryKeys = ["sessionEventId"], foreignKeys = [
    ForeignKey(entity = RecordingSessionRow::class, parentColumns = ["sessionId"], childColumns = ["sessionId"], onDelete = ForeignKey.CASCADE)
], indices = [Index(value = ["sessionId", "timestampUs"]), Index(value = ["sessionEventId", "sessionId"], unique = true)])
internal data class SessionEventRow(@Embedded val value: SessionEvent)

@Entity(tableName = "MovementSessionEvent", primaryKeys = ["movementId", "sessionEventId"], foreignKeys = [
    ForeignKey(entity = SessionMovementRow::class, parentColumns = ["movementId", "sessionId"], childColumns = ["movementId", "sessionId"], onDelete = ForeignKey.CASCADE),
    ForeignKey(entity = SessionEventRow::class, parentColumns = ["sessionEventId", "sessionId"], childColumns = ["sessionEventId", "sessionId"], onDelete = ForeignKey.CASCADE)
], indices = [Index(value = ["movementId", "sessionId"]), Index(value = ["sessionEventId", "sessionId"])])
internal data class MovementSessionEventRow(@Embedded val value: MovementSessionEvent)

@Entity(tableName = "Label", primaryKeys = ["labelId"], indices = [Index(value = ["machineKey"], unique = true)])
internal data class LabelRow(@Embedded val value: Label)

@Entity(tableName = "MovementLabel", primaryKeys = ["movementId", "labelId"], foreignKeys = [
    ForeignKey(entity = SessionMovementRow::class, parentColumns = ["movementId"], childColumns = ["movementId"], onDelete = ForeignKey.CASCADE),
    ForeignKey(entity = LabelRow::class, parentColumns = ["labelId"], childColumns = ["labelId"], onDelete = ForeignKey.RESTRICT)
], indices = [Index(value = ["labelId"])])
internal data class MovementLabelRow(@Embedded val value: MovementLabel)

@Entity(tableName = "MovementAnalysis", primaryKeys = ["analysisId"], foreignKeys = [
    ForeignKey(entity = SessionMovementRow::class, parentColumns = ["movementId"], childColumns = ["movementId"], onDelete = ForeignKey.CASCADE),
    ForeignKey(entity = LandmarkTrackRow::class, parentColumns = ["landmarkTrackId"], childColumns = ["landmarkTrackId"], onDelete = ForeignKey.NO_ACTION, deferred = true)
], indices = [Index(value = ["movementId", "analyzerKey", "analyzerVersion", "state", "createdAtMs"]), Index(value = ["landmarkTrackId"])])
internal data class MovementAnalysisRow(@Embedded val value: MovementAnalysis)

@Entity(tableName = "MeasurementResult", primaryKeys = ["measurementResultId"], foreignKeys = [
    ForeignKey(entity = MovementAnalysisRow::class, parentColumns = ["analysisId"], childColumns = ["analysisId"], onDelete = ForeignKey.CASCADE)
], indices = [Index(value = ["analysisId"]), Index(value = ["measurementKey"])])
internal data class MeasurementResultRow(@Embedded val value: MeasurementResult)

@Entity(tableName = "UserBodyMeasurement", primaryKeys = ["bodyMeasurementId"], foreignKeys = [
    ForeignKey(entity = TrainingUserRow::class, parentColumns = ["userId"], childColumns = ["userId"], onDelete = ForeignKey.RESTRICT)
], indices = [Index(value = ["userId", "type", "measuredAtMs"])])
internal data class UserBodyMeasurementRow(@Embedded val value: UserBodyMeasurement)

@Entity(tableName = "UserCalibration", primaryKeys = ["calibrationId"], foreignKeys = [
    ForeignKey(entity = TrainingUserRow::class, parentColumns = ["userId"], childColumns = ["userId"], onDelete = ForeignKey.RESTRICT)
], indices = [Index(value = ["userId", "type", "measuredAtMs"])])
internal data class UserCalibrationRow(@Embedded val value: UserCalibration)

@Entity(tableName = "AnalysisBodyMeasurement", primaryKeys = ["analysisId", "bodyMeasurementId"], foreignKeys = [
    ForeignKey(entity = MovementAnalysisRow::class, parentColumns = ["analysisId"], childColumns = ["analysisId"], onDelete = ForeignKey.CASCADE),
    ForeignKey(entity = UserBodyMeasurementRow::class, parentColumns = ["bodyMeasurementId"], childColumns = ["bodyMeasurementId"], onDelete = ForeignKey.RESTRICT)
], indices = [Index(value = ["bodyMeasurementId"])])
internal data class AnalysisBodyMeasurementRow(@Embedded val value: AnalysisBodyMeasurement)

@Entity(tableName = "AnalysisCalibration", primaryKeys = ["analysisId", "calibrationId"], foreignKeys = [
    ForeignKey(entity = MovementAnalysisRow::class, parentColumns = ["analysisId"], childColumns = ["analysisId"], onDelete = ForeignKey.CASCADE),
    ForeignKey(entity = UserCalibrationRow::class, parentColumns = ["calibrationId"], childColumns = ["calibrationId"], onDelete = ForeignKey.RESTRICT)
], indices = [Index(value = ["calibrationId"])])
internal data class AnalysisCalibrationRow(@Embedded val value: AnalysisCalibration)

@Entity(tableName = "SessionBodyMeasurement", primaryKeys = ["sessionId", "bodyMeasurementId"], foreignKeys = [
    ForeignKey(entity = RecordingSessionRow::class, parentColumns = ["sessionId"], childColumns = ["sessionId"], onDelete = ForeignKey.CASCADE),
    ForeignKey(entity = UserBodyMeasurementRow::class, parentColumns = ["bodyMeasurementId"], childColumns = ["bodyMeasurementId"], onDelete = ForeignKey.RESTRICT)
], indices = [Index(value = ["bodyMeasurementId"])])
internal data class SessionBodyMeasurementRow(@Embedded val value: SessionBodyMeasurement)
