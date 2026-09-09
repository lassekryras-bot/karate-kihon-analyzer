package dk.lasse.karatecliprecorder.wiki

import android.content.Context
import android.graphics.*
import android.os.SystemClock
import android.view.MotionEvent
import android.view.View
import android.widget.*
import androidx.core.content.ContextCompat
import dk.lasse.karatecliprecorder.R
import org.json.JSONObject
import kotlin.math.*

/** Installed pose-only example. Measurements are exported, never inferred by the UI. */
internal class HikiteWikiView(context: Context, bundle: JSONObject) : LinearLayout(context) {
    private val presentation = bundle.getJSONArray("presentations").getJSONObject(0)
    private val motion = bundle.getJSONObject("motions").getJSONObject(presentation.getString("motion_id"))
    private val rows = motion.getJSONArray("samples").let { a -> (0 until a.length()).map { a.getJSONObject(it) } }
    private val marker = presentation.getJSONObject("maximum_marker")
    private val first = rows.first().getDouble("t")
    private val last = rows.last().getDouble("t")
    private var timestamp = last
    private var mode = 0
    private var playing = false
    private var playbackRate = .5
    private var clock = 0L
    private val ink = ContextCompat.getColor(context, R.color.app_text_primary)
    private val accent = ContextCompat.getColor(context, R.color.app_accent)
    private val paper = ContextCompat.getColor(context, R.color.home_card_surface)
    private val density = resources.displayMetrics.density
    private val pose = Drawing(false)
    private val graph = Drawing(true)
    private val controls = WikiMotionControls(
        context,
        listOf(WikiJumpPoint("Maximum speed", (marker.getDouble("timestamp_ms") - first) / (last - first))),
    )
    private val metrics = WikiMetricList(
        context,
        listOf(
            WikiMetricDefinition("shoulder", "Wrist to shoulder line", "m", selectable = true),
            WikiMetricDefinition("forearm", "Forearm to torso", "°", selectable = true),
            WikiMetricDefinition("wrist", "Wrist position", "m", selectable = true),
            WikiMetricDefinition("speed", "Elbow speed", "m/s"),
            WikiMetricDefinition("bend", "Wrist bend", "—"),
        ),
    )
    private val points = rows.flatMap { row -> row.getJSONObject("p").let { p -> p.keys().asSequence().map { point(p, it) }.toList() } } + point(rows.last(), "shoulder_foot")
    private val xmin = points.minOf { it.x }; private val xmax = points.maxOf { it.x }
    private val headRadius = rows.map { it.getDouble("radius") }.sorted().let { values ->
        (values[(values.size - 1) / 2] + values[values.size / 2]) / 2
    }.toFloat()
    private val ymin = points.minOf { it.y } - headRadius
    private val ymax = points.maxOf { it.y }
    private val tick = object : Runnable {
        override fun run() {
            if (!playing) return
            val now = SystemClock.uptimeMillis()
            timestamp = min(last, timestamp + (now - clock) * playbackRate); clock = now
            if (timestamp >= last) playing = false
            update(); if (playing) postDelayed(this, 16)
        }
    }
    init {
        require(bundle.getString("contract") == "karate_measurement_presentation_v2")
        require(presentation.getString("measurement_id") == "hikite_finish")
        require(presentation.getString("presentation_id") == "hikite:event:6")
        require(presentation.getString("coordinate_reference") == "fixed_analysis_camera")
        require(presentation.getString("distance_unit") == "meter")
        require(presentation.getString("speed_unit") == "meters_per_second")
        require(rows.size > 1 && rows.zipWithNext().all { (a,b) -> a.getDouble("t") < b.getDouble("t") })
        require(rows.any { it.getInt("f") == marker.getInt("frame_number") && it.getDouble("t") == marker.getDouble("timestamp_ms") })
        orientation = VERTICAL
        val animationCard = wikiSectionCard(context)
        val graphCard = wikiSectionCard(context)
        addView(animationCard)
        addView(graphCard)
        animationCard.addView(label("Example punch 6 · right-arm hikite"))
        animationCard.addView(pose, LayoutParams(-1, (290*density).roundToInt()))
        controls.onSeek = { seek(it) }
        controls.onPlayToggle = { if (playing) pause() else play() }
        controls.onSpeedChange = { rate ->
            playbackRate = rate
            if (playing) clock = SystemClock.uptimeMillis()
        }
        animationCard.addView(controls)
        metrics.onMetricSelected = { key ->
            mode = when (key) {
                "shoulder" -> 0
                "forearm" -> 1
                else -> 2
            }
            metrics.select(key)
            update()
        }
        metrics.select("shoulder")
        animationCard.addView(metrics)
        animationCard.addView(label("Position lines appear at the finish."))
        graphCard.addView(graph, LayoutParams(-1, (190*density).roundToInt()))
        graphCard.addView(label("See how your elbow speed changes. Drag along the graph to inspect the movement."))
        update()
    }
    private fun label(copy: String) = TextView(context).apply { text = copy; textSize = 17f; setTextColor(ink); setPadding(0, (8*density).toInt(), 0, (8*density).toInt()) }
    private fun point(obj: JSONObject, key: String): PointF = obj.getJSONArray(key).let { PointF(it.getDouble(0).toFloat(), it.getDouble(1).toFloat()) }
    private fun selected() = rows.minBy { abs(it.getDouble("t") - timestamp) }
    private fun play() {
        if (timestamp >= last) timestamp = first
        playing = true
        clock = SystemClock.uptimeMillis()
        removeCallbacks(tick)
        update()
        post(tick)
    }
    fun pause() { playing = false; removeCallbacks(tick); update() }
    private fun seek(progress: Double) { playing = false; removeCallbacks(tick); timestamp = first + progress.coerceIn(0.0,1.0)*(last-first); update() }
    private fun update() {
        val r = selected()
        controls.update((timestamp-first)/(last-first), playing)
        metrics.setValue("shoulder", "%.2f".format(r.getDouble("shoulder_distance")))
        metrics.setValue("forearm", "%.0f".format(r.getDouble("forearm_angle")))
        metrics.setValue(
            "wrist",
            "%.2f %s".format(
                abs(r.getDouble("behind")),
                if (r.getDouble("behind") >= 0) "behind" else "in front",
            ),
        )
        metrics.setValue("speed", "%.2f".format(r.getDouble("speed")))
        metrics.setValue("bend", "Not measured")
        pose.invalidate(); graph.invalidate()
    }
    override fun onDetachedFromWindow() { pause(); super.onDetachedFromWindow() }
    override fun onWindowFocusChanged(hasWindowFocus: Boolean) { super.onWindowFocusChanged(hasWindowFocus); if (!hasWindowFocus) pause() }
    override fun onWindowVisibilityChanged(visibility: Int) { super.onWindowVisibilityChanged(visibility); if (visibility != VISIBLE) pause() }
    private inner class Drawing(private val graphOnly: Boolean) : View(context) {
        private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
        init { contentDescription = if(graphOnly) "Elbow speed graph, meters per second" else "Hikite animation with selected finish measurement" }
        override fun performClick(): Boolean { super.performClick(); return true }
        override fun onTouchEvent(event: MotionEvent): Boolean {
            if (!graphOnly) return false
            when(event.actionMasked) {
                MotionEvent.ACTION_DOWN, MotionEvent.ACTION_MOVE -> { parent.requestDisallowInterceptTouchEvent(true); seek(((event.x-52*density)/(width-68*density)).toDouble()); return true }
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> { parent.requestDisallowInterceptTouchEvent(false); performClick(); return true }
            }
            return true
        }
        override fun onDraw(c: Canvas) {
            super.onDraw(c)
            fun line(a: PointF,b: PointF,color: Int=ink,thick: Float=2f) { paint.color=color; paint.style=Paint.Style.STROKE; paint.strokeWidth=thick*density; c.drawLine(a.x,a.y,b.x,b.y,paint) }
            fun ring(p: PointF) { paint.color=ink; paint.style=Paint.Style.STROKE; paint.strokeWidth=2*density; c.drawCircle(p.x,p.y,8*density,paint) }
            fun text(copy: String,x: Float,y: Float) { paint.color=ink; paint.style=Paint.Style.FILL; paint.textSize=12*resources.displayMetrics.scaledDensity; c.drawText(copy,x,y,paint) }
            val r=selected()
            if(graphOnly) {
                val left=52*density; val right=width-16*density; val top=34*density; val bottom=height-34*density
                val max=rows.maxOf { it.getDouble("speed") }*1.15
                fun xy(row: JSONObject)=PointF((left+(row.getDouble("t")-first)/(last-first)*(right-left)).toFloat(),(bottom-row.getDouble("speed")/max*(bottom-top)).toFloat())
                line(PointF(left,top),PointF(left,bottom),ink,1f); line(PointF(left,bottom),PointF(right,bottom),ink,1f)
                rows.zipWithNext().forEach { (a,b) -> line(xy(a),xy(b),accent) }
                val current=xy(r); line(PointF(current.x,top),PointF(current.x,bottom),ink,1f)
                ring(xy(rows.first { it.getInt("f")==marker.getInt("frame_number") }))
                paint.color=accent; paint.style=Paint.Style.FILL; c.drawCircle(current.x,current.y,4*density,paint)
                text("Elbow speed · m/s",0f,18*density)
                text("0",0f,bottom); text("%.1f".format(max),0f,top)
                text("%.2f s".format(first/1000),left,height-12*density)
                paint.textSize=12*resources.displayMetrics.scaledDensity
                val end="%.2f s".format(last/1000); text(end,right-paint.measureText(end),height-12*density)
                return
            }
            val headBounds = rows.flatMap { row ->
                val h = point(row.getJSONObject("p"), "head_center")
                listOf(PointF(h.x-headRadius, h.y-headRadius), PointF(h.x+headRadius, h.y+headRadius))
            }
            val transform = WikiFigureTransform(points + headBounds, width.toFloat(), height.toFloat(), 16*density)
            val z = transform.scale
            fun xy(p: PointF)=transform.map(p.x, p.y)
            val p=r.getJSONObject("p")
            fun at(key: String)=xy(point(p,key))
            fun arm(side: String) { line(at("${side}_shoulder"),at("${side}_elbow"),ink,4f); line(at("${side}_elbow"),at("${side}_wrist"),ink,4f) }
            arm("left")
            val corners=listOf("left_shoulder","right_shoulder","right_hip","left_hip").map { at(it) }
            val body=Path().apply { moveTo(corners[0].x,corners[0].y); corners.drop(1).forEach { lineTo(it.x,it.y) }; close() }
            paint.pathEffect=null; paint.color=paper; paint.style=Paint.Style.FILL; c.drawPath(body,paint)
            paint.color=ink; paint.style=Paint.Style.STROKE; paint.strokeWidth=2*density; c.drawPath(body,paint)
            arm("right")
            val rawHead = point(p, "head_center")
            val shoulderMid = point(r, "shoulder_mid")
            val hipMid = point(r, "hip_mid")
            val torsoHeight = hipMid.y - shoulderMid.y
            val headLevel = if (abs(torsoHeight) > 1e-6f) (rawHead.y - shoulderMid.y) / torsoHeight else 0f
            val head = xy(PointF(
                shoulderMid.x + headLevel * (hipMid.x - shoulderMid.x),
                rawHead.y,
            ))
            c.drawCircle(head.x, head.y, headRadius * z, paint)
            if(r.getInt("f")==marker.getInt("frame_number")) ring(at("right_elbow"))
            if(r !== rows.last()) return
            fun dashed(a: PointF,b: PointF) { paint.pathEffect=DashPathEffect(floatArrayOf(6*density,5*density),0f); line(a,b,ink,1.5f); paint.pathEffect=null }
            val wrist=at("right_wrist")
            when(mode) {
                0 -> { val foot=xy(point(r,"shoulder_foot")); val ends=listOf(at("left_shoulder"),at("right_shoulder"),foot).sortedBy { it.x }; dashed(ends.first(),ends.last()); line(wrist,foot) }
                1 -> {
                    val s=point(r,"shoulder_mid"); val h=point(r,"hip_mid"); val w=point(p,"right_wrist")
                    val end=xy(PointF(w.x+(s.x-h.x)*.6f,w.y+(s.y-h.y)*.6f))
                    dashed(xy(s),xy(h)); dashed(wrist,end)
                    val elbow=at("right_elbow")
                    val start=Math.toDegrees(atan2((end.y-wrist.y).toDouble(),(end.x-wrist.x).toDouble())).toFloat()
                    var sweep=Math.toDegrees(atan2((elbow.y-wrist.y).toDouble(),(elbow.x-wrist.x).toDouble())).toFloat()-start
                    while(sweep>180) sweep-=360; while(sweep< -180) sweep+=360
                    val radius=24*density; paint.color=ink; paint.style=Paint.Style.STROKE; paint.strokeWidth=2*density
                    c.drawArc(RectF(wrist.x-radius,wrist.y-radius,wrist.x+radius,wrist.y+radius),start,sweep,false,paint)
                }
                else -> { dashed(xy(point(r,"shoulder_mid")),xy(point(r,"hip_mid"))); line(wrist,xy(point(r,"foot"))) }
            }
        }
    }
}
