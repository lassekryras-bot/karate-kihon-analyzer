package dk.lasse.karatecliprecorder.assisted

import android.app.Dialog
import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.*
import dk.lasse.karateanalyzer.core.PoseFrame
import java.io.File

/** VideoView's fitted bounds are also the normalized-image landmark viewport. */
class LandmarkPlaybackDialog(
    context: Context,
    file: File,
    private val frames: List<PoseFrame>,
    private val playbackStartMs: Long = 0,
    private val playbackEndMs: Long? = null,
) : Dialog(context) {
    private val video = VideoView(context)
    private val landmarkOverlay = object : View(context) {
        private val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.GREEN }
        override fun onDraw(canvas: Canvas) {
            super.onDraw(canvas)
            val time = video.currentPosition.toLong()
            val found = frames.binarySearchBy(time) { it.timestampMs }
            val index = if (found >= 0) found else -found - 2
            val frame = frames.getOrNull(index)
            if (frame != null && time - frame.timestampMs <= 150) frame.landmarks.values.forEach { sample ->
                sample.position?.takeIf { sample.visibility >= .5f && sample.presence >= .5f }?.let { point ->
                    canvas.drawCircle(video.left + point.x * video.width, video.top + point.y * video.height, 4f, paint)
                }
            }
            if (isAttachedToWindow) postInvalidateDelayed(33)
        }
    }
    init {
        val body = LinearLayout(context).apply { orientation = LinearLayout.VERTICAL }
        body.addView(TextView(context).apply { text = if (frames.isEmpty()) "Recording" else "Movement landmarks"; textSize = 20f })
        val surface = FrameLayout(context).apply {
            setBackgroundColor(Color.BLACK)
            addView(video, FrameLayout.LayoutParams(-1, -1, Gravity.CENTER))
            if (frames.isNotEmpty()) addView(landmarkOverlay, FrameLayout.LayoutParams(-1, -1))
        }
        body.addView(surface, LinearLayout.LayoutParams(-1, 0, 1f))
        body.addView(Button(context).apply { text = "Close"; setOnClickListener { dismiss() } })
        setContentView(body)
        video.setVideoPath(file.absolutePath)
        video.setMediaController(MediaController(context).also { it.setAnchorView(surface) })
        video.setOnPreparedListener {
            video.seekTo(playbackStartMs.coerceIn(0, Int.MAX_VALUE.toLong()).toInt())
            video.start()
            playbackEndMs?.let { end -> stopAt(end) }
        }
        video.setOnErrorListener { _, _, _ -> Toast.makeText(context, "Recording playback failed", Toast.LENGTH_LONG).show(); true }
        setOnDismissListener { video.stopPlayback() }
    }
    override fun onStart() {
        super.onStart()
        window?.setLayout(ViewGroup.LayoutParams.MATCH_PARENT, (context.resources.displayMetrics.heightPixels * .9).toInt())
    }

    private fun stopAt(endMs: Long) {
        if (!video.isPlaying) return
        if (video.currentPosition >= endMs) {
            video.pause()
            video.seekTo(endMs.coerceIn(0, Int.MAX_VALUE.toLong()).toInt())
        } else video.postDelayed({ stopAt(endMs) }, 33)
    }
}
