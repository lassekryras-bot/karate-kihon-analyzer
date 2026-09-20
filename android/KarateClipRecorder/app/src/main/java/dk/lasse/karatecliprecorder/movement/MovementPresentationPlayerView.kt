package dk.lasse.karatecliprecorder.movement

import android.content.Context
import android.content.res.ColorStateList
import android.graphics.Color
import android.graphics.RectF
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Handler
import android.os.SystemClock
import android.os.Looper
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.*
import androidx.core.content.ContextCompat
import dk.lasse.karatecliprecorder.R
import java.io.File
import kotlin.math.roundToInt

/**
 * Reusable synchronized presentation component for movements.
 * Exposes Video | Analysis | Graph modes driven by one MovementTimelineState.
 */
class MovementPresentationPlayerView(
    context: Context,
    val timelineState: MovementTimelineState,
    private val videoFile: File?,
) : LinearLayout(context), MovementTimelineState.TimelineListener {

    private val density = resources.displayMetrics.density

    internal val videoView = VideoView(context)
    internal var mediaPlayer: android.media.MediaPlayer? = null
    private val videoSeeking = MovementVideoSeeking()
    val overlayView = MovementAnalysisOverlayView(context, timelineState)
    val graphView = MovementGraphView(context, timelineState, isExpanded = false)
    internal val unavailableLabel = TextView(context).apply {
        textSize = 14f
        setTextColor(ContextCompat.getColor(context, R.color.app_text_secondary))
        gravity = Gravity.CENTER
        visibility = View.GONE
    }

    private val videoContainer = FrameLayout(context)
    internal val cropContainer = FrameLayout(context)

    // Controls
    private val modeSwitcher = LinearLayout(context)
    internal val videoTab = Button(context)
    internal val analysisTab = Button(context)
    internal val graphTab = Button(context)

    private val timelineBar = SeekBar(context).apply {
        max = 1000
        contentDescription = "Movement timeline"
    }
    private val timeDisplay = TextView(context).apply {
        textSize = 12f
        typeface = Typeface.MONOSPACE
        setTextColor(ContextCompat.getColor(context, R.color.app_text_primary))
        gravity = Gravity.CENTER_VERTICAL
    }

    private val playButton = ImageButton(context)
    private val prevStepButton = Button(context)
    private val nextStepButton = Button(context)
    private val expandButton = ImageButton(context)

    var onExpandRequested: () -> Unit = {}
    var plotDefinitions: Map<String, MovementPlotDefinition> = emptyMap()
        set(value) {
            field = value
            val plot = value[timelineState.selectedPlotKey] ?: value.values.firstOrNull()
            graphView.plotDefinition = plot
        }

    var analysisNotice: String? = "Analysis evidence is unavailable for this movement."
        set(value) {
            field = value
            updateModeViews(timelineState.currentMode)
        }

    var verticalBounds: VerticalBounds = VerticalBounds.FULL
        set(value) {
            field = value
            applyViewport()
            overlayView.invalidate()
        }

    var isPlaybackActive: Boolean = true
        private set

    private var lastTickMs = 0L
    private val handler = Handler(Looper.getMainLooper())
    private val playbackTick = object : Runnable {
        override fun run() {
            if (timelineState.isPlaying && isPlaybackActive) {
                val now = SystemClock.uptimeMillis()
                val elapsedUs = (now - lastTickMs).coerceAtLeast(0L) * 1000L
                lastTickMs = now
                if (timelineState.currentMode != PlayerMode.GRAPH && videoView.isPlaying) {
                    onVideoPosition(videoView.currentPosition)
                } else if (timelineState.currentMode == PlayerMode.GRAPH || videoFile?.isFile != true) {
                    val nextUs = timelineState.currentTimestampUs + (elapsedUs * timelineState.playbackRate).toLong()
                    val targetLimit = timelineState.canonicalImpactUs ?: timelineState.playbackEndUs
                    if (nextUs >= targetLimit) {
                        timelineState.updatePlaybackPositionUs(targetLimit)
                        timelineState.setPlaying(false)
                    } else {
                        timelineState.updatePlaybackPositionUs(nextUs)
                    }
                }
                if (timelineState.isPlaying) handler.postDelayed(this, 33)
            }
        }
    }

    init {
        orientation = VERTICAL
        timelineState.addListener(this)

        setupSurface()
        setupControls()

        bindMovementVideoViewport(cropContainer, videoView, overlayView, videoFile?.isFile == true, { verticalBounds }) { mediaPlayer }
        videoContainer.addOnLayoutChangeListener { _, _, _, _, _, _, _, _, _ -> applyViewport() }

        if (videoFile != null && videoFile.isFile) {
            videoView.setVideoPath(videoFile.absolutePath)
            videoView.setOnPreparedListener { mp -> onVideoPrepared(mp) }
            videoView.setOnCompletionListener {
                timelineState.setPlaying(false)
            }
        } else {
            unavailableLabel.text = "Video media is unavailable for this movement."
        }

        updateModeViews(timelineState.currentMode)
        updateTimelineProgress(timelineState.progress)
        updatePlaybackState(timelineState.isPlaying)
    }

    private fun applyViewport() {
        val mp = mediaPlayer ?: return
        if (videoContainer.width > 0 && videoContainer.height > 0) {
            applyMovementVideoViewport(
                cropContainer = cropContainer,
                video = videoView,
                overlay = overlayView,
                viewBounds = RectF(0f, 0f, videoContainer.width.toFloat(), videoContainer.height.toFloat()),
                videoWidth = mp.videoWidth,
                videoHeight = mp.videoHeight,
                verticalBounds = verticalBounds,
            )
        }
    }

    fun setPlaybackActive(active: Boolean) {
        if (isPlaybackActive == active) return
        isPlaybackActive = active
        if (!active) {
            videoView.pause()
            runCatching { mediaPlayer?.let { if (it.isPlaying) it.pause() } }
            handler.removeCallbacks(playbackTick)
        } else {
            if (timelineState.currentMode != PlayerMode.GRAPH) videoSeeking.seekToUs(timelineState.currentTimestampUs)
            onPlaybackRateChanged(timelineState.playbackRate)
            updatePlaybackState(timelineState.isPlaying)
        }
    }

    internal fun onVideoPrepared(mp: android.media.MediaPlayer) {
        mediaPlayer = mp
        applyViewport()
        videoSeeking.onPrepared(mp)
        overlayView.invalidate()
        if (timelineState.currentMode != PlayerMode.GRAPH) videoSeeking.seekToUs(timelineState.currentTimestampUs)
        if (isPlaybackActive) {
            onPlaybackRateChanged(timelineState.playbackRate)
            if (timelineState.isPlaying && timelineState.currentMode != PlayerMode.GRAPH) videoView.start()
        } else {
            runCatching { if (mp.isPlaying) mp.pause() }
            videoView.pause()
        }
    }

    private fun setupSurface() {
        val surfaceHeight = (220 * density).roundToInt()
        cropContainer.apply {
            clipChildren = true
            clipToOutline = true
            outlineProvider = android.view.ViewOutlineProvider.BOUNDS
            addView(videoView, FrameLayout.LayoutParams(-1, -1, Gravity.CENTER))
            addView(overlayView, FrameLayout.LayoutParams(-1, -1))
        }
        videoContainer.apply {
            background = GradientDrawable().apply {
                setColor(ContextCompat.getColor(context, R.color.profile_avatar_background))
                cornerRadius = 12f * density
                setStroke(1.dp(), ContextCompat.getColor(context, R.color.app_border))
            }
            clipToOutline = true

            addView(cropContainer, FrameLayout.LayoutParams(-1, -1))
            addView(graphView, FrameLayout.LayoutParams(-1, -1))
            addView(unavailableLabel, FrameLayout.LayoutParams(-1, -1, Gravity.CENTER))
        }
        addView(videoContainer, LayoutParams(LayoutParams.MATCH_PARENT, surfaceHeight))
    }

    private fun setupControls() {
        val controlsCard = LinearLayout(context).apply {
            orientation = VERTICAL
            setPadding(0, 8.dp(), 0, 0)
        }

        // 1. Mode Switcher Tabs
        modeSwitcher.apply {
            orientation = HORIZONTAL
            gravity = Gravity.CENTER_HORIZONTAL
            setPadding(0, 4.dp(), 0, 6.dp())
        }

        fun styleTab(btn: Button, text: String, mode: PlayerMode) {
            btn.apply {
                this.text = text
                textSize = 12f
                isAllCaps = false
                minHeight = 36.dp()
                setPadding(16.dp(), 4.dp(), 16.dp(), 4.dp())
                setOnClickListener { timelineState.setMode(mode) }
            }
        }
        styleTab(videoTab, "Video", PlayerMode.VIDEO)
        styleTab(analysisTab, "Analysis", PlayerMode.ANALYSIS)
        styleTab(graphTab, "Graph", PlayerMode.GRAPH)

        modeSwitcher.addView(videoTab, LayoutParams(0, -2, 1f).apply { marginEnd = 4.dp() })
        modeSwitcher.addView(analysisTab, LayoutParams(0, -2, 1f).apply { marginEnd = 4.dp() })
        modeSwitcher.addView(graphTab, LayoutParams(0, -2, 1f))
        controlsCard.addView(modeSwitcher)

        // 2. Timeline seekbar and readout
        val timelineRow = LinearLayout(context).apply {
            orientation = HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        timelineBar.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(bar: SeekBar?, progress: Int, fromUser: Boolean) {
                if (fromUser) {
                    timelineState.seekProgress(progress / 1000.0)
                }
            }
            override fun onStartTrackingTouch(bar: SeekBar?) { timelineState.setPlaying(false) }
            override fun onStopTrackingTouch(bar: SeekBar?) {}
        })
        timelineRow.addView(timelineBar, LayoutParams(0, -2, 1f))
        timelineRow.addView(timeDisplay, LayoutParams(-2, -2).apply { marginStart = 8.dp() })
        controlsCard.addView(timelineRow)

        // 3. Playback buttons row
        val buttonRow = LinearLayout(context).apply {
            orientation = HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }

        prevStepButton.apply {
            text = "‹"
            textSize = 18f
            contentDescription = "Previous frame sample"
            minHeight = 44.dp()
            isEnabled = timelineState.canStepSamples
            alpha = if (timelineState.canStepSamples) 1f else 0.4f
            setOnClickListener { timelineState.setPlaying(false); timelineState.stepPreviousSample() }
        }

        playButton.apply {
            updatePlayButtonIcon(timelineState.playbackControlState)
            setBackgroundColor(Color.TRANSPARENT)
            imageTintList = ColorStateList.valueOf(ContextCompat.getColor(context, R.color.app_accent))
            minimumHeight = 48.dp()
            minimumWidth = 48.dp()
            setOnClickListener { timelineState.togglePlayOrReplay() }
        }

        nextStepButton.apply {
            text = "›"
            textSize = 18f
            contentDescription = "Next frame sample"
            minHeight = 44.dp()
            isEnabled = timelineState.canStepSamples
            alpha = if (timelineState.canStepSamples) 1f else 0.4f
            setOnClickListener { timelineState.setPlaying(false); timelineState.stepNextSample() }
        }

        expandButton.apply {
            contentDescription = "Expand inspection player"
            setImageResource(R.drawable.ic_player_expand)
            setBackgroundColor(Color.TRANSPARENT)
            imageTintList = ColorStateList.valueOf(ContextCompat.getColor(context, R.color.app_text_primary))
            minimumHeight = 44.dp()
            minimumWidth = 44.dp()
            setOnClickListener { onExpandRequested() }
        }

        buttonRow.addView(prevStepButton, LayoutParams(48.dp(), 44.dp()))
        buttonRow.addView(playButton, LayoutParams(48.dp(), 48.dp()).apply { marginStart = 12.dp() })
        buttonRow.addView(nextStepButton, LayoutParams(48.dp(), 44.dp()).apply { marginStart = 12.dp() })
        buttonRow.addView(View(context), LayoutParams(0, 1, 1f))
        buttonRow.addView(expandButton, LayoutParams(44.dp(), 44.dp()))

        controlsCard.addView(buttonRow)
        addView(controlsCard)
    }

    override fun onTimestampChanged(timestampUs: Long, progress: Double) {
        updateTimelineProgress(progress)
    }

    override fun onSeekRequested(timestampUs: Long) {
        if (!isPlaybackActive || timelineState.currentMode == PlayerMode.GRAPH) return
        videoSeeking.seekToUs(timestampUs)
    }

    internal fun onVideoPosition(positionMs: Int) {
        if (!isPlaybackActive || videoSeeking.isPending || !timelineState.isPlaying || timelineState.currentMode == PlayerMode.GRAPH) return
        val positionUs = positionMs * 1000L
        val targetLimit = timelineState.canonicalImpactUs ?: timelineState.playbackEndUs
        timelineState.updatePlaybackPositionUs(positionUs)
        if (positionUs >= targetLimit) timelineState.setPlaying(false)
    }

    override fun onModeChanged(mode: PlayerMode) {
        val leavingGraph = graphView.visibility == View.VISIBLE && mode != PlayerMode.GRAPH
        updateModeViews(mode)
        if (leavingGraph && isPlaybackActive) videoSeeking.seekToUs(timelineState.currentTimestampUs)
        if (isPlaybackActive) updatePlaybackState(timelineState.isPlaying)
    }

    override fun onSelectedPlotKeyChanged(key: String?) {
        val plot = plotDefinitions[key] ?: plotDefinitions.values.firstOrNull()
        graphView.plotDefinition = plot
    }

    override fun onPlaybackStateChanged(isPlaying: Boolean) {
        if (!isPlaybackActive) {
            updatePlayButtonIcon(timelineState.playbackControlState)
            return
        }
        updatePlaybackState(isPlaying)
    }

    override fun onPlaybackControlStateChanged(controlState: PlaybackControlState) {
        updatePlayButtonIcon(controlState)
    }

    override fun onPlaybackRateChanged(rate: Double) {
        if (!isPlaybackActive) return
        lastTickMs = SystemClock.uptimeMillis()
        mediaPlayer?.let { mp ->
            runCatching { applyMovementPlaybackRate(mp, videoView, rate, timelineState.isPlaying && timelineState.currentMode != PlayerMode.GRAPH) }
        }
    }

    private fun updateModeViews(mode: PlayerMode) {
        fun tabColor(isActive: Boolean): Int = if (isActive) {
            ContextCompat.getColor(context, R.color.app_accent)
        } else {
            ContextCompat.getColor(context, R.color.home_card_surface)
        }
        fun textColor(isActive: Boolean): Int = if (isActive) {
            Color.WHITE
        } else {
            ContextCompat.getColor(context, R.color.app_text_primary)
        }

        fun applyTabStyle(btn: Button, isActive: Boolean) {
            btn.background = GradientDrawable().apply {
                setColor(tabColor(isActive))
                cornerRadius = 6f * density
                if (!isActive) setStroke(1.dp(), ContextCompat.getColor(context, R.color.app_border))
            }
            btn.setTextColor(textColor(isActive))
            btn.isSelected = isActive
        }

        applyTabStyle(videoTab, mode == PlayerMode.VIDEO)
        applyTabStyle(analysisTab, mode == PlayerMode.ANALYSIS)
        applyTabStyle(graphTab, mode == PlayerMode.GRAPH)

        val hasVideo = videoFile != null && videoFile.isFile
        val notice = when (mode) {
            PlayerMode.VIDEO -> if (hasVideo) null else "Video media is unavailable for this movement."
            PlayerMode.ANALYSIS -> analysisNotice
            else -> null
        }
        unavailableLabel.text = notice
        unavailableLabel.visibility = if (notice == null) View.GONE else View.VISIBLE
        unavailableLabel.gravity = if (mode == PlayerMode.ANALYSIS && overlayView.overlayDefinition != null) Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL else Gravity.CENTER
        unavailableLabel.setPadding(8.dp(), 8.dp(), 8.dp(), 8.dp())
        when (mode) {
            PlayerMode.VIDEO -> {
                cropContainer.visibility = if (hasVideo) View.VISIBLE else View.GONE
                videoView.visibility = if (hasVideo) View.VISIBLE else View.GONE
                overlayView.visibility = View.GONE
                graphView.visibility = View.GONE
            }
            PlayerMode.ANALYSIS -> {
                cropContainer.visibility = View.VISIBLE
                videoView.visibility = if (hasVideo) View.VISIBLE else View.GONE
                overlayView.visibility = View.VISIBLE
                graphView.visibility = View.GONE
            }
            PlayerMode.GRAPH -> {
                videoView.pause()
                runCatching { mediaPlayer?.let { if (it.isPlaying) it.pause() } }
                cropContainer.visibility = View.GONE
                videoView.visibility = View.GONE
                overlayView.visibility = View.GONE
                graphView.visibility = View.VISIBLE
            }
            PlayerMode.POSE -> {
                cropContainer.visibility = View.GONE
                videoView.visibility = View.GONE
                overlayView.visibility = View.GONE
                graphView.visibility = View.GONE
            }
        }
    }

    private fun updateTimelineProgress(progress: Double) {
        timelineBar.progress = (progress.coerceIn(0.0, 1.0) * 1000).toInt()
        val curSec = (timelineState.currentTimestampUs - timelineState.playbackStartUs) / 1_000_000.0
        val totalSec = timelineState.durationUs / 1_000_000.0
        timeDisplay.text = "${"%.2f".format(curSec)} / ${"%.2f".format(totalSec)} s"
    }

    private fun updatePlaybackState(playing: Boolean) {
        updatePlayButtonIcon(timelineState.playbackControlState)
        if (playing) {
            onPlaybackRateChanged(timelineState.playbackRate)
            if (timelineState.currentMode != PlayerMode.GRAPH && videoFile?.isFile == true && !videoView.isPlaying) {
                videoView.start()
            }
            handler.removeCallbacks(playbackTick)
            lastTickMs = SystemClock.uptimeMillis()
            handler.post(playbackTick)
        } else {
            videoView.pause()
            runCatching { mediaPlayer?.let { if (it.isPlaying) it.pause() } }
            handler.removeCallbacks(playbackTick)
        }
    }

    private fun updatePlayButtonIcon(controlState: PlaybackControlState) {
        when (controlState) {
            PlaybackControlState.PLAYING -> {
                playButton.setImageResource(R.drawable.ic_player_pause)
                playButton.contentDescription = "Pause"
            }
            PlaybackControlState.PAUSED_PLAY -> {
                playButton.setImageResource(R.drawable.ic_player_play)
                playButton.contentDescription = "Play"
            }
            PlaybackControlState.PAUSED_REPLAY -> {
                playButton.setImageResource(R.drawable.ic_player_replay)
                playButton.contentDescription = "Replay"
            }
        }
    }

    fun pause() {
        timelineState.setPlaying(false)
    }

    override fun onDetachedFromWindow() {
        super.onDetachedFromWindow()
        timelineState.removeListener(this)
        handler.removeCallbacks(playbackTick)
        videoSeeking.clear()
        videoView.stopPlayback()
        mediaPlayer = null
    }

    private fun Int.dp() = (this * density).roundToInt()
}
