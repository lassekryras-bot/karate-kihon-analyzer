package dk.lasse.karatecliprecorder.recordings

import android.content.Context
import android.os.Handler
import android.os.Looper
import dk.lasse.karatecliprecorder.training.*

/** Read-only presentation contract over the durable recording queue. It never schedules work. */
data class QueueManagerJob(
    val jobId: String,
    val recordingId: String,
    val sessionId: String,
    val activityKey: String?,
    val activityName: String,
    val plannedRepetitions: Int?,
    val state: QueueState,
    val phase: ProcessingPhase,
    val planKey: String,
    val movementCount: Int,
    val error: String?,
    val changedAtMs: Long,
    val audience: QueueJobAudience = QueueJobAudience.RECENT_USER,
) {
    val ready: Boolean get() = state == QueueState.READY && phase == ProcessingPhase.READY
    val active: Boolean get() = state == QueueState.PROCESSING
    val dismissible: Boolean get() = ready
    val statusLabel: String get() = when {
        ready -> if (planKey == RecordingProcessingPlans.STRAIGHT_PUNCH_SEGMENTS.key) "Segments ready" else "Result ready"
        state == QueueState.QUEUED -> "Waiting"
        state == QueueState.FAILED && movementCount > 0 -> "Processing incomplete"
        state == QueueState.FAILED -> "Processing failed"
        phase == ProcessingPhase.LANDMARKS -> "Processing landmarks"
        phase == ProcessingPhase.SEGMENTATION -> "Finding movements"
        phase == ProcessingPhase.ANALYSIS -> "Analyzing movements"
        else -> "Processing"
    }

}

enum class QueueJobAudience { RECENT_USER, MAINTENANCE }

data class QueueManagerTraySnapshot(
    val visible: List<QueueManagerJob>,
    val hiddenCount: Int,
    val hiddenReady: Int,
    val hiddenProcessing: Int,
    val hiddenWaiting: Int,
    val hiddenFailed: Int = 0,
) {
    val overflowLabel: String? get() = hiddenCount.takeIf { it > 0 }?.let {
        "+$it more · $hiddenReady ready · $hiddenProcessing processing · $hiddenWaiting waiting" +
            if (hiddenFailed > 0) " · $hiddenFailed failed" else ""
    }
}

/** Pure ordering/visibility policy shared by production UI and focused tests. */
object QueueManagerPresentation {
    private const val MAX_VISIBLE = 3

    fun snapshot(jobs: List<QueueManagerJob>, acknowledged: Set<String>): QueueManagerTraySnapshot {
        val relevant = jobs.asSequence()
            .filter { it.audience == QueueJobAudience.RECENT_USER }
            .filterNot { it.ready && it.sessionId in acknowledged }
            .sortedWith(compareBy<QueueManagerJob> { rank(it) }
                .thenByDescending { it.changedAtMs }
                .thenBy { it.sessionId })
            .toList()
        val hidden = relevant.drop(MAX_VISIBLE)
        return QueueManagerTraySnapshot(
            visible = relevant.take(MAX_VISIBLE),
            hiddenCount = hidden.size,
            hiddenReady = hidden.count { it.ready },
            hiddenProcessing = hidden.count { it.active },
            hiddenWaiting = hidden.count { it.state == QueueState.QUEUED },
            hiddenFailed = hidden.count { it.state == QueueState.FAILED },
        )
    }

    private fun rank(job: QueueManagerJob) = when {
        job.active -> 0
        job.ready -> 1
        job.state == QueueState.FAILED -> 2
        else -> 3
    }
}

/** Process-session UI state. It is deliberately not persisted as training evidence. */
object QueueManagerTraySession {
    private val acknowledged = linkedSetOf<String>()
    private var initialized = false
    private var currentReady = emptySet<String>()
    var userCollapsed: Boolean = false
        private set
    var recordingHidden: Boolean = false
        private set

    @Synchronized fun observe(jobs: List<QueueManagerJob>): QueueManagerTraySnapshot {
        currentReady = jobs.filter { it.ready }.mapTo(linkedSetOf()) { it.sessionId }
        if (!initialized) {
            // A fresh launch does not turn all historical READY rows into an inbox.
            acknowledged += currentReady
            initialized = true
        }
        return QueueManagerPresentation.snapshot(jobs, acknowledged)
    }

    @Synchronized fun collapse() { userCollapsed = true }
    @Synchronized fun expand() { userCollapsed = false }
    @Synchronized fun hideForRecording(hidden: Boolean) { recordingHidden = hidden }
    @Synchronized fun acknowledgeReady(sessionId: String) {
        if (sessionId in currentReady) acknowledged += sessionId
    }
    @Synchronized fun acknowledgedIds(): Set<String> = acknowledged.toSet()

    internal @Synchronized fun resetForTests(collapsed: Boolean = false) {
        acknowledged.clear(); currentReady = emptySet(); initialized = false
        userCollapsed = collapsed; recordingHidden = false
    }
}

/** One application observer fans durable Queue Manager state out to tray instances. */
object QueueManager {
    private val handler = Handler(Looper.getMainLooper())
    private val observers = linkedSetOf<(QueueManagerTraySnapshot) -> Unit>()
    private var context: Context? = null
    private var loading = false
    private var started = false
    private var latest = QueueManagerTraySnapshot(emptyList(), 0, 0, 0, 0)
    private val refresh = object : Runnable {
        override fun run() {
            poll()
            if (started) handler.postDelayed(this, 1_500)
        }
    }

    fun start(context: Context) {
        if (started) return
        this.context = context.applicationContext
        started = true
        handler.post(refresh)
    }

    fun observe(context: Context, observer: (QueueManagerTraySnapshot) -> Unit) {
        start(context)
        observers += observer
        observer(latest)
    }

    fun removeObserver(observer: (QueueManagerTraySnapshot) -> Unit) { observers -= observer }
    fun refreshNow() { if (started) handler.post { poll() } }

    fun collapseTray() {
        QueueManagerTraySession.collapse()
        publish(latest)
    }

    fun expandTray() {
        QueueManagerTraySession.expand()
        publish(latest)
    }

    fun setRecordingHidden(hidden: Boolean) {
        QueueManagerTraySession.hideForRecording(hidden)
        publish(QueueManagerPresentation.snapshot(latestJobs, QueueManagerTraySession.acknowledgedIds()))
    }

    fun acknowledge(sessionId: String) {
        QueueManagerTraySession.acknowledgeReady(sessionId)
        publish(QueueManagerPresentation.snapshot(latestJobs, QueueManagerTraySession.acknowledgedIds()))
    }

    private var latestJobs = emptyList<QueueManagerJob>()
    private fun poll() {
        val app = context ?: return
        if (loading) return
        loading = true
        TrainingServices.get(app).submit({ repo ->
            repo.jobs().mapNotNull { processing ->
                val session = repo.session(processing.sessionId) ?: return@mapNotNull null
                val recording = repo.recording(processing.sessionId) ?: return@mapNotNull null
                QueueManagerJob(
                    jobId = processing.sessionId,
                    recordingId = recording.recordingId,
                    sessionId = session.sessionId,
                    activityKey = session.activityKey,
                    activityName = session.expectedActivity ?: "Recording",
                    plannedRepetitions = session.expectedRepetitions,
                    state = processing.state,
                    phase = processing.phase,
                    planKey = processing.planKey,
                    movementCount = repo.movementCount(session.sessionId),
                    error = processing.error,
                    changedAtMs = processing.promotedAtMs ?: processing.queuedAtMs,
                    audience = if (processing.planKey.startsWith("maintenance:"))
                        QueueJobAudience.MAINTENANCE else QueueJobAudience.RECENT_USER,
                )
            }
        }) { result ->
            loading = false
            result.onSuccess { jobs ->
                latestJobs = jobs
                publish(QueueManagerTraySession.observe(jobs))
            }
        }
    }

    private fun publish(snapshot: QueueManagerTraySnapshot) {
        latest = snapshot
        observers.toList().forEach { it(snapshot) }
    }
}
