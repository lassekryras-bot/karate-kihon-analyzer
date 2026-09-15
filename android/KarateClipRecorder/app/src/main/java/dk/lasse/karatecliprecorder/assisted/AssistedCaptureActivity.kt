package dk.lasse.karatecliprecorder.assisted

import android.Manifest
import android.content.Intent
import android.content.pm.ActivityInfo
import android.content.pm.PackageManager
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.media.MediaRouter
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.provider.Settings
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.widget.*
import android.widget.LinearLayout.LayoutParams
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import dk.lasse.karatecliprecorder.*
import dk.lasse.karatecliprecorder.captureprofile.CaptureQuality
import dk.lasse.karatecliprecorder.captureprofile.RearLens
import dk.lasse.karatecliprecorder.captureprofile.VideoQualityTier
import dk.lasse.karatecliprecorder.orders.SoundFileTrainingOrderPlayer
import dk.lasse.karatecliprecorder.orders.TrainingOrder
import dk.lasse.karatecliprecorder.profile.BodyMeasurementSnapshot
import dk.lasse.karatecliprecorder.profile.ProfileRepository
import dk.lasse.karatecliprecorder.sharedcapture.*
import dk.lasse.karatecliprecorder.training.*
import dk.lasse.karatecliprecorder.recordings.QueueManager
import dk.lasse.karatecliprecorder.recordings.QueueManagerTrayView
import java.util.Locale

/**
 * Record & Analyze host page under Skill Coach.
 * Hosts the shared camera preview, user-configurable recording setup card,
 * and the conventional circular Record / Stop camera control.
 */
class AssistedCaptureActivity : AppCompatActivity() {
    private val profiles by lazy { ProfileRepository(this, AppPreferences(this)) }
    private val preferences by lazy { TrainingPreferences(this) }
    private lateinit var controller: AssistedCaptureController
    private lateinit var player: SoundFileTrainingOrderPlayer
    private lateinit var cameraPreviewView: SharedCameraPreviewView
    private lateinit var recordButton: SharedCameraRecordButton
    private lateinit var permissionButton: Button
    private lateinit var topNavigation: View
    private lateinit var setupCard: LinearLayout
    private lateinit var viewRecordingButton: View
    private lateinit var setup: AssistedCaptureSetup

    private var camera: CameraXRecordingAdapter? = null
    private var cameraReady = false
    private var selectedSession: String? = null
    private var disposed = false
    private var renderedState: AssistedCaptureState? = null
    private var qualities = emptyList<CaptureQuality>()
    private var quality: CaptureQuality? = null
    private var automaticQuality = true
    private var zoom = 1f
    private var minZoom = 1f
    private var maxZoom = 1f
    private var lenses = emptyList<RearLens>()
    private var lensId: String? = null
    private var playbackDialog: LandmarkPlaybackDialog? = null

    private var recordingStartRealtime = 0L
    private var lastCueOrdinal = 0

    private val routeHandler = Handler(Looper.getMainLooper())
    private val routeRefresh = object : Runnable {
        override fun run() {
            if (!disposed) {
                updateRuntimeStatus()
                routeHandler.postDelayed(this, 1000)
            }
        }
    }

    private val permissionLauncher = registerForActivityResult(ActivityResultContracts.RequestPermission()) { allowed ->
        if (allowed) {
            bindCamera()
        } else {
            permissionButton.visibility = View.VISIBLE
            updateRuntimeStatus()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setup = preferences.read(profiles.activeProfile().id).copy(
            expectedActivity = savedInstanceState?.getString("activity")
                ?: intent.getStringExtra("expectedActivity")
                ?: "Alternating straight punches",
            expectedCategory = savedInstanceState?.getString("category")
                ?: intent.getStringExtra("expectedCategory")
                ?: "Punches"
        )
        player = SoundFileTrainingOrderPlayer(this)
        controller = AssistedCaptureController(
            prepare = { request, ready ->
                selectedSession = null
                camera?.prepareSharedCapture(request, ready) ?: controller.fail("Camera is not ready")
            },
            stopCamera = { camera?.stopRecording() },
            playCount = { value -> player.playImmediately(TrainingOrder.valueOf("COUNT_$value")) },
            stopAudio = player::stop,
            persistCue = { value, ordinal, time ->
                lastCueOrdinal = ordinal
                camera?.recordCountCue(value, ordinal, time)
            },
            changed = ::renderState,
            persistBoundary = { type, time, reason -> camera?.recordBoundary(type, time, reason) },
            playPrompt = { prompt ->
                CapturePromptCatalog.trainingOrder(prompt)?.let(player::play)
            },
        )

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(0xFF11161B.toInt()) // Soft dark camera studio background
        }

        ViewCompat.setOnApplyWindowInsetsListener(root) { view, insets ->
            val bars = insets.getInsets(
                WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.displayCutout()
            )
            view.setPadding(bars.left + 16.dp(), bars.top + 8.dp(), bars.right + 16.dp(), bars.bottom + 12.dp())
            insets
        }

        // Top Navigation: Back arrow on left, centered "Record & Analyze" title
        topNavigation = buildTopNavigation()
        root.addView(topNavigation)
        root.addView(QueueManagerTrayView(this), LinearLayout.LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT))

        // Shared Camera Preview View
        cameraPreviewView = SharedCameraPreviewView(this).apply {
            onSettingsClick = { openCameraSettings() }
            onAudioRouteClick = { openAudioRouteChooser() }
            onFocusTap = { x, y -> camera?.focus(x, y) }
            onStatusTap = { reason -> handleStatusTap(reason) }
        }
        root.addView(cameraPreviewView, LinearLayout.LayoutParams(LayoutParams.MATCH_PARENT, 0, 1f))

        // Recording setup card
        setupCard = buildSetupCard()
        root.addView(setupCard, LinearLayout.LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT).apply {
            topMargin = 12.dp()
            bottomMargin = 8.dp()
        })

        // Camera Permission recovery button (hidden by default)
        permissionButton = Button(this).apply {
            text = "Allow camera"
            setTextColor(Color.WHITE)
            background = GradientDrawable().apply {
                setColor(0xFFEF4444.toInt())
                cornerRadius = 8.dp().toFloat()
            }
            visibility = View.GONE
            setOnClickListener { requestCamera() }
        }
        root.addView(permissionButton, LinearLayout.LayoutParams(LayoutParams.MATCH_PARENT, 48.dp()).apply {
            bottomMargin = 8.dp()
        })

        // Bottom action area: Centered Record/Stop control + optional View Recording button
        val bottomActionArea = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
        }

        recordButton = SharedCameraRecordButton(this).apply {
            isEnabled = false
            onAction = { btnState ->
                when (btnState) {
                    CameraButtonState.RECORD -> {
                        val resources = ProcessingPolicy.snapshot(this@AssistedCaptureActivity)
                        val blocked = ProcessingPolicy.recordingBlock(
                            resources,
                            ProcessingPreferences(this@AssistedCaptureActivity).minimumBattery
                        )
                        if (blocked != null) {
                            Toast.makeText(this@AssistedCaptureActivity, blocked, Toast.LENGTH_LONG).show()
                        } else {
                            preferences.save(profiles.activeProfile().id, setup)
                            controller.record(
                                SharedCaptureRequests.recordAndAnalyze(
                                    setup.expectedActivity,
                                    setup.expectedCategory,
                                    setup.repetitions,
                                    setup.cadenceMs,
                                    setup.spokenCounting,
                                )
                            )
                        }
                    }
                    CameraButtonState.STOP -> controller.stop()
                    CameraButtonState.STOP_NOW -> controller.stop()
                    CameraButtonState.SAVED -> controller.recordAnother()
                }
            }
        }
        bottomActionArea.addView(recordButton, LinearLayout.LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT).apply {
            gravity = Gravity.CENTER_HORIZONTAL
            topMargin = 4.dp()
            bottomMargin = 4.dp()
        })

        viewRecordingButton = TextView(this).apply {
            text = "View recording"
            textSize = 14f
            setTextColor(Color.WHITE)
            gravity = Gravity.CENTER
            typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
            background = GradientDrawable().apply {
                setColor(0xFF242E3C.toInt())
                cornerRadius = 10.dp().toFloat()
                setStroke(1.dp(), 0x33FFFFFF.toInt())
            }
            setPadding(20.dp(), 10.dp(), 20.dp(), 10.dp())
            visibility = View.GONE
            setOnClickListener { selectedSession?.let(::openRecording) }
        }
        bottomActionArea.addView(viewRecordingButton, LinearLayout.LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT).apply {
            gravity = Gravity.CENTER_HORIZONTAL
            topMargin = 6.dp()
        })

        root.addView(bottomActionArea)

        setContentView(root)

        onBackPressedDispatcher.addCallback(this, object : androidx.activity.OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                controller.interrupt("back_navigation")
                finish()
            }
        })

        requestCamera()
    }

    private fun buildTopNavigation(): View {
        return FrameLayout(this).apply {
            val backButton = ImageButton(this@AssistedCaptureActivity).apply {
                setImageResource(R.drawable.ic_tabler_arrow_left)
                imageTintList = android.content.res.ColorStateList.valueOf(Color.WHITE)
                background = null
                contentDescription = "Back to Skill Coach"
                setPadding(12.dp(), 12.dp(), 12.dp(), 12.dp())
                setOnClickListener {
                    controller.interrupt("back_navigation")
                    finish()
                }
            }
            addView(backButton, FrameLayout.LayoutParams(48.dp(), 48.dp(), Gravity.START or Gravity.CENTER_VERTICAL))

            val titleView = TextView(this@AssistedCaptureActivity).apply {
                text = "Record & Analyze"
                textSize = 19f
                setTextColor(Color.WHITE)
                typeface = Typeface.create("sans-serif", Typeface.BOLD)
                gravity = Gravity.CENTER
            }
            addView(titleView, FrameLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.CENTER))

            minimumHeight = 48.dp()
        }
    }

    private fun buildSetupCard(): LinearLayout {
        return LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            background = GradientDrawable().apply {
                setColor(0xFF1A222C.toInt())
                cornerRadius = 16.dp().toFloat()
                setStroke(1.dp(), 0xFF283545.toInt())
            }
            setPadding(16.dp(), 14.dp(), 16.dp(), 14.dp())

            // Card Header: Gear icon + "Recording setup"
            val header = LinearLayout(this@AssistedCaptureActivity).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                val icon = AppIconView(this@AssistedCaptureActivity, AppIcon.SETTINGS, sizeDp = 20).apply {
                    setIconColor(Color.WHITE)
                }
                addView(icon, LayoutParams(20.dp(), 20.dp()).apply { marginEnd = 10.dp() })
                val title = TextView(this@AssistedCaptureActivity).apply {
                    text = "Recording setup"
                    textSize = 16.5f
                    setTextColor(Color.WHITE)
                    typeface = Typeface.create("sans-serif", Typeface.BOLD)
                }
                addView(title)
            }
            addView(header, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT).apply {
                bottomMargin = 14.dp()
            })

            // Row 1: Activity
            val activityRow = buildSettingRow(
                icon = AppIcon.KARATE,
                label = "Activity",
                content = buildPillButton(setup.expectedActivity) {
                    val names = arrayOf("Alternating straight punches", "Front kicks", "Other karate movements")
                    AlertDialog.Builder(this@AssistedCaptureActivity)
                        .setTitle("Expected activity")
                        .setItems(names) { _, index ->
                            setup = setup.copy(
                                expectedActivity = names[index],
                                expectedCategory = listOf("Punches", "Kicks", "Other")[index]
                            )
                            preferences.save(profiles.activeProfile().id, setup)
                            renderSetupCard()
                        }.show()
                }
            )
            addView(activityRow)

            // Row 2: Planned
            val plannedRow = buildSettingRow(
                icon = AppIcon.CHART_BAR,
                label = "Planned",
                content = buildStepper(
                    valueText = setup.repetitions.toString(),
                    onDecrease = {
                        setup = setup.copy(repetitions = (setup.repetitions - 1).coerceAtLeast(1))
                        preferences.save(profiles.activeProfile().id, setup)
                        renderSetupCard()
                    },
                    onIncrease = {
                        setup = setup.copy(repetitions = (setup.repetitions + 1).coerceAtMost(1000))
                        preferences.save(profiles.activeProfile().id, setup)
                        renderSetupCard()
                    }
                )
            )
            addView(plannedRow)

            // Row 3: Cadence
            val cadenceRow = buildSettingRow(
                icon = AppIcon.CLOCK,
                label = "Cadence",
                content = buildStepper(
                    valueText = String.format(Locale.US, "%.1f s", setup.cadenceMs / 1000.0),
                    onDecrease = {
                        setup = setup.copy(cadenceMs = (setup.cadenceMs - 100).coerceAtLeast(AssistedCaptureSetup.MIN_CADENCE_MS))
                        preferences.save(profiles.activeProfile().id, setup)
                        renderSetupCard()
                    },
                    onIncrease = {
                        setup = setup.copy(cadenceMs = (setup.cadenceMs + 100).coerceAtMost(10_000))
                        preferences.save(profiles.activeProfile().id, setup)
                        renderSetupCard()
                    }
                )
            )
            addView(cadenceRow)

            // Row 4: Cue mode
            val cueModeRow = buildSettingRow(
                icon = AppIcon.VOLUME,
                label = "Cue mode",
                content = buildPillButton("App cues") {
                    val choices = arrayOf(
                        "App cues (spoken movement cues)",
                        "Self-cued — not available yet",
                        "Automatic movement count — not available yet"
                    )
                    AlertDialog.Builder(this@AssistedCaptureActivity)
                        .setTitle("Cue mode")
                        .setItems(choices) { _, index ->
                            if (index > 0) {
                                val block = if (index == 1) CaptureStartBlock.UNSUPPORTED_SELF_CUED else CaptureStartBlock.UNSUPPORTED_FREE_AUTO_COUNT
                                Toast.makeText(this@AssistedCaptureActivity, block.message, Toast.LENGTH_LONG).show()
                            }
                        }.show()
                }
            )
            addView(cueModeRow)
        }
    }

    private fun buildSettingRow(icon: AppIcon, label: String, content: View): View {
        return LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            minimumHeight = 44.dp()
            setPadding(0, 4.dp(), 0, 4.dp())

            val iconView = AppIconView(this@AssistedCaptureActivity, icon, sizeDp = 20).apply {
                setIconColor(0xFF94A3B8.toInt())
            }
            addView(iconView, LayoutParams(20.dp(), 20.dp()).apply { marginEnd = 12.dp() })

            val labelView = TextView(this@AssistedCaptureActivity).apply {
                text = label
                textSize = 15f
                setTextColor(Color.WHITE)
            }
            addView(labelView, LayoutParams(0, LayoutParams.WRAP_CONTENT, 1f))

            addView(content)
        }
    }

    private fun buildPillButton(text: String, onClick: () -> Unit): View {
        return LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            background = GradientDrawable().apply {
                setColor(0xFF242E3C.toInt())
                cornerRadius = 10.dp().toFloat()
            }
            setPadding(12.dp(), 7.dp(), 10.dp(), 7.dp())

            val textView = TextView(this@AssistedCaptureActivity).apply {
                this.text = text
                textSize = 13.5f
                setTextColor(Color.WHITE)
            }
            addView(textView)

            val chevron = AppIconView(this@AssistedCaptureActivity, AppIcon.CHEVRON_RIGHT, sizeDp = 16).apply {
                setIconColor(0xFF94A3B8.toInt())
            }
            addView(chevron, LayoutParams(16.dp(), 16.dp()).apply { marginStart = 6.dp() })

            setOnClickListener { onClick() }
        }
    }

    private fun buildStepper(valueText: String, onDecrease: () -> Unit, onIncrease: () -> Unit): View {
        return LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL

            val minusBtn = TextView(this@AssistedCaptureActivity).apply {
                text = "−"
                textSize = 17f
                setTextColor(Color.WHITE)
                gravity = Gravity.CENTER
                background = GradientDrawable().apply {
                    setColor(0xFF242E3C.toInt())
                    cornerRadius = 8.dp().toFloat()
                }
                setOnClickListener { onDecrease() }
            }
            addView(minusBtn, LayoutParams(42.dp(), 34.dp()))

            val display = TextView(this@AssistedCaptureActivity).apply {
                text = valueText
                textSize = 14f
                setTextColor(Color.WHITE)
                typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
                gravity = Gravity.CENTER
                background = GradientDrawable().apply {
                    setColor(0xFF171F2A.toInt())
                    cornerRadius = 8.dp().toFloat()
                }
            }
            addView(display, LayoutParams(58.dp(), 34.dp()).apply {
                marginStart = 6.dp()
                marginEnd = 6.dp()
            })

            val plusBtn = TextView(this@AssistedCaptureActivity).apply {
                text = "+"
                textSize = 17f
                setTextColor(Color.WHITE)
                gravity = Gravity.CENTER
                background = GradientDrawable().apply {
                    setColor(0xFF242E3C.toInt())
                    cornerRadius = 8.dp().toFloat()
                }
                setOnClickListener { onIncrease() }
            }
            addView(plusBtn, LayoutParams(42.dp(), 34.dp()))
        }
    }

    private fun renderSetupCard() {
        val parent = setupCard.parent as? ViewGroup ?: return
        val index = parent.indexOfChild(setupCard)
        parent.removeView(setupCard)
        setupCard = buildSetupCard()
        parent.addView(setupCard, index, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
            topMargin = 12.dp()
            bottomMargin = 8.dp()
        })
    }

    private fun requestCamera() {
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED) {
            bindCamera()
        } else {
            val history = getSharedPreferences("assisted_permission", MODE_PRIVATE)
            val route = PermissionRequestPolicy.route(
                false,
                history.getBoolean("camera", false),
                androidx.core.app.ActivityCompat.shouldShowRequestPermissionRationale(this, Manifest.permission.CAMERA)
            )
            if (route == PermissionRequestRoute.APP_SETTINGS) {
                permissionButton.text = "Open camera settings"
                permissionButton.visibility = View.VISIBLE
                permissionButton.setOnClickListener {
                    startActivity(
                        Intent(
                            Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                            android.net.Uri.parse("package:$packageName")
                        )
                    )
                }
            } else {
                history.edit().putBoolean("camera", true).apply()
                permissionLauncher.launch(Manifest.permission.CAMERA)
            }
        }
    }

    private fun bindCamera() {
        if (disposed || camera != null) return
        permissionButton.visibility = View.GONE
        camera = CameraXRecordingAdapter(
            context = this,
            lifecycleOwner = this,
            previewView = cameraPreviewView.preview,
            onStateChanged = { state ->
                cameraReady = state != RecordingState.PREPARING && state != RecordingState.FAILED
                if (!controller.captureActive) {
                    recordButton.isEnabled = cameraReady
                }
                updateRuntimeStatus()
            },
            onSaved = { result ->
                if (!disposed) {
                    selectedSession = result.sessionId
                    controller.saved()
                }
            },
            onError = { if (!disposed) controller.fail(it) },
            onRecordingStarted = { startMs ->
                recordingStartRealtime = SystemClock.elapsedRealtime()
                lastCueOrdinal = 0
                controller.recordingStarted(startMs)
            },
            onRecordingFinalizing = controller::recordingFinalizing,
            previewOnly = true,
            onCameraOptions = { supported, chosen, low, high ->
                qualities = supported
                quality = chosen
                minZoom = low
                maxZoom = high
                updateQualityBadge()
            },
            onRearLenses = { available, selected ->
                lenses = available
                lensId = selected
            },
            bodyMeasurements = { BodyMeasurementSnapshot.from(profiles.activeProfile()) },
        ).also { it.bindCameraPreview() }
    }

    private fun updateQualityBadge() {
        val chosen = quality
        if (chosen != null) {
            cameraPreviewView.setQualityBadge(chosen.toString())
            return
        }
        val profile = camera?.selectedCaptureProfile
        val tierLabel = when (profile?.selectedQualityTier) {
            VideoQualityTier.UHD -> "4K"
            VideoQualityTier.FHD -> "1080p"
            VideoQualityTier.HD -> "720p"
            VideoQualityTier.SD -> "480p"
            else -> "1080p"
        }
        val fps = profile?.preferredTargetFps ?: 60
        cameraPreviewView.setQualityBadge("$tierLabel · $fps fps")
    }

    private fun openCameraSettings() {
        val ratios = (listOf(minZoom, 1f, 2f, 3f).filter { it in minZoom..maxZoom }).distinct()
        CameraSettingsSheet(
            context = this,
            lenses = lenses,
            selectedLensId = lensId,
            zoomRatios = ratios,
            currentZoom = zoom,
            qualities = qualities,
            selectedQuality = quality,
            automaticQuality = automaticQuality,
            onLensSelected = { id ->
                lensId = id
                zoom = 1f
                automaticQuality = true
                camera?.setRearLens(id)
                updateQualityBadge()
            },
            onZoomSelected = { ratio ->
                zoom = ratio
                camera?.setZoom(zoom)
            },
            onQualitySelected = { q, auto ->
                automaticQuality = auto
                camera?.setCaptureQuality(if (auto) null else q)
                updateQualityBadge()
            }
        ).show()
    }

    private fun openAudioRouteChooser() {
        AudioOutputChooserDialog(
            context = this,
            onConnectDevice = {
                runCatching { startActivity(Intent(Settings.ACTION_BLUETOOTH_SETTINGS)) }
                    .onFailure { Toast.makeText(this, "Android audio settings are unavailable.", Toast.LENGTH_LONG).show() }
            }
        ).show()
    }

    private fun handleStatusTap(reason: String?) {
        when (reason) {
            "request_camera" -> requestCamera()
            "storage_blocker", "storage_warning" -> {
                Toast.makeText(this, "Storage is low. Free space to prevent recording interruptions.", Toast.LENGTH_LONG).show()
            }
            "battery_blocker" -> {
                val threshold = ProcessingPreferences(this).minimumBattery
                Toast.makeText(this, "Connect a charger: battery is below $threshold%.", Toast.LENGTH_LONG).show()
            }
        }
    }

    private fun updateRuntimeStatus() {
        val router = getSystemService(MEDIA_ROUTER_SERVICE) as? MediaRouter
        val route = router?.getSelectedRoute(MediaRouter.ROUTE_TYPE_LIVE_AUDIO)
        val isHeadset = route?.name?.toString()?.contains("Bluetooth", ignoreCase = true) == true
            || (route?.deviceType == MediaRouter.RouteInfo.DEVICE_TYPE_BLUETOOTH)
        cameraPreviewView.setAudioRoute(isHeadset)

        val resources = ProcessingPolicy.snapshot(this)
        val minBattery = ProcessingPreferences(this).minimumBattery

        val status = when {
            resources.freeBytes < ProcessingPolicy.HARD_STORAGE_BYTES ->
                CameraStatusMessage.storageBlocker("Low storage")
            !ProcessingPolicy.mayStart(resources, minBattery) ->
                CameraStatusMessage.batteryBlocker("Battery below start threshold")
            ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA) != PackageManager.PERMISSION_GRANTED ->
                CameraStatusMessage.missingRequirement("Camera access required", "request_camera")
            resources.freeBytes < ProcessingPolicy.WARN_STORAGE_BYTES ->
                CameraStatusMessage.missingRequirement("Storage is getting low", "storage_warning")
            !cameraReady ->
                CameraStatusMessage.informational("Preparing camera…")
            else ->
                CameraStatusMessage.ready()
        }
        cameraPreviewView.setStatus(status)

        val blocked = ProcessingPolicy.recordingBlock(resources, minBattery)
        if (::recordButton.isInitialized && !controller.captureActive) {
            recordButton.isEnabled = cameraReady && blocked == null
        }
    }

    private fun renderState(state: AssistedCaptureState, text: String) {
        if (disposed) return
        val active = controller.captureActive
        QueueManager.setRecordingHidden(state == AssistedCaptureState.RECORDING || state == AssistedCaptureState.FINISHING)

        if (state == AssistedCaptureState.COUNTDOWN) {
            cameraPreviewView.showCountdown(text)
        } else {
            cameraPreviewView.hideCountdown()
        }

        if (state == AssistedCaptureState.RECORDING || state == AssistedCaptureState.FINISHING) {
            val elapsedSec = if (recordingStartRealtime > 0) {
                (SystemClock.elapsedRealtime() - recordingStartRealtime).coerceAtLeast(0) / 1000
            } else 0
            val formattedTime = String.format(Locale.US, "%02d:%02d", elapsedSec / 60, elapsedSec % 60)
            val isFinishing = state == AssistedCaptureState.FINISHING
            cameraPreviewView.setRecordingProgress(
                stateLabel = if (isFinishing) "Finishing" else "Recording",
                currentCue = lastCueOrdinal,
                totalPlanned = setup.repetitions,
                formattedTime = formattedTime
            )
        }

        if (state == AssistedCaptureState.FINALIZING) {
            cameraPreviewView.setSavingStatus("Saving recording…", isComplete = false)
        } else if (state == AssistedCaptureState.SAVED) {
            cameraPreviewView.setSavingStatus("Recording saved ✓", isComplete = true)
        }

        if (renderedState == state) return
        renderedState = state

        // Layout expansion and visibility transitions
        topNavigation.visibility = if (active) View.GONE else View.VISIBLE
        setupCard.visibility = if (active || state == AssistedCaptureState.SAVED) View.GONE else View.VISIBLE
        cameraPreviewView.setRecordingActive(active, isFinishing = state == AssistedCaptureState.FINISHING)

        recordButton.state = when (state) {
            AssistedCaptureState.FINISHING -> CameraButtonState.STOP_NOW
            AssistedCaptureState.SAVED -> CameraButtonState.SAVED
            else -> if (active) CameraButtonState.STOP else CameraButtonState.RECORD
        }
        recordButton.isEnabled = state != AssistedCaptureState.FINALIZING && (active || cameraReady)

        viewRecordingButton.visibility = if (state == AssistedCaptureState.SAVED && selectedSession != null) View.VISIBLE else View.GONE

        camera?.lockSetup(active)
        requestedOrientation = if (active) ActivityInfo.SCREEN_ORIENTATION_LOCKED else ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED
        if (active) {
            window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        } else {
            window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        }

        updateRuntimeStatus()
    }

    private fun openRecording(id: String) {
        startActivity(
            Intent(this, dk.lasse.karatecliprecorder.recordings.RecordingsActivity::class.java)
                .putExtra(dk.lasse.karatecliprecorder.recordings.RecordingsActivity.EXTRA_SESSION_ID, id)
        )
    }

    override fun onResume() {
        super.onResume()
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED) {
            bindCamera()
        }
        routeHandler.post(routeRefresh)
    }

    override fun onSaveInstanceState(outState: Bundle) {
        outState.putString("activity", setup.expectedActivity)
        outState.putString("category", setup.expectedCategory)
        super.onSaveInstanceState(outState)
    }

    override fun onStop() {
        controller.interrupt("app_backgrounded_or_locked")
        routeHandler.removeCallbacksAndMessages(null)
        playbackDialog?.dismiss()
        playbackDialog = null
        super.onStop()
    }

    override fun onDestroy() {
        disposed = true
        QueueManager.setRecordingHidden(false)
        controller.close()
        camera?.close()
        player.release()
        super.onDestroy()
    }

    private fun Int.dp() = (this * resources.displayMetrics.density).toInt()
}
