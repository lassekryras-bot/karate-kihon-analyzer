package dk.lasse.karatecliprecorder.captureprofile

data class SelectedCaptureProfile(
    val selectedQualityTier: VideoQualityTier,
    val selectedCameraXQualityName: String,
    val targetWidth: Int?,
    val targetHeight: Int?,
    val preferredTargetFps: Int,
    val selectedFpsRange: CaptureFpsRange?,
    val supportedQualityNames: List<String>,
    val supportedFpsRanges: List<CaptureFpsRange>,
    val selectionReason: String,
    val is60FpsSupported: Boolean = preferredTargetFps == 60,
    val imageAnalysisSupported: Boolean = true,
    val sessionSupports60Fps: Boolean = preferredTargetFps == 60,
    val fallbackSelected: Boolean = false,
    val fallbackReason: String? = null,
    val capabilityReport: SessionCapabilityReport? = null,
) {
    fun withActualRecordedFps(actualFps: Double?, verified: Boolean?): SelectedCaptureProfile {
        val updatedReport = capabilityReport?.copy(
            actualRecordedFps = actualFps,
            actualFpsVerified = verified,
        )
        return copy(capabilityReport = updatedReport)
    }
}
