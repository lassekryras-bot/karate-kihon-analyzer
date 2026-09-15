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
    val preference = listOf(CaptureQuality("FHD", 60), CaptureQuality("HD", 60), CaptureQuality("FHD", 30), CaptureQuality("HD", 30))
    fun ordered(supported: List<CaptureQuality>) = preference.filter { it in supported } + supported.filter { it !in preference }

    @androidx.annotation.OptIn(markerClass = [ExperimentalCamera2Interop::class])
    fun supported(info: CameraInfo): List<CaptureQuality> {
        val camera = Camera2CameraInfo.from(info)
        val map = camera.getCameraCharacteristic(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP)
        val ranges = camera.getCameraCharacteristic(CameraCharacteristics.CONTROL_AE_AVAILABLE_TARGET_FPS_RANGES).orEmpty()
        val qualities = Recorder.getVideoCapabilities(info).getSupportedQualities(DynamicRange.SDR)
        return ordered(listOf(Triple(Quality.FHD, "FHD", Size(1920, 1080)), Triple(Quality.HD, "HD", Size(1280, 720)),
            Triple(Quality.UHD, "UHD", Size(3840, 2160)), Triple(Quality.SD, "SD", Size(720, 480))).flatMap { (quality, name, size) ->
            if (quality !in qualities) emptyList() else {
                val duration = runCatching { map?.getOutputMinFrameDuration(MediaRecorder::class.java, size) ?: 0 }.getOrDefault(0)
                listOf(60, 30).filter { fps -> ranges.any { it.contains(fps) } &&
                    (fps == 30 || duration > 0 && duration <= 1_000_000_000L / fps + 1000) }.map { CaptureQuality(name, it) }
            }
        }).ifEmpty { listOf(CaptureQuality("HD", 30)) }
    }
}
