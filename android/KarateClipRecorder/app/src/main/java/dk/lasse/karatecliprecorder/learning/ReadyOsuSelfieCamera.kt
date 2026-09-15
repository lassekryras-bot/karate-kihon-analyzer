package dk.lasse.karatecliprecorder.learning

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Matrix
import androidx.camera.view.PreviewView
import androidx.lifecycle.LifecycleOwner
import dk.lasse.karatecliprecorder.sharedcapture.*
import dk.lasse.karatecliprecorder.training.*
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong

/** Activity-specific voice orchestration over the same backend and persistence used by video capture. */
class ReadyOsuSelfieCamera(
    context: Context,
    lifecycleOwner: LifecycleOwner,
    previewView: PreviewView,
    private val userId: () -> String,
    private val onReady: () -> Unit,
    private val onCaptured: (Bitmap, PersistedCaptureResult) -> Unit,
    private val onFailure: (ReadyOsuSelfieCameraFailure) -> Unit,
) : AutoCloseable {
    private val appContext = context.applicationContext
    private val services = TrainingServices.get(context)
    private val persistence = CapturePersistenceCoordinator(services)
    private val backend = SharedCameraCaptureBackend(context, lifecycleOwner, previewView)
    private val request = SharedCaptureRequests.readyOsuSelfie()
    private val generation = AtomicLong(0L)
    private val closed = AtomicBoolean(false)
    private var ready = false
    private var capturePending = false
    private var interrupted = false
    private var lease: AutoCloseable? = null

    fun start() {
        if (closed.get()) return
        val attempt = generation.incrementAndGet()
        backend.bind(request,
            onReady = {
                if (current(attempt)) { ready = true; onReady() }
            },
            onError = {
                if (current(attempt)) onFailure(ReadyOsuSelfieCameraFailure.FRONT_CAMERA_UNAVAILABLE)
            })
    }

    fun capture() {
        if (!ready || capturePending || closed.get()) {
            onFailure(ReadyOsuSelfieCameraFailure.CAPTURE_FAILED)
            return
        }
        capturePending = true
        val attempt = generation.get()
        lease = ProcessingCoordinator.reserveCapture()
        val blocked = ProcessingPolicy.recordingBlock(
            ProcessingPolicy.snapshot(appContext), ProcessingPreferences(appContext).minimumBattery)
        if (blocked != null) {
            capturePending = false
            finishLease()
            onFailure(ReadyOsuSelfieCameraFailure.CAPTURE_FAILED)
            return
        }
        services.submit({
            ProcessingCoordinator.awaitCapturePriority()
            persistence.prepare(request, userId(), "front;voice_triggered;shared_backend")
        }) { preparedResult ->
            val prepared = preparedResult.getOrNull()
            if (prepared == null) {
                finishLease()
                capturePending = false
                if (current(attempt)) onFailure(ReadyOsuSelfieCameraFailure.CAPTURE_FAILED)
                return@submit
            }
            if (!current(attempt)) {
                services.submit({ persistence.cancelPrepared(prepared, "capture_page_destroyed") }) {
                    finishLease(); capturePending = false
                }
                return@submit
            }
            backend.capturePhoto(prepared.file,
                onSaved = { finalizePhoto(prepared, attempt) },
                onError = { failure ->
                    services.submit({ persistence.finalize(prepared, CaptureOutcome.FAILED, failure = failure) }) {
                        finishLease(); capturePending = false
                        if (current(attempt)) onFailure(ReadyOsuSelfieCameraFailure.CAPTURE_FAILED)
                    }
                })
        }
    }

    private fun finalizePhoto(prepared: PreparedCapture, attempt: Long) {
        val source = BitmapFactory.decodeFile(prepared.file.absolutePath)
        if (source == null) {
            services.submit({ persistence.finalize(prepared, CaptureOutcome.FAILED, failure = "Finalized photo could not be decoded") }) {
                finishLease(); capturePending = false
                if (current(attempt)) onFailure(ReadyOsuSelfieCameraFailure.CAPTURE_FAILED)
            }
            return
        }
        val mirrored = Bitmap.createBitmap(source, 0, 0, source.width, source.height,
            Matrix().apply { postScale(-1f, 1f) }, true).also { if (it !== source) source.recycle() }
        services.submit({
            persistence.finalize(prepared, if (interrupted) CaptureOutcome.INTERRUPTED else CaptureOutcome.COMPLETED,
                width = mirrored.width, height = mirrored.height)
        }) { result ->
            finishLease(); capturePending = false
            val persisted = result.getOrNull()
            if (persisted == null || !persisted.mediaFinalized || !current(attempt)) {
                mirrored.recycle()
                if (current(attempt)) onFailure(ReadyOsuSelfieCameraFailure.CAPTURE_FAILED)
            } else onCaptured(mirrored, persisted)
        }
    }

    override fun close() {
        if (!closed.compareAndSet(false, true)) return
        interrupted = capturePending
        generation.incrementAndGet()
        ready = false
        backend.close()
        finishLease()
    }

    private fun finishLease() { lease?.close(); lease = null }
    private fun current(attempt: Long) = !closed.get() && generation.get() == attempt
}

enum class ReadyOsuSelfieCameraFailure { FRONT_CAMERA_UNAVAILABLE, CAPTURE_FAILED }
