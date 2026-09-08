package dk.lasse.karatecliprecorder.learning

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Matrix
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageCapture
import androidx.camera.core.ImageCaptureException
import androidx.camera.core.ImageProxy
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.core.content.ContextCompat
import androidx.lifecycle.LifecycleOwner
import java.io.IOException
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong

/** Front-camera runner for the hands-free Ready? — Osu selfie attempt. */
class ReadyOsuSelfieCamera(
    context: Context,
    private val lifecycleOwner: LifecycleOwner,
    private val previewView: PreviewView,
    private val onReady: () -> Unit,
    private val onCaptured: (Bitmap) -> Unit,
    private val onFailure: (ReadyOsuSelfieCameraFailure) -> Unit,
) : AutoCloseable {
    private val appContext = context.applicationContext
    private val mainExecutor = ContextCompat.getMainExecutor(context)
    private val captureExecutor: ExecutorService = Executors.newSingleThreadExecutor()
    private val generation = AtomicLong(0L)
    private val closed = AtomicBoolean(false)
    private var cameraProvider: ProcessCameraProvider? = null
    private var preview: Preview? = null
    private var imageCapture: ImageCapture? = null

    fun start() {
        if (closed.get()) return
        val requestGeneration = generation.incrementAndGet()
        val providerFuture = ProcessCameraProvider.getInstance(appContext)
        providerFuture.addListener({
            if (!isCurrent(requestGeneration)) return@addListener
            try {
                val provider = providerFuture.get()
                if (!provider.hasCamera(CameraSelector.DEFAULT_FRONT_CAMERA)) {
                    onFailure(ReadyOsuSelfieCameraFailure.FRONT_CAMERA_UNAVAILABLE)
                    return@addListener
                }
                val nextPreview = Preview.Builder().build().also {
                    it.setSurfaceProvider(previewView.surfaceProvider)
                }
                val nextCapture = ImageCapture.Builder()
                    .setCaptureMode(ImageCapture.CAPTURE_MODE_MINIMIZE_LATENCY)
                    .build()
                    .also { capture ->
                        previewView.display?.rotation?.let { capture.targetRotation = it }
                    }

                provider.unbindAll()
                provider.bindToLifecycle(
                    lifecycleOwner,
                    CameraSelector.DEFAULT_FRONT_CAMERA,
                    nextPreview,
                    nextCapture,
                )
                if (!isCurrent(requestGeneration)) {
                    provider.unbind(nextPreview, nextCapture)
                    return@addListener
                }
                cameraProvider = provider
                preview = nextPreview
                imageCapture = nextCapture
                onReady()
            } catch (_: Throwable) {
                if (isCurrent(requestGeneration)) onFailure(ReadyOsuSelfieCameraFailure.FRONT_CAMERA_UNAVAILABLE)
            }
        }, mainExecutor)
    }

    fun capture() {
        val capture = imageCapture
        if (capture == null || closed.get()) {
            onFailure(ReadyOsuSelfieCameraFailure.CAPTURE_FAILED)
            return
        }
        val requestGeneration = generation.get()
        capture.takePicture(captureExecutor, object : ImageCapture.OnImageCapturedCallback() {
            override fun onCaptureSuccess(image: ImageProxy) {
                val bitmap = try {
                    image.toMirroredUprightBitmap()
                        ?: throw IOException("The captured selfie could not be decoded.")
                } catch (_: Throwable) {
                    null
                } finally {
                    image.close()
                }
                mainExecutor.execute {
                    if (!isCurrent(requestGeneration)) {
                        bitmap?.recycle()
                    } else if (bitmap == null) {
                        onFailure(ReadyOsuSelfieCameraFailure.CAPTURE_FAILED)
                    } else {
                        onCaptured(bitmap)
                    }
                }
            }

            override fun onError(exception: ImageCaptureException) {
                mainExecutor.execute {
                    if (isCurrent(requestGeneration)) onFailure(ReadyOsuSelfieCameraFailure.CAPTURE_FAILED)
                }
            }
        })
    }

    override fun close() {
        if (!closed.compareAndSet(false, true)) return
        generation.incrementAndGet()
        val provider = cameraProvider
        val boundPreview = preview
        val boundCapture = imageCapture
        if (provider != null && boundPreview != null && boundCapture != null) {
            runCatching { provider.unbind(boundPreview, boundCapture) }
        }
        preview = null
        imageCapture = null
        cameraProvider = null
        captureExecutor.shutdownNow()
    }

    private fun isCurrent(requestGeneration: Long) =
        !closed.get() && generation.get() == requestGeneration

    private fun ImageProxy.toMirroredUprightBitmap(): Bitmap? {
        val sourceBuffer = planes.firstOrNull()?.buffer ?: return null
        val buffer = sourceBuffer.duplicate().apply { rewind() }
        val bytes = ByteArray(buffer.remaining())
        buffer.get(bytes)
        val decoded = BitmapFactory.decodeByteArray(bytes, 0, bytes.size) ?: return null
        val upright = if (imageInfo.rotationDegrees == 0) {
            decoded
        } else {
            Bitmap.createBitmap(
                decoded,
                0,
                0,
                decoded.width,
                decoded.height,
                Matrix().apply { postRotate(imageInfo.rotationDegrees.toFloat()) },
                true,
            ).also { if (it !== decoded) decoded.recycle() }
        }
        return Bitmap.createBitmap(
            upright,
            0,
            0,
            upright.width,
            upright.height,
            Matrix().apply { postScale(-1f, 1f) },
            true,
        ).also { if (it !== upright) upright.recycle() }
    }
}

enum class ReadyOsuSelfieCameraFailure {
    FRONT_CAMERA_UNAVAILABLE,
    CAPTURE_FAILED,
}
