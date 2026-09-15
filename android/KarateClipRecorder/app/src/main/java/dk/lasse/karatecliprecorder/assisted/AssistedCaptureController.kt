package dk.lasse.karatecliprecorder.assisted

import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import dk.lasse.karatecliprecorder.training.AssistedCaptureSetup
import dk.lasse.karatecliprecorder.sharedcapture.*

enum class AssistedCaptureState { READY, PREPARING, COUNTDOWN, STARTING, RECORDING, FINISHING, FINALIZING, SAVED, PROCESSING, COMPLETE, CANCELLED, FAILED }

/** Timing starts from CameraX Start, never from the Record tap or the visual countdown. */
class AssistedCaptureController(
    private val prepare: (SharedCaptureRequest, (() -> Unit) -> Unit) -> Unit,
    private val stopCamera: () -> Unit,
    private val playCount: (Int) -> Boolean,
    private val stopAudio: () -> Unit,
    private val persistCue: (Int, Int, Long) -> Unit,
    private val changed: (AssistedCaptureState, String) -> Unit,
    private val nowMs: () -> Long = SystemClock::elapsedRealtime,
    private val persistBoundary: (String, Long, String?) -> Unit = { _, _, _ -> },
    private val playPrompt: (CapturePrompt) -> Unit = {},
) {
    private val handler = Handler(Looper.getMainLooper())
    var state = AssistedCaptureState.READY
        private set
    private var generation = 0
    private var request = SharedCaptureRequests.recordAndAnalyze("Alternating straight punches", "Punches", 10, 1000, true)
    private var startedMs = 0L
    private var ordinal = 0
    private var nextCueMs = 0L
    private var lastCueMs: Long? = null
    val captureActive: Boolean get() = state in setOf(AssistedCaptureState.PREPARING, AssistedCaptureState.COUNTDOWN,
        AssistedCaptureState.STARTING, AssistedCaptureState.RECORDING, AssistedCaptureState.FINISHING, AssistedCaptureState.FINALIZING)

    fun recordAnother() {
        if (state == AssistedCaptureState.SAVED || state == AssistedCaptureState.COMPLETE) transition(AssistedCaptureState.READY, "Ready to record")
    }

    fun record(captureRequest: SharedCaptureRequest) {
        if (state !in setOf(AssistedCaptureState.READY, AssistedCaptureState.CANCELLED, AssistedCaptureState.FAILED, AssistedCaptureState.COMPLETE)) return
        captureRequest.startBlock()?.let { fail(it.message); return }
        require(captureRequest.captureType == CaptureType.VIDEO)
        request = captureRequest
        val attempt = ++generation
        transition(AssistedCaptureState.PREPARING, "Preparing recording…")
        prepare(captureRequest) { start ->
            if (attempt != generation || state != AssistedCaptureState.PREPARING) return@prepare
            fun countdown(value: Int) {
                if (attempt != generation) return
                if (value == 0) {
                    transition(AssistedCaptureState.STARTING, "Starting recording…")
                    start()
                } else {
                    transition(AssistedCaptureState.COUNTDOWN, value.toString())
                    handler.postDelayed({ countdown(value - 1) }, 1000)
                }
            }
            if (captureRequest.countdown == CaptureCountdown.NONE) countdown(0) else countdown(3)
        }
    }

    fun recordingStarted(monotonicMs: Long) {
        if (state != AssistedCaptureState.STARTING) { stopCamera(); return }
        startedMs = monotonicMs
        ordinal = 0
        lastCueMs = null
        nextCueMs = startedMs + 500L
        transition(AssistedCaptureState.RECORDING, "Recording • 0:00")
        tick(generation)
    }

    private fun tick(attempt: Int) {
        if (attempt != generation || state != AssistedCaptureState.RECORDING) return
        val now = nowMs()
        val planned = request.plannedRepetitions ?: 0
        val cadence = requireNotNull(request.cadenceMs)
        if (request.cueMode == CaptureCueMode.APP_CUED && ordinal < planned && now >= nextCueMs) {
            val value = ordinal % 10 + 1
            if (request.spokenMovementCues && !playCount(value)) {
                fail("Spoken counting unavailable. Recording stopped; check sound and retry.")
                return
            }
            ordinal++
            lastCueMs = nowMs()
            persistCue(value, ordinal, requireNotNull(lastCueMs))
            // Anchor normal cadence to Start; avoid a burst/overlap if the UI thread was delayed.
            nextCueMs = maxOf(startedMs + 500L + ordinal * cadence,
                now + AssistedCaptureSetup.MIN_CADENCE_MS)
            if (ordinal == planned && request.autoStop == CaptureAutoStop.AFTER_FINAL_CUE) {
                beginAutomaticFinish(attempt)
                return
            }
        }
        val seconds = (now - startedMs).coerceAtLeast(0) / 1000
        changed(state, "Recording • ${seconds / 60}:${(seconds % 60).toString().padStart(2, '0')}" +
            if (request.cueMode == CaptureCueMode.APP_CUED) " • Cue $ordinal/$planned" else "")
        handler.postDelayed({ tick(attempt) }, 25)
    }

    fun stop() {
        if (state == AssistedCaptureState.FINISHING) {
            persistBoundary("FORCE_STOP_REQUESTED", nowMs(), null)
            finishNow()
            return
        }
        if (state == AssistedCaptureState.RECORDING) {
            beginGracefulStop(null)
            return
        }
        if (state !in setOf(AssistedCaptureState.PREPARING, AssistedCaptureState.COUNTDOWN,
                AssistedCaptureState.STARTING, AssistedCaptureState.RECORDING)) return
        val beforeStart = state == AssistedCaptureState.PREPARING || state == AssistedCaptureState.COUNTDOWN
        persistBoundary("STOP_REQUESTED", nowMs(), null)
        ++generation
        handler.removeCallbacksAndMessages(null)
        stopAudio()
        stopCamera()
        transition(if (beforeStart) AssistedCaptureState.CANCELLED else AssistedCaptureState.FINALIZING,
            if (beforeStart) "Recording cancelled" else "Finalizing recording…")
    }

    /** Voice is a single graceful stop hook; recognizer ownership remains with the host. */
    fun voiceStop() {
        if (state == AssistedCaptureState.RECORDING) beginGracefulStop("voice_stop")
    }

    private fun beginGracefulStop(reason: String?) {
            val stopRequest = nowMs()
            persistBoundary("STOP_REQUESTED", stopRequest, reason)
            ++generation
            handler.removeCallbacksAndMessages(null)
            stopAudio()
            val target = gracefulEndMs(lastCueMs, stopRequest, requireNotNull(request.cadenceMs))
            if (target <= stopRequest) finishNow() else {
                transition(AssistedCaptureState.FINISHING, "Finishing…")
                handler.postDelayed({ if (request.operationalVoicePrompts) playPrompt(CapturePrompt.STOPPED); finishNow() }, target - stopRequest)
            }
    }

    private fun beginAutomaticFinish(attempt: Int) {
        if (attempt != generation || state != AssistedCaptureState.RECORDING) return
        ++generation
        handler.removeCallbacksAndMessages(null)
        val target = gracefulEndMs(lastCueMs, nowMs(), requireNotNull(request.cadenceMs))
        transition(AssistedCaptureState.FINISHING, "Finishing…")
        handler.postDelayed({ if (request.operationalVoicePrompts) playPrompt(request.completionPrompt); finishNow() },
            (target - nowMs()).coerceAtLeast(0))
    }

    private fun finishNow() {
        ++generation; handler.removeCallbacksAndMessages(null); stopAudio(); stopCamera()
        transition(AssistedCaptureState.FINALIZING, "Finalizing recording…")
    }

    fun interrupt(reason: String) {
        if (!captureActive || state == AssistedCaptureState.FINALIZING) return
        persistBoundary("INTERRUPTION_DETECTED", nowMs(), reason)
        if (state == AssistedCaptureState.PREPARING || state == AssistedCaptureState.COUNTDOWN) stop() else finishNow()
    }

    fun recordingFinalizing() {
        if (state == AssistedCaptureState.RECORDING || state == AssistedCaptureState.FINISHING || state == AssistedCaptureState.STARTING)
            persistBoundary("INTERRUPTION_DETECTED", nowMs(), "camera_finalized_without_stop")
        ++generation
        handler.removeCallbacksAndMessages(null)
        stopAudio()
        transition(AssistedCaptureState.FINALIZING, "Finalizing recording…")
    }
    fun saved() { transition(AssistedCaptureState.SAVED, "Recording saved") }
    fun processing() { transition(AssistedCaptureState.PROCESSING, "Recording saved\nCreating movement data…") }
    fun complete() { transition(AssistedCaptureState.COMPLETE, "Recording saved\nMovement data ready") }
    fun fail(message: String) {
        if (captureActive) persistBoundary("INTERRUPTION_DETECTED", nowMs(), message)
        ++generation
        handler.removeCallbacksAndMessages(null)
        stopAudio()
        stopCamera()
        transition(AssistedCaptureState.FAILED, "Failed: $message")
    }
    fun close() { interrupt("capture_page_destroyed"); ++generation; handler.removeCallbacksAndMessages(null); stopAudio() }
    companion object {
        fun gracefulEndMs(lastCueMs: Long?, stopRequestMs: Long, cadenceMs: Long): Long =
            (lastCueMs ?: stopRequestMs) + cadenceMs * 3 / 2
    }
    private fun transition(next: AssistedCaptureState, text: String) { state = next; changed(next, text) }
}
