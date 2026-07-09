package dk.lasse.karatecliprecorder.captureprofile

object CaptureProfileSelector {
    fun select(
        supportedQualityNames: List<String>,
        supportedFpsRanges: List<CaptureFpsRange>,
    ): SelectedCaptureProfile {
        val normalizedQualities = supportedQualityNames.map { it.uppercase() }.distinct()
        val selectedQualityName = when {
            "FHD" in normalizedQualities -> "FHD"
            "HD" in normalizedQualities -> "HD"
            "SD" in normalizedQualities -> "SD"
            normalizedQualities.isNotEmpty() -> normalizedQualities.first()
            else -> "HD"
        }
        val selectedTier = selectedQualityName.toVideoQualityTier()
        val selectedFpsRange = selectFpsRange(supportedFpsRanges)
        val preferredTargetFps = when {
            selectedFpsRange?.supports60() == true -> 60
            selectedFpsRange?.supports30() == true -> 30
            selectedFpsRange != null -> selectedFpsRange.maxFps
            else -> 30
        }
        val dimensions = selectedTier.targetDimensions()

        return SelectedCaptureProfile(
            selectedQualityTier = selectedTier,
            selectedCameraXQualityName = selectedQualityName,
            targetWidth = dimensions?.first,
            targetHeight = dimensions?.second,
            preferredTargetFps = preferredTargetFps,
            selectedFpsRange = selectedFpsRange,
            supportedQualityNames = normalizedQualities,
            supportedFpsRanges = supportedFpsRanges,
            selectionReason = buildSelectionReason(selectedQualityName, selectedFpsRange, preferredTargetFps),
        )
    }

    fun fallback(reason: String): SelectedCaptureProfile = SelectedCaptureProfile(
        selectedQualityTier = VideoQualityTier.HD,
        selectedCameraXQualityName = "HD",
        targetWidth = 1280,
        targetHeight = 720,
        preferredTargetFps = 30,
        selectedFpsRange = null,
        supportedQualityNames = emptyList(),
        supportedFpsRanges = emptyList(),
        selectionReason = reason,
    )

    private fun selectFpsRange(ranges: List<CaptureFpsRange>): CaptureFpsRange? = when {
        ranges.isEmpty() -> null
        ranges.any { it.supports60() } -> ranges.filter { it.supports60() }.maxBy { it.maxFps }
        ranges.any { it.supports30() } -> ranges.filter { it.supports30() }.maxBy { it.maxFps }
        else -> ranges.maxWith(compareBy<CaptureFpsRange> { it.maxFps }.thenBy { it.minFps })
    }

    private fun buildSelectionReason(
        selectedQualityName: String,
        selectedFpsRange: CaptureFpsRange?,
        preferredTargetFps: Int,
    ): String {
        val qualityReason = "Selected $selectedQualityName because it is the best supported karate-analysis quality."
        val fpsReason = when {
            selectedFpsRange?.supports60() == true -> "Preferred 60fps because camera exposes a range supporting 60."
            selectedFpsRange?.supports30() == true -> "Preferred 30fps because 60fps is unavailable but camera exposes a range supporting 30."
            selectedFpsRange != null -> "Preferred ${preferredTargetFps}fps from the highest available max FPS range."
            else -> "Preferred safe 30fps because FPS ranges were unavailable."
        }
        return "$qualityReason $fpsReason"
    }

    private fun String.toVideoQualityTier(): VideoQualityTier = when (this) {
        "UHD" -> VideoQualityTier.UHD
        "FHD" -> VideoQualityTier.FHD
        "HD" -> VideoQualityTier.HD
        "SD" -> VideoQualityTier.SD
        else -> VideoQualityTier.UNKNOWN
    }

    private fun VideoQualityTier.targetDimensions(): Pair<Int, Int>? = when (this) {
        VideoQualityTier.UHD -> 3840 to 2160
        VideoQualityTier.FHD -> 1920 to 1080
        VideoQualityTier.HD -> 1280 to 720
        VideoQualityTier.SD -> 720 to 480
        VideoQualityTier.UNKNOWN -> null
    }
}
