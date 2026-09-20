package dk.lasse.karatecliprecorder

import android.content.Context
import android.graphics.Bitmap
import android.os.Environment
import android.os.SystemClock
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageProxy
import androidx.camera.core.resolutionselector.ResolutionSelector
import androidx.camera.core.resolutionselector.ResolutionStrategy
import androidx.camera.video.VideoRecordEvent
import androidx.camera.view.PreviewView
import androidx.lifecycle.LifecycleOwner
import dk.lasse.karatecliprecorder.captureprofile.CaptureQuality
import dk.lasse.karatecliprecorder.captureprofile.RearLens
import dk.lasse.karatecliprecorder.captureprofile.SelectedCaptureProfile
import dk.lasse.karatecliprecorder.profile.BodyMeasurementSnapshot
import dk.lasse.karatecliprecorder.sharedcapture.*
import dk.lasse.karatecliprecorder.training.*
import java.io.File
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean

/** Compatibility adapter for guided sessions; all CameraX ownership lives in SharedCameraCaptureBackend. */
class CameraXRecordingAdapter(
    private val context: Context,
    lifecycleOwner: LifecycleOwner,
    private val previewView: PreviewView,
    private val onStateChanged: (RecordingState) -> Unit,
    private val onSaved: (RecordingResult) -> Unit,
    private val onError: (String) -> Unit,
    private val onAnalysisError: (String) -> Unit = onError,
    private val onCaptureProfileSelected: (SelectedCaptureProfile) -> Unit = {},
    private val cameraSelector: CameraSelector = CameraSelector.DEFAULT_BACK_CAMERA,
    val analysisInputMirrored: Boolean = false,
    private val onAnalysisFramePermit: (Long) -> Any? = { null },
    private val onAnalysisPermitRelease: (Any) -> Unit = {},
    private val onAnalysisFrame: (Bitmap, Long, Any?, FloatArray?) -> Boolean = { _, _, _, _ -> false },
    private val onRecordingStarted: (Long) -> Unit = {},
    private val onRecordingFinalizing: () -> Unit = {},
    private val previewOnly: Boolean = false,
    private val onCameraOptions: (List<CaptureQuality>, CaptureQuality, Float, Float) -> Unit = { _, _, _, _ -> },
    private val onRearLenses: (List<RearLens>, String) -> Unit = { _, _ -> },
    private val bodyMeasurements: () -> BodyMeasurementSnapshot? = { null },
) : SessionRecordingAdapter, AutoCloseable {
    private val training = TrainingServices.get(context)
    private val persistence = CapturePersistenceCoordinator(training)
    private val camera = SharedCameraCaptureBackend(context, lifecycleOwner, previewView)
    private val analysisExecutor: ExecutorService = Executors.newSingleThreadExecutor()
    private val analysisEnabled = AtomicBoolean(false)
    private val closed = AtomicBoolean(false)
    private var imageAnalysis: ImageAnalysis? = null
    private var requestedQuality: CaptureQuality? = null
    private var requestedLens: String? = null
    private var requestedZoom = 1f
    private var setupLocked = false
    private var recordingPending = false
    private var pendingCancelled = false
    private var captureLease: AutoCloseable? = null
    private var cancelPrepared: (() -> Unit)? = null
    private var trainingSessionId: String? = null
    private var recordingStartMs: Long? = null
    private var pendingOutcome = CaptureOutcome.COMPLETED
    private val pendingEvents = mutableListOf<Pair<String, Long>>()
    private var measurementSessionActive = false
    private var sessionMeasurements: BodyMeasurementSnapshot? = null

    var selectedCaptureProfile: SelectedCaptureProfile? = null
        private set

    override fun beginMeasurementSession() {
        sessionMeasurements = bodyMeasurements()
        measurementSessionActive = true
    }

    override fun endMeasurementSession() {
        measurementSessionActive = false
        sessionMeasurements = null
    }

    fun bindCameraPreview() {
        if (closed.get()) return
        onStateChanged(RecordingState.PREPARING)
        if (!previewOnly && imageAnalysis == null) {
            imageAnalysis = ImageAnalysis.Builder()
                .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                .setOutputImageFormat(ImageAnalysis.OUTPUT_IMAGE_FORMAT_RGBA_8888)
                .setResolutionSelector(
                    ResolutionSelector.Builder()
                        .setResolutionStrategy(
                            ResolutionStrategy(android.util.Size(640, 480), ResolutionStrategy.FALLBACK_RULE_CLOSEST_HIGHER_THEN_LOWER)
                        )
                        .build()
                )
                .build().also { it.setAnalyzer(analysisExecutor, ::analyzeImage) }
        }
        camera.bind(currentPreviewRequest(), imageAnalysis,
            onReady = { binding ->
                selectedCaptureProfile = binding.captureProfile
                onCaptureProfileSelected(binding.captureProfile)
                if (previewOnly) {
                    binding.selectedQuality?.let {
                        onCameraOptions(binding.supportedQualities, it, binding.minimumZoom, binding.maximumZoom)
                    }
                    binding.selectedLensId?.let { onRearLenses(binding.rearLenses, it) }
                }
                onStateChanged(RecordingState.IDLE)
            },
            onError = { message -> onStateChanged(RecordingState.FAILED); onError(message) })
    }

    fun setCaptureQuality(quality: CaptureQuality?) {
        if (setupLocked) return
        requestedQuality = quality
        bindCameraPreview()
    }

    fun setRearLens(id: String) {
        if (setupLocked) return
        requestedLens = id
        requestedZoom = 1f
        requestedQuality = null
        bindCameraPreview()
    }

    fun setZoom(ratio: Float) {
        if (setupLocked) return
        requestedZoom = ratio
        camera.setZoom(ratio)
    }

    fun focus(x: Float, y: Float) = camera.focus(x, y)

    fun lockSetup(locked: Boolean) {
        setupLocked = locked
        camera.lockConfiguration(locked)
    }

    fun setAnalysisEnabled(enabled: Boolean) { analysisEnabled.set(enabled) }

    private fun analyzeImage(image: ImageProxy) {
        var permit: Any? = null
        try {
            if (!analysisEnabled.get() || closed.get()) return
            val timestampMs = image.imageInfo.timestamp / 1_000_000L
            permit = onAnalysisFramePermit(timestampMs) ?: return
            val bitmap = image.toUprightBitmap() ?: run {
                onAnalysisError("Camera analysis frame conversion failed.")
                return
            }
            var transferred = false
            try { transferred = onAnalysisFrame(bitmap, timestampMs, permit, null) }
            finally { if (!transferred && !bitmap.isRecycled) bitmap.recycle() }
            if (transferred) permit = null
        } catch (error: Exception) {
            onAnalysisError("Camera analysis failed: ${error.message}")
        } finally {
            permit?.let(onAnalysisPermitRelease)
            image.close()
        }
    }

    private fun ImageProxy.toUprightBitmap(): Bitmap? {
        val plane = planes.firstOrNull() ?: return null
        return CameraRgbaBitmapConverter.convert(plane.buffer, width, height, plane.pixelStride,
            plane.rowStride, imageInfo.rotationDegrees)
    }

    override fun startRecording(customName: String?) = prepareRecording(legacyVideoRequest(), customName) { it() }

    fun prepareSharedCapture(request: SharedCaptureRequest, onPrepared: (() -> Unit) -> Unit) =
        prepareRecording(request, null, onPrepared)

    private fun prepareRecording(request: SharedCaptureRequest, customName: String?, onPrepared: (() -> Unit) -> Unit) {
        request.startBlock()?.let { onError(it.message); return }
        if (closed.get()) return
        if (camera.recordingActive || recordingPending) {
            onError("Previous recording is still finishing. Try again shortly.")
            return
        }
        val snapshot = if (measurementSessionActive) sessionMeasurements else bodyMeasurements()
        val userId = snapshot?.profileId
        if (userId == null) {
            onStateChanged(RecordingState.FAILED)
            onError("Select a training profile before recording.")
            return
        }
        captureLease = ProcessingCoordinator.reserveCapture()
        recordingPending = true
        pendingCancelled = false
        pendingOutcome = CaptureOutcome.COMPLETED
        recordingStartMs = null
        pendingEvents.clear()
        training.submit({
            ProcessingCoordinator.awaitCapturePriority()
            val legacyFile = customName?.let { createGuidedSessionFile("${it.removeSuffix(".mp4")}_${trainingId()}.mp4") }
            persistence.prepare(request, userId, cameraProvenance(), snapshot, legacyFile)
        }) { preparedResult ->
            if (preparedResult.isFailure) {
                releaseCapturePriority()
                recordingPending = false
                if (!closed.get()) {
                    onStateChanged(RecordingState.FAILED)
                    onError("Could not save capture session: ${preparedResult.exceptionOrNull()?.message}")
                }
                return@submit
            }
            val prepared = preparedResult.getOrThrow()
            trainingSessionId = prepared.session.sessionId
            val cancel = {
                releaseCapturePriority()
                recordingPending = false
                cancelPrepared = null
                training.submit({ persistence.cancelPrepared(prepared, "cancelled_before_capture") })
                Unit
            }
            if (closed.get() || pendingCancelled) { cancel(); return@submit }
            cancelPrepared = cancel
            onPrepared startPrepared@{
                if (closed.get() || pendingCancelled) { cancel(); return@startPrepared }
                if (request.captureType != CaptureType.VIDEO) {
                    cancel()
                    onError("This recording adapter requires a video request.")
                    return@startPrepared
                }
                val block = ProcessingPolicy.recordingBlock(ProcessingPolicy.snapshot(context), ProcessingPreferences(context).minimumBattery)
                if (block != null) {
                    cancel()
                    onStateChanged(RecordingState.FAILED)
                    onError(block)
                    return@startPrepared
                }
                cancelPrepared = null
                recordingPending = false
                startPreparedVideo(prepared, snapshot)
            }
        }
    }

    private fun startPreparedVideo(prepared: PreparedCapture, snapshot: BodyMeasurementSnapshot?) {
        try {
            camera.startVideo(prepared.file) { event ->
                when (event) {
                    is VideoRecordEvent.Start -> {
                        if (closed.get() || pendingCancelled) { camera.stopVideo(); return@startVideo }
                        recordingStartMs = SystemClock.elapsedRealtime() - event.recordingStats.recordedDurationNanos / 1_000_000
                        training.submit({ it.setSessionState(prepared.session.sessionId, SessionState.RECORDING) })
                        onStateChanged(RecordingState.RECORDING)
                        camera.lockConfiguration(previewOnly)
                        onRecordingStarted(requireNotNull(recordingStartMs))
                        pendingEvents.toList().also { pendingEvents.clear() }.forEach { (name, time) -> recordSessionEvent(name, time) }
                    }
                    is VideoRecordEvent.Finalize -> finalizeVideo(prepared, snapshot, event)
                }
            }
        } catch (error: Exception) {
            releaseCapturePriority()
            training.submit({ persistence.finalize(prepared, CaptureOutcome.FAILED, failure = error.message ?: "CameraX start failed") })
            onStateChanged(RecordingState.FAILED)
            onError("Recording could not start: ${error.message}")
        }
    }

    private fun finalizeVideo(prepared: PreparedCapture, snapshot: BodyMeasurementSnapshot?, event: VideoRecordEvent.Finalize) {
        recordingPending = true
        if (!closed.get()) onRecordingFinalizing()
        val cameraError = if (event.hasError()) "Recording failed: ${event.error}" else null
        val startMs = recordingStartMs
        training.submit({
            val expectedFps = selectedCaptureProfile?.preferredTargetFps
            val metadata = if (cameraError == null) runCatching { RecordedVideoMetadata.read(prepared.file, expectedFps) }
                else Result.failure(IllegalStateException(cameraError))
            val failure = cameraError ?: metadata.exceptionOrNull()?.let { it.message ?: "MP4 verification failed" }
            val media = metadata.getOrNull()
            media?.verificationResult?.let { verification ->
                selectedCaptureProfile = selectedCaptureProfile?.withActualRecordedFps(verification.actualFps, verification.isVerified)
            }
            val persisted = persistence.finalize(prepared, pendingOutcome,
                event.recordingStats.recordedDurationNanos / 1000, media?.width, media?.height, media?.frameRate, failure)
            check(persisted.mediaFinalized) { persisted.failureReason ?: "Unreadable video" }
            if (prepared.request.callerId != "record_and_analyze") runCatching {
                File(prepared.file.parentFile, "${prepared.file.nameWithoutExtension}.body-measurements.json")
                    .writeText(requireNotNull(snapshot).toJson().toString(2))
            }
            persisted
        }) { saved ->
            releaseCapturePriority()
            recordingPending = false
            if (closed.get()) return@submit
            val result = saved.getOrNull()
            if (result == null || !result.mediaFinalized) {
                onStateChanged(RecordingState.FAILED)
                onError(cameraError ?: "Recording finalization or verification failed: ${saved.exceptionOrNull()?.message}")
            } else {
                onStateChanged(RecordingState.SAVED)
                onSaved(RecordingResult(prepared.file.name, prepared.file.absolutePath, event.outputResults.outputUri,
                    persistedCapture = result, recordingStartMonotonicMs = startMs,
                    userId = prepared.session.userId, guided = prepared.session.guided))
            }
        }
    }

    override fun recordSessionEvent(name: String, monotonicMs: Long) {
        val sessionId = trainingSessionId ?: return
        val start = recordingStartMs
        if (start == null) { pendingEvents += name to monotonicMs; return }
        val elapsed = monotonicMs - start
        training.submit({ it.addEvent(SessionEvent(sessionId = sessionId, type = "cue",
            timestampUs = elapsed.coerceAtLeast(0) * 1000, data = name,
            timingSource = if (elapsed < 0) "before_video_start_clamped" else "CameraX_Start_elapsedRealtime_ms")) }) {
            if (it.isFailure && !closed.get()) onError("Could not save session cue: ${it.exceptionOrNull()?.message}")
        }
    }

    fun recordBoundary(type: String, monotonicMs: Long, reason: String?) {
        if (type == "FORCE_STOP_REQUESTED") pendingOutcome = CaptureOutcome.FORCE_STOPPED
        if (type == "INTERRUPTION_DETECTED") pendingOutcome = CaptureOutcome.INTERRUPTED
        val id = trainingSessionId ?: return
        val start = recordingStartMs
        training.submit({ it.recordBoundary(SessionEvent(sessionId = id, type = type,
            timestampUs = if (start == null) 0 else (monotonicMs - start).coerceAtLeast(0) * 1000,
            data = reason, timingSource = if (start == null) "before_video_start" else "CameraX_Start_elapsedRealtime_ms"),
            if (type == "INTERRUPTION_DETECTED") reason else null) })
    }

    /**
     * Persists a `spoken_count` event: the package-defined estimated audible cue timestamp
     * (`playback_start + immutable A10 offset`). This serves as the authoritative semantic cue
     * reference for reaction and movement analysis.
     */
    fun recordCountCue(value: Int, ordinal: Int, monotonicMs: Long, packageVersionId: String? = null, assetId: String? = null) {
        val sessionId = trainingSessionId ?: return
        val start = recordingStartMs ?: return
        val dataStr = buildString {
            append("count=").append(value)
            append(";ordinal=").append(ordinal)
            if (packageVersionId != null) append(";packageVersion=").append(packageVersionId)
            if (assetId != null) append(";asset=").append(assetId)
        }
        val timingSource = if (packageVersionId != null) "audio_cue_anchor_A10" else "playback_request_CameraX_Start_elapsedRealtime_ms"
        training.submit({ it.addEvent(SessionEvent(sessionId = sessionId, type = "spoken_count",
            timestampUs = (monotonicMs - start).coerceAtLeast(0) * 1000,
            data = dataStr, timingSource = timingSource)) }) {
            if (it.isFailure && !closed.get()) onError("Could not persist count cue: ${it.exceptionOrNull()?.message}")
        }
    }

    /**
     * Persists a `cue_playback_start` event: the app playback request/start-command timestamp.
     * Captures when playback was requested via SoundPool. Any future device/audio route latency
     * calibration can be applied downstream without mutating this recorded event.
     */
    fun recordPlaybackStart(value: Int, ordinal: Int, monotonicMs: Long, packageVersionId: String? = null, assetId: String? = null) {
        val sessionId = trainingSessionId ?: return
        val start = recordingStartMs ?: return
        val dataStr = buildString {
            append("count=").append(value)
            append(";ordinal=").append(ordinal)
            if (packageVersionId != null) append(";packageVersion=").append(packageVersionId)
            if (assetId != null) append(";asset=").append(assetId)
        }
        training.submit({ it.addEvent(SessionEvent(sessionId = sessionId, type = "cue_playback_start",
            timestampUs = (monotonicMs - start).coerceAtLeast(0) * 1000,
            data = dataStr, timingSource = "CameraX_Start_elapsedRealtime_ms")) }) {
            if (it.isFailure && !closed.get()) onError("Could not persist playback start: ${it.exceptionOrNull()?.message}")
        }
    }

    override fun currentRecordingSessionId(): String? = trainingSessionId

    override fun stopRecording() {
        pendingCancelled = true
        cancelPrepared?.invoke()
        if (camera.recordingActive) trainingSessionId?.let { id ->
            training.submit({ it.setSessionState(id, SessionState.FINALIZING) })
        }
        camera.stopVideo()
    }

    override fun createGuidedSessionFile(fileName: String): File {
        val sessionDir = File(getMoviesDir(), GUIDED_SESSION_DIR_NAME)
        if (!sessionDir.exists()) sessionDir.mkdirs()
        return File(sessionDir, fileName)
    }

    private fun getMoviesDir(): File = (context.getExternalFilesDir(Environment.DIRECTORY_MOVIES) ?: context.filesDir)
        .also { if (!it.exists()) it.mkdirs() }

    private fun currentPreviewRequest(): SharedCaptureRequest {
        val base = if (previewOnly) SharedCaptureRequests.recordAndAnalyze(
            "Alternating straight punches", "Punches", 10, 1000, true)
        else legacyVideoRequest()
        return base.copy(
            camera = if (cameraSelector == CameraSelector.DEFAULT_FRONT_CAMERA) CaptureCamera.FRONT else CaptureCamera.REAR,
            preferredLensId = requestedLens,
            preferredZoom = requestedZoom,
            quality = requestedQuality?.let { CaptureQualityPolicy.Exact(CaptureQualityRequest(it.name, it.fps)) }
                ?: CaptureQualityPolicy.AutomaticFastMovement,
        )
    }

    private fun legacyVideoRequest() = SharedCaptureRequest(
        captureType = CaptureType.VIDEO,
        trigger = CaptureTrigger.CALLER_CONTROLLED,
        callerId = "guided_session",
        activityContextId = if (measurementSessionActive) "guided_jodan_session" else "legacy_recording",
        expectedActivity = if (measurementSessionActive) "Guided Jodan punches" else null,
        plannedRepetitions = if (measurementSessionActive) 10 else null,
        camera = if (cameraSelector == CameraSelector.DEFAULT_FRONT_CAMERA) CaptureCamera.FRONT else CaptureCamera.REAR,
        countdown = CaptureCountdown.NONE,
        autoStop = CaptureAutoStop.MANUAL,
        operationalVoicePrompts = true,
    )

    private fun cameraProvenance() = "${if (cameraSelector == CameraSelector.DEFAULT_FRONT_CAMERA) "front" else "back"};" +
        "cameraId=$requestedLens;zoom=$requestedZoom;quality=${requestedQuality ?: "automatic"};rotation=${previewView.display?.rotation}"

    private fun releaseCapturePriority() { captureLease?.close(); captureLease = null }

    override fun close() {
        if (!closed.compareAndSet(false, true)) return
        pendingCancelled = true
        cancelPrepared?.invoke()
        analysisEnabled.set(false)
        imageAnalysis?.clearAnalyzer()
        imageAnalysis = null
        camera.close()
        analysisExecutor.shutdownNow()
    }

    companion object { private const val GUIDED_SESSION_DIR_NAME = "guided_jodan_session" }
}
