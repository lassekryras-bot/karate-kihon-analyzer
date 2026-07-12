package dk.lasse.karatecliprecorder

import android.Manifest
import android.content.pm.PackageManager
import android.graphics.Color
import android.os.Bundle
import android.os.Looper
import android.view.Gravity
import android.view.View
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
import dk.lasse.karatecliprecorder.captureprofile.CaptureFpsRange
import dk.lasse.karatecliprecorder.captureprofile.SelectedCaptureProfile
import dk.lasse.karatecliprecorder.learning.FindYourWeaponAnalysisCoordinator
import dk.lasse.karatecliprecorder.learning.FindYourWeaponAnalysisState
import dk.lasse.karatecliprecorder.learning.FindYourWeaponSessionController
import dk.lasse.karatecliprecorder.learning.FindYourWeaponState
import dk.lasse.karatecliprecorder.learning.FindYourWeaponStep
import dk.lasse.karatecliprecorder.learning.HandGuideOverlayView
import dk.lasse.karatecliprecorder.mediapipehandadapter.FramePermit
import dk.lasse.karatecliprecorder.mediapipehandadapter.LiveGestureRecognizerRunner
import dk.lasse.karatecliprecorder.mediapipehandadapter.RecognizerLifecycleState
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicLong

class MainActivity : AppCompatActivity() {
    private lateinit var previewView: PreviewView
    private lateinit var startSessionButton: Button
    private lateinit var findYourWeaponButton: Button
    private lateinit var cancelSessionButton: Button
    private lateinit var findYourWeaponBackButton: Button
    private lateinit var findYourWeaponNextButton: Button
    private lateinit var handGuideOverlayView: HandGuideOverlayView
    private lateinit var findYourWeaponAssetText: TextView
    private lateinit var statusText: TextView
    private lateinit var currentCountText: TextView
    private lateinit var currentStrikeText: TextView
    private lateinit var expectedSideText: TextView
    private lateinit var recordingStateText: TextView
    private lateinit var savedClipText: TextView
    private lateinit var metadataPathText: TextView
    private lateinit var captureProfileText: TextView
    private lateinit var analyzerDebugText: TextView
    private var recordingAdapter: CameraXRecordingAdapter? = null
    private var sessionController: GuidedJodanSessionController? = null
    private var findYourWeaponController: FindYourWeaponSessionController? = null
    private var guidedSessionActive = false
    private var findYourWeaponActive = false
    private var latestGuidedState = GuidedSessionState.IDLE
    private var trainingOrderPlayer: TrainingOrderPlayer? = null
    private var recognizerRunner: LiveGestureRecognizerRunner? = null
    private var analysisCoordinator: FindYourWeaponAnalysisCoordinator? = null
    private val recognizerExecutor: ExecutorService = Executors.newSingleThreadExecutor()
    private var recognizerState: RecognizerLifecycleState = RecognizerLifecycleState.INACTIVE
    private val submittedFrameCount = AtomicLong(0)
    private val processedFrameCount = AtomicLong(0)
    private val droppedFrameCount = AtomicLong(0)

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

        handGuideOverlayView = HandGuideOverlayView(this).apply {
            layoutParams = FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT,
            )
            visibility = View.GONE
        }

        findYourWeaponAssetText = sessionText("Tutorial image placeholder: none", 14f).apply {
            visibility = View.GONE
        }

        statusText = sessionText("Status: waiting for camera permission", 16f)
        currentCountText = sessionText("Count: none", 20f)
        currentStrikeText = sessionText("Strike: none", 14f)
        expectedSideText = sessionText("Expected side: none", 14f)
        recordingStateText = sessionText("Recording: idle", 14f)
        savedClipText = sessionText("Saved clips: 0 / 10", 14f)
        metadataPathText = sessionText("Metadata: not saved", 14f)
        captureProfileText = sessionText("Capture profile: detecting", 14f)
        analyzerDebugText = sessionText("Analyzer: inactive", 14f)

        startSessionButton = Button(this).apply {
            text = "Start Jodan Session"
            isEnabled = false
            setOnClickListener { startGuidedSession() }
        }
        findYourWeaponButton = Button(this).apply {
            text = "Find Your Weapon"
            isEnabled = false
            setOnClickListener { startFindYourWeaponSession() }
        }
        cancelSessionButton = Button(this).apply {
            text = "Cancel Session"
            isEnabled = false
            setOnClickListener {
                if (findYourWeaponActive) {
                    findYourWeaponController?.cancel()
                } else {
                    sessionController?.cancel()
                }
            }
        }
        findYourWeaponBackButton = Button(this).apply {
            text = "Back"
            visibility = View.GONE
            setOnClickListener { findYourWeaponController?.back() }
        }
        findYourWeaponNextButton = Button(this).apply {
            text = "Next"
            visibility = View.GONE
            setOnClickListener { findYourWeaponController?.next() }
        }

        val controls = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(32, 32, 32, 48)
            setBackgroundColor(0x66000000)
            addView(startSessionButton)
            addView(findYourWeaponButton)
            addView(cancelSessionButton)
            addView(findYourWeaponBackButton)
            addView(findYourWeaponNextButton)
            addView(statusText)
            addView(currentCountText)
            addView(currentStrikeText)
            addView(findYourWeaponAssetText)
            addView(expectedSideText)
            addView(recordingStateText)
            addView(savedClipText)
            addView(captureProfileText)
            addView(analyzerDebugText)
            addView(metadataPathText)
        }

        val root = FrameLayout(this).apply {
            addView(previewView)
            addView(handGuideOverlayView)
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
            onAnalysisError = ::handleAnalysisError,
            onCaptureProfileSelected = ::handleCaptureProfileSelected,
            onAnalysisFramePermit = { timestampMs ->
                val coordinator = analysisCoordinator
                val runner = recognizerRunner
                if (coordinator == null || runner == null || recognizerState != RecognizerLifecycleState.READY) {
                    droppedFrameCount.incrementAndGet()
                    null
                } else {
                    runner.tryAcquireFrame(timestampMs, coordinator.currentGenerationToken()).also { permit ->
                        if (permit == null) droppedFrameCount.incrementAndGet()
                    }
                }
            },
            onAnalysisPermitRelease = { permit -> (permit as? FramePermit)?.let { recognizerRunner?.releasePermit(it) } },
            onAnalysisFrame = { bitmap, _, permit ->
                val framePermit = permit as? FramePermit
                if (framePermit == null) {
                    false
                } else {
                    val accepted = recognizerRunner?.submit(bitmap, framePermit) ?: false
                    if (accepted) submittedFrameCount.incrementAndGet()
                    accepted
                }
            },
        )
        recordingAdapter = adapter
        analysisCoordinator = FindYourWeaponAnalysisCoordinator { state -> runOnUiThread { updateAnalysisState(state) } }
        findYourWeaponController = FindYourWeaponSessionController(
            onStateChanged = ::updateFindYourWeaponState,
        )
        sessionController = GuidedJodanSessionController(
            recordingAdapter = adapter,
            onStateChanged = ::updateGuidedState,
            onPromptChanged = { prompt -> currentCountText.text = "Count: $prompt" },
            onStrikeChanged = ::showCurrentStrike,
            onSavedClipCountChanged = { savedCount -> savedClipText.text = "Saved clips: $savedCount / 10" },
            onComplete = ::showSessionComplete,
            onError = { message -> metadataPathText.text = "Error: $message" },
            captureProfile = adapter.selectedCaptureProfile,
        )
        adapter.bindCameraPreview()
    }

    private fun handleCaptureProfileSelected(profile: SelectedCaptureProfile) {
        captureProfileText.text = "Capture profile: ${profile.selectedQualityTier.name} / ${profile.targetHeight?.let { "${it}p" } ?: "unknown resolution"} / preferred ${profile.preferredTargetFps} fps\nSupported FPS: ${profile.supportedFpsRanges.toDisplayText()}"
        sessionController?.captureProfile = profile
    }

    private fun List<CaptureFpsRange>.toDisplayText(): String =
        if (isEmpty()) "unknown" else joinToString { "${it.minFps}-${it.maxFps}" }

    private fun startGuidedSession() {
        metadataPathText.text = "Metadata: not saved"
        sessionController?.start()
    }

    private fun startFindYourWeaponSession() {
        metadataPathText.text = "Metadata: not saved"
        analysisCoordinator?.reset()
        recognizerState = RecognizerLifecycleState.INITIALIZING
        updateAnalysisState(
            FindYourWeaponAnalysisState(
                activeStep = null,
                timestampMs = null,
                handDetected = false,
                handedness = dk.lasse.karateanalyzer.core.Handedness.UNKNOWN,
                instantResult = null,
                temporalResult = null,
                openPalmGestureScore = null,
                closedFistGestureScore = null,
                inferenceLatencyMs = null,
                recognizerState = recognizerState,
            ),
        )
        recognizerExecutor.execute {
            val runner = createRecognizerRunner()
            runOnMainThread {
                recognizerRunner?.close()
                recognizerRunner = runner
                recognizerState = runner.lifecycleState
                if (runner.initializationSucceeded()) {
                    findYourWeaponController?.start()
                } else {
                    recordingAdapter?.setAnalysisEnabled(false)
                }
            }
        }
    }

    override fun onDestroy() {
        recognizerRunner?.close()
        recognizerRunner = null
        recognizerState = RecognizerLifecycleState.CLOSED
        recognizerExecutor.shutdownNow()
        recordingAdapter?.close()
        recordingAdapter = null
        trainingOrderPlayer?.release()
        trainingOrderPlayer = null
        super.onDestroy()
    }

    private fun updateRecordingState(state: RecordingState) {
        recordingStateText.text = "Recording: ${state.name.lowercase()}"
        val cameraReady = state == RecordingState.IDLE || state == RecordingState.SAVED || state == RecordingState.FAILED
        startSessionButton.isEnabled = cameraReady && !guidedSessionActive && !findYourWeaponActive
        findYourWeaponButton.isEnabled = cameraReady && !guidedSessionActive && !findYourWeaponActive
    }

    private fun updateGuidedState(state: GuidedSessionState) {
        latestGuidedState = state
        guidedSessionActive = state in ACTIVE_GUIDED_STATES
        statusText.text = "Status: ${state.name.lowercase()}"
        startSessionButton.isEnabled = !guidedSessionActive && !findYourWeaponActive
        findYourWeaponButton.isEnabled = !guidedSessionActive && !findYourWeaponActive
        cancelSessionButton.isEnabled = guidedSessionActive || findYourWeaponActive
        TrainingOrderMapper.fromSessionState(state)?.let(::playTrainingOrder)
    }


    private fun updateFindYourWeaponState(state: FindYourWeaponState) {
        findYourWeaponActive = state.isActive
        val step = state.step
        handGuideOverlayView.visibility = if (step == FindYourWeaponStep.OPEN_PALM && state.isActive) View.VISIBLE else View.GONE
        findYourWeaponAssetText.visibility = if (state.isActive && step != null) View.VISIBLE else View.GONE
        findYourWeaponBackButton.visibility = if (state.isActive) View.VISIBLE else View.GONE
        findYourWeaponNextButton.visibility = if (state.isActive) View.VISIBLE else View.GONE
        analysisCoordinator?.setActiveStep(if (state.isActive) step else null)
        recordingAdapter?.setAnalysisEnabled(state.isActive && step != null)
        if (!state.isActive) { analysisCoordinator?.reset() }
        if (state.isActive && step != null) {
            val content = step.content()
            statusText.text = content.title
            currentCountText.text = content.instruction
            currentStrikeText.text = content.detail
            expectedSideText.text = "Step: ${content.stepNumber} / ${FindYourWeaponStep.entries.size}"
            findYourWeaponAssetText.text = "Tutorial image placeholder: ${content.placeholderFileName}"
            findYourWeaponBackButton.isEnabled = step != FindYourWeaponStep.OPEN_PALM
            findYourWeaponNextButton.text = if (step == FindYourWeaponStep.FRONT_TWO_KNUCKLES) "Finish" else "Next"
        } else {
            statusText.text = if (state.isComplete) "Find Your Weapon complete" else "Status: idle"
            currentCountText.text = "Count: none"
            currentStrikeText.text = "Strike: none"
            expectedSideText.text = "Expected side: none"
            findYourWeaponAssetText.text = "Tutorial image placeholder: none"
            findYourWeaponNextButton.text = "Next"
        }
        startSessionButton.isEnabled = !guidedSessionActive && !findYourWeaponActive
        findYourWeaponButton.isEnabled = !guidedSessionActive && !findYourWeaponActive
        cancelSessionButton.isEnabled = guidedSessionActive || findYourWeaponActive
    }

    private fun createRecognizerRunner(): LiveGestureRecognizerRunner = LiveGestureRecognizerRunner(
        context = this,
        onResult = { output ->
            processedFrameCount.incrementAndGet()
            analysisCoordinator?.process(output)
        },
        onError = { message -> analysisCoordinator?.reportError(message, RecognizerLifecycleState.FAILED) },
    )

    private fun updateAnalysisState(state: FindYourWeaponAnalysisState) {
        analyzerDebugText.text = if (state.errorMessage != null) {
            "Analyzer error: ${state.errorMessage}"
        } else if (state.activeStep == null) {
            "Analyzer: inactive"
        } else {
            listOf(
                "Analyzer: ${if (state.handDetected) "hand detected" else "no hand"}",
                "Hand: ${state.handedness.name.lowercase()}",
                "Instant: ${state.instantResult?.status?.name?.lowercase() ?: "none"} / ${state.instantResult?.feedbackCode?.name ?: "NONE"}",
                "Score: ${state.instantResult?.score?.format2() ?: "--"}",
                "Quality: ${state.instantResult?.quality?.format2() ?: "--"}",
                "Temporal: ${state.temporalResult?.status?.name?.lowercase() ?: "none"}",
                "Progress: ${((state.temporalResult?.progress ?: 0f) * 100f).toInt()}%",
                "Latency: ${state.inferenceLatencyMs?.let { "$it ms" } ?: "--"}",
                "Step: ${state.activeStep?.name ?: "none"}",
                "Accepted: ${state.temporalResult?.accepted ?: false}",
                "Newly accepted: ${state.temporalResult?.newlyAccepted ?: false}",
                "Reliable hold: ${state.temporalResult?.reliableHoldCreditMs?.toInt() ?: 0} ms",
                "Reliable ratio: ${state.temporalResult?.weightedReliableRatio?.format2() ?: "--"}",
                "OpenPalm: ${state.openPalmGestureScore?.format2() ?: "--"}",
                "ClosedFist: ${state.closedFistGestureScore?.format2() ?: "--"}",
                "Timestamp: ${state.timestampMs ?: "--"}",
                "Frames: submitted=${submittedFrameCount.get()} processed=${processedFrameCount.get()} dropped=${droppedFrameCount.get()}",
                "Recognizer: ${state.recognizerState.name.lowercase()}",
            ).joinToString("\n")
        }
    }

    private fun Float.format2(): String = String.format(java.util.Locale.US, "%.2f", this)

    private fun Double.format2(): String = String.format(java.util.Locale.US, "%.2f", this)

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


    private fun handleAnalysisError(message: String) {
        analysisCoordinator?.reportError(message, recognizerState)
    }

    private fun handleRecordingError(message: String) {
        runOnMainThread {
            sessionController?.handleRecordingError(message)
            metadataPathText.text = "Error: $message"
        }
    }

    private fun runOnMainThread(action: () -> Unit) {
        if (Looper.myLooper() == Looper.getMainLooper()) {
            action()
        } else {
            runOnUiThread(action)
        }
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


    private fun FindYourWeaponStep.content(): FindYourWeaponStepContent = when (this) {
        FindYourWeaponStep.OPEN_PALM -> FindYourWeaponStepContent(
            stepNumber = 1,
            title = "Find Your Weapon",
            instruction = "Place your open palm inside the blue hand guide.",
            detail = "Keep your fingers open and face your palm toward the camera.",
            placeholderFileName = "find_weapon_01_open_palm.txt",
        )
        FindYourWeaponStep.BEND_FINGERTIPS -> FindYourWeaponStepContent(
            stepNumber = 2,
            title = "Find Your Weapon",
            instruction = "Bend the top parts of your fingers.",
            detail = "Start by folding the fingertips.",
            placeholderFileName = "find_weapon_02_bend_fingertips.txt",
        )
        FindYourWeaponStep.CLOSE_FINGERS -> FindYourWeaponStepContent(
            stepNumber = 3,
            title = "Find Your Weapon",
            instruction = "Close your fingers into your palm.",
            detail = "Make the fist shape.",
            placeholderFileName = "find_weapon_03_close_fingers.txt",
        )
        FindYourWeaponStep.THUMB_ON_TOP -> FindYourWeaponStepContent(
            stepNumber = 4,
            title = "Find Your Weapon",
            instruction = "Place your thumb across the front of your fingers.",
            detail = "Keep the fist firm but relaxed.",
            placeholderFileName = "find_weapon_04_thumb_on_top.txt",
        )
        FindYourWeaponStep.FRONT_TWO_KNUCKLES -> FindYourWeaponStepContent(
            stepNumber = 5,
            title = "Find Your Weapon",
            instruction = "These two front knuckles are your weapon.",
            detail = "Aim with the index and middle knuckles.",
            placeholderFileName = "find_weapon_05_front_two_knuckles.txt",
        )
    }

    private data class FindYourWeaponStepContent(
        val stepNumber: Int,
        val title: String,
        val instruction: String,
        val detail: String,
        val placeholderFileName: String,
    )

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
