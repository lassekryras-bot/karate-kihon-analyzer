package dk.lasse.karatecliprecorder.recordings

import java.io.File
import org.junit.Test
import kotlin.test.*

class QueueManagerTrayArchitectureTest {
    private val root = generateSequence(File(System.getProperty("user.dir"))) { it.parentFile }
        .first { it.resolve("app/src/main/AndroidManifest.xml").isFile }
    private val sources = root.resolve("app/src/main/java/dk/lasse/karatecliprecorder")

    @Test fun trayUsesOneReadOnlyQueueObserverAndLivesBelowSharedHeaders() {
        val manager = sources.resolve("recordings/QueueManager.kt").readText()
        assertTrue(manager.contains("repo.jobs()"))
        assertFalse(manager.contains("RecordingQueue.schedule"))
        assertFalse(manager.contains("processNow"))
        val headers = sources.resolve("PageHeaders.kt").readText()
        assertTrue(headers.indexOf("addView(header") < headers.indexOf("addView(QueueManagerTrayView"))
        assertTrue(headers.indexOf("addView(QueueManagerTrayView") < headers.indexOf("addView(scroller"))
        assertTrue(sources.resolve("TrainScreenView.kt").readText().contains("QueueManagerTrayView"))
        assertTrue(sources.resolve("recordings/RecordingsActivity.kt").readText().contains("QueueManagerTrayView"))
    }

    @Test fun recordingContextAndAccessibilityControlsAreExplicit() {
        val capture = sources.resolve("assisted/AssistedCaptureActivity.kt").readText()
        assertTrue(capture.contains("state == AssistedCaptureState.RECORDING"))
        assertTrue(capture.contains("QueueManager.setRecordingHidden"))
        val tray = sources.resolve("recordings/QueueManagerTrayView.kt").readText()
        assertTrue(tray.contains("minimumHeight = dp(48)"))
        assertTrue(tray.contains("contentDescription"))
        assertTrue(tray.contains("setStateDescription"))
        assertFalse(tray.contains("RecordingQueue"))
    }
}
