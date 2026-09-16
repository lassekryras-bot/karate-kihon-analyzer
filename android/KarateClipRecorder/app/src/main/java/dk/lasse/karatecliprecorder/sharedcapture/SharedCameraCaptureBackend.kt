package dk.lasse.karatecliprecorder.sharedcapture

import android.content.Context
import android.util.Range
import androidx.camera.core.*
import androidx.camera.core.featuregroup.GroupableFeature
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.video.*
import androidx.camera.video.GroupableFeatures
import androidx.camera.view.PreviewView
import androidx.core.content.ContextCompat
import androidx.lifecycle.LifecycleOwner
import dk.lasse.karatecliprecorder.captureprofile.*
import java.io.File
import java.util.concurrent.Executor
import java.util.concurrent.atomic.AtomicBoolean

data class SharedCameraBinding(
    val selectedQuality: CaptureQuality?,
    val supportedQualities: List<CaptureQuality>,
    val rearLenses: List<RearLens>,
    val selectedLensId: String?,
    val minimumZoom: Float,
    val maximumZoom: Float,
    val captureProfile: SelectedCaptureProfile,
)

/**
 * The one CameraX ownership layer used by video and photo callers. It owns provider binding,
 * preview, camera selection, zoom/focus/exposure lock, active media use cases and cleanup.
 */
class SharedCameraCaptureBackend(
    context: Context,
    private val lifecycleOwner: LifecycleOwner,
    private val previewView: PreviewView,
) : AutoCloseable {
    private val appContext = context.applicationContext
    private val mainExecutor: Executor = ContextCompat.getMainExecutor(context)
    private val closed = AtomicBoolean(false)
    private var generation = 0L
    private var provider: ProcessCameraProvider? = null
    private var preview: Preview? = null
    private var camera: Camera? = null
    private var videoCapture: VideoCapture<Recorder>? = null
    private var imageCapture: ImageCapture? = null
    private var imageAnalysis: ImageAnalysis? = null
    private var recording: Recording? = null
    private var locked = false

    val recordingActive: Boolean get() = recording != null

    fun bind(request: SharedCaptureRequest, analysis: ImageAnalysis? = null,
             onReady: (SharedCameraBinding) -> Unit, onError: (String) -> Unit) {
        request.startBlock()?.let { onError(it.message); return }
        if (closed.get()) return
        val attempt = ++generation
        val future = ProcessCameraProvider.getInstance(appContext)
        future.addListener({
            if (!current(attempt)) return@addListener
            try {
                val nextProvider = future.get()
                val direction = if (request.camera == CaptureCamera.REAR) CameraSelector.LENS_FACING_BACK else CameraSelector.LENS_FACING_FRONT
                val facingSelector = CameraSelector.Builder().requireLensFacing(direction).build()
                val infos = facingSelector.filter(nextProvider.availableCameraInfos)
                check(infos.isNotEmpty()) { "${request.camera.name.lowercase().replaceFirstChar { it.uppercase() }} camera unavailable" }
                val rearLenses = if (request.camera == CaptureCamera.REAR) infos.map(AssistedCameraOptions::rearLens) else emptyList()
                val wantedId = request.preferredLensId?.takeIf { id -> rearLenses.any { it.id == id } }
                    ?: rearLenses.firstOrNull()?.id
                val selectedInfo = if (wantedId == null) infos.first() else infos.first { AssistedCameraOptions.rearLens(it).id == wantedId }
                val selector = CameraSelector.Builder().requireLensFacing(direction).addCameraFilter { candidates ->
                    candidates.filter { it === selectedInfo }
                }.build()
                val profile = CameraCapabilityInitializer.initialize(
                    selectedInfo,
                    wantedId,
                    requiresAnalysis = analysis != null,
                )
                val supported = if (request.captureType == CaptureType.VIDEO) {
                    AssistedCameraOptions.supported(selectedInfo, profile.capabilityReport)
                } else emptyList()
                val requested = when (val policy = request.quality) {
                    CaptureQualityPolicy.AutomaticFastMovement -> null
                    is CaptureQualityPolicy.Exact -> CaptureQuality(policy.value.resolution, policy.value.framesPerSecond)
                }
                val candidates = when {
                    request.captureType != CaptureType.VIDEO -> emptyList()
                    requested != null -> listOf(requested)
                    else -> supported
                }
                var nextPreview: Preview? = null
                var bound: Camera? = null
                var chosen: CaptureQuality? = null
                var nextVideo: VideoCapture<Recorder>? = null
                var nextPhoto: ImageCapture? = null
                var bindError: Throwable? = null
                if (request.captureType == CaptureType.VIDEO) {
                    for (candidate in candidates.ifEmpty { listOf(CaptureQuality("HD", 30)) }) {
                        try {
                            nextProvider.unbindAll()
                            val previewUseCase = Preview.Builder().build().also {
                                it.setSurfaceProvider(previewView.surfaceProvider)
                            }
                            val recorder = Recorder.Builder()
                                .setQualitySelector(QualitySelector.from(candidate.toCameraXQuality()))
                                .build()
                            val capture = VideoCapture.Builder(recorder)
                                .setTargetFrameRate(Range(candidate.fps, candidate.fps))
                                .build()
                            capture.targetRotation = previewView.display?.rotation ?: android.view.Surface.ROTATION_0

                            bound = nextProvider.bindToLifecycle(
                                lifecycleOwner,
                                selector,
                                *listOfNotNull(previewUseCase, capture, analysis).toTypedArray()
                            )
                            chosen = candidate
                            nextVideo = capture
                            nextPreview = previewUseCase
                            break
                        } catch (error: Throwable) {
                            bindError = error
                            nextProvider.unbindAll()
                        }
                    }
                } else {
                    nextProvider.unbindAll()
                    val previewUseCase = Preview.Builder().build().also {
                        it.setSurfaceProvider(previewView.surfaceProvider)
                    }
                    nextPhoto = ImageCapture.Builder().setCaptureMode(ImageCapture.CAPTURE_MODE_MINIMIZE_LATENCY).build().also {
                        it.targetRotation = previewView.display?.rotation ?: android.view.Surface.ROTATION_0
                    }
                    bound = nextProvider.bindToLifecycle(lifecycleOwner, selector, previewUseCase, nextPhoto)
                    nextPreview = previewUseCase
                }
                checkNotNull(bound) { "No supported ${request.captureType.name.lowercase()} configuration: ${bindError?.message}" }
                if (!current(attempt)) {
                    nextProvider.unbind(*listOfNotNull(nextPreview, nextVideo, nextPhoto, analysis).toTypedArray())
                    return@addListener
                }
                provider = nextProvider
                preview = nextPreview
                camera = bound
                videoCapture = nextVideo
                imageCapture = nextPhoto
                imageAnalysis = analysis
                previewView.scaleType = PreviewView.ScaleType.FILL_CENTER
                val zoomState = bound.cameraInfo.zoomState.value
                bound.cameraControl.setZoomRatio(request.preferredZoom.coerceIn(
                    zoomState?.minZoomRatio ?: 1f, zoomState?.maxZoomRatio ?: 1f))
                onReady(SharedCameraBinding(chosen, supported, rearLenses, wantedId,
                    zoomState?.minZoomRatio ?: 1f, zoomState?.maxZoomRatio ?: 1f, profile))
            } catch (error: Throwable) {
                if (current(attempt)) onError("Camera preview failed: ${error.message}")
            }
        }, mainExecutor)
    }

    fun startVideo(file: File, onEvent: (VideoRecordEvent) -> Unit) {
        check(!closed.get() && recording == null) { "Camera is not ready to record" }
        val capture = checkNotNull(videoCapture) { "Video capture is not bound" }
        recording = capture.output.prepareRecording(appContext, FileOutputOptions.Builder(file).build())
            .start(mainExecutor) { event ->
                if (event is VideoRecordEvent.Finalize) recording = null
                onEvent(event)
            }
    }

    fun capturePhoto(file: File, onSaved: () -> Unit, onError: (String) -> Unit) {
        val capture = imageCapture
        if (capture == null || closed.get()) { onError("Photo capture is not ready"); return }
        capture.takePicture(ImageCapture.OutputFileOptions.Builder(file).build(), mainExecutor,
            object : ImageCapture.OnImageSavedCallback {
                override fun onImageSaved(result: ImageCapture.OutputFileResults) { onSaved() }
                override fun onError(exception: ImageCaptureException) {
                    onError("Photo capture failed: ${exception.message}")
                }
            })
    }

    fun stopVideo() { recording?.stop() }

    fun setZoom(ratio: Float) {
        if (locked) return
        val state = camera?.cameraInfo?.zoomState?.value ?: return
        camera?.cameraControl?.setZoomRatio(ratio.coerceIn(state.minZoomRatio, state.maxZoomRatio))
    }

    fun focus(x: Float, y: Float) {
        if (locked) return
        val point = previewView.meteringPointFactory.createPoint(x, y)
        camera?.cameraControl?.startFocusAndMetering(FocusMeteringAction.Builder(point).disableAutoCancel().build())
    }

    fun lockConfiguration(value: Boolean) {
        locked = value
        if (value) {
            videoCapture?.targetRotation = previewView.display?.rotation ?: android.view.Surface.ROTATION_0
            imageCapture?.targetRotation = previewView.display?.rotation ?: android.view.Surface.ROTATION_0
        }
        stabilizeExposure(value)
    }

    @androidx.annotation.OptIn(markerClass = [androidx.camera.camera2.interop.ExperimentalCamera2Interop::class])
    private fun stabilizeExposure(value: Boolean) {
        val bound = camera ?: return
        val info = androidx.camera.camera2.interop.Camera2CameraInfo.from(bound.cameraInfo)
        if (info.getCameraCharacteristic(android.hardware.camera2.CameraCharacteristics.CONTROL_AE_LOCK_AVAILABLE) == true) {
            val options = androidx.camera.camera2.interop.CaptureRequestOptions.Builder()
                .setCaptureRequestOption(android.hardware.camera2.CaptureRequest.CONTROL_AE_LOCK, value).build()
            androidx.camera.camera2.interop.Camera2CameraControl.from(bound.cameraControl).setCaptureRequestOptions(options)
        }
    }

    override fun close() {
        if (!closed.compareAndSet(false, true)) return
        generation++
        recording?.close()
        recording = null
        provider?.unbind(*listOfNotNull(preview, videoCapture, imageCapture, imageAnalysis).toTypedArray())
        preview = null
        videoCapture = null
        imageCapture = null
        imageAnalysis = null
        camera = null
        provider = null
    }

    private fun current(attempt: Long) = !closed.get() && generation == attempt
    private fun CaptureQuality.toCameraXQuality() = when (name) {
        "UHD" -> Quality.UHD
        "FHD" -> Quality.FHD
        "HD" -> Quality.HD
        else -> Quality.SD
    }
}
