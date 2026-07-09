package dk.lasse.karatecliprecorder.captureprofile

import android.hardware.camera2.CameraCharacteristics
import androidx.camera.camera2.interop.Camera2CameraInfo
import androidx.camera.core.CameraInfo
import androidx.camera.core.DynamicRange
import androidx.camera.video.Quality
import androidx.camera.video.Recorder

object CameraCapabilityInitializer {
    fun initialize(cameraInfo: CameraInfo): SelectedCaptureProfile = try {
        val supportedQualities = Recorder.getVideoCapabilities(cameraInfo)
            .getSupportedQualities(DynamicRange.SDR)
            .map { it.toQualityName() }
        val fpsRanges = loadFpsRanges(cameraInfo)
        CaptureProfileSelector.select(supportedQualities, fpsRanges)
    } catch (error: Exception) {
        CaptureProfileSelector.fallback(
            reason = "Using safe HD 30fps fallback because camera capability lookup failed: ${error.message.orEmpty()}",
        )
    }

    private fun loadFpsRanges(cameraInfo: CameraInfo): List<CaptureFpsRange> = try {
        Camera2CameraInfo.from(cameraInfo)
            .getCameraCharacteristic(CameraCharacteristics.CONTROL_AE_AVAILABLE_TARGET_FPS_RANGES)
            ?.map { range -> CaptureFpsRange(range.lower, range.upper) }
            ?.sortedWith(compareBy<CaptureFpsRange> { it.minFps }.thenBy { it.maxFps })
            .orEmpty()
    } catch (_: Exception) {
        emptyList()
    }

    private fun Quality.toQualityName(): String = when (this) {
        Quality.UHD -> "UHD"
        Quality.FHD -> "FHD"
        Quality.HD -> "HD"
        Quality.SD -> "SD"
        else -> "UNKNOWN"
    }
}
