package dk.lasse.karatecliprecorder.recordings

import android.app.Activity
import android.graphics.*
import android.media.MediaExtractor
import android.media.MediaFormat
import android.media.MediaMetadataRetriever
import android.os.Build
import android.view.MotionEvent
import android.view.View
import android.widget.*
import androidx.appcompat.app.AlertDialog
import dk.lasse.karateanalyzer.geometry.*
import dk.lasse.karatecliprecorder.training.*
import java.io.File
import java.util.concurrent.Executors

/** Selects actual video frame indices, then maps FIT-display taps through the shared inverse transform. */
object BodyScaleCalibrationDialog {
    private data class Source(val file: File, val geometry: CanonicalGeometryDescriptor, val timestamps: List<Long>, val durationUs: Long)

    fun show(activity: Activity, training: TrainingServices, sessionId: String, changed: () -> Unit) {
        if (Build.VERSION.SDK_INT < 28) {
            Toast.makeText(activity, "Frame-index calibration requires Android 9 or newer.", Toast.LENGTH_LONG).show()
            return
        }
        training.submit({ repo ->
            val recording = requireNotNull(repo.recording(sessionId))
            val run = repo.currentRun(sessionId)
            val track = repo.tracks(recording.recordingId).firstOrNull { it.landmarkTrackId == run?.sourceLandmarkTrackId }
                ?: error("Process this recording before calibrating image height.")
            check(track.state == ProcessingState.COMPLETED && track.sourceState == SourceState.AVAILABLE)
            val geometry = CanonicalGeometryStorageAdapter.resolveTrackGeometry(recording, track, { repo.file(it) })
            check(geometry.isAvailable && geometry.landmarkTrackId == track.landmarkTrackId) { "Canonical recording geometry is unavailable." }
            val file = repo.file(recording.filePath)
            check(file.isFile && recording.sourceState == SourceState.AVAILABLE) { "Original recording is unavailable." }
            val extractor = MediaExtractor()
            val times = mutableListOf<Long>()
            try {
                extractor.setDataSource(file.absolutePath)
                val index = (0 until extractor.trackCount).first { extractor.getTrackFormat(it).getString(MediaFormat.KEY_MIME)?.startsWith("video/") == true }
                extractor.selectTrack(index)
                while (extractor.sampleTime >= 0) { times += extractor.sampleTime; extractor.advance() }
            } finally { extractor.release() }
            val ordered = times.sorted()
            check(ordered.isNotEmpty() && ordered.distinct().size == ordered.size) { "Ambiguous video frame timestamps." }
            Source(file, geometry, ordered, maxOf(recording.durationUs ?: 0, ordered.last() + 1))
        }) { result ->
            result.onSuccess { source -> showEditor(activity, training, sessionId, source, changed) }
                .onFailure { Toast.makeText(activity, it.message ?: "Calibration unavailable", Toast.LENGTH_LONG).show() }
        }
    }

    @android.annotation.TargetApi(28)
    private fun showEditor(activity: Activity, training: TrainingServices, sessionId: String, source: Source, changed: () -> Unit) {
        val executor = Executors.newSingleThreadExecutor()
        var closed = false
        var generation = 0
        var selected = 0
        var loadedIndex: Int? = null
        val body = LinearLayout(activity).apply { orientation = LinearLayout.VERTICAL; setPadding(20, 8, 20, 8) }
        val status = TextView(activity)
        val preview = CalibrationFrameView(activity, source.geometry.toFrameGeometry())
        val confirmation = CheckBox(activity).apply {
            text = "I am upright and fully visible here. Camera position, zoom and my distance from the camera stay unchanged throughout this recording."
        }
        body.addView(TextView(activity).apply { text = "Choose an upright frame at your performance position. Tap the top of your head, then the floor at your feet. Recalibrate if camera, zoom or distance changes. This is projected image height, not a physical-distance guarantee." })
        body.addView(preview, LinearLayout.LayoutParams(-1, (activity.resources.displayMetrics.heightPixels * 0.38f).toInt()))
        body.addView(status)
        val seek = SeekBar(activity).apply { max = source.timestamps.lastIndex }
        body.addView(seek)
        val buttons = LinearLayout(activity)
        body.addView(buttons)
        body.addView(confirmation)
        val dialog = AlertDialog.Builder(activity).setTitle("Calibrate image body height")
            .setView(ScrollView(activity).apply { addView(body) }).setNegativeButton("Cancel", null)
            .setPositiveButton("Save & reanalyze", null).create()
        fun load(index: Int) {
            selected = index.coerceIn(source.timestamps.indices)
            val request = ++generation
            val frameIndex = selected
            loadedIndex = null
            preview.setFrame(null)
            status.text = "Loading frame ${frameIndex + 1}…"
            confirmation.isChecked = false
            executor.execute {
                val result = runCatching {
                    val reader = MediaMetadataRetriever()
                    try {
                        reader.setDataSource(source.file.absolutePath)
                        val bitmap = requireNotNull(reader.getFrameAtIndex(frameIndex)) { "Frame could not be decoded." }
                        check(bitmap.width == source.geometry.canonicalWidth && bitmap.height == source.geometry.canonicalHeight) {
                            bitmap.recycle(); "Decoded frame does not match the saved canonical geometry. Reprocess landmarks first."
                        }
                        bitmap
                    } finally { reader.release() }
                }
                activity.runOnUiThread {
                    if (closed || request != generation) { result.getOrNull()?.recycle(); return@runOnUiThread }
                    result.onSuccess {
                        preview.setFrame(it); loadedIndex = frameIndex
                        status.text = "Frame ${frameIndex + 1} · %.3f s. Tap head top, then floor.".format(source.timestamps[frameIndex] / 1_000_000.0)
                    }.onFailure { status.text = it.message ?: "Frame unavailable" }
                }
            }
        }
        seek.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(bar: SeekBar?, value: Int, fromUser: Boolean) { if (fromUser) selected = value }
            override fun onStartTrackingTouch(bar: SeekBar?) {}
            override fun onStopTrackingTouch(bar: SeekBar?) { load(selected) }
        })
        fun button(label: String, action: () -> Unit) = Button(activity).apply { text = label; setOnClickListener { action() } }
        buttons.addView(button("Previous") { seek.progress = (selected - 1).coerceAtLeast(0); load(seek.progress) })
        buttons.addView(button("Next") { seek.progress = (selected + 1).coerceAtMost(seek.max); load(seek.progress) })
        buttons.addView(button("Reset points") { preview.resetPoints() })
        dialog.setOnDismissListener { closed = true; generation++; executor.shutdown(); preview.setFrame(null) }
        dialog.setOnShowListener {
            dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
                val index = loadedIndex
                val head = preview.head
                val floor = preview.floor
                if (index == null || head == null || floor == null || !confirmation.isChecked) {
                    status.text = "Select both points and confirm the recording conditions."; return@setOnClickListener
                }
                val c = ImageBodyScaleCalibration(trainingId(), source.geometry, source.timestamps[index], index,
                    head, floor, 0, source.durationUs, true)
                if (ImageBodyScaleProvider.evidence(c, source.geometry, 0, source.durationUs) == null) {
                    status.text = "The head must be above the floor, with both points inside the image."; return@setOnClickListener
                }
                dialog.getButton(AlertDialog.BUTTON_POSITIVE).isEnabled = false
                training.submit({ repo ->
                    check(repo.currentRun(sessionId)?.sourceLandmarkTrackId == c.geometry.landmarkTrackId) { "Landmark track changed; reopen calibration." }
                    repo.addEvent(SessionEvent(sessionId = sessionId, type = ImageBodyScaleStore.EVENT,
                        timestampUs = c.frameTimestampUs, data = ImageBodyScaleStore.encode(c), timingSource = "video_frame_index_pts"))
                }) { saved ->
                    saved.onSuccess {
                        dialog.dismiss()
                        Toast.makeText(activity, "Calibration saved. Reanalysis started.", Toast.LENGTH_SHORT).show()
                        training.reanalyze(sessionId) { run ->
                            Toast.makeText(activity, run.exceptionOrNull()?.message ?: "Reanalysis complete", Toast.LENGTH_LONG).show(); changed()
                        }
                    }.onFailure { status.text = it.message; dialog.getButton(AlertDialog.BUTTON_POSITIVE).isEnabled = true }
                }
            }
            load(0)
        }
        dialog.show()
    }
}

private class CalibrationFrameView(context: android.content.Context, private val geometry: FrameGeometry) : View(context) {
    private var bitmap: Bitmap? = null
    var head: SourceNormalizedPoint? = null; private set
    var floor: SourceNormalizedPoint? = null; private set
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    fun resetPoints() { head = null; floor = null; invalidate() }
    fun setFrame(value: Bitmap?) { bitmap?.takeIf { it !== value }?.recycle(); bitmap = value; resetPoints() }
    private fun transform() = OverlayCoordinateTransformer.resolveDisplayTransform(width.toFloat(), height.toFloat(), ContentScaleMode.FIT, frameGeometry = geometry)
    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        canvas.drawColor(Color.BLACK)
        val image = bitmap ?: return
        if (width <= 0 || height <= 0) return
        val t = transform()
        val b = t.displayedImageBounds
        canvas.drawBitmap(image, null, RectF(b.left, b.top, b.right, b.bottom), paint)
        paint.color = Color.CYAN; paint.strokeWidth = 3f
        listOfNotNull(head, floor).forEach {
            val p = OverlayCoordinateTransformer.sourceToCanvas(it, t)
            canvas.drawCircle(p.x, p.y, 9f, paint); canvas.drawLine(b.left, p.y, b.right, p.y, paint)
        }
    }
    override fun onTouchEvent(event: MotionEvent): Boolean {
        if (bitmap == null || width <= 0 || height <= 0) return false
        if (event.action == MotionEvent.ACTION_UP) {
            val t = transform(); val p = CanvasPoint(event.x, event.y)
            if (OverlayCoordinateTransformer.hitTestCanvasPoint(p, t) == HitTestResult.INSIDE_IMAGE) {
                val source = OverlayCoordinateTransformer.canvasToSource(p, t)
                if (head == null || floor != null) { head = source; floor = null } else floor = source
                invalidate(); performClick()
            }
        }
        return true
    }
    override fun performClick(): Boolean { super.performClick(); return true }
}
