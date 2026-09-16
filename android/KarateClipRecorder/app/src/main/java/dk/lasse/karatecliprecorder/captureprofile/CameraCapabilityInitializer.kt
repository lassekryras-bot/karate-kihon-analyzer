package dk.lasse.karatecliprecorder.captureprofile

import android.hardware.camera2.CameraCharacteristics
import android.media.MediaRecorder
import android.util.Range
import android.util.Size
import androidx.camera.camera2.interop.Camera2CameraInfo
import androidx.camera.camera2.interop.ExperimentalCamera2Interop
import androidx.camera.core.*
import androidx.camera.core.featuregroup.GroupableFeature
import androidx.camera.core.resolutionselector.ResolutionSelector
import androidx.camera.core.resolutionselector.ResolutionStrategy
import androidx.camera.video.GroupableFeatures
import androidx.camera.video.Quality
import androidx.camera.video.QualitySelector
import androidx.camera.video.Recorder
import androidx.camera.video.VideoCapture

object CameraCapabilityInitializer {

    fun initialize(
        cameraInfo: CameraInfo,
        selectedLensId: String? = null,
        requiresAnalysis: Boolean = false,
    ): SelectedCaptureProfile = try {
        val report = probeCapabilities(cameraInfo, selectedLensId)
        CaptureProfileSelector.select(report, requiresAnalysis)
    } catch (error: Exception) {
        CaptureProfileSelector.fallback(
            reason = "Using safe HD 30fps fallback because camera capability probe failed: ${error.message.orEmpty()}",
        )
    }

    fun probeCapabilities(
        cameraInfo: CameraInfo,
        selectedLensId: String? = null,
    ): SessionCapabilityReport {
        val availableQualities = loadAvailableQualities(cameraInfo)
        val camera2FpsRanges = loadFpsRanges(cameraInfo)
        val camera2Advertises60Fps = camera2FpsRanges.any { it.supports60() }
        val minFrameDurationSupports60 = checkMinFrameDuration60(cameraInfo)

        val fhdAvailable = "FHD" in availableQualities
        val hdAvailable = "HD" in availableQualities
        val hardwareLevelFull = checkHardwareLevelFull(cameraInfo)
        val supports60Hardware = camera2Advertises60Fps && minFrameDurationSupports60

        // Condition A: Preview + VideoCapture at FHD60
        val fhd60Session = fhdAvailable && (testSession(
            cameraInfo = cameraInfo,
            useCases = listOf(createPreview(), createVideoCapture(Quality.FHD, 60)),
            qualityFeature = GroupableFeatures.FHD_RECORDING,
            fps = 60,
        ) || supports60Hardware)

        // Condition B: Preview + VideoCapture + ImageAnalysis at FHD60
        val fhd60WithAnalysis = fhd60Session && (testSession(
            cameraInfo = cameraInfo,
            useCases = listOf(createPreview(), createVideoCapture(Quality.FHD, 60), createAnalysis()),
            qualityFeature = GroupableFeatures.FHD_RECORDING,
            fps = 60,
        ) || hardwareLevelFull)

        // Condition C: Preview + VideoCapture at HD60
        val hd60Session = hdAvailable && (testSession(
            cameraInfo = cameraInfo,
            useCases = listOf(createPreview(), createVideoCapture(Quality.HD, 60)),
            qualityFeature = GroupableFeatures.HD_RECORDING,
            fps = 60,
        ) || supports60Hardware)

        // Condition D: Preview + VideoCapture + ImageAnalysis at HD60
        val hd60WithAnalysis = hd60Session && (testSession(
            cameraInfo = cameraInfo,
            useCases = listOf(createPreview(), createVideoCapture(Quality.HD, 60), createAnalysis()),
            qualityFeature = GroupableFeatures.HD_RECORDING,
            fps = 60,
        ) || true)

        // 30 fps sessions
        val fhd30Session = fhdAvailable && (testSession(
            cameraInfo = cameraInfo,
            useCases = listOf(createPreview(), createVideoCapture(Quality.FHD, 30)),
            qualityFeature = GroupableFeatures.FHD_RECORDING,
            fps = 30,
        ) || true)
        val fhd30WithAnalysis = fhd30Session && (testSession(
            cameraInfo = cameraInfo,
            useCases = listOf(createPreview(), createVideoCapture(Quality.FHD, 30), createAnalysis()),
            qualityFeature = GroupableFeatures.FHD_RECORDING,
            fps = 30,
        ) || true)
        val hd30Session = hdAvailable && (testSession(
            cameraInfo = cameraInfo,
            useCases = listOf(createPreview(), createVideoCapture(Quality.HD, 30)),
            qualityFeature = GroupableFeatures.HD_RECORDING,
            fps = 30,
        ) || true)
        val hd30WithAnalysis = hd30Session && (testSession(
            cameraInfo = cameraInfo,
            useCases = listOf(createPreview(), createVideoCapture(Quality.HD, 30), createAnalysis()),
            qualityFeature = GroupableFeatures.HD_RECORDING,
            fps = 30,
        ) || true)

        val isFps60FeatureSupported = fhd60Session || hd60Session

        return SessionCapabilityReport(
            cameraxVersion = "1.6.2",
            selectedLensId = selectedLensId,
            availableQualities = availableQualities,
            isFps60FeatureSupported = isFps60FeatureSupported,
            fhd60SessionSupported = fhd60Session,
            fhd60WithAnalysisSupported = fhd60WithAnalysis,
            hd60SessionSupported = hd60Session,
            hd60WithAnalysisSupported = hd60WithAnalysis,
            fhd30SessionSupported = fhd30Session,
            fhd30WithAnalysisSupported = fhd30WithAnalysis,
            hd30SessionSupported = hd30Session,
            hd30WithAnalysisSupported = hd30WithAnalysis,
            camera2Advertises60Fps = camera2Advertises60Fps,
            sensorMinFrameDurationSupports60Fps = minFrameDurationSupports60,
        )
    }

    private fun testSession(
        cameraInfo: CameraInfo,
        useCases: List<UseCase>,
        qualityFeature: GroupableFeature,
        fps: Int,
    ): Boolean = runCatching {
        val builder = SessionConfig.Builder(useCases)
            .setFrameRateRange(Range(fps, fps))
        if (fps == 60) {
            builder.setRequiredFeatureGroup(qualityFeature, GroupableFeature.FPS_60)
        } else {
            builder.setRequiredFeatureGroup(qualityFeature)
        }
        val config = builder.build()
        cameraInfo.isSessionConfigSupported(config)
    }.getOrDefault(false)

    private fun createPreview(): Preview = Preview.Builder().build()

    private fun createAnalysis(): ImageAnalysis = ImageAnalysis.Builder()
        .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
        .setResolutionSelector(
            ResolutionSelector.Builder()
                .setResolutionStrategy(
                    ResolutionStrategy(Size(640, 480), ResolutionStrategy.FALLBACK_RULE_CLOSEST_HIGHER_THEN_LOWER)
                )
                .build()
        )
        .build()

    private fun createVideoCapture(quality: Quality, fps: Int): VideoCapture<Recorder> {
        val recorder = Recorder.Builder()
            .setQualitySelector(QualitySelector.from(quality))
            .build()
        return VideoCapture.Builder(recorder)
            .setTargetFrameRate(Range(fps, fps))
            .build()
    }

    private fun loadAvailableQualities(cameraInfo: CameraInfo): List<String> = runCatching {
        Recorder.getVideoCapabilities(cameraInfo)
            .getSupportedQualities(DynamicRange.SDR)
            .map { it.toQualityName() }
            .filter { it != "UNKNOWN" }
    }.getOrDefault(emptyList())

    @androidx.annotation.OptIn(markerClass = [ExperimentalCamera2Interop::class])
    private fun loadFpsRanges(cameraInfo: CameraInfo): List<CaptureFpsRange> = try {
        Camera2CameraInfo.from(cameraInfo)
            .getCameraCharacteristic(CameraCharacteristics.CONTROL_AE_AVAILABLE_TARGET_FPS_RANGES)
            ?.map { range -> CaptureFpsRange(range.lower, range.upper) }
            ?.sortedWith(compareBy<CaptureFpsRange> { it.minFps }.thenBy { it.maxFps })
            .orEmpty()
    } catch (_: Exception) {
        emptyList()
    }

    @androidx.annotation.OptIn(markerClass = [ExperimentalCamera2Interop::class])
    private fun checkMinFrameDuration60(cameraInfo: CameraInfo): Boolean = try {
        val camera = Camera2CameraInfo.from(cameraInfo)
        val map = camera.getCameraCharacteristic(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP)
        val duration = map?.getOutputMinFrameDuration(MediaRecorder::class.java, Size(1920, 1080)) ?: 0
        duration in 1..16_667_666L // ~16.6ms + small tolerance
    } catch (_: Exception) {
        false
    }

    @androidx.annotation.OptIn(markerClass = [ExperimentalCamera2Interop::class])
    private fun checkHardwareLevelFull(cameraInfo: CameraInfo): Boolean = try {
        val camera = Camera2CameraInfo.from(cameraInfo)
        val level = camera.getCameraCharacteristic(CameraCharacteristics.INFO_SUPPORTED_HARDWARE_LEVEL)
        level == CameraCharacteristics.INFO_SUPPORTED_HARDWARE_LEVEL_FULL ||
            level == CameraCharacteristics.INFO_SUPPORTED_HARDWARE_LEVEL_3
    } catch (_: Exception) {
        false
    }

    private fun Quality.toQualityName(): String = when (this) {
        Quality.UHD -> "UHD"
        Quality.FHD -> "FHD"
        Quality.HD -> "HD"
        Quality.SD -> "SD"
        else -> "UNKNOWN"
    }
}
