package dk.lasse.karatecliprecorder.movement

import android.app.Dialog
import android.content.Context
import android.content.res.ColorStateList
import android.graphics.Color
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
 * Full-screen / expanded inspection dialog for Movement Presentation Player.
 * Synchronizes with the shared MovementTimelineState.
 */
class MovementExpandedInspectionDialog(
    context: Context,
    val timelineState: MovementTimelineState,
    private val videoFile: File?,
    private val presentationData: MovementPresentationData,
) : Dialog(context), MovementTimelineState.TimelineListener {

    private val density = context.resources.displayMetrics.density

    internal val videoView = VideoView(context)
    internal var mediaPlayer: android.media.MediaPlayer? = null
    private val videoSeeking = MovementVideoSeeking()
    val overlayView = MovementAnalysisOverlayView(context, timelineState).apply {
        overlayDefinition = presentationData.overlayDefinition
    }
    val graphView = MovementGraphView(context, timelineState, isExpanded = true).apply {
        plotDefinition = presentationData.plotDefinitions[timelineState.selectedPlotKey]
            ?: presentationData.plotDefinitions.values.firstOrNull()
    }

    private var cleanedUp = false
    internal val unavailableLabel = TextView(context).apply {
        textSize = 14f
        setTextColor(ContextCompat.getColor(context, R.color.app_text_secondary))
        gravity = Gravity.CENTER
        visibility = View.GONE
    }

    private val videoTab = Button(context)
    private val analysisTab = Button(context)
    private val graphTab = Button(context)

    private val timelineBar = SeekBar(context).apply {
        max = 1000
        contentDescription = "Precision movement timeline"
    }
    private val timeDisplay = TextView(context).apply {
        textSize = 14f
        typeface = Typeface.MONOSPACE
        setTextColor(ContextCompat.getColor(context, R.color.app_text_primary))
    }

    private val playButton = ImageButton(context)

    private var lastTickMs = 0L
    private val handler = Handler(Looper.getMainLooper())
    private val playbackTick = object : Runnable {
        override fun run() {
            if (timelineState.isPlaying && !cleanedUp) {
                val now = SystemClock.uptimeMillis()
                val elapsedUs = (now - lastTickMs).coerceAtLeast(0L) * 1000L
                lastTickMs = now
                if (timelineState.currentMode != PlayerMode.GRAPH && videoView.isPlaying) {
                    onVideoPosition(videoView.currentPosition)
                } else if (timelineState.currentMode == PlayerMode.GRAPH || videoFile?.isFile != true) {
                    val nextUs = timelineState.currentTimestampUs + (elapsedUs * timelineState.playbackRate).toLong()
                    if (nextUs >= timelineState.playbackEndUs) {
                        timelineState.updatePlaybackPositionUs(timelineState.playbackEndUs)
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
        timelineState.addListener(this)
        bindMovementVideoViewport(videoView, overlayView, videoFile?.isFile == true) { mediaPlayer }

        val root = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(ContextCompat.getColor(context, R.color.app_background))
            setPadding(16.dp(), 12.dp(), 16.dp(), 16.dp())
        }

        // Header bar
        val header = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        val title = TextView(context).apply {
            text = "Movement ${presentationData.displayedNumber} Inspection"
            textSize = 18f
            typeface = Typeface.create("sans-serif", Typeface.BOLD)
            setTextColor(ContextCompat.getColor(context, R.color.app_text_primary))
        }
        val closeBtn = Button(context).apply {
            text = "Done"
            isAllCaps = false
            minHeight = 44.dp()
            setOnClickListener { dismiss() }
        }
        header.addView(title, LinearLayout.LayoutParams(0, -2, 1f))
        header.addView(closeBtn, LinearLayout.LayoutParams(-2, -2))
        root.addView(header)

        // Mode switch tabs
        val modeRow = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_HORIZONTAL
            setPadding(0, 8.dp(), 0, 8.dp())
        }
        fun styleTab(btn: Button, text: String, mode: PlayerMode) {
            btn.apply {
                this.text = text
                textSize = 13f
                isAllCaps = false
                minHeight = 40.dp()
                setPadding(16.dp(), 6.dp(), 16.dp(), 6.dp())
                setOnClickListener { timelineState.setMode(mode) }
            }
        }
        styleTab(videoTab, "Video", PlayerMode.VIDEO)
        styleTab(analysisTab, "Analysis", PlayerMode.ANALYSIS)
        styleTab(graphTab, "Graph", PlayerMode.GRAPH)
        modeRow.addView(videoTab, LinearLayout.LayoutParams(0, -2, 1f).apply { marginEnd = 4.dp() })
        modeRow.addView(analysisTab, LinearLayout.LayoutParams(0, -2, 1f).apply { marginEnd = 4.dp() })
        modeRow.addView(graphTab, LinearLayout.LayoutParams(0, -2, 1f))
        root.addView(modeRow)

        // Main Surface
        val surface = FrameLayout(context).apply {
            background = GradientDrawable().apply {
                setColor(ContextCompat.getColor(context, R.color.profile_avatar_background))
                cornerRadius = 12f * density
                setStroke(1.dp(), ContextCompat.getColor(context, R.color.app_border))
            }
            clipToOutline = true
            addView(videoView, FrameLayout.LayoutParams(-1, -1, Gravity.CENTER))
            addView(overlayView, FrameLayout.LayoutParams(-1, -1))
            addView(graphView, FrameLayout.LayoutParams(-1, -1))
            addView(unavailableLabel, FrameLayout.LayoutParams(-1, -1))
        }
        root.addView(surface, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f))

        // Precision timeline
        val timelineRow = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(0, 10.dp(), 0, 6.dp())
        }
        timelineBar.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(bar: SeekBar?, progress: Int, fromUser: Boolean) {
                if (fromUser) timelineState.seekProgress(progress / 1000.0)
            }
            override fun onStartTrackingTouch(bar: SeekBar?) { timelineState.setPlaying(false) }
            override fun onStopTrackingTouch(bar: SeekBar?) {}
        })
        timelineRow.addView(timelineBar, LinearLayout.LayoutParams(0, -2, 1f))
        timelineRow.addView(timeDisplay, LinearLayout.LayoutParams(-2, -2).apply { marginStart = 8.dp() })
        root.addView(timelineRow)

        // Controls bar: step prev, play, step next, rate spinner, event jump spinner
        val controlsBar = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }

        val prevBtn = Button(context).apply {
            contentDescription = "Previous frame sample"
            text = "‹"
            textSize = 20f
            minHeight = 48.dp()
            isEnabled = timelineState.canStepSamples
            alpha = if (timelineState.canStepSamples) 1f else 0.4f
            setOnClickListener { timelineState.setPlaying(false); timelineState.stepPreviousSample() }
        }
        playButton.apply {
            setImageResource(R.drawable.ic_player_play)
            setBackgroundColor(Color.TRANSPARENT)
            imageTintList = ColorStateList.valueOf(ContextCompat.getColor(context, R.color.app_accent))
            minimumHeight = 48.dp()
            minimumWidth = 48.dp()
            setOnClickListener { timelineState.setPlaying(!timelineState.isPlaying) }
        }
        val nextBtn = Button(context).apply {
            contentDescription = "Next frame sample"
            text = "›"
            textSize = 20f
            minHeight = 48.dp()
            isEnabled = timelineState.canStepSamples
            alpha = if (timelineState.canStepSamples) 1f else 0.4f
            setOnClickListener { timelineState.setPlaying(false); timelineState.stepNextSample() }
        }

        controlsBar.addView(prevBtn, LinearLayout.LayoutParams(48.dp(), 48.dp()))
        controlsBar.addView(playButton, LinearLayout.LayoutParams(48.dp(), 48.dp()).apply { marginStart = 8.dp() })
        controlsBar.addView(nextBtn, LinearLayout.LayoutParams(48.dp(), 48.dp()).apply { marginStart = 8.dp() })

        // Speed spinner
        val speedOptions = listOf("1.0×", "0.75×", "0.50×", "0.25×", "0.10×")
        val speedValues = listOf(1.0, 0.75, 0.5, 0.25, 0.1)
        val initialSpeedPos = speedValues.indexOfFirst { kotlin.math.abs(it - timelineState.playbackRate) < 0.01 }.coerceAtLeast(0)
        val speedSpinner = Spinner(context).apply {
            contentDescription = "Playback speed"
            adapter = ArrayAdapter(context, android.R.layout.simple_spinner_dropdown_item, speedOptions)
            setSelection(initialSpeedPos)
            onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
                override fun onItemSelected(p0: AdapterView<*>?, p1: View?, pos: Int, p3: Long) {
                    timelineState.setPlaybackRate(speedValues[pos])
                }
                override fun onNothingSelected(p0: AdapterView<*>?) {}
            }
        }
        controlsBar.addView(speedSpinner, LinearLayout.LayoutParams(-2, 48.dp()).apply { marginStart = 12.dp() })

        // Jump to named event spinner
        val eventLabels = listOf("Jump to…") + presentationData.namedEvents.map { it.name }
        val jumpSpinner = Spinner(context).apply {
            contentDescription = "Jump to movement event"
            adapter = ArrayAdapter(context, android.R.layout.simple_spinner_dropdown_item, eventLabels)
            onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
                override fun onItemSelected(p0: AdapterView<*>?, p1: View?, pos: Int, p3: Long) {
                    if (pos > 0) {
                        val event = presentationData.namedEvents[pos - 1]
                        timelineState.setPlaying(false)
                        timelineState.seekUs(event.timestampUs)
                        setSelection(0)
                    }
                }
                override fun onNothingSelected(p0: AdapterView<*>?) {}
            }
        }
        controlsBar.addView(jumpSpinner, LinearLayout.LayoutParams(0, 48.dp(), 1f).apply { marginStart = 8.dp() })

        root.addView(controlsBar)
        setContentView(root)

        if (videoFile != null && videoFile.isFile) {
            videoView.setVideoPath(videoFile.absolutePath)
            videoView.setOnPreparedListener { mp -> onVideoPrepared(mp) }
            videoView.setOnCompletionListener { timelineState.setPlaying(false) }
        }

        updateModeViews(timelineState.currentMode)
        updateTimelineProgress(timelineState.progress)
        updatePlaybackState(timelineState.isPlaying)
    }

    internal fun onVideoPrepared(mp: android.media.MediaPlayer) {
        mediaPlayer = mp
        videoSeeking.onPrepared(mp)
        overlayView.invalidate()
        if (timelineState.currentMode != PlayerMode.GRAPH) videoSeeking.seekToUs(timelineState.currentTimestampUs)
        if (!cleanedUp) {
            onPlaybackRateChanged(timelineState.playbackRate)
            if (timelineState.isPlaying && timelineState.currentMode != PlayerMode.GRAPH) videoView.start()
        } else {
            runCatching { if (mp.isPlaying) mp.pause() }
            videoView.pause()
        }
    }

    internal fun cleanup() {
        if (cleanedUp) return
        cleanedUp = true
        timelineState.removeListener(this)
        timelineState.removeListener(overlayView)
        timelineState.removeListener(graphView)
        handler.removeCallbacks(playbackTick)
        timelineState.setPlaying(false)
        videoSeeking.clear()
        videoView.stopPlayback()
        mediaPlayer = null
    }

    override fun onStop() {
        cleanup()
        super.onStop()
    }

    override fun dismiss() {
        cleanup()
        super.dismiss()
    }

    override fun onStart() {
        super.onStart()
        window?.setLayout(ViewGroup.LayoutParams.MATCH_PARENT, (context.resources.displayMetrics.heightPixels * 0.92).toInt())
    }

    override fun onTimestampChanged(timestampUs: Long, progress: Double) {
        updateTimelineProgress(progress)
    }

    override fun onSeekRequested(timestampUs: Long) {
        if (cleanedUp || timelineState.currentMode == PlayerMode.GRAPH) return
        videoSeeking.seekToUs(timestampUs)
    }

    internal fun onVideoPosition(positionMs: Int) {
        if (cleanedUp || videoSeeking.isPending || !timelineState.isPlaying || timelineState.currentMode == PlayerMode.GRAPH) return
        val positionUs = positionMs * 1000L
        timelineState.updatePlaybackPositionUs(positionUs)
        if (positionUs >= timelineState.playbackEndUs) timelineState.setPlaying(false)
    }

    override fun onModeChanged(mode: PlayerMode) {
        val leavingGraph = graphView.visibility == View.VISIBLE && mode != PlayerMode.GRAPH
        updateModeViews(mode)
        if (leavingGraph && !cleanedUp) videoSeeking.seekToUs(timelineState.currentTimestampUs)
        updatePlaybackState(timelineState.isPlaying)
    }

    override fun onSelectedPlotKeyChanged(key: String?) {
        graphView.plotDefinition = presentationData.plotDefinitions[key]
            ?: presentationData.plotDefinitions.values.firstOrNull()
    }

    override fun onPlaybackStateChanged(isPlaying: Boolean) {
        updatePlaybackState(isPlaying)
    }

    override fun onPlaybackRateChanged(rate: Double) {
        if (cleanedUp) return
        lastTickMs = SystemClock.uptimeMillis()
        mediaPlayer?.let { mp ->
            runCatching { applyMovementPlaybackRate(mp, videoView, rate, timelineState.isPlaying && timelineState.currentMode != PlayerMode.GRAPH) }
        }
    }

    private fun updateModeViews(mode: PlayerMode) {
        fun applyTabStyle(btn: Button, isActive: Boolean) {
            btn.background = GradientDrawable().apply {
                setColor(if (isActive) ContextCompat.getColor(context, R.color.app_accent) else ContextCompat.getColor(context, R.color.home_card_surface))
                cornerRadius = 6f * density
                if (!isActive) setStroke(1.dp(), ContextCompat.getColor(context, R.color.app_border))
            }
            btn.setTextColor(if (isActive) Color.WHITE else ContextCompat.getColor(context, R.color.app_text_primary))
            btn.isSelected = isActive
        }

        applyTabStyle(videoTab, mode == PlayerMode.VIDEO)
        applyTabStyle(analysisTab, mode == PlayerMode.ANALYSIS)
        applyTabStyle(graphTab, mode == PlayerMode.GRAPH)

        val hasVideo = videoFile != null && videoFile.isFile
        val notice = when (mode) {
            PlayerMode.VIDEO -> if (hasVideo) null else "Video media is unavailable for this movement."
            PlayerMode.ANALYSIS -> presentationData.analysisNotice
            else -> null
        }
        unavailableLabel.text = notice
        unavailableLabel.visibility = if (notice == null) View.GONE else View.VISIBLE
        unavailableLabel.gravity = if (mode == PlayerMode.ANALYSIS && overlayView.overlayDefinition != null) Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL else Gravity.CENTER
        unavailableLabel.setPadding(8.dp(), 8.dp(), 8.dp(), 8.dp())
        when (mode) {
            PlayerMode.VIDEO -> {
                videoView.visibility = if (hasVideo) View.VISIBLE else View.GONE
                overlayView.visibility = View.GONE
                graphView.visibility = View.GONE
            }
            PlayerMode.ANALYSIS -> {
                videoView.visibility = if (hasVideo) View.VISIBLE else View.GONE
                overlayView.visibility = View.VISIBLE
                graphView.visibility = View.GONE
            }
            PlayerMode.GRAPH -> {
                videoView.pause()
                runCatching { mediaPlayer?.let { if (it.isPlaying) it.pause() } }
                videoView.visibility = View.GONE
                overlayView.visibility = View.GONE
                graphView.visibility = View.VISIBLE
            }
            PlayerMode.POSE -> {
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
        if (cleanedUp) return
        if (playing) {
            playButton.setImageResource(R.drawable.ic_player_pause)
            playButton.contentDescription = "Pause"
            onPlaybackRateChanged(timelineState.playbackRate)
            if (timelineState.currentMode != PlayerMode.GRAPH && videoFile?.isFile == true && !videoView.isPlaying) {
                videoView.start()
            }
            handler.removeCallbacks(playbackTick)
            lastTickMs = SystemClock.uptimeMillis()
            handler.post(playbackTick)
        } else {
            playButton.setImageResource(R.drawable.ic_player_play)
            playButton.contentDescription = "Play"
            videoView.pause()
            runCatching { mediaPlayer?.let { if (it.isPlaying) it.pause() } }
            handler.removeCallbacks(playbackTick)
        }
    }

    private fun Int.dp() = (this * density).roundToInt()
}
