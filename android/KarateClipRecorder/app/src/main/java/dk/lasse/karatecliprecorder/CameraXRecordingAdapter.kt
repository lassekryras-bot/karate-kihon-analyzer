package dk.lasse.karatecliprecorder

import android.content.Context
import android.os.Environment
import androidx.camera.core.CameraSelector
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.video.FallbackStrategy
import androidx.camera.video.FileOutputOptions
import androidx.camera.video.PendingRecording
import androidx.camera.video.Quality
import androidx.camera.video.QualitySelector
import androidx.camera.video.Recorder
import androidx.camera.video.Recording
import androidx.camera.video.VideoCapture
import androidx.camera.video.VideoRecordEvent
import androidx.camera.view.PreviewView
import androidx.core.content.ContextCompat
import dk.lasse.karatecliprecorder.captureprofile.CameraCapabilityInitializer
import dk.lasse.karatecliprecorder.captureprofile.CaptureProfileSelector
import dk.lasse.karatecliprecorder.captureprofile.SelectedCaptureProfile
import androidx.lifecycle.LifecycleOwner
import java.io.File
import java.util.Locale
import java.util.concurrent.Executor

class CameraXRecordingAdapter(
    private val context: Context,
    private val lifecycleOwner: LifecycleOwner,
    private val previewView: PreviewView,
    private val onStateChanged: (RecordingState) -> Unit,
    private val onSaved: (RecordingResult) -> Unit,
    private val onError: (String) -> Unit,
    private val onCaptureProfileSelected: (SelectedCaptureProfile) -> Unit = {},
) {
    private var videoCapture: VideoCapture<Recorder>? = null
    private var activeRecording: Recording? = null
    private var nextClipNumber = 1
    var selectedCaptureProfile: SelectedCaptureProfile? = null
        private set
    private val mainExecutor: Executor = ContextCompat.getMainExecutor(context)

    fun bindCameraPreview() {
        onStateChanged(RecordingState.PREPARING)
        val providerFuture = ProcessCameraProvider.getInstance(context)
        providerFuture.addListener({
            try {
                val cameraProvider = providerFuture.get()
                val preview = Preview.Builder().build().also {
                    it.setSurfaceProvider(previewView.surfaceProvider)
                }
                val cameraSelector = CameraSelector.DEFAULT_BACK_CAMERA
                val cameraInfo = cameraProvider.availableCameraInfos.firstOrNull { info ->
                    runCatching { cameraSelector.filter(listOf(info)).isNotEmpty() }.getOrDefault(false)
                }
                val profile = cameraInfo
                    ?.let { CameraCapabilityInitializer.initialize(it) }
                    ?: CaptureProfileSelector.fallback(
                        reason = "Using safe HD 30fps fallback because no back camera info was available.",
                    )
                selectedCaptureProfile = profile
                onCaptureProfileSelected(profile)

                val recorder = Recorder.Builder()
                    .setQualitySelector(profile.toQualitySelector())
                    .build()
                videoCapture = VideoCapture.withOutput(recorder)

                cameraProvider.unbindAll()
                cameraProvider.bindToLifecycle(
                    lifecycleOwner,
                    cameraSelector,
                    preview,
                    videoCapture,
                )
                onStateChanged(RecordingState.IDLE)
            } catch (error: Exception) {
                onStateChanged(RecordingState.FAILED)
                onError("Camera preview failed: ${error.message}")
            }
        }, mainExecutor)
    }

    fun startRecording(fileName: String? = null) {
        val capture = videoCapture
        if (capture == null) {
            onStateChanged(RecordingState.FAILED)
            onError("Camera is not ready yet.")
            return
        }
        if (activeRecording != null) {
            return
        }

        val outputFile = if (fileName == null) createNextOutputFile() else createGuidedSessionFile(fileName)
        if (fileName != null && outputFile.exists()) {
            outputFile.delete()
        }
        val outputOptions = FileOutputOptions.Builder(outputFile).build()
        val pendingRecording: PendingRecording = capture.output.prepareRecording(context, outputOptions)

        onStateChanged(RecordingState.RECORDING)
        activeRecording = pendingRecording.start(mainExecutor) { event ->
            when (event) {
                is VideoRecordEvent.Finalize -> {
                    activeRecording = null
                    if (event.hasError()) {
                        onStateChanged(RecordingState.FAILED)
                        onError("Recording failed: ${event.error}")
                    } else {
                        val result = RecordingResult(
                            fileName = outputFile.name,
                            absolutePath = outputFile.absolutePath,
                            uri = event.outputResults.outputUri,
                        )
                        onStateChanged(RecordingState.SAVED)
                        onSaved(result)
                    }
                }
            }
        }
    }

    fun stopRecording() {
        activeRecording?.stop()
    }

    fun createGuidedSessionFile(fileName: String): File {
        val sessionDir = File(getMoviesDir(), GUIDED_SESSION_DIR_NAME)
        if (!sessionDir.exists()) {
            sessionDir.mkdirs()
        }
        return File(sessionDir, fileName)
    }

    private fun createNextOutputFile(): File {
        val moviesDir = getMoviesDir()

        while (true) {
            val fileName = String.format(Locale.US, "strike_test_%03d.mp4", nextClipNumber++)
            val candidate = File(moviesDir, fileName)
            if (!candidate.exists()) {
                return candidate
            }
        }
    }

    private fun getMoviesDir(): File {
        val moviesDir = context.getExternalFilesDir(Environment.DIRECTORY_MOVIES)
            ?: context.filesDir
        if (!moviesDir.exists()) {
            moviesDir.mkdirs()
        }
        return moviesDir
    }

    private fun SelectedCaptureProfile.toQualitySelector(): QualitySelector {
        val quality = when (selectedCameraXQualityName.uppercase()) {
            "UHD" -> Quality.UHD
            "FHD" -> Quality.FHD
            "HD" -> Quality.HD
            "SD" -> Quality.SD
            else -> Quality.HD
        }
        return QualitySelector.from(
            quality,
            FallbackStrategy.higherQualityOrLowerThan(quality),
        )
    }

    companion object {
        private const val GUIDED_SESSION_DIR_NAME = "guided_jodan_session"
    }
}
