package dk.lasse.karatecliprecorder.recordings

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
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import dk.lasse.karatecliprecorder.AppIcon
import dk.lasse.karatecliprecorder.AppIconView
import dk.lasse.karatecliprecorder.AppPreferences
import dk.lasse.karatecliprecorder.R
import dk.lasse.karatecliprecorder.SubPageHeader
import dk.lasse.karatecliprecorder.assisted.LandmarkPlaybackDialog
import dk.lasse.karatecliprecorder.profile.ProfileRepository
import dk.lasse.karatecliprecorder.training.*
import java.time.LocalDate
import java.time.YearMonth
import java.time.ZoneId
import java.time.Instant
import java.time.format.DateTimeFormatter

/** Passive evidence browser. Opening a row never promotes or waits for landmark work. */
class RecordingsActivity : AppCompatActivity() {
    private val training by lazy { TrainingServices.get(this) }
    private val profileStore = lazy { ProfileRepository(this, AppPreferences(this)) }
    private val profiles by profileStore
    private lateinit var body: LinearLayout
    private lateinit var header: SubPageHeader
    private var browser = RecordingBrowser(emptyList())
    private var month = YearMonth.now()
    private var day: LocalDate? = null
    private var category: String? = null
    private var selected: String? = null
    private var detailsExpanded = false
    private var segmentData: SegmentData? = null
    private var loaded = false
    private var active = false
    private var loading = false
    private var playback: LandmarkPlaybackDialog? = null
    private val handler = Handler(Looper.getMainLooper())
    private val refresh = object : Runnable {
        override fun run() { load(); if (active) handler.postDelayed(this, 1500) }
    }
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        selected = savedInstanceState?.getString("selected") ?: intent.getStringExtra(EXTRA_SESSION_ID)
        category = savedInstanceState?.getString("category")
        day = savedInstanceState?.getString("day")?.let(LocalDate::parse)
        month = savedInstanceState?.getString("month")?.let(YearMonth::parse) ?: YearMonth.now()
        detailsExpanded = savedInstanceState?.getBoolean("details_expanded") ?: false
        body = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16), dp(8), dp(16), dp(24))
        }
        val scroll = ScrollView(this).apply {
            setBackgroundColor(ContextCompat.getColor(this@RecordingsActivity, R.color.app_background))
            addView(body)
        }
        header = SubPageHeader(this, "Recordings", onBack = ::back)
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(ContextCompat.getColor(this@RecordingsActivity, R.color.app_background))
            addView(header, LinearLayout.LayoutParams(-1, -2))
            addView(QueueManagerTrayView(this@RecordingsActivity), LinearLayout.LayoutParams(-1, -2))
            addView(scroll, LinearLayout.LayoutParams(-1, 0, 1f))
        }
        androidx.core.view.ViewCompat.setOnApplyWindowInsetsListener(scroll) { view, insets ->
            val bars = insets.getInsets(androidx.core.view.WindowInsetsCompat.Type.navigationBars())
            view.setPadding(0, 0, 0, bars.bottom); insets
        }
        setContentView(root)
        onBackPressedDispatcher.addCallback(this, object : androidx.activity.OnBackPressedCallback(true) {
            override fun handleOnBackPressed() { back() }
        })
        render()
    }
    override fun onSaveInstanceState(outState: Bundle) {
        outState.putString("selected", selected); outState.putString("category", category)
        outState.putString("day", day?.toString()); outState.putString("month", month.toString())
        outState.putBoolean("details_expanded", detailsExpanded)
        super.onSaveInstanceState(outState)
    }
    override fun onStart() { super.onStart(); active = true; handler.post(refresh) }
    override fun onStop() {
        active = false; handler.removeCallbacksAndMessages(null); playback?.dismiss(); playback = null
        super.onStop()
    }
    override fun onDestroy() {
        if (profileStore.isInitialized()) profiles.close()
        super.onDestroy()
    }
    private fun back() {
        if (selected != null) { selected = null; detailsExpanded = false; render() }
        else {
            startActivity(android.content.Intent(this, dk.lasse.karatecliprecorder.MainActivity::class.java)
                .addFlags(android.content.Intent.FLAG_ACTIVITY_CLEAR_TOP or android.content.Intent.FLAG_ACTIVITY_SINGLE_TOP)
                .putExtra("open_performance", true))
            finish()
        }
    }
    private fun load() {
        if (loading) return
        loading = true
        val user = profiles.activeProfile().id
        training.submit({ repo ->
            // A post-capture deep link retains the recording's owning profile.
            val owner = selected?.let { repo.session(it)?.userId } ?: user
            BrowserLoad(repo.recordingSummaries(owner), selected?.let { id ->
                SegmentData(repo.movements(id))
            })
        }) { result ->
            loading = false
            if (!active) return@submit
            result.onSuccess {
                val changed = !loaded || it.summaries != browser.recordings || it.segments != segmentData
                loaded = true; browser = RecordingBrowser(it.summaries); segmentData = it.segments
                if (changed) render()
            }.onFailure { toast("Could not load recordings: ${it.message}") }
        }
    }
    private fun render() {
        body.removeAllViews()
        header.setTitle(if (selected == null) "Recordings" else "Recording")
        if (!loaded) { body.addView(label("Loading recordings…")); return }
        selected?.let { id -> detail(browser.exact(id)); return }
        body.addView(button("Filter: ${category ?: "All activities"}") {
            val categories = browser.categories
            AlertDialog.Builder(this).setTitle("Stored activity context")
                .setItems((listOf("All activities") + categories).toTypedArray()) { _, index ->
                    category = if (index == 0) null else categories[index - 1]; render()
                }.show()
        })
        val navigation = LinearLayout(this)
        navigation.addView(button("Recent") { day = null; render() }, LinearLayout.LayoutParams(0, -2, 1f))
        navigation.addView(button("Today") { day = LocalDate.now(); month = YearMonth.now(); render() }, LinearLayout.LayoutParams(0, -2, 1f))
        body.addView(navigation)
        calendar()
        body.addView(label(day?.format(DateTimeFormatter.ofPattern("d MMMM yyyy")) ?: "Recent recordings", 20f))
        val rows = day?.let { browser.day(it, category) } ?: browser.recent(category)
        if (rows.isEmpty()) body.addView(label("No recordings for this selection."))
        rows.forEach { row ->
            body.addView(button("${date(row)}\n${row.context} · ${row.countLabel} · ${row.status}") {
                selected = row.session.sessionId; segmentData = null; detailsExpanded = false; render(); load()
            }.apply { gravity = Gravity.START or Gravity.CENTER_VERTICAL; textSize = 14f })
        }
    }
    private fun calendar() {
        val header = LinearLayout(this).apply { gravity = Gravity.CENTER_VERTICAL }
        header.addView(button("‹") { month = month.minusMonths(1); render() }.apply { contentDescription = "Previous month" }, LinearLayout.LayoutParams(dp(48), dp(48)))
        header.addView(label(month.format(DateTimeFormatter.ofPattern("MMMM yyyy")), 18f).apply { gravity = Gravity.CENTER }, LinearLayout.LayoutParams(0, -2, 1f))
        header.addView(button("›") { month = month.plusMonths(1); render() }.apply { contentDescription = "Next month" }, LinearLayout.LayoutParams(dp(48), dp(48)))
        body.addView(header)
        val weekdays = LinearLayout(this)
        listOf("M", "T", "W", "T", "F", "S", "S").forEach { weekdays.addView(label(it, 13f).apply { gravity = Gravity.CENTER }, LinearLayout.LayoutParams(0, -2, 1f)) }
        body.addView(weekdays)
        val marked = browser.markedDays(month, category)
        val offset = month.atDay(1).dayOfWeek.value - 1
        val cells = ((offset + month.lengthOfMonth() + 6) / 7) * 7
        for (week in 0 until cells / 7) {
            val line = LinearLayout(this)
            for (column in 0..6) {
                val number = week * 7 + column - offset + 1
                val date = if (number in 1..month.lengthOfMonth()) month.atDay(number) else null
                val cell = label(date?.let { "$number${if (it in marked) " •" else ""}" } ?: "", 14f).apply {
                    gravity = Gravity.CENTER; minHeight = dp(48)
                    if (date != null) {
                        contentDescription = "$date${if (date in marked) ", recordings available" else ", no recordings"}"
                        isClickable = true; isFocusable = true; isSelected = date == day
                        if (date == day) setBackgroundColor(0x335A83AB)
                        if (date in marked) setTypeface(typeface, android.graphics.Typeface.BOLD)
                        setOnClickListener { day = date; render() }
                    }
                }
                line.addView(cell, LinearLayout.LayoutParams(0, dp(48), 1f))
            }
            body.addView(line)
        }
    }
    private fun detail(row: RecordingSummary?) {
        if (row == null) { body.addView(label("This recording is no longer available.")); return }
        QueueManager.acknowledge(row.session.sessionId)
        body.addView(summaryCard(row))
        body.addView(sessionAnalysisCard(row), LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(12) })
        body.addView(movementsCard(row), LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(12) })
    }
    private fun summaryCard(row: RecordingSummary): View = card().apply {
        addView(TextView(this@RecordingsActivity).apply {
            text = row.context
            textSize = 18f
            typeface = Typeface.create("sans-serif", Typeface.BOLD)
            setTextColor(ContextCompat.getColor(this@RecordingsActivity, R.color.app_text_primary))
        })
        addView(TextView(this@RecordingsActivity).apply {
            text = date(row)
            textSize = 13f
            setTextColor(ContextCompat.getColor(this@RecordingsActivity, R.color.app_text_secondary))
            setPadding(0, dp(2), 0, dp(6))
        })

        val tags = LinearLayout(this@RecordingsActivity).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(0, 0, 0, dp(4))
        }
        fun addTag(text: String) {
            val c = chip(text)
            val lp = LinearLayout.LayoutParams(-2, -2).apply {
                if (tags.childCount > 0) marginStart = dp(6)
            }
            tags.addView(c, lp)
        }
        row.session.expectedCategory?.let { addTag(it) }
        row.session.expectedRepetitions?.let { addTag("Planned $it") }
        row.session.requestedView?.let { addTag(it) }
        if (row.session.spokenCounting == true) addTag("Hands-free")
        if (tags.childCount > 0) addView(tags)

        if (row.processing?.state == QueueState.FAILED) {
            val failureBox = LinearLayout(this@RecordingsActivity).apply {
                orientation = LinearLayout.VERTICAL
                background = GradientDrawable().apply {
                    setColor(ContextCompat.getColor(this@RecordingsActivity, R.color.skill_coach_guidance_surface))
                    cornerRadius = dp(8).toFloat()
                }
                setPadding(dp(12), dp(8), dp(12), dp(8))
                val err = row.processing.error ?: "Processing failed"
                addView(TextView(this@RecordingsActivity).apply {
                    text = "Processing failed: $err"
                    textSize = 13f
                    setTextColor(ContextCompat.getColor(this@RecordingsActivity, R.color.app_error))
                })
                addView(button("Retry") {
                    RecordingQueue.processNow(this@RecordingsActivity, row.session.sessionId) { result ->
                        result.onFailure { toast(it.message ?: "Unable to process") }; load()
                    }
                }.apply {
                    layoutParams = LinearLayout.LayoutParams(-2, -2).apply { topMargin = dp(6) }
                })
            }
            addView(failureBox, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(8) })
        }

        addView(View(this@RecordingsActivity).apply {
            setBackgroundColor(ContextCompat.getColor(this@RecordingsActivity, R.color.app_divider))
        }, LinearLayout.LayoutParams(-1, dp(1)).apply { topMargin = dp(10); bottomMargin = dp(4) })

        addView(Button(this@RecordingsActivity).apply {
            text = "Recording details"
            isAllCaps = false
            textSize = 14f
            setTextColor(ContextCompat.getColor(this@RecordingsActivity, R.color.app_accent))
            setBackgroundColor(android.graphics.Color.TRANSPARENT)
            gravity = Gravity.START or Gravity.CENTER_VERTICAL
            minHeight = dp(36)
            setPadding(0, dp(4), 0, 0)
            setOnClickListener {
                detailsExpanded = !detailsExpanded
                render()
            }
        })

        if (detailsExpanded) {
            val detailsBox = LinearLayout(this@RecordingsActivity).apply {
                orientation = LinearLayout.VERTICAL
                background = GradientDrawable().apply {
                    setColor(ContextCompat.getColor(this@RecordingsActivity, R.color.app_background))
                    cornerRadius = dp(8).toFloat()
                }
                setPadding(dp(12), dp(10), dp(12), dp(10))
            }
            row.recording.durationUs?.let {
                detailsBox.addView(label("Duration: ${"%.1f".format(it / 1_000_000.0)} s", 13f))
            }
            val res = if (row.recording.width != null && row.recording.height != null)
                "${row.recording.width}x${row.recording.height}" else null
            val fps = row.recording.frameRate?.let { "${it.toInt()} fps" }
            if (res != null || fps != null) {
                detailsBox.addView(label("Video: ${listOfNotNull(res, fps).joinToString(" @ ")}", 13f))
            }
            row.recording.camera?.let { detailsBox.addView(label("Camera: $it", 13f)) }
            row.recording.device?.let { detailsBox.addView(label("Device: $it", 13f)) }
            detailsBox.addView(label("Status: ${row.status}", 13f))
            row.processing?.let { p ->
                p.landmarkDurationMs?.let { detailsBox.addView(label("Landmarks: $it ms", 13f)) }
                p.segmentationDurationMs?.let { detailsBox.addView(label("Segmentation: $it ms", 13f)) }
            }
            row.session.interruptionReason?.let { detailsBox.addView(label("Interrupted: ${it.replace('_', ' ')}", 13f)) }

            val utils = LinearLayout(this@RecordingsActivity).apply {
                orientation = LinearLayout.VERTICAL
            }
            if (row.recording.sourceState == SourceState.AVAILABLE) {
                utils.addView(button("Watch full recording") { play(row) })
                if (AppPreferences(this@RecordingsActivity).developerMode && row.processing?.state == QueueState.READY) {
                    utils.addView(button("View landmarks (debug)") { playLandmarks(row) }.apply {
                        (layoutParams as? ViewGroup.MarginLayoutParams)?.topMargin = dp(6)
                    })
                }
            } else {
                utils.addView(label("The master video is unavailable.", 13f))
            }
            if (row.recording.sourceState == SourceState.AVAILABLE &&
                row.processing?.state in listOf(null, QueueState.QUEUED) &&
                row.processing?.state != QueueState.FAILED) {
                utils.addView(button("Process now") {
                    RecordingQueue.processNow(this@RecordingsActivity, row.session.sessionId) { result ->
                        result.onFailure { toast(it.message ?: "Unable to process") }; load()
                    }
                }.apply { (layoutParams as? ViewGroup.MarginLayoutParams)?.topMargin = dp(6) })
            }
            utils.addView(button("Delete recording") {
                AlertDialog.Builder(this@RecordingsActivity).setTitle("Delete recording?")
                    .setMessage("Remove this recording and its movement data? This cannot be undone.")
                    .setNegativeButton("Cancel", null).setPositiveButton("Delete") { _, _ ->
                        RecordingQueue.delete(this@RecordingsActivity, row.session.sessionId) { result ->
                            result.onSuccess { selected = null }.onFailure { toast("Deletion pending: ${it.message}") }
                            load()
                        }
                    }.show()
            }.apply {
                (layoutParams as? ViewGroup.MarginLayoutParams)?.topMargin = dp(6)
                setTextColor(ContextCompat.getColor(this@RecordingsActivity, R.color.app_error))
            })
            detailsBox.addView(utils, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(8) })
            addView(detailsBox, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(8) })
        }
    }
    private fun sessionAnalysisCard(row: RecordingSummary): View = card().apply {
        addView(TextView(this@RecordingsActivity).apply {
            text = "Session analysis"
            textSize = 18f
            typeface = Typeface.create("sans-serif", Typeface.BOLD)
            setTextColor(ContextCompat.getColor(this@RecordingsActivity, R.color.app_text_primary))
        })
        addView(TextView(this@RecordingsActivity).apply {
            text = "Analysis not available yet"
            textSize = 14f
            typeface = Typeface.create("sans-serif", Typeface.BOLD)
            setTextColor(ContextCompat.getColor(this@RecordingsActivity, R.color.app_text_secondary))
            setPadding(0, dp(4), 0, dp(2))
        })
        addView(TextView(this@RecordingsActivity).apply {
            text = "Movement segmentation is complete. Kinematic measurements and comparison against your baseline will appear here once an analyzer is configured for this activity."
            textSize = 13f
            setTextColor(ContextCompat.getColor(this@RecordingsActivity, R.color.app_text_secondary))
            setLineSpacing(0f, 1.2f)
        })
    }
    private fun movementsCard(row: RecordingSummary): View = card().apply {
        val details = segmentData
        val count = details?.movements?.size ?: row.movementCount
        val titleText = if (count > 0) "Movements ($count)" else "Movements"
        addView(TextView(this@RecordingsActivity).apply {
            text = titleText
            textSize = 18f
            typeface = Typeface.create("sans-serif", Typeface.BOLD)
            setTextColor(ContextCompat.getColor(this@RecordingsActivity, R.color.app_text_primary))
            setPadding(0, 0, 0, dp(8))
        })

        if (details == null) {
            addView(label("Loading movements…", 14f))
            return@apply
        }
        if (details.movements.isEmpty()) {
            val emptyMessage = if (row.processing?.phase == ProcessingPhase.READY) {
                "No movements were detected."
            } else {
                "Movement segmentation is in progress…"
            }
            addView(label(emptyMessage, 14f))
            return@apply
        }

        details.movements.forEachIndexed { index, movement ->
            if (index > 0) {
                addView(View(this@RecordingsActivity).apply {
                    setBackgroundColor(ContextCompat.getColor(this@RecordingsActivity, R.color.app_divider))
                }, LinearLayout.LayoutParams(-1, dp(1)).apply { topMargin = dp(8); bottomMargin = dp(8) })
            }
            addView(buildMovementRow(row, movement, index + 1))
        }
    }
    private fun buildMovementRow(row: RecordingSummary, movement: SessionMovement, number: Int): View {
        val rowLayout = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(0, dp(4), 0, dp(4))
            isClickable = true
            isFocusable = true
            setOnClickListener {
                play(row, movement.playbackStartUs / 1000, movement.playbackEndUs / 1000)
            }
        }

        val thumbContainer = FrameLayout(this).apply {
            background = GradientDrawable().apply {
                setColor(ContextCompat.getColor(this@RecordingsActivity, R.color.profile_avatar_background))
                cornerRadius = dp(8).toFloat()
                setStroke(dp(1), ContextCompat.getColor(this@RecordingsActivity, R.color.app_border))
            }
            clipToOutline = true

            val silhouette = ImageView(this@RecordingsActivity).apply {
                setImageResource(R.drawable.ic_tabler_karate)
                imageTintList = ColorStateList.valueOf(ContextCompat.getColor(this@RecordingsActivity, R.color.app_text_secondary))
                alpha = 0.45f
                scaleType = ImageView.ScaleType.FIT_CENTER
            }
            addView(silhouette, FrameLayout.LayoutParams(dp(44), dp(44), Gravity.CENTER))

            val badge = TextView(this@RecordingsActivity).apply {
                text = number.toString()
                textSize = 10f
                typeface = Typeface.create("sans-serif", Typeface.BOLD)
                setTextColor(android.graphics.Color.WHITE)
                background = GradientDrawable().apply {
                    setColor(0x99000000.toInt())
                    cornerRadius = dp(4).toFloat()
                }
                setPadding(dp(5), dp(1), dp(5), dp(1))
            }
            addView(badge, FrameLayout.LayoutParams(-2, -2).apply {
                topMargin = dp(4)
                marginStart = dp(4)
            })

            val playCircle = FrameLayout(this@RecordingsActivity).apply {
                background = GradientDrawable().apply {
                    setColor(0xCCFFFFFF.toInt())
                    shape = GradientDrawable.OVAL
                }
                val playIcon = ImageView(this@RecordingsActivity).apply {
                    setImageResource(R.drawable.ic_player_play)
                    imageTintList = ColorStateList.valueOf(ContextCompat.getColor(this@RecordingsActivity, R.color.app_text_primary))
                    scaleType = ImageView.ScaleType.FIT_CENTER
                }
                addView(playIcon, FrameLayout.LayoutParams(dp(14), dp(14), Gravity.CENTER))
            }
            addView(playCircle, FrameLayout.LayoutParams(dp(30), dp(30), Gravity.CENTER))
        }
        rowLayout.addView(thumbContainer, LinearLayout.LayoutParams(dp(72), dp(96)))

        val detailsCol = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(12), 0, dp(8), 0)
            addView(TextView(this@RecordingsActivity).apply {
                text = "Movement $number"
                textSize = 15f
                typeface = Typeface.create("sans-serif", Typeface.BOLD)
                setTextColor(ContextCompat.getColor(this@RecordingsActivity, R.color.app_text_primary))
            })
            val startSec = movement.playbackStartUs / 1_000_000.0
            val endSec = movement.playbackEndUs / 1_000_000.0
            val durationSec = (movement.endUs - movement.startUs) / 1_000_000.0
            addView(TextView(this@RecordingsActivity).apply {
                text = "Time: ${"%.1f".format(startSec)} – ${"%.1f".format(endSec)} s (duration ${"%.2f".format(durationSec)} s)"
                textSize = 12f
                setTextColor(ContextCompat.getColor(this@RecordingsActivity, R.color.app_text_secondary))
                setPadding(0, dp(2), 0, dp(4))
            })
            val chipContainer = LinearLayout(this@RecordingsActivity).apply {
                orientation = LinearLayout.HORIZONTAL
                addView(chip("Segment ready"))
            }
            addView(chipContainer)
        }
        rowLayout.addView(detailsCol, LinearLayout.LayoutParams(0, -2, 1f))

        val chevron = AppIconView(this, AppIcon.CHEVRON_RIGHT, sizeDp = 20).apply {
            setIconColor(ContextCompat.getColor(this@RecordingsActivity, R.color.app_text_secondary))
        }
        rowLayout.addView(chevron, LinearLayout.LayoutParams(dp(20), dp(20)))

        return rowLayout
    }
    private fun card() = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        background = GradientDrawable().apply {
            setColor(ContextCompat.getColor(this@RecordingsActivity, R.color.home_card_surface))
            cornerRadius = dp(16).toFloat()
            setStroke(dp(1), ContextCompat.getColor(this@RecordingsActivity, R.color.app_border))
        }
        setPadding(dp(16), dp(16), dp(16), dp(16))
    }
    private fun chip(text: String) = TextView(this).apply {
        this.text = text
        textSize = 12f
        typeface = Typeface.create("sans-serif", Typeface.BOLD)
        setTextColor(ContextCompat.getColor(this@RecordingsActivity, R.color.app_text_secondary))
        background = GradientDrawable().apply {
            setColor(ContextCompat.getColor(this@RecordingsActivity, R.color.app_background))
            cornerRadius = dp(6).toFloat()
        }
        setPadding(dp(8), dp(4), dp(8), dp(4))
    }
    private fun play(row: RecordingSummary, startMs: Long = 0, endMs: Long? = null) {
        val file = training.repository.file(row.recording.filePath)
        if (!file.isFile) { toast("The video file is unavailable."); return }
        playback = LandmarkPlaybackDialog(this, file, emptyList(), startMs, endMs).also { it.show() }
    }
    private fun playLandmarks(row: RecordingSummary) {
        training.submit({ repo -> repo.tracks(row.recording.recordingId).lastOrNull {
            it.state == ProcessingState.COMPLETED && it.sourceState == SourceState.AVAILABLE
        } }) { result ->
            val track = result.getOrNull() ?: run { toast("No completed landmark track"); return@submit }
            training.processingExecutor.execute {
                val frames = runCatching { LandmarkFiles.read(training.repository.file(track.filePath), track.sha256, track.formatId) }
                runOnUiThread {
                    if (active && selected == row.session.sessionId) frames.onSuccess {
                        playback = LandmarkPlaybackDialog(this, training.repository.file(row.recording.filePath), it).also { dialog -> dialog.show() }
                    }.onFailure { toast("Landmark playback unavailable: ${it.message}") }
                }
            }
        }
    }
    private fun date(row: RecordingSummary) = Instant.ofEpochMilli(row.session.startedAtMs).atZone(ZoneId.systemDefault())
        .format(DateTimeFormatter.ofPattern("d MMM yyyy · HH:mm"))
    private fun time(valueUs: Long) = "%.3f s".format(valueUs / 1_000_000.0)
    private fun label(value: String, size: Float = 16f) = TextView(this).apply {
        text = value; textSize = size; setPadding(0, dp(8), 0, dp(8))
        setTextColor(ContextCompat.getColor(this@RecordingsActivity, R.color.app_text_primary))
    }
    private fun button(value: String, action: () -> Unit) = Button(this).apply {
        text = value; isAllCaps = false; minHeight = dp(48); setOnClickListener { action() }
    }
    private fun toast(value: String) { Toast.makeText(this, value, Toast.LENGTH_LONG).show() }
    private fun dp(value: Int) = (value * resources.displayMetrics.density).toInt()
    companion object { const val EXTRA_SESSION_ID = "recording_session_id" }
}

private data class SegmentData(val movements: List<SessionMovement>)
private data class BrowserLoad(val summaries: List<RecordingSummary>, val segments: SegmentData?)
