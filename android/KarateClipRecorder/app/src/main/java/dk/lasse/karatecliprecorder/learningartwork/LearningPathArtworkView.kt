package dk.lasse.karatecliprecorder.learningartwork

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
import android.widget.FrameLayout
import androidx.core.graphics.PathParser
import dk.lasse.karatecliprecorder.enso.EnsoBackgroundView
import dk.lasse.karatecliprecorder.enso.EnsoLibrary
import dk.lasse.karatecliprecorder.enso.EnsoVariant
import dk.lasse.karatecliprecorder.enso.EnsoThemeTokens
import org.xmlpull.v1.XmlPullParser
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors
import kotlin.math.min

/**
 * Reusable two-layer learning artwork: a semantic Enso behind an independent black foreground.
 * The selected Enso is created once and remains stable for this view's visible lifetime.
 */
class LearningPathArtworkView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
) : FrameLayout(context, attrs) {
    private val ensoView = EnsoBackgroundView(context)
    private val foregroundView = MonochromeSvgView(context).apply {
        setArtworkScale(FOREGROUND_SCALE)
    }
    private var selectedEnso: EnsoVariant? = null

    init {
        clipChildren = false
        clipToPadding = false
        addView(ensoView, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT))
        addView(foregroundView, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT))
    }

    fun setArtwork(
        foreground: LearningArtworkForeground,
        activityType: LearningActivityType,
        ensoVariant: EnsoVariant? = null,
    ) {
        if (selectedEnso == null) {
            selectedEnso = ensoVariant ?: EnsoLibrary().createInstance().variant
        }

        ensoView.setArtwork(
            selectedVariant = requireNotNull(selectedEnso),
            baseColor = LearningArtworkStyleResolver.ensoBaseColor(activityType),
        )
        foregroundView.setArtwork(foreground.rawResourceId)
    }

    /** Path identity is always neutral; assessment red belongs to the activity screen. */
    fun setPathArtwork(
        foreground: LearningArtworkForeground,
        ensoVariant: EnsoVariant? = null,
    ) {
        if (selectedEnso == null) {
            selectedEnso = ensoVariant ?: EnsoLibrary().createInstance().variant
        }
        ensoView.setArtwork(
            selectedVariant = requireNotNull(selectedEnso),
            baseColor = EnsoThemeTokens.ensoPracticeBaseColor,
        )
        foregroundView.setArtwork(foreground.rawResourceId)
    }

    fun selectedEnsoVariant(): EnsoVariant? = selectedEnso

    companion object {
        private const val FOREGROUND_SCALE = 0.54f
    }
}

/** Minimal vector renderer for the normalized, monochrome learning-foreground SVGs. */
private class MonochromeSvgView(context: Context) : View(context) {
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
        color = Color.BLACK
    }
    private var artwork: MonochromeArtwork? = null
    private var resourceId: Int? = null
    private var requestGeneration = 0
    private var artworkScale = 1f

    init {
        importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_NO
    }

    fun setArtwork(rawResourceId: Int) {
        if (resourceId == rawResourceId && artwork != null) return
        resourceId = rawResourceId
        artwork = null
        requestGeneration += 1
        val generation = requestGeneration
        MonochromeArtworkRepository.loadAsync(resources, rawResourceId) { result ->
            post {
                if (generation != requestGeneration || resourceId != rawResourceId) return@post
                result.onSuccess {
                    artwork = it
                    invalidate()
                }.onFailure {
                    Log.e(TAG, "Unable to render learning foreground resource $rawResourceId.", it)
                }
            }
        }
    }

    fun setArtworkScale(fraction: Float) {
        require(fraction > 0f && fraction <= 1f) { "Artwork scale must be in (0, 1]." }
        artworkScale = fraction
        invalidate()
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val current = artwork ?: return
        if (width == 0 || height == 0) return

        val scale = min(width / current.viewportWidth, height / current.viewportHeight) * artworkScale
        val left = (width - current.viewportWidth * scale) / 2f
        val top = (height - current.viewportHeight * scale) / 2f
        canvas.save()
        canvas.translate(left, top)
        canvas.scale(scale, scale)
        canvas.drawPath(current.path, paint)
        canvas.restore()
    }

    companion object {
        private const val TAG = "LearningForeground"
    }
}

private data class MonochromeArtwork(
    val viewportWidth: Float,
    val viewportHeight: Float,
    val path: Path,
)

private object MonochromeArtworkRepository {
    private val executor = Executors.newSingleThreadExecutor()
    private val cache = ConcurrentHashMap<Int, MonochromeArtwork>()

    fun loadAsync(
        resources: Resources,
        rawResourceId: Int,
        callback: (Result<MonochromeArtwork>) -> Unit,
    ) {
        cache[rawResourceId]?.let { cached ->
            callback(Result.success(cached))
            return
        }
        executor.execute {
            callback(runCatching {
                cache[rawResourceId] ?: resources.openRawResource(rawResourceId).use { stream ->
                    parseMonochromeSvg(stream.reader()).also { cache[rawResourceId] = it }
                }
            })
        }
    }

    private fun parseMonochromeSvg(reader: java.io.Reader): MonochromeArtwork {
        val parser = Xml.newPullParser().apply { setInput(reader) }
        var viewportWidth = 1024f
        var viewportHeight = 1024f
        val combinedPath = Path()
        var pathCount = 0

        while (parser.eventType != XmlPullParser.END_DOCUMENT) {
            if (parser.eventType == XmlPullParser.START_TAG) {
                when (parser.name.substringAfter(':')) {
                    "svg" -> parser.attribute("viewBox")?.let { viewBox ->
                        val values = viewBox.trim().split(Regex("\\s+")).map(String::toFloat)
                        require(values.size == 4 && values[2] > 0f && values[3] > 0f) {
                            "Invalid learning-artwork SVG viewBox: $viewBox"
                        }
                        viewportWidth = values[2]
                        viewportHeight = values[3]
                    }
                    "path" -> {
                        val fill = requireNotNull(parser.attribute("fill")) {
                            "Learning-artwork path is missing its monochrome fill."
                        }
                        require(fill.isBlackFill()) { "Learning-artwork foreground must remain black: $fill" }
                        val pathData = requireNotNull(parser.attribute("d")) {
                            "Learning-artwork path is missing d."
                        }
                        val path = requireNotNull(PathParser.createPathFromPathData(pathData)) {
                            "Unable to parse learning-artwork path data."
                        }
                        path.fillType = if (parser.attribute("fill-rule") == "evenodd") {
                            Path.FillType.EVEN_ODD
                        } else {
                            Path.FillType.WINDING
                        }
                        combinedPath.addPath(path)
                        pathCount += 1
                    }
                }
            }
            parser.next()
        }

        require(pathCount > 0) { "Learning-artwork SVG contains no paths." }
        return MonochromeArtwork(viewportWidth, viewportHeight, combinedPath)
    }

    private fun String.isBlackFill(): Boolean {
        val normalized = lowercase().replace(" ", "")
        return normalized == "#000000" || normalized == "#000" || normalized == "black" ||
            normalized == "rgb(0,0,0)" || normalized == "rgba(0,0,0,1)"
    }

    private fun XmlPullParser.attribute(name: String): String? {
        for (index in 0 until attributeCount) {
            if (getAttributeName(index).substringAfter(':') == name) return getAttributeValue(index)
        }
        return null
    }
}
