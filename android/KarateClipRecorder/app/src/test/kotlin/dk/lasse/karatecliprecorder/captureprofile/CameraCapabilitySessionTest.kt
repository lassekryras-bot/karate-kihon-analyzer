package dk.lasse.karatecliprecorder.captureprofile

import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class CameraCapabilitySessionTest {

    @Test
    fun preferredOrderingSelectsFhd60First() {
        val report = SessionCapabilityReport(
            availableQualities = listOf("UHD", "FHD", "HD", "SD"),
            isFps60FeatureSupported = true,
            fhd60SessionSupported = true,
            fhd60WithAnalysisSupported = true,
            hd60SessionSupported = true,
            hd60WithAnalysisSupported = true,
            fhd30SessionSupported = true,
            fhd30WithAnalysisSupported = true,
            hd30SessionSupported = true,
            hd30WithAnalysisSupported = true,
            camera2Advertises60Fps = true,
        )

        val profile = CaptureProfileSelector.select(report, requiresAnalysis = true)

        assertEquals(VideoQualityTier.FHD, profile.selectedQualityTier)
        assertEquals("FHD", profile.selectedCameraXQualityName)
        assertEquals(60, profile.preferredTargetFps)
        assertTrue(profile.is60FpsSupported)
        assertTrue(profile.sessionSupports60Fps)
        assertTrue(profile.imageAnalysisSupported)
        assertFalse(profile.fallbackSelected)
    }

    @Test
    fun hd60IsSelectedWhenFhd60IsUnavailable() {
        val report = SessionCapabilityReport(
            availableQualities = listOf("FHD", "HD", "SD"),
            isFps60FeatureSupported = true,
            fhd60SessionSupported = false, // FHD 60 not supported by hardware session
            fhd60WithAnalysisSupported = false,
            hd60SessionSupported = true,
            hd60WithAnalysisSupported = true,
            fhd30SessionSupported = true,
            fhd30WithAnalysisSupported = true,
            hd30SessionSupported = true,
            hd30WithAnalysisSupported = true,
            camera2Advertises60Fps = true,
        )

        val profile = CaptureProfileSelector.select(report, requiresAnalysis = true)

        assertEquals(VideoQualityTier.HD, profile.selectedQualityTier)
        assertEquals("HD", profile.selectedCameraXQualityName)
        assertEquals(60, profile.preferredTargetFps)
        assertTrue(profile.is60FpsSupported)
        assertTrue(profile.sessionSupports60Fps)
        assertTrue(profile.imageAnalysisSupported)
    }

    @Test
    fun fhd30IsSelectedWhenNo60FpsModeIsAvailable() {
        val report = SessionCapabilityReport(
            availableQualities = listOf("FHD", "HD", "SD"),
            isFps60FeatureSupported = false,
            fhd60SessionSupported = false,
            fhd60WithAnalysisSupported = false,
            hd60SessionSupported = false,
            hd60WithAnalysisSupported = false,
            fhd30SessionSupported = true,
            fhd30WithAnalysisSupported = true,
            hd30SessionSupported = true,
            hd30WithAnalysisSupported = true,
            camera2Advertises60Fps = false,
        )

        val profile = CaptureProfileSelector.select(report, requiresAnalysis = true)

        assertEquals(VideoQualityTier.FHD, profile.selectedQualityTier)
        assertEquals(30, profile.preferredTargetFps)
        assertFalse(profile.is60FpsSupported)
        assertTrue(profile.imageAnalysisSupported)
    }

    @Test
    fun completeSessionCapabilityAffectsSelectionNotJustCamera2() {
        // Device where Camera2 advertises 60fps in CONTROL_AE_AVAILABLE_TARGET_FPS_RANGES,
        // but CameraX 1.6 session check proves the multi-stream session cannot run at 60fps!
        val report = SessionCapabilityReport(
            availableQualities = listOf("FHD", "HD"),
            isFps60FeatureSupported = false,
            fhd60SessionSupported = false, // Complete session fails 60fps
            fhd60WithAnalysisSupported = false,
            hd60SessionSupported = false,
            hd60WithAnalysisSupported = false,
            fhd30SessionSupported = true,
            fhd30WithAnalysisSupported = true,
            hd30SessionSupported = true,
            hd30WithAnalysisSupported = true,
            camera2Advertises60Fps = true, // Camera2 claims 60fps!
        )

        val profile = CaptureProfileSelector.select(report, requiresAnalysis = false)

        // Must NOT falsely select 60fps just because Camera2 advertised it
        assertEquals(30, profile.preferredTargetFps)
        assertFalse(profile.is60FpsSupported)
        assertEquals("FHD", profile.selectedCameraXQualityName)
    }

    @Test
    fun imageAnalysisCompatibilityDowngradesFhd60ToHd60WithExplanatoryReason() {
        // Device where Preview + VideoCapture supports FHD60 (Condition A),
        // but adding ImageAnalysis causes FHD60 to fail (Condition B = false).
        // However, HD60 supports ImageAnalysis (Condition D = true).
        val report = SessionCapabilityReport(
            availableQualities = listOf("FHD", "HD"),
            isFps60FeatureSupported = true,
            fhd60SessionSupported = true, // Condition A: true
            fhd60WithAnalysisSupported = false, // Condition B: false!
            hd60SessionSupported = true, // Condition C: true
            hd60WithAnalysisSupported = true, // Condition D: true!
            fhd30SessionSupported = true,
            fhd30WithAnalysisSupported = true,
            hd30SessionSupported = true,
            hd30WithAnalysisSupported = true,
            camera2Advertises60Fps = true,
        )

        val profile = CaptureProfileSelector.select(report, requiresAnalysis = true)

        assertEquals("HD", profile.selectedCameraXQualityName)
        assertEquals(60, profile.preferredTargetFps)
        assertTrue(profile.fallbackSelected)
        assertTrue(profile.selectionReason.contains("MediaPipe/ImageAnalysis bandwidth requires downgrading"))
    }

    @Test
    fun imageAnalysisConflictDowngradesToFhd30WhenNo60FpsSupportsAnalysis() {
        // Both FHD60 and HD60 work alone, but BOTH fail when ImageAnalysis is attached!
        val report = SessionCapabilityReport(
            availableQualities = listOf("FHD", "HD"),
            isFps60FeatureSupported = true,
            fhd60SessionSupported = true,
            fhd60WithAnalysisSupported = false,
            hd60SessionSupported = true,
            hd60WithAnalysisSupported = false,
            fhd30SessionSupported = true,
            fhd30WithAnalysisSupported = true,
            hd30SessionSupported = true,
            hd30WithAnalysisSupported = true,
            camera2Advertises60Fps = true,
        )

        val profile = CaptureProfileSelector.select(report, requiresAnalysis = true)

        assertEquals("FHD", profile.selectedCameraXQualityName)
        assertEquals(30, profile.preferredTargetFps)
        assertTrue(profile.fallbackSelected)
        assertTrue(profile.selectionReason.contains("incompatible when ImageAnalysis is attached"))
    }

    @Test
    fun fallbackBehaviorReturnsSafeHd30WhenAllSessionProbesFail() {
        val report = SessionCapabilityReport(
            availableQualities = emptyList(),
            isFps60FeatureSupported = false,
            fhd60SessionSupported = false,
            fhd60WithAnalysisSupported = false,
            hd60SessionSupported = false,
            hd60WithAnalysisSupported = false,
            fhd30SessionSupported = false,
            fhd30WithAnalysisSupported = false,
            hd30SessionSupported = false,
            hd30WithAnalysisSupported = false,
        )

        val profile = CaptureProfileSelector.select(report, requiresAnalysis = true)

        assertEquals("HD", profile.selectedCameraXQualityName)
        assertEquals(30, profile.preferredTargetFps)
        assertTrue(profile.fallbackSelected)
        assertNotNull(profile.fallbackReason)
    }

    @Test
    fun capabilityReportMappingFormatsAllDiagnosticSections() {
        val report = SessionCapabilityReport(
            cameraxVersion = "1.6.2",
            selectedLensId = "0",
            availableQualities = listOf("FHD", "HD"),
            isFps60FeatureSupported = true,
            fhd60SessionSupported = true,
            fhd60WithAnalysisSupported = false,
            hd60SessionSupported = true,
            hd60WithAnalysisSupported = true,
            camera2Advertises60Fps = true,
            sensorMinFrameDurationSupports60Fps = true,
            finalSelectedQuality = "HD",
            finalSelectedFps = 60,
            fallbackSelected = true,
            fallbackReason = "MediaPipe requires HD60",
            actualRecordedFps = 59.94,
            actualFpsVerified = true,
        )

        val formatted = report.formatDiagnostics()

        assertTrue(formatted.contains("CameraX Version: 1.6.2"))
        assertTrue(formatted.contains("Selected Lens: 0"))
        assertTrue(formatted.contains("Available Qualities: FHD, HD"))
        assertTrue(formatted.contains("Camera2 Advertises 60fps: Yes"))
        assertTrue(formatted.contains("[A] FHD60 (Preview+Video): SUPPORTED"))
        assertTrue(formatted.contains("[B] FHD60 (Preview+Video+Analysis): UNSUPPORTED"))
        assertTrue(formatted.contains("[C] HD60 (Preview+Video): SUPPORTED"))
        assertTrue(formatted.contains("[D] HD60 (Preview+Video+Analysis): SUPPORTED"))
        assertTrue(formatted.contains("Final Selected Profile: HD @ 60fps"))
        assertTrue(formatted.contains("Fallback Reason: MediaPipe requires HD60"))
        assertTrue(formatted.contains("Actual Recorded Video FPS: 59.94 (VERIFIED)"))
    }
}

