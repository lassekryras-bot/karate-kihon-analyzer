package dk.lasse.karatecliprecorder.mediapipehandadapter

import android.content.Context
import android.graphics.Bitmap
import com.google.mediapipe.framework.image.BitmapImageBuilder
import com.google.mediapipe.framework.image.MPImage
import com.google.mediapipe.tasks.core.BaseOptions
import com.google.mediapipe.tasks.core.Delegate
import com.google.mediapipe.tasks.vision.core.RunningMode
import com.google.mediapipe.tasks.vision.gesturerecognizer.GestureRecognizer
import java.util.concurrent.atomic.AtomicBoolean

/** Adapter-neutral live recognizer output consumed by the app. */
data class LiveGestureRecognizerOutput(
    val timestampMs: Long,
    val observations: List<MediaPipeHandObservation>,
    val inputWidth: Int,
    val inputHeight: Int,
    val inferenceLatencyMs: Long?,
    val generationToken: Long = 0L,
)

class LiveGestureRecognizerRunner(
    context: Context,
    private val onResult: (LiveGestureRecognizerOutput) -> Unit,
    private val onError: (String) -> Unit,
    private val clientFactory: LiveGestureRecognizerClientFactory = MediaPipeLiveGestureRecognizerClientFactory(context),
) : AutoCloseable {
    private val busy = AtomicBoolean(false)
    private val lock = Any()
    private var closed = false
    private val timestampGate = MonotonicTimestampGate()
    private var generation = 0L
    private var inFlight: InFlight? = null
    private var client: LiveGestureRecognizerClient? = null

    init {
        client = runCatching {
            clientFactory.create(
                onResult = ::completeResult,
                onRuntimeError = ::handleRuntimeError,
            )
        }.getOrElse { error ->
            onError(error.message ?: "Gesture Recognizer creation failed")
            null
        }
    }

    /**
     * Returns true only after ownership of [bitmap] transfers to this runner. When false is
     * returned, callers keep ownership and must release it themselves.
     */
    fun submit(bitmap: Bitmap, timestampMs: Long, generationToken: Long = 0L): Boolean {
        val recognizer = client ?: return false
        val acceptedGeneration: Long
        synchronized(lock) {
            if (closed) return false
            if (!timestampGate.tryAccept(timestampMs)) return false
            if (!busy.compareAndSet(false, true)) return false
            acceptedGeneration = generation
        }
        val image = BitmapImageBuilder(bitmap).build()
        synchronized(lock) {
            inFlight = InFlight(
                bitmap = bitmap,
                image = image,
                timestampMs = timestampMs,
                width = bitmap.width,
                height = bitmap.height,
                startedAtMs = System.currentTimeMillis(),
                generation = acceptedGeneration,
                outputGenerationToken = generationToken,
            )
        }
        return runCatching { recognizer.recognizeAsync(image, timestampMs); true }.getOrElse { error ->
            // Ownership did not successfully transfer to MediaPipe. Close the MPImage we created,
            // keep caller bitmap ownership, and return false so CameraX can release the bitmap once.
            synchronized(lock) {
                if (inFlight?.timestampMs == timestampMs && inFlight?.generation == acceptedGeneration) {
                    inFlight = null
                    busy.set(false)
                }
            }
            image.close()
            onError("MediaPipe runtime error: ${error.message ?: error}")
            false
        }
    }

    private fun completeResult(
        timestampMs: Long,
        observations: List<MediaPipeHandObservation>,
        callbackImage: MPImage?,
    ) {
        val completed = releaseMatchingInFlight(timestampMs, recycleBitmap = true)
        callbackImage?.close()
        if (completed == null) return
        onResult(
            LiveGestureRecognizerOutput(
                timestampMs = timestampMs,
                observations = observations,
                inputWidth = completed.width,
                inputHeight = completed.height,
                inferenceLatencyMs = System.currentTimeMillis() - completed.startedAtMs,
                generationToken = completed.outputGenerationToken,
            ),
        )
    }

    private fun handleRuntimeError(message: String) {
        releaseCurrentInFlight(recycleBitmap = true)
        onError("MediaPipe runtime error: $message")
    }

    private fun releaseMatchingInFlight(timestampMs: Long, recycleBitmap: Boolean): InFlight? = synchronized(lock) {
        val current = inFlight
        if (current != null && current.timestampMs == timestampMs && current.generation == generation) {
            inFlight = null
            busy.set(false)
            current.image.close()
            if (recycleBitmap) current.bitmap.recycle()
            current
        } else null
    }

    private fun releaseCurrentInFlight(recycleBitmap: Boolean): InFlight? = synchronized(lock) {
        val current = inFlight
        inFlight = null
        busy.set(false)
        current?.image?.close()
        if (recycleBitmap) current?.bitmap?.recycle()
        current
    }

    override fun close() {
        val clientToClose = synchronized(lock) {
            if (closed) return
            closed = true
            generation++
            releaseCurrentInFlight(recycleBitmap = true)
            client.also { client = null }
        }
        clientToClose?.close()
    }

    private data class InFlight(
        val bitmap: Bitmap,
        val image: MPImage,
        val timestampMs: Long,
        val width: Int,
        val height: Int,
        val startedAtMs: Long,
        val generation: Long,
        val outputGenerationToken: Long,
    )
}

fun interface LiveGestureRecognizerClientFactory {
    fun create(
        onResult: (timestampMs: Long, observations: List<MediaPipeHandObservation>, callbackImage: MPImage?) -> Unit,
        onRuntimeError: (String) -> Unit,
    ): LiveGestureRecognizerClient
}

interface LiveGestureRecognizerClient : AutoCloseable {
    fun recognizeAsync(image: MPImage, timestampMs: Long)
    override fun close()
}

private class MediaPipeLiveGestureRecognizerClientFactory(
    private val context: Context,
) : LiveGestureRecognizerClientFactory {
    override fun create(
        onResult: (timestampMs: Long, observations: List<MediaPipeHandObservation>, callbackImage: MPImage?) -> Unit,
        onRuntimeError: (String) -> Unit,
    ): LiveGestureRecognizerClient {
        GestureRecognizerModelAssetValidator { path ->
            runCatching { context.assets.open(path).close(); true }.getOrDefault(false)
        }.validate()
        val adapter = GestureRecognizerResultAdapter()
        val baseOptions = BaseOptions.builder()
            .setModelAssetPath(GESTURE_RECOGNIZER_MODEL_ASSET_PATH)
            .setDelegate(Delegate.CPU)
            .build()
        val options = GestureRecognizer.GestureRecognizerOptions.builder()
            .setBaseOptions(baseOptions)
            .setRunningMode(RunningMode.LIVE_STREAM)
            .setMinHandDetectionConfidence(0.5f)
            .setMinHandPresenceConfidence(0.5f)
            .setMinTrackingConfidence(0.5f)
            .setResultListener { result, callbackImage ->
                val timestamp = result.timestampMs()
                onResult(timestamp, adapter.adapt(result, timestamp), callbackImage)
            }
            .setErrorListener { error -> onRuntimeError(error.message ?: error.toString()) }
            .build()
        return MediaPipeLiveGestureRecognizerClient(GestureRecognizer.createFromOptions(context, options))
    }
}

private class MediaPipeLiveGestureRecognizerClient(
    private val recognizer: GestureRecognizer,
) : LiveGestureRecognizerClient {
    override fun recognizeAsync(image: MPImage, timestampMs: Long) {
        recognizer.recognizeAsync(image, timestampMs)
    }

    override fun close() {
        recognizer.close()
    }
}
