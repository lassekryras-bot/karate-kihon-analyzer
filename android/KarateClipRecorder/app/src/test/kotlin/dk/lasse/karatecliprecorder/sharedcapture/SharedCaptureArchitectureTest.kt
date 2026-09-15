package dk.lasse.karatecliprecorder.sharedcapture

import java.io.File
import kotlin.test.*
import org.junit.Test

class SharedCaptureArchitectureTest {
    private val root = generateSequence(File(System.getProperty("user.dir"))) { it.parentFile }
        .first { it.resolve("app/src/main/AndroidManifest.xml").isFile }
    private val sources = root.resolve("app/src/main/java/dk/lasse/karatecliprecorder")

    @Test fun oneBackendOwnsCameraXVideoAndPhotoUseCases() {
        val cameraOwners = sources.walkTopDown().filter { it.extension == "kt" }
            .filter { it.readText().contains("ProcessCameraProvider") }.toList()
        assertEquals(listOf(sources.resolve("sharedcapture/SharedCameraCaptureBackend.kt")), cameraOwners)
        val backend = cameraOwners.single().readText()
        assertTrue(backend.contains("VideoCapture.Builder"))
        assertTrue(backend.contains("ImageCapture.Builder"))
        assertTrue(sources.resolve("learning/ReadyOsuSelfieCamera.kt").readText().contains("SharedCameraCaptureBackend"))
        assertTrue(sources.resolve("CameraXRecordingAdapter.kt").readText().contains("SharedCameraCaptureBackend"))
    }

    @Test fun recorderPersistenceDoesNotOwnProcessingQueueImplementation() {
        val adapter = sources.resolve("CameraXRecordingAdapter.kt").readText()
        val persistence = sources.resolve("sharedcapture/CapturePersistenceCoordinator.kt").readText()
        assertFalse(adapter.contains("RecordingQueue"))
        assertFalse(adapter.contains("import androidx.work"))
        assertFalse(persistence.contains("RecordingQueue."))
        assertFalse(persistence.contains("import androidx.work"))
        assertTrue(persistence.contains("CaptureFinalizedEvents.publish"))
    }

    @Test fun learningShellDoesNotOwnCameraOrPersistence() {
        val shell = sources.resolve("learningactivity/ActivityShellView.kt").readText()
        assertFalse(shell.contains("SharedCameraCaptureBackend"))
        assertFalse(shell.contains("CapturePersistenceCoordinator"))
        assertFalse(shell.contains("ProcessCameraProvider"))
    }
}
