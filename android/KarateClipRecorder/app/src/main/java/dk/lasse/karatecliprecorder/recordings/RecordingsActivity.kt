package dk.lasse.karatecliprecorder.recordings

import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.widget.*
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
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
        if (selected != null) { selected = null; render() }
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
                selected = row.session.sessionId; segmentData = null; render(); load()
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
        body.addView(label(row.context, 21f))
        body.addView(label("Expected activity · ${row.session.expectedCategory ?: "Unspecified"}"))
        body.addView(label("${date(row)}\n${row.countLabel} · ${row.status}"))
        row.recording.durationUs?.let { body.addView(label("Duration: ${"%.1f".format(it / 1_000_000.0)} s")) }
        row.session.interruptionReason?.let { body.addView(label("Capture interrupted: ${it.replace('_', ' ')}")) }
        row.processing?.error?.let { body.addView(label("Processing failed: $it")) }
        segments(row)
        if (row.recording.sourceState == SourceState.AVAILABLE && row.processing?.state in listOf(null, QueueState.QUEUED, QueueState.FAILED)) {
            body.addView(button(if (row.processing?.state == QueueState.FAILED) "Retry" else "Process now") {
                RecordingQueue.processNow(this, row.session.sessionId) { result ->
                    result.onFailure { toast(it.message ?: "Unable to process") }; load()
                }
            })
        }
        body.addView(label("Recording evidence", 18f))
        if (row.recording.sourceState == SourceState.AVAILABLE) {
            body.addView(button("Watch full recording") { play(row) })
            if (AppPreferences(this).developerMode && row.processing?.state == QueueState.READY)
                body.addView(button("View landmarks (debug)") { playLandmarks(row) })
        } else body.addView(label("The master video is unavailable."))
        body.addView(button("Delete recording") {
            AlertDialog.Builder(this).setTitle("Delete recording?")
                .setMessage("Remove this recording and its movement data? This cannot be undone.")
                .setNegativeButton("Cancel", null).setPositiveButton("Delete") { _, _ ->
                    RecordingQueue.delete(this, row.session.sessionId) { result ->
                        result.onSuccess { selected = null }.onFailure { toast("Deletion pending: ${it.message}") }
                        load()
                    }
                }.show()
        })
    }
    private fun segments(row: RecordingSummary) {
        body.addView(label("Segments", 18f))
        body.addView(label("Activity: ${row.context}"))
        body.addView(label("Planned repetitions: ${row.session.expectedRepetitions?.toString() ?: "Not specified"}"))
        body.addView(label("Detected movements: ${row.movementCount}"))
        body.addView(label("Status: ${row.status}"))
        val details = segmentData
        if (details == null) { body.addView(label("Loading segments…")); return }
        if (details.movements.isEmpty()) {
            body.addView(label(if (row.processing?.phase == ProcessingPhase.READY)
                "No movements were detected." else "Movement segmentation is not complete."))
            return
        }
        details.movements.forEachIndexed { index, movement ->
            body.addView(label("Movement ${index + 1}", 17f))
            body.addView(label("Logical: ${time(movement.startUs)} – ${time(movement.endUs)}\n" +
                "Duration: ${time(movement.endUs - movement.startUs)}\n" +
                "Playback: ${time(movement.playbackStartUs)} – ${time(movement.playbackEndUs)}\n" +
                "Segmenter: ${movement.segmentationSource} v${movement.segmentationVersion}\n" +
                "Landmark track: ${movement.segmentationTrackId ?: "Unavailable"}"))
            if (row.recording.sourceState == SourceState.AVAILABLE) body.addView(button("Play movement ${index + 1}") {
                play(row, movement.playbackStartUs / 1000, movement.playbackEndUs / 1000)
            })
        }
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
