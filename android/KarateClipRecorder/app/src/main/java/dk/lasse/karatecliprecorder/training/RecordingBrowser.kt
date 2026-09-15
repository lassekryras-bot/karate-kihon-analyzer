package dk.lasse.karatecliprecorder.training

import java.time.Instant
import java.time.LocalDate
import java.time.YearMonth
import java.time.ZoneId

data class RecordingSummary(val session: RecordingSession, val recording: MasterRecording,
    val processing: RecordingProcessing?, val movementCount: Int) {
    val countLabel: String get() = if (processing?.phase == ProcessingPhase.READY || movementCount > 0)
        "Detected $movementCount" + (session.expectedRepetitions?.let { " · Planned $it" } ?: "")
        else session.expectedRepetitions?.let { "Planned $it" } ?: "No planned count"
    val status: String get() = processing?.let { job -> when (job.phase) {
        ProcessingPhase.QUEUED -> "Queued"
        ProcessingPhase.LANDMARKS -> "Processing landmarks"
        ProcessingPhase.SEGMENTATION -> "Finding movements"
        ProcessingPhase.ANALYSIS -> "Analyzing movements"
        ProcessingPhase.READY -> "Segments ready"
        ProcessingPhase.FAILED -> "Processing failed"
    } }
        ?: if (recording.sourceState == SourceState.AVAILABLE) "Saved" else "Video unavailable"
    val context: String get() = session.expectedActivity ?: "Unspecified activity"
    fun day(zone: ZoneId): LocalDate = Instant.ofEpochMilli(session.startedAtMs).atZone(zone).toLocalDate()
}

/** One context-filtered source supplies both list rows and calendar markers. Never schedules work. */
class RecordingBrowser(val recordings: List<RecordingSummary>, private val zone: ZoneId = ZoneId.systemDefault()) {
    val categories get() = recordings.mapNotNull { it.session.expectedCategory }.distinct().sorted()
    private fun filtered(category: String?) = recordings.filter { category == null || it.session.expectedCategory == category }
        .sortedWith(compareByDescending<RecordingSummary> { it.session.startedAtMs }.thenBy { it.session.sessionId })
    fun recent(category: String? = null) = filtered(category).take(5)
    fun day(day: LocalDate, category: String? = null) = filtered(category).filter { it.day(zone) == day }
    fun markedDays(month: YearMonth, category: String? = null) = filtered(category).map { it.day(zone) }
        .filter { YearMonth.from(it) == month }.toSet()
    fun exact(id: String) = recordings.firstOrNull { it.session.sessionId == id }
}
