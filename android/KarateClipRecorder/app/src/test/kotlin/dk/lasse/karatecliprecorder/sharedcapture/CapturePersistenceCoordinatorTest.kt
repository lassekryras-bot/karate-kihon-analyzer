package dk.lasse.karatecliprecorder.sharedcapture

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import dk.lasse.karatecliprecorder.training.*
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.util.UUID
import java.util.concurrent.Executors
import kotlin.test.*

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28])
class CapturePersistenceCoordinatorTest {
    private val context = ApplicationProvider.getApplicationContext<Context>()
    private lateinit var services: TrainingServices
    private lateinit var persistence: CapturePersistenceCoordinator
    private val userId = UUID.randomUUID().toString()
    private val executor = Executors.newSingleThreadExecutor()

    @Before fun setup() {
        TrainingServices.closeForTests()
        context.deleteDatabase("karate-training.db")
        services = TrainingServices.get(context)
        worker { services.repository.createUser(TrainingUser(userId, 1)) }
        persistence = CapturePersistenceCoordinator(services)
    }

    @After fun cleanup() {
        CaptureFinalizedEvents.listener = null
        TrainingServices.closeForTests()
        context.deleteDatabase("karate-training.db")
        executor.shutdownNow()
    }

    @Test fun photoUsesCanonicalStorageAndReturnsDurableIdentityWithoutQueueWork() {
        val emitted = mutableListOf<PersistedCaptureResult>()
        CaptureFinalizedEvents.listener = { emitted += it }
        val prepared = worker { persistence.prepare(SharedCaptureRequests.readyOsuSelfie(), userId, "front:test") }
        assertEquals("photos/${prepared.recording.recordingId}.jpg", prepared.recording.filePath)
        prepared.file.writeBytes(byteArrayOf(0xff.toByte(), 0xd8.toByte(), 1, 2, 0xff.toByte(), 0xd9.toByte()))
        val result = worker { persistence.finalize(prepared, CaptureOutcome.COMPLETED, width = 100, height = 80) }
        assertTrue(result.mediaFinalized)
        assertEquals(prepared.recording.recordingId, result.captureId)
        val recording = worker { services.repository.recording(result.sessionId)!! }
        val session = worker { services.repository.session(result.sessionId)!! }
        assertEquals(CaptureType.PHOTO, recording.captureType)
        assertEquals("ready-osu", session.activityKey)
        assertEquals("learning_path", session.callerId)
        assertEquals(CaptureOutcome.COMPLETED.name, session.captureOutcome)
        assertNull(worker { services.repository.job(result.sessionId) })
        assertEquals(listOf(result), emitted)
    }

    @Test fun invalidFinalizedPhotoIsPersistedAsFailureAndEmitsNoSuccess() {
        var emitted = false
        CaptureFinalizedEvents.listener = { emitted = true }
        val prepared = worker { persistence.prepare(SharedCaptureRequests.readyOsuSelfie(), userId, "front:test") }
        prepared.file.writeBytes(byteArrayOf(1))
        val result = worker { persistence.finalize(prepared, CaptureOutcome.COMPLETED, width = 0, height = 0) }
        assertFalse(result.mediaFinalized)
        assertEquals(CaptureOutcome.FAILED, result.outcome)
        assertFalse(emitted)
        assertEquals(SourceState.FAILED, worker { services.repository.recording(result.sessionId)!!.sourceState })
        assertEquals(CaptureOutcome.FAILED.name, worker { services.repository.session(result.sessionId)!!.captureOutcome })
    }

    @Test fun usableInterruptedCaptureKeepsMediaAndPersistsOutcomeAndReason() {
        val prepared = worker { persistence.prepare(SharedCaptureRequests.readyOsuSelfie(), userId, "front:test") }
        prepared.file.writeBytes(byteArrayOf(0xff.toByte(), 0xd8.toByte(), 1, 2, 0xff.toByte(), 0xd9.toByte()))
        val result = worker { persistence.finalize(prepared, CaptureOutcome.INTERRUPTED, width = 100, height = 80) }
        val session = worker { services.repository.session(result.sessionId)!! }
        assertTrue(result.mediaFinalized)
        assertEquals(CaptureOutcome.INTERRUPTED, result.outcome)
        assertEquals(CaptureOutcome.INTERRUPTED.name, session.captureOutcome)
        assertEquals("interrupted", session.interruptionReason)
        assertEquals("CAPTURE_OUTCOME", worker { services.repository.events(result.sessionId).single().type })
    }

    private fun <T> worker(block: () -> T): T = executor.submit<T> { block() }.get()
}
