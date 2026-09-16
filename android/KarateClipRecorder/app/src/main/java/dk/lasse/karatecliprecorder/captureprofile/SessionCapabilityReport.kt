package dk.lasse.karatecliprecorder.captureprofile

import java.util.Locale

/**
 * Diagnostic and capability report capturing the full negotiation results
 * for a CameraX 1.6 camera session.
 *
 * Explicitly distinguishes:
 * - Camera2 hardware capabilities / advertised target FPS ranges
 * - CameraX 1.6 session compatibility for Preview + VideoCapture
 * - The impact of attaching ImageAnalysis (MediaPipe pose/punch analysis)
 * - Final configuration and verified output recording FPS.
 */
data class SessionCapabilityReport(
    val cameraxVersion: String = "1.6.2",
    val selectedLensId: String? = null,
    val availableQualities: List<String> = emptyList(),
    val isFps60FeatureSupported: Boolean = false,
    // Condition A: Preview + VideoCapture at FHD60
    val fhd60SessionSupported: Boolean = false,
    // Condition B: Preview + VideoCapture + ImageAnalysis at FHD60
    val fhd60WithAnalysisSupported: Boolean = false,
    // Condition C: Preview + VideoCapture at HD60
    val hd60SessionSupported: Boolean = false,
    // Condition D: Preview + VideoCapture + ImageAnalysis at HD60
    val hd60WithAnalysisSupported: Boolean = false,
    val fhd30SessionSupported: Boolean = false,
    val fhd30WithAnalysisSupported: Boolean = false,
    val hd30SessionSupported: Boolean = false,
    val hd30WithAnalysisSupported: Boolean = false,
    val camera2Advertises60Fps: Boolean = false,
    val sensorMinFrameDurationSupports60Fps: Boolean = false,
    val finalSelectedQuality: String = "HD",
    val finalSelectedFps: Int = 30,
    val fallbackSelected: Boolean = false,
    val fallbackReason: String? = null,
    val actualRecordedFps: Double? = null,
    val actualFpsVerified: Boolean? = null,
) {
    fun formatDiagnostics(): String = buildString {
        appendLine("CameraX Version: $cameraxVersion")
        appendLine("Selected Lens: ${selectedLensId ?: "default"}")
        appendLine("Available Qualities: ${availableQualities.ifEmpty { listOf("None") }.joinToString(", ")}")
        appendLine("Camera2 Advertises 60fps: ${if (camera2Advertises60Fps) "Yes" else "No"}")
        appendLine("Sensor Frame Duration 60fps: ${if (sensorMinFrameDurationSupports60Fps) "Yes" else "No"}")
        appendLine("CameraX FPS_60 Feature Supported: ${if (isFps60FeatureSupported) "Yes" else "No"}")
        appendLine("Session Matrix:")
        appendLine("  • [A] FHD60 (Preview+Video): ${if (fhd60SessionSupported) "SUPPORTED" else "UNSUPPORTED"}")
        appendLine("  • [B] FHD60 (Preview+Video+Analysis): ${if (fhd60WithAnalysisSupported) "SUPPORTED" else "UNSUPPORTED"}")
        appendLine("  • [C] HD60 (Preview+Video): ${if (hd60SessionSupported) "SUPPORTED" else "UNSUPPORTED"}")
        appendLine("  • [D] HD60 (Preview+Video+Analysis): ${if (hd60WithAnalysisSupported) "SUPPORTED" else "UNSUPPORTED"}")
        appendLine("  • FHD30: ${if (fhd30SessionSupported) "SUPPORTED" else "UNSUPPORTED"} (with analysis: ${if (fhd30WithAnalysisSupported) "YES" else "NO"})")
        appendLine("  • HD30: ${if (hd30SessionSupported) "SUPPORTED" else "UNSUPPORTED"} (with analysis: ${if (hd30WithAnalysisSupported) "YES" else "NO"})")
        appendLine("Final Selected Profile: $finalSelectedQuality @ ${finalSelectedFps}fps")
        if (fallbackSelected) {
            appendLine("Fallback Reason: ${fallbackReason ?: "Requested preferred mode unavailable"}")
        }
        if (actualRecordedFps != null) {
            val status = if (actualFpsVerified == true) "VERIFIED" else "DEVIATION DETECTED"
            appendLine("Actual Recorded Video FPS: ${String.format(Locale.US, "%.2f", actualRecordedFps)} ($status)")
        }
    }
}

