package dk.lasse.karatecliprecorder.artwork

import android.annotation.SuppressLint
import android.content.Context
import android.content.res.Resources
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.util.AttributeSet
import android.util.Log
import android.util.Xml
import android.view.View
import androidx.annotation.RawRes
import androidx.core.graphics.PathParser
import org.xmlpull.v1.XmlPullParser
import java.io.Reader
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors
import kotlin.math.roundToInt

data class FullColorSvgPath(val path: Path, val color: Int)

data class FullColorSvgArtwork(
    val viewportWidth: Float,
    val viewportHeight: Float,
    val paths: List<FullColorSvgPath>,
)

object FullColorSvgRepository {
    private val executor = Executors.newSingleThreadExecutor()
    private val cache = ConcurrentHashMap<Int, FullColorSvgArtwork>()
    private val rgba = Regex("rgba\\((\\d+),(\\d+),(\\d+),([0-9.]+)\\)")

    fun loadAsync(
        resources: Resources,
        @RawRes resourceId: Int,
        callback: (Result<FullColorSvgArtwork>) -> Unit,
    ) {
        cache[resourceId]?.let { callback(Result.success(it)); return }
        executor.execute {
            callback(runCatching {
                cache[resourceId] ?: resources.openRawResource(resourceId).use { stream ->
                    parse(stream.reader()).also { cache[resourceId] = it }
                }
            })
        }
    }

    private fun parse(reader: Reader): FullColorSvgArtwork {
        val parser = Xml.newPullParser().apply { setInput(reader) }
        var viewportWidth = 1f
        var viewportHeight = 1f
        val paths = mutableListOf<FullColorSvgPath>()
        while (parser.eventType != XmlPullParser.END_DOCUMENT) {
            if (parser.eventType == XmlPullParser.START_TAG) {
                when (parser.name.substringAfter(':')) {
                    "svg" -> parser.attribute("viewBox")?.let { viewBox ->
                        val values = viewBox.trim().split(Regex("\\s+")).map(String::toFloat)
                        require(values.size == 4 && values[2] > 0f && values[3] > 0f)
                        viewportWidth = values[2]
                        viewportHeight = values[3]
                    }
                    "path" -> {
                        val d = parser.attribute("d")
                        if (!d.isNullOrBlank()) {
                            val path = PathParser.createPathFromPathData(d)
                            path.fillType = if (parser.attribute("fill-rule") == "evenodd") {
                                Path.FillType.EVEN_ODD
                            } else {
                                Path.FillType.WINDING
                            }
                            paths += FullColorSvgPath(
                                path = path,
                                color = parseFill(parser.attribute("fill") ?: "#000000"),
                            )
                        }
                    }
                }
            }
            parser.next()
        }
        require(paths.isNotEmpty()) { "SVG contains no valid paths." }
        return FullColorSvgArtwork(viewportWidth, viewportHeight, paths)
    }

    private fun parseFill(value: String): Int {
        val match = rgba.matchEntire(value.replace(" ", "")) ?: return Color.parseColor(value)
        val red = match.groupValues[1].toInt().coerceIn(0, 255)
        val green = match.groupValues[2].toInt().coerceIn(0, 255)
        val blue = match.groupValues[3].toInt().coerceIn(0, 255)
        val alpha = (match.groupValues[4].toFloat().coerceIn(0f, 1f) * 255f).roundToInt()
        return Color.argb(alpha, red, green, blue)
    }

    private fun XmlPullParser.attribute(name: String): String? {
        for (index in 0 until attributeCount) {
            if (getAttributeName(index).substringAfter(':') == name) return getAttributeValue(index)
        }
        return null
    }
}

/**
 * Minimal renderer for path-only, full-color SVGs loaded from raw resources.
 */
@SuppressLint("ViewConstructor")
class FullColorSvgView @JvmOverloads constructor(
    context: Context,
    @RawRes private val resourceId: Int,
    private val mirrored: Boolean = false,
    attrs: AttributeSet? = null,
) : View(context, attrs) {

    private val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }
    private var artwork: FullColorSvgArtwork? = null

    init {
        FullColorSvgRepository.loadAsync(resources, resourceId) { result ->
            post {
                result.onSuccess {
                    artwork = it
                    invalidate()
                }.onFailure { error ->
                    Log.e(TAG, "Unable to render artwork $resourceId.", error)
                }
            }
        }
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val image = artwork ?: return
        if (width <= 0 || height <= 0) return

        val scale = minOf(width.toFloat() / image.viewportWidth, height.toFloat() / image.viewportHeight)
        canvas.save()
        canvas.clipRect(0, 0, width, height)
        if (mirrored) {
            canvas.translate(width.toFloat(), 0f)
            canvas.scale(-scale, scale)
        } else {
            canvas.scale(scale, scale)
        }
        image.paths.forEach { item ->
            paint.color = item.color
            canvas.drawPath(item.path, paint)
        }
        canvas.restore()
    }

    private companion object {
        const val TAG = "FullColorSvgView"
    }
}
