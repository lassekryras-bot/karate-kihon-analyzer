package dk.lasse.karatecliprecorder.training

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import dk.lasse.karateanalyzer.capture.PoseReplayJson
import dk.lasse.karateanalyzer.capture.retrospective.*
import dk.lasse.karateanalyzer.core.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File
import kotlin.test.*

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28])
class TrainingProcessorTest {
    @Test fun recordedTenPunchFixturePersistsAndReopensWithoutRerunningMediaPipe() {
        var root: File? = File(".").canonicalFile
        while (root != null && !File(root, "AGENTS.md").exists()) root = root.parentFile
        val fixtureFile = File(requireNotNull(root), "output/task5/real-kihon-10-punch.fixture.json")
        // Existing real-recording fixture; import is a supported JSON use, not application persistence.
        org.junit.Assume.assumeTrue("Optional local real-recording fixture", fixtureFile.isFile)
        verifyPersistence(PoseReplayJson.decode(fixtureFile.readText()).frames, null, assisted = false)
    }

    private fun buildSyntheticTenPunchFrames(): List<PoseFrame> {
        // 3000 ms of still side-view initialization (120 frames at 25ms)
        // followed by 10 punch cycles (each 1500 ms = 60 frames), total 740 frames (18,500 ms)
        return (0..740).map { index ->
            val t = index * 25L
            val cycle = (t - 3000L).floorDiv(1500)
            val phase = (t - 3000L).mod(1500) / 1000.0
            val reach = if (cycle in 0..9 && phase < 0.5) kotlin.math.sin(phase * 2 * Math.PI).toFloat() * 0.35f else 0f

            fun sample(x: Float, y: Float, z: Float = 0f) = PoseLandmarkSample(
                position = Point3(x, y, z),
                worldPosition = Point3(x, y, z),
                visibility = 0.95f,
                presence = 0.95f,
                source = LandmarkSource.OBSERVED,
            )

            val leftWrist = sample(0.48f - reach, 0.50f, -0.10f)
            val rightWrist = sample(0.52f + reach, 0.50f, 0.10f)
            val leftElbow = sample(0.48f - reach * 0.5f, 0.45f, -0.10f)
            val rightElbow = sample(0.52f + reach * 0.5f, 0.45f, 0.10f)

            PoseFrame(t, mapOf(
                PoseLandmarkId.NOSE to sample(0.48f, 0.20f, -0.10f),
                PoseLandmarkId.MOUTH_LEFT to sample(0.47f, 0.24f, -0.10f),
                PoseLandmarkId.MOUTH_RIGHT to sample(0.49f, 0.24f, 0.10f),
                PoseLandmarkId.LEFT_SHOULDER to sample(0.48f, 0.35f, -0.10f),
                PoseLandmarkId.RIGHT_SHOULDER to sample(0.52f, 0.35f, 0.10f),
                PoseLandmarkId.LEFT_ELBOW to leftElbow,
                PoseLandmarkId.RIGHT_ELBOW to rightElbow,
                PoseLandmarkId.LEFT_WRIST to leftWrist,
                PoseLandmarkId.RIGHT_WRIST to rightWrist,
                PoseLandmarkId.LEFT_INDEX to leftWrist,
                PoseLandmarkId.RIGHT_INDEX to rightWrist,
                PoseLandmarkId.LEFT_THUMB to leftWrist,
                PoseLandmarkId.RIGHT_THUMB to rightWrist,
                PoseLandmarkId.LEFT_PINKY to leftWrist,
                PoseLandmarkId.RIGHT_PINKY to rightWrist,
                PoseLandmarkId.LEFT_HIP to sample(0.48f, 0.65f, -0.10f),
                PoseLandmarkId.RIGHT_HIP to sample(0.52f, 0.65f, 0.10f),
            ))
        }
    }

    @Test fun syntheticContinuousTenPunchSessionPersistsExactlyTenMovements() {
        val frames = buildSyntheticTenPunchFrames()
        verifyPersistence(frames, 10, assisted = true)
    }

    private fun verifyPersistence(frames: List<PoseFrame>, expectedCount: Int?, assisted: Boolean) {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val databaseName = "processor-${trainingId()}"
        val directory = kotlin.io.path.createTempDirectory("processor-test").toFile()
        fun open() = Room.databaseBuilder(context, KarateTrainingDatabase::class.java, databaseName).allowMainThreadQueries().build()
        var db = open()
        try {
            var repository = TrainingRepository(db)
            val user = TrainingUser()
            repository.createUser(user)
            val session = RecordingSession(userId = user.userId, startedAtMs = 1000,
                activityKey = if (assisted) AssistedCaptureSetup.ACTIVITY_KEY else "guided_jodan_session",
                guided = !assisted, expectedRepetitions = if (assisted) 12 else 10)
            val source = File(directory, "master.mp4").apply { writeText("decoder replaced by captured pose fixture") }
            repository.beginSession(session, MasterRecording(sessionId = session.sessionId, filePath = source.path, createdAtMs = 1000))
            repository.finishRecording(session.sessionId, frames.last().timestampMs * 1000)
            repository.addEvent(SessionEvent(sessionId = session.sessionId, type = "cue", timestampUs = 400_000, data = "Ichi"))
            repository.addEvent(SessionEvent(sessionId = session.sessionId, type = "STOP_REQUESTED", timestampUs = 450_000))
            repository.enqueue(session.sessionId)
            var decodes = 0
            val decoder = object : VideoPoseProcessor {
                override fun processVideo(videoFile: File, onProgress: (Float, Long) -> Unit): List<PoseFrame> { decodes++; return frames }
            }
            var clock = 1_000L
            val processor = TrainingSessionProcessor(repository, directory, decoder, "fixture-v1",
                elapsedRealtimeMs = { clock.also { clock += 7 } })
            val count = processor.process(session.sessionId)
            println("Continuous ${if (expectedCount == null) "real fixture" else "synthetic fixture"}: $count movements, 1 landmark decode")
            if (expectedCount != null) assertEquals(expectedCount, count)
            else assertTrue(count >= 10, "Real fixture has ten punches and may contain later reset movements; actual count=$count")
            assertEquals(count, repository.movementCount(session.sessionId))
            val fifth = assertNotNull(repository.movementEvidence(session.sessionId, 5))
            assertEquals(1, fifth.landmarkTracks.size)
            assertEquals(1, fifth.analyses.size)
            assertEquals(if (assisted) 7 else 2, fifth.measurements.size)
            assertNotNull(fifth.observation)
            assertEquals(if (assisted) emptySet() else setOf("straight_punch", "jodan"), fifth.labels.map { it.machineKey }.toSet())
            if (assisted) {
                val geometry = assertNotNull(StraightPunchGeometryCodec.decode(fifth.analyses.single().geometryJson))
                assertTrue(geometry.rays.isNotEmpty())
                assertTrue(geometry.timestampUs in fifth.movement.playbackStartUs..fifth.movement.playbackEndUs)
                assertEquals(12, repository.session(session.sessionId)!!.expectedRepetitions)
                assertEquals(count, repository.movementCount(session.sessionId))
                assertEquals(SessionState.COMPLETED, repository.session(session.sessionId)!!.state)
                val job = repository.job(session.sessionId)!!
                assertEquals(ProcessingPhase.READY, job.phase)
                assertEquals(RecordingProcessingPlans.STRAIGHT_PUNCH_TARGET_ANALYSIS.key, job.planKey)
                assertEquals(7L, job.landmarkDurationMs)
                assertEquals(7L, job.segmentationDurationMs)
                assertEquals(TrainingSessionProcessor.SEGMENTATION_VERSION, job.segmentationVersion)
                assertNotNull(job.sourceLandmarkTrackId)
            }
            val identities = repository.movements(session.sessionId).map { it.movementId }
            repository.deleteVideo(session.sessionId)
            db.close()
            db = open()
            repository = TrainingRepository(db)
            assertEquals(fifth.measurements, repository.movementEvidence(session.sessionId, 5)!!.measurements)
            assertEquals(fifth.analyses, repository.movementEvidence(session.sessionId, 5)!!.analyses,
                "Persisted analyzer geometry must survive closing and reopening Room")
            val retry = TrainingSessionProcessor(repository, directory, decoder, "fixture-v1")
            assertEquals(count, retry.process(session.sessionId))
            assertEquals(1, decodes)
            assertEquals(identities, repository.movements(session.sessionId).map { it.movementId })
        } finally { db.close(); context.deleteDatabase(databaseName); directory.deleteRecursively() }
    }

    @Test fun reanalysisReusesMlsCreatesFreshMovementsAndPublishesOnSuccess() {
        val frames = buildSyntheticTenPunchFrames()

        val context = ApplicationProvider.getApplicationContext<Context>()
        val databaseName = "reanalysis-${trainingId()}"
        val directory = kotlin.io.path.createTempDirectory("reanalysis-test").toFile()
        val db = Room.databaseBuilder(context, KarateTrainingDatabase::class.java, databaseName).allowMainThreadQueries().build()
        try {
            val repository = TrainingRepository(db)
            val user = TrainingUser()
            repository.createUser(user)
            val session = RecordingSession(userId = user.userId, startedAtMs = 1000,
                activityKey = AssistedCaptureSetup.ACTIVITY_KEY, guided = false, expectedRepetitions = 10)
            val source = File(directory, "master.mp4").apply { writeText("decoder replaced by fixture") }
            repository.beginSession(session, MasterRecording(sessionId = session.sessionId, filePath = source.path, createdAtMs = 1000))
            repository.finishRecording(session.sessionId, frames.last().timestampMs * 1000)
            repository.enqueue(session.sessionId)

            var decodes = 0
            val decoder = object : VideoPoseProcessor {
                override fun processVideo(videoFile: File, onProgress: (Float, Long) -> Unit): List<PoseFrame> { decodes++; return frames }
            }
            val processor = TrainingSessionProcessor(repository, directory, decoder, "fixture-v1")
            val initialCount = processor.process(session.sessionId)
            assertEquals(10, initialCount)
            assertEquals(1, decodes)

            val initialRun = assertNotNull(repository.currentRun(session.sessionId))
            assertEquals(RunMode.INITIAL, initialRun.mode)
            assertEquals(RunState.COMPLETED, initialRun.state)
            assertTrue(initialRun.isCurrent)

            val initialMovements = repository.movements(session.sessionId)
            val initialMovementIds = initialMovements.map { it.movementId }
            assertEquals(10, initialMovementIds.size)

            assertTrue(repository.canReanalyze(session.sessionId))
            val (reanalysisRun, _) = repository.prepareReanalysisRun(session.sessionId)
            assertEquals(RunMode.REANALYSIS, reanalysisRun.mode)
            assertEquals(RunState.PROCESSING, reanalysisRun.state)
            assertFalse(reanalysisRun.isCurrent)
            assertEquals(initialRun.sourceLandmarkTrackId, reanalysisRun.sourceLandmarkTrackId)

            val reanalyzedCount = processor.processReanalysis(session.sessionId, reanalysisRun.runId)
            assertEquals(10, reanalyzedCount)
            assertEquals(1, decodes, "MediaPipe video pose processor must NOT be rerun during reanalysis")

            val currentRun = assertNotNull(repository.currentRun(session.sessionId))
            assertEquals(reanalysisRun.runId, currentRun.runId)
            assertEquals(RunMode.REANALYSIS, currentRun.mode)
            assertEquals(RunState.COMPLETED, currentRun.state)
            assertTrue(currentRun.isCurrent)
            assertEquals(TrainingSessionProcessor.SEGMENTATION_VERSION, currentRun.segmenterVersion)

            val oldRun = repository.run(initialRun.runId)!!
            assertFalse(oldRun.isCurrent)

            val newMovements = repository.movements(session.sessionId)
            val newMovementIds = newMovements.map { it.movementId }
            assertEquals(10, newMovementIds.size)
            assertTrue(newMovementIds.none { it in initialMovementIds }, "New movement IDs must be generated for reanalysis")
            assertTrue(newMovements.all { it.runId == reanalysisRun.runId })
            assertTrue(newMovements.all { it.analysisFrameUs != null })

            val firstMovementEvidence = repository.movementEvidence(session.sessionId, 1)!!
            assertEquals(1, firstMovementEvidence.analyses.size)
            assertEquals("straight_punch_target", firstMovementEvidence.analyses.first().analyzerKey)
            assertTrue(firstMovementEvidence.measurements.isNotEmpty())

            val gedanMeasurement = firstMovementEvidence.measurements.firstOrNull { it.measurementKey == TrainingMeasurements.PUNCH_GEDAN_TARGET_ANGLE_ERROR_DEG }
            assertNotNull(gedanMeasurement)
            assertEquals(ResultState.ABSTAINED, gedanMeasurement.state)
            assertEquals("provisional_gedan_target_unspecified", gedanMeasurement.reason)
            assertNull(gedanMeasurement.numericValue)

            val canonicalUs = newMovements.first().analysisFrameUs
            assertNotNull(canonicalUs)
            assertTrue(firstMovementEvidence.measurements.all { it.occurrenceUs == canonicalUs })
            assertTrue(firstMovementEvidence.measurements.all { it.frameIndex != null })
        } finally {
            db.close()
            context.deleteDatabase(databaseName)
            directory.deleteRecursively()
        }
    }

    @Test fun reanalysisFailureLeavesPreviousRunCurrent() {
        val frames = (0..620).map { index ->
            val t = index * 25L
            fun sample(x: Float, y: Float) = PoseLandmarkSample(Point3(x, y, 0f), Point3(x, y, 0f), 0.9f, 0.9f, LandmarkSource.OBSERVED)
            PoseFrame(t, mapOf(
                PoseLandmarkId.LEFT_SHOULDER to sample(0.4f, 0.3f), PoseLandmarkId.RIGHT_SHOULDER to sample(0.6f, 0.3f),
                PoseLandmarkId.LEFT_ELBOW to sample(0.4f, 0.5f), PoseLandmarkId.RIGHT_ELBOW to sample(0.6f, 0.5f),
                PoseLandmarkId.LEFT_WRIST to sample(0.4f, 0.6f), PoseLandmarkId.RIGHT_WRIST to sample(0.6f, 0.6f),
                PoseLandmarkId.LEFT_HIP to sample(0.4f, 0.6f), PoseLandmarkId.RIGHT_HIP to sample(0.6f, 0.6f)))
        }

        val context = ApplicationProvider.getApplicationContext<Context>()
        val databaseName = "reanalysis-failure-${trainingId()}"
        val directory = kotlin.io.path.createTempDirectory("reanalysis-failure-test").toFile()
        val db = Room.databaseBuilder(context, KarateTrainingDatabase::class.java, databaseName).allowMainThreadQueries().build()
        try {
            val repository = TrainingRepository(db)
            val user = TrainingUser()
            repository.createUser(user)
            val session = RecordingSession(userId = user.userId, startedAtMs = 1000,
                activityKey = AssistedCaptureSetup.ACTIVITY_KEY, guided = false, expectedRepetitions = 10)
            val source = File(directory, "master.mp4").apply { writeText("fixture") }
            repository.beginSession(session, MasterRecording(sessionId = session.sessionId, filePath = source.path, createdAtMs = 1000))
            repository.finishRecording(session.sessionId, frames.last().timestampMs * 1000)

            val decoder = object : VideoPoseProcessor {
                override fun processVideo(videoFile: File, onProgress: (Float, Long) -> Unit): List<PoseFrame> = frames
            }
            val processor = TrainingSessionProcessor(repository, directory, decoder, "fixture-v1")
            processor.process(session.sessionId)

            val initialRun = assertNotNull(repository.currentRun(session.sessionId))
            assertTrue(initialRun.isCurrent)
            val initialMovements = repository.movements(session.sessionId)

            val (reanalysisRun, _) = repository.prepareReanalysisRun(session.sessionId)

            val failingProcessor = TrainingSessionProcessor(repository, directory, decoder, "fixture-v1",
                segment = { _, _, _, _ -> throw RuntimeException("Simulated segmentation failure") })

            assertFailsWith<RuntimeException> {
                failingProcessor.processReanalysis(session.sessionId, reanalysisRun.runId)
            }

            val failedRun = repository.run(reanalysisRun.runId)!!
            assertEquals(RunState.FAILED, failedRun.state)
            assertFalse(failedRun.isCurrent)

            val currentRun = assertNotNull(repository.currentRun(session.sessionId))
            assertEquals(initialRun.runId, currentRun.runId)
            assertTrue(currentRun.isCurrent)
            assertEquals(initialMovements.map { it.movementId }, repository.movements(session.sessionId).map { it.movementId })
        } finally {
            db.close()
            context.deleteDatabase(databaseName)
            directory.deleteRecursively()
        }
    }

    @Test fun reanalysisWithZeroDetectionsPublishesSuccessfully() {
        val frames = listOf(
            PoseFrame(0, emptyMap()),
            PoseFrame(100, emptyMap())
        )

        val context = ApplicationProvider.getApplicationContext<Context>()
        val databaseName = "reanalysis-zero-${trainingId()}"
        val directory = kotlin.io.path.createTempDirectory("reanalysis-zero-test").toFile()
        val db = Room.databaseBuilder(context, KarateTrainingDatabase::class.java, databaseName).allowMainThreadQueries().build()
        try {
            val repository = TrainingRepository(db)
            val user = TrainingUser()
            repository.createUser(user)
            val session = RecordingSession(userId = user.userId, startedAtMs = 1000,
                activityKey = AssistedCaptureSetup.ACTIVITY_KEY, guided = false, expectedRepetitions = 10)
            val source = File(directory, "master.mp4").apply { writeText("fixture") }
            repository.beginSession(session, MasterRecording(sessionId = session.sessionId, filePath = source.path, createdAtMs = 1000))
            repository.finishRecording(session.sessionId, 100_000)

            val decoder = object : VideoPoseProcessor {
                override fun processVideo(videoFile: File, onProgress: (Float, Long) -> Unit): List<PoseFrame> = frames
            }
            val processor = TrainingSessionProcessor(repository, directory, decoder, "fixture-v1",
                segment = { seqId, path, _, _ ->
                    RetrospectiveSessionResult(
                        sequenceId = seqId,
                        masterVideoPath = path,
                        totalFrames = 0,
                        totalDurationMs = 0L,
                        detectedMovementCount = 0,
                        movements = emptyList(),
                        kinematicsTimeline = emptyList(),
                        stats = RetrospectiveSignalStats(0.0, 0.0, 0.0, 0.0, 0.1580),
                        qomTimeline = emptyList()
                    )
                })

            val count = processor.process(session.sessionId)
            assertEquals(0, count)

            val currentRun = assertNotNull(repository.currentRun(session.sessionId))
            assertEquals(RunState.COMPLETED, currentRun.state)
            assertTrue(currentRun.isCurrent)
            assertEquals(0, repository.movementCount(session.sessionId))
            assertTrue(repository.movements(session.sessionId).isEmpty())
        } finally {
            db.close()
            context.deleteDatabase(databaseName)
            directory.deleteRecursively()
        }
    }

    @Test fun landmarkReprocessGeneratesNewLandmarkTrackAndPublishesRun() {
        val frames = (0..200).map { index ->
            val t = index * 25L
            fun sample(x: Float, y: Float) = PoseLandmarkSample(Point3(x, y, 0f), Point3(x, y, 0f), 0.9f, 0.9f, LandmarkSource.OBSERVED)
            PoseFrame(t, mapOf(
                PoseLandmarkId.LEFT_SHOULDER to sample(0.4f, 0.3f), PoseLandmarkId.RIGHT_SHOULDER to sample(0.6f, 0.3f),
                PoseLandmarkId.LEFT_ELBOW to sample(0.4f, 0.5f), PoseLandmarkId.RIGHT_ELBOW to sample(0.6f, 0.5f),
                PoseLandmarkId.LEFT_WRIST to sample(0.4f, 0.6f), PoseLandmarkId.RIGHT_WRIST to sample(0.6f, 0.6f),
                PoseLandmarkId.LEFT_HIP to sample(0.4f, 0.6f), PoseLandmarkId.RIGHT_HIP to sample(0.6f, 0.6f)))
        }

        val context = ApplicationProvider.getApplicationContext<Context>()
        val directory = kotlin.io.path.createTempDirectory("landmark-reprocess-test").toFile()
        val db = Room.inMemoryDatabaseBuilder(context, KarateTrainingDatabase::class.java).allowMainThreadQueries().build()
        try {
            val repository = TrainingRepository(db)
            val user = TrainingUser()
            repository.createUser(user)
            val session = RecordingSession(userId = user.userId, startedAtMs = 1000,
                activityKey = AssistedCaptureSetup.ACTIVITY_KEY, guided = false, expectedRepetitions = 5)
            val source = File(directory, "master.mp4").apply { writeText("fake-video-content") }
            repository.beginSession(session, MasterRecording(sessionId = session.sessionId, filePath = source.path, createdAtMs = 1000))
            repository.finishRecording(session.sessionId, frames.last().timestampMs * 1000)

            var decodeCount = 0
            val decoder = object : VideoPoseProcessor {
                override fun processVideo(videoFile: File, onProgress: (Float, Long) -> Unit): List<PoseFrame> {
                    decodeCount++
                    return frames
                }
            }

            // 1. Initial processing
            val processor1 = TrainingSessionProcessor(repository, directory, decoder, "fixture-v1", trackConfiguration = "full_v1")
            processor1.process(session.sessionId)
            assertEquals(1, decodeCount)

            val initialRun = assertNotNull(repository.currentRun(session.sessionId))
            assertEquals(RunMode.INITIAL, initialRun.mode)
            val initialTrackId = initialRun.sourceLandmarkTrackId
            assertNotNull(initialTrackId)

            assertTrue(repository.canReprocessLandmarks(session.sessionId))

            // 2. Prepare and execute landmark reprocess
            val reprocessRun = repository.prepareLandmarkReprocessRun(session.sessionId)
            assertEquals(RunMode.LANDMARK_REPROCESS, reprocessRun.mode)
            assertEquals(RunState.PROCESSING, reprocessRun.state)

            val processor2 = TrainingSessionProcessor(repository, directory, decoder, "fixture-v2", trackConfiguration = "heavy_v1")
            processor2.processLandmarkReprocess(session.sessionId, reprocessRun.runId)
            assertEquals(2, decodeCount, "Reprocess landmarks must invoke pose decoder")

            val currentRun = assertNotNull(repository.currentRun(session.sessionId))
            assertEquals(reprocessRun.runId, currentRun.runId)
            assertEquals(RunState.COMPLETED, currentRun.state)
            assertTrue(currentRun.isCurrent)
            assertNotNull(currentRun.sourceLandmarkTrackId)
            assertNotEquals(initialTrackId, currentRun.sourceLandmarkTrackId, "New landmark track must be generated")

            // Original initial run and track must be retained
            val oldRun = assertNotNull(repository.run(initialRun.runId))
            assertFalse(oldRun.isCurrent)
            val recording = requireNotNull(repository.recording(session.sessionId))
            val tracks = repository.tracks(recording.recordingId)
            assertNotNull(tracks.firstOrNull { it.landmarkTrackId == initialTrackId })
            assertNotNull(tracks.firstOrNull { it.landmarkTrackId == currentRun.sourceLandmarkTrackId })
        } finally {
            db.close()
            directory.deleteRecursively()
        }
    }

    @Test fun landmarkReprocessFailurePreservesPreviousRunAndRetainsNewMLS() {
        val frames = (0..100).map { index ->
            val t = index * 25L
            fun sample(x: Float, y: Float) = PoseLandmarkSample(Point3(x, y, 0f), Point3(x, y, 0f), 0.9f, 0.9f, LandmarkSource.OBSERVED)
            PoseFrame(t, mapOf(
                PoseLandmarkId.LEFT_SHOULDER to sample(0.4f, 0.3f), PoseLandmarkId.RIGHT_SHOULDER to sample(0.6f, 0.3f)))
        }

        val context = ApplicationProvider.getApplicationContext<Context>()
        val directory = kotlin.io.path.createTempDirectory("landmark-reprocess-fail-test").toFile()
        val db = Room.inMemoryDatabaseBuilder(context, KarateTrainingDatabase::class.java).allowMainThreadQueries().build()
        try {
            val repository = TrainingRepository(db)
            val user = TrainingUser()
            repository.createUser(user)
            val session = RecordingSession(userId = user.userId, startedAtMs = 1000,
                activityKey = AssistedCaptureSetup.ACTIVITY_KEY, guided = false, expectedRepetitions = 5)
            val source = File(directory, "master.mp4").apply { writeText("fake-video-content") }
            repository.beginSession(session, MasterRecording(sessionId = session.sessionId, filePath = source.path, createdAtMs = 1000))
            repository.finishRecording(session.sessionId, frames.last().timestampMs * 1000)

            val decoder = object : VideoPoseProcessor {
                override fun processVideo(videoFile: File, onProgress: (Float, Long) -> Unit): List<PoseFrame> = frames
            }
            val processor1 = TrainingSessionProcessor(repository, directory, decoder, "fixture-v1", trackConfiguration = "full_v1")
            processor1.process(session.sessionId)

            val initialRun = assertNotNull(repository.currentRun(session.sessionId))
            val reprocessRun = repository.prepareLandmarkReprocessRun(session.sessionId)

            val failingProcessor = TrainingSessionProcessor(
                repository, directory, decoder, "fixture-v2", trackConfiguration = "heavy_v1",
                segment = { _, _, _, _ -> throw RuntimeException("Simulated segmentation crash after landmark extraction") }
            )

            assertFailsWith<RuntimeException> {
                failingProcessor.processLandmarkReprocess(session.sessionId, reprocessRun.runId)
            }

            // Previous run remains current
            val currentRun = assertNotNull(repository.currentRun(session.sessionId))
            assertEquals(initialRun.runId, currentRun.runId)
            assertTrue(currentRun.isCurrent)

            // Failed run is marked failed
            val failedRun = assertNotNull(repository.run(reprocessRun.runId))
            assertEquals(RunState.FAILED, failedRun.state)
            assertFalse(failedRun.isCurrent)

            // The landmark track created by the reprocess step must be preserved!
            assertNotNull(failedRun.sourceLandmarkTrackId)
            val recording = requireNotNull(repository.recording(session.sessionId))
            val tracks = repository.tracks(recording.recordingId)
            assertNotNull(tracks.firstOrNull { it.landmarkTrackId == failedRun.sourceLandmarkTrackId })
        } finally {
            db.close()
            directory.deleteRecursively()
        }
    }
}
