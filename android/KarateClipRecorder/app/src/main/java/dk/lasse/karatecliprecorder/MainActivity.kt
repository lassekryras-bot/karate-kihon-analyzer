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
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import dk.lasse.karatecliprecorder.orders.SoundFileTrainingOrderPlayer
import dk.lasse.karatecliprecorder.orders.TrainingOrder
import dk.lasse.karatecliprecorder.orders.TrainingOrderMapper
import dk.lasse.karatecliprecorder.orders.TrainingOrderPlayer

class MainActivity : AppCompatActivity() {
    private lateinit var previewView: PreviewView
    private lateinit var startSessionButton: Button
    private lateinit var cancelSessionButton: Button
    private lateinit var statusText: TextView
    private lateinit var currentCountText: TextView
    private lateinit var currentStrikeText: TextView
    private lateinit var expectedSideText: TextView
    private lateinit var recordingStateText: TextView
    private lateinit var savedClipText: TextView
    private lateinit var metadataPathText: TextView
    private var recordingAdapter: CameraXRecordingAdapter? = null
    private var sessionController: GuidedJodanSessionController? = null
    private var guidedSessionActive = false
    private var latestGuidedState = GuidedSessionState.IDLE
    private var trainingOrderPlayer: TrainingOrderPlayer? = null

    private val cameraPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission(),
    ) { granted ->
        if (granted) {
            startCamera()
        } else {
            updateRecordingState(RecordingState.FAILED)
            savedClipText.text = "Camera permission is required to record clips."
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        buildUi()
        trainingOrderPlayer = SoundFileTrainingOrderPlayer(this)
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

        statusText = sessionText("Status: waiting for camera permission", 16f)
        currentCountText = sessionText("Count: none", 20f)
        currentStrikeText = sessionText("Strike: none", 14f)
        expectedSideText = sessionText("Expected side: none", 14f)
        recordingStateText = sessionText("Recording: idle", 14f)
        savedClipText = sessionText("Saved clips: 0 / 10", 14f)
        metadataPathText = sessionText("Metadata: not saved", 14f)

        startSessionButton = Button(this).apply {
            text = "Start Jodan Session"
            isEnabled = false
            setOnClickListener { startGuidedSession() }
        }
        cancelSessionButton = Button(this).apply {
            text = "Cancel Session"
            isEnabled = false
            setOnClickListener { sessionController?.cancel() }
        }

        val controls = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(32, 32, 32, 48)
            setBackgroundColor(0x66000000)
            addView(startSessionButton)
            addView(cancelSessionButton)
            addView(statusText)
            addView(currentCountText)
            addView(currentStrikeText)
            addView(expectedSideText)
            addView(recordingStateText)
            addView(savedClipText)
            addView(metadataPathText)
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
        ViewCompat.setOnApplyWindowInsetsListener(root) { _, insets ->
            val systemBars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            val params = controls.layoutParams as FrameLayout.LayoutParams
            params.bottomMargin = systemBars.bottom + 16.dp()
            controls.layoutParams = params
            insets
        }
        setContentView(root)
        ViewCompat.requestApplyInsets(root)
    }

    private fun Int.dp(): Int = (this * resources.displayMetrics.density).toInt()

    private fun sessionText(initialText: String, size: Float): TextView = TextView(this).apply {
        text = initialText
        setTextColor(Color.WHITE)
        textSize = size
    }

    private fun requestCameraPermissionIfNeeded() {
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED) {
            startCamera()
        } else {
            cameraPermissionLauncher.launch(Manifest.permission.CAMERA)
        }
    }

    private fun startCamera() {
        val adapter = CameraXRecordingAdapter(
            context = this,
            lifecycleOwner = this,
            previewView = previewView,
            onStateChanged = ::updateRecordingState,
            onSaved = ::handleSavedClip,
            onError = ::handleRecordingError,
        )
        recordingAdapter = adapter
        sessionController = GuidedJodanSessionController(
            recordingAdapter = adapter,
            onStateChanged = ::updateGuidedState,
            onPromptChanged = { prompt -> currentCountText.text = "Count: $prompt" },
            onStrikeChanged = ::showCurrentStrike,
            onSavedClipCountChanged = { savedCount -> savedClipText.text = "Saved clips: $savedCount / 10" },
            onComplete = ::showSessionComplete,
            onError = { message -> metadataPathText.text = "Error: $message" },
        )
        adapter.bindCameraPreview()
    }

    private fun startGuidedSession() {
        metadataPathText.text = "Metadata: not saved"
        sessionController?.start()
    }

    override fun onDestroy() {
        trainingOrderPlayer?.release()
        trainingOrderPlayer = null
        super.onDestroy()
    }

    private fun updateRecordingState(state: RecordingState) {
        recordingStateText.text = "Recording: ${state.name.lowercase()}"
        val cameraReady = state == RecordingState.IDLE || state == RecordingState.SAVED || state == RecordingState.FAILED
        startSessionButton.isEnabled = cameraReady && !guidedSessionActive
    }

    private fun updateGuidedState(state: GuidedSessionState) {
        latestGuidedState = state
        guidedSessionActive = state in ACTIVE_GUIDED_STATES
        statusText.text = "Status: ${state.name.lowercase()}"
        startSessionButton.isEnabled = !guidedSessionActive
        cancelSessionButton.isEnabled = guidedSessionActive
        TrainingOrderMapper.fromSessionState(state)?.let(::playTrainingOrder)
    }

    private fun showCurrentStrike(plan: GuidedStrikePlan?) {
        if (plan == null) {
            currentStrikeText.text = "Strike: none"
            expectedSideText.text = "Expected side: none"
            return
        }
        TrainingOrderMapper.fromStrikePlan(plan)?.let(::playTrainingOrder)
        currentStrikeText.text = "Strike: ${plan.index} / 10 (${plan.fileName})"
        expectedSideText.text = "Expected side: ${plan.expectedSide.metadataValue}"
    }

    private fun handleSavedClip(result: RecordingResult) {
        if (latestGuidedState == GuidedSessionState.RECORDING || latestGuidedState == GuidedSessionState.SAVING) {
            sessionController?.handleRecordingSaved(result)
        } else {
            savedClipText.text = "Last saved clip: ${result.fileName}\nPath: ${result.absolutePath}\nURI: ${result.uri}"
        }
    }

    private fun handleRecordingError(message: String) {
        sessionController?.handleRecordingError(message)
        metadataPathText.text = "Error: $message"
    }

    private fun showSessionComplete(result: GuidedSessionResult) {
        savedClipText.text = "Saved clips: ${result.savedClipCount} / ${result.expectedClipCount}"
        metadataPathText.text = "Metadata: ${result.metadataPath}"
        playTrainingOrder(TrainingOrderMapper.fromSessionResult(result))
        if (result.completed) {
            currentCountText.text = "Count: Session complete"
        }
    }

    private fun playTrainingOrder(order: TrainingOrder) {
        trainingOrderPlayer?.play(order)
    }

    companion object {
        private val ACTIVE_GUIDED_STATES = setOf(
            GuidedSessionState.READY,
            GuidedSessionState.YOI,
            GuidedSessionState.PROMPTING_STRIKE,
            GuidedSessionState.RECORDING,
            GuidedSessionState.SAVING,
        )
    }
}
