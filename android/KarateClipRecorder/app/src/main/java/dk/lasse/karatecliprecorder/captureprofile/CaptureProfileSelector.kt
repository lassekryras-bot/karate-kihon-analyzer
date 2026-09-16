package dk.lasse.karatecliprecorder.captureprofile

object CaptureProfileSelector {

    fun select(
        report: SessionCapabilityReport,
        requiresAnalysis: Boolean = false,
    ): SelectedCaptureProfile {
        val selectedQuality: String
        val selectedFps: Int
        val fallbackSelected: Boolean
        val reason: String

        if (requiresAnalysis) {
            when {
                report.fhd60WithAnalysisSupported -> {
                    selectedQuality = "FHD"
                    selectedFps = 60
                    fallbackSelected = false
                    reason = "Selected preferred FHD 60fps with full ImageAnalysis coexistence."
                }
                report.hd60WithAnalysisSupported -> {
                    selectedQuality = "HD"
                    selectedFps = 60
                    fallbackSelected = report.fhd60SessionSupported
                    reason = if (report.fhd60SessionSupported) {
                        "FHD 60fps supported without analysis, but MediaPipe/ImageAnalysis bandwidth requires downgrading to HD 60fps."
                    } else {
                        "Selected HD 60fps with ImageAnalysis (FHD 60fps unsupported by hardware session)."
                    }
                }
                report.fhd30WithAnalysisSupported -> {
                    selectedQuality = "FHD"
                    selectedFps = 30
                    fallbackSelected = report.fhd60SessionSupported || report.hd60SessionSupported
                    reason = if (report.fhd60SessionSupported || report.hd60SessionSupported) {
                        "60fps capture is incompatible when ImageAnalysis is attached; selected FHD 30fps."
                    } else {
                        "Selected FHD 30fps with ImageAnalysis (60fps modes unavailable on device)."
                    }
                }
                report.hd30WithAnalysisSupported -> {
                    selectedQuality = "HD"
                    selectedFps = 30
                    fallbackSelected = true
                    reason = "Selected HD 30fps with ImageAnalysis."
                }
                else -> {
                    // Safe fallback if session probing did not confirm analysis support
                    return fallback(
                        reason = "Using safe HD 30fps fallback: complete session with ImageAnalysis was not confirmed.",
                        report = report,
                    )
                }
            }
        } else {
            when {
                report.fhd60SessionSupported -> {
                    selectedQuality = "FHD"
                    selectedFps = 60
                    fallbackSelected = false
                    reason = "Selected preferred FHD 60fps verified by CameraX 1.6 session negotiation."
                }
                report.hd60SessionSupported -> {
                    selectedQuality = "HD"
                    selectedFps = 60
                    fallbackSelected = false
                    reason = "Selected HD 60fps (FHD 60fps session unsupported on this device)."
                }
                report.fhd30SessionSupported -> {
                    selectedQuality = "FHD"
                    selectedFps = 30
                    fallbackSelected = false
                    reason = "Selected FHD 30fps (60fps session modes unavailable on this device)."
                }
                report.hd30SessionSupported -> {
                    selectedQuality = "HD"
                    selectedFps = 30
                    fallbackSelected = false
                    reason = "Selected HD 30fps."
                }
                else -> {
                    return fallback(
                        reason = "Using safe HD 30fps fallback: CameraX session negotiation did not confirm preferred modes.",
                        report = report,
                    )
                }
            }
        }

        val tier = selectedQuality.toVideoQualityTier()
        val dimensions = tier.targetDimensions()
        val finalReport = report.copy(
            finalSelectedQuality = selectedQuality,
            finalSelectedFps = selectedFps,
            fallbackSelected = fallbackSelected,
            fallbackReason = if (fallbackSelected) reason else null,
        )

        return SelectedCaptureProfile(
            selectedQualityTier = tier,
            selectedCameraXQualityName = selectedQuality,
            targetWidth = dimensions?.first,
            targetHeight = dimensions?.second,
            preferredTargetFps = selectedFps,
            selectedFpsRange = CaptureFpsRange(selectedFps, selectedFps),
            supportedQualityNames = report.availableQualities,
            supportedFpsRanges = if (report.camera2Advertises60Fps) {
                listOf(CaptureFpsRange(30, 30), CaptureFpsRange(60, 60))
            } else {
                listOf(CaptureFpsRange(30, 30))
            },
            selectionReason = reason,
            is60FpsSupported = selectedFps == 60,
            imageAnalysisSupported = if (requiresAnalysis) {
                when {
                    selectedQuality == "FHD" && selectedFps == 60 -> report.fhd60WithAnalysisSupported
                    selectedQuality == "HD" && selectedFps == 60 -> report.hd60WithAnalysisSupported
                    selectedQuality == "FHD" && selectedFps == 30 -> report.fhd30WithAnalysisSupported
                    else -> report.hd30WithAnalysisSupported
                }
            } else true,
            sessionSupports60Fps = if (selectedFps == 60) true else report.isFps60FeatureSupported,
            fallbackSelected = fallbackSelected,
            fallbackReason = if (fallbackSelected) reason else null,
            capabilityReport = finalReport,
        )
    }

    /** Legacy compatibility overload */
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
            is60FpsSupported = preferredTargetFps == 60,
            imageAnalysisSupported = true,
            sessionSupports60Fps = preferredTargetFps == 60,
            fallbackSelected = false,
            fallbackReason = null,
            capabilityReport = null,
        )
    }

    fun fallback(
        reason: String,
        report: SessionCapabilityReport? = null,
    ): SelectedCaptureProfile {
        val finalReport = report?.copy(
            finalSelectedQuality = "HD",
            finalSelectedFps = 30,
            fallbackSelected = true,
            fallbackReason = reason,
        )
        return SelectedCaptureProfile(
            selectedQualityTier = VideoQualityTier.HD,
            selectedCameraXQualityName = "HD",
            targetWidth = 1280,
            targetHeight = 720,
            preferredTargetFps = 30,
            selectedFpsRange = CaptureFpsRange(30, 30),
            supportedQualityNames = report?.availableQualities ?: emptyList(),
            supportedFpsRanges = emptyList(),
            selectionReason = reason,
            is60FpsSupported = false,
            imageAnalysisSupported = report?.hd30WithAnalysisSupported ?: true,
            sessionSupports60Fps = false,
            fallbackSelected = true,
            fallbackReason = reason,
            capabilityReport = finalReport,
        )
    }

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
