package dk.lasse.karatecliprecorder

import android.net.Uri
import dk.lasse.karatecliprecorder.sharedcapture.PersistedCaptureResult

data class RecordingResult(
    val fileName: String,
    val absolutePath: String,
    val uri: Uri,
    val persistedCapture: PersistedCaptureResult? = null,
    val sessionId: String? = persistedCapture?.sessionId,
    val recordingStartMonotonicMs: Long? = null,
    val userId: String? = null,
    val guided: Boolean = false,
) {
    val captureId: String? get() = persistedCapture?.captureId
}
