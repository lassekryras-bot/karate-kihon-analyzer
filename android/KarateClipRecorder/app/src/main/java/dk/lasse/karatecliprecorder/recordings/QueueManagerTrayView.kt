package dk.lasse.karatecliprecorder.recordings

import android.animation.LayoutTransition
import android.content.Context
import android.content.Intent
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView
import androidx.core.content.ContextCompat
import androidx.core.view.ViewCompat
import dk.lasse.karatecliprecorder.R
import dk.lasse.karatecliprecorder.training.QueueState

/** App-shell presentation of Queue Manager state. This view never starts or cancels processing. */
class QueueManagerTrayView(context: Context) : LinearLayout(context) {
    private val expanded = LinearLayout(context).apply { orientation = VERTICAL }
    private val rows = LinearLayout(context).apply { orientation = VERTICAL }
    private val overflow = text("", 12f, false).apply {
        setTextColor(ContextCompat.getColor(context, R.color.app_text_secondary))
    }
    private val handle = text("Queue Manager", 12f, true).apply {
        gravity = Gravity.CENTER
        minimumHeight = dp(48)
        isClickable = true
        isFocusable = true
        setOnClickListener { QueueManager.expandTray() }
    }
    private var latest = QueueManagerTraySnapshot(emptyList(), 0, 0, 0, 0)
    private var downY = 0f
    private val observer: (QueueManagerTraySnapshot) -> Unit = { render(it) }

    init {
        orientation = VERTICAL
        layoutTransition = LayoutTransition().apply { setDuration(180) }
        background = GradientDrawable().apply {
            setColor(ContextCompat.getColor(context, R.color.app_card_surface))
            setStroke(dp(1), ContextCompat.getColor(context, R.color.app_divider))
            cornerRadii = floatArrayOf(0f, 0f, 0f, 0f, dp(14).toFloat(), dp(14).toFloat(), dp(14).toFloat(), dp(14).toFloat())
        }
        elevation = dp(2).toFloat()
        expanded.setPadding(dp(16), dp(8), dp(16), dp(8))
        expanded.addView(text("Queue Manager", 14f, true).apply {
            contentDescription = "Queue Manager Tray, expanded. Swipe up to collapse."
            ViewCompat.setAccessibilityHeading(this, true)
        })
        expanded.addView(rows)
        expanded.addView(overflow)
        expanded.setOnTouchListener { _, event -> gesture(event) }
        addView(expanded, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT))
        addView(handle, LayoutParams(LayoutParams.MATCH_PARENT, dp(48)))
        handle.setOnTouchListener { _, event ->
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> { downY = event.y; true }
                MotionEvent.ACTION_UP -> {
                    if (event.y - downY > dp(32)) QueueManager.expandTray() else handle.performClick()
                    true
                }
                else -> true
            }
        }
        visibility = View.GONE
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        QueueManager.observe(context, observer)
    }

    override fun onDetachedFromWindow() {
        QueueManager.removeObserver(observer)
        super.onDetachedFromWindow()
    }

    private fun render(snapshot: QueueManagerTraySnapshot) {
        latest = snapshot
        val hiddenForRecording = QueueManagerTraySession.recordingHidden
        visibility = if (snapshot.visible.isEmpty() || hiddenForRecording) View.GONE else View.VISIBLE
        if (visibility == View.GONE) return
        val collapsed = QueueManagerTraySession.userCollapsed
        expanded.visibility = if (collapsed) View.GONE else View.VISIBLE
        handle.visibility = if (collapsed) View.VISIBLE else View.GONE
        if (collapsed) {
            val ready = snapshot.visible.count { it.ready } + snapshot.hiddenReady
            val active = snapshot.visible.count { it.active } + snapshot.hiddenProcessing
            val count = snapshot.visible.size + snapshot.hiddenCount
            handle.text = when {
                ready > 0 -> "Queue Manager · $ready ready"
                active > 0 -> "Queue Manager · processing"
                else -> "Queue Manager · $count waiting"
            }
            handle.contentDescription = "${handle.text}. Tap or swipe down to expand."
            ViewCompat.setStateDescription(handle, "Collapsed")
            return
        }
        rows.removeAllViews()
        snapshot.visible.forEach { rows.addView(row(it)) }
        overflow.text = snapshot.overflowLabel.orEmpty()
        overflow.visibility = if (snapshot.overflowLabel == null) View.GONE else View.VISIBLE
        contentDescription = "Queue Manager Tray, expanded, ${snapshot.visible.size} visible jobs"
        ViewCompat.setStateDescription(this, "Expanded")
    }

    private fun row(job: QueueManagerJob) = LinearLayout(context).apply {
        orientation = HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        minimumHeight = dp(64)
        setPadding(0, dp(6), 0, dp(6))
        val planned = job.plannedRepetitions?.let { " · $it planned" }.orEmpty()
        addView(LinearLayout(context).apply {
            orientation = VERTICAL
            addView(text(job.activityName + planned, 14f, true))
            addView(text(job.statusLabel, 13f, false).apply {
                setTextColor(ContextCompat.getColor(context, if (job.state == QueueState.FAILED) R.color.app_accent else R.color.app_text_secondary))
            })
            job.error?.takeIf { job.state == QueueState.FAILED }?.let { reason ->
                addView(text(reason, 12f, false).apply {
                    maxLines = 1
                    ellipsize = android.text.TextUtils.TruncateAt.END
                    setTextColor(ContextCompat.getColor(context, R.color.app_text_secondary))
                })
            }
        }, LayoutParams(0, LayoutParams.WRAP_CONTENT, 1f))
        if (job.ready || job.state == QueueState.FAILED) {
            addView(Button(context).apply {
                text = if (job.ready || job.movementCount > 0) "View" else "Details"
                isAllCaps = false
                minimumWidth = dp(64)
                minimumHeight = dp(48)
                contentDescription = if (text == "View") "View ${job.activityName} result"
                    else "Open ${job.activityName} processing details"
                setOnClickListener {
                    if (job.ready) QueueManager.acknowledge(job.sessionId)
                    context.startActivity(Intent(context, RecordingsActivity::class.java)
                        .putExtra(RecordingsActivity.EXTRA_SESSION_ID, job.sessionId))
                }
            })
        }
        contentDescription = "${job.activityName}$planned. ${job.statusLabel}." +
            if (job.ready) " View result available." else ""
    }

    private fun gesture(event: MotionEvent): Boolean {
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> { downY = event.y; return true }
            MotionEvent.ACTION_UP -> {
                val delta = event.y - downY
                if (delta < -dp(32)) QueueManager.collapseTray()
                else if (delta > dp(32)) QueueManager.expandTray()
                else return false
                return true
            }
        }
        return true
    }

    private fun text(value: String, size: Float, bold: Boolean) = TextView(context).apply {
        text = value
        textSize = size
        setTextColor(ContextCompat.getColor(context, R.color.app_text_primary))
        typeface = Typeface.create("sans-serif", if (bold) Typeface.BOLD else Typeface.NORMAL)
        setPadding(0, dp(3), 0, dp(3))
    }

    private fun dp(value: Int) = (value * resources.displayMetrics.density).toInt()
}
