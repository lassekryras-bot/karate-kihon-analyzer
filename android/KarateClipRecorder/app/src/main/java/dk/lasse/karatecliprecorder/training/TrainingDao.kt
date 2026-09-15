package dk.lasse.karatecliprecorder.training

import androidx.room.*

@Dao
internal interface TrainingDao {
    @Insert(onConflict = OnConflictStrategy.IGNORE) fun insert(row: RecordingProcessingRow)
    @Update fun update(row: RecordingProcessingRow)
    @Query("SELECT * FROM RecordingProcessing WHERE sessionId = :id") fun job(id: String): RecordingProcessingRow?
    @Query("SELECT * FROM RecordingProcessing ORDER BY promotedAtMs IS NULL, promotedAtMs DESC, queuedAtMs, sessionId") fun jobs(): List<RecordingProcessingRow>
    @Query("UPDATE RecordingProcessing SET state = 'QUEUED', phase = 'QUEUED' WHERE state = 'PROCESSING'") fun recoverJobs()
    @Query("SELECT * FROM RecordingSession WHERE activityKey = 'record_and_analyze_assisted_v1'") fun assistedSessions(): List<RecordingSessionRow>
    @Insert fun insert(row: TrainingUserRow)
    @Insert fun insert(row: RecordingSessionRow)
    @Insert fun insert(row: MasterRecordingRow)
    @Insert fun insert(row: LandmarkTrackRow)
    @Insert fun insert(row: SessionMovementRow)
    @Insert fun insert(row: ObservationContextRow)
    @Insert fun insert(row: SessionEventRow)
    @Insert fun insert(row: MovementSessionEventRow)
    @Insert fun insert(row: LabelRow)
    @Insert fun insert(row: MovementLabelRow)
    @Insert fun insert(row: MovementAnalysisRow)
    @Insert fun insert(row: MeasurementResultRow)
    @Insert fun insert(row: UserBodyMeasurementRow)
    @Insert fun insert(row: UserCalibrationRow)
    @Insert fun insert(row: AnalysisBodyMeasurementRow)
    @Insert fun insert(row: AnalysisCalibrationRow)
    @Insert fun insert(row: SessionBodyMeasurementRow)

    @Query("SELECT * FROM TrainingUser WHERE userId = :id") fun user(id: String): TrainingUserRow?
    @Query("SELECT * FROM RecordingSession WHERE sessionId = :id") fun session(id: String): RecordingSessionRow?
    @Query("SELECT * FROM RecordingSession WHERE userId = :id ORDER BY startedAtMs DESC, sessionId DESC") fun sessions(id: String): List<RecordingSessionRow>
    @Query("SELECT * FROM RecordingSession WHERE state != 'COMPLETED' ORDER BY startedAtMs") fun recoverable(): List<RecordingSessionRow>
    @Query("UPDATE RecordingSession SET state = :state, reason = :reason WHERE sessionId = :id") fun sessionState(id: String, state: SessionState, reason: String?)
    @Query("UPDATE RecordingSession SET endedAtMs = :endMs, state = :state, reason = :reason, captureOutcome = :outcome WHERE sessionId = :id")
    fun finishRecording(id: String, endMs: Long, state: SessionState, reason: String?, outcome: String?)
    @Query("SELECT * FROM MasterRecording WHERE sessionId = :id") fun recording(id: String): MasterRecordingRow?
    @Update fun update(row: MasterRecordingRow)
    @Query("UPDATE RecordingSession SET interruptionReason = :reason WHERE sessionId = :id") fun interruptSession(id: String, reason: String)
    @Query("SELECT * FROM MasterRecording") fun recordings(): List<MasterRecordingRow>
    @Query("SELECT * FROM LandmarkTrack WHERE recordingId = :id ORDER BY createdAtMs DESC, landmarkTrackId DESC") fun tracks(id: String): List<LandmarkTrackRow>
    @Query("SELECT * FROM LandmarkTrack WHERE landmarkTrackId = :id") fun track(id: String): LandmarkTrackRow?
    @Update fun update(row: LandmarkTrackRow)
    @Query("SELECT * FROM SessionMovement WHERE sessionId = :id ORDER BY startUs, endUs, movementId") fun movements(id: String): List<SessionMovementRow>
    @Query("SELECT * FROM SessionMovement WHERE movementId = :id") fun movement(id: String): SessionMovementRow?
    @Query("SELECT COUNT(*) FROM SessionMovement WHERE sessionId = :id") fun movementCount(id: String): Int
    @Query("UPDATE SessionMovement SET state = :state WHERE movementId = :id") fun movementState(id: String, state: MovementState)
    @Query("SELECT * FROM ObservationContext WHERE movementId = :id") fun observation(id: String): ObservationContextRow?
    @Query("SELECT * FROM SessionEvent WHERE sessionId = :id ORDER BY timestampUs, sessionEventId") fun events(id: String): List<SessionEventRow>
    @Query("SELECT e.* FROM SessionEvent e JOIN MovementSessionEvent l ON l.sessionEventId = e.sessionEventId WHERE l.movementId = :id ORDER BY e.timestampUs") fun movementEvents(id: String): List<SessionEventRow>
    @Query("SELECT * FROM Label WHERE machineKey = :key") fun label(key: String): LabelRow?
    @Query("SELECT l.* FROM Label l JOIN MovementLabel ml ON ml.labelId = l.labelId WHERE ml.movementId = :id ORDER BY l.machineKey") fun labels(id: String): List<LabelRow>
    @Query("SELECT * FROM MovementAnalysis WHERE movementId = :id ORDER BY createdAtMs DESC, analysisId DESC") fun analyses(id: String): List<MovementAnalysisRow>
    @Query("SELECT * FROM MeasurementResult WHERE analysisId = :id ORDER BY measurementResultId") fun results(id: String): List<MeasurementResultRow>
    @Query("SELECT * FROM UserBodyMeasurement WHERE userId = :id ORDER BY measuredAtMs DESC, bodyMeasurementId DESC") fun bodyMeasurements(id: String): List<UserBodyMeasurementRow>
    @Query("SELECT * FROM UserCalibration WHERE userId = :id ORDER BY measuredAtMs DESC, calibrationId DESC") fun calibrations(id: String): List<UserCalibrationRow>
    @Query("SELECT * FROM UserBodyMeasurement WHERE bodyMeasurementId = :id") fun bodyMeasurement(id: String): UserBodyMeasurementRow?
    @Query("SELECT * FROM UserCalibration WHERE calibrationId = :id") fun calibration(id: String): UserCalibrationRow?
    @Query("SELECT bodyMeasurementId FROM SessionBodyMeasurement WHERE sessionId = :id") fun sessionBodyIds(id: String): List<String>
    @Query("DELETE FROM RecordingSession WHERE sessionId = :id") fun deleteSession(id: String)
    @Query("DELETE FROM TrainingUser WHERE userId = :id") fun deleteUser(id: String)
    @Query("""
        SELECT r.* FROM MeasurementResult r
        JOIN MovementAnalysis a ON a.analysisId = r.analysisId
        JOIN SessionMovement m ON m.movementId = a.movementId
        JOIN RecordingSession s ON s.sessionId = m.sessionId
        WHERE s.userId = :userId AND r.measurementKey = :key AND a.analyzerKey = :analyzer
        AND a.analyzerVersion IN (:versions) AND a.state IN ('COMPLETED', 'PARTIAL')
        ORDER BY s.startedAtMs DESC, m.startUs DESC, m.movementId DESC, a.createdAtMs DESC, r.measurementResultId
        LIMIT :limit OFFSET :offset
    """) fun historyCandidates(userId: String, key: String, analyzer: String, versions: List<String>, limit: Int, offset: Int): List<MeasurementResultRow>
    @Query("SELECT * FROM MovementAnalysis WHERE analysisId = :id") fun analysis(id: String): MovementAnalysisRow?
}
