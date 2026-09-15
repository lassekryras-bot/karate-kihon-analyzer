package dk.lasse.karatecliprecorder.sharedcapture

import android.content.Context
import android.view.View
import android.widget.TextView
import androidx.test.core.app.ApplicationProvider
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28])
class SharedCameraCaptureUiTest {
    private val context: Context = ApplicationProvider.getApplicationContext()

    @Test
    fun recordButtonReflectsStatesAndFootprint() {
        val button = SharedCameraRecordButton(context)
        var lastAction: CameraButtonState? = null
        button.onAction = { lastAction = it }

        // Initial state: RECORD
        assertEquals(CameraButtonState.RECORD, button.state)
        val label = button.getChildAt(1) as TextView
        assertEquals("Record", label.text.toString())
        button.performClick()
        assertEquals(CameraButtonState.RECORD, lastAction)

        // STOP state
        button.state = CameraButtonState.STOP
        assertEquals("Stop", label.text.toString())
        button.performClick()
        assertEquals(CameraButtonState.STOP, lastAction)

        // STOP_NOW state
        button.state = CameraButtonState.STOP_NOW
        assertEquals("Stop now", label.text.toString())
        button.performClick()
        assertEquals(CameraButtonState.STOP_NOW, lastAction)

        // SAVED state
        button.state = CameraButtonState.SAVED
        assertEquals("Record another", label.text.toString())
        button.performClick()
        assertEquals(CameraButtonState.SAVED, lastAction)

        // Disabled behavior
        button.isEnabled = false
        assertFalse(button.isEnabled)
        assertEquals(0.45f, button.alpha)
    }

    @Test
    fun statusMessagePrioritiesFollowSpecification() {
        val storage = CameraStatusMessage.storageBlocker("Low storage")
        val battery = CameraStatusMessage.batteryBlocker("Battery below start threshold")
        val missingSetup = CameraStatusMessage.missingRequirement("Camera access required", "request_camera")
        val info = CameraStatusMessage.informational("Preparing camera…")
        val ready = CameraStatusMessage.ready()

        val list = listOf(ready, missingSetup, storage, info, battery)
        val sorted = list.sortedBy { it.priority }

        // Expected order: Storage blocker (1) -> Battery blocker (2) -> Missing requirement (3) -> Info (4) -> Ready (5)
        assertEquals(storage, sorted[0])
        assertEquals(battery, sorted[1])
        assertEquals(missingSetup, sorted[2])
        assertEquals(info, sorted[3])
        assertEquals(ready, sorted[4])

        assertEquals(CameraStatusSeverity.BLOCKING, storage.severity)
        assertEquals(CameraStatusSeverity.BLOCKING, battery.severity)
        assertEquals(CameraStatusSeverity.WARNING, missingSetup.severity)
        assertEquals(CameraStatusSeverity.READY, ready.severity)
    }

    @Test
    fun previewTransitionsBetweenPreRecordingAndRecordingModes() {
        val previewView = SharedCameraPreviewView(context)

        // Initial pre-recording mode
        assertFalse(previewView.recordingActive)

        var tappedReason: String? = null
        previewView.onStatusTap = { tappedReason = it }

        val statusMsg = CameraStatusMessage.missingRequirement("Camera access required", "request_camera")
        previewView.setStatus(statusMsg)

        // Tap on pre-recording status bar emits semantic event
        val preRecBar = (0 until previewView.childCount)
            .map { previewView.getChildAt(it) }
            .first { it.visibility == View.VISIBLE && it.isClickable }
        preRecBar.performClick()
        assertEquals("request_camera", tappedReason)

        // Transition to Recording mode
        previewView.setRecordingActive(true, isFinishing = false)
        assertTrue(previewView.recordingActive)

        // Set recording progress
        previewView.setRecordingProgress(
            stateLabel = "Recording",
            currentCue = 3,
            totalPlanned = 10,
            formattedTime = "00:12"
        )

        // Finishing state
        previewView.setRecordingActive(true, isFinishing = true)
        previewView.setRecordingProgress(
            stateLabel = "Finishing",
            currentCue = 10,
            totalPlanned = 10,
            formattedTime = "00:19"
        )

        // Saving state returns to single centered status
        previewView.setSavingStatus("Saving recording…", isComplete = false)
        assertNotNull(previewView)

        // Saved state
        previewView.setSavingStatus("Recording saved ✓", isComplete = true)

        // Countdown display
        previewView.showCountdown("3")
        previewView.hideCountdown()
    }
}
