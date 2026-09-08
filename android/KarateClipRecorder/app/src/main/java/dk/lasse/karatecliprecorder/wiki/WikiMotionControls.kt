package dk.lasse.karatecliprecorder.wiki

import android.content.Context
import android.graphics.Typeface
import android.graphics.drawable.ColorDrawable
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.AdapterView
import android.widget.ArrayAdapter
import android.widget.FrameLayout
import android.widget.ImageButton
import android.widget.LinearLayout
import android.widget.SeekBar
import android.widget.Spinner
import android.widget.TableLayout
import android.widget.TableRow
import android.widget.TextView
import androidx.core.content.ContextCompat
import dk.lasse.karatecliprecorder.R
import kotlin.math.roundToInt

internal data class WikiJumpPoint(val label: String, val progress: Double)

/**
 * Shared controls for wiki animations: timeline, important-frame navigation,
 * a centered play/pause/replay control, and playback speed.
 */
internal class WikiMotionControls(
    context: Context,
    highlights: List<WikiJumpPoint>,
) : LinearLayout(context) {
    private val density = resources.displayMetrics.density
    private val timeline = SeekBar(context).apply {
        max = 1000
        contentDescription = "Animation position"
    }
    private val play = ImageButton(context).apply {
        minimumWidth = 48.dp()
        minimumHeight = 48.dp()
        setPadding(12.dp(), 12.dp(), 12.dp(), 12.dp())
        setBackgroundResource(android.R.drawable.btn_default)
    }
    private val jumpPoints = listOf(
        WikiJumpPoint("Jump to…", Double.NaN),
        WikiJumpPoint("Start", 0.0),
        WikiJumpPoint("Finish", 1.0),
    ) + highlights
    private var resettingJump = false

    var onSeek: (Double) -> Unit = {}
    var onPlayToggle: () -> Unit = {}
    var onSpeedChange: (Double) -> Unit = {}

    init {
        orientation = VERTICAL
        addView(timeline, LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        timeline.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(bar: SeekBar?, progress: Int, fromUser: Boolean) {
                if (fromUser) onSeek(progress / 1000.0)
            }
            override fun onStartTrackingTouch(bar: SeekBar?) = Unit
            override fun onStopTrackingTouch(bar: SeekBar?) = Unit
        })

        val row = LinearLayout(context).apply {
            orientation = HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        val left = FrameLayout(context)
        val center = FrameLayout(context)
        val right = FrameLayout(context)
        row.addView(left, LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        row.addView(center, LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        row.addView(right, LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))

        val jump = spinner(jumpPoints.map { it.label }).apply {
            contentDescription = "Jump to frame"
            onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
                override fun onNothingSelected(parent: AdapterView<*>?) = Unit
                override fun onItemSelected(parent: AdapterView<*>?, view: View?, position: Int, id: Long) {
                    if (position == 0 || resettingJump) return
                    onSeek(jumpPoints[position].progress)
                    resettingJump = true
                    setSelection(0)
                    resettingJump = false
                }
            }
        }
        left.addView(jump, FrameLayout.LayoutParams(
            ViewGroup.LayoutParams.WRAP_CONTENT,
            ViewGroup.LayoutParams.WRAP_CONTENT,
            Gravity.START or Gravity.CENTER_VERTICAL,
        ))

        play.setOnClickListener { onPlayToggle() }
        center.addView(play, FrameLayout.LayoutParams(
            ViewGroup.LayoutParams.WRAP_CONTENT,
            ViewGroup.LayoutParams.WRAP_CONTENT,
            Gravity.CENTER,
        ))

        val speedValues = listOf(1.0, 0.75, 0.5, 0.25, 0.1)
        val speed = spinner(listOf("×1", "×0.75", "×0.50", "×0.25", "×0.10")).apply {
            contentDescription = "Playback speed"
            setSelection(2)
            onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
                override fun onNothingSelected(parent: AdapterView<*>?) = Unit
                override fun onItemSelected(parent: AdapterView<*>?, view: View?, position: Int, id: Long) {
                    onSpeedChange(speedValues[position])
                }
            }
        }
        right.addView(speed, FrameLayout.LayoutParams(
            ViewGroup.LayoutParams.WRAP_CONTENT,
            ViewGroup.LayoutParams.WRAP_CONTENT,
            Gravity.END or Gravity.CENTER_VERTICAL,
        ))
        addView(row, LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        update(1.0, playing = false)
    }

    fun update(progress: Double, playing: Boolean) {
        val normalized = progress.coerceIn(0.0, 1.0)
        timeline.progress = (normalized * 1000).roundToInt()
        when {
            playing -> {
                play.setImageResource(R.drawable.ic_player_pause)
                play.contentDescription = "Pause animation"
            }
            normalized >= 0.9995 -> {
                play.setImageResource(R.drawable.ic_player_replay)
                play.contentDescription = "Replay animation"
            }
            else -> {
                play.setImageResource(R.drawable.ic_player_play)
                play.contentDescription = "Play animation"
            }
        }
    }

    private fun spinner(labels: List<String>) = Spinner(context).apply {
        adapter = ArrayAdapter(context, android.R.layout.simple_spinner_item, labels).also {
            it.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item)
        }
        minimumHeight = 48.dp()
    }

    private fun Int.dp() = (this * density).roundToInt()
}

internal data class WikiMetricDefinition(
    val key: String,
    val label: String,
    val unit: String,
    val selectable: Boolean = false,
)

/** Quiet, table-like list of values at the selected animation frame. */
internal class WikiMetricList(
    context: Context,
    definitions: List<WikiMetricDefinition>,
) : TableLayout(context) {
    private data class Cells(
        val definition: WikiMetricDefinition,
        val row: TableRow,
        val label: TextView,
        val value: TextView,
        val unit: TextView,
    )

    private val density = resources.displayMetrics.density
    private val ink = ContextCompat.getColor(context, R.color.app_text_primary)
    private val secondary = ContextCompat.getColor(context, R.color.app_text_secondary)
    private val cells = linkedMapOf<String, Cells>()
    var onMetricSelected: (String) -> Unit = {}

    init {
        isShrinkAllColumns = true
        dividerDrawable = ColorDrawable(ContextCompat.getColor(context, R.color.app_divider))
        dividerPadding = 0
        showDividers = LinearLayout.SHOW_DIVIDER_MIDDLE
        addView(tableRow("Measurement", "Value", "Unit", heading = true))
        definitions.forEach { definition ->
            val label = cell(definition.label)
            val value = cell("", Gravity.END).apply { typeface = Typeface.MONOSPACE }
            val unit = cell(definition.unit).apply { setTextColor(secondary) }
            val row = TableRow(context).apply {
                addView(label, column(1.55f))
                addView(value, column(.75f))
                addView(unit, column(1.2f))
                if (definition.selectable) {
                    isClickable = true
                    isFocusable = true
                    setBackgroundResource(android.R.drawable.list_selector_background)
                    setOnClickListener { onMetricSelected(definition.key) }
                }
            }
            cells[definition.key] = Cells(definition, row, label, value, unit)
            addView(row)
        }
    }

    fun setValue(key: String, value: String) {
        val item = cells.getValue(key)
        item.value.text = value
        item.row.contentDescription = buildString {
            append(item.definition.label)
            append(", ")
            append(value)
            if (item.definition.unit != "—") {
                append(" ")
                append(item.definition.unit)
            }
            if (item.definition.selectable) append(". Double tap to select its figure overlay.")
        }
    }

    fun select(key: String) {
        cells.values.forEach { item ->
            val selected = item.definition.key == key
            item.label.typeface = if (selected) Typeface.DEFAULT_BOLD else Typeface.DEFAULT
            item.row.isSelected = selected
            item.row.isActivated = selected
        }
    }

    private fun tableRow(a: String, b: String, c: String, heading: Boolean) = TableRow(context).apply {
        addView(cell(a).apply { if (heading) typeface = Typeface.DEFAULT_BOLD }, column(1.55f))
        addView(cell(b, Gravity.END).apply { if (heading) typeface = Typeface.DEFAULT_BOLD }, column(.75f))
        addView(cell(c).apply { if (heading) typeface = Typeface.DEFAULT_BOLD }, column(1.2f))
    }

    private fun cell(copy: String, horizontalGravity: Int = Gravity.START) = TextView(context).apply {
        text = copy
        textSize = 16f
        setTextColor(ink)
        gravity = horizontalGravity or Gravity.CENTER_VERTICAL
        setPadding(8.dp(), 10.dp(), 8.dp(), 10.dp())
    }

    private fun column(weight: Float) = TableRow.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, weight)
    private fun Int.dp() = (this * density).roundToInt()
}
