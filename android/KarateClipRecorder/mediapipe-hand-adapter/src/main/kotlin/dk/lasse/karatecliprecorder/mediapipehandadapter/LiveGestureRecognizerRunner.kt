package dk.lasse.karatecliprecorder.mediapipehandadapter

import android.content.Context
import android.graphics.Bitmap
import com.google.mediapipe.framework.image.BitmapImageBuilder
import com.google.mediapipe.framework.image.MPImage
import com.google.mediapipe.tasks.core.BaseOptions
import com.google.mediapipe.tasks.core.Delegate
import com.google.mediapipe.tasks.vision.core.RunningMode
import com.google.mediapipe.tasks.vision.gesturerecognizer.GestureRecognizer
import com.google.mediapipe.tasks.vision.gesturerecognizer.GestureRecognizerResult
import java.util.concurrent.atomic.AtomicBoolean

/** Adapter-neutral live recognizer output consumed by the app. */
data class LiveGestureRecognizerOutput(
    val timestampMs: Long,
    val observations: List<MediaPipeHandObservation>,
    val inputWidth: Int,
    val inputHeight: Int,
    val inferenceLatencyMs: Long?,
)

class LiveGestureRecognizerRunner(
    context: Context,
    private val onResult: (LiveGestureRecognizerOutput) -> Unit,
    private val onError: (String) -> Unit,
) : AutoCloseable {
    private val adapter = GestureRecognizerResultAdapter()
    private val busy = AtomicBoolean(false)
    private val lock = Any()
    private var closed = false
    private val timestampGate = MonotonicTimestampGate()
    private var inFlight: InFlight? = null
    private var recognizer: GestureRecognizer? = null

    init {
        recognizer = runCatching {
            GestureRecognizerModelAssetValidator { path ->
                runCatching { context.assets.open(path).close(); true }.getOrDefault(false)
            }.validate()
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
                .setResultListener(::handleResult)
                .setErrorListener { error -> handleRuntimeError(error.message ?: error.toString()) }
                .build()
            GestureRecognizer.createFromOptions(context, options)
        }.getOrElse { error ->
            onError(error.message ?: "Gesture Recognizer creation failed")
            null
        }
    }

    fun submit(bitmap: Bitmap, timestampMs: Long): Boolean {
        val recognizer = recognizer ?: return false
        synchronized(lock) {
            if (closed) return false
            if (!timestampGate.tryAccept(timestampMs)) return false
            if (!busy.compareAndSet(false, true)) return false
        }
        val image = BitmapImageBuilder(bitmap).build()
        synchronized(lock) { inFlight = InFlight(bitmap, image, timestampMs, System.currentTimeMillis()) }
        return runCatching { recognizer.recognizeAsync(image, timestampMs); true }.getOrElse { error ->
            releaseInFlight()
            onError("MediaPipe runtime error: ${error.message ?: error}")
            false
        }
    }

    private fun handleResult(result: GestureRecognizerResult, input: MPImage) {
        val completed = releaseInFlight(input)
        val timestamp = result.timestampMs()
        onResult(
            LiveGestureRecognizerOutput(
                timestampMs = timestamp,
                observations = adapter.adapt(result, timestamp),
                inputWidth = completed?.bitmap?.width ?: 0,
                inputHeight = completed?.bitmap?.height ?: 0,
                inferenceLatencyMs = completed?.startedAtMs?.let { System.currentTimeMillis() - it },
            ),
        )
    }

    private fun handleRuntimeError(message: String) {
        releaseInFlight()
        onError("MediaPipe runtime error: $message")
    }

    private fun releaseInFlight(expected: MPImage? = null): InFlight? = synchronized(lock) {
        val current = inFlight
        if (expected == null || current?.image === expected) {
            inFlight = null
            busy.set(false)
            current?.bitmap?.recycle()
            current?.image?.close()
            current
        } else null
    }

    override fun close() {
        val recognizerToClose = synchronized(lock) {
            if (closed) return
            closed = true
            releaseInFlight()
            recognizer.also { recognizer = null }
        }
        recognizerToClose?.close()
    }

    private data class InFlight(val bitmap: Bitmap, val image: MPImage, val timestampMs: Long, val startedAtMs: Long)
}
