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
)
