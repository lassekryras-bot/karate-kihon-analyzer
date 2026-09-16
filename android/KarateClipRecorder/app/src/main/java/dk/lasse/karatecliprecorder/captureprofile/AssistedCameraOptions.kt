package dk.lasse.karatecliprecorder.captureprofile

import android.hardware.camera2.CameraCharacteristics
import android.media.MediaRecorder
import android.util.Size
import androidx.camera.camera2.interop.Camera2CameraInfo
import androidx.camera.camera2.interop.ExperimentalCamera2Interop
import androidx.camera.core.CameraInfo
import androidx.camera.core.DynamicRange
import androidx.camera.video.Quality
import androidx.camera.video.Recorder

data class CaptureQuality(val name: String, val fps: Int) {
    override fun toString() = "${if (name == "FHD") "1080p" else if (name == "HD") "720p" else name} · $fps fps"
}

data class RearLens(val id: String, val label: String)

object AssistedCameraOptions {
    @androidx.annotation.OptIn(markerClass = [ExperimentalCamera2Interop::class])
    fun rearLens(info: CameraInfo): RearLens {
        val camera = Camera2CameraInfo.from(info)
        val focal = camera.getCameraCharacteristic(CameraCharacteristics.LENS_INFO_AVAILABLE_FOCAL_LENGTHS)?.firstOrNull()
        return RearLens(camera.cameraId, "Rear lens ${camera.cameraId}${focal?.let { " (${it} mm)" } ?: ""}")
    }

    val preference = listOf(
        CaptureQuality("FHD", 60),
        CaptureQuality("HD", 60),
        CaptureQuality("FHD", 30),
        CaptureQuality("HD", 30),
    )

    fun ordered(supported: List<CaptureQuality>): List<CaptureQuality> =
        preference.filter { it in supported } + supported.filter { it !in preference }

    @androidx.annotation.OptIn(markerClass = [ExperimentalCamera2Interop::class])
    fun supported(info: CameraInfo, existingReport: SessionCapabilityReport? = null): List<CaptureQuality> {
        // Primary: CameraX 1.6 session capability probe
        val report = existingReport ?: CameraCapabilityInitializer.probeCapabilities(info)
        val sessionSupported = mutableListOf<CaptureQuality>()
        if (report.fhd60SessionSupported) sessionSupported += CaptureQuality("FHD", 60)
        if (report.hd60SessionSupported) sessionSupported += CaptureQuality("HD", 60)
        if (report.fhd30SessionSupported) sessionSupported += CaptureQuality("FHD", 30)
        if (report.hd30SessionSupported) sessionSupported += CaptureQuality("HD", 30)

        if (sessionSupported.isNotEmpty()) {
            return ordered(sessionSupported)
        }

        // Defensive fallback for environments where CameraX session probe is unavailable (e.g. Robolectric unit tests)
        val camera = runCatching { Camera2CameraInfo.from(info) }.getOrNull()
        val map = camera?.getCameraCharacteristic(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP)
        val ranges = camera?.getCameraCharacteristic(CameraCharacteristics.CONTROL_AE_AVAILABLE_TARGET_FPS_RANGES).orEmpty()
        val qualities = runCatching {
            Recorder.getVideoCapabilities(info).getSupportedQualities(DynamicRange.SDR)
        }.getOrDefault(emptyList())

        val candidates = listOf(
            Triple(Quality.FHD, "FHD", Size(1920, 1080)),
            Triple(Quality.HD, "HD", Size(1280, 720)),
            Triple(Quality.UHD, "UHD", Size(3840, 2160)),
            Triple(Quality.SD, "SD", Size(720, 480)),
        ).flatMap { (quality, name, size) ->
            if (quality !in qualities) emptyList() else {
                val duration = runCatching { map?.getOutputMinFrameDuration(MediaRecorder::class.java, size) ?: 0 }.getOrDefault(0)
                listOf(60, 30).filter { fps ->
                    ranges.any { it.contains(fps) } && (fps == 30 || duration in 1..16_667_666L)
                }.map { CaptureQuality(name, it) }
            }
        }

        return ordered(candidates).ifEmpty { listOf(CaptureQuality("HD", 30)) }
    }
}
