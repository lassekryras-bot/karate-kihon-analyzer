package dk.lasse.karatecliprecorder.training

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import dk.lasse.karateanalyzer.core.*
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File
import kotlin.test.*

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28])
class TrainingRepositoryTest {
    private lateinit var db: KarateTrainingDatabase
    private lateinit var repository: TrainingRepository
    private lateinit var directory: File
    private val user = TrainingUser()
    private val policy = AnalyzerPolicy("test_analyzer", listOf("2", "1"))

    @Before fun open() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        db = Room.inMemoryDatabaseBuilder(context, KarateTrainingDatabase::class.java).allowMainThreadQueries().build()
        repository = TrainingRepository(db)
        repository.createUser(user)
        directory = kotlin.io.path.createTempDirectory("training-test").toFile()
    }
    @After fun close() { db.close(); directory.deleteRecursively() }

    private fun session(time: Long = 1000, count: Int = 10): Pair<RecordingSession, LandmarkTrack> {
        val session = RecordingSession(userId = user.userId, startedAtMs = time, guided = true, expectedRepetitions = count)
        val file = File(directory, "${session.sessionId}.mp4").apply { writeText("synthetic source") }
        val recording = MasterRecording(sessionId = session.sessionId, filePath = file.path, createdAtMs = time)
        repository.beginSession(session, recording)
        repository.finishRecording(session.sessionId, 30_000_000, width = 1920, height = 1080, frameRate = 30.0)
        val track = LandmarkTrack(recordingId = recording.recordingId, pipelineKey = "test", pipelineVersion = "1",
            configuration = "fixture", filePath = File(directory, "${trainingId()}.pose").path,
            state = ProcessingState.COMPLETED, sourceState = SourceState.AVAILABLE)
        repository.addTrack(track)
        return session to track
    }
    private fun segment(session: RecordingSession, track: LandmarkTrack, count: Int = 10): List<SessionMovement> {
        val movements = (1..count).map { n -> SessionMovement(sessionId = session.sessionId,
            startUs = n * 1_000_000L, endUs = n * 1_000_000L + 500_000, playbackStartUs = n * 1_000_000L - 150_000,
            playbackEndUs = n * 1_000_000L + 700_000, segmentationSource = "fixture", segmentationVersion = "1",
            segmentationTrackId = track.landmarkTrackId) }
        repository.saveSegmentation(session.sessionId, movements.reversed(), movements.map {
            ObservationContext(it.movementId, ObservedView.RIGHT_SIDE, BodySide.RIGHT, 75.0, 0.9, "synthetic_observation")
        }, labels = listOf(Label(machineKey = "straight_punch", displayText = "Straight Punch", category = "technique")))
        return movements
    }
    private fun analyze(movement: SessionMovement, track: LandmarkTrack, version: String = "1", time: Long = 10,
                        state: AnalysisState = AnalysisState.COMPLETED): MovementAnalysis {
        val analysis = MovementAnalysis(movementId = movement.movementId, analyzerKey = policy.analyzerKey,
            analyzerVersion = version, landmarkTrackId = track.landmarkTrackId, state = state, createdAtMs = time)
        repository.saveAnalysis(analysis, if (state == AnalysisState.FAILED) emptyList() else values(analysis))
        return analysis
    }
    private fun values(analysis: MovementAnalysis) = listOf(
        MeasurementResult(analysisId = analysis.analysisId, measurementKey = "PUNCH_ELBOW_ANGLE", calculationVersion = "1",
            numericValue = 170.0, state = ResultState.VALID, side = BodySide.RIGHT, role = "strike", confidence = 0.9),
        MeasurementResult(analysisId = analysis.analysisId, measurementKey = "PUNCH_HEIGHT_ERROR_TORSO_RATIO", calculationVersion = "1",
            numericValue = 0.03, state = ResultState.VALID, side = BodySide.RIGHT, role = "strike"),
    )

    @Test fun tenPunchAcceptanceRetainsWholeEvidenceGraphAndChronologicalNumbering() {
        val (session, track) = session()
        val movements = segment(session, track)
        val cue = SessionEvent(sessionId = session.sessionId, type = "count", timestampUs = 4_900_000, data = "Go")
        val instruction = SessionEvent(sessionId = session.sessionId, type = "instruction", timestampUs = 0, data = "Jodan")
        repository.addEvent(cue); repository.addEvent(instruction)
        listOf(cue, instruction).forEach { repository.associateEvent(MovementSessionEvent(movements[4].movementId, it.sessionEventId, session.sessionId)) }
        movements.forEach { analyze(it, track) }
        repository.addTrack(track.copy(landmarkTrackId = trainingId(), pipelineVersion = "2", filePath = "new-model.pose"))
        val punch5 = assertNotNull(repository.movementEvidence(session.sessionId, 5))
        assertEquals(movements[4], punch5.movement.copy(state = MovementState.DETECTED))
        assertEquals(10, repository.movementCount(session.sessionId))
        assertEquals(movements.map { it.movementId }, repository.movements(session.sessionId).map { it.movementId })
        assertEquals(2, punch5.landmarkTracks.size)
        assertEquals(2, punch5.events.size)
        assertEquals(2, punch5.measurements.size)
        assertEquals("straight_punch", punch5.labels.single().machineKey)
        assertEquals(ObservedView.RIGHT_SIDE, punch5.observation?.view)
        assertEquals(4_850_000L, punch5.movement.playbackStartUs)
    }

    @Test fun approvalOrderAndFailedRunsFallBackWithoutReplacingEarlierEvidence() {
        val (session, track) = session()
        val m = segment(session, track).first()
        val old = analyze(m, track, "1", 20)
        analyze(m, track, "2", 30, AnalysisState.FAILED)
        assertEquals(old, repository.preferredAnalysis(m.movementId, policy))
        val approved = analyze(m, track, "2", 25)
        analyze(m, track, "99", 100)
        analyze(m, track, "1", 200)
        assertEquals(approved, repository.preferredAnalysis(m.movementId, policy))
        assertEquals(5, repository.movementEvidence(session.sessionId, 1)!!.analyses.size)
        assertEquals(8, repository.movementEvidence(session.sessionId, 1)!!.measurements.size)
    }

    @Test fun insertingMissedMovementChangesDisplayPositionWithoutChangingOtherIdentities() {
        val (session, track) = session()
        val original = segment(session, track)
        val missed = original.first().copy(movementId = trainingId(), startUs = 500_000, endUs = 800_000,
            playbackStartUs = 400_000, playbackEndUs = 900_000)
        repository.addMovement(missed, ObservationContext(missed.movementId))
        assertEquals(missed.movementId, repository.movementEvidence(session.sessionId, 1)!!.movement.movementId)
        assertEquals(original.first().movementId, repository.movementEvidence(session.sessionId, 2)!!.movement.movementId)
        assertEquals(11, repository.movementCount(session.sessionId))
    }

    @Test fun unsupportedDatabaseVersionCannotSilentlyDestroyHistory() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val name = "unsupported-version-${trainingId()}"
        var stored = Room.databaseBuilder(context, KarateTrainingDatabase::class.java, name).allowMainThreadQueries().build()
        try {
            TrainingRepository(stored).createUser(user)
            stored.openHelper.writableDatabase.execSQL("PRAGMA user_version=99")
            stored.close()
            stored = Room.databaseBuilder(context, KarateTrainingDatabase::class.java, name).allowMainThreadQueries().build()
            assertFails { stored.openHelper.writableDatabase }
            android.database.sqlite.SQLiteDatabase.openDatabase(context.getDatabasePath(name).path, null,
                android.database.sqlite.SQLiteDatabase.OPEN_READONLY).use { sqlite ->
                sqlite.rawQuery("SELECT userId FROM TrainingUser", null).use { cursor ->
                    assertTrue(cursor.moveToFirst()); assertEquals(user.userId, cursor.getString(0))
                }
            }
        } finally { stored.close(); context.deleteDatabase(name) }
    }

    @Test fun deletingOnlyVideoPreservesLandmarksAndHistoricalValues() {
        val (session, track) = session()
        val movement = segment(session, track).first()
        analyze(movement, track)
        val before = repository.movementEvidence(session.sessionId, 1)!!
        repository.deleteVideo(session.sessionId)
        repository.deleteVideo(session.sessionId)
        val after = repository.movementEvidence(session.sessionId, 1)!!
        assertFalse(File(after.recording.filePath).exists())
        assertEquals(SourceState.DELETED, after.recording.sourceState)
        assertEquals(before.measurements, after.measurements)
        assertEquals(before.landmarkTracks, after.landmarkTracks)
    }

    @Test fun bodyMeasurementsAndCalibrationsAreAppendOnlyAndAnalysisReferencesExactVersions() {
        val oldBody = UserBodyMeasurement(userId = user.userId, type = "height", value = 160.0, measuredAtMs = 1, source = "manual")
        val newBody = oldBody.copy(bodyMeasurementId = trainingId(), value = 165.0, measuredAtMs = 2)
        repository.addBodyMeasurement(oldBody); repository.addBodyMeasurement(newBody)
        val oldCalibration = UserCalibration(userId = user.userId, type = "scale", measuredAtMs = 1, version = "1", source = "manual", state = "valid", data = "scale=1")
        val newCalibration = oldCalibration.copy(calibrationId = trainingId(), measuredAtMs = 2, data = "scale=2")
        repository.addCalibration(oldCalibration); repository.addCalibration(newCalibration)
        val (session, track) = session()
        val movement = segment(session, track).first()
        val analysis = MovementAnalysis(movementId = movement.movementId, analyzerKey = "test", analyzerVersion = "1", landmarkTrackId = track.landmarkTrackId, state = AnalysisState.COMPLETED)
        repository.saveAnalysis(analysis, values(analysis), listOf(oldBody.bodyMeasurementId), listOf(oldCalibration.calibrationId))
        assertEquals(listOf(newBody, oldBody), repository.bodyMeasurements(user.userId))
        assertEquals(listOf(newCalibration, oldCalibration), repository.calibrations(user.userId))
        assertFails { repository.addBodyMeasurement(oldBody.copy(value = 999.0)) }
        assertFails { repository.addCalibration(oldCalibration.copy(data = "changed")) }
        db.openHelper.readableDatabase.query("SELECT calibrationId FROM AnalysisCalibration").use {
            assertTrue(it.moveToFirst()); assertEquals(oldCalibration.calibrationId, it.getString(0))
        }
    }

    @Test fun invalidSecondMeasurementRollsBackRunAndFirstMeasurement() {
        val (session, track) = session()
        val m = segment(session, track).first()
        val run = MovementAnalysis(movementId = m.movementId, analyzerKey = "test", analyzerVersion = "1", landmarkTrackId = track.landmarkTrackId, state = AnalysisState.COMPLETED)
        assertFails { repository.saveAnalysis(run, values(run).mapIndexed { i, r -> if (i == 1) r.copy(numericValue = Double.NaN) else r }) }
        assertTrue(repository.movementEvidence(session.sessionId, 1)!!.analyses.isEmpty())
        assertTrue(db.trainingDao().results(run.analysisId).isEmpty())
        assertEquals(MovementState.DETECTED, repository.movements(session.sessionId).first().state)
    }

    @Test fun segmentationRollbackAndCrossSessionForeignKeysProtectOwnership() {
        val (session, track) = session()
        val (other, otherTrack) = session(2000)
        val movement = segment(session, track).first()
        val otherEvent = SessionEvent(sessionId = other.sessionId, type = "cue", timestampUs = 0)
        repository.addEvent(otherEvent)
        assertFails { repository.associateEvent(MovementSessionEvent(movement.movementId, otherEvent.sessionEventId, session.sessionId)) }
        assertFails { analyze(movement, otherTrack) }
        assertFails { db.trainingDao().deleteUser(user.userId) }
        val invalid = movement.copy(movementId = trainingId(), sessionId = other.sessionId, segmentationTrackId = otherTrack.landmarkTrackId)
        assertFails { repository.saveSegmentation(other.sessionId, listOf(invalid, invalid), listOf(ObservationContext(invalid.movementId))) }
        assertEquals(0, repository.movementCount(other.sessionId))
        analyze(movement, track)
        repository.deleteSession(session.sessionId)
        assertNull(repository.session(session.sessionId))
        assertTrue(repository.tracks(track.recordingId).isEmpty())
        assertNotNull(db.trainingDao().label("straight_punch"))
        assertNotNull(repository.session(other.sessionId))
    }

    @Test fun rollingHistoryFiltersBeforeLimitAndDeduplicatesReanalysisAcrossSessions() {
        val expected = mutableListOf<String>()
        repeat(12) { n ->
            val (session, track) = session(1000L + n)
            segment(session, track).forEach { movement ->
                analyze(movement, track, "1")
                val preferred = analyze(movement, track, "2")
                analyze(movement, track, "2", 50, AnalysisState.FAILED)
                expected += preferred.analysisId
            }
        }
        val query = HistoryQuery(user.userId, "PUNCH_ELBOW_ANGLE", policy, setOf("straight_punch"), BodySide.RIGHT, "strike")
        val history = repository.history(query)
        assertEquals(100, history.size)
        assertEquals(expected.reversed().take(100), history.map { it.analysisId })
        assertTrue(repository.history(query.copy(side = BodySide.LEFT)).isEmpty())
        assertTrue(repository.history(query.copy(labelKeys = setOf("front_kick"))).isEmpty())
        assertTrue(repository.history(query.copy(minimumConfidence = 0.95)).isEmpty())
        db.openHelper.readableDatabase.query("EXPLAIN QUERY PLAN SELECT * FROM SessionMovement WHERE sessionId='fixture' ORDER BY startUs").use { cursor ->
            assertTrue(cursor.moveToFirst())
            assertTrue(cursor.getString(3).contains("INDEX"))
        }
        db.openHelper.readableDatabase.query("""
            EXPLAIN QUERY PLAN SELECT r.* FROM MeasurementResult r
            JOIN MovementAnalysis a ON a.analysisId = r.analysisId
            JOIN SessionMovement m ON m.movementId = a.movementId
            JOIN RecordingSession s ON s.sessionId = m.sessionId
            WHERE s.userId = '${user.userId}' AND r.measurementKey = 'PUNCH_ELBOW_ANGLE'
            AND a.analyzerKey = 'test_analyzer' AND a.analyzerVersion IN ('2', '1')
            AND a.state IN ('COMPLETED', 'PARTIAL')
            ORDER BY s.startedAtMs DESC, m.startUs DESC, m.movementId DESC, a.createdAtMs DESC, r.measurementResultId
            LIMIT 200 OFFSET 0
        """.trimIndent()).use { cursor ->
            val plan = mutableListOf<String>()
            while (cursor.moveToNext()) plan += cursor.getString(3)
            println("Rolling-history query plan: ${plan.joinToString(" | ")}")
            assertTrue(plan.any { it.contains("INDEX") })
        }
    }

    @Test fun recoveryRetainsCompletedRunsAndMarksInterruptedSession() {
        val (session, track) = session()
        val movement = segment(session, track).first()
        val analysis = analyze(movement, track)
        repository.setSessionState(session.sessionId, SessionState.ANALYZING)
        File(repository.recording(session.sessionId)!!.filePath).delete()
        val recovered = repository.recoverInterruptedWork().single()
        assertEquals(SessionState.PARTIAL, recovered.state)
        assertEquals(SourceState.MISSING, repository.recording(session.sessionId)!!.sourceState)
        assertEquals(analysis, repository.preferredAnalysis(movement.movementId, policy))
    }

    @Test fun landmarkFileRoundTripKeepsWorldCoordinatesConfidenceAndProvenance() {
        val frames = List(3) { i -> PoseFrame(i * 33L, mapOf(PoseLandmarkId.RIGHT_WRIST to
            PoseLandmarkSample(Point3(0.5f, 0.4f, 0.2f), Point3(0.2f, 0.4f, 0.6f), 0.9f, 0.8f, LandmarkSource.OBSERVED))) }
        val file = File(directory, "test.pose")
        val hash = LandmarkFiles.write(file, frames)
        assertEquals(frames, LandmarkFiles.read(file, hash))
        file.appendText("damaged")
        assertFails { LandmarkFiles.read(file, hash) }
    }
}
