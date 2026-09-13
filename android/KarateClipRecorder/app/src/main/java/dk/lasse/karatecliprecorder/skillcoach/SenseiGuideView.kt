package dk.lasse.karatecliprecorder.skillcoach

import android.annotation.SuppressLint
import android.content.Context
import android.content.res.Resources
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.Rect
import android.graphics.Typeface
import android.util.Log
import android.util.Xml
import android.view.Gravity
import android.view.View
import android.widget.FrameLayout
import android.widget.TextView
import androidx.annotation.RawRes
import androidx.core.content.ContextCompat
import androidx.core.graphics.PathParser
import dk.lasse.karatecliprecorder.R
import org.xmlpull.v1.XmlPullParser
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors
import kotlin.math.roundToInt

/**
 * Personalized Guidance / Sensei UI Contract
 *
 * The Sensei is a stable coaching character, not flexible decorative artwork. Every guidance
 * state keeps the same render scale, top-center anchor, and visible composition, including the
 * black belt and knot. The speech bubble likewise has a fixed footprint relative to the Sensei,
 * fixed text size, and a three-line maximum. Do not resize either element or shrink speech text
 * to accommodate a message; rewrite dialogue more concisely instead.
 *
 * First-person, encouraging, or personality-led language belongs in the bubble. Factual app
 * information, analysis, recommendations, and longer explanations belong in the card copy.
 * Future Sensei poses may change the artwork, but must retain this scale and layout footprint.
 * The reusable artwork stays localization-safe: all dialogue is rendered at runtime.
 */
@SuppressLint("ViewConstructor")
class SenseiGuideView(
    context: Context,
    speech: String,
) : FrameLayout(context) {
    private val speechView = TextView(context).apply {
        text = speech
        textSize = SPEECH_TEXT_SIZE_SP
        typeface = Typeface.create("sans-serif", Typeface.BOLD)
        gravity = Gravity.CENTER
        setTextColor(ContextCompat.getColor(context, R.color.skill_coach_speech_text))
        includeFontPadding = false
        maxLines = SPEECH_MAX_LINES
        importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
    }

    init {
        minimumHeight = STANDARD_HEIGHT_DP.dp()
        clipChildren = true
        clipToPadding = true
        contentDescription = "Sensei says: $speech"
        addView(FullColorSvgView(context, R.raw.sensei_speaking_blank_bubble).apply {
            importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
        }, LayoutParams(ARTWORK_SIZE_DP.dp(), ARTWORK_SIZE_DP.dp(), ARTWORK_GRAVITY))
        addView(speechView)
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        super.onMeasure(widthMeasureSpec, heightMeasureSpec)
        val bubble = SenseiArtworkGeometry.speechBounds(measuredWidth, resources.displayMetrics.density)
        speechView.measure(
            MeasureSpec.makeMeasureSpec(bubble.width(), MeasureSpec.EXACTLY),
            MeasureSpec.makeMeasureSpec(bubble.height(), MeasureSpec.EXACTLY),
        )
    }

    override fun onLayout(changed: Boolean, left: Int, top: Int, right: Int, bottom: Int) {
        super.onLayout(changed, left, top, right, bottom)
        val bubble = SenseiArtworkGeometry.speechBounds(width, resources.displayMetrics.density)
        speechView.layout(bubble.left, bubble.top, bubble.right, bubble.bottom)
    }

    private fun Int.dp() = (this * resources.displayMetrics.density).roundToInt()

    companion object {
        const val STANDARD_HEIGHT_DP = 200
        internal const val ARTWORK_SIZE_DP = 240
        internal const val SPEECH_TEXT_SIZE_SP = 10.5f
        internal const val SPEECH_MAX_LINES = 3
        internal const val ARTWORK_GRAVITY = Gravity.TOP or Gravity.CENTER_HORIZONTAL
    }
}

/** Coordinates of the safe text area inside the mirrored 1024-square source artwork. */
private object SenseiArtworkGeometry {
    const val VIEWPORT_SIZE = 1024f
    private const val BUBBLE_LEFT = 55f
    private const val BUBBLE_TOP = 100f
    private const val BUBBLE_WIDTH = 490f
    private const val BUBBLE_HEIGHT = 240f

    fun speechBounds(viewWidth: Int, density: Float): Rect {
        val artworkWidth = SenseiGuideView.ARTWORK_SIZE_DP * density
        val artworkLeft = (viewWidth - artworkWidth) / 2f
        val scale = artworkWidth / VIEWPORT_SIZE
        val left = artworkLeft + BUBBLE_LEFT * scale
        val top = BUBBLE_TOP * scale
        return Rect(
            left.roundToInt(),
            top.roundToInt(),
            (left + BUBBLE_WIDTH * scale).roundToInt(),
            (top + BUBBLE_HEIGHT * scale).roundToInt(),
        )
    }
}

/** Minimal renderer for the supplied path-only, full-color Sensei SVG. */
@SuppressLint("ViewConstructor")
private class FullColorSvgView(
    context: Context,
    @RawRes private val resourceId: Int,
) : View(context) {
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }
    private var artwork: FullColorSvgArtwork? = null

    init {
        FullColorSvgRepository.loadAsync(resources, resourceId) { result ->
            post {
                result.onSuccess {
                    artwork = it
                    invalidate()
                }.onFailure { error ->
                    Log.e(TAG, "Unable to render Sensei artwork $resourceId.", error)
                }
            }
        }
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val image = artwork ?: return
        if (width <= 0 || height <= 0) return

        // The parent gives this renderer a fixed square footprint. Keep that scale and top anchor
        // for every guidance state, then let the shorter parent viewport crop below the belt knot.
        val scale = width.toFloat() / image.viewportWidth
        canvas.save()
        canvas.clipRect(0, 0, width, height)
        canvas.translate(width.toFloat(), 0f)
        canvas.scale(-scale, scale)
        image.paths.forEach { item ->
            paint.color = item.color
            canvas.drawPath(item.path, paint)
        }
        canvas.restore()
    }

    private companion object {
        const val TAG = "SenseiArtwork"
    }
}

private data class FullColorSvgPath(val path: Path, val color: Int)

private data class FullColorSvgArtwork(
    val viewportWidth: Float,
    val viewportHeight: Float,
    val paths: List<FullColorSvgPath>,
)

private object FullColorSvgRepository {
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

    private fun parse(reader: java.io.Reader): FullColorSvgArtwork {
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
                        val path = requireNotNull(
                            PathParser.createPathFromPathData(requireNotNull(parser.attribute("d"))),
                        )
                        path.fillType = if (parser.attribute("fill-rule") == "evenodd") {
                            Path.FillType.EVEN_ODD
                        } else {
                            Path.FillType.WINDING
                        }
                        paths += FullColorSvgPath(
                            path = path,
                            color = parseFill(requireNotNull(parser.attribute("fill"))),
                        )
                    }
                }
            }
            parser.next()
        }
        require(paths.isNotEmpty()) { "Sensei SVG contains no paths." }
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
