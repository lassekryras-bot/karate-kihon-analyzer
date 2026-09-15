package dk.lasse.karatecliprecorder.training

import android.content.Context
import android.graphics.Bitmap
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import dk.lasse.karatecliprecorder.sharedcapture.CaptureOutcome
import dk.lasse.karatecliprecorder.sharedcapture.CaptureType
import java.io.File
import kotlin.test.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28])
class CaptureRecoveryTest {
    @Test fun pendingPhotoSurvivesProcessRecoveryAsInterruptedEvidence() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val db = Room.inMemoryDatabaseBuilder(context, KarateTrainingDatabase::class.java)
            .allowMainThreadQueries().build()
        val directory = kotlin.io.path.createTempDirectory("photo-recovery").toFile()
        try {
            val repo = TrainingRepository(db, TrainingStorage(directory))
            val user = TrainingUser(); repo.createUser(user)
            val session = RecordingSession(userId = user.userId, startedAtMs = 1,
                state = SessionState.PREPARING)
            val id = trainingId()
            val recording = MasterRecording(id, session.sessionId, "photos/$id.jpg", 1,
                captureType = CaptureType.PHOTO, mimeType = "image/jpeg")
            repo.beginSession(session, recording)
            val file = repo.file(recording.filePath).apply { parentFile!!.mkdirs() }
            Bitmap.createBitmap(12, 8, Bitmap.Config.ARGB_8888).useBitmap {
                file.outputStream().use { output -> assertTrue(it.compress(Bitmap.CompressFormat.JPEG, 90, output)) }
            }

            val interrupted = repo.recoverInterruptedWork().single { it.sessionId == session.sessionId }
            recoverPendingCapture(repo, interrupted, recording)

            assertEquals(SourceState.AVAILABLE, repo.recording(session.sessionId)!!.sourceState)
            val recovered = repo.session(session.sessionId)!!
            assertEquals(CaptureOutcome.INTERRUPTED.name, recovered.captureOutcome)
            assertEquals("process_recovered_after_capture_interruption", recovered.interruptionReason)
            assertEquals("INTERRUPTION_DETECTED", repo.events(session.sessionId).single().type)
        } finally {
            db.close()
            directory.deleteRecursively()
        }
    }

    private inline fun Bitmap.useBitmap(block: (Bitmap) -> Unit) {
        try { block(this) } finally { recycle() }
    }
}
