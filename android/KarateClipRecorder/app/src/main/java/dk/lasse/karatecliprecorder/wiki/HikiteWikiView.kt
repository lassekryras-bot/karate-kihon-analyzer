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
    private var clock = 0L
    private val ink = ContextCompat.getColor(context, R.color.app_text_primary)
    private val accent = ContextCompat.getColor(context, R.color.app_accent)
    private val paper = ContextCompat.getColor(context, R.color.home_card_surface)
    private val density = resources.displayMetrics.density
    private val pose = Drawing(false)
    private val graph = Drawing(true)
    private val position = label("")
    private val values = label("")
    private val play = Button(context)
    private val slider = SeekBar(context)
    private val points = rows.flatMap { row -> row.getJSONObject("p").let { p -> p.keys().asSequence().map { point(p, it) }.toList() } } + point(rows.last(), "shoulder_foot")
    private val xmin = points.minOf { it.x }; private val xmax = points.maxOf { it.x }
    private val ymin = points.minOf { it.y } - rows.maxOf { it.getDouble("radius") }.toFloat()
    private val ymax = points.maxOf { it.y }
    private val tick = object : Runnable {
        override fun run() {
            if (!playing) return
            val now = SystemClock.uptimeMillis()
            timestamp = min(last, timestamp + (now - clock) * .5); clock = now
            if (timestamp >= last) playing = false
            update(); if (playing) postDelayed(this, 16)
        }
    }
    init {
        require(bundle.getString("contract") == "karate_measurement_presentation_v2")
        require(presentation.getString("measurement_id") == "hikite_finish")
        require(presentation.getString("presentation_id") == "hikite:event:6")
        require(presentation.getString("coordinate_reference") == "fixed_analysis_camera")
        require(presentation.getString("distance_unit") == "upper_arm_length")
        require(presentation.getString("speed_unit") == "upper_arm_lengths_per_second")
        require(rows.size > 1 && rows.zipWithNext().all { (a,b) -> a.getDouble("t") < b.getDouble("t") })
        require(rows.any { it.getInt("f") == marker.getInt("frame_number") && it.getDouble("t") == marker.getDouble("timestamp_ms") })
        orientation = VERTICAL
        addView(label("Example punch 6 · right-arm hikite"))
        listOf("Wrist to shoulder line", "Forearm to torso", "Wrist position").forEachIndexed { i, copy ->
            addView(Button(context).apply { text = copy; setOnClickListener { mode = i; update() } })
        }
        addView(pose, LayoutParams(-1, (260*density).roundToInt()))
        addView(label("Position lines appear at the finish."))
        addView(graph, LayoutParams(-1, (190*density).roundToInt()))
        addView(label("See how your elbow speed changes. Drag along the graph to inspect the movement."))
        slider.max = 1000; slider.contentDescription = "Punch position"
        slider.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(bar: SeekBar?, progress: Int, fromUser: Boolean) { if (fromUser) seek(progress / 1000.0) }
            override fun onStartTrackingTouch(bar: SeekBar?) { pause() }
            override fun onStopTrackingTouch(bar: SeekBar?) {}
        })
        addView(slider)
        play.setOnClickListener {
            if (playing) pause() else {
                if (timestamp >= last) timestamp = first
                playing = true; clock = SystemClock.uptimeMillis(); update(); post(tick)
            }
        }
        addView(play)
        addView(Button(context).apply { text = "Show finish"; setOnClickListener { seek(1.0) } })
        addView(Button(context).apply { text = "Show maximum speed"; setOnClickListener { seek((marker.getDouble("timestamp_ms")-first)/(last-first)) } })
        addView(position); addView(values)
        addView(label("Maximum elbow speed: %.2f upper-arm lengths/s".format(marker.getDouble("speed"))))
        addView(label("Wrist bend: not measured yet"))
        update()
    }
    private fun label(copy: String) = TextView(context).apply { text = copy; textSize = 17f; setTextColor(ink); setPadding(0, (8*density).toInt(), 0, (8*density).toInt()) }
    private fun point(obj: JSONObject, key: String): PointF = obj.getJSONArray(key).let { PointF(it.getDouble(0).toFloat(), it.getDouble(1).toFloat()) }
    private fun selected() = rows.minBy { abs(it.getDouble("t") - timestamp) }
    fun pause() { playing = false; removeCallbacks(tick); update() }
    private fun seek(progress: Double) { playing = false; removeCallbacks(tick); timestamp = first + progress.coerceIn(0.0,1.0)*(last-first); update() }
    private fun update() {
        val r = selected()
        slider.progress = ((timestamp-first)/(last-first)*1000).roundToInt()
        play.text = if (playing) "Pause" else "Play at half speed"
        position.text = "Frame ${r.getInt("f")} · %.3f s".format(r.getDouble("t")/1000)
        values.text = "Elbow speed: %.2f upper-arm lengths/s\n".format(r.getDouble("speed")) + when(mode) {
            0 -> "Wrist to shoulder line: %.2f upper-arm lengths".format(r.getDouble("shoulder_distance"))
            1 -> "Forearm to torso: %.0f°".format(r.getDouble("forearm_angle"))
            else -> "Wrist ${if(r.getDouble("behind") >= 0) "behind" else "in front"}: %.2f upper-arm lengths".format(abs(r.getDouble("behind")))
        }
        pose.invalidate(); graph.invalidate()
    }
    override fun onDetachedFromWindow() { pause(); super.onDetachedFromWindow() }
    override fun onWindowFocusChanged(hasWindowFocus: Boolean) { super.onWindowFocusChanged(hasWindowFocus); if (!hasWindowFocus) pause() }
    override fun onWindowVisibilityChanged(visibility: Int) { super.onWindowVisibilityChanged(visibility); if (visibility != VISIBLE) pause() }
    private inner class Drawing(private val graphOnly: Boolean) : View(context) {
        private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
        init { contentDescription = if(graphOnly) "Elbow speed graph, upper-arm lengths per second" else "Hikite animation with selected finish measurement" }
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
                text("Elbow speed · upper-arm lengths/s",0f,18*density)
                text("0",0f,bottom); text("%.1f".format(max),0f,top)
                text("%.2f s".format(first/1000),left,height-12*density)
                paint.textSize=12*resources.displayMetrics.scaledDensity
                val end="%.2f s".format(last/1000); text(end,right-paint.measureText(end),height-12*density)
                return
            }
            val z=min((width-32*density)/(xmax-xmin),(height-32*density)/(ymax-ymin))
            fun xy(p: PointF)=PointF((width-(xmax-xmin)*z)/2+(p.x-xmin)*z,16*density+(p.y-ymin)*z)
            val p=r.getJSONObject("p")
            fun at(key: String)=xy(point(p,key))
            fun arm(side: String) { line(at("${side}_shoulder"),at("${side}_elbow"),ink,4f); line(at("${side}_elbow"),at("${side}_wrist"),ink,4f) }
            arm("left")
            val corners=listOf("left_shoulder","right_shoulder","right_hip","left_hip").map { at(it) }
            val body=Path().apply { moveTo(corners[0].x,corners[0].y); corners.drop(1).forEach { lineTo(it.x,it.y) }; close() }
            paint.pathEffect=null; paint.color=paper; paint.style=Paint.Style.FILL; c.drawPath(body,paint)
            paint.color=ink; paint.style=Paint.Style.STROKE; paint.strokeWidth=2*density; c.drawPath(body,paint)
            arm("right"); val head=at("head_center"); c.drawCircle(head.x,head.y,r.getDouble("radius").toFloat()*z,paint)
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
