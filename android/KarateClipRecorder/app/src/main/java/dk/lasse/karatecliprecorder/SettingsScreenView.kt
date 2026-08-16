package dk.lasse.karatecliprecorder

import android.content.Context
import android.graphics.Typeface
import android.view.Gravity
import android.view.View
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.widget.SwitchCompat
import androidx.core.content.ContextCompat
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat

/** Passive, vertically scrolling Settings destination. Camera work starts only via callbacks. */
class SettingsScreenView(
    context: Context,
    private val preferences: AppPreferences,
    private val hasCameraPermission: () -> Boolean,
    private val onHome: () -> Unit,
    private val onTrain: () -> Unit,
    private val onProgress: () -> Unit,
    private val onCameraSetup: () -> Unit,
    private val onCameraPermissionRequest: () -> Unit,
    private val onTrainingData: () -> Unit,
    private val onClearTrainingHistory: () -> Unit,
    private val onDeveloperModeChanged: (Boolean) -> Unit,
    private val onCameraDebug: () -> Unit,
    private val onLearningUiDebug: () -> Unit,
    private val onAbout: () -> Unit,
    private val onHelp: () -> Unit,
    private val onPrivacy: () -> Unit,
    private val onThemeChanged: (AppTheme) -> Unit,
) : FrameLayout(context) {
    private lateinit var cameraPermissionRow: SettingsRowView
    private lateinit var trainingSoundsSwitch: SwitchCompat
    private lateinit var voiceGuidanceSwitch: SwitchCompat
    private lateinit var developerModeSwitch: SwitchCompat
    private lateinit var countdownValue: TextView
    private lateinit var practiceDurationValue: TextView
    private lateinit var themeValue: TextView

    init {
        setBackgroundColor(ContextCompat.getColor(context, R.color.app_background))

        val content = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(20.dp(), 16.dp(), 20.dp(), 110.dp())
            addView(header())
            addView(cameraAndAnalysisSection())
            addView(soundAndVoiceSection())
            addView(trainingPreferencesSection())
            addView(appearanceSection())
            addView(dataAndPrivacySection())
            addView(developerSection())
            addView(aboutSection())
        }
        addView(ScrollView(context).apply {
            isFillViewport = true
            clipToPadding = false
            addView(content, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT))
        }, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT))

        val navigation = AppBottomNavigationView(
            context = context,
            selectedDestination = AppDestination.SETTINGS,
            onHome = onHome,
            onTrain = onTrain,
            onProgress = onProgress,
            onSettings = {},
        )
        addView(navigation, LayoutParams(LayoutParams.MATCH_PARENT, AppBottomNavigationView.BASE_HEIGHT_DP.dp(), Gravity.BOTTOM))

        ViewCompat.setOnApplyWindowInsetsListener(this) { _, insets ->
            val systemBars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            val navigationBars = insets.getInsets(WindowInsetsCompat.Type.navigationBars())
            content.setPadding(20.dp(), systemBars.top + 16.dp(), 20.dp(), navigationBars.bottom + 110.dp())
            insets
        }
        ViewCompat.requestApplyInsets(this)
        refresh()
    }

    fun refresh() {
        if (!::cameraPermissionRow.isInitialized) return
        refreshCameraPermissionState()
        syncSwitch(trainingSoundsSwitch, preferences.trainingSounds)
        syncSwitch(voiceGuidanceSwitch, preferences.voiceGuidance)
        syncSwitch(developerModeSwitch, preferences.developerMode)
        countdownValue.text = countdownDisplay(preferences.countdownSeconds)
        practiceDurationValue.text = durationDisplay(preferences.defaultPracticeDurationMinutes)
        themeValue.text = preferences.theme.displayName
    }

    fun refreshCameraPermissionState() {
        if (!::cameraPermissionRow.isInitialized) return
        if (hasCameraPermission()) {
            cameraPermissionRow.setStatus(
                text = "Allowed",
                color = ContextCompat.getColor(context, R.color.app_success),
                icon = AppIcon.SHIELD_CHECK,
            )
            cameraPermissionRow.clearAction()
        } else {
            cameraPermissionRow.setStatus(
                text = "Not allowed",
                color = ContextCompat.getColor(context, R.color.app_text_secondary),
            )
            cameraPermissionRow.setAction(onCameraPermissionRequest, "Not allowed. Tap to allow")
        }
    }

    private fun header() = LinearLayout(context).apply {
        orientation = LinearLayout.VERTICAL
        addView(TextView(context).apply {
            text = "Settings"
            textSize = 29f
            typeface = Typeface.create("sans-serif", Typeface.BOLD)
            setTextColor(ContextCompat.getColor(context, R.color.app_text_primary))
            ViewCompat.setAccessibilityHeading(this, true)
        })
        addView(TextView(context).apply {
            text = "Control how the app works."
            textSize = 15f
            setTextColor(ContextCompat.getColor(context, R.color.app_text_secondary))
        }, LinearLayout.LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT).apply {
            topMargin = 4.dp()
            bottomMargin = 2.dp()
        })
    }

    private fun cameraAndAnalysisSection() = SettingsSectionView(context, "CAMERA & ANALYSIS").apply {
        addRow(SettingsRowView(
            context,
            AppIcon.CAMERA,
            "Camera setup",
            "Get the best results from your camera",
        ).apply { configureAsNavigation(onClick = onCameraSetup) })
        cameraPermissionRow = SettingsRowView(
            context,
            AppIcon.SHIELD_CHECK,
            "Camera permission",
            "Access to camera for analysis",
        )
        addRow(cameraPermissionRow)
    }

    private fun soundAndVoiceSection() = SettingsSectionView(context, "SOUND & VOICE").apply {
        addRow(SettingsRowView(
            context,
            AppIcon.VOLUME,
            "Training sounds",
            "Play sounds for cues and feedback",
        ).apply {
            trainingSoundsSwitch = configureAsToggle(preferences.trainingSounds) { enabled ->
                preferences.trainingSounds = enabled
            }
        })
        addRow(SettingsRowView(
            context,
            AppIcon.MICROPHONE,
            "Voice guidance",
            "Spoken instructions during training",
        ).apply {
            voiceGuidanceSwitch = configureAsToggle(preferences.voiceGuidance) { enabled ->
                preferences.voiceGuidance = enabled
            }
        })
        addRow(SettingsRowView(
            context,
            AppIcon.CLOCK,
            "Countdown before training",
            "Time before camera starts",
        ).apply {
            countdownValue = requireNotNull(configureAsNavigation(
                value = countdownDisplay(preferences.countdownSeconds),
                onClick = ::showCountdownDialog,
            ))
        })
    }

    private fun trainingPreferencesSection() = SettingsSectionView(context, "TRAINING PREFERENCES").apply {
        addRow(SettingsRowView(
            context,
            AppIcon.CLOCK,
            "Default practice duration",
            "Length of practice sessions",
        ).apply {
            practiceDurationValue = requireNotNull(configureAsNavigation(
                value = durationDisplay(preferences.defaultPracticeDurationMinutes),
                onClick = ::showPracticeDurationDialog,
            ))
        })
    }

    private fun appearanceSection() = SettingsSectionView(context, "APPEARANCE").apply {
        addRow(SettingsRowView(
            context,
            AppIcon.PALETTE,
            "Theme",
            "Choose app appearance",
        ).apply {
            themeValue = requireNotNull(configureAsNavigation(
                value = preferences.theme.displayName,
                onClick = ::showThemeDialog,
            ))
        })
    }

    private fun dataAndPrivacySection() = SettingsSectionView(context, "DATA & PRIVACY").apply {
        addRow(SettingsRowView(
            context,
            AppIcon.DATABASE,
            "Training data",
            "Manage how your training data is stored",
        ).apply { configureAsNavigation(onClick = onTrainingData) })
        addRow(SettingsRowView(
            context,
            AppIcon.TRASH,
            "Clear training history",
            "Remove all training sessions and results",
        ).apply { configureAsNavigation(onClick = onClearTrainingHistory) })
    }

    private fun developerSection() = SettingsSectionView(context, "DEVELOPER & DEBUG").apply {
        addRow(SettingsRowView(
            context,
            AppIcon.CODE,
            "Developer mode",
            "Enable advanced tools and diagnostics",
        ).apply {
            developerModeSwitch = configureAsToggle(preferences.developerMode) { enabled ->
                preferences.developerMode = enabled
                onDeveloperModeChanged(enabled)
            }
        })
        addRow(SettingsRowView(
            context,
            AppIcon.BUG,
            "Camera Debug",
            "Open camera debug and analysis tools",
        ).apply { configureAsNavigation(onClick = onCameraDebug) })
        addRow(SettingsRowView(
            context,
            AppIcon.PALETTE,
            "Learning UI gallery",
            "Preview artwork and every progress marker state",
        ).apply { configureAsNavigation(onClick = onLearningUiDebug) })
    }

    private fun aboutSection() = SettingsSectionView(context, "ABOUT").apply {
        addRow(SettingsRowView(
            context,
            AppIcon.INFO_CIRCLE,
            "About Karate Kihon Analyzer",
            "App information and version",
        ).apply { configureAsNavigation(onClick = onAbout) })
        addRow(SettingsRowView(
            context,
            AppIcon.HELP_CIRCLE,
            "Help & how it works",
            "Learn more about the app",
        ).apply { configureAsNavigation(onClick = onHelp) })
        addRow(SettingsRowView(
            context,
            AppIcon.SHIELD,
            "Privacy",
            "Privacy and data information",
        ).apply { configureAsNavigation(onClick = onPrivacy) })
    }

    private fun showCountdownDialog() {
        val options = AppPreferences.COUNTDOWN_OPTIONS_SECONDS
        showChoiceDialog(
            title = "Countdown before training",
            labels = options.map(::countdownDisplay),
            selectedIndex = options.indexOf(preferences.countdownSeconds),
        ) { index ->
            preferences.countdownSeconds = options[index]
            countdownValue.text = countdownDisplay(options[index])
        }
    }

    private fun showPracticeDurationDialog() {
        val options = AppPreferences.PRACTICE_DURATION_OPTIONS_MINUTES
        showChoiceDialog(
            title = "Default practice duration",
            labels = options.map(::durationDisplay),
            selectedIndex = options.indexOf(preferences.defaultPracticeDurationMinutes),
        ) { index ->
            preferences.defaultPracticeDurationMinutes = options[index]
            practiceDurationValue.text = durationDisplay(options[index])
        }
    }

    private fun showThemeDialog() {
        val options = AppTheme.entries
        showChoiceDialog(
            title = "Theme",
            labels = options.map(AppTheme::displayName),
            selectedIndex = options.indexOf(preferences.theme),
        ) { index ->
            val theme = options[index]
            preferences.theme = theme
            themeValue.text = theme.displayName
            onThemeChanged(theme)
        }
    }

    private fun showChoiceDialog(
        title: String,
        labels: List<String>,
        selectedIndex: Int,
        onSelected: (Int) -> Unit,
    ) {
        AlertDialog.Builder(context)
            .setTitle(title)
            .setSingleChoiceItems(labels.toTypedArray(), selectedIndex) { dialog, selected ->
                onSelected(selected)
                dialog.dismiss()
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun countdownDisplay(seconds: Int) = when (seconds) {
        0 -> "No countdown"
        1 -> "1 second"
        else -> "$seconds seconds"
    }

    private fun durationDisplay(minutes: Int) = if (minutes == 1) "1 minute" else "$minutes minutes"

    private fun syncSwitch(toggle: SwitchCompat, value: Boolean) {
        if (toggle.isChecked != value) toggle.isChecked = value
    }

    private fun Int.dp() = (this * resources.displayMetrics.density).toInt()
}
