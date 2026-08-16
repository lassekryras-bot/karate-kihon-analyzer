package dk.lasse.karatecliprecorder

import android.Manifest
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Color
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.util.Log
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.widget.Button
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.OnBackPressedCallback
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.app.AppCompatDelegate
import androidx.appcompat.widget.SwitchCompat
import androidx.camera.view.PreviewView
import androidx.core.content.ContextCompat
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import dk.lasse.karatecliprecorder.enso.EnsoDebugGalleryView
import dk.lasse.karatecliprecorder.enso.EnsoLibrary
import dk.lasse.karatecliprecorder.learningartwork.LearningActivityEntryView
import dk.lasse.karatecliprecorder.learningartwork.LearningActivityType
import dk.lasse.karatecliprecorder.learningpath.LearnScreenView
import dk.lasse.karatecliprecorder.learningpath.LearningDestination
import dk.lasse.karatecliprecorder.learningpath.LearningPath
import dk.lasse.karatecliprecorder.learningpath.LearningPathCatalog
import dk.lasse.karatecliprecorder.learningpath.LearningPathId
import dk.lasse.karatecliprecorder.learningpath.SkillProgressionView
import dk.lasse.karatecliprecorder.orders.SoundFileTrainingOrderPlayer
import dk.lasse.karatecliprecorder.orders.TrainingOrder
import dk.lasse.karatecliprecorder.orders.TrainingOrderMapper
import dk.lasse.karatecliprecorder.orders.TrainingOrderPlayer
import dk.lasse.karatecliprecorder.captureprofile.CaptureFpsRange
import dk.lasse.karatecliprecorder.captureprofile.SelectedCaptureProfile
import dk.lasse.karatecliprecorder.learning.FindYourWeaponAnalysisCoordinator
import dk.lasse.karatecliprecorder.learning.FindYourWeaponAnalysisState
import dk.lasse.karatecliprecorder.learning.FindYourWeaponAutoAdvanceController
import dk.lasse.karatecliprecorder.learning.FindYourWeaponAutoAdvanceDecision
import dk.lasse.karatecliprecorder.learning.FindYourWeaponCoachCopy
import dk.lasse.karatecliprecorder.learning.FindYourWeaponCoachTextGate
import dk.lasse.karatecliprecorder.learning.FindYourWeaponDebugOverlayView
import dk.lasse.karatecliprecorder.learning.FindYourWeaponProgressRingView
import dk.lasse.karatecliprecorder.learning.FindYourWeaponSessionController
import dk.lasse.karatecliprecorder.learning.FindYourWeaponState
import dk.lasse.karatecliprecorder.learning.FindYourWeaponStep
import dk.lasse.karatecliprecorder.learning.HandGuideOverlayView
import dk.lasse.karatecliprecorder.learning.CountRecognitionAlternative
import dk.lasse.karatecliprecorder.learning.CountRecognitionError
import dk.lasse.karatecliprecorder.learning.CountRecognitionFailure
import dk.lasse.karatecliprecorder.learning.CountStatus
import dk.lasse.karatecliprecorder.learning.CountTrainingPhase
import dk.lasse.karatecliprecorder.learning.CountTrainingSession
import dk.lasse.karatecliprecorder.learning.CountTranscriptNormalizer
import dk.lasse.karatecliprecorder.learning.CameraSetupCapture
import dk.lasse.karatecliprecorder.learning.CameraSetupCaptureStore
import dk.lasse.karatecliprecorder.learning.CameraSetupSessionCoordinator
import dk.lasse.karatecliprecorder.learning.CameraSetupStage
import dk.lasse.karatecliprecorder.learning.CameraSetupState
import dk.lasse.karatecliprecorder.learning.CameraView
import dk.lasse.karatecliprecorder.learning.JapaneseCountFullExamplePlayer
import dk.lasse.karatecliprecorder.learning.JapaneseCountLessonItem
import dk.lasse.karatecliprecorder.learning.JapaneseCountLesson
import dk.lasse.karatecliprecorder.learning.JapaneseCountListeningPolicy
import dk.lasse.karatecliprecorder.learning.JapaneseCountLiveRecognizer
import dk.lasse.karatecliprecorder.learning.JapaneseCountLevel1Controller
import dk.lasse.karatecliprecorder.learning.JapaneseCountLevel1State
import dk.lasse.karatecliprecorder.learning.PunchHeightCaptureStore
import dk.lasse.karatecliprecorder.learning.PunchHeightCompletedSession
import dk.lasse.karatecliprecorder.learning.PunchHeightOverlayView
import dk.lasse.karatecliprecorder.learning.PunchHeightSessionCoordinator
import dk.lasse.karatecliprecorder.learning.PunchHeightSessionStage
import dk.lasse.karatecliprecorder.learning.PunchHeightSessionState
import dk.lasse.karatecliprecorder.learning.PunchHeightVoiceCoach
import dk.lasse.karatecliprecorder.mediapipehandadapter.FramePermit
import dk.lasse.karatecliprecorder.mediapipehandadapter.LiveGestureRecognizerRunner
import dk.lasse.karatecliprecorder.mediapipehandadapter.RecognizerLifecycleState
import dk.lasse.karatecliprecorder.mediapipeposeadapter.LivePoseLandmarkerRunner
import dk.lasse.karatecliprecorder.mediapipeposeadapter.LivePoseLandmarkerOutput
import dk.lasse.karatecliprecorder.mediapipeposeadapter.PoseFramePermit
import dk.lasse.karatecliprecorder.mediapipeposeadapter.PoseRecognizerLifecycleState
import dk.lasse.karateanalyzer.core.PunchHeightAnalyzer
import dk.lasse.karateanalyzer.core.PunchHeightTargetType
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicLong

class MainActivity : AppCompatActivity() {
    private lateinit var appRoot: FrameLayout
    private lateinit var trainingRoot: View
    private lateinit var homeScreen: HomeScreenView
    private lateinit var learnScreen: LearnScreenView
    private lateinit var settingsScreen: SettingsScreenView
    private var skillProgressionScreen: SkillProgressionView? = null
    private val learningPaths by lazy(LearningPathCatalog::create)
    private lateinit var appPreferences: AppPreferences
    private var ensoDebugGallery: View? = null
    private var cameraStartupRequested = false
    private var openCameraSetupAfterPermission = false
    private var currentAppDestination = AppDestination.HOME
    private lateinit var previewView: PreviewView
    private lateinit var startSessionButton: Button
    private lateinit var findYourWeaponButton: Button
    private lateinit var punchHeightButton: Button
    private lateinit var cameraSetupViewButtons: LinearLayout
    private lateinit var cameraSetupTitleText: TextView
    private lateinit var cameraSetupMessageText: TextView
    private lateinit var cameraSetupProgress: ProgressBar
    private lateinit var cameraSetupCapturedImage: ImageView
    private lateinit var cameraSetupRetakeButton: Button
    private lateinit var cameraSetupDoneButton: Button
    private lateinit var countJapanesePracticeEntry: LearningActivityEntryView
    private lateinit var countJapaneseTestEntry: LearningActivityEntryView
    private lateinit var cancelSessionButton: Button
    private lateinit var findYourWeaponBackButton: Button
    private lateinit var findYourWeaponNextButton: Button
    private lateinit var japaneseCountBackButton: Button
    private lateinit var japaneseCountPlayButton: Button
    private lateinit var japaneseCountReplayButton: Button
    private lateinit var japaneseCountNextButton: Button
    private lateinit var japaneseCountListenButton: Button
    private lateinit var japaneseCountFinishButton: Button
    private lateinit var japaneseCountRetryButton: Button
    private lateinit var debugSwitch: SwitchCompat
    private lateinit var handGuideOverlayView: HandGuideOverlayView
    private lateinit var findYourWeaponImage: ImageView
    private lateinit var findYourWeaponDebugOverlayView: FindYourWeaponDebugOverlayView
    private lateinit var findYourWeaponProgressRingView: FindYourWeaponProgressRingView
    private lateinit var findYourWeaponMessageText: TextView
    private lateinit var japaneseCountText: TextView
    private lateinit var japaneseCountFeedbackText: TextView
    private lateinit var japaneseCountResultsText: TextView
    private lateinit var debugScopeText: TextView
    private lateinit var statusText: TextView
    private lateinit var currentCountText: TextView
    private lateinit var currentStrikeText: TextView
    private lateinit var expectedSideText: TextView
    private lateinit var recordingStateText: TextView
    private lateinit var savedClipText: TextView
    private lateinit var metadataPathText: TextView
    private lateinit var captureProfileText: TextView
    private lateinit var analyzerDebugText: TextView
    private lateinit var punchHeightOverlayView: PunchHeightOverlayView
    private lateinit var punchHeightTitleText: TextView
    private lateinit var punchHeightMessageText: TextView
    private lateinit var punchHeightProgress: ProgressBar
    private lateinit var punchHeightMultiplierText: TextView
    private lateinit var punchHeightMultiplierMinusButton: Button
    private lateinit var punchHeightMultiplierResetButton: Button
    private lateinit var punchHeightMultiplierPlusButton: Button
    private lateinit var punchHeightMultiplierControls: LinearLayout
    private lateinit var punchHeightReviewLargeImage: ImageView
    private lateinit var punchHeightReviewThumbnails: LinearLayout
    private lateinit var punchHeightReviewExplanation: TextView
    private lateinit var punchHeightPracticeAgainButton: Button
    private lateinit var punchHeightCloseButton: Button
    private var recordingAdapter: CameraXRecordingAdapter? = null
    private var sessionController: GuidedJodanSessionController? = null
    private var findYourWeaponController: FindYourWeaponSessionController? = null
    private lateinit var japaneseCountLevel1Controller: JapaneseCountLevel1Controller
    private lateinit var japaneseCountFullExamplePlayer: JapaneseCountFullExamplePlayer
    private lateinit var japaneseCountLiveRecognizer: JapaneseCountLiveRecognizer
    private var guidedSessionActive = false
    private var findYourWeaponActive = false
    private var japaneseCountActive = false
    private var punchHeightActive = false
    private var cameraSetupActive = false
    private var debugUiVisible = false
    private var findYourWeaponKnuckleGuideVisible = false
    private var findYourWeaponFinishReady = false
    private var renderedFindYourWeaponStep: FindYourWeaponStep? = null
    private var latestFindYourWeaponAnalysisState: FindYourWeaponAnalysisState? = null
    private var latestGuidedState = GuidedSessionState.IDLE
    private var latestRecordingState = RecordingState.PREPARING
    private var trainingOrderPlayer: TrainingOrderPlayer? = null
    private var recognizerRunner: LiveGestureRecognizerRunner? = null
    private var analysisCoordinator: FindYourWeaponAnalysisCoordinator? = null
    private var poseRecognizerRunner: LivePoseLandmarkerRunner? = null
    private val punchHeightCoordinator = PunchHeightSessionCoordinator()
    private lateinit var punchHeightCaptureStore: PunchHeightCaptureStore
    private var punchHeightVoiceCoach: PunchHeightVoiceCoach? = null
    private var latestPunchHeightState = PunchHeightSessionState()
    private var completedPunchHeightSession: PunchHeightCompletedSession? = null
    private val cameraSetupCoordinator = CameraSetupSessionCoordinator()
    private lateinit var cameraSetupCaptureStore: CameraSetupCaptureStore
    private var cameraSetupVoiceCoach: PunchHeightVoiceCoach? = null
    private var latestCameraSetupState = CameraSetupState()
    private var completedCameraSetupCapture: CameraSetupCapture? = null
    private var lastSpokenCameraSetupMessage: String? = null
    private var poseRecognizerState = PoseRecognizerLifecycleState.CLOSED
    private val recognizerExecutor: ExecutorService = Executors.newSingleThreadExecutor()
    private val punchHeightStorageExecutor: ExecutorService = Executors.newSingleThreadExecutor()
    private val mainHandler = Handler(Looper.getMainLooper())
    private val findYourWeaponAutoAdvanceController = FindYourWeaponAutoAdvanceController()
    private val findYourWeaponCoachTextGate = FindYourWeaponCoachTextGate()
    private var pendingFindYourWeaponAdvance: Runnable? = null
    private var pendingJapaneseCountRecognitionRestart: Runnable? = null
    private var japaneseCountCommittedTranscript = ""
    private var japaneseCountMode: LearningActivityType? = null
    private var pendingJapaneseCountMicrophonePermission = false
    private var japaneseCountTrainingSession = CountTrainingSession()
    private var recognizerState: RecognizerLifecycleState = RecognizerLifecycleState.INACTIVE
    private val submittedFrameCount = AtomicLong(0)
    private val processedFrameCount = AtomicLong(0)
    private val droppedFrameCount = AtomicLong(0)

    private val cameraPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission(),
    ) { granted ->
        if (granted) {
            startCameraIfNeeded()
            if (openCameraSetupAfterPermission) startCameraSetupSession()
        } else {
            cameraStartupRequested = false
            updateRecordingState(RecordingState.FAILED)
            savedClipText.text = "Camera permission is required to record clips."
        }
        openCameraSetupAfterPermission = false
        if (::settingsScreen.isInitialized) settingsScreen.refreshCameraPermissionState()
    }

    private val settingsCameraPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission(),
    ) { granted ->
        if (::settingsScreen.isInitialized) settingsScreen.refreshCameraPermissionState()
        Toast.makeText(
            this,
            if (granted) "Camera permission allowed." else "Camera permission was not allowed.",
            Toast.LENGTH_SHORT,
        ).show()
    }

    private val audioPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission(),
    ) { granted ->
        if (pendingJapaneseCountMicrophonePermission) {
            pendingJapaneseCountMicrophonePermission = false
            if (granted) {
                beginJapaneseCountLiveRecognition()
            } else {
                showJapaneseCountLevel2Error(CountRecognitionError.MICROPHONE_PERMISSION_DENIED)
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        appPreferences = AppPreferences(this)
        AppCompatDelegate.setDefaultNightMode(appPreferences.theme.nightMode)
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        debugUiVisible = appPreferences.developerMode
        japaneseCountLevel1Controller = JapaneseCountLevel1Controller(::updateJapaneseCountLevel1State)
        punchHeightCaptureStore = PunchHeightCaptureStore(this)
        cameraSetupCaptureStore = CameraSetupCaptureStore(this)
        buildUi()
        homeScreen = HomeScreenView(
            context = this,
            onContinue = { showSkillProgression(requireLearningPath(LearningPathId.JODAN_PUNCH)) },
            onLearn = ::showLearnUi,
            onPractice = ::openTrainingHub,
            onSkillCoach = ::openTrainingHub,
            onTrain = ::showLearnUi,
            onProgress = { showHomeDestinationPlaceholder("Progress") },
            onSettings = ::showSettingsUi,
        )
        learnScreen = LearnScreenView(
            context = this,
            paths = learningPaths,
            onPathSelected = ::showSkillProgression,
            onHome = ::showHomeUi,
            onProgress = { showHomeDestinationPlaceholder("Progress") },
            onSettings = ::showSettingsUi,
        ).apply {
            visibility = View.GONE
        }
        settingsScreen = SettingsScreenView(
            context = this,
            preferences = appPreferences,
            hasCameraPermission = ::hasCameraPermission,
            onHome = ::showHomeUi,
            onTrain = ::showLearnUi,
            onProgress = { showHomeDestinationPlaceholder("Progress") },
            onCameraSetup = ::openCameraSetupFromSettings,
            onCameraPermissionRequest = {
                settingsCameraPermissionLauncher.launch(Manifest.permission.CAMERA)
            },
            onTrainingData = { showHomeDestinationPlaceholder("Training data") },
            onClearTrainingHistory = ::confirmClearTrainingHistory,
            onDeveloperModeChanged = ::setDeveloperMode,
            onCameraDebug = ::openCameraDebug,
            onLearningUiDebug = ::openEnsoDebugGallery,
            onAbout = ::showAboutDialog,
            onHelp = ::showHelpDialog,
            onPrivacy = ::showPrivacyDialog,
            onThemeChanged = { theme -> AppCompatDelegate.setDefaultNightMode(theme.nightMode) },
        ).apply {
            visibility = View.GONE
        }
        appRoot = FrameLayout(this).apply {
            addView(trainingRoot)
            addView(homeScreen)
            addView(learnScreen)
            addView(settingsScreen)
        }
        setContentView(appRoot)
        trainingOrderPlayer = SoundFileTrainingOrderPlayer(this)
        japaneseCountFullExamplePlayer = JapaneseCountFullExamplePlayer(this)
        japaneseCountLiveRecognizer = JapaneseCountLiveRecognizer(this)
        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                if (skillProgressionScreen?.visibility == View.VISIBLE) {
                    showLearnUi()
                } else if (currentAppDestination == AppDestination.SETTINGS) {
                    showHomeUi()
                } else if (currentAppDestination == AppDestination.TRAIN && learnScreen.visibility == View.VISIBLE) {
                    showHomeUi()
                } else {
                    isEnabled = false
                    onBackPressedDispatcher.onBackPressed()
                }
            }
        })
        if (savedInstanceState?.getString(STATE_APP_DESTINATION) == AppDestination.SETTINGS.name) {
            showSettingsUi()
        } else if (savedInstanceState?.getString(STATE_APP_DESTINATION) == AppDestination.TRAIN.name) {
            showLearnUi()
        }
    }

    private fun showHomeDestinationPlaceholder(destination: String) {
        Toast.makeText(this, "$destination coming soon.", Toast.LENGTH_SHORT).show()
    }

    private fun showHomeUi() {
        currentAppDestination = AppDestination.HOME
        trainingRoot.visibility = View.GONE
        learnScreen.visibility = View.GONE
        skillProgressionScreen?.visibility = View.GONE
        settingsScreen.visibility = View.GONE
        homeScreen.visibility = View.VISIBLE
    }

    private fun showLearnUi() {
        currentAppDestination = AppDestination.TRAIN
        trainingRoot.visibility = View.GONE
        homeScreen.visibility = View.GONE
        settingsScreen.visibility = View.GONE
        skillProgressionScreen?.visibility = View.GONE
        learnScreen.visibility = View.VISIBLE
    }

    private fun showSkillProgression(path: LearningPath) {
        currentAppDestination = AppDestination.TRAIN
        trainingRoot.visibility = View.GONE
        homeScreen.visibility = View.GONE
        settingsScreen.visibility = View.GONE
        learnScreen.visibility = View.GONE
        skillProgressionScreen?.let(appRoot::removeView)
        skillProgressionScreen = SkillProgressionView(
            context = this,
            path = path,
            onBack = ::showLearnUi,
            onStart = ::openLearningActivity,
            onHome = ::showHomeUi,
            onTrain = ::showLearnUi,
            onProgress = { showHomeDestinationPlaceholder("Progress") },
            onSettings = ::showSettingsUi,
        ).also { progression ->
            appRoot.addView(
                progression,
                FrameLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.MATCH_PARENT,
                ),
            )
        }
    }

    private fun requireLearningPath(id: LearningPathId): LearningPath =
        learningPaths.first { it.id == id }

    private fun showSettingsUi() {
        currentAppDestination = AppDestination.SETTINGS
        trainingRoot.visibility = View.GONE
        learnScreen.visibility = View.GONE
        skillProgressionScreen?.visibility = View.GONE
        homeScreen.visibility = View.GONE
        settingsScreen.refresh()
        settingsScreen.visibility = View.VISIBLE
    }

    private fun openEnsoDebugGallery() {
        if ((applicationInfo.flags and ApplicationInfo.FLAG_DEBUGGABLE) == 0) {
            return
        }
        if (ensoDebugGallery != null) return

        ensoDebugGallery = EnsoDebugGalleryView(
            context = this,
            onClose = ::closeEnsoDebugGallery,
        ).also { gallery ->
            appRoot.addView(
                gallery,
                FrameLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.MATCH_PARENT,
                ),
            )
        }
    }

    private fun closeEnsoDebugGallery() {
        ensoDebugGallery?.let(appRoot::removeView)
        ensoDebugGallery = null
    }

    private fun openTrainingHub() {
        showTrainingUi()
        openCameraSetupAfterPermission = false
        requestCameraPermissionIfNeeded()
    }

    private fun openLearningActivity(destination: LearningDestination) {
        when (destination) {
            LearningDestination.JODAN_SESSION -> openTrainingHub()
            LearningDestination.JAPANESE_COUNTING_PRACTICE -> {
                showTrainingUi()
                startJapaneseCountingPractice()
            }
            LearningDestination.JAPANESE_COUNTING_TEST -> {
                showTrainingUi()
                startJapaneseCountingTest()
            }
        }
    }

    private fun showTrainingUi() {
        currentAppDestination = AppDestination.TRAIN
        homeScreen.visibility = View.GONE
        learnScreen.visibility = View.GONE
        skillProgressionScreen?.visibility = View.GONE
        settingsScreen.visibility = View.GONE
        trainingRoot.visibility = View.VISIBLE
    }

    private fun openCameraSetupFromSettings() {
        showTrainingUi()
        openCameraSetupAfterPermission = true
        if (hasCameraPermission()) {
            startCameraIfNeeded()
            openCameraSetupAfterPermission = false
            startCameraSetupSession()
        } else {
            cameraStartupRequested = true
            cameraPermissionLauncher.launch(Manifest.permission.CAMERA)
        }
    }

    private fun openCameraDebug() {
        setDeveloperMode(true)
        openTrainingHub()
    }

    private fun setDeveloperMode(enabled: Boolean) {
        appPreferences.developerMode = enabled
        debugUiVisible = enabled
        if (::debugSwitch.isInitialized && debugSwitch.isChecked != enabled) {
            debugSwitch.isChecked = enabled
        }
        if (::analyzerDebugText.isInitialized) {
            updateJapaneseCountDebugText()
            updateFindYourWeaponOverlay(latestFindYourWeaponAnalysisState)
            updatePunchHeightState(latestPunchHeightState, speak = false)
            updateControlVisibility()
        }
    }

    private fun confirmClearTrainingHistory() {
        AlertDialog.Builder(this)
            .setTitle("Clear training history?")
            .setMessage("This permanently removes all saved training sessions and results from this device. This cannot be undone.")
            .setNegativeButton("Cancel", null)
            .setPositiveButton("Clear") { _, _ ->
                punchHeightStorageExecutor.execute {
                    val result = TrainingHistoryStore(this).clear()
                    runOnMainThread {
                        val message = when {
                            !result.succeeded -> "Some training history could not be removed."
                            result.removedDirectoryCount == 0 -> "There was no saved training history to clear."
                            else -> "Training history cleared."
                        }
                        Toast.makeText(this, message, Toast.LENGTH_LONG).show()
                    }
                }
            }
            .show()
    }

    private fun showAboutDialog() {
        val version = runCatching {
            packageManager.getPackageInfo(packageName, 0).versionName
        }.getOrNull().orEmpty().ifBlank { "Unknown" }
        showInformationDialog(
            title = "Karate Kihon Analyzer",
            message = "Version $version\n\nCamera-based tools for learning and practicing karate kihon.",
        )
    }

    private fun showHelpDialog() = showInformationDialog(
        title = "Help & how it works",
        message = "Choose Learn for guided lessons, Practice for drills, or Skill Coach for camera-based technique feedback. Camera setup helps you find a reliable position before analysis.",
    )

    private fun showPrivacyDialog() = showInformationDialog(
        title = "Privacy",
        message = "Camera analysis runs within the app. Saved clips and training results are stored in this app's private device storage. You can remove saved training history from Settings.",
    )

    private fun showInformationDialog(title: String, message: String) {
        AlertDialog.Builder(this)
            .setTitle(title)
            .setMessage(message)
            .setPositiveButton("OK", null)
            .show()
    }

    override fun onResume() {
        super.onResume()
        if (::settingsScreen.isInitialized) settingsScreen.refreshCameraPermissionState()
    }

    override fun onSaveInstanceState(outState: Bundle) {
        outState.putString(STATE_APP_DESTINATION, currentAppDestination.name)
        super.onSaveInstanceState(outState)
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

        findYourWeaponImage = ImageView(this).apply {
            layoutParams = FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT,
            )
            alpha = 0.72f
            setPadding(64.dp(), 96.dp(), 64.dp(), 220.dp())
            scaleType = ImageView.ScaleType.FIT_CENTER
            visibility = View.GONE
        }

        punchHeightOverlayView = PunchHeightOverlayView(this).apply {
            layoutParams = FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT,
            )
            visibility = View.GONE
        }

        findYourWeaponDebugOverlayView = FindYourWeaponDebugOverlayView(this).apply {
            layoutParams = FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT,
            )
            visibility = View.GONE
        }

        findYourWeaponProgressRingView = FindYourWeaponProgressRingView(this).apply {
            layoutParams = LinearLayout.LayoutParams(58.dp(), 58.dp()).apply {
                gravity = Gravity.CENTER_HORIZONTAL
                bottomMargin = 10.dp()
            }
            visibility = View.GONE
        }

        findYourWeaponMessageText = sessionText("", 18f).apply {
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT,
            ).apply {
                gravity = Gravity.CENTER_HORIZONTAL
                bottomMargin = 10.dp()
            }
            gravity = Gravity.CENTER
            visibility = View.GONE
        }

        punchHeightTitleText = sessionText("", 24f).apply {
            gravity = Gravity.CENTER
            visibility = View.GONE
        }
        punchHeightMessageText = sessionText("", 18f).apply {
            gravity = Gravity.CENTER
            visibility = View.GONE
        }
        punchHeightProgress = ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal).apply {
            max = 1000
            progress = 0
            visibility = View.GONE
        }
        punchHeightMultiplierText = sessionText("Chin projection ×1.10", 14f).apply {
            gravity = Gravity.CENTER
            visibility = View.GONE
        }
        punchHeightReviewLargeImage = ImageView(this).apply {
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 260.dp())
            scaleType = ImageView.ScaleType.FIT_CENTER
            visibility = View.GONE
        }
        punchHeightReviewThumbnails = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER
            visibility = View.GONE
        }
        punchHeightReviewExplanation = sessionText("", 15f).apply {
            gravity = Gravity.CENTER
            visibility = View.GONE
        }

        cameraSetupTitleText = sessionText("Camera setup", 24f).apply {
            gravity = Gravity.CENTER
            visibility = View.GONE
        }
        cameraSetupMessageText = sessionText("", 18f).apply {
            gravity = Gravity.CENTER
            visibility = View.GONE
        }
        cameraSetupProgress = ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal).apply {
            max = 1000
            progress = 0
            visibility = View.GONE
        }
        cameraSetupCapturedImage = ImageView(this).apply {
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 280.dp())
            scaleType = ImageView.ScaleType.FIT_CENTER
            visibility = View.GONE
        }

        japaneseCountText = sessionText("", 32f).apply {
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT,
            ).apply {
                gravity = Gravity.CENTER_HORIZONTAL
                bottomMargin = 10.dp()
            }
            gravity = Gravity.CENTER
            visibility = View.GONE
        }

        japaneseCountFeedbackText = sessionText("", 16f).apply {
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT,
            ).apply {
                gravity = Gravity.CENTER_HORIZONTAL
                bottomMargin = 10.dp()
            }
            gravity = Gravity.CENTER
            visibility = View.GONE
        }

        japaneseCountResultsText = sessionText("", 14f).apply {
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT,
            ).apply {
                gravity = Gravity.CENTER_HORIZONTAL
                bottomMargin = 10.dp()
            }
            gravity = Gravity.CENTER
            visibility = View.GONE
        }

        debugScopeText = sessionText("Debug: camera", 14f)
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
        punchHeightButton = Button(this).apply {
            text = "Camera Setup"
            isEnabled = false
            setOnClickListener { startCameraSetupSession() }
        }
        val learningArtworkBag = EnsoLibrary().newShuffleBag()
        countJapanesePracticeEntry = LearningActivityEntryView(
            context = this,
            title = "Japanese Counting",
            activityType = LearningActivityType.PRACTICE,
            ensoVariant = learningArtworkBag.next(),
            onClick = ::startJapaneseCountingPractice,
        )
        countJapaneseTestEntry = LearningActivityEntryView(
            context = this,
            title = "Japanese Counting",
            activityType = LearningActivityType.TEST,
            ensoVariant = learningArtworkBag.next(),
            onClick = ::startJapaneseCountingTest,
        )
        cancelSessionButton = Button(this).apply {
            text = "Cancel Session"
            isEnabled = false
            setOnClickListener {
                if (cameraSetupActive) {
                    closeCameraSetupSession()
                } else if (punchHeightActive) {
                    cancelPunchHeightSession()
                } else if (japaneseCountActive) {
                    stopJapaneseCountSession()
                } else if (findYourWeaponActive) {
                    navigateFindYourWeaponManually { findYourWeaponController?.cancel() }
                } else {
                    sessionController?.cancel()
                }
            }
        }
        findYourWeaponBackButton = Button(this).apply {
            text = "Back"
            visibility = View.GONE
            setOnClickListener { navigateFindYourWeaponManually { findYourWeaponController?.back() } }
        }
        findYourWeaponNextButton = Button(this).apply {
            text = "Next"
            visibility = View.GONE
            setOnClickListener { navigateFindYourWeaponManually { findYourWeaponController?.next() } }
        }
        japaneseCountBackButton = Button(this).apply {
            text = "Previous"
            visibility = View.GONE
            setOnClickListener { navigateJapaneseCountLevel1 { japaneseCountLevel1Controller.back() } }
        }
        japaneseCountPlayButton = Button(this).apply {
            text = "Play full example"
            visibility = View.GONE
            setOnClickListener { playJapaneseCountFullExample() }
        }
        japaneseCountReplayButton = Button(this).apply {
            text = "Replay"
            visibility = View.GONE
            setOnClickListener { playJapaneseCountLevel1Item() }
        }
        japaneseCountNextButton = Button(this).apply {
            text = "Next"
            visibility = View.GONE
            setOnClickListener { navigateJapaneseCountLevel1 { japaneseCountLevel1Controller.next() } }
        }
        japaneseCountListenButton = Button(this).apply {
            text = "Start listening"
            visibility = View.GONE
            setOnClickListener { requestJapaneseCountLiveRecognition() }
        }
        japaneseCountFinishButton = Button(this).apply {
            text = "Stop listening"
            visibility = View.GONE
            setOnClickListener { finishJapaneseCountLiveRecognition() }
        }
        japaneseCountRetryButton = Button(this).apply {
            text = "Retry"
            visibility = View.GONE
            setOnClickListener { resetJapaneseCountLevel2() }
        }
        punchHeightMultiplierMinusButton = Button(this).apply {
            text = "−0.05"
            setOnClickListener { adjustPunchHeightMultiplier(-PunchHeightAnalyzer.CHIN_PROJECTION_STEP) }
        }
        punchHeightMultiplierResetButton = Button(this).apply {
            text = "Reset"
            setOnClickListener { setPunchHeightMultiplier(PunchHeightAnalyzer.DEFAULT_CHIN_PROJECTION_MULTIPLIER) }
        }
        punchHeightMultiplierPlusButton = Button(this).apply {
            text = "+0.05"
            setOnClickListener { adjustPunchHeightMultiplier(PunchHeightAnalyzer.CHIN_PROJECTION_STEP) }
        }
        punchHeightPracticeAgainButton = Button(this).apply {
            text = "Practice again"
            visibility = View.GONE
            setOnClickListener { startPunchHeightSession() }
        }
        punchHeightCloseButton = Button(this).apply {
            text = "Close"
            visibility = View.GONE
            setOnClickListener { closePunchHeightSession() }
        }
        cameraSetupRetakeButton = Button(this).apply {
            text = "Retake"
            visibility = View.GONE
            setOnClickListener {
                if (latestCameraSetupState.stage == CameraSetupStage.ADJUST_CAMERA) {
                    restartCameraSetupAfterAdjustment()
                } else {
                    latestCameraSetupState.selectedView?.let(::selectCameraSetupView)
                }
            }
        }
        cameraSetupDoneButton = Button(this).apply {
            text = "Done"
            visibility = View.GONE
            setOnClickListener { closeCameraSetupSession() }
        }
        cameraSetupViewButtons = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            visibility = View.GONE
            CameraView.entries.forEach { view ->
                addView(Button(this@MainActivity).apply {
                    text = view.displayName
                    contentDescription = "Select ${view.displayName} camera view"
                    setOnClickListener { selectCameraSetupView(view) }
                })
            }
        }
        debugSwitch = SwitchCompat(this).apply {
            text = "Debug"
            setTextColor(Color.WHITE)
            isChecked = debugUiVisible
            setOnCheckedChangeListener { _, checked ->
                setDeveloperMode(checked)
            }
        }

        punchHeightMultiplierControls = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER
            addView(punchHeightMultiplierMinusButton, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
            addView(punchHeightMultiplierResetButton, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
            addView(punchHeightMultiplierPlusButton, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
            visibility = View.GONE
        }

        val controls = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(32, 32, 32, 48)
            setBackgroundColor(0x66000000)
            addView(startSessionButton)
            addView(findYourWeaponButton)
            addView(punchHeightButton)
            addView(countJapanesePracticeEntry, LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT,
            ).apply { bottomMargin = 8.dp() })
            addView(countJapaneseTestEntry, LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT,
            ).apply { bottomMargin = 8.dp() })
            addView(cameraSetupTitleText)
            addView(cameraSetupMessageText)
            addView(cameraSetupViewButtons)
            addView(cameraSetupProgress)
            addView(cameraSetupCapturedImage)
            addView(cameraSetupRetakeButton)
            addView(cameraSetupDoneButton)
            addView(punchHeightTitleText)
            addView(punchHeightMessageText)
            addView(punchHeightProgress)
            addView(punchHeightMultiplierText)
            addView(punchHeightMultiplierControls)
            addView(punchHeightReviewLargeImage)
            addView(punchHeightReviewThumbnails)
            addView(punchHeightReviewExplanation)
            addView(punchHeightPracticeAgainButton)
            addView(punchHeightCloseButton)
            addView(findYourWeaponProgressRingView)
            addView(findYourWeaponMessageText)
            addView(japaneseCountText)
            addView(japaneseCountFeedbackText)
            addView(japaneseCountResultsText)
            addView(japaneseCountBackButton)
            addView(japaneseCountPlayButton)
            addView(japaneseCountReplayButton)
            addView(japaneseCountNextButton)
            addView(japaneseCountListenButton)
            addView(japaneseCountFinishButton)
            addView(japaneseCountRetryButton)
            addView(findYourWeaponNextButton)
            addView(debugSwitch)
            addView(cancelSessionButton)
            addView(findYourWeaponBackButton)
            addView(debugScopeText)
            addView(statusText)
            addView(currentCountText)
            addView(currentStrikeText)
            addView(expectedSideText)
            addView(recordingStateText)
            addView(savedClipText)
            addView(captureProfileText)
            addView(analyzerDebugText)
            addView(metadataPathText)
        }
        val controlsScroller = object : ScrollView(this) {
            override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
                val maxHeight = (resources.displayMetrics.heightPixels * MAX_CONTROLS_HEIGHT_RATIO).toInt()
                super.onMeasure(
                    widthMeasureSpec,
                    View.MeasureSpec.makeMeasureSpec(maxHeight, View.MeasureSpec.AT_MOST),
                )
            }
        }.apply {
            isFillViewport = false
            addView(
                controls,
                FrameLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT,
                ),
            )
        }

        val root = FrameLayout(this).apply {
            addView(previewView)
            addView(handGuideOverlayView)
            addView(punchHeightOverlayView)
            addView(findYourWeaponImage)
            addView(findYourWeaponDebugOverlayView)
            addView(
                controlsScroller,
                FrameLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT,
                    Gravity.BOTTOM,
                ),
            )
        }
        ViewCompat.setOnApplyWindowInsetsListener(root) { _, insets ->
            val systemBars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            val params = controlsScroller.layoutParams as FrameLayout.LayoutParams
            params.bottomMargin = systemBars.bottom + 16.dp()
            controlsScroller.layoutParams = params
            insets
        }
        trainingRoot = root.apply { visibility = View.GONE }
        ViewCompat.requestApplyInsets(root)
        updateControlVisibility()
    }

    private fun Int.dp(): Int = (this * resources.displayMetrics.density).toInt()

    private fun sessionText(initialText: String, size: Float): TextView = TextView(this).apply {
        text = initialText
        setTextColor(Color.WHITE)
        textSize = size
    }

    private fun requestCameraPermissionIfNeeded() {
        if (hasCameraPermission()) {
            startCameraIfNeeded()
        } else {
            if (cameraStartupRequested) return
            cameraStartupRequested = true
            cameraPermissionLauncher.launch(Manifest.permission.CAMERA)
        }
    }

    private fun hasCameraPermission(): Boolean = ContextCompat.checkSelfPermission(
        this,
        Manifest.permission.CAMERA,
    ) == PackageManager.PERMISSION_GRANTED

    private fun startCameraIfNeeded() {
        cameraStartupRequested = true
        if (recordingAdapter == null) startCamera()
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
                if (cameraSetupActive || punchHeightActive) {
                    val runner = poseRecognizerRunner
                    if (runner == null || poseRecognizerState != PoseRecognizerLifecycleState.READY) {
                        droppedFrameCount.incrementAndGet()
                        null
                    } else {
                        val generationToken = if (cameraSetupActive) {
                            cameraSetupCoordinator.currentGenerationToken()
                        } else {
                            punchHeightCoordinator.currentGenerationToken()
                        }
                        runner.tryAcquireFrame(timestampMs, generationToken).also { permit ->
                            if (permit == null) droppedFrameCount.incrementAndGet()
                        }
                    }
                } else {
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
                }
            },
            onAnalysisPermitRelease = { permit ->
                when (permit) {
                    is PoseFramePermit -> poseRecognizerRunner?.releasePermit(permit)
                    is FramePermit -> recognizerRunner?.releasePermit(permit)
                }
            },
            onAnalysisFrame = { bitmap, _, permit, analysisToPreviewTransform ->
                val accepted = when (permit) {
                    is PoseFramePermit -> poseRecognizerRunner?.submit(bitmap, permit) ?: false
                    is FramePermit -> recognizerRunner?.submit(bitmap, permit, analysisToPreviewTransform) ?: false
                    else -> false
                }
                if (accepted) submittedFrameCount.incrementAndGet()
                accepted
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
            countdownBeforeTrainingMs = appPreferences.countdownSeconds * 1_000L,
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

    private fun startCameraSetupSession() {
        recordingAdapter?.setAnalysisEnabled(false)
        poseRecognizerRunner?.close()
        poseRecognizerRunner = null
        poseRecognizerState = PoseRecognizerLifecycleState.INITIALIZING
        cameraSetupVoiceCoach?.close()
        cameraSetupVoiceCoach = if (appPreferences.voiceGuidance) PunchHeightVoiceCoach(this) else null
        completedCameraSetupCapture = null
        lastSpokenCameraSetupMessage = null
        cameraSetupCapturedImage.setImageDrawable(null)
        cameraSetupActive = true
        latestCameraSetupState = cameraSetupCoordinator.start()
        updateCameraSetupState(latestCameraSetupState, speak = false)
        updateMainMenuAvailability()
        updateControlVisibility()
        recognizerExecutor.execute {
            val runner = createPoseRecognizerRunner()
            runOnMainThread {
                poseRecognizerRunner?.close()
                poseRecognizerRunner = runner
                poseRecognizerState = runner.lifecycleState
                if (!cameraSetupActive) return@runOnMainThread
                if (runner.initializationSucceeded()) {
                    recordingAdapter?.setAnalysisEnabled(
                        cameraSetupCoordinator.currentState().stage == CameraSetupStage.POSITIONING,
                    )
                } else {
                    updateCameraSetupState(cameraSetupCoordinator.fail("Pose tracking could not start."))
                }
            }
        }
    }

    private fun selectCameraSetupView(view: CameraView) {
        if (!cameraSetupActive) return
        completedCameraSetupCapture = null
        cameraSetupCapturedImage.setImageDrawable(null)
        cameraSetupVoiceCoach?.reset()
        lastSpokenCameraSetupMessage = null
        updateCameraSetupState(cameraSetupCoordinator.selectView(view), speak = true)
        recordingAdapter?.setAnalysisEnabled(poseRecognizerState == PoseRecognizerLifecycleState.READY)
    }

    private fun handleCameraSetupPoseResult(output: LivePoseLandmarkerOutput, bitmap: Bitmap) {
        processedFrameCount.incrementAndGet()
        val decision = cameraSetupCoordinator.process(output.poseFrame)
        if (!decision.shouldCapture) {
            if (!bitmap.isRecycled) bitmap.recycle()
            runOnMainThread {
                if (cameraSetupActive) {
                    if (decision.state.stage == CameraSetupStage.ADJUST_CAMERA) {
                        recordingAdapter?.setAnalysisEnabled(false)
                    }
                    updateCameraSetupState(decision.state)
                }
            }
            return
        }
        recordingAdapter?.setAnalysisEnabled(false)
        runOnMainThread { if (cameraSetupActive) updateCameraSetupState(decision.state) }
        val view = decision.state.selectedView ?: run {
            if (!bitmap.isRecycled) bitmap.recycle()
            return
        }
        val captureFrame = decision.captureFrame ?: run {
            if (!bitmap.isRecycled) bitmap.recycle()
            return
        }
        punchHeightStorageExecutor.execute {
            runCatching { cameraSetupCaptureStore.save(bitmap, view, captureFrame) }
                .onSuccess { capture ->
                    runOnMainThread {
                        if (!cameraSetupActive) return@runOnMainThread
                        completedCameraSetupCapture = capture
                        cameraSetupCapturedImage.setImageBitmap(BitmapFactory.decodeFile(capture.imageFile.absolutePath))
                        metadataPathText.text = "Camera setup: ${capture.imageFile.absolutePath}"
                        updateCameraSetupState(cameraSetupCoordinator.captureSaved())
                    }
                }
                .onFailure { error ->
                    runOnMainThread {
                        if (cameraSetupActive) updateCameraSetupState(
                            cameraSetupCoordinator.fail("Could not save the setup picture: ${error.message}"),
                        )
                    }
                }
        }
    }

    private fun updateCameraSetupState(state: CameraSetupState, speak: Boolean = true) {
        latestCameraSetupState = state
        cameraSetupTitleText.text = state.selectedView?.let { "Camera setup - ${it.displayName}" } ?: "Camera setup"
        cameraSetupMessageText.text = state.message
        cameraSetupProgress.progress = (state.holdProgress * cameraSetupProgress.max).toInt()
        if (appPreferences.voiceGuidance && speak && cameraSetupActive && state.message != lastSpokenCameraSetupMessage) {
            val spoken = cameraSetupVoiceCoach?.speak(state.message, SystemClock.elapsedRealtime()) == true
            if (spoken) lastSpokenCameraSetupMessage = state.message
        }
        updateControlVisibility()
    }

    private fun restartCameraSetupAfterAdjustment() {
        if (!cameraSetupActive || latestCameraSetupState.stage != CameraSetupStage.ADJUST_CAMERA) return
        cameraSetupVoiceCoach?.reset()
        lastSpokenCameraSetupMessage = null
        updateCameraSetupState(cameraSetupCoordinator.restartAfterCameraAdjustment())
        recordingAdapter?.setAnalysisEnabled(poseRecognizerState == PoseRecognizerLifecycleState.READY)
    }

    private fun closeCameraSetupSession() {
        recordingAdapter?.setAnalysisEnabled(false)
        cameraSetupCoordinator.cancel()
        cameraSetupActive = false
        poseRecognizerRunner?.close()
        poseRecognizerRunner = null
        poseRecognizerState = PoseRecognizerLifecycleState.CLOSED
        cameraSetupVoiceCoach?.close()
        cameraSetupVoiceCoach = null
        lastSpokenCameraSetupMessage = null
        completedCameraSetupCapture = null
        cameraSetupCapturedImage.setImageDrawable(null)
        updateMainMenuAvailability()
        updateControlVisibility()
    }

    private fun startPunchHeightSession() {
        recordingAdapter?.setAnalysisEnabled(false)
        poseRecognizerRunner?.close()
        poseRecognizerRunner = null
        poseRecognizerState = PoseRecognizerLifecycleState.INITIALIZING
        punchHeightVoiceCoach?.close()
        punchHeightVoiceCoach = if (appPreferences.voiceGuidance) PunchHeightVoiceCoach(this) else null
        completedPunchHeightSession = null
        clearPunchHeightReview()
        try {
            punchHeightCaptureStore.beginSession()
        } catch (error: Exception) {
            Toast.makeText(this, error.message ?: "Could not start Punch Heights.", Toast.LENGTH_LONG).show()
            return
        }
        punchHeightActive = true
        val state = punchHeightCoordinator.start()
        updatePunchHeightState(state)
        updateMainMenuAvailability()
        updateControlVisibility()
        recognizerExecutor.execute {
            val runner = createPoseRecognizerRunner()
            runOnMainThread {
                poseRecognizerRunner?.close()
                poseRecognizerRunner = runner
                poseRecognizerState = runner.lifecycleState
                if (punchHeightActive && runner.initializationSucceeded()) {
                    recordingAdapter?.setAnalysisEnabled(true)
                } else if (punchHeightActive) {
                    failPunchHeightSession("Pose tracking could not start. Check that the Pose Landmarker model is installed.")
                }
            }
        }
    }

    private fun createPoseRecognizerRunner(): LivePoseLandmarkerRunner = LivePoseLandmarkerRunner(
        context = this,
        onResult = { output, bitmap ->
            if (cameraSetupActive) handleCameraSetupPoseResult(output, bitmap)
            else handlePunchHeightPoseResult(output, bitmap)
        },
        onError = { message -> runOnMainThread {
            if (cameraSetupActive) updateCameraSetupState(cameraSetupCoordinator.fail(message))
            else failPunchHeightSession(message)
        } },
    )

    private fun handlePunchHeightPoseResult(output: LivePoseLandmarkerOutput, bitmap: Bitmap) {
        processedFrameCount.incrementAndGet()
        val decision = punchHeightCoordinator.process(output)
        val snapshot = decision.captureSnapshot
        if (snapshot == null) {
            if (!bitmap.isRecycled) bitmap.recycle()
            runOnMainThread { if (punchHeightActive) updatePunchHeightState(decision.state) }
            return
        }
        recordingAdapter?.setAnalysisEnabled(false)
        runOnMainThread { if (punchHeightActive) updatePunchHeightState(decision.state) }
        punchHeightStorageExecutor.execute {
            runCatching { punchHeightCaptureStore.saveCapture(bitmap, snapshot) }
                .onSuccess {
                    runOnMainThread {
                        if (!punchHeightActive) return@runOnMainThread
                        updatePunchHeightState(punchHeightCoordinator.captureSaved(snapshot.targetType))
                        mainHandler.postDelayed({ advancePunchHeightAfterCapture(snapshot.targetType) }, PUNCH_HEIGHT_CAPTURE_PAUSE_MS)
                    }
                }
                .onFailure { error -> runOnMainThread { failPunchHeightSession("Could not save ${snapshot.targetType.name.lowercase()}: ${error.message}") } }
        }
    }

    private fun advancePunchHeightAfterCapture(capturedTarget: PunchHeightTargetType) {
        if (!punchHeightActive || capturedTarget !in punchHeightCoordinator.currentState().capturedTargets) return
        val state = punchHeightCoordinator.advanceAfterCapture()
        updatePunchHeightState(state)
        if (state.stage == PunchHeightSessionStage.SESSION_REVIEW) {
            finishPunchHeightSessionStorage()
        } else {
            recordingAdapter?.setAnalysisEnabled(poseRecognizerState == PoseRecognizerLifecycleState.READY)
        }
    }

    private fun finishPunchHeightSessionStorage() {
        recordingAdapter?.setAnalysisEnabled(false)
        punchHeightStorageExecutor.execute {
            runCatching { punchHeightCaptureStore.completeSession() }
                .onSuccess { session ->
                    runOnMainThread {
                        if (!punchHeightActive) return@runOnMainThread
                        completedPunchHeightSession = session
                        val state = punchHeightCoordinator.markReviewReady()
                        updatePunchHeightState(state)
                        showPunchHeightReview(session)
                    }
                }
                .onFailure { error -> runOnMainThread { failPunchHeightSession("Could not finish the image set: ${error.message}") } }
        }
    }

    private fun showPunchHeightReview(session: PunchHeightCompletedSession) {
        punchHeightReviewThumbnails.removeAllViews()
        session.captures.forEach { capture ->
            val thumbnail = ImageView(this).apply {
                layoutParams = LinearLayout.LayoutParams(0, 100.dp(), 1f).apply { marginStart = 4.dp(); marginEnd = 4.dp() }
                scaleType = ImageView.ScaleType.CENTER_CROP
                setImageBitmap(BitmapFactory.decodeFile(capture.analysisFile.absolutePath))
                contentDescription = "${capture.targetType.name.lowercase()} analysis"
                setOnClickListener { selectPunchHeightReviewCapture(capture) }
            }
            punchHeightReviewThumbnails.addView(thumbnail)
        }
        session.captures.firstOrNull()?.let(::selectPunchHeightReviewCapture)
        updateControlVisibility()
    }

    private fun selectPunchHeightReviewCapture(capture: dk.lasse.karatecliprecorder.learning.PunchHeightSavedCapture) {
        punchHeightReviewLargeImage.setImageBitmap(BitmapFactory.decodeFile(capture.analysisFile.absolutePath))
        punchHeightReviewExplanation.text = buildString {
            append(capture.snapshot.target.explanation)
            append(" Your fist was ")
            append(String.format(java.util.Locale.US, "%+.1f%%", capture.snapshot.signedHeightErrorTorsoRatio * 100f))
            append(" of your torso length from the target centre.")
        }
    }

    private fun clearPunchHeightReview() {
        if (::punchHeightReviewThumbnails.isInitialized) punchHeightReviewThumbnails.removeAllViews()
        if (::punchHeightReviewLargeImage.isInitialized) punchHeightReviewLargeImage.setImageDrawable(null)
        if (::punchHeightReviewExplanation.isInitialized) punchHeightReviewExplanation.text = ""
    }

    private fun adjustPunchHeightMultiplier(delta: Float) {
        setPunchHeightMultiplier(punchHeightCoordinator.currentState().chinProjectionMultiplier + delta)
    }

    private fun setPunchHeightMultiplier(value: Float) {
        if (!punchHeightActive) return
        updatePunchHeightState(punchHeightCoordinator.setChinProjectionMultiplier(value), speak = false)
    }

    private fun cancelPunchHeightSession() {
        recordingAdapter?.setAnalysisEnabled(false)
        punchHeightCoordinator.cancel()
        punchHeightActive = false
        poseRecognizerRunner?.close()
        poseRecognizerRunner = null
        poseRecognizerState = PoseRecognizerLifecycleState.CLOSED
        punchHeightVoiceCoach?.close()
        punchHeightVoiceCoach = null
        punchHeightStorageExecutor.execute { punchHeightCaptureStore.cancelSession() }
        clearPunchHeightReview()
        latestPunchHeightState = PunchHeightSessionState()
        updateMainMenuAvailability()
        updateControlVisibility()
    }

    private fun closePunchHeightSession() {
        punchHeightCoordinator.complete()
        punchHeightActive = false
        recordingAdapter?.setAnalysisEnabled(false)
        poseRecognizerRunner?.close()
        poseRecognizerRunner = null
        poseRecognizerState = PoseRecognizerLifecycleState.CLOSED
        punchHeightVoiceCoach?.close()
        punchHeightVoiceCoach = null
        completedPunchHeightSession = null
        clearPunchHeightReview()
        latestPunchHeightState = PunchHeightSessionState()
        updateMainMenuAvailability()
        updateControlVisibility()
    }

    private fun failPunchHeightSession(message: String) {
        if (!punchHeightActive) return
        recordingAdapter?.setAnalysisEnabled(false)
        val failed = punchHeightCoordinator.fail(message)
        updatePunchHeightState(failed, speak = false)
        Toast.makeText(this, message, Toast.LENGTH_LONG).show()
        punchHeightActive = false
        poseRecognizerRunner?.close()
        poseRecognizerRunner = null
        poseRecognizerState = PoseRecognizerLifecycleState.FAILED
        punchHeightVoiceCoach?.close()
        punchHeightVoiceCoach = null
        punchHeightStorageExecutor.execute { punchHeightCaptureStore.cancelSession() }
        updateMainMenuAvailability()
        updateControlVisibility()
    }

    private fun updatePunchHeightState(state: PunchHeightSessionState, speak: Boolean = true) {
        latestPunchHeightState = state
        val inReview = state.stage == PunchHeightSessionStage.SESSION_REVIEW && completedPunchHeightSession != null
        punchHeightTitleText.text = when {
            inReview -> "Punch Heights review"
            state.targetType != null -> "${state.targetType.name.lowercase().replaceFirstChar(Char::uppercase)} — ${state.capturedTargets.size + 1} of 3"
            state.stage == PunchHeightSessionStage.SESSION_SETUP -> "Punch Heights — Setup"
            state.stage == PunchHeightSessionStage.BODY_INITIALIZATION -> "Body initialization"
            else -> "Punch Heights — Level 1"
        }
        punchHeightMessageText.text = state.message
        punchHeightProgress.progress = ((state.evaluation?.holdProgress
            ?: state.setupEvaluation?.progress
            ?: state.initializationEvaluation?.progress
            ?: 0f) * punchHeightProgress.max).toInt()
        punchHeightMultiplierText.text = String.format(
            java.util.Locale.US,
            "Jodan chin projection ×%.2f",
            state.chinProjectionMultiplier,
        )
        punchHeightOverlayView.setSessionState(if (punchHeightActive && !inReview) state else null, debugUiVisible)
        if (appPreferences.voiceGuidance && speak && punchHeightActive) {
            punchHeightVoiceCoach?.speak(state.message, SystemClock.elapsedRealtime())
        }
        val evaluation = state.evaluation
        analyzerDebugText.text = if (evaluation == null) {
            "Pose: ${poseRecognizerState.name.lowercase()} / stage ${state.stage.name.lowercase()}"
        } else listOf(
            "Pose: ${poseRecognizerState.name.lowercase()} / ${state.stage.name.lowercase()}",
            "Target: ${evaluation.target?.calculationStrategy ?: "lost"}",
            "Confidence: ${evaluation.target?.confidence?.format2() ?: "--"}",
            "Arm: ${evaluation.activeArm.name.lowercase()} / elbow ${evaluation.elbowAngleDegrees?.format2() ?: "--"}°",
            "Error: ${evaluation.signedHeightErrorTorsoRatio?.let { String.format(java.util.Locale.US, "%+.2f", it) } ?: "--"}",
            "Hold: ${evaluation.stableHoldMs} ms / ${(evaluation.holdProgress * 100f).toInt()}%",
            "Chin: ${evaluation.target?.chinEstimate?.source?.name ?: "n/a"} / ${evaluation.target?.chinEstimate?.confidence?.format2() ?: "--"}",
            "Capture: target ≥0.70 / arm ≥0.60 / elbow ≥165° / hold 1200 ms",
            "Motion 400 ms: fist ≤0.025 / target ≤0.015 / body ≤0.020 torso",
        ).joinToString("\n")
        updateControlVisibility()
    }

    private fun startJapaneseCountingPractice() {
        stopJapaneseCountSession()
        metadataPathText.text = "Metadata: not saved"
        japaneseCountMode = LearningActivityType.PRACTICE
        japaneseCountActive = true
        japaneseCountLevel1Controller.start()
    }

    private fun startJapaneseCountingTest() {
        stopJapaneseCountSession()
        metadataPathText.text = "Metadata: not saved"
        japaneseCountMode = LearningActivityType.TEST
        japaneseCountActive = true
        resetJapaneseCountLevel2()
        if (japaneseCountTrainingSession.phase == CountTrainingPhase.READY) {
            playJapaneseCountFullExample()
        }
    }

    private fun stopJapaneseCountSession() {
        cancelJapaneseCountRecognitionRestart()
        pendingJapaneseCountMicrophonePermission = false
        trainingOrderPlayer?.stop()
        if (::japaneseCountFullExamplePlayer.isInitialized) {
            japaneseCountFullExamplePlayer.stop()
        }
        if (::japaneseCountLiveRecognizer.isInitialized) {
            japaneseCountLiveRecognizer.cancel()
        }
        japaneseCountMode = null
        japaneseCountActive = false
        japaneseCountCommittedTranscript = ""
        japaneseCountTrainingSession = CountTrainingSession()
        if (::japaneseCountLevel1Controller.isInitialized) {
            japaneseCountLevel1Controller.cancel()
        }
        japaneseCountText.text = ""
        japaneseCountFeedbackText.text = ""
        japaneseCountResultsText.text = ""
        updateJapaneseCountDebugText()
        updateMainMenuAvailability()
        updateControlVisibility()
    }

    private fun navigateJapaneseCountLevel1(action: () -> Unit) {
        if (japaneseCountMode != LearningActivityType.PRACTICE) return
        trainingOrderPlayer?.stop()
        action()
    }

    private fun playJapaneseCountLevel1Item() {
        val item = japaneseCountLevel1Controller.state.item ?: return
        playJapaneseCountPrompt(item)
    }

    private fun playJapaneseCountPrompt(item: JapaneseCountLessonItem, onComplete: () -> Unit = {}) {
        japaneseCountFullExamplePlayer.stop()
        if (appPreferences.trainingSounds) {
            trainingOrderPlayer?.play(item.order, onComplete) ?: onComplete()
        } else {
            onComplete()
        }
    }

    private fun playJapaneseCountFullExample() {
        if (japaneseCountMode != LearningActivityType.TEST) return
        trainingOrderPlayer?.stop()
        if (!appPreferences.trainingSounds) return
        japaneseCountFullExamplePlayer.play(
            onError = { error ->
                japaneseCountFeedbackText.text =
                    "The full count example could not be played. Tap Play full example to retry."
                if ((applicationInfo.flags and ApplicationInfo.FLAG_DEBUGGABLE) != 0) {
                    Log.e(JAPANESE_COUNT_LOG_TAG, "Full count example playback failed.", error)
                }
            },
        )
    }

    private fun requestJapaneseCountLiveRecognition() {
        if (japaneseCountMode != LearningActivityType.TEST) return
        val hasAudioPermission = ContextCompat.checkSelfPermission(
            this,
            Manifest.permission.RECORD_AUDIO,
        ) == PackageManager.PERMISSION_GRANTED
        if (!hasAudioPermission) {
            pendingJapaneseCountMicrophonePermission = true
            audioPermissionLauncher.launch(Manifest.permission.RECORD_AUDIO)
            return
        }
        beginJapaneseCountLiveRecognition()
    }

    private fun beginJapaneseCountLiveRecognition() {
        if (japaneseCountMode != LearningActivityType.TEST) return
        trainingOrderPlayer?.stop()
        japaneseCountFullExamplePlayer.stop()
        updateJapaneseCountLevel2Session(
            japaneseCountTrainingSession.copy(
                phase = CountTrainingPhase.LISTENING,
                partialTranscripts = emptyList(),
                finalTranscriptAlternatives = emptyList(),
                selectedTranscript = null,
                normalizationTokens = emptyList(),
                normalizedSequence = emptyList(),
                countResults = emptyList(),
                sequenceScore = 0f,
                successful = false,
                error = null,
                technicalError = null,
            ),
        )
        japaneseCountCommittedTranscript = ""
        cancelJapaneseCountRecognitionRestart()
        startJapaneseCountRecognitionSegment()
    }

    private fun startJapaneseCountRecognitionSegment() {
        if (
            japaneseCountMode != LearningActivityType.TEST ||
            japaneseCountTrainingSession.phase != CountTrainingPhase.LISTENING
        ) return
        japaneseCountLiveRecognizer.start(
            onPartialResults = { partials ->
                if (
                    japaneseCountMode == LearningActivityType.TEST &&
                    japaneseCountTrainingSession.phase == CountTrainingPhase.LISTENING
                ) {
                    val combinedPartials = partials.map(::combineJapaneseCountTranscript)
                    val candidates = combinedPartials.map(CountTranscriptNormalizer::normalizeJapaneseTranscript)
                    val reachedCountLimit = candidates.any {
                        JapaneseCountListeningPolicy.shouldAutoStop(it.normalizedSequence.size)
                    }
                    updateJapaneseCountLevel2Session(
                        japaneseCountTrainingSession.copy(
                            partialTranscripts = (japaneseCountTrainingSession.partialTranscripts + combinedPartials)
                                .distinct()
                                .takeLast(MAX_JAPANESE_PARTIAL_TRANSCRIPTS),
                        ),
                    )
                    if (reachedCountLimit) finishJapaneseCountLiveRecognition()
                }
            },
            onSegmentResults = { alternatives ->
                processJapaneseCountRecognitionAlternatives(
                    alternatives = alternatives,
                    restartIfIncomplete = false,
                )
            },
            onFinalResults = ::completeJapaneseCountLiveRecognition,
            onRecognitionEnded = ::handleJapaneseCountRecognitionEnded,
            onError = ::handleJapaneseCountRecognitionError,
        )
    }

    private fun finishJapaneseCountLiveRecognition() {
        if (
            japaneseCountMode != LearningActivityType.TEST ||
            japaneseCountTrainingSession.phase != CountTrainingPhase.LISTENING
        ) return
        cancelJapaneseCountRecognitionRestart()
        updateJapaneseCountLevel2Session(
            japaneseCountTrainingSession.copy(phase = CountTrainingPhase.FINALIZING),
        )
        japaneseCountLiveRecognizer.stopListening()
    }

    private fun completeJapaneseCountLiveRecognition(alternatives: List<CountRecognitionAlternative>) {
        processJapaneseCountRecognitionAlternatives(
            alternatives = alternatives,
            restartIfIncomplete = true,
        )
    }

    private fun processJapaneseCountRecognitionAlternatives(
        alternatives: List<CountRecognitionAlternative>,
        restartIfIncomplete: Boolean,
        combineWithCommittedTranscript: Boolean = true,
    ) {
        if (japaneseCountMode != LearningActivityType.TEST) return
        val combinedAlternatives = if (combineWithCommittedTranscript) {
            alternatives.map { alternative ->
                alternative.copy(transcript = combineJapaneseCountTranscript(alternative.transcript))
            }
        } else {
            alternatives
        }
        val selected = CountTranscriptNormalizer.selectStrongestAlternative(combinedAlternatives)
        if (selected == null) {
            handleJapaneseCountRecognitionError(
                CountRecognitionFailure(
                    CountRecognitionError.EMPTY_TRANSCRIPTION,
                ),
            )
            return
        }
        val finalHistory = (
            japaneseCountTrainingSession.finalTranscriptAlternatives +
                combinedAlternatives.map(CountRecognitionAlternative::transcript)
            ).distinct()

        if (
            japaneseCountTrainingSession.phase == CountTrainingPhase.LISTENING &&
            !JapaneseCountListeningPolicy.shouldAutoStop(selected.normalizedSequence.size)
        ) {
            japaneseCountCommittedTranscript = selected.rawTranscript
            updateJapaneseCountLevel2Session(
                japaneseCountTrainingSession.copy(
                    finalTranscriptAlternatives = finalHistory,
                    selectedTranscript = selected.rawTranscript,
                    normalizationTokens = selected.tokens,
                    normalizedSequence = selected.normalizedSequence,
                    countResults = selected.exerciseResult.countResults,
                    sequenceScore = selected.sequenceScore,
                ),
            )
            if (restartIfIncomplete) scheduleJapaneseCountRecognitionRestart()
            return
        }

        cancelJapaneseCountRecognitionRestart()
        japaneseCountLiveRecognizer.cancel()
        updateJapaneseCountLevel2Session(
            japaneseCountTrainingSession.copy(
                phase = CountTrainingPhase.RESULT,
                finalTranscriptAlternatives = finalHistory,
                selectedTranscript = selected.rawTranscript,
                normalizationTokens = selected.tokens,
                normalizedSequence = selected.normalizedSequence,
                countResults = selected.exerciseResult.countResults,
                sequenceScore = selected.sequenceScore,
                successful = selected.successful,
                error = null,
                technicalError = null,
            ),
        )
    }

    private fun handleJapaneseCountRecognitionEnded() {
        if (japaneseCountMode != LearningActivityType.TEST) return
        when (japaneseCountTrainingSession.phase) {
            CountTrainingPhase.LISTENING -> scheduleJapaneseCountRecognitionRestart()
            CountTrainingPhase.FINALIZING -> finalizeJapaneseCountBestAvailableTranscript()
            else -> Unit
        }
    }

    private fun finalizeJapaneseCountBestAvailableTranscript() {
        val bestAvailable = CountTranscriptNormalizer.selectStrongestAlternative(
            buildList {
                japaneseCountCommittedTranscript.takeIf(String::isNotBlank)?.let { transcript ->
                    add(CountRecognitionAlternative(transcript))
                }
                japaneseCountTrainingSession.partialTranscripts.forEach { transcript ->
                    add(CountRecognitionAlternative(transcript))
                }
            },
        )
        if (bestAvailable == null) {
            showJapaneseCountLevel2Error(CountRecognitionError.EMPTY_TRANSCRIPTION)
            return
        }
        processJapaneseCountRecognitionAlternatives(
            alternatives = listOf(CountRecognitionAlternative(bestAvailable.rawTranscript)),
            restartIfIncomplete = false,
            combineWithCommittedTranscript = false,
        )
    }

    private fun handleJapaneseCountRecognitionError(
        failure: CountRecognitionFailure,
    ) {
        if (japaneseCountMode != LearningActivityType.TEST) return
        val retryableEarlyEnd = failure.error == CountRecognitionError.NO_SPEECH_DETECTED ||
            failure.error == CountRecognitionError.BUSY ||
            failure.error == CountRecognitionError.EMPTY_TRANSCRIPTION
        when {
            retryableEarlyEnd && japaneseCountTrainingSession.phase == CountTrainingPhase.LISTENING -> {
                scheduleJapaneseCountRecognitionRestart(
                    delayMs = if (failure.error == CountRecognitionError.BUSY) {
                        JAPANESE_COUNT_BUSY_RETRY_DELAY_MS
                    } else {
                        0L
                    },
                )
            }
            retryableEarlyEnd &&
                japaneseCountTrainingSession.phase == CountTrainingPhase.FINALIZING &&
                (japaneseCountCommittedTranscript.isNotBlank() ||
                    japaneseCountTrainingSession.partialTranscripts.isNotEmpty()) -> {
                finalizeJapaneseCountBestAvailableTranscript()
            }
            else -> showJapaneseCountLevel2Error(
                error = failure.error,
                technicalMessage = failure.technicalMessage,
            )
        }
    }

    private fun combineJapaneseCountTranscript(transcript: String): String =
        listOf(japaneseCountCommittedTranscript, transcript)
            .filter(String::isNotBlank)
            .joinToString(" ")

    private fun scheduleJapaneseCountRecognitionRestart(delayMs: Long = 0L) {
        cancelJapaneseCountRecognitionRestart()
        pendingJapaneseCountRecognitionRestart = Runnable {
            pendingJapaneseCountRecognitionRestart = null
            if (
                japaneseCountMode == LearningActivityType.TEST &&
                japaneseCountTrainingSession.phase == CountTrainingPhase.LISTENING
            ) {
                startJapaneseCountRecognitionSegment()
            }
        }.also { restart ->
            mainHandler.postDelayed(restart, delayMs)
        }
    }

    private fun cancelJapaneseCountRecognitionRestart() {
        pendingJapaneseCountRecognitionRestart?.let(mainHandler::removeCallbacks)
        pendingJapaneseCountRecognitionRestart = null
    }

    private fun resetJapaneseCountLevel2() {
        if (japaneseCountMode != LearningActivityType.TEST) return
        pendingJapaneseCountMicrophonePermission = false
        cancelJapaneseCountRecognitionRestart()
        japaneseCountCommittedTranscript = ""
        japaneseCountLiveRecognizer.cancel()
        updateJapaneseCountLevel2Session(
            CountTrainingSession(phase = CountTrainingPhase.READY),
        )
    }

    private fun showJapaneseCountLevel2Error(
        error: CountRecognitionError,
        technicalMessage: String? = null,
    ) {
        updateJapaneseCountLevel2Session(
            japaneseCountTrainingSession.copy(
                phase = CountTrainingPhase.ERROR,
                successful = false,
                error = error,
                technicalError = technicalMessage,
            ),
        )
    }

    private fun updateJapaneseCountLevel2Session(session: CountTrainingSession) {
        if (japaneseCountMode != LearningActivityType.TEST) return
        if (session.phase != CountTrainingPhase.LISTENING) cancelJapaneseCountRecognitionRestart()
        japaneseCountTrainingSession = session
        if ((applicationInfo.flags and ApplicationInfo.FLAG_DEBUGGABLE) != 0) {
            Log.d(JAPANESE_COUNT_LOG_TAG, session.toString())
        }
        japaneseCountActive = true
        japaneseCountText.text = when (session.phase) {
            CountTrainingPhase.READY -> "Japanese Counting Test\nCount aloud from 1 to 10"
            CountTrainingPhase.LISTENING -> session.partialTranscripts.lastOrNull()
                ?.let { "Listening...\n$it" }
                ?: "Listening...\nSay all ten numbers; brief pauses are okay"
            CountTrainingPhase.FINALIZING -> "Finishing Japanese recognition..."
            CountTrainingPhase.RESULT -> if (session.successful) "Complete count recognized" else "Count needs another try"
            CountTrainingPhase.ERROR -> "Unable to analyze this count"
            else -> "Japanese Counting Test"
        }
        japaneseCountFeedbackText.text = when (session.phase) {
            CountTrainingPhase.READY -> "Play the example, then start Japanese live recognition."
            CountTrainingPhase.LISTENING -> "Tap Stop listening when done, or it stops after ten recognized numbers."
            CountTrainingPhase.FINALIZING -> "Please wait for the final result."
            CountTrainingPhase.RESULT -> if (session.successful) {
                "Success - all ten numbers are in the correct order."
            } else {
                "One or more numbers were missing or out of order."
            }
            CountTrainingPhase.ERROR -> session.error.toJapaneseCountUserMessage()
            else -> ""
        }
        japaneseCountResultsText.text = buildList {
            session.selectedTranscript?.takeIf(String::isNotBlank)?.let { transcript ->
                add("Recognized: $transcript")
            }
            session.countResults.forEach { result ->
                add(
                    when (result.status) {
                        CountStatus.CORRECT -> "${result.expectedNumber}  Correct"
                        CountStatus.INCORRECT -> buildString {
                            append(result.expectedNumber)
                            append("  Incorrect - heard: ")
                            append(result.recognizedText ?: "unknown")
                            result.normalizedNumber?.let { normalized -> append(" -> $normalized") }
                        }
                        CountStatus.MISSING -> "${result.expectedNumber}  Missing - no count recognized"
                    },
                )
            }
        }.joinToString("\n")
        updateJapaneseCountDebugText()
        updateControlVisibility()
    }

    private fun updateJapaneseCountDebugText() {
        if (currentDebugScope() != DebugScope.COUNT_JAPANESE) return
        analyzerDebugText.text = when (japaneseCountMode) {
            LearningActivityType.PRACTICE -> {
                val state = japaneseCountLevel1Controller.state
                listOf(
                    "Activity: practice (self-training only)",
                    "Expected: ${state.item?.number ?: "--"} / ${state.item?.japanese ?: "--"}",
                    "Microphone: unused",
                    "Recognition: unused",
                ).joinToString("\n")
            }
            LearningActivityType.TEST -> with(japaneseCountTrainingSession) {
                listOf(
                    "Activity: test / Phase: ${phase.name.lowercase()}",
                    "Language: $recognitionLanguage",
                    "Partial: ${partialTranscripts.joinToString(" | ").ifBlank { "--" }}",
                    "Final alternatives: ${finalTranscriptAlternatives.joinToString(" | ").ifBlank { "--" }}",
                    "Selected: ${selectedTranscript ?: "--"}",
                    "Tokens: ${normalizationTokens.joinToString(" | ") { token -> "${token.recognizedText}->${token.normalizedNumber ?: "?"}/${token.normalizationRule?.name ?: "NONE"}" }.ifBlank { "--" }}",
                    "Normalized: ${normalizedSequence.joinToString().ifBlank { "--" }}",
                    "Results: ${countResults.joinToString { "${it.expectedNumber}:${it.status.name}" }.ifBlank { "--" }}",
                    "Score: ${sequenceScore.format2()} / Success: $successful",
                    "Error: ${error?.name ?: "--"}${technicalError?.let { " / $it" } ?: ""}",
                ).joinToString("\n")
            }
            null -> "Analyzer: inactive"
        }
    }

    private fun CountStatus.toJapaneseCountStatusText(): String = when (this) {
        CountStatus.CORRECT -> "Correct"
        CountStatus.INCORRECT -> "Incorrect"
        CountStatus.MISSING -> "Missing"
    }

    private fun CountRecognitionError?.toJapaneseCountUserMessage(): String = when (this) {
        CountRecognitionError.MICROPHONE_PERMISSION_DENIED -> "Microphone permission is needed for the Japanese Counting Test. You can retry after granting it."
        CountRecognitionError.NO_SPEECH_DETECTED,
        CountRecognitionError.EMPTY_TRANSCRIPTION -> "No count was detected. Please retry and speak clearly."
        CountRecognitionError.RECOGNITION_SERVICE_UNAVAILABLE -> "Speech recognition is not available on this device."
        CountRecognitionError.LANGUAGE_NOT_SUPPORTED,
        CountRecognitionError.LANGUAGE_UNAVAILABLE -> "The required recognition language is unavailable."
        CountRecognitionError.NETWORK -> "Recognition needs a working network connection. Please retry."
        CountRecognitionError.TIMEOUT -> "Recognition timed out. Please retry."
        CountRecognitionError.BUSY -> "Speech recognition is busy. Please retry."
        CountRecognitionError.CLIENT,
        CountRecognitionError.SERVER,
        CountRecognitionError.UNKNOWN,
        null -> "The count could not be analyzed. Please retry."
    }

    private fun startFindYourWeaponSession() {
        metadataPathText.text = "Metadata: not saved"
        resetFindYourWeaponAutoAdvance()
        renderedFindYourWeaponStep = null
        latestFindYourWeaponAnalysisState = null
        findYourWeaponKnuckleGuideVisible = false
        findYourWeaponFinishReady = false
        findYourWeaponProgressRingView.setProgress(0f, accepted = false)
        findYourWeaponMessageText.text = ""
        findYourWeaponCoachTextGate.reset()
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
        findYourWeaponController?.start()
        recognizerExecutor.execute {
            val runner = createRecognizerRunner()
            runOnMainThread {
                recognizerRunner?.close()
                recognizerRunner = runner
                recognizerState = runner.lifecycleState
                if (runner.initializationSucceeded()) {
                    recordingAdapter?.setAnalysisEnabled(findYourWeaponActive && findYourWeaponController?.state?.step != null)
                } else {
                    recordingAdapter?.setAnalysisEnabled(false)
                }
            }
        }
    }

    private fun navigateFindYourWeaponManually(action: () -> Unit) {
        resetFindYourWeaponAutoAdvance()
        action()
    }

    private fun resetFindYourWeaponAutoAdvance() {
        cancelPendingFindYourWeaponAdvance()
        findYourWeaponAutoAdvanceController.reset()
    }

    private fun cancelPendingFindYourWeaponAdvance() {
        pendingFindYourWeaponAdvance?.let(mainHandler::removeCallbacks)
        pendingFindYourWeaponAdvance = null
        findYourWeaponAutoAdvanceController.cancelPending()
    }

    override fun onStop() {
        if (cameraSetupActive) closeCameraSetupSession()
        if (punchHeightActive) cancelPunchHeightSession()
        cancelJapaneseCountRecognitionRestart()
        trainingOrderPlayer?.stop()
        if (::japaneseCountFullExamplePlayer.isInitialized) {
            japaneseCountFullExamplePlayer.stop()
        }
        if (
            ::japaneseCountLiveRecognizer.isInitialized &&
            japaneseCountMode == LearningActivityType.TEST &&
            japaneseCountTrainingSession.phase in setOf(
                CountTrainingPhase.LISTENING,
                CountTrainingPhase.FINALIZING,
            )
        ) {
            japaneseCountLiveRecognizer.cancel()
            showJapaneseCountLevel2Error(
                error = CountRecognitionError.CLIENT,
                technicalMessage = "Recognition was cancelled because the activity stopped.",
            )
        }
        super.onStop()
    }

    override fun onDestroy() {
        cancelPendingFindYourWeaponAdvance()
        if (::japaneseCountLiveRecognizer.isInitialized) {
            japaneseCountLiveRecognizer.release()
        }
        if (::japaneseCountFullExamplePlayer.isInitialized) {
            japaneseCountFullExamplePlayer.release()
        }
        recognizerRunner?.close()
        recognizerRunner = null
        poseRecognizerRunner?.close()
        poseRecognizerRunner = null
        punchHeightVoiceCoach?.close()
        punchHeightVoiceCoach = null
        cameraSetupVoiceCoach?.close()
        cameraSetupVoiceCoach = null
        recognizerState = RecognizerLifecycleState.CLOSED
        recognizerExecutor.shutdownNow()
        punchHeightStorageExecutor.shutdownNow()
        punchHeightCaptureStore.cancelSession()
        recordingAdapter?.close()
        recordingAdapter = null
        trainingOrderPlayer?.release()
        trainingOrderPlayer = null
        super.onDestroy()
    }

    private fun updateRecordingState(state: RecordingState) {
        latestRecordingState = state
        recordingStateText.text = "Recording: ${state.name.lowercase()}"
        updateMainMenuAvailability()
        updateControlVisibility()
    }

    private fun updateGuidedState(state: GuidedSessionState) {
        latestGuidedState = state
        guidedSessionActive = state in ACTIVE_GUIDED_STATES
        statusText.text = "Status: ${state.name.lowercase()}"
        updateMainMenuAvailability()
        updateControlVisibility()
        TrainingOrderMapper.fromSessionState(state)?.let(::playTrainingOrder)
    }

    private fun updateJapaneseCountLevel1State(state: JapaneseCountLevel1State) {
        if (japaneseCountMode != LearningActivityType.PRACTICE) {
            updateControlVisibility()
            return
        }
        japaneseCountActive = true
        val item = state.item
        if (item != null) {
            statusText.text = "Japanese Counting — Practice"
            currentCountText.text = "Count: ${state.itemIndex + 1} / ${JapaneseCountLesson.items.size}"
            currentStrikeText.text = "Japanese: ${item.japanese}"
            expectedSideText.text = "Number: ${item.number}"
            japaneseCountText.text = "${item.number}\n${item.japanese}"
            japaneseCountFeedbackText.text = "${state.itemIndex + 1} of ${JapaneseCountLesson.items.size}"
            japaneseCountResultsText.text = ""
            japaneseCountBackButton.isEnabled = state.itemIndex > 0
            japaneseCountReplayButton.isEnabled = true
            japaneseCountNextButton.text = if (state.itemIndex == JapaneseCountLesson.items.lastIndex) "Finish" else "Next"
            playJapaneseCountPrompt(item)
        } else {
            trainingOrderPlayer?.stop()
            statusText.text = if (state.isComplete) "Japanese Counting practice complete" else "Status: idle"
            currentCountText.text = "Count: none"
            currentStrikeText.text = "Strike: none"
            expectedSideText.text = "Expected side: none"
            japaneseCountText.text = if (state.isComplete) "Practice complete" else ""
            japaneseCountFeedbackText.text = if (state.isComplete) "You practiced all ten numbers." else ""
            japaneseCountNextButton.text = "Next"
        }
        updateMainMenuAvailability()
        updateJapaneseCountDebugText()
        updateControlVisibility()
    }


    private fun updateFindYourWeaponState(state: FindYourWeaponState) {
        findYourWeaponActive = state.isActive
        val step = state.step
        val activeStep = if (state.isActive) step else null
        if (renderedFindYourWeaponStep != activeStep) {
            cancelPendingFindYourWeaponAdvance()
            findYourWeaponAutoAdvanceController.onStepChanged(activeStep)
            findYourWeaponKnuckleGuideVisible = false
            findYourWeaponFinishReady = false
            findYourWeaponProgressRingView.setProgress(0f, accepted = false)
            findYourWeaponMessageText.text = ""
            findYourWeaponCoachTextGate.reset()
            renderedFindYourWeaponStep = activeStep
        }
        handGuideOverlayView.visibility = View.GONE
        if (!state.isActive) {
            findYourWeaponDebugOverlayView.setOverlay(null)
            latestFindYourWeaponAnalysisState = null
            findYourWeaponCoachTextGate.reset()
        }
        analysisCoordinator?.setActiveStep(if (state.isActive) step else null)
        recordingAdapter?.setAnalysisEnabled(state.isActive && step != null)
        if (!state.isActive) { analysisCoordinator?.reset() }
        if (state.isActive && step != null) {
            val content = step.content()
            statusText.text = content.title
            currentCountText.text = content.instruction
            currentStrikeText.text = content.detail
            expectedSideText.text = "Step: ${content.stepNumber} / ${FindYourWeaponStep.entries.size}"
            findYourWeaponImage.setImageResource(content.imageResId)
            findYourWeaponBackButton.isEnabled = step != FindYourWeaponStep.OPEN_PALM
            findYourWeaponNextButton.text = if (step == FindYourWeaponStep.FRONT_TWO_KNUCKLES) "Finish" else "Next"
            updateFindYourWeaponCoachText(force = true)
        } else {
            statusText.text = if (state.isComplete) "Find Your Weapon complete" else "Status: idle"
            currentCountText.text = "Count: none"
            currentStrikeText.text = "Strike: none"
            expectedSideText.text = "Expected side: none"
            findYourWeaponImage.setImageDrawable(null)
            findYourWeaponNextButton.text = "Next"
            findYourWeaponFinishReady = false
            findYourWeaponProgressRingView.setProgress(0f, accepted = false)
            findYourWeaponMessageText.text = ""
            findYourWeaponCoachTextGate.reset()
        }
        updateMainMenuAvailability()
        updateControlVisibility()
    }

    private fun updateMainMenuAvailability() {
        val availability = mainMenuAvailability(
            recordingState = latestRecordingState,
            guidedSessionActive = guidedSessionActive,
            findYourWeaponActive = findYourWeaponActive,
            japaneseCountActive = japaneseCountActive,
            punchHeightActive = punchHeightActive,
            cameraSetupActive = cameraSetupActive,
        )
        startSessionButton.isEnabled = availability.cameraActionsEnabled
        findYourWeaponButton.isEnabled = availability.cameraActionsEnabled
        punchHeightButton.isEnabled = availability.cameraActionsEnabled
        countJapanesePracticeEntry.isEnabled = availability.countActionsEnabled
        countJapaneseTestEntry.isEnabled = availability.countActionsEnabled
        cancelSessionButton.isEnabled = availability.cancelEnabled
    }

    private fun updateControlVisibility() {
        val idle = !guidedSessionActive && !findYourWeaponActive && !japaneseCountActive && !punchHeightActive && !cameraSetupActive
        val active = guidedSessionActive || findYourWeaponActive || japaneseCountActive || punchHeightActive || cameraSetupActive
        val debugScope = currentDebugScope()

        startSessionButton.visibility = if (idle) View.VISIBLE else View.GONE
        findYourWeaponButton.visibility = if (idle) View.VISIBLE else View.GONE
        punchHeightButton.visibility = if (idle) View.VISIBLE else View.GONE
        countJapanesePracticeEntry.visibility = if (idle) View.VISIBLE else View.GONE
        countJapaneseTestEntry.visibility = if (idle) View.VISIBLE else View.GONE
        findYourWeaponImage.visibility = if (findYourWeaponActive && findYourWeaponController?.state?.step != null) View.VISIBLE else View.GONE
        findYourWeaponDebugOverlayView.visibility = if ((debugUiVisible || findYourWeaponKnuckleGuideVisible) && findYourWeaponActive && findYourWeaponController?.state?.step != null) View.VISIBLE else View.GONE
        findYourWeaponProgressRingView.visibility = if (shouldShowFindYourWeaponProgressRing()) View.VISIBLE else View.GONE
        findYourWeaponMessageText.visibility = if (findYourWeaponActive && findYourWeaponController?.state?.step != null) View.VISIBLE else View.GONE
        val practiceItemActive = japaneseCountMode == LearningActivityType.PRACTICE && japaneseCountLevel1Controller.state.item != null
        val testActive = japaneseCountMode == LearningActivityType.TEST
        val testPhase = japaneseCountTrainingSession.phase
        val testCanPlayExample = testPhase == CountTrainingPhase.READY ||
            testPhase == CountTrainingPhase.RESULT ||
            testPhase == CountTrainingPhase.ERROR
        japaneseCountText.visibility = if (japaneseCountActive) View.VISIBLE else View.GONE
        japaneseCountFeedbackText.visibility = if (japaneseCountActive) View.VISIBLE else View.GONE
        japaneseCountResultsText.visibility = if (testActive && japaneseCountTrainingSession.countResults.isNotEmpty()) View.VISIBLE else View.GONE
        japaneseCountBackButton.visibility = if (practiceItemActive) View.VISIBLE else View.GONE
        japaneseCountPlayButton.visibility = if (testActive && testCanPlayExample) View.VISIBLE else View.GONE
        japaneseCountReplayButton.visibility = if (practiceItemActive) View.VISIBLE else View.GONE
        japaneseCountNextButton.visibility = if (practiceItemActive) View.VISIBLE else View.GONE
        japaneseCountListenButton.visibility = if (testActive && testPhase == CountTrainingPhase.READY) View.VISIBLE else View.GONE
        japaneseCountFinishButton.visibility = if (testActive && testPhase == CountTrainingPhase.LISTENING) View.VISIBLE else View.GONE
        japaneseCountRetryButton.visibility = if (
            testActive && (testPhase == CountTrainingPhase.RESULT || testPhase == CountTrainingPhase.ERROR)
        ) View.VISIBLE else View.GONE
        findYourWeaponNextButton.visibility = if (findYourWeaponActive && shouldShowFindYourWeaponNextButton()) View.VISIBLE else View.GONE
        findYourWeaponBackButton.visibility = if (debugUiVisible && findYourWeaponActive) View.VISIBLE else View.GONE
        val cameraSetupCaptured = cameraSetupActive && latestCameraSetupState.stage == CameraSetupStage.CAPTURED && completedCameraSetupCapture != null
        val cameraSetupNeedsAdjustment = cameraSetupActive && latestCameraSetupState.stage == CameraSetupStage.ADJUST_CAMERA
        cameraSetupTitleText.visibleWhen(cameraSetupActive)
        cameraSetupMessageText.visibleWhen(cameraSetupActive)
        cameraSetupViewButtons.visibleWhen(cameraSetupActive && latestCameraSetupState.stage == CameraSetupStage.SELECT_VIEW)
        cameraSetupProgress.visibleWhen(cameraSetupActive && latestCameraSetupState.stage == CameraSetupStage.POSITIONING)
        cameraSetupCapturedImage.visibleWhen(cameraSetupCaptured)
        cameraSetupRetakeButton.text = if (cameraSetupNeedsAdjustment) "Camera adjusted - restart" else "Retake"
        cameraSetupRetakeButton.visibleWhen(cameraSetupCaptured || cameraSetupNeedsAdjustment)
        cameraSetupDoneButton.visibleWhen(cameraSetupCaptured)
        val punchHeightReviewVisible = punchHeightActive && latestPunchHeightState.stage == PunchHeightSessionStage.SESSION_REVIEW && completedPunchHeightSession != null
        punchHeightTitleText.visibleWhen(punchHeightActive)
        punchHeightMessageText.visibleWhen(punchHeightActive && !punchHeightReviewVisible)
        punchHeightProgress.visibleWhen(punchHeightActive && !punchHeightReviewVisible)
        val multiplierDebugVisible = debugUiVisible && punchHeightActive && latestPunchHeightState.targetType == PunchHeightTargetType.JODAN
        punchHeightMultiplierText.visibleWhen(multiplierDebugVisible)
        punchHeightMultiplierControls.visibleWhen(multiplierDebugVisible)
        punchHeightReviewLargeImage.visibleWhen(punchHeightReviewVisible)
        punchHeightReviewThumbnails.visibleWhen(punchHeightReviewVisible)
        punchHeightReviewExplanation.visibleWhen(punchHeightReviewVisible)
        punchHeightPracticeAgainButton.visibleWhen(punchHeightReviewVisible)
        punchHeightCloseButton.visibleWhen(punchHeightReviewVisible)
        punchHeightOverlayView.visibleWhen(punchHeightActive && !punchHeightReviewVisible)
        cancelSessionButton.text = if (japaneseCountActive || punchHeightReviewVisible || cameraSetupCaptured) "Close" else "Cancel Session"
        cancelSessionButton.visibility = if (
            japaneseCountActive ||
            (cameraSetupActive && !cameraSetupCaptured) ||
            (punchHeightActive && !punchHeightReviewVisible) ||
            (debugUiVisible && active && !punchHeightReviewVisible)
        ) View.VISIBLE else View.GONE

        debugScopeText.text = "Debug: ${debugScope.label}"
        debugScopeText.visibleWhen(debugUiVisible)
        statusText.visibleWhen(debugUiVisible && debugScope != DebugScope.CAMERA)
        currentCountText.visibleWhen(debugUiVisible && debugScope != DebugScope.CAMERA)
        currentStrikeText.visibleWhen(debugUiVisible && debugScope != DebugScope.CAMERA)
        expectedSideText.visibleWhen(debugUiVisible && debugScope != DebugScope.CAMERA)
        recordingStateText.visibleWhen(debugUiVisible && debugScope != DebugScope.FIND_YOUR_WEAPON)
        savedClipText.visibleWhen(debugUiVisible && debugScope != DebugScope.FIND_YOUR_WEAPON)
        captureProfileText.visibleWhen(debugUiVisible && debugScope != DebugScope.FIND_YOUR_WEAPON)
        analyzerDebugText.visibleWhen(debugUiVisible && (debugScope == DebugScope.FIND_YOUR_WEAPON || debugScope == DebugScope.COUNT_JAPANESE || debugScope == DebugScope.PUNCH_HEIGHTS))
        metadataPathText.visibleWhen(debugUiVisible && debugScope != DebugScope.FIND_YOUR_WEAPON)
    }

    private fun currentDebugScope(): DebugScope = when {
        cameraSetupActive -> DebugScope.PUNCH_HEIGHTS
        punchHeightActive -> DebugScope.PUNCH_HEIGHTS
        japaneseCountActive -> DebugScope.COUNT_JAPANESE
        findYourWeaponActive -> DebugScope.FIND_YOUR_WEAPON
        guidedSessionActive -> DebugScope.GUIDED_SESSION
        else -> DebugScope.CAMERA
    }

    private fun View.visibleWhen(visible: Boolean) {
        visibility = if (visible) View.VISIBLE else View.GONE
    }

    private fun shouldShowFindYourWeaponNextButton(): Boolean {
        val step = findYourWeaponController?.state?.step
        return step != FindYourWeaponStep.FRONT_TWO_KNUCKLES || findYourWeaponFinishReady
    }

    private fun shouldShowFindYourWeaponProgressRing(): Boolean {
        val state = findYourWeaponController?.state
        val step = state?.step
        return findYourWeaponActive &&
            state?.isActive == true &&
            step != null &&
            step != FindYourWeaponStep.FRONT_TWO_KNUCKLES
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
        latestFindYourWeaponAnalysisState = state
        updateFindYourWeaponProgress(state)
        updateFindYourWeaponFinishState(state)
        updateFindYourWeaponCoachText(state)
        updateFindYourWeaponOverlay(state)
        maybeScheduleFindYourWeaponAutoAdvance(state)
        analyzerDebugText.text = if (state.errorMessage != null) {
            "Analyzer error: ${state.errorMessage}"
        } else if (state.recognizerState == RecognizerLifecycleState.INITIALIZING) {
            "Analyzer: initializing"
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
                "Thumb open: ${state.thumbOpenScore?.format2() ?: "--"}",
                "Thumb closed: ${state.thumbClosedScore?.format2() ?: "--"}",
                "Thumb distance: ${state.thumbFingerDistanceRatio?.format2() ?: "--"}",
                "Thumb inside: ${state.thumbInsideBoundary?.let { it.toString() } ?: "--"}",
                "Thumb line: ${state.thumbInsideBoundaryRatio?.format2() ?: "--"}",
                "Thumb past middle: ${state.thumbInsideMiddleBoundary?.let { it.toString() } ?: "--"}",
                "Middle line: ${state.thumbInsideMiddleBoundaryRatio?.format2() ?: "--"}",
                "Timestamp: ${state.timestampMs ?: "--"}",
                "Frames: submitted=${submittedFrameCount.get()} processed=${processedFrameCount.get()} dropped=${droppedFrameCount.get()}",
                "Recognizer: ${state.recognizerState.name.lowercase()}",
            ).joinToString("\n")
        }
    }

    private fun updateFindYourWeaponProgress(state: FindYourWeaponAnalysisState) {
        val currentState = findYourWeaponController?.state
        val currentStep = currentState?.step
        if (
            currentState?.isActive != true ||
            currentStep == null ||
            currentStep == FindYourWeaponStep.FRONT_TWO_KNUCKLES ||
            state.activeStep != currentStep
        ) {
            findYourWeaponProgressRingView.setProgress(0f, accepted = false)
            return
        }
        findYourWeaponProgressRingView.setProgress(
            progress = state.temporalResult?.progress ?: 0f,
            accepted = state.temporalResult?.accepted == true,
        )
    }

    private fun updateFindYourWeaponFinishState(state: FindYourWeaponAnalysisState) {
        val currentState = findYourWeaponController?.state
        val currentStep = currentState?.step
        if (
            currentState?.isActive == true &&
            currentStep == FindYourWeaponStep.FRONT_TWO_KNUCKLES &&
            state.activeStep == currentStep &&
            state.temporalResult?.accepted == true
        ) {
            findYourWeaponFinishReady = true
        }
    }

    private fun updateFindYourWeaponCoachText(
        state: FindYourWeaponAnalysisState? = latestFindYourWeaponAnalysisState,
        force: Boolean = false,
    ) {
        val currentStep = findYourWeaponController?.state?.step
        findYourWeaponMessageText.text = if (findYourWeaponActive && currentStep != null) {
            val candidateText = FindYourWeaponCoachCopy.messageText(
                step = currentStep,
                state = state,
                finalAccepted = findYourWeaponFinishReady,
            )
            findYourWeaponCoachTextGate.displayText(
                candidateText = candidateText,
                nowMs = SystemClock.uptimeMillis(),
                force = force,
            )
        } else {
            findYourWeaponCoachTextGate.reset()
            ""
        }
    }

    private fun updateFindYourWeaponOverlay(state: FindYourWeaponAnalysisState?) {
        val currentStep = findYourWeaponController?.state?.step
        val overlay = state?.debugOverlay
        val analyzerMatchesCurrentStep = currentStep != null && state?.activeStep == currentStep
        val showDebugGuides = debugUiVisible && findYourWeaponActive && analyzerMatchesCurrentStep
        findYourWeaponKnuckleGuideVisible = findYourWeaponActive &&
            currentStep == FindYourWeaponStep.FRONT_TWO_KNUCKLES &&
            analyzerMatchesCurrentStep &&
            overlay?.highlightPoints?.isNotEmpty() == true
        findYourWeaponDebugOverlayView.setOverlay(
            overlay = if (showDebugGuides || findYourWeaponKnuckleGuideVisible) overlay else null,
            showDebugGuides = showDebugGuides,
        )
        updateControlVisibility()
    }

    private fun maybeScheduleFindYourWeaponAutoAdvance(state: FindYourWeaponAnalysisState) {
        val currentState = findYourWeaponController?.state
        if (currentState?.step == FindYourWeaponStep.FRONT_TWO_KNUCKLES) return
        val decision = findYourWeaponAutoAdvanceController.onAnalysis(
            state = state,
            currentStep = currentState?.step,
            isSessionActive = currentState?.isActive == true,
        )
        if (decision !is FindYourWeaponAutoAdvanceDecision.Schedule) return

        pendingFindYourWeaponAdvance?.let(mainHandler::removeCallbacks)
        val advance = Runnable {
            pendingFindYourWeaponAdvance = null
            val latestState = findYourWeaponController?.state
            if (findYourWeaponAutoAdvanceController.consumePendingAdvance(latestState?.step, latestState?.isActive == true)) {
                findYourWeaponController?.next()
            }
        }
        pendingFindYourWeaponAdvance = advance
        mainHandler.postDelayed(advance, decision.delayMs)
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
        if (appPreferences.trainingSounds) trainingOrderPlayer?.play(order)
    }


    private fun FindYourWeaponStep.content(): FindYourWeaponStepContent = when (this) {
        FindYourWeaponStep.OPEN_PALM -> FindYourWeaponStepContent(
            stepNumber = 1,
            title = "Find Your Weapon",
            instruction = "Place your open palm inside the bright hand guide.",
            detail = "Keep your fingers open and face your palm toward the camera.",
            imageResId = R.drawable.find_weapon_01_open_palm,
        )
        FindYourWeaponStep.BEND_FINGERTIPS -> FindYourWeaponStepContent(
            stepNumber = 2,
            title = "Find Your Weapon",
            instruction = "Bend the top parts of your fingers.",
            detail = "Start by folding the fingertips.",
            imageResId = R.drawable.find_weapon_02_bend_fingertips,
        )
        FindYourWeaponStep.CLOSE_FINGERS -> FindYourWeaponStepContent(
            stepNumber = 3,
            title = "Find Your Weapon",
            instruction = "Close your fingers into your palm.",
            detail = "Make the fist shape.",
            imageResId = R.drawable.find_weapon_03_close_fingers,
        )
        FindYourWeaponStep.THUMB_ON_TOP -> FindYourWeaponStepContent(
            stepNumber = 4,
            title = "Find Your Weapon",
            instruction = "Place your thumb across the front of your fingers.",
            detail = "Keep the fist firm but relaxed.",
            imageResId = R.drawable.find_weapon_04_thumb_on_top,
        )
        FindYourWeaponStep.FRONT_TWO_KNUCKLES -> FindYourWeaponStepContent(
            stepNumber = 5,
            title = "Find Your Weapon",
            instruction = "These two front knuckles are your weapon.",
            detail = "Aim with the index and middle knuckles.",
            imageResId = R.drawable.find_weapon_05_front_two_knuckles,
        )
    }

    private data class FindYourWeaponStepContent(
        val stepNumber: Int,
        val title: String,
        val instruction: String,
        val detail: String,
        val imageResId: Int,
    )

    companion object {
        private const val STATE_APP_DESTINATION = "app_destination"
        private const val JAPANESE_COUNT_LOG_TAG = "JapaneseCountTraining"
        private const val MAX_JAPANESE_PARTIAL_TRANSCRIPTS = 20
        private const val JAPANESE_COUNT_BUSY_RETRY_DELAY_MS = 250L
        private const val PUNCH_HEIGHT_CAPTURE_PAUSE_MS = 800L
        private const val MAX_CONTROLS_HEIGHT_RATIO = 0.82f

        private val ACTIVE_GUIDED_STATES = setOf(
            GuidedSessionState.READY,
            GuidedSessionState.YOI,
            GuidedSessionState.PROMPTING_STRIKE,
            GuidedSessionState.RECORDING,
            GuidedSessionState.SAVING,
        )
    }
}

private enum class DebugScope(val label: String) {
    CAMERA("camera"),
    GUIDED_SESSION("guided session"),
    COUNT_JAPANESE("count to 10"),
    FIND_YOUR_WEAPON("Find Your Weapon"),
    PUNCH_HEIGHTS("Punch Heights - Level 1"),
}
