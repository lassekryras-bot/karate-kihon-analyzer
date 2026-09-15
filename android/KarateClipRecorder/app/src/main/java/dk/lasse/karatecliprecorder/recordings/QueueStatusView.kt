package dk.lasse.karatecliprecorder.recordings

import android.content.Context
import android.content.Intent
import android.os.Handler
import android.os.Looper
import androidx.appcompat.widget.AppCompatTextView
import dk.lasse.karatecliprecorder.training.*

/** Read-only global status. Poll only while attached; extraction never runs on this executor. */
class QueueStatusView(context: Context) : AppCompatTextView(context) {
    private val handler = Handler(Looper.getMainLooper())
    private var attached = false
    private var loading = false
    private var readySessionId: String? = null
    private val refresh = object : Runnable {
        override fun run() {
            if (!attached) return
            if (!loading) {
                loading = true
                TrainingServices.get(context).submit({ it.jobs() }) { result ->
                    loading = false
                    if (attached) {
                        val message = queueStatus(result.getOrDefault(emptyList()))
                        readySessionId = result.getOrDefault(emptyList()).firstOrNull {
                            it.state == QueueState.READY && it.phase == ProcessingPhase.READY
                        }?.sessionId
                        text = message; visibility = if (message.isEmpty()) GONE else VISIBLE
                    }
                }
            }
            handler.postDelayed(this, 2000)
        }
    }
    init {
        visibility = GONE; textSize = 12f
        val padding = (8 * resources.displayMetrics.density).toInt()
        setPadding(padding, padding, padding, padding)
        setTextColor(android.graphics.Color.WHITE); setBackgroundColor(0xDD303030.toInt())
        isClickable = true; isFocusable = true
        setOnClickListener {
            context.startActivity(Intent(context, RecordingsActivity::class.java).apply {
                readySessionId?.let { putExtra(RecordingsActivity.EXTRA_SESSION_ID, it) }
            })
        }
    }
    override fun onAttachedToWindow() { super.onAttachedToWindow(); attached = true; handler.post(refresh) }
    override fun onDetachedFromWindow() { attached = false; handler.removeCallbacksAndMessages(null); super.onDetachedFromWindow() }
    companion object {
        fun queueStatus(jobs: List<RecordingProcessing>): String {
            val queued = jobs.count { it.state == QueueState.QUEUED }
            val processing = jobs.filter { it.state == QueueState.PROCESSING }
            val ready = jobs.any { it.state == QueueState.READY && it.phase == ProcessingPhase.READY }
            val phase = processing.firstOrNull()?.let { when (it.phase) {
                ProcessingPhase.LANDMARKS -> "Processing landmarks"
                ProcessingPhase.SEGMENTATION -> "Finding movements"
                ProcessingPhase.ANALYSIS -> "Analyzing movements"
                else -> "Processing"
            } }
            return listOfNotNull(phase?.let { if (processing.size == 1) it else "${processing.size} processing" },
                if (queued > 0) "$queued queued" else null,
                if (ready) "Segments ready · View" else null).joinToString(" · ")
                .let { if (it.isEmpty()) "" else "Recordings: $it" }
        }
    }
}
