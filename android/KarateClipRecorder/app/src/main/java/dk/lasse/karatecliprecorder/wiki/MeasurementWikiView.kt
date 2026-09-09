package dk.lasse.karatecliprecorder.wiki

import android.content.Context
import android.graphics.*
import android.graphics.drawable.GradientDrawable
import android.os.SystemClock
import android.view.MotionEvent
import android.view.View
import android.widget.*
import androidx.core.content.ContextCompat
import androidx.core.view.ViewCompat
import dk.lasse.karatecliprecorder.AppIcon
import dk.lasse.karatecliprecorder.SettingsCardView
import dk.lasse.karatecliprecorder.SettingsRowView
import dk.lasse.karatecliprecorder.StickyHeaderPageLayout
import dk.lasse.karatecliprecorder.SubPageHeader
import dk.lasse.karatecliprecorder.R
import org.json.JSONArray
import org.json.JSONObject
import kotlin.math.*

/** Offline catalogue-driven wiki. Only explicit, installed renderers can be selected. */
class MeasurementWikiView(context: Context, private val onClose: () -> Unit) : LinearLayout(context) {
    private val catalogue = JSONObject(context.assets.open("wiki/catalogue.json").bufferedReader().use { it.readText() })
    private val content = LinearLayout(context).apply { orientation = VERTICAL }
    private var visual: LinearLayout? = null
    private var player: PunchGraphView? = null
    private var hikitePlayer: HikiteWikiView? = null
    private var page = "index"
    private val ink = ContextCompat.getColor(context, R.color.app_text_primary)
    private val paper = ContextCompat.getColor(context, R.color.home_card_surface)
    private val header = SubPageHeader(context, title = "Measurement wiki", onBack = ::back)
    private var measurementTitle = "Measurement wiki"
    init {
        orientation = VERTICAL
        setBackgroundColor(ContextCompat.getColor(context, R.color.app_background))
        addView(StickyHeaderPageLayout(
            context = context,
            header = header,
            body = content,
            topContentPaddingDp = 16,
        ), LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT))
        index()
    }
    fun back() {
        when (page) {
            "method" -> { content.removeAllViews(); content.addView(visual); header.setTitle(measurementTitle); page = "visual" }
            "visual" -> index()
            else -> onClose()
        }
    }
    private fun label(copy: String, heading: Boolean = false) = TextView(context).apply {
        text = copy; textSize = if (heading) 25f else 17f; setTextColor(ink)
        setPadding(4.dp(), 12.dp(), 4.dp(), 12.dp())
        if (heading) { typeface = Typeface.DEFAULT_BOLD; ViewCompat.setAccessibilityHeading(this, true) }
    }
    private fun index() {
        header.setTitle("Measurement wiki")
        player?.pause(); hikitePlayer?.pause(); hikitePlayer = null; player = null; visual = null; page = "index"; content.removeAllViews()
        content.addView(TextView(context).apply {
            text = "Explore what each measurement means, using an example punch."
            textSize = 14f
            setTextColor(ContextCompat.getColor(context, R.color.app_text_secondary))
        }, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT).apply {
            bottomMargin = 16.dp()
        })
        val entries = catalogue.getJSONArray("measurements")
        for (i in 0 until entries.length()) {
            val definition = entries.getJSONObject(i)
            content.addView(SettingsCardView(context).apply {
                addSettingsRow(SettingsRowView(
                    context,
                    AppIcon.KARATE,
                    definition.getString("title"),
                    definition.getString("description"),
                ).apply { configureAsNavigation(onClick = { open(definition) }) })
            }, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT).apply {
                if (i > 0) topMargin = 10.dp()
            })
        }
    }
    private fun open(definition: JSONObject) {
        content.removeAllViews(); page = "visual"
        measurementTitle = definition.getString("title")
        header.setTitle(measurementTitle)
        val card = LinearLayout(context).apply {
            orientation = VERTICAL
        }
        visual = card; content.addView(card)
        card.addView(label(definition.getString("description")))
        try {
            if (definition.getString("renderer_id") == "hikite_pose_graph") {
                require(definition.getString("measurement_id") == "hikite_finish")
                val example = definition.getJSONObject("wiki_example")
                require(example.getString("presentation_id") == "hikite:event:6")
                val asset = example.getString("asset")
                require(asset.startsWith("wiki/examples/") && !asset.contains(".."))
                val bundle = JSONObject(context.assets.open(asset).bufferedReader().use { it.readText() })
                val drawing = HikiteWikiView(context, bundle)
                hikitePlayer = drawing
                card.addView(drawing)
            } else {
            require(definition.getString("renderer_id") == "pose_with_path_graph") { "Renderer not installed" }
            val speed = definition.getString("measurement_id") == "camera_relative_wrist_speed"
            require(speed || definition.getString("measurement_id") == "punch_path_typical_deviation_rms") {
                "Measurement not supported by this renderer"
            }
            val example = definition.optJSONObject("wiki_example") ?: catalogue.getJSONObject("default_example")
            val asset = example.getString("asset")
            require(asset.startsWith("wiki/examples/") && !asset.contains(".."))
            val bundle = JSONObject(context.assets.open(asset).bufferedReader().use { it.readText() })
            require(bundle.getString("contract") == "karate_measurement_presentation_v2")
            val entries = bundle.getJSONArray("presentations")
            val matches = (0 until entries.length()).map { entries.getJSONObject(it) }.filter {
                it.getString("presentation_id") == example.getString("presentation_id") &&
                    it.getString("measurement_id") == definition.getString("measurement_id")
            }
            require(matches.size == 1) { "Matching example missing" }
            val presentation = matches.single()
            val motion = bundle.getJSONObject("motions").getJSONObject(presentation.getString("motion_id"))
            require(presentation.getJSONObject("availability").getString("status") == "available") {
                presentation.getJSONObject("availability").optString("reason", "Measurement unavailable")
            }
            val scaleUnit = presentation.getJSONObject("scale").getString("output_unit")
            val graphUnit = presentation.getJSONObject("graph").getString("output_unit")
            require(if (speed) (scaleUnit == "upper_arm_length" && graphUnit == "upper_arm_lengths_per_second") ||
                (scaleUnit == "meter" && graphUnit == "meters_per_second")
                else (scaleUnit == "shoulder_width" && graphUnit == "shoulder_width") ||
                    (scaleUnit == "meter" && graphUnit == "meter")) { "Unsupported unit" }
            require(presentation.getString("coordinate_reference") == "fixed_analysis_camera" &&
                presentation.getJSONObject("overlays").getString("coordinate_space") == "fixed_analysis_camera_output_units") {
                "Fixed-camera path required; update this example"
            }
            val summary = presentation.getJSONObject("summary")
            val rms = if (speed) 0.0 else summary.getDouble("typical_deviation_rms_output_units")
            val maximum = summary.getDouble(if (speed) "maximum_speed_output_units" else "maximum_deviation_output_units")
            require(rms.isFinite() && maximum.isFinite() && rms >= 0 && maximum >= rms) {
                "Measurement values unavailable"
            }
            val marker = presentation.getJSONObject("maximum_marker")
            require(motion.getJSONArray("frames").objects().any {
                it.getInt("frame_number") == marker.getInt("frame_number") &&
                    it.getDouble("timestamp_ms") == marker.getDouble("timestamp_ms") && !it.isNull("pose")
            }) { "Maximum pose unavailable" }
            val animationCard = wikiSectionCard(context)
            val graphCard = wikiSectionCard(context)
            card.addView(animationCard)
            card.addView(graphCard)
            animationCard.addView(label(bundle.getJSONObject("example").getString("label")))
            val drawing = PunchGraphView(context, bundle, presentation, motion)
            player = drawing
            val graph = PunchGraphView(context, bundle, presentation, motion, graphOnly = true)
            graph.onScrub = { drawing.seek(it) }
            val samples = presentation.getJSONObject("graph").getJSONArray("samples")
            val firstTimestamp = samples.getJSONObject(0).getDouble("timestamp_ms")
            val lastTimestamp = samples.getJSONObject(samples.length() - 1).getDouble("timestamp_ms")
            val maximumProgress = (marker.getDouble("timestamp_ms") - firstTimestamp) / (lastTimestamp - firstTimestamp)
            val controls = WikiMotionControls(
                context,
                listOf(WikiJumpPoint(if (speed) "Maximum speed" else "Maximum deviation", maximumProgress)),
            )
            controls.onSeek = { drawing.seek(it) }
            controls.onPlayToggle = { if (drawing.playing) drawing.pause() else drawing.play() }
            controls.onSpeedChange = { drawing.setPlaybackRate(it) }
            drawing.onPosition = { progress, _, _ ->
                graph.seek(progress)
                controls.update(progress, drawing.playing)
            }
            animationCard.addView(drawing, LayoutParams(-1, 290.dp()))
            animationCard.addView(controls)
            animationCard.addView(label(definition.getString("figure_text")))
            graphCard.addView(graph, LayoutParams(-1, 170.dp()))
            graphCard.addView(label(definition.getString("graph_text")))
            val unit = if (speed) (if (graphUnit == "meters_per_second") "m/s" else "upper-arm lengths/s") else if (graphUnit == "meter") "m" else "shoulder widths"
            if (!speed) graphCard.addView(label("Typical deviation (RMS): %.3f %s".format(rms, unit)))
            graphCard.addView(label((if (speed) "Maximum speed: %.2f %s" else "Maximum deviation: %.3f %s").format(maximum, unit)))
            drawing.seek(0.0)
            }
        } catch (error: Exception) {
            card.addView(label("Example unavailable: ${error.message ?: "Could not load this measurement"}"))
        }
        card.addView(SettingsCardView(context).apply {
            addSettingsRow(SettingsRowView(context, AppIcon.KARATE,
                "How is this calculated?", "See the measurement method").apply {
            configureAsNavigation(onClick = {
                player?.pause(); hikitePlayer?.pause(); page = "method"; content.removeAllViews()
                header.setTitle("How it is calculated")
                content.addView(wikiSectionCard(context).apply {
                    addView(label(definition.getString("method_text")))
                })
            })
            })
        })
    }
    private fun Int.dp() = (this * resources.displayMetrics.density).roundToInt()
}

/** Shared timestamp cursor drives both upper-body pose and the measurement graph. */
internal class PunchGraphView(context: Context, bundle: JSONObject, presentation: JSONObject, motion: JSONObject, private val graphOnly: Boolean = false) : View(context) {
    private val speed = presentation.getString("measurement_id") == "camera_relative_wrist_speed"
    private val valueKey = if (speed) "speed_output_units" else "signed_deviation_output_units"
    private val graphLabel = if (speed) (if (presentation.getJSONObject("graph").getString("output_unit") == "meters_per_second") "Speed · m/s" else "Speed · upper-arm lengths/s") else if (presentation.getJSONObject("graph").getString("output_unit") == "meter") "Signed deviation · m" else "Signed deviation · shoulder widths"
    private val frames = motion.getJSONArray("frames").objects()
    private val samples = presentation.getJSONObject("graph").getJSONArray("samples").objects()
    private val trajectory = presentation.getJSONObject("overlays").getJSONArray("trajectory_samples").objects()
    private val maximumMarker = presentation.getJSONObject("maximum_marker")
    private val wrist = presentation.getJSONObject("overlays").getString("wrist_role")
    // One camera origin for the whole replay; never re-anchor to the current pose.
    private val origin = frames.first { it.getDouble("timestamp_ms") == samples.first().getDouble("timestamp_ms") }
        .getJSONObject("pose").getJSONObject(wrist.replace("wrist", "shoulder"))
    private val scale = presentation.getJSONObject("scale").getDouble("analysis_pixels_per_output_unit")
    private val geometry = bundle.getJSONObject("frame_geometry").getJSONObject("analysis_frame")
    private val iw = geometry.getDouble("width_px")
    private val ih = geometry.getDouble("height_px")
    private val figureHeadRadius = frames.map { it.getJSONObject("pose").getDouble("head_radius") * iw }.sorted().let { it[it.size / 2].toFloat() }
    private val figurePoints = frames.flatMap { frame ->
        val p = frame.getJSONObject("pose")
        p.keys().asSequence().mapNotNull { key -> p.optJSONObject(key)?.let {
            PointF((it.getDouble("x") * iw).toFloat(), (it.getDouble("y") * ih).toFloat())
        } }.toList() + p.getJSONObject("head_center").let {
            val x = (it.getDouble("x") * iw).toFloat(); val y = (it.getDouble("y") * ih).toFloat()
            listOf(PointF(x - figureHeadRadius, y - figureHeadRadius), PointF(x + figureHeadRadius, y + figureHeadRadius))
        }
    }
    private val first = samples.first().getDouble("timestamp_ms")
    private val last = samples.last().getDouble("timestamp_ms")
    private var timestamp = first
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val ink = ContextCompat.getColor(context, R.color.app_text_primary)
    private val red = ContextCompat.getColor(context, R.color.app_accent)
    private val paper = ContextCompat.getColor(context, R.color.home_card_surface)
    private val layers = motion.getJSONArray("arm_layer_order")
    private var clock = 0L
    private var playbackRate = .5
    var playing = false; private set
    var onScrub: ((Double) -> Unit)? = null
    var onPosition: ((Double, Int, Double) -> Unit)? = null
    private val tick = object : Runnable {
        override fun run() {
            if (!playing) return
            val now = SystemClock.uptimeMillis()
            timestamp = min(last, timestamp + (now - clock) * playbackRate); clock = now
            if (timestamp >= last) playing = false
            update(); if (playing) postDelayed(this, 16)
        }
    }
    init { contentDescription = if (speed) "Punch animation and wrist speed graph" else "Punch animation and wrist deviation graph"; isFocusable = true }
    fun play() { if (timestamp >= last) timestamp = first; playing = true; clock = SystemClock.uptimeMillis(); removeCallbacks(tick); post(tick) }
    fun pause() { playing = false; removeCallbacks(tick); update() }
    fun setPlaybackRate(rate: Double) {
        playbackRate = rate.coerceIn(.1, 1.0)
        if (playing) clock = SystemClock.uptimeMillis()
    }
    fun seek(progress: Double) { pause(); timestamp = first + progress.coerceIn(0.0, 1.0) * (last - first); update() }
    fun seekTimestamp(value: Double) { seek(if (last > first) (value - first) / (last - first) else 0.0) }
    private fun selected() = frames.minBy { abs(it.getDouble("timestamp_ms") - timestamp) }
    private fun update() { invalidate(); onPosition?.invoke(if (last > first) (timestamp - first) / (last - first) else 0.0, selected().getInt("frame_number"), selected().getDouble("timestamp_ms")) }
    override fun onDetachedFromWindow() { pause(); super.onDetachedFromWindow() }
    override fun onWindowFocusChanged(hasWindowFocus: Boolean) { super.onWindowFocusChanged(hasWindowFocus); if (!hasWindowFocus) pause() }
    override fun onWindowVisibilityChanged(visibility: Int) { super.onWindowVisibilityChanged(visibility); if (visibility != VISIBLE) pause() }
    override fun performClick(): Boolean { super.performClick(); return true }
    override fun onTouchEvent(event: MotionEvent): Boolean {
        if (!graphOnly) return false
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN, MotionEvent.ACTION_MOVE -> {
                parent.requestDisallowInterceptTouchEvent(true)
                onScrub?.invoke(((event.x - width * .12f) / (width * .82f)).toDouble().coerceIn(0.0, 1.0)); return true
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> { parent.requestDisallowInterceptTouchEvent(false); performClick(); return true }
        }
        return true
    }
    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val height = (this.height / if (graphOnly) .36f else .64f).toInt()
        if (graphOnly) canvas.translate(0f, -height * .64f)
        val pose = selected().optJSONObject("pose") ?: return
        // Pose and historical overlays share the same fixed camera transform.
        val transform = WikiFigureTransform(figurePoints, width.toFloat(), this.height.toFloat(), 16 * resources.displayMetrics.density)
        val zoom = transform.scale
        fun xy(p: JSONObject) = transform.map((p.getDouble("x") * iw).toFloat(), (p.getDouble("y") * ih).toFloat())
        fun cameraPoint(row: JSONObject): PointF {
            val p = row.getJSONArray("camera_wrist")
            return transform.map((p.getDouble(0) * scale).toFloat(), (p.getDouble(1) * scale).toFloat())
        }
        fun line(a: PointF, b: PointF, color: Int, thickness: Float = 4f) {
            paint.color = color; paint.style = Paint.Style.STROKE; paint.strokeWidth = thickness * resources.displayMetrics.density
            canvas.drawLine(a.x, a.y, b.x, b.y, paint)
        }
        fun arm(side: String) {
            val roles = listOf("${side}_shoulder", "${side}_elbow", "${side}_wrist")
            roles.zipWithNext().forEach { (a,b) ->
                val pa = pose.optJSONObject(a); val pb = pose.optJSONObject(b)
                if (pa != null && pb != null) line(xy(pa), xy(pb), ink)
            }
        }
        for (i in 0 until layers.length()) when (layers.getString(i)) {
            "left_arm" -> arm("left")
            "right_arm" -> arm("right")
            "torso" -> {
                val points = listOf("left_shoulder", "right_shoulder", "right_hip", "left_hip").map { xy(pose.getJSONObject(it)) }
                val path = Path().apply { moveTo(points[0].x, points[0].y); points.drop(1).forEach { lineTo(it.x,it.y) }; close() }
                paint.style = Paint.Style.FILL; paint.color = paper; canvas.drawPath(path,paint)
                paint.style = Paint.Style.STROKE; paint.color = ink; paint.strokeWidth = 2 * resources.displayMetrics.density; canvas.drawPath(path,paint)
            }
        }
        val head = xy(pose.getJSONObject("head_center"))
        paint.style = Paint.Style.STROKE; paint.color = ink
        paint.strokeWidth = 2 * resources.displayMetrics.density
        canvas.drawCircle(head.x,head.y,figureHeadRadius * zoom,paint)
        if (!speed) {
            paint.pathEffect = DashPathEffect(floatArrayOf(10f,8f),0f)
            line(cameraPoint(trajectory.first()),cameraPoint(trajectory.last()),ink,1.5f)
            paint.pathEffect = null
        }
        trajectory.filter { it.getDouble("timestamp_ms") <= timestamp }.zipWithNext().forEach { (a,b) -> line(cameraPoint(a),cameraPoint(b),red,2f) }
        val current = xy(pose.getJSONObject(wrist)); paint.style = Paint.Style.FILL; paint.color = red
        canvas.drawCircle(current.x,current.y,5 * resources.displayMetrics.density,paint)
        fun ring(point: PointF) {
            paint.style = Paint.Style.STROKE; paint.color = ink
            paint.strokeWidth = 2 * resources.displayMetrics.density
            canvas.drawCircle(point.x, point.y, 9 * resources.displayMetrics.density, paint)
        }
        if (selected().getDouble("timestamp_ms") >= maximumMarker.getDouble("timestamp_ms")) {
            val peak = cameraPoint(maximumMarker)
            if (!speed) {
                val projected = cameraPoint(JSONObject().put("camera_wrist", maximumMarker.getJSONArray("camera_reference_point")))
                line(peak, projected, ink, 2f)
            }
            ring(peak)
        }
        val left = width * .12f; val right = width * .94f; val zero = height * (if (speed) .90f else .81f); val amplitude = height * (if (speed) .18f else .09f)
        val maxValue = samples.maxOf { abs(it.getDouble(valueKey)) }.coerceAtLeast(.001)
        fun gx(t: Double) = (left + (t-first)/(last-first)*(right-left)).toFloat()
        fun gy(v: Double) = (zero-v/maxValue*amplitude).toFloat()
        line(PointF(left,zero),PointF(right,zero),ink,1f)
        samples.zipWithNext().forEach { (a,b) -> line(PointF(gx(a.getDouble("timestamp_ms")),gy(a.getDouble(valueKey))),PointF(gx(b.getDouble("timestamp_ms")),gy(b.getDouble(valueKey))),red,1.5f) }
        line(PointF(gx(timestamp),zero-amplitude),PointF(gx(timestamp),if (speed) zero else zero+amplitude),ink,1f)
        val sample = samples.minBy { abs(it.getDouble("timestamp_ms")-timestamp) }
        paint.style = Paint.Style.FILL; paint.color = red
        canvas.drawCircle(gx(sample.getDouble("timestamp_ms")),gy(sample.getDouble(valueKey)),5f,paint)
        maximumMarker.let { marker ->
            ring(PointF(gx(marker.getDouble("timestamp_ms")), gy(marker.getDouble(valueKey))))
        }
        paint.style = Paint.Style.FILL
        paint.color = ink; paint.textSize = 12 * resources.displayMetrics.scaledDensity
        canvas.drawText("0",0f,zero,paint)
        canvas.drawText("+%.2f".format(maxValue),0f,zero-amplitude,paint)
        if (!speed) canvas.drawText("−%.2f".format(maxValue),0f,zero+amplitude,paint)
        canvas.drawText(graphLabel,left,height*.68f,paint)
        canvas.drawText("%.3f s".format(first/1000),left,height*.98f,paint)
        canvas.drawText("%.3f s".format(last/1000),right-55*resources.displayMetrics.density,height*.98f,paint)
    }
}
private fun JSONArray.objects(): List<JSONObject> = (0 until length()).map { getJSONObject(it) }
