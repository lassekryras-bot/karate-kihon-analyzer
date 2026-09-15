package dk.lasse.karatecliprecorder.training

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import dk.lasse.karateanalyzer.capture.PoseReplayJson
import dk.lasse.karateanalyzer.capture.retrospective.VideoPoseProcessor
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
        verifyPersistence(PoseReplayJson.decode(fixtureFile.readText()).frames, null)
    }

    @Test fun syntheticContinuousTenPunchSessionPersistsExactlyTenMovements() {
        val frames = (0..620).map { index ->
            val t = index * 25L
            val cycle = (t - 500).floorDiv(1500)
            val phase = (t - 500).mod(1500) / 1000.0
            val reach = if (cycle in 0..9 && phase < 0.5) kotlin.math.sin(phase * 2 * Math.PI).toFloat() * 0.35f else 0f
            fun sample(x: Float, y: Float) = PoseLandmarkSample(Point3(x, y, 0f), visibility = 0.9f, presence = 0.9f, source = LandmarkSource.OBSERVED)
            PoseFrame(t, mapOf(
                PoseLandmarkId.LEFT_SHOULDER to sample(0.4f, 0.3f), PoseLandmarkId.RIGHT_SHOULDER to sample(0.6f, 0.3f),
                PoseLandmarkId.LEFT_ELBOW to sample(0.4f, 0.5f), PoseLandmarkId.RIGHT_ELBOW to sample(0.6f, 0.5f),
                PoseLandmarkId.LEFT_WRIST to sample(0.4f - reach, 0.6f), PoseLandmarkId.RIGHT_WRIST to sample(0.6f + reach, 0.6f),
                PoseLandmarkId.LEFT_HIP to sample(0.4f, 0.6f), PoseLandmarkId.RIGHT_HIP to sample(0.6f, 0.6f)))
        }
        verifyPersistence(frames, 10)
    }

    private fun verifyPersistence(frames: List<PoseFrame>, expectedCount: Int?) {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val databaseName = "processor-${trainingId()}"
        val directory = kotlin.io.path.createTempDirectory("processor-test").toFile()
        fun open() = Room.databaseBuilder(context, KarateTrainingDatabase::class.java, databaseName).allowMainThreadQueries().build()
        var db = open()
        try {
            var repository = TrainingRepository(db)
            val user = TrainingUser()
            repository.createUser(user)
            val session = RecordingSession(userId = user.userId, startedAtMs = 1000, activityKey = "guided_jodan_session", guided = true, expectedRepetitions = 10)
            val source = File(directory, "master.mp4").apply { writeText("decoder replaced by captured pose fixture") }
            repository.beginSession(session, MasterRecording(sessionId = session.sessionId, filePath = source.path, createdAtMs = 1000))
            repository.finishRecording(session.sessionId, frames.last().timestampMs * 1000)
            repository.addEvent(SessionEvent(sessionId = session.sessionId, type = "cue", timestampUs = 400_000, data = "Ichi"))
            var decodes = 0
            val decoder = object : VideoPoseProcessor {
                override fun processVideo(videoFile: File, onProgress: (Float, Long) -> Unit): List<PoseFrame> { decodes++; return frames }
            }
            val processor = TrainingSessionProcessor(repository, directory, decoder, "fixture-v1")
            val count = processor.process(session.sessionId)
            println("Continuous ${if (expectedCount == null) "real fixture" else "synthetic fixture"}: $count movements, 1 landmark decode")
            if (expectedCount != null) assertEquals(expectedCount, count)
            else assertTrue(count >= 10, "Real fixture has ten punches and may contain later reset movements; actual count=$count")
            assertEquals(count, repository.movementCount(session.sessionId))
            val fifth = assertNotNull(repository.movementEvidence(session.sessionId, 5))
            assertEquals(1, fifth.landmarkTracks.size)
            assertEquals(1, fifth.analyses.size)
            assertEquals(2, fifth.measurements.size)
            assertNotNull(fifth.observation)
            assertEquals(setOf("straight_punch", "jodan"), fifth.labels.map { it.machineKey }.toSet())
            val identities = repository.movements(session.sessionId).map { it.movementId }
            repository.deleteVideo(session.sessionId)
            db.close()
            db = open()
            repository = TrainingRepository(db)
            assertEquals(fifth.measurements, repository.movementEvidence(session.sessionId, 5)!!.measurements)
            val retry = TrainingSessionProcessor(repository, directory, decoder, "fixture-v1")
            assertEquals(count, retry.process(session.sessionId))
            assertEquals(1, decodes)
            assertEquals(identities, repository.movements(session.sessionId).map { it.movementId })
        } finally { db.close(); context.deleteDatabase(databaseName); directory.deleteRecursively() }
    }
}
