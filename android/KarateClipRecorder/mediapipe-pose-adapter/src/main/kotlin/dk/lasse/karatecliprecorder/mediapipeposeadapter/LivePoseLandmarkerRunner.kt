package dk.lasse.karatecliprecorder.mediapipeposeadapter

import android.content.Context
import android.graphics.Bitmap
import com.google.mediapipe.framework.image.BitmapImageBuilder
import com.google.mediapipe.framework.image.MPImage
import com.google.mediapipe.tasks.components.containers.Landmark
import com.google.mediapipe.tasks.components.containers.NormalizedLandmark
import com.google.mediapipe.tasks.core.BaseOptions
import com.google.mediapipe.tasks.core.Delegate
import com.google.mediapipe.tasks.vision.core.RunningMode
import com.google.mediapipe.tasks.vision.poselandmarker.PoseLandmarker
import com.google.mediapipe.tasks.vision.poselandmarker.PoseLandmarkerResult
import dk.lasse.karateanalyzer.core.LandmarkSource
import dk.lasse.karateanalyzer.core.Point3
import dk.lasse.karateanalyzer.core.PoseFrame
import dk.lasse.karateanalyzer.core.PoseLandmarkId
import dk.lasse.karateanalyzer.core.PoseLandmarkSample
import java.util.concurrent.atomic.AtomicBoolean

const val POSE_LANDMARKER_MODEL_ASSET_PATH = "mediapipe/pose_landmarker_full.task"

enum class PoseRecognizerLifecycleState { INITIALIZING, READY, FAILED, CLOSED }

data class LivePoseLandmarkerOutput(
    val poseFrame: PoseFrame,
    val inputWidth: Int,
    val inputHeight: Int,
    val inferenceLatencyMs: Long,
    val generationToken: Long,
)

class PoseFramePermit internal constructor(
    internal val timestampMs: Long,
    internal val runnerGeneration: Long,
    internal val outputGenerationToken: Long,
)

class MissingPoseLandmarkerModelException(path: String) :
    IllegalStateException("Missing Pose Landmarker model asset: $path")

class PoseLandmarkerModelAssetValidator(
    private val assetExists: (String) -> Boolean,
    private val path: String = POSE_LANDMARKER_MODEL_ASSET_PATH,
) {
    fun validate() {
        if (!assetExists(path)) throw MissingPoseLandmarkerModelException(path)
    }
}

class LivePoseLandmarkerRunner(
    context: Context,
    private val onResult: (LivePoseLandmarkerOutput, Bitmap) -> Unit,
    private val onError: (String) -> Unit,
    private val clientFactory: PoseLandmarkerClientFactory = MediaPipePoseLandmarkerClientFactory(context),
) : AutoCloseable {
    private val lock = Any()
    private val busy = AtomicBoolean(false)
    private var closed = false
    private var runnerGeneration = 0L
    private var lastTimestampMs = Long.MIN_VALUE
    private var pendingPermit: PoseFramePermit? = null
    private var inFlight: InFlight? = null
    private var client: PoseLandmarkerClient? = null
    var lifecycleState = PoseRecognizerLifecycleState.INITIALIZING
        private set

    init {
        client = runCatching { clientFactory.create(::completeResult, ::handleRuntimeError) }
            .onSuccess { lifecycleState = PoseRecognizerLifecycleState.READY }
            .getOrElse { error ->
                lifecycleState = PoseRecognizerLifecycleState.FAILED
                onError(error.message ?: "Pose Landmarker creation failed")
                null
            }
    }

    fun initializationSucceeded() = lifecycleState == PoseRecognizerLifecycleState.READY

    fun tryAcquireFrame(timestampMs: Long, generationToken: Long): PoseFramePermit? = synchronized(lock) {
        if (closed || client == null || lifecycleState != PoseRecognizerLifecycleState.READY) return null
        if (timestampMs <= lastTimestampMs || !busy.compareAndSet(false, true)) return null
        lastTimestampMs = timestampMs
        PoseFramePermit(timestampMs, runnerGeneration, generationToken).also { pendingPermit = it }
    }

    fun releasePermit(permit: PoseFramePermit) = synchronized(lock) {
        if (pendingPermit == permit) {
            pendingPermit = null
            busy.set(false)
        }
    }

    fun submit(bitmap: Bitmap, permit: PoseFramePermit): Boolean {
        val recognizer = client ?: return false
        var image: MPImage? = null
        return runCatching {
            image = BitmapImageBuilder(bitmap).build()
            synchronized(lock) {
                if (closed || permit.runnerGeneration != runnerGeneration || pendingPermit != permit) {
                    image?.close()
                    return false
                }
                inFlight = InFlight(bitmap, image!!, permit, bitmap.width, bitmap.height, System.currentTimeMillis())
                pendingPermit = null
            }
            recognizer.detectAsync(image!!, permit.timestampMs)
            true
        }.getOrElse { error ->
            synchronized(lock) {
                pendingPermit = null
                inFlight = null
                busy.set(false)
            }
            image?.close()
            onError("MediaPipe pose runtime error: ${error.message ?: error}")
            false
        }
    }

    private fun completeResult(result: PoseLandmarkerResult, callbackImage: MPImage?) {
        val timestampMs = result.timestampMs()
        val completed = synchronized(lock) {
            val current = inFlight
            if (current == null || current.permit.timestampMs != timestampMs || current.permit.runnerGeneration != runnerGeneration) null
            else {
                inFlight = null
                busy.set(false)
                current.image.close()
                current
            }
        }
        callbackImage?.close()
        if (completed == null) return
        val output = LivePoseLandmarkerOutput(
            poseFrame = MediaPipePoseResultMapper.map(result),
            inputWidth = completed.width,
            inputHeight = completed.height,
            inferenceLatencyMs = System.currentTimeMillis() - completed.startedAtMs,
            generationToken = completed.permit.outputGenerationToken,
        )
        runCatching { onResult(output, completed.bitmap) }
            .onFailure {
                if (!completed.bitmap.isRecycled) completed.bitmap.recycle()
                onError("Pose result handling failed: ${it.message ?: it}")
            }
    }

    private fun handleRuntimeError(message: String) {
        val bitmap = synchronized(lock) {
            val current = inFlight
            current?.image?.close()
            inFlight = null
            pendingPermit = null
            busy.set(false)
            current?.bitmap
        }
        if (bitmap?.isRecycled == false) bitmap.recycle()
        onError("MediaPipe pose runtime error: $message")
    }

    override fun close() {
        val pair = synchronized(lock) {
            if (closed) return
            closed = true
            runnerGeneration++
            lifecycleState = PoseRecognizerLifecycleState.CLOSED
            val current = inFlight
            current?.image?.close()
            inFlight = null
            pendingPermit = null
            busy.set(false)
            client.also { client = null } to current?.bitmap
        }
        pair.first?.close()
        if (pair.second?.isRecycled == false) pair.second?.recycle()
    }

    private data class InFlight(
        val bitmap: Bitmap,
        val image: MPImage,
        val permit: PoseFramePermit,
        val width: Int,
        val height: Int,
        val startedAtMs: Long,
    )
}

object MediaPipePoseResultMapper {
    fun map(result: PoseLandmarkerResult): PoseFrame {
        val normalized = result.landmarks().firstOrNull().orEmpty()
        val world = result.worldLandmarks().firstOrNull().orEmpty()
        val landmarks = PoseLandmarkId.entries.associateWith { id ->
            val index = id.ordinal
            val point = normalized.getOrNull(index)
            val worldPoint = world.getOrNull(index)
            point.toSample(worldPoint)
        }
        return PoseFrame(result.timestampMs(), landmarks)
    }

    private fun NormalizedLandmark?.toSample(world: Landmark?): PoseLandmarkSample {
        if (this == null || !x().isFinite() || !y().isFinite() || !z().isFinite()) return PoseLandmarkSample(null)
        return PoseLandmarkSample(
            position = Point3(x(), y(), z()),
            worldPosition = world?.takeIf { it.x().isFinite() && it.y().isFinite() && it.z().isFinite() }
                ?.let { Point3(it.x(), it.y(), it.z()) },
            visibility = visibility().orElse(0f),
            presence = presence().orElse(0f),
            source = LandmarkSource.OBSERVED,
        )
    }
}

fun interface PoseLandmarkerClientFactory {
    fun create(
        onResult: (PoseLandmarkerResult, MPImage?) -> Unit,
        onRuntimeError: (String) -> Unit,
    ): PoseLandmarkerClient
}

interface PoseLandmarkerClient : AutoCloseable {
    fun detectAsync(image: MPImage, timestampMs: Long)
    override fun close()
}

private class MediaPipePoseLandmarkerClientFactory(private val context: Context) : PoseLandmarkerClientFactory {
    override fun create(
        onResult: (PoseLandmarkerResult, MPImage?) -> Unit,
        onRuntimeError: (String) -> Unit,
    ): PoseLandmarkerClient {
        PoseLandmarkerModelAssetValidator(
            assetExists = { path ->
                runCatching {
                    context.assets.open(path).use { }
                    true
                }.getOrDefault(false)
            },
        ).validate()
        val options = PoseLandmarker.PoseLandmarkerOptions.builder()
            .setBaseOptions(
                BaseOptions.builder()
                    .setModelAssetPath(POSE_LANDMARKER_MODEL_ASSET_PATH)
                    .setDelegate(Delegate.CPU)
                    .build(),
            )
            .setRunningMode(RunningMode.LIVE_STREAM)
            .setNumPoses(1)
            .setMinPoseDetectionConfidence(0.5f)
            .setMinPosePresenceConfidence(0.5f)
            .setMinTrackingConfidence(0.5f)
            .setOutputSegmentationMasks(false)
            .setResultListener { result, image -> onResult(result, image) }
            .setErrorListener { error -> onRuntimeError(error.message ?: error.toString()) }
            .build()
        return MediaPipePoseLandmarkerClient(PoseLandmarker.createFromOptions(context, options))
    }
}

private class MediaPipePoseLandmarkerClient(private val landmarker: PoseLandmarker) : PoseLandmarkerClient {
    override fun detectAsync(image: MPImage, timestampMs: Long) = landmarker.detectAsync(image, timestampMs)
    override fun close() = landmarker.close()
}
