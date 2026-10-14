package dk.lasse.karatecliprecorder.training

import android.content.Context
import android.content.Intent
import android.os.Looper
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.ListView
import android.widget.TextView
import androidx.test.core.app.ApplicationProvider
import dk.lasse.karatecliprecorder.AppPreferences
import dk.lasse.karatecliprecorder.profile.ProfileRepository
import dk.lasse.karatecliprecorder.recordings.RecordingsActivity
import org.junit.After
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowDialog
import java.time.LocalDate
import java.time.YearMonth
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import kotlin.test.*

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28])
class RecordingsActivityTest {
    private val context = ApplicationProvider.getApplicationContext<Context>()
    @After fun close() { TrainingServices.closeForTests() }
    private fun drainUntil(done: () -> Boolean) {
        val deadline = System.nanoTime() + 5_000_000_000L
        while (!done() && System.nanoTime() < deadline) {
            shadowOf(Looper.getMainLooper()).idle(); Thread.sleep(10)
        }
        assertTrue(done(), "Timed out waiting for UI/storage callback")
    }
    private fun views(view: View): List<View> = listOf(view) + if (view is ViewGroup)
        (0 until view.childCount).flatMap { views(view.getChildAt(it)) } else emptyList()

    @Test fun browserFilterCalendarAndRowBackRestoreSelection() {
        ProcessingPreferences(context).background = false
        val training = TrainingServices.get(context)
        val profileRepository = ProfileRepository(context, AppPreferences(context))
        val user = profileRepository.activeProfile().id
        profileRepository.close()
        val zone = ZoneId.systemDefault()
        val month = YearMonth.now().minusMonths(1)
        val fixtures = listOf(
            month.atDay(9) to ("Punches" to "Alternating straight punches"),
            month.atDay(8) to ("Kicks" to "Front kicks"),
            month.atDay(7) to ("Punches" to "Alternating straight punches"),
        )
        var seeded = false
        training.submit({ repo ->
            repo.createUser(TrainingUser(user))
            fixtures.forEach { (date, context) ->
                val sessionId = trainingId()
                val recordingId = trainingId()
                repo.beginSession(
                    RecordingSession(
                        sessionId = sessionId,
                        userId = user,
                        startedAtMs = date.atTime(12, 0).atZone(zone).toInstant().toEpochMilli(),
                        activityKey = AssistedCaptureSetup.ACTIVITY_KEY,
                        state = SessionState.RECORDED,
                        expectedActivity = context.second,
                        expectedCategory = context.first,
                        expectedRepetitions = 10,
                    ),
                    MasterRecording(
                        recordingId = recordingId,
                        sessionId = sessionId,
                        filePath = training.storage.recording(recordingId),
                        createdAtMs = date.atTime(12, 0).atZone(zone).toInstant().toEpochMilli(),
                        durationUs = 1_000_000,
                        sourceState = SourceState.AVAILABLE,
                    ),
                )
            }
        }) { it.getOrThrow(); seeded = true }
        drainUntil { seeded }

        // Robolectric does not run the manifest's AndroidX Startup ContentProvider.
        runCatching { androidx.work.WorkManager.initialize(context, androidx.work.Configuration.Builder().build()) }
        val activity = Robolectric.buildActivity(RecordingsActivity::class.java).setup()
        try {
            val root = activity.get().findViewById<View>(android.R.id.content)
            fun textValues() = views(root).filterIsInstance<TextView>().map { it.text.toString() }
            fun semantic(description: String) = views(root).single { it.contentDescription == description }
            fun dateDescription(date: LocalDate, available: Boolean, selected: Boolean = false) =
                "${date.format(DateTimeFormatter.ofPattern("d MMMM yyyy"))}, " +
                    (if (available) "recordings available" else "no recordings") +
                    (if (selected) ", selected" else "")
            fun dayCell(date: LocalDate, available: Boolean, selected: Boolean = false) =
                semantic(dateDescription(date, available, selected))
            fun rowsFor(activityName: String) = views(root).filter {
                it.isClickable && it.contentDescription?.toString()?.contains(". $activityName.") == true
            }
            fun monthLabel() = month.format(DateTimeFormatter.ofPattern("MMMM yyyy"))

            drainUntil { "Recent recordings" in textValues() }
            val previous = views(root).single { it.contentDescription == "Previous month" }
            val next = views(root).single { it.contentDescription == "Next month" }
            assertTrue(previous.isClickable && previous.isFocusable)
            assertTrue(next.isClickable && next.isFocusable)
            assertTrue(semantic("Recent recordings, selected").isClickable)
            assertTrue(semantic("Today recordings").isClickable)

            previous.performClick()
            assertTrue(monthLabel() in textValues())
            assertNotNull(dayCell(month.atDay(9), available = true))
            assertNotNull(dayCell(month.atDay(8), available = true))
            assertEquals(2, rowsFor("Alternating straight punches").size)
            assertEquals(1, rowsFor("Front kicks").size)
            assertTrue(rowsFor("Alternating straight punches").all {
                it.contentDescription.toString().contains("Planned 10") &&
                    it.contentDescription.toString().contains("Saved")
            })

            semantic("Filter recordings. Selected activity: All activities").performClick()
            val filterDialog = ShadowDialog.getLatestDialog()
            val dialogRoot = requireNotNull(filterDialog.window?.decorView)
            val filterList = views(dialogRoot).filterIsInstance<ListView>().singleOrNull()
            if (filterList != null) {
                val punchesIndex = (0 until filterList.count).first {
                    filterList.adapter.getItem(it).toString() == "Punches"
                }
                filterList.performItemClick(null, punchesIndex, filterList.adapter.getItemId(punchesIndex))
            } else {
                views(dialogRoot).first { option ->
                    option.isClickable && views(option).filterIsInstance<TextView>().any { it.text == "Punches" }
                }.performClick()
            }
            assertTrue(semantic("Filter recordings. Selected activity: Punches").isClickable)
            assertEquals(2, rowsFor("Alternating straight punches").size)
            assertEquals(0, rowsFor("Front kicks").size)
            assertNotNull(dayCell(month.atDay(8), available = false))

            val selectedDay = dayCell(month.atDay(9), available = true)
            assertTrue(selectedDay.isClickable && selectedDay.isFocusable)
            selectedDay.performClick()
            assertTrue("9 ${month.format(DateTimeFormatter.ofPattern("MMMM yyyy"))}" in textValues())
            assertNotNull(dayCell(month.atDay(9), available = true, selected = true))
            assertEquals(1, rowsFor("Alternating straight punches").size)

            val row = rowsFor("Alternating straight punches").single()
            row.performClick()
            assertTrue("Recording" in textValues())
            assertTrue("Recording details" in textValues())

            activity.get().onBackPressedDispatcher.onBackPressed()
            assertTrue(semantic("Filter recordings. Selected activity: Punches").isClickable)
            assertTrue("9 ${month.format(DateTimeFormatter.ofPattern("MMMM yyyy"))}" in textValues())
            assertEquals(1, rowsFor("Alternating straight punches").size)
            assertNotNull(dayCell(month.atDay(9), available = true, selected = true))
            assertTrue(monthLabel() in textValues())
        } finally { activity.pause().stop().destroy() }
    }

    @Test fun exactFailedRecordingHasPlaybackRetryAndConfirmedDeletionWithoutPromotion() {
        // Robolectric does not run the manifest's AndroidX Startup ContentProvider.
        runCatching { androidx.work.WorkManager.initialize(context, androidx.work.Configuration.Builder().build()) }
        ProcessingPreferences(context).background = false
        val training = TrainingServices.get(context)
        val profileRepository = ProfileRepository(context, AppPreferences(context))
        val user = profileRepository.activeProfile().id
        profileRepository.close()
        val session = RecordingSession(userId = user, startedAtMs = 1000, activityKey = AssistedCaptureSetup.ACTIVITY_KEY,
            expectedActivity = "Front kicks", expectedCategory = "Kicks", expectedRepetitions = 12)
        var seeded = false
        training.submit({ repo ->
            repo.createUser(TrainingUser(user))
            val id = trainingId()
            val reference = training.storage.recording(id)
            training.storage.resolve(reference).apply { parentFile!!.mkdirs(); writeText("playback fixture") }
            repo.beginSession(session, MasterRecording(id, session.sessionId, reference, 1000))
            repo.finishRecording(session.sessionId, 2_000_000)
            repo.recoverJobs()
            repo.updateJob(repo.job(session.sessionId)!!.copy(state = QueueState.FAILED, phase = ProcessingPhase.FAILED, error = "fixture extraction failure"))
        }) { it.getOrThrow(); seeded = true }
        drainUntil { seeded }
        val intent = Intent(context, RecordingsActivity::class.java).putExtra(RecordingsActivity.EXTRA_SESSION_ID, session.sessionId)
        val activity = Robolectric.buildActivity(RecordingsActivity::class.java, intent).setup()
        try {
            val root = activity.get().findViewById<View>(android.R.id.content)
            fun texts() = views(root).filterIsInstance<TextView>().map { it.text.toString() }
            fun click(label: String) { views(root).filterIsInstance<Button>().single { it.text.toString() == label }.performClick() }
            drainUntil { "Front kicks" in texts() }
            assertTrue(texts().any { "Planned 12" in it } && texts().any { "Failed" in it || "failed" in it })
            assertTrue("Retry" in texts())
            click("Recording details")
            assertTrue("Watch full recording" in texts())
            click("Watch full recording")
            val watch = ShadowDialog.getLatestDialog()
            assertTrue(watch.isShowing)
            watch.dismiss()
            var inspected = false
            training.submit({ repo ->
                assertNull(repo.job(session.sessionId)!!.promotedAtMs)
                assertEquals(0, repo.movementCount(session.sessionId))
            }) { it.getOrThrow(); inspected = true }
            drainUntil { inspected }
            click("Delete recording")
            val dialog = ShadowDialog.getLatestDialog()
            assertTrue(dialog.isShowing)
            dialog.findViewById<Button>(android.R.id.button2).performClick()
            drainUntil { !dialog.isShowing }
            assertTrue("Watch full recording" in texts())
            click("Delete recording")
            ShadowDialog.getLatestDialog().findViewById<Button>(android.R.id.button1).performClick()
            drainUntil { "No recordings for this selection." in texts() }
        } finally { activity.pause().stop().destroy() }
    }

    @Test fun recordingWithMovementsRendersThreeCardHierarchyAndAllowsPlayback() {
        runCatching { androidx.work.WorkManager.initialize(context, androidx.work.Configuration.Builder().build()) }
        ProcessingPreferences(context).background = false
        val training = TrainingServices.get(context)
        val profileRepository = ProfileRepository(context, AppPreferences(context))
        val user = profileRepository.activeProfile().id
        profileRepository.close()
        val session = RecordingSession(userId = user, startedAtMs = 2000, activityKey = AssistedCaptureSetup.ACTIVITY_KEY,
            expectedActivity = "Alternating straight punches", expectedCategory = "Punches", expectedRepetitions = 2)
        val movements = listOf(
            SessionMovement(sessionId = session.sessionId, startUs = 500_000, endUs = 1_500_000,
                playbackStartUs = 200_000, playbackEndUs = 1_800_000,
                segmentationSource = "test_segmenter", segmentationVersion = "1"),
            SessionMovement(sessionId = session.sessionId, startUs = 2_000_000, endUs = 3_000_000,
                playbackStartUs = 1_700_000, playbackEndUs = 3_300_000,
                segmentationSource = "test_segmenter", segmentationVersion = "1")
        )
        var seeded = false
        training.submit({ repo ->
            repo.createUser(TrainingUser(user))
            val id = trainingId()
            val reference = training.storage.recording(id)
            training.storage.resolve(reference).apply { parentFile!!.mkdirs(); writeText("playback fixture") }
            repo.beginSession(session, MasterRecording(id, session.sessionId, reference, 2000))
            repo.finishRecording(session.sessionId, 4_000_000)
            repo.recoverJobs()
            val observations = movements.map { ObservationContext(it.movementId) }
            repo.saveSegmentation(session.sessionId, movements, observations, emptyList(), emptyList())
            repo.updateJob(repo.job(session.sessionId)!!.copy(state = QueueState.READY, phase = ProcessingPhase.READY))
        }) { it.getOrThrow(); seeded = true }
        drainUntil { seeded }

        val intent = Intent(context, RecordingsActivity::class.java).putExtra(RecordingsActivity.EXTRA_SESSION_ID, session.sessionId)
        val activity = Robolectric.buildActivity(RecordingsActivity::class.java, intent).setup()
        try {
            val root = activity.get().findViewById<View>(android.R.id.content)
            fun texts() = views(root).filterIsInstance<TextView>().map { it.text.toString() }
            drainUntil { "Alternating straight punches" in texts() }
            // Verify 3 cards
            assertTrue(texts().any { "Alternating straight punches" in it })
            assertTrue("Session analysis" in texts())
            assertTrue("Target analysis unavailable" in texts())
            assertTrue("Movements (2)" in texts())
            assertTrue("Movement 1" in texts())
            assertTrue("Movement 2" in texts())
            // Tapping a movement row opens MovementDetailActivity
            val row1 = views(root).first { it.isClickable && views(it).filterIsInstance<TextView>().any { tv -> tv.text == "Movement 1" } }
            val row1ImageViews = views(row1).filterIsInstance<android.widget.ImageView>()
            assertTrue(row1ImageViews.isNotEmpty(), "Movement row should render thumbnail/silhouette placeholder non-blockingly")
            row1.performClick()
            val nextIntent = shadowOf(activity.get()).nextStartedActivity
            assertNotNull(nextIntent)
            assertEquals(dk.lasse.karatecliprecorder.movement.MovementDetailActivity::class.java.name, nextIntent.component?.className)
            assertEquals(session.sessionId, nextIntent.getStringExtra(dk.lasse.karatecliprecorder.movement.MovementDetailActivity.EXTRA_SESSION_ID))
            assertEquals(movements[0].movementId, nextIntent.getStringExtra(dk.lasse.karatecliprecorder.movement.MovementDetailActivity.EXTRA_MOVEMENT_ID))
            assertEquals(1, nextIntent.getIntExtra(dk.lasse.karatecliprecorder.movement.MovementDetailActivity.EXTRA_DISPLAYED_NUMBER, 0))
        } finally { activity.pause().stop().destroy() }
    }
}
