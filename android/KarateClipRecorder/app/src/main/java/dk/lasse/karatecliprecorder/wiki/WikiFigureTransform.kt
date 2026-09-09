package dk.lasse.karatecliprecorder.wiki

import android.graphics.PointF
import kotlin.math.min

/** One fixed fit for the entire motion, shared by the body and all overlays. */
internal class WikiFigureTransform(points: List<PointF>, width: Float, height: Float, padding: Float) {
    private val left = points.minOf { it.x }
    private val top = points.minOf { it.y }
    private val spanX = (points.maxOf { it.x } - left).coerceAtLeast(.0001f)
    private val spanY = (points.maxOf { it.y } - top).coerceAtLeast(.0001f)
    val scale = min((width - 2 * padding).coerceAtLeast(1f) / spanX, (height - 2 * padding).coerceAtLeast(1f) / spanY)
    private val offsetX = (width - spanX * scale) / 2
    private val offsetY = (height - spanY * scale) / 2
    fun map(x: Float, y: Float) = PointF(offsetX + (x - left) * scale, offsetY + (y - top) * scale)
}
