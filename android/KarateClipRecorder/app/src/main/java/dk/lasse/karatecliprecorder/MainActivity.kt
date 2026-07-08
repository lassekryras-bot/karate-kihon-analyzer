package dk.lasse.karatecliprecorder

import android.Manifest
import android.content.pm.PackageManager
import android.graphics.Color
import android.os.Bundle
import android.view.Gravity
import android.view.ViewGroup
import android.widget.Button
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.camera.view.PreviewView
import androidx.core.content.ContextCompat

class MainActivity : AppCompatActivity() {
    private lateinit var previewView: PreviewView
    private lateinit var recordButton: Button
    private lateinit var statusText: TextView
    private lateinit var savedClipText: TextView
    private var recordingAdapter: CameraXRecordingAdapter? = null

    private val cameraPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission(),
    ) { granted ->
        if (granted) {
            startCamera()
        } else {
            updateState(RecordingState.FAILED)
            savedClipText.text = "Camera permission is required to record clips."
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        buildUi()
        requestCameraPermissionIfNeeded()
    }

    private fun buildUi() {
        previewView = PreviewView(this).apply {
            layoutParams = FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT,
            )
            scaleType = PreviewView.ScaleType.FILL_CENTER
        }

        statusText = TextView(this).apply {
            text = "Status: waiting for camera permission"
            setTextColor(Color.WHITE)
            textSize = 16f
        }
        savedClipText = TextView(this).apply {
            text = "Last saved clip: none"
            setTextColor(Color.WHITE)
            textSize = 14f
        }
        recordButton = Button(this).apply {
            text = "Record 4 seconds"
            isEnabled = false
            setOnClickListener { recordFourSecondClip() }
        }

        val controls = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(32, 32, 32, 48)
            setBackgroundColor(0x66000000)
            addView(recordButton)
            addView(statusText)
            addView(savedClipText)
        }

        val root = FrameLayout(this).apply {
            addView(previewView)
            addView(
                controls,
                FrameLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT,
                    Gravity.BOTTOM,
                ),
            )
        }
        setContentView(root)
    }

    private fun requestCameraPermissionIfNeeded() {
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED) {
            startCamera()
        } else {
            cameraPermissionLauncher.launch(Manifest.permission.CAMERA)
        }
    }

    private fun startCamera() {
        recordingAdapter = CameraXRecordingAdapter(
            context = this,
            lifecycleOwner = this,
            previewView = previewView,
            onStateChanged = ::updateState,
            onSaved = ::showSavedClip,
            onError = { message -> savedClipText.text = message },
        ).also { it.bindCameraPreview() }
    }

    private fun recordFourSecondClip() {
        recordButton.isEnabled = false
        recordingAdapter?.startRecording()
        recordButton.postDelayed({
            recordingAdapter?.stopRecording()
        }, FOUR_SECOND_RECORDING_MS)
    }

    private fun updateState(state: RecordingState) {
        statusText.text = "Status: ${state.name.lowercase()}"
        recordButton.isEnabled = state == RecordingState.IDLE || state == RecordingState.SAVED || state == RecordingState.FAILED
    }

    private fun showSavedClip(result: RecordingResult) {
        savedClipText.text = "Last saved clip: ${result.fileName}\nPath: ${result.absolutePath}\nURI: ${result.uri}"
    }

    companion object {
        private const val FOUR_SECOND_RECORDING_MS = 4_000L
    }
}
