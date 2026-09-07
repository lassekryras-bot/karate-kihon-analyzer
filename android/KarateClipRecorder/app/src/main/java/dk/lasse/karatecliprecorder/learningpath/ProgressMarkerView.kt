package dk.lasse.karatecliprecorder.learningpath

import android.animation.ValueAnimator
import android.content.Context
import android.content.res.Resources
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.Path
import android.util.AttributeSet
import android.util.Log
import android.util.Xml
import android.view.View
import android.view.animation.LinearInterpolator
import androidx.annotation.RawRes
import androidx.core.content.ContextCompat
import androidx.core.graphics.PathParser
import dk.lasse.karatecliprecorder.R
import org.xmlpull.v1.XmlPullParser
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors
import kotlin.math.floor
import kotlin.math.min

enum class ProgressMarkerAsset(@RawRes val rawResourceId: Int) {
    COMPLETED(R.raw.completion_marker_step_12),
    CURRENT(R.raw.progress_current),
    AVAILABLE(R.raw.progress_available),
    MILESTONE(R.raw.progress_milestone),
    LOCK_OVERLAY(R.raw.progress_lock_overlay),
}

data class CompletionMarkerFrameBlend(
    val firstIndex: Int,
    val secondIndex: Int,
    val secondAlpha: Float,
)

/** Vector keyframes supplied for the dot → fill → check completion transition. */
internal object CompletionMarkerAnimationSpec {
    val frameResourceIds = intArrayOf(
        R.raw.completion_marker_step_01,
        R.raw.completion_marker_step_02,
        R.raw.completion_marker_step_03,
        R.raw.completion_marker_step_04,
        R.raw.completion_marker_step_05,
        R.raw.completion_marker_step_06,
        R.raw.completion_marker_step_07,
        R.raw.completion_marker_step_08,
        R.raw.completion_marker_step_09,
        R.raw.completion_marker_step_10,
        R.raw.completion_marker_step_11,
        R.raw.completion_marker_step_12,
    )
    const val DURATION_MS = 760L

    fun blend(progress: Float): CompletionMarkerFrameBlend {
        val clamped = progress.coerceIn(0f, frameResourceIds.lastIndex.toFloat())
        val first = floor(clamped).toInt()
        val second = (first + 1).coerceAtMost(frameResourceIds.lastIndex)
        return CompletionMarkerFrameBlend(first, second, clamped - first)
    }
}

enum class ProgressMarkerTint { RED, PALE_RED, GRAY, BLACK }

data class ProgressMarkerVisual(
    val baseAsset: ProgressMarkerAsset?,
    val baseTint: ProgressMarkerTint?,
    val centerFillTint: ProgressMarkerTint? = null,
    val drawLockedSurface: Boolean = false,
    val lockOverlay: Boolean = false,
)

/** Resolves all seven visible states from the five supplied SVG primitives. */
object ProgressMarkerVisualResolver {
    fun resolve(type: LearningStepType, state: LearningProgressState): ProgressMarkerVisual =
        if (type == LearningStepType.MILESTONE) {
            ProgressMarkerVisual(
                baseAsset = ProgressMarkerAsset.MILESTONE,
                baseTint = if (state == LearningProgressState.COMPLETED) {
                    ProgressMarkerTint.RED
                } else {
                    ProgressMarkerTint.GRAY
                },
                lockOverlay = state == LearningProgressState.LOCKED,
            )
        } else {
            when (state) {
                LearningProgressState.COMPLETED -> ProgressMarkerVisual(
                    ProgressMarkerAsset.COMPLETED,
                    ProgressMarkerTint.RED,
                )
                LearningProgressState.CURRENT -> ProgressMarkerVisual(
                    ProgressMarkerAsset.CURRENT,
                    ProgressMarkerTint.RED,
                    centerFillTint = ProgressMarkerTint.RED,
                )
                LearningProgressState.AVAILABLE -> ProgressMarkerVisual(
                    ProgressMarkerAsset.AVAILABLE,
                    ProgressMarkerTint.RED,
                    centerFillTint = ProgressMarkerTint.PALE_RED,
                )
                LearningProgressState.LOCKED -> ProgressMarkerVisual(
                    baseAsset = null,
                    baseTint = null,
                    drawLockedSurface = true,
                    lockOverlay = true,
                )
            }
        }
}

class ProgressMarkerView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
) : View(context, attrs) {
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }
    private var visual = ProgressMarkerVisualResolver.resolve(
        LearningStepType.REGULAR,
        LearningProgressState.AVAILABLE,
    )
    private var baseArtwork: MarkerArtwork? = null
    private var lockArtwork: MarkerArtwork? = null
    private var requestGeneration = 0
    private val completionFrames = arrayOfNulls<MarkerArtwork>(CompletionMarkerAnimationSpec.frameResourceIds.size)
    private var completionProgress = CompletionMarkerAnimationSpec.frameResourceIds.lastIndex.toFloat()
    private var completionAnimator: ValueAnimator? = null
    private var shouldAnimateCompletion = false
    private var completionAnimationStarted = false

    init {
        importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_NO
        loadArtwork()
    }

    fun setMarker(
        type: LearningStepType,
        state: LearningProgressState,
        animateCompletion: Boolean = false,
    ) {
        completionAnimator?.cancel()
        completionAnimator = null
        visual = ProgressMarkerVisualResolver.resolve(type, state)
        shouldAnimateCompletion = animateCompletion &&
            type == LearningStepType.REGULAR &&
            state == LearningProgressState.COMPLETED
        completionAnimationStarted = false
        completionProgress = if (shouldAnimateCompletion) 0f else CompletionMarkerAnimationSpec.frameResourceIds.lastIndex.toFloat()
        completionFrames.fill(null)
        requestGeneration += 1
        baseArtwork = null
        lockArtwork = null
        loadArtwork()
        invalidate()
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        startCompletionAnimationIfReady()
    }

    override fun onDetachedFromWindow() {
        completionAnimator?.cancel()
        completionAnimator = null
        super.onDetachedFromWindow()
    }

    private fun loadArtwork() {
        val generation = requestGeneration
        visual.baseAsset?.let { asset ->
            MarkerArtworkRepository.loadAsync(resources, asset.rawResourceId) { result ->
                post {
                    if (generation != requestGeneration) return@post
                    result.onSuccess { baseArtwork = it; invalidate() }
                        .onFailure { Log.e(TAG, "Unable to load ${asset.name} marker.", it) }
                }
            }
        }
        if (visual.lockOverlay) {
            MarkerArtworkRepository.loadAsync(resources, ProgressMarkerAsset.LOCK_OVERLAY.rawResourceId) { result ->
                post {
                    if (generation != requestGeneration) return@post
                    result.onSuccess { lockArtwork = it; invalidate() }
                        .onFailure { Log.e(TAG, "Unable to load lock marker overlay.", it) }
                }
            }
        }
        if (shouldAnimateCompletion) loadCompletionFrames(generation)
    }

    private fun loadCompletionFrames(generation: Int) {
        CompletionMarkerAnimationSpec.frameResourceIds.forEachIndexed { index, resourceId ->
            MarkerArtworkRepository.loadAsync(resources, resourceId) { result ->
                post {
                    if (generation != requestGeneration || !shouldAnimateCompletion) return@post
                    result.onSuccess {
                        completionFrames[index] = it
                        startCompletionAnimationIfReady()
                        invalidate()
                    }.onFailure {
                        Log.e(TAG, "Unable to load completion marker frame ${index + 1}.", it)
                    }
                }
            }
        }
    }

    private fun startCompletionAnimationIfReady() {
        if (
            !shouldAnimateCompletion || completionAnimationStarted || !isAttachedToWindow ||
            completionFrames.any { it == null }
        ) return
        completionAnimationStarted = true
        if (!ValueAnimator.areAnimatorsEnabled()) {
            completionProgress = CompletionMarkerAnimationSpec.frameResourceIds.lastIndex.toFloat()
            invalidate()
            return
        }
        completionAnimator = ValueAnimator.ofFloat(
            0f,
            CompletionMarkerAnimationSpec.frameResourceIds.lastIndex.toFloat(),
        ).apply {
            duration = CompletionMarkerAnimationSpec.DURATION_MS
            interpolator = LinearInterpolator()
            addUpdateListener { animation ->
                completionProgress = animation.animatedValue as Float
                invalidate()
            }
            start()
        }
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        if (width == 0 || height == 0) return

        if (shouldAnimateCompletion) {
            val blend = CompletionMarkerAnimationSpec.blend(completionProgress)
            val first = completionFrames[blend.firstIndex]
            val second = completionFrames[blend.secondIndex]
            if (first != null) {
                drawArtwork(canvas, first, tintColor(ProgressMarkerTint.RED), ((1f - blend.secondAlpha) * 255).toInt())
            }
            if (second != null && blend.secondIndex != blend.firstIndex && blend.secondAlpha > 0f) {
                drawArtwork(canvas, second, tintColor(ProgressMarkerTint.RED), (blend.secondAlpha * 255).toInt())
            }
            return
        }

        if (visual.drawLockedSurface) {
            paint.color = ContextCompat.getColor(context, R.color.progress_locked_surface)
            val radius = min(width, height) * 0.46f
            canvas.drawCircle(width / 2f, height / 2f, radius, paint)
        }
        visual.centerFillTint?.let { tint ->
            paint.color = tintColor(tint)
            canvas.drawCircle(
                width / 2f,
                height / 2f,
                min(width, height) * CENTER_FILL_RADIUS_FRACTION,
                paint,
            )
        }
        baseArtwork?.let { drawArtwork(canvas, it, tintColor(requireNotNull(visual.baseTint))) }
        lockArtwork?.let { drawArtwork(canvas, it, tintColor(ProgressMarkerTint.BLACK)) }
    }

    private fun drawArtwork(canvas: Canvas, artwork: MarkerArtwork, color: Int, alpha: Int = 255) {
        paint.color = color
        paint.alpha = alpha.coerceIn(0, 255)
        val scale = min(width / artwork.viewportWidth, height / artwork.viewportHeight)
        canvas.save()
        canvas.translate(
            (width - artwork.viewportWidth * scale) / 2f,
            (height - artwork.viewportHeight * scale) / 2f,
        )
        canvas.scale(scale, scale)
        canvas.drawPath(artwork.path, paint)
        canvas.restore()
        paint.alpha = 255
    }

    private fun tintColor(tint: ProgressMarkerTint): Int = when (tint) {
        ProgressMarkerTint.RED -> ContextCompat.getColor(context, R.color.progress_red)
        ProgressMarkerTint.PALE_RED -> ContextCompat.getColor(context, R.color.progress_pale_fill)
        ProgressMarkerTint.GRAY -> ContextCompat.getColor(context, R.color.progress_gray)
        ProgressMarkerTint.BLACK -> ContextCompat.getColor(context, R.color.progress_icon_black)
    }

    companion object {
        private const val TAG = "ProgressMarker"
        private const val CENTER_FILL_RADIUS_FRACTION = 0.27f
    }
}

private data class MarkerArtwork(
    val viewportWidth: Float,
    val viewportHeight: Float,
    val path: Path,
)

private object MarkerArtworkRepository {
    private val executor = Executors.newSingleThreadExecutor()
    private val cache = ConcurrentHashMap<Int, MarkerArtwork>()

    fun loadAsync(resources: Resources, @RawRes resourceId: Int, callback: (Result<MarkerArtwork>) -> Unit) {
        cache[resourceId]?.let { callback(Result.success(it)); return }
        executor.execute {
            callback(runCatching {
                cache[resourceId] ?: resources.openRawResource(resourceId).use { stream ->
                    parse(stream.reader()).also { cache[resourceId] = it }
                }
            })
        }
    }

    private fun parse(reader: java.io.Reader): MarkerArtwork {
        val parser = Xml.newPullParser().apply { setInput(reader) }
        var viewportWidth = 1024f
        var viewportHeight = 1024f
        val combinedPath = Path()
        val transformStack = java.util.ArrayDeque<Matrix>()
        var count = 0
        while (parser.eventType != XmlPullParser.END_DOCUMENT) {
            if (parser.eventType == XmlPullParser.START_TAG) {
                when (parser.name.substringAfter(':')) {
                    "svg" -> parser.attribute("viewBox")?.let { viewBox ->
                        val values = viewBox.trim().split(Regex("\\s+")).map(String::toFloat)
                        require(values.size == 4 && values[2] > 0f && values[3] > 0f)
                        viewportWidth = values[2]
                        viewportHeight = values[3]
                    }
                    "g" -> {
                        val matrix = transformStack.peekLast()?.let(::Matrix) ?: Matrix()
                        parser.attribute("transform")?.let { applySvgTransform(matrix, it) }
                        transformStack.addLast(matrix)
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
                        transformStack.peekLast()?.let(path::transform)
                        combinedPath.addPath(path)
                        count += 1
                    }
                }
            } else if (
                parser.eventType == XmlPullParser.END_TAG &&
                parser.name.substringAfter(':') == "g" &&
                transformStack.isNotEmpty()
            ) {
                transformStack.removeLast()
            }
            parser.next()
        }
        require(count > 0) { "Progress marker SVG contains no paths." }
        return MarkerArtwork(viewportWidth, viewportHeight, combinedPath)
    }

    private fun applySvgTransform(matrix: Matrix, transform: String) {
        TRANSFORM_REGEX.findAll(transform).forEach { match ->
            val values = match.groupValues[2]
                .trim()
                .split(Regex("[\\s,]+"))
                .filter(String::isNotBlank)
                .map(String::toFloat)
            when (match.groupValues[1]) {
                "translate" -> matrix.postTranslate(values[0], values.getOrElse(1) { 0f })
                "scale" -> matrix.postScale(values[0], values.getOrElse(1) { values[0] })
            }
        }
    }

    private fun XmlPullParser.attribute(name: String): String? {
        for (index in 0 until attributeCount) {
            if (getAttributeName(index).substringAfter(':') == name) return getAttributeValue(index)
        }
        return null
    }

    private val TRANSFORM_REGEX = Regex("(translate|scale)\\(([^)]*)\\)")
}
