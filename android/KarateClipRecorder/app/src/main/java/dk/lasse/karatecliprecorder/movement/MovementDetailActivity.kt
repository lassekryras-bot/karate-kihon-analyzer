package dk.lasse.karatecliprecorder.movement

import android.content.res.ColorStateList
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.*
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import dk.lasse.karateanalyzer.core.PoseFrame
import dk.lasse.karatecliprecorder.AppPreferences
import dk.lasse.karatecliprecorder.R
import dk.lasse.karatecliprecorder.SubPageHeader
import dk.lasse.karatecliprecorder.training.*
import java.io.File
import kotlin.math.roundToInt

class MovementDetailActivity : AppCompatActivity() {

    private val training by lazy { TrainingServices.get(this) }
    private val preferences by lazy { AppPreferences(this) }

    private var sessionId: String? = null
    private var movementId: String? = null
    private var displayedNumber: Int = 1

    private var savedTimestampUs: Long? = null
    private var savedMode: PlayerMode? = null
    private var savedPlotKey: String? = null
    private var savedPlaybackRate: Double = 1.0
    internal var isExpandedOpen: Boolean = false

    private lateinit var contentLayout: LinearLayout
    private lateinit var header: SubPageHeader
    internal var playerView: MovementPresentationPlayerView? = null
    internal var timelineState: MovementTimelineState? = null
    internal var expandedDialog: MovementExpandedInspectionDialog? = null

    internal var presentationData: MovementPresentationData? = null
    internal var videoFile: File? = null
    internal var loadedFrames: List<PoseFrame> = emptyList()
    internal var verticalBounds: VerticalBounds = VerticalBounds.FULL
    private var active = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        sessionId = intent.getStringExtra(EXTRA_SESSION_ID) ?: savedInstanceState?.getString(EXTRA_SESSION_ID)
        movementId = intent.getStringExtra(EXTRA_MOVEMENT_ID) ?: savedInstanceState?.getString(EXTRA_MOVEMENT_ID)
        displayedNumber = intent.getIntExtra(EXTRA_DISPLAYED_NUMBER, savedInstanceState?.getInt(EXTRA_DISPLAYED_NUMBER, 1) ?: 1)

        savedTimestampUs = savedInstanceState?.getLong("saved_timestamp_us")
        savedInstanceState?.getString("saved_mode")?.let {
            savedMode = runCatching { PlayerMode.valueOf(it) }.getOrNull()
        }
        savedPlotKey = savedInstanceState?.getString("saved_plot_key")
        savedPlaybackRate = savedInstanceState?.getDouble("saved_playback_rate", 1.0) ?: 1.0
        isExpandedOpen = savedInstanceState?.getBoolean("expanded_dialog_open") ?: false

        contentLayout = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(16.dp(), 8.dp(), 16.dp(), 24.dp())
        }

        val scrollView = ScrollView(this).apply {
            setBackgroundColor(ContextCompat.getColor(this@MovementDetailActivity, R.color.app_background))
            addView(contentLayout)
        }

        header = SubPageHeader(this, title = "Movement $displayedNumber", onBack = { finish() })

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(ContextCompat.getColor(this@MovementDetailActivity, R.color.app_background))
            addView(header, LinearLayout.LayoutParams(-1, -2))
            addView(scrollView, LinearLayout.LayoutParams(-1, 0, 1f))
        }

        androidx.core.view.ViewCompat.setOnApplyWindowInsetsListener(scrollView) { view, insets ->
            val bars = insets.getInsets(androidx.core.view.WindowInsetsCompat.Type.navigationBars())
            view.setPadding(0, 0, 0, bars.bottom)
            insets
        }

        setContentView(root)
        loadData()
    }

    override fun onStart() {
        super.onStart()
        active = true
        if (presentationData != null && playerView == null) render()
        if (isExpandedOpen && expandedDialog == null) showExpandedInspection()
        if (expandedDialog?.isShowing != true) playerView?.setPlaybackActive(true)
    }

    override fun onStop() {
        active = false
        playerView?.pause()
        playerView?.setPlaybackActive(false)
        val dialog = expandedDialog
        expandedDialog = null
        dialog?.dismiss()
        super.onStop()
    }

    override fun onSaveInstanceState(outState: Bundle) {
        outState.putString(EXTRA_SESSION_ID, sessionId)
        outState.putString(EXTRA_MOVEMENT_ID, movementId)
        outState.putInt(EXTRA_DISPLAYED_NUMBER, displayedNumber)
        timelineState?.let {
            outState.putLong("saved_timestamp_us", it.currentTimestampUs)
            outState.putString("saved_mode", it.currentMode.name)
            outState.putString("saved_plot_key", it.selectedPlotKey)
            outState.putDouble("saved_playback_rate", it.playbackRate)
        }
        outState.putBoolean("expanded_dialog_open", isExpandedOpen)
        super.onSaveInstanceState(outState)
    }

    private fun loadData() {
        val mId = movementId ?: return
        contentLayout.removeAllViews()
        contentLayout.addView(TextView(this).apply {
            text = "Loading movement detail…"
            textSize = 15f
            setTextColor(ContextCompat.getColor(this@MovementDetailActivity, R.color.app_text_secondary))
            setPadding(0, 16.dp(), 0, 0)
        })

        training.submit({ repo ->
            var evidence = repo.movementEvidence(mId) ?: return@submit null
            val dispNum = repo.movementDisplayedNumber(mId)
            val rec = repo.recording(evidence.movement.sessionId)
            val video = rec?.let { repo.file(it.filePath) }

            // Resolve exact landmark track matching analysis provenance
            val preferredAnalysis = evidence.presentationAnalysis()
            val preferredTrackId = preferredAnalysis?.landmarkTrackId ?: evidence.movement.segmentationTrackId
            var track = if (preferredTrackId != null) {
                evidence.landmarkTracks.firstOrNull {
                    it.landmarkTrackId == preferredTrackId && it.state == ProcessingState.COMPLETED && it.sourceState == SourceState.AVAILABLE
                }
            } else null
            val frames: List<PoseFrame> = if (track != null) {
                runCatching {
                    LandmarkFiles.read(repo.file(track.filePath), track.sha256, track.formatId)
                }.getOrDefault(emptyList())
            } else emptyList()

            // Resolve and persist canonical geometry outside UI thread using storage-resolved file reference
            if (rec != null) {
                CanonicalGeometryStorageAdapter.resolveTrackGeometry(
                    recording = rec,
                    track = track,
                    fileResolver = { repo.file(it) },
                    onPersistDescriptor = { updatedTrack, updatedRecording, _ ->
                        if (updatedTrack != null) {
                            repo.updateTrack(updatedTrack)
                            track = updatedTrack
                        }
                        repo.updateRecording(updatedRecording)
                        evidence = evidence.copy(
                            recording = updatedRecording,
                            landmarkTracks = if (updatedTrack != null) {
                                evidence.landmarkTracks.map {
                                    if (it.landmarkTrackId == updatedTrack.landmarkTrackId) updatedTrack else it
                                }
                            } else evidence.landmarkTracks,
                        )
                    }
                )
            }

            Triple(evidence, dispNum to video, frames)
        }) { result ->
            if (isDestroyed) return@submit
            val triple = result.getOrNull()
            if (triple == null) {
                renderNotFound()
                return@submit
            }
            val evidence = triple.first
            val dispNum = triple.second.first
            val video = triple.second.second
            val frames = triple.third

            displayedNumber = dispNum
            videoFile = video
            loadedFrames = frames
            presentationData = MovementPresentationMapper.map(evidence, dispNum, frames)
            if (active) render()
        }
    }

    private fun renderNotFound() {
        contentLayout.removeAllViews()
        contentLayout.addView(TextView(this).apply {
            text = "This movement could not be found."
            textSize = 16f
            setTextColor(ContextCompat.getColor(this@MovementDetailActivity, R.color.app_error))
            setPadding(0, 24.dp(), 0, 0)
        })
    }

    private fun render() {
        val data = presentationData ?: return
        contentLayout.removeAllViews()
        header.setTitle("Movement ${data.displayedNumber}")

        // 1. Movement Title & Context
        val titleBlock = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(0, 4.dp(), 0, 12.dp())
        }
        val contextText = TextView(this).apply {
            text = data.contextSubtitle
            textSize = 15f
            typeface = Typeface.create("sans-serif", Typeface.BOLD)
            setTextColor(ContextCompat.getColor(this@MovementDetailActivity, R.color.app_text_secondary))
        }
        titleBlock.addView(contextText)
        contentLayout.addView(titleBlock)

        // 2. Movement Presentation Player
        val initialTimestamp = savedTimestampUs ?: data.canonicalImpactUs
        val initialMode = savedMode ?: data.preferredInitialMode

        timelineState = MovementTimelineState(
            playbackStartUs = data.debugData.playbackStartUs,
            playbackEndUs = data.debugData.playbackEndUs,
            initialTimestampUs = initialTimestamp,
            initialMode = initialMode,
            initialPlotKey = savedPlotKey ?: data.plotDefinitions.keys.firstOrNull(),
            knownSampleTimestampsUs = data.knownSampleTimestampsUs,
            namedEvents = data.namedEvents,
            canonicalImpactUs = data.canonicalImpactUs,
            replayStartUs = data.debugData.playbackStartUs,
        )
        timelineState!!.setPlaybackRate(savedPlaybackRate)

        val activeSide = when (data.debugData.activeArm) {
            "LEFT" -> BodySide.LEFT
            "RIGHT" -> BodySide.RIGHT
            else -> BodySide.UNKNOWN
        }

        verticalBounds = MovementVerticalViewportCalculator.computeVerticalBounds(
            frames = loadedFrames,
            startUs = data.debugData.playbackStartUs,
            endUs = data.debugData.playbackEndUs,
        )

        playerView = MovementPresentationPlayerView(this, timelineState!!, videoFile).apply {
            this.verticalBounds = this@MovementDetailActivity.verticalBounds
            overlayView.overlayDefinition = data.overlayDefinition
            overlayView.frames = loadedFrames
            overlayView.activeSide = activeSide
            plotDefinitions = data.plotDefinitions
            analysisNotice = data.analysisNotice

            onExpandRequested = { showExpandedInspection() }
        }
        contentLayout.addView(playerView)

        // 3. Key Results Card
        contentLayout.addView(buildKeyResultsCard(data), LinearLayout.LayoutParams(-1, -2).apply { topMargin = 16.dp() })

        // 4. Needs Attention Card (only if findings exist)
        if (data.findings.isNotEmpty()) {
            contentLayout.addView(buildNeedsAttentionCard(data), LinearLayout.LayoutParams(-1, -2).apply { topMargin = 12.dp() })
        }

        // 5. Analysis Debug Card (only when developerMode is enabled)
        if (preferences.developerMode) {
            contentLayout.addView(buildAnalysisDebugCard(data.debugData), LinearLayout.LayoutParams(-1, -2).apply { topMargin = 12.dp() })
        }

        // Restore expanded dialog if needed
        if (isExpandedOpen) {
            showExpandedInspection()
        }
    }

    private fun buildKeyResultsCard(data: MovementPresentationData): View = card().apply {
        addView(TextView(this@MovementDetailActivity).apply {
            text = "Key Results"
            textSize = 17f
            typeface = Typeface.create("sans-serif", Typeface.BOLD)
            setTextColor(ContextCompat.getColor(this@MovementDetailActivity, R.color.app_text_primary))
            setPadding(0, 0, 0, 10.dp())
        })

        data.keyResults.forEachIndexed { index, item ->
            if (index > 0) {
                addView(View(this@MovementDetailActivity).apply {
                    setBackgroundColor(ContextCompat.getColor(this@MovementDetailActivity, R.color.app_divider))
                }, LinearLayout.LayoutParams(-1, 1.dp()).apply { topMargin = 8.dp(); bottomMargin = 8.dp() })
            }
            addView(buildKeyResultRow(item, data))
        }
    }

    private fun buildKeyResultRow(item: KeyResultItem, data: MovementPresentationData): View {
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(0, 4.dp(), 0, 4.dp())
            isClickable = item.isAvailable
            isFocusable = item.isAvailable
            if (item.isAvailable) {
                setOnClickListener {
                    onResultSelected(item, data)
                }
            }
        }

        val textCol = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            addView(TextView(this@MovementDetailActivity).apply {
                text = item.label
                textSize = 14f
                setTextColor(ContextCompat.getColor(this@MovementDetailActivity, R.color.app_text_secondary))
            })
            item.sublabel?.let {
                addView(TextView(this@MovementDetailActivity).apply {
                    text = it
                    textSize = 12f
                    setTextColor(ContextCompat.getColor(this@MovementDetailActivity, R.color.app_text_secondary))
                    setPadding(0, 2.dp(), 0, 0)
                })
            }
        }
        row.addView(textCol, LinearLayout.LayoutParams(0, -2, 1f))

        val valCol = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            addView(TextView(this@MovementDetailActivity).apply {
                text = item.formattedValue
                textSize = 15f
                typeface = Typeface.create("sans-serif", Typeface.BOLD)
                setTextColor(
                    if (item.isAvailable) ContextCompat.getColor(this@MovementDetailActivity, R.color.app_text_primary)
                    else ContextCompat.getColor(this@MovementDetailActivity, R.color.app_text_secondary)
                )
            })
            if (item.isAvailable) {
                addView(TextView(this@MovementDetailActivity).apply {
                    text = " ›"
                    textSize = 16f
                    setTextColor(ContextCompat.getColor(this@MovementDetailActivity, R.color.app_accent))
                })
            }
        }
        row.addView(valCol, LinearLayout.LayoutParams(-2, -2))
        return row
    }

    private fun onResultSelected(item: KeyResultItem, data: MovementPresentationData) {
        val timeline = timelineState ?: return
        timeline.setPlaying(false)
        timeline.seekUs(item.focusTimestampUs)
        timeline.setMode(item.preferredMode)

        item.plotKey?.let { key ->
            timeline.setSelectedPlotKey(key)
        }
    }

    private fun buildNeedsAttentionCard(data: MovementPresentationData): View = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        val ochre = 0xFFC25E00.toInt()
        background = GradientDrawable().apply {
            setColor(ContextCompat.getColor(this@MovementDetailActivity, R.color.skill_coach_guidance_surface))
            cornerRadius = 14f * density
            setStroke(1.dp(), ochre)
        }
        setPadding(16.dp(), 14.dp(), 16.dp(), 14.dp())

        addView(TextView(this@MovementDetailActivity).apply {
            text = "Needs Attention"
            textSize = 16f
            typeface = Typeface.create("sans-serif", Typeface.BOLD)
            setTextColor(ochre)
            setPadding(0, 0, 0, 8.dp())
        })

        data.findings.forEach { finding ->
            val findingBox = LinearLayout(this@MovementDetailActivity).apply {
                orientation = LinearLayout.VERTICAL
                isClickable = true
                isFocusable = true
                setPadding(0, 4.dp(), 0, 4.dp())
                setOnClickListener {
                    timelineState?.setPlaying(false)
                    timelineState?.seekUs(finding.focusTimestampUs)
                    timelineState?.setMode(finding.preferredMode)
                }
                addView(TextView(this@MovementDetailActivity).apply {
                    text = finding.title
                    textSize = 14f
                    typeface = Typeface.create("sans-serif", Typeface.BOLD)
                    setTextColor(ContextCompat.getColor(this@MovementDetailActivity, R.color.app_text_primary))
                })
                addView(TextView(this@MovementDetailActivity).apply {
                    text = finding.description
                    textSize = 13f
                    setTextColor(ContextCompat.getColor(this@MovementDetailActivity, R.color.app_text_secondary))
                    setPadding(0, 2.dp(), 0, 0)
                })
            }
            addView(findingBox)
        }
    }

    private fun buildAnalysisDebugCard(debug: MovementDebugData): View = card().apply {
        addView(TextView(this@MovementDetailActivity).apply {
            text = "Analysis Debug"
            textSize = 16f
            typeface = Typeface.create("sans-serif", Typeface.BOLD)
            setTextColor(ContextCompat.getColor(this@MovementDetailActivity, R.color.app_text_primary))
            setPadding(0, 0, 0, 8.dp())
        })

        fun debugRow(lbl: String, value: String, timestampUs: Long? = null) {
            val tv = TextView(this@MovementDetailActivity).apply {
                text = "$lbl: $value"
                textSize = 12f
                typeface = Typeface.MONOSPACE
                setTextColor(ContextCompat.getColor(this@MovementDetailActivity, R.color.app_text_secondary))
                setPadding(0, 2.dp(), 0, 2.dp())
                if (timestampUs != null) {
                    isClickable = true
                    isFocusable = true
                    setTextColor(ContextCompat.getColor(this@MovementDetailActivity, R.color.app_accent))
                    setOnClickListener {
                        timelineState?.setPlaying(false)
                        timelineState?.seekUs(timestampUs)
                    }
                }
            }
            addView(tv)
        }

        debugRow("Movement ID", debug.movementId)
        debugRow("Duration", "${"%.3f".format(debug.durationUs / 1_000_000.0)} s (${debug.durationUs} µs)")
        debugRow("Logical bounds", "${"%.3f".format(debug.logicalStartUs / 1_000_000.0)} – ${"%.3f".format(debug.logicalEndUs / 1_000_000.0)} s", debug.logicalStartUs)
        debugRow("Playback bounds", "${"%.3f".format(debug.playbackStartUs / 1_000_000.0)} – ${"%.3f".format(debug.playbackEndUs / 1_000_000.0)} s", debug.playbackStartUs)
        debug.canonicalAnalysisTimestampUs?.let {
            debugRow("Canonical frame", "${"%.3f".format(it / 1_000_000.0)} s (tap to seek)", it)
        }
        debug.canonicalFrameIndex?.let { debugRow("Frame index", "#$it") }
        debugRow("Active arm", debug.activeArm)
        debugRow("Analysis ID", debug.analysisId ?: "none")
        debugRow("Analysis state", debug.analysisState?.name ?: "unavailable")
        debug.reason?.let { debugRow("Analysis reason", it) }
        debug.evidenceReason?.let { debugRow("Evidence availability", it) }

        if (debug.measurements.isNotEmpty()) {
            addView(TextView(this@MovementDetailActivity).apply {
                text = "Measurements:"
                textSize = 12f
                typeface = Typeface.create("sans-serif", Typeface.BOLD)
                setTextColor(ContextCompat.getColor(this@MovementDetailActivity, R.color.app_text_primary))
                setPadding(0, 6.dp(), 0, 2.dp())
            })
            debug.measurements.forEach { m ->
                val v = m.numericValue?.let { "%.2f".format(it) } ?: m.categoricalValue ?: "null"
                val conf = m.confidence?.let { " (conf: ${"%.2f".format(it)})" } ?: ""
                debugRow("  • ${m.measurementKey}", "$v$conf", m.occurrenceUs)
            }
        }

        addView(TextView(this@MovementDetailActivity).apply {
            text = "Provenance:"
            textSize = 12f
            typeface = Typeface.create("sans-serif", Typeface.BOLD)
            setTextColor(ContextCompat.getColor(this@MovementDetailActivity, R.color.app_text_primary))
            setPadding(0, 6.dp(), 0, 2.dp())
        })
        debugRow("  Analyzer", "${debug.analyzerKey ?: "none"} v${debug.analyzerVersion ?: "none"}")
        debugRow("  Segmenter", debug.segmenterVersion ?: "none")
        debugRow("  Track ID", debug.landmarkTrackId ?: "none")
        debugRow("  Run ID", debug.runId ?: "none")

        debug.bodyHeightDebug?.let { bh ->
            addView(TextView(this@MovementDetailActivity).apply {
                text = "Body Height Model:"
                textSize = 12f
                typeface = Typeface.create("sans-serif", Typeface.BOLD)
                setTextColor(ContextCompat.getColor(this@MovementDetailActivity, R.color.app_text_primary))
                setPadding(0, 6.dp(), 0, 2.dp())
            })
            debugRow("  Config ID", bh.configId)
            debugRow("  Evaluation time", "${"%.3f".format(bh.selectedTimestampUs / 1_000_000.0)} s (radius: ${bh.requestedRadius})", bh.selectedTimestampUs)
            debugRow("  Torso evidence", "${bh.torsoUsableCount} contributing, state: ${bh.torsoWindowState}")
            if (bh.torsoContributingTimestampsUs.isNotEmpty()) {
                bh.torsoContributingTimestampsUs.forEachIndexed { idx, ts ->
                    debugRow("    Torso sample #${idx + 1}", "${"%.3f".format(ts / 1_000_000.0)}s (tap to seek)", ts)
                }
            }
            if (bh.torsoExclusionReasons.isNotEmpty()) {
                bh.torsoExclusionReasons.forEach { (ts, reason) ->
                    debugRow("    Torso excluded @ ${"%.3f".format(ts / 1_000_000.0)}s", reason, ts)
                }
            }
            bh.torsoDisagreementMetric?.let { debugRow("  Torso disagreement", "%.4f (aspect-corr)".format(it)) }

            debugRow("  Head evidence", "${bh.headUsableCount} contributing, state: ${bh.headWindowState}")
            if (bh.headContributingTimestampsUs.isNotEmpty()) {
                bh.headContributingTimestampsUs.forEachIndexed { idx, ts ->
                    debugRow("    Head sample #${idx + 1}", "${"%.3f".format(ts / 1_000_000.0)}s (tap to seek)", ts)
                }
            }
            if (bh.headExclusionReasons.isNotEmpty()) {
                bh.headExclusionReasons.forEach { (ts, reason) ->
                    debugRow("    Head excluded @ ${"%.3f".format(ts / 1_000_000.0)}s", reason, ts)
                }
            }
            bh.headDisagreementMetric?.let { debugRow("  Head disagreement", "%.4f (aspect-corr)".format(it)) }

            bh.shoulderCenter?.let { debugRow("  Shoulder center", "(${ "%.3f".format(it.x) }, ${ "%.3f".format(it.y) })") }
            bh.hipCenter?.let { debugRow("  Hip center", "(${ "%.3f".format(it.x) }, ${ "%.3f".format(it.y) })") }
            bh.torsoCenter?.let { debugRow("  Torso center", "(${ "%.3f".format(it.x) }, ${ "%.3f".format(it.y) })") }
            bh.headAnchor?.let { debugRow("  Head anchor", "(${ "%.3f".format(it.x) }, ${ "%.3f".format(it.y) })") }
            bh.currentTorsoLength?.let { debugRow("  Torso length", "%.3f".format(it)) }
            bh.currentBodyUp?.let { debugRow("  Body up (aspect-corr)", "(${ "%.3f".format(it.x) }, ${ "%.3f".format(it.y) })") }

            if (bh.perSampleObservations.isNotEmpty()) {
                addView(TextView(this@MovementDetailActivity).apply {
                    text = "  Per-sample anchors:"
                    textSize = 11f
                    typeface = Typeface.create("sans-serif", Typeface.BOLD)
                    setTextColor(ContextCompat.getColor(this@MovementDetailActivity, R.color.app_text_secondary))
                    setPadding(0, 4.dp(), 0, 1.dp())
                })
                bh.perSampleObservations.forEach { s ->
                    val sStr = s.shoulderCenter?.let { "S:(${ "%.2f".format(it.x) },${ "%.2f".format(it.y) })" } ?: "S:none"
                    val hStr = s.hipCenter?.let { "H:(${ "%.2f".format(it.x) },${ "%.2f".format(it.y) })" } ?: "H:none"
                    val headStr = s.headAnchor?.let { "Head:(${ "%.2f".format(it.x) },${ "%.2f".format(it.y) })" } ?: "Head:none"
                    val trackStr = s.trackId?.let { " [$it]" } ?: ""
                    debugRow("    @ ${"%.3f".format(s.timestampUs / 1_000_000.0)}s", "$sStr $hStr $headStr$trackStr", s.timestampUs)
                }
            }

            debugRow("  Chūdan target [${bh.chudanEstimatorId}]", "${bh.chudanStatus}: ${bh.chudanTarget?.let { "(${ "%.3f".format(it.x) }, ${ "%.3f".format(it.y) })" } ?: "none"}")
            debugRow("  Gedan target [${bh.gedanEstimatorId}]", "${bh.gedanStatus}: ${bh.gedanTarget?.let { "(${ "%.3f".format(it.x) }, ${ "%.3f".format(it.y) })" } ?: "none"}")
            bh.jodanStatus?.let { debugRow("  Jōdan target [${bh.jodanEstimatorId}]", "$it: ${bh.jodanTarget?.let { pt -> "(${ "%.3f".format(pt.x) }, ${ "%.3f".format(pt.y) })" } ?: "none"}") }
        }
    }

    private fun showExpandedInspection() {
        val state = timelineState ?: return
        val data = presentationData ?: return
        expandedDialog?.dismiss()
        expandedDialog = null
        playerView?.setPlaybackActive(false)
        isExpandedOpen = true
        val activeSide = when (data.debugData.activeArm) {
            "LEFT" -> BodySide.LEFT
            "RIGHT" -> BodySide.RIGHT
            else -> BodySide.UNKNOWN
        }
        expandedDialog = MovementExpandedInspectionDialog(this, state, videoFile, data).also { dialog ->
            dialog.verticalBounds = verticalBounds
            dialog.overlayView.frames = loadedFrames
            dialog.overlayView.activeSide = activeSide
            dialog.setOnDismissListener {
                if (expandedDialog === dialog) {
                    expandedDialog = null
                    isExpandedOpen = false
                    playerView?.setPlaybackActive(active)
                }
            }
            dialog.show()
        }
    }

    private fun card() = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        background = GradientDrawable().apply {
            setColor(ContextCompat.getColor(this@MovementDetailActivity, R.color.home_card_surface))
            cornerRadius = 14f * density
            setStroke(1.dp(), ContextCompat.getColor(this@MovementDetailActivity, R.color.app_border))
        }
        setPadding(16.dp(), 14.dp(), 16.dp(), 14.dp())
    }

    private val density get() = resources.displayMetrics.density
    private fun Int.dp() = (this * density).roundToInt()

    companion object {
        const val EXTRA_SESSION_ID = "movement_session_id"
        const val EXTRA_MOVEMENT_ID = "movement_id"
        const val EXTRA_DISPLAYED_NUMBER = "movement_displayed_number"
    }
}
