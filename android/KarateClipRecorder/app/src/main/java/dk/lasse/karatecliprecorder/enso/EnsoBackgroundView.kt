package dk.lasse.karatecliprecorder.enso

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
import androidx.core.graphics.PathParser
import org.xmlpull.v1.XmlPullParser
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors
import kotlin.math.min

/**
 * Reusable background-only artwork layer for learning cards and pages.
 *
 * Selection is supplied by lifecycle/state code through [setArtwork]; drawing never selects or
 * randomizes a variant. Foreground learning artwork can be placed above this view in any container.
 */
class EnsoBackgroundView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
) : View(context, attrs) {
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }
    private var artwork: EnsoArtwork? = null
    private var variant: EnsoVariant? = null
    private var requestGeneration = 0
    private var palette = EnsoTonePalette.fromBaseColor(EnsoThemeTokens.ensoBaseColor)
    private var artworkScale = DEFAULT_ARTWORK_SCALE

    init {
        importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_NO
    }

    fun setArtwork(
        selectedVariant: EnsoVariant,
        baseColor: Int = EnsoThemeTokens.ensoBaseColor,
    ) {
        setBaseColor(baseColor)
        if (variant == selectedVariant && artwork != null) return

        variant = selectedVariant
        artwork = null
        requestGeneration += 1
        val generation = requestGeneration
        EnsoArtworkRepository.loadAsync(resources, selectedVariant) { result ->
            post {
                if (generation != requestGeneration || variant != selectedVariant) return@post
                result.onSuccess {
                    artwork = it
                    invalidate()
                }.onFailure {
                    Log.e(TAG, "Unable to render Enso ${selectedVariant.debugLabel}.", it)
                }
            }
        }
    }

    fun setBaseColor(baseColor: Int) {
        if (palette.baseColor == baseColor) return
        palette = EnsoTonePalette.fromBaseColor(baseColor)
        invalidate()
    }

    fun setArtworkScale(fraction: Float) {
        require(fraction > 0f && fraction <= 1f) { "Artwork scale must be in (0, 1]." }
        if (artworkScale == fraction) return
        artworkScale = fraction
        invalidate()
    }

    fun selectedVariant(): EnsoVariant? = variant

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val currentArtwork = artwork ?: return
        if (width == 0 || height == 0) return

        val scale = min(
            width / currentArtwork.viewportWidth,
            height / currentArtwork.viewportHeight,
        ) * artworkScale
        val renderedWidth = currentArtwork.viewportWidth * scale
        val renderedHeight = currentArtwork.viewportHeight * scale
        val left = (width - renderedWidth) / 2f
        val top = (height - renderedHeight) / 2f

        canvas.save()
        canvas.translate(left, top)
        canvas.scale(scale, scale)
        currentArtwork.layers.forEach { layer ->
            paint.color = layer.tone?.let(palette::colorForTone) ?: Color.WHITE
            canvas.drawPath(layer.path, paint)
        }
        canvas.restore()
    }

    companion object {
        private const val TAG = "EnsoBackgroundView"
        private const val DEFAULT_ARTWORK_SCALE = 0.90f
    }
}

private data class EnsoArtwork(
    val viewportWidth: Float,
    val viewportHeight: Float,
    val layers: List<EnsoLayer>,
)

private data class EnsoLayer(
    val path: Path,
    val tone: Int?,
)

private object EnsoArtworkRepository {
    private val executor = Executors.newSingleThreadExecutor()
    private val cache = ConcurrentHashMap<EnsoVariant, EnsoArtwork>()

    fun loadAsync(
        resources: Resources,
        variant: EnsoVariant,
        callback: (Result<EnsoArtwork>) -> Unit,
    ) {
        cache[variant]?.let { cached ->
            callback(Result.success(cached))
            return
        }
        executor.execute {
            callback(runCatching {
                cache[variant] ?: resources.openRawResource(variant.rawResourceId).use { stream ->
                    EnsoSvgParser.parse(stream.reader()).also { cache[variant] = it }
                }
            })
        }
    }
}

private object EnsoSvgParser {
    private val toneByFill = EnsoTonePalette.canonicalGray
        .mapIndexed { index, gray -> "#%02X%02X%02X".format(gray, gray, gray) to index + 1 }
        .toMap()

    fun parse(reader: java.io.Reader): EnsoArtwork {
        val parser = Xml.newPullParser().apply { setInput(reader) }
        var viewportWidth = 1024f
        var viewportHeight = 1024f
        val layers = mutableListOf<EnsoLayer>()

        while (parser.eventType != XmlPullParser.END_DOCUMENT) {
            if (parser.eventType == XmlPullParser.START_TAG) {
                when (parser.name.substringAfter(':')) {
                    "svg" -> parser.attribute("viewBox")?.let { viewBox ->
                        val values = viewBox.trim().split(Regex("\\s+")).map(String::toFloat)
                        require(values.size == 4 && values[2] > 0f && values[3] > 0f) {
                            "Invalid Enso SVG viewBox: $viewBox"
                        }
                        viewportWidth = values[2]
                        viewportHeight = values[3]
                    }
                    "path" -> {
                        val pathData = requireNotNull(parser.attribute("d")) { "Enso path is missing d." }
                        val path = requireNotNull(PathParser.createPathFromPathData(pathData)) {
                            "Unable to parse Enso path data."
                        }
                        path.fillType = if (parser.attribute("fill-rule") == "evenodd") {
                            Path.FillType.EVEN_ODD
                        } else {
                            Path.FillType.WINDING
                        }
                        layers.append(path, parser.tone())
                    }
                    "rect" -> {
                        val left = parser.attribute("x")?.toFloat() ?: 0f
                        val top = parser.attribute("y")?.toFloat() ?: 0f
                        val rectWidth = requireNotNull(parser.attribute("width")).toFloat()
                        val rectHeight = requireNotNull(parser.attribute("height")).toFloat()
                        val path = Path().apply {
                            fillType = Path.FillType.WINDING
                            addRect(left, top, left + rectWidth, top + rectHeight, Path.Direction.CW)
                        }
                        layers.append(path, parser.tone())
                    }
                }
            }
            parser.next()
        }

        require(layers.isNotEmpty()) { "Enso SVG contains no drawable layers." }
        return EnsoArtwork(viewportWidth, viewportHeight, layers)
    }

    private fun MutableList<EnsoLayer>.append(path: Path, tone: Int?) {
        val previous = lastOrNull()
        if (previous != null && previous.tone == tone && previous.path.fillType == path.fillType) {
            previous.path.addPath(path)
        } else {
            add(EnsoLayer(path, tone))
        }
    }

    private fun XmlPullParser.tone(): Int? {
        val fill = requireNotNull(attribute("fill")) { "Enso drawable is missing a fill." }.uppercase()
        if (fill == "#FFFFFF") return null
        return requireNotNull(toneByFill[fill]) { "Unknown Enso tone fill: $fill" }
    }

    private fun XmlPullParser.attribute(name: String): String? {
        for (index in 0 until attributeCount) {
            if (getAttributeName(index).substringAfter(':') == name) return getAttributeValue(index)
        }
        return null
    }
}
