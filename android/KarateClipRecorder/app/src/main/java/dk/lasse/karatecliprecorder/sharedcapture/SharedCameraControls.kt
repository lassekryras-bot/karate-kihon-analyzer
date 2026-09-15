package dk.lasse.karatecliprecorder.sharedcapture

import android.app.Dialog
import android.content.Context
import android.content.Intent
import android.content.res.ColorStateList
import android.graphics.*
import android.graphics.drawable.ColorDrawable
import android.graphics.drawable.GradientDrawable
import android.media.MediaRouter
import android.os.SystemClock
import android.provider.Settings
import android.util.AttributeSet
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.view.ViewOutlineProvider
import android.view.WindowManager
import android.widget.*
import androidx.camera.view.PreviewView
import androidx.core.content.ContextCompat
import dk.lasse.karatecliprecorder.AppIcon
import dk.lasse.karatecliprecorder.AppIconView
import dk.lasse.karatecliprecorder.R
import dk.lasse.karatecliprecorder.captureprofile.CaptureFpsRange
import dk.lasse.karatecliprecorder.captureprofile.CaptureQuality
import dk.lasse.karatecliprecorder.captureprofile.RearLens
import dk.lasse.karatecliprecorder.captureprofile.VideoQualityTier
import java.util.Locale

/** Status severity levels used for pre-recording and system notifications. */
enum class CameraStatusSeverity {
    READY,
    WARNING,
    BLOCKING,
}

/** Immutable semantic message for the pre-recording single status bar. */
data class CameraStatusMessage(
    val text: String,
    val severity: CameraStatusSeverity,
    val priority: Int,
    val actionReason: String? = null,
) {
    companion object {
        fun ready(): CameraStatusMessage = CameraStatusMessage(
            text = "Ready",
            severity = CameraStatusSeverity.READY,
            priority = 5,
        )

        fun missingRequirement(text: String, reason: String? = null): CameraStatusMessage = CameraStatusMessage(
            text = text,
            severity = CameraStatusSeverity.WARNING,
            priority = 3,
            actionReason = reason,
        )

        fun batteryBlocker(text: String = "Battery below start threshold"): CameraStatusMessage = CameraStatusMessage(
            text = text,
            severity = CameraStatusSeverity.BLOCKING,
            priority = 2,
            actionReason = "battery_blocker",
        )

        fun storageBlocker(text: String = "Low storage"): CameraStatusMessage = CameraStatusMessage(
            text = text,
            severity = CameraStatusSeverity.BLOCKING,
            priority = 1,
            actionReason = "storage_blocker",
        )

        fun informational(text: String): CameraStatusMessage = CameraStatusMessage(
            text = text,
            severity = CameraStatusSeverity.READY,
            priority = 4,
        )
    }
}

/** Button state for the circular bottom camera control. */
enum class CameraButtonState {
    RECORD,
    STOP,
    STOP_NOW,
    SAVED,
}

/**
 * Compact circular Record / Stop camera control conforming to Sections 14 & 15.
 * Maintains fixed footprint and center location before, during, and after capture.
 */
class SharedCameraRecordButton @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0,
) : LinearLayout(context, attrs, defStyleAttr) {

    var state: CameraButtonState = CameraButtonState.RECORD
        set(value) {
            field = value
            updateAppearance()
        }

    var onAction: ((CameraButtonState) -> Unit)? = null

    private val glyphView = object : View(context) {
        private val ringPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE
            strokeWidth = 3.dp().toFloat()
            color = 0xFFEF4444.toInt() // Vibrant camera red
        }
        private val fillPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.FILL
            color = 0xFFEF4444.toInt()
        }
        private val stopPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.FILL
            color = Color.WHITE
        }

        override fun onDraw(canvas: Canvas) {
            super.onDraw(canvas)
            val cx = width / 2f
            val cy = height / 2f
            val outerRadius = (width.coerceAtMost(height) / 2f) - ringPaint.strokeWidth

            // Outer red ring with slight inset
            canvas.drawCircle(cx, cy, outerRadius, ringPaint)

            // Inner filled red circle
            val innerRadius = outerRadius - 6.dp().toFloat()
            canvas.drawCircle(cx, cy, innerRadius, fillPaint)

            // Stop square glyph if in Stop or Stop Now state
            if (state == CameraButtonState.STOP || state == CameraButtonState.STOP_NOW) {
                val squareHalf = 9.dp().toFloat()
                val rect = RectF(cx - squareHalf, cy - squareHalf, cx + squareHalf, cy + squareHalf)
                val corner = 3.dp().toFloat()
                canvas.drawRoundRect(rect, corner, corner, stopPaint)
            }
        }
    }

    private val labelView = TextView(context).apply {
        textSize = 14f
        setTextColor(Color.WHITE)
        typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
        gravity = Gravity.CENTER
    }

    init {
        orientation = VERTICAL
        gravity = Gravity.CENTER_HORIZONTAL
        isClickable = true
        isFocusable = true

        val buttonSize = 72.dp()
        addView(glyphView, LayoutParams(buttonSize, buttonSize).apply {
            gravity = Gravity.CENTER_HORIZONTAL
        })

        addView(labelView, LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT).apply {
            topMargin = 6.dp()
            gravity = Gravity.CENTER_HORIZONTAL
        })

        setOnClickListener {
            if (isEnabled) {
                onAction?.invoke(state)
            }
        }

        updateAppearance()
    }

    override fun setEnabled(enabled: Boolean) {
        super.setEnabled(enabled)
        alpha = if (enabled) 1.0f else 0.45f
    }

    private fun updateAppearance() {
        labelView.text = when (state) {
            CameraButtonState.RECORD -> "Record"
            CameraButtonState.STOP -> "Stop"
            CameraButtonState.STOP_NOW -> "Stop now"
            CameraButtonState.SAVED -> "Record another"
        }
        glyphView.invalidate()
    }

    private fun Int.dp() = (this * resources.displayMetrics.density).toInt()
}

/**
 * Reusable embedded Camera Preview View conforming to the Shared Camera Capture UI specification.
 *
 * Responsibilities:
 * - Hosts [PreviewView] with center-crop fill (`FILL_CENTER`) and rounded corners.
 * - Displays top-left resolution · FPS quality badge before recording.
 * - Displays top-right camera settings and audio route buttons before recording.
 * - Displays single centered status pill before recording and during saving.
 * - Displays shallow translucent 3-column status bar (State | Progress | Elapsed time) during recording.
 * - Displays prominent countdown overlay.
 * - Provides bottom sheets for camera settings and audio output selection.
 */
class SharedCameraPreviewView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0,
) : FrameLayout(context, attrs, defStyleAttr) {

    val preview: PreviewView = PreviewView(context).apply {
        scaleType = PreviewView.ScaleType.FILL_CENTER
        outlineProvider = object : ViewOutlineProvider() {
            override fun getOutline(view: View, outline: Outline) {
                outline.setRoundRect(0, 0, view.width, view.height, 18.dp().toFloat())
            }
        }
        clipToOutline = true
        setOnTouchListener { _, event ->
            if (event.action == MotionEvent.ACTION_UP && !recordingActive) {
                onFocusTap?.invoke(event.x, event.y)
                performClick()
            }
            true
        }
    }

    // Top-left capture quality badge (e.g. "1080p · 60 fps")
    private val qualityBadge = TextView(context).apply {
        textSize = 12.5f
        setTextColor(Color.WHITE)
        typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
        setPadding(10.dp(), 5.dp(), 10.dp(), 5.dp())
        background = GradientDrawable().apply {
            setColor(0xB01A222C.toInt())
            cornerRadius = 12.dp().toFloat()
            setStroke(1.dp(), 0x33FFFFFF.toInt())
        }
        visibility = View.VISIBLE
    }

    // Top-right camera settings cog button
    private val settingsButton = FrameLayout(context).apply {
        val size = 42.dp()
        layoutParams = LayoutParams(size, size)
        background = GradientDrawable().apply {
            setColor(0xB01A222C.toInt())
            cornerRadius = 10.dp().toFloat()
            setStroke(1.dp(), 0x33FFFFFF.toInt())
        }
        val icon = AppIconView(context, AppIcon.SETTINGS, sizeDp = 22).apply {
            setIconColor(Color.WHITE)
        }
        addView(icon, LayoutParams(22.dp(), 22.dp(), Gravity.CENTER))
        setOnClickListener { onSettingsClick?.invoke() }
    }

    // Top-right audio output button
    private val audioRouteButton = FrameLayout(context).apply {
        val size = 42.dp()
        layoutParams = LayoutParams(size, size)
        background = GradientDrawable().apply {
            setColor(0xB01A222C.toInt())
            cornerRadius = 10.dp().toFloat()
            setStroke(1.dp(), 0x33FFFFFF.toInt())
        }
        val icon = AppIconView(context, AppIcon.VOLUME, sizeDp = 22).apply {
            tag = "audio_icon"
            setIconColor(Color.WHITE)
        }
        addView(icon, LayoutParams(22.dp(), 22.dp(), Gravity.CENTER))
        setOnClickListener { onAudioRouteClick?.invoke() }
    }

    private val topRightControls = LinearLayout(context).apply {
        orientation = LinearLayout.VERTICAL
        addView(settingsButton)
        addView(audioRouteButton, LinearLayout.LayoutParams(42.dp(), 42.dp()).apply {
            topMargin = 8.dp()
        })
    }

    // Pre-recording single centered status bar (pill)
    private val preRecordingStatusDot = View(context).apply {
        background = GradientDrawable().apply {
            shape = GradientDrawable.OVAL
            setColor(0xFF10B981.toInt())
        }
    }
    private val preRecordingStatusText = TextView(context).apply {
        textSize = 14f
        setTextColor(Color.WHITE)
        typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
    }
    private val preRecordingStatusBar = LinearLayout(context).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.CENTER
        setPadding(16.dp(), 7.dp(), 18.dp(), 7.dp())
        background = GradientDrawable().apply {
            setColor(0xB8131B24.toInt())
            cornerRadius = 20.dp().toFloat()
            setStroke(1.dp(), 0x33FFFFFF.toInt())
        }
        addView(preRecordingStatusDot, LayoutParams(9.dp(), 9.dp()).apply {
            marginEnd = 8.dp()
            gravity = Gravity.CENTER_VERTICAL
        })
        addView(preRecordingStatusText, LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT).apply {
            gravity = Gravity.CENTER_VERTICAL
        })
        setOnClickListener {
            currentStatus?.actionReason?.let { reason ->
                onStatusTap?.invoke(reason)
            }
        }
    }

    // Active recording shallow translucent 3-column status bar
    private val recStateDot = View(context).apply {
        background = GradientDrawable().apply {
            shape = GradientDrawable.OVAL
            setColor(0xFFEF4444.toInt())
        }
    }
    private val recStateText = TextView(context).apply {
        textSize = 14.5f
        setTextColor(Color.WHITE)
        typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
        text = "Recording"
    }
    private val recProgressText = TextView(context).apply {
        textSize = 14.5f
        setTextColor(Color.WHITE)
        typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
        gravity = Gravity.CENTER
        text = "0 / 10"
    }
    private val recElapsedText = TextView(context).apply {
        textSize = 14.5f
        setTextColor(Color.WHITE)
        typeface = Typeface.MONOSPACE
        fontFeatureSettings = "tnum"
        gravity = Gravity.CENTER
        text = "00:00"
    }

    private val recordingStatusBar = LinearLayout(context).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        background = GradientDrawable().apply {
            setColor(0xCC11161B.toInt())
            cornerRadius = 14.dp().toFloat()
            setStroke(1.dp(), 0x2E3A4B.toInt())
        }
        setPadding(12.dp(), 8.dp(), 12.dp(), 8.dp())
        minimumHeight = 44.dp()

        // Column 1: State (Recording / Finishing)
        val col1 = LinearLayout(context).apply {
            gravity = Gravity.CENTER
            addView(recStateDot, LayoutParams(9.dp(), 9.dp()).apply {
                marginEnd = 8.dp()
                gravity = Gravity.CENTER_VERTICAL
            })
            addView(recStateText)
        }
        addView(col1, LinearLayout.LayoutParams(0, LayoutParams.MATCH_PARENT, 1f))

        // Divider 1
        addView(createDivider(), LinearLayout.LayoutParams(1.dp(), 20.dp()).apply {
            gravity = Gravity.CENTER_VERTICAL
        })

        // Column 2: Progress (3 / 10)
        val col2 = LinearLayout(context).apply {
            gravity = Gravity.CENTER
            addView(recProgressText)
        }
        addView(col2, LinearLayout.LayoutParams(0, LayoutParams.MATCH_PARENT, 1f))

        // Divider 2
        addView(createDivider(), LinearLayout.LayoutParams(1.dp(), 20.dp()).apply {
            gravity = Gravity.CENTER_VERTICAL
        })

        // Column 3: Elapsed time (00:12)
        val col3 = LinearLayout(context).apply {
            gravity = Gravity.CENTER
            addView(recElapsedText)
        }
        addView(col3, LinearLayout.LayoutParams(0, LayoutParams.MATCH_PARENT, 1f))

        visibility = View.GONE
    }

    // Countdown overlay (3, 2, 1)
    private val countdownText = TextView(context).apply {
        textSize = 76f
        setTextColor(Color.WHITE)
        typeface = Typeface.create("sans-serif", Typeface.BOLD)
        gravity = Gravity.CENTER
        setShadowLayer(16f, 0f, 4f, 0xAA000000.toInt())
        visibility = View.GONE
    }

    // Callback listeners
    var onSettingsClick: (() -> Unit)? = null
    var onAudioRouteClick: (() -> Unit)? = null
    var onStatusTap: ((actionReason: String?) -> Unit)? = null
    var onFocusTap: ((x: Float, y: Float) -> Unit)? = null

    var recordingActive: Boolean = false
        private set

    private var currentStatus: CameraStatusMessage? = null

    init {
        addView(preview, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT))

        // Top-left quality badge
        addView(qualityBadge, LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT, Gravity.START or Gravity.TOP).apply {
            topMargin = 12.dp()
            marginStart = 12.dp()
        })

        // Top-right controls
        addView(topRightControls, LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT, Gravity.END or Gravity.TOP).apply {
            topMargin = 12.dp()
            marginEnd = 12.dp()
        })

        // Pre-recording centered status bar
        addView(preRecordingStatusBar, LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT, Gravity.CENTER_HORIZONTAL or Gravity.BOTTOM).apply {
            bottomMargin = 14.dp()
        })

        // Active recording 3-column status bar
        addView(recordingStatusBar, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT, Gravity.BOTTOM).apply {
            marginStart = 12.dp()
            marginEnd = 12.dp()
            bottomMargin = 12.dp()
        })

        // Countdown
        addView(countdownText, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT, Gravity.CENTER))

        // Default badge and status
        setQualityBadge("1080p · 60 fps")
        setStatus(CameraStatusMessage.ready())
    }

    private fun createDivider(): View = View(context).apply {
        setBackgroundColor(0x33FFFFFF.toInt())
    }

    /** Updates the top-left quality/FPS badge. */
    fun setQualityBadge(text: String) {
        qualityBadge.text = text
    }

    /** Updates the audio route button icon based on device type. */
    fun setAudioRoute(isHeadset: Boolean) {
        val iconView = audioRouteButton.findViewWithTag<AppIconView>("audio_icon") ?: return
        if (isHeadset) {
            iconView.setImageResource(R.drawable.ic_tabler_volume) // could switch to headset if icon available
            iconView.setIconColor(0xFF10B981.toInt()) // subtle accent for connected route
        } else {
            iconView.setImageResource(R.drawable.ic_tabler_volume)
            iconView.setIconColor(Color.WHITE)
        }
    }

    /** Sets the pre-recording single status message with severity tinting and priority handling. */
    fun setStatus(message: CameraStatusMessage) {
        currentStatus = message
        preRecordingStatusText.text = message.text

        val (dotColor, textColor, bgColor, strokeColor) = when (message.severity) {
            CameraStatusSeverity.READY -> Quad(0xFF10B981.toInt(), Color.WHITE, 0xB8131B24.toInt(), 0x33FFFFFF.toInt())
            CameraStatusSeverity.WARNING -> Quad(0xFFF59E0B.toInt(), 0xFFFDE68A.toInt(), 0xCC2E2614.toInt(), 0x66F59E0B.toInt())
            CameraStatusSeverity.BLOCKING -> Quad(0xFFEF4444.toInt(), 0xFFFCA5A5.toInt(), 0xCC2E1418.toInt(), 0x66EF4444.toInt())
        }

        (preRecordingStatusDot.background as? GradientDrawable)?.setColor(dotColor)
        preRecordingStatusText.setTextColor(textColor)
        (preRecordingStatusBar.background as? GradientDrawable)?.apply {
            setColor(bgColor)
            setStroke(1.dp(), strokeColor)
        }
    }

    /** Displays the 3-column recording status bar. */
    fun setRecordingProgress(stateLabel: String, currentCue: Int, totalPlanned: Int, formattedTime: String) {
        recStateText.text = stateLabel
        recProgressText.text = "$currentCue / $totalPlanned"
        recElapsedText.text = formattedTime
    }

    /** Displays large countdown number. */
    fun showCountdown(value: String) {
        countdownText.text = value
        countdownText.visibility = View.VISIBLE
    }

    fun hideCountdown() {
        countdownText.visibility = View.GONE
    }

    /**
     * Toggles recording mode presentation:
     * - In recording mode, hides all pre-recording controls (badge, cog, audio route, pre-rec status).
     * - In pre-recording mode, restores clean layout.
     */
    fun setRecordingActive(active: Boolean, isFinishing: Boolean = false) {
        recordingActive = active
        if (active) {
            qualityBadge.visibility = View.GONE
            topRightControls.visibility = View.GONE
            preRecordingStatusBar.visibility = View.GONE
            recordingStatusBar.visibility = View.VISIBLE
            recStateText.text = if (isFinishing) "Finishing" else "Recording"
        } else {
            hideCountdown()
            recordingStatusBar.visibility = View.GONE
            qualityBadge.visibility = View.VISIBLE
            topRightControls.visibility = View.VISIBLE
            preRecordingStatusBar.visibility = View.VISIBLE
        }
    }

    /** Shows centered saving / saved status. */
    fun setSavingStatus(text: String, isComplete: Boolean = false) {
        recordingStatusBar.visibility = View.GONE
        hideCountdown()
        preRecordingStatusBar.visibility = View.VISIBLE
        qualityBadge.visibility = View.GONE
        topRightControls.visibility = View.GONE

        preRecordingStatusText.text = text
        val dotColor = if (isComplete) 0xFF10B981.toInt() else 0xFFF59E0B.toInt()
        (preRecordingStatusDot.background as? GradientDrawable)?.setColor(dotColor)
        preRecordingStatusText.setTextColor(Color.WHITE)
        (preRecordingStatusBar.background as? GradientDrawable)?.apply {
            setColor(0xB8131B24.toInt())
            setStroke(1.dp(), 0x33FFFFFF.toInt())
        }
    }

    private data class Quad(val a: Int, val b: Int, val c: Int, val d: Int)
    private fun Int.dp() = (this * resources.displayMetrics.density).toInt()
}

/**
 * Shared dark bottom sheet dialog with swipe-down-to-dismiss support, rounded top corners,
 * and dim background conforming to the app's visual language.
 */
open class DarkBottomSheetDialog(context: Context) : Dialog(context) {

    protected val sheetBody: LinearLayout = LinearLayout(context).apply {
        orientation = LinearLayout.VERTICAL
        background = GradientDrawable().apply {
            setColor(0xFF1A222C.toInt())
            cornerRadii = floatArrayOf(20.dp().toFloat(), 20.dp().toFloat(), 20.dp().toFloat(), 20.dp().toFloat(), 0f, 0f, 0f, 0f)
            setStroke(1.dp(), 0xFF283545.toInt())
        }
        setPadding(20.dp(), 12.dp(), 20.dp(), 24.dp())
    }

    protected val dragHandle: View = View(context).apply {
        background = GradientDrawable().apply {
            setColor(0x44FFFFFF.toInt())
            cornerRadius = 3.dp().toFloat()
        }
    }

    protected val dragContainer: FrameLayout = FrameLayout(context).apply {
        addView(dragHandle, FrameLayout.LayoutParams(36.dp(), 4.dp(), Gravity.CENTER))
    }

    private var initialY = 0f
    private var isDragging = false

    init {
        requestWindowFeature(android.view.Window.FEATURE_NO_TITLE)
        setCanceledOnTouchOutside(true)
        setupDragToDismiss()
    }

    private fun setupDragToDismiss() {
        sheetBody.addView(dragContainer, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 28.dp()).apply {
            bottomMargin = 4.dp()
        })
        attachDragToDismiss(dragContainer)
    }

    protected fun attachDragToDismiss(view: View) {
        view.setOnTouchListener { _, event ->
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    initialY = event.rawY
                    isDragging = true
                    true
                }
                MotionEvent.ACTION_MOVE -> {
                    if (isDragging) {
                        val deltaY = (event.rawY - initialY).coerceAtLeast(0f)
                        sheetBody.translationY = deltaY
                    }
                    true
                }
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                    if (isDragging) {
                        isDragging = false
                        if (sheetBody.translationY > 60.dp()) {
                            dismiss()
                        } else {
                            sheetBody.animate().translationY(0f).setDuration(150).start()
                        }
                    }
                    true
                }
                else -> false
            }
        }
    }

    protected fun buildTitleRow(titleText: String, onClose: () -> Unit = { dismiss() }): LinearLayout {
        return LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            val title = TextView(context).apply {
                text = titleText
                textSize = 18f
                setTextColor(Color.WHITE)
                typeface = Typeface.create("sans-serif", Typeface.BOLD)
            }
            addView(title, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
            val close = TextView(context).apply {
                text = "Done"
                textSize = 15f
                setTextColor(0xFFEF4444.toInt()) // Coral/red accent
                typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
                setPadding(8.dp(), 4.dp(), 8.dp(), 4.dp())
                setOnClickListener { onClose() }
            }
            addView(close)
        }
    }

    protected fun createSectionHeading(text: String): TextView = TextView(context).apply {
        this.text = text
        textSize = 11.5f
        typeface = Typeface.create("sans-serif", Typeface.BOLD)
        letterSpacing = 0.08f
        setTextColor(0xFF94A3B8.toInt())
        setPadding(0, 10.dp(), 0, 8.dp())
    }

    protected fun createSectionDivider(): View = View(context).apply {
        setBackgroundColor(0xFF283545.toInt())
        layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 1.dp()).apply {
            topMargin = 12.dp()
            bottomMargin = 4.dp()
        }
    }

    override fun onStart() {
        super.onStart()
        window?.let { win ->
            win.setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))
            win.setLayout(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
            win.setGravity(Gravity.BOTTOM)
            win.addFlags(WindowManager.LayoutParams.FLAG_DIM_BEHIND)
            win.setDimAmount(0.6f)
        }
    }

    protected fun Int.dp() = (this * context.resources.displayMetrics.density).toInt()
}

/**
 * Camera settings sheet/overlay conforming to Section 8.
 * Exposes rear lens / zoom, resolution + FPS, and focus behavior.
 */
class CameraSettingsSheet(
    context: Context,
    private val lenses: List<RearLens>,
    private val selectedLensId: String?,
    private val zoomRatios: List<Float>,
    private val currentZoom: Float,
    private val qualities: List<CaptureQuality>,
    private val selectedQuality: CaptureQuality?,
    private val automaticQuality: Boolean,
    private val onLensSelected: (String) -> Unit,
    private val onZoomSelected: (Float) -> Unit,
    private val onQualitySelected: (CaptureQuality?, Boolean) -> Unit,
) : DarkBottomSheetDialog(context) {

    init {
        // Title row
        sheetBody.addView(
            buildTitleRow("Camera settings") { dismiss() },
            LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
        )

        // Divider
        sheetBody.addView(createSectionDivider())

        // Section: Rear lens (if multiple available)
        if (lenses.size > 1) {
            sheetBody.addView(createSectionHeading("LENS"))
            val lensGroup = RadioGroup(context).apply {
                orientation = RadioGroup.VERTICAL
            }
            lenses.forEach { lens ->
                val radio = RadioButton(context).apply {
                    text = lens.label
                    setTextColor(Color.WHITE)
                    buttonTintList = ColorStateList.valueOf(0xFFEF4444.toInt())
                    isChecked = lens.id == selectedLensId
                    setOnClickListener {
                        onLensSelected(lens.id)
                        dismiss()
                    }
                }
                lensGroup.addView(radio)
            }
            sheetBody.addView(lensGroup, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
            sheetBody.addView(createSectionDivider())
        }

        // Section: Zoom
        if (zoomRatios.isNotEmpty()) {
            sheetBody.addView(createSectionHeading("REAR CAMERA ZOOM"))
            val zoomRow = LinearLayout(context).apply {
                orientation = LinearLayout.HORIZONTAL
            }
            zoomRatios.forEach { ratio ->
                val isSelected = kotlin.math.abs(ratio - currentZoom) < 0.05f
                val btn = TextView(context).apply {
                    text = "${String.format(Locale.US, "%.1f", ratio)}×"
                    textSize = 14f
                    gravity = Gravity.CENTER
                    setTextColor(if (isSelected) Color.WHITE else 0xFF94A3B8.toInt())
                    background = GradientDrawable().apply {
                        setColor(if (isSelected) 0xFF242E3C.toInt() else 0xFF151C24.toInt())
                        cornerRadius = 8.dp().toFloat()
                        setStroke(1.dp(), if (isSelected) 0xFFEF4444.toInt() else 0xFF283545.toInt())
                    }
                    setPadding(16.dp(), 8.dp(), 16.dp(), 8.dp())
                    setOnClickListener {
                        onZoomSelected(ratio)
                        dismiss()
                    }
                }
                zoomRow.addView(btn, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
                    marginEnd = 8.dp()
                })
            }
            sheetBody.addView(zoomRow, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
            sheetBody.addView(createSectionDivider())
        }

        // Section: Resolution + FPS
        sheetBody.addView(createSectionHeading("RECORDING QUALITY"))
        val qualityGroup = RadioGroup(context).apply {
            orientation = RadioGroup.VERTICAL
        }
        val autoRadio = RadioButton(context).apply {
            text = "Automatic (recommended)"
            setTextColor(Color.WHITE)
            buttonTintList = ColorStateList.valueOf(0xFFEF4444.toInt())
            isChecked = automaticQuality
            setOnClickListener {
                onQualitySelected(null, true)
                dismiss()
            }
        }
        qualityGroup.addView(autoRadio)

        qualities.forEach { q ->
            val radio = RadioButton(context).apply {
                text = q.toString()
                setTextColor(Color.WHITE)
                buttonTintList = ColorStateList.valueOf(0xFFEF4444.toInt())
                isChecked = !automaticQuality && q == selectedQuality
                setOnClickListener {
                    onQualitySelected(q, false)
                    dismiss()
                }
            }
            qualityGroup.addView(radio)
        }
        sheetBody.addView(qualityGroup, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        sheetBody.addView(createSectionDivider())

        // Section: Focus & exposure note
        sheetBody.addView(createSectionHeading("FOCUS & EXPOSURE"))
        val focusNote = TextView(context).apply {
            text = "Tap anywhere on the camera preview to lock focus and exposure on the athlete."
            textSize = 13f
            setTextColor(0xFF94A3B8.toInt())
            setLineSpacing(0f, 1.2f)
        }
        sheetBody.addView(focusNote, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))

        setContentView(sheetBody)
    }
}

/**
 * Audio output chooser dialog conforming to Section 9.
 * Shows active route, local devices, and 'Connect device…' Bluetooth handoff.
 */
class AudioOutputChooserDialog(
    context: Context,
    private val onConnectDevice: () -> Unit,
) : DarkBottomSheetDialog(context) {

    init {
        // Title row
        sheetBody.addView(
            buildTitleRow("Audio output") { dismiss() },
            LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
        )

        // Divider
        sheetBody.addView(View(context).apply {
            setBackgroundColor(0xFF283545.toInt())
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 1.dp()).apply {
                topMargin = 12.dp()
                bottomMargin = 12.dp()
            }
        })

        // Current active route
        val router = context.getSystemService(Context.MEDIA_ROUTER_SERVICE) as? MediaRouter
        val activeRoute = router?.getSelectedRoute(MediaRouter.ROUTE_TYPE_LIVE_AUDIO)
        val routeName = activeRoute?.name?.toString() ?: "Phone speaker"

        val activeRow = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(12.dp(), 12.dp(), 12.dp(), 12.dp())
            background = GradientDrawable().apply {
                setColor(0xFF242E3C.toInt())
                cornerRadius = 10.dp().toFloat()
            }
            val icon = AppIconView(context, AppIcon.VOLUME, sizeDp = 22).apply {
                setIconColor(0xFFEF4444.toInt()) // Coral/red active accent
            }
            addView(icon, LinearLayout.LayoutParams(22.dp(), 22.dp()).apply { marginEnd = 12.dp() })
            val label = TextView(context).apply {
                text = "$routeName (Active)"
                textSize = 15f
                setTextColor(Color.WHITE)
                typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
            }
            addView(label, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
            val check = AppIconView(context, AppIcon.CHECK, sizeDp = 20).apply {
                setIconColor(0xFFEF4444.toInt()) // Coral/red accent
            }
            addView(check, LinearLayout.LayoutParams(20.dp(), 20.dp()))
        }
        sheetBody.addView(activeRow, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))

        // Connect device button
        val connectRow = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(12.dp(), 14.dp(), 12.dp(), 14.dp())
            background = GradientDrawable().apply {
                setColor(0xFF171F2A.toInt())
                cornerRadius = 10.dp().toFloat()
                setStroke(1.dp(), 0xFF283545.toInt())
            }
            val label = TextView(context).apply {
                text = "Connect device…"
                textSize = 15f
                setTextColor(0xFFEF4444.toInt()) // Coral/red accent
            }
            addView(label, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
            val chevron = AppIconView(context, AppIcon.CHEVRON_RIGHT, sizeDp = 18).apply {
                setIconColor(0xFFEF4444.toInt()) // Coral/red accent
            }
            addView(chevron, LinearLayout.LayoutParams(18.dp(), 18.dp()))
            setOnClickListener {
                dismiss()
                onConnectDevice()
            }
        }
        sheetBody.addView(connectRow, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
            topMargin = 10.dp()
        })

        // Explanatory note
        val note = TextView(context).apply {
            text = "Karate Kihon Analyzer plays spoken cues and counting through Android's active media output."
            textSize = 13f
            setTextColor(0xFF94A3B8.toInt())
            setPadding(4.dp(), 14.dp(), 4.dp(), 0)
            setLineSpacing(0f, 1.2f)
        }
        sheetBody.addView(note)

        setContentView(sheetBody)
    }
}

/**
 * Bottom selection sheet for expected activity conforming to Section 5.
 */
class ActivitySelectionSheet(
    context: Context,
    private val selectedActivity: String,
    private val onActivitySelected: (name: String, category: String) -> Unit,
) : DarkBottomSheetDialog(context) {

    data class ActivityOption(val name: String, val category: String, val subtitle: String)

    init {
        // Title row
        sheetBody.addView(
            buildTitleRow("Expected activity") { dismiss() },
            LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
        )

        // Subtitle note
        val note = TextView(context).apply {
            text = "Select the movement being recorded and analyzed."
            textSize = 13f
            setTextColor(0xFF94A3B8.toInt())
            setPadding(0, 4.dp(), 0, 10.dp())
        }
        sheetBody.addView(note)

        // Divider
        sheetBody.addView(View(context).apply {
            setBackgroundColor(0xFF283545.toInt())
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 1.dp()).apply {
                bottomMargin = 10.dp()
            }
        })

        val options = listOf(
            ActivityOption("Alternating straight punches", "Punches", "Kihon choku-zuki from heiko-dachi"),
            ActivityOption("Front kicks", "Kicks", "Mae-geri repetitions from kamae"),
            ActivityOption("Other karate movements", "Other", "General kihon or kata technique"),
        )

        options.forEach { opt ->
            val isSelected = opt.name == selectedActivity
            val row = LinearLayout(context).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                setPadding(14.dp(), 12.dp(), 14.dp(), 12.dp())
                background = GradientDrawable().apply {
                    setColor(if (isSelected) 0xFF242E3C.toInt() else 0xFF171F2A.toInt())
                    cornerRadius = 10.dp().toFloat()
                    setStroke(1.dp(), if (isSelected) 0xFFEF4444.toInt() else 0xFF283545.toInt())
                }
                val icon = AppIconView(context, AppIcon.KARATE, sizeDp = 20).apply {
                    setIconColor(if (isSelected) 0xFFEF4444.toInt() else 0xFF94A3B8.toInt())
                }
                addView(icon, LinearLayout.LayoutParams(20.dp(), 20.dp()).apply { marginEnd = 12.dp() })

                val textCol = LinearLayout(context).apply {
                    orientation = LinearLayout.VERTICAL
                    val title = TextView(context).apply {
                        text = opt.name
                        textSize = 15f
                        typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
                        setTextColor(Color.WHITE)
                    }
                    addView(title)
                    val sub = TextView(context).apply {
                        text = opt.subtitle
                        textSize = 12.5f
                        setTextColor(0xFF94A3B8.toInt())
                    }
                    addView(sub)
                }
                addView(textCol, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))

                if (isSelected) {
                    val check = AppIconView(context, AppIcon.CHECK, sizeDp = 20).apply {
                        setIconColor(0xFFEF4444.toInt())
                    }
                    addView(check, LinearLayout.LayoutParams(20.dp(), 20.dp()))
                }

                setOnClickListener {
                    onActivitySelected(opt.name, opt.category)
                    dismiss()
                }
            }

            sheetBody.addView(row, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
                bottomMargin = 8.dp()
            })
        }

        setContentView(sheetBody)
    }
}

/**
 * Bottom selection sheet for cue mode conforming to Section 5.
 */
class CueModeSelectionSheet(
    context: Context,
    private val onUnavailableSelected: (CaptureStartBlock) -> Unit,
) : DarkBottomSheetDialog(context) {

    init {
        // Title row
        sheetBody.addView(
            buildTitleRow("Cue mode") { dismiss() },
            LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
        )

        // Subtitle note
        val note = TextView(context).apply {
            text = "Choose how repetitions are signaled and counted."
            textSize = 13f
            setTextColor(0xFF94A3B8.toInt())
            setPadding(0, 4.dp(), 0, 10.dp())
        }
        sheetBody.addView(note)

        // Divider
        sheetBody.addView(View(context).apply {
            setBackgroundColor(0xFF283545.toInt())
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 1.dp()).apply {
                bottomMargin = 10.dp()
            }
        })

        // Option 1: App cues (Available and selected)
        val appCuedRow = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(14.dp(), 12.dp(), 14.dp(), 12.dp())
            background = GradientDrawable().apply {
                setColor(0xFF242E3C.toInt())
                cornerRadius = 10.dp().toFloat()
                setStroke(1.dp(), 0xFFEF4444.toInt())
            }
            val icon = AppIconView(context, AppIcon.VOLUME, sizeDp = 20).apply {
                setIconColor(0xFFEF4444.toInt())
            }
            addView(icon, LinearLayout.LayoutParams(20.dp(), 20.dp()).apply { marginEnd = 12.dp() })

            val textCol = LinearLayout(context).apply {
                orientation = LinearLayout.VERTICAL
                val title = TextView(context).apply {
                    text = "App cues"
                    textSize = 15f
                    typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
                    setTextColor(Color.WHITE)
                }
                addView(title)
                val sub = TextView(context).apply {
                    text = "Spoken movement cues and cadence"
                    textSize = 12.5f
                    setTextColor(0xFF94A3B8.toInt())
                }
                addView(sub)
            }
            addView(textCol, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))

            val check = AppIconView(context, AppIcon.CHECK, sizeDp = 20).apply {
                setIconColor(0xFFEF4444.toInt())
            }
            addView(check, LinearLayout.LayoutParams(20.dp(), 20.dp()))

            setOnClickListener {
                dismiss()
            }
        }
        sheetBody.addView(appCuedRow, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
            bottomMargin = 8.dp()
        })

        // Option 2: Self-cued (Not available yet)
        val selfCuedRow = buildUnavailableRow(
            titleText = "Self-cued",
            subtitleText = "Athlete chooses their own tempo (not available yet)",
            block = CaptureStartBlock.UNSUPPORTED_SELF_CUED,
        )
        sheetBody.addView(selfCuedRow, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
            bottomMargin = 8.dp()
        })

        // Option 3: Automatic movement count (Not available yet)
        val autoCountRow = buildUnavailableRow(
            titleText = "Automatic movement count",
            subtitleText = "Real-time rep detection (not available yet)",
            block = CaptureStartBlock.UNSUPPORTED_FREE_AUTO_COUNT,
        )
        sheetBody.addView(autoCountRow, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))

        setContentView(sheetBody)
    }

    private fun buildUnavailableRow(titleText: String, subtitleText: String, block: CaptureStartBlock): View {
        return LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(14.dp(), 12.dp(), 14.dp(), 12.dp())
            background = GradientDrawable().apply {
                setColor(0xFF171F2A.toInt())
                cornerRadius = 10.dp().toFloat()
                setStroke(1.dp(), 0xFF283545.toInt())
            }
            val icon = AppIconView(context, AppIcon.CLOCK, sizeDp = 20).apply {
                setIconColor(0xFF64748B.toInt())
            }
            addView(icon, LinearLayout.LayoutParams(20.dp(), 20.dp()).apply { marginEnd = 12.dp() })

            val textCol = LinearLayout(context).apply {
                orientation = LinearLayout.VERTICAL
                val title = TextView(context).apply {
                    text = titleText
                    textSize = 15f
                    typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
                    setTextColor(0xFFCBD5E1.toInt())
                }
                addView(title)
                val sub = TextView(context).apply {
                    text = subtitleText
                    textSize = 12.5f
                    setTextColor(0xFF64748B.toInt())
                }
                addView(sub)
            }
            addView(textCol, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))

            val badge = TextView(context).apply {
                text = "Soon"
                textSize = 11f
                typeface = Typeface.create("sans-serif-bold", Typeface.BOLD)
                setTextColor(0xFF94A3B8.toInt())
                background = GradientDrawable().apply {
                    setColor(0xFF242E3C.toInt())
                    cornerRadius = 6.dp().toFloat()
                }
                setPadding(6.dp(), 2.dp(), 6.dp(), 2.dp())
            }
            addView(badge)

            setOnClickListener {
                onUnavailableSelected(block)
            }
        }
    }
}
