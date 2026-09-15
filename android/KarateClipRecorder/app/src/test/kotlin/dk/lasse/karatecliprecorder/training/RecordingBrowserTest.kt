package dk.lasse.karatecliprecorder.training

import dk.lasse.karatecliprecorder.recordings.QueueStatusView
import org.junit.Test
import java.time.*
import kotlin.test.*

class RecordingBrowserTest {
    private val zone = ZoneId.of("Europe/Copenhagen")
    private fun row(day: Int, category: String = "Punches", count: Int = 0, state: QueueState = QueueState.QUEUED): RecordingSummary {
        val time = LocalDate.of(2026, 9, day).atTime(17, 42).atZone(zone).toInstant().toEpochMilli()
        val session = RecordingSession(sessionId = "recording-$day", userId = "user", startedAtMs = time,
            expectedActivity = if (category == "Punches") "Alternating straight punches" else "Front kicks",
            expectedCategory = category, expectedRepetitions = 10)
        return RecordingSummary(session, MasterRecording(sessionId = session.sessionId, filePath = "$day.mp4",
            createdAtMs = time, sourceState = SourceState.AVAILABLE), RecordingProcessing(session.sessionId, time, state), count)
    }
    @Test fun recentIsLatestFiveAndDayUsesLocalTime() {
        val browser = RecordingBrowser((1..9).map { row(it) }, zone)
        assertEquals(listOf(9, 8, 7, 6, 5), browser.recent().map { it.day(zone).dayOfMonth })
        assertEquals("recording-3", browser.day(LocalDate.of(2026, 9, 3)).single().session.sessionId)
        val midnight = row(1).let { it.copy(session = it.session.copy(startedAtMs = Instant.parse("2026-09-01T22:30:00Z").toEpochMilli())) }
        assertEquals(LocalDate.of(2026, 9, 2), midnight.day(zone))
    }
    @Test fun storedFilterChangesBothCalendarAndList() {
        val browser = RecordingBrowser(listOf(row(1), row(2, "Kicks"), row(3)), zone)
        assertEquals(listOf("recording-3", "recording-1"), browser.recent("Punches").map { it.session.sessionId })
        assertEquals(setOf(LocalDate.of(2026, 9, 1), LocalDate.of(2026, 9, 3)), browser.markedDays(YearMonth.of(2026, 9), "Punches"))
        assertTrue(browser.day(LocalDate.of(2026, 9, 2), "Punches").isEmpty())
        assertTrue(browser.markedDays(YearMonth.of(2026, 10), "Punches").isEmpty())
    }
    @Test fun deepLinkIsExactEvenOutsideRecentAndVideoIndependentOfQueue() {
        val rows = (1..8).map { row(it, state = listOf(QueueState.QUEUED, QueueState.PROCESSING, QueueState.FAILED)[it % 3]) }
        val browser = RecordingBrowser(rows, zone)
        rows.forEach { row ->
            assertSame(row, browser.exact(row.session.sessionId))
            assertEquals(SourceState.AVAILABLE, browser.exact(row.session.sessionId)!!.recording.sourceState)
            assertNull(row.processing!!.promotedAtMs)
        }
        assertNull(browser.exact("deleted"))
    }
    @Test fun plannedCountIsNeverCalledDetectedAndQueueStatusIsAccurate() {
        assertEquals("Planned 10", row(1).countLabel)
        assertEquals("9 movements", row(2, count = 9).countLabel)
        val jobs = listOf(row(1), row(2), row(3, state = QueueState.PROCESSING), row(4, state = QueueState.FAILED)).map { it.processing!! }
        assertEquals("Recordings: 1 processing · 2 queued", QueueStatusView.queueStatus(jobs))
        assertEquals("", QueueStatusView.queueStatus(jobs.filter { it.state == QueueState.FAILED }))
    }
}
