package dk.lasse.karatecliprecorder.assisted

import java.io.File
import org.junit.Test
import kotlin.test.*

class AssistedCaptureArchitectureTest {
    private val root = generateSequence(File(System.getProperty("user.dir"))) { it.parentFile }
        .first { it.resolve("app/src/main/AndroidManifest.xml").isFile }
    private val sources = root.resolve("app/src/main/java/dk/lasse/karatecliprecorder")

    @Test fun skillCoachCardOpensRegisteredDedicatedPage() {
        val activity = sources.resolve("MainActivity.kt").readText()
        val handler = activity.substringAfter("private fun handleSkillCoachAction").substringBefore("private fun showHomeUi")
        assertTrue(handler.contains("action == SkillCoachAction.RECORD_AND_ANALYZE"))
        assertTrue(handler.contains("startActivity(android.content.Intent(this, dk.lasse.karatecliprecorder.assisted.AssistedCaptureActivity::class.java))"))
        assertTrue(root.resolve("app/src/main/AndroidManifest.xml").readText().contains(".assisted.AssistedCaptureActivity"))
    }

    @Test fun assistedRouteUsesRepositoryAndNoMicrophoneOrSegmentation() {
        val page = sources.resolve("assisted/AssistedCaptureActivity.kt").readText()
        assertTrue(page.contains("Manifest.permission.CAMERA"))
        assertFalse(page.contains("RECORD_AUDIO"))
        assertFalse(page.contains("trainingDao"))
        assertFalse(page.contains(".process(id)"))
        assertFalse(page.contains(".ensureLandmarks(id)"))
        assertTrue(page.contains("previewOnly = true"))
        val adapter = sources.resolve("CameraXRecordingAdapter.kt").readText()
        assertFalse(adapter.contains("withAudioEnabled"))
        assertTrue(adapter.indexOf("persistence.prepare(request") < adapter.indexOf("onPrepared startPrepared@"))
        assertTrue(adapter.indexOf("onPrepared startPrepared@") < adapter.indexOf("camera.startVideo(prepared.file)"))
        assertTrue(adapter.indexOf("persistence.finalize(prepared") < adapter.indexOf("onSaved(RecordingResult"))
        assertFalse(adapter.contains("RecordingQueue"))
        val media = sources.resolve("training/RecordedVideoMetadata.kt").readText()
        assertTrue(media.contains("startsWith(\"audio/\")"))
        assertTrue(media.contains("getFrameAtTime(0)"))
    }
}
