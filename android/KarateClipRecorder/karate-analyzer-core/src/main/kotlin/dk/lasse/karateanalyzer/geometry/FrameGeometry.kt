package dk.lasse.karateanalyzer.geometry

import kotlin.math.roundToInt

/**
 * Master spatial coordinate in the canonical source frame:
 * Full upright, unmirrored video frame with origin at top-left,
 * +X toward image right, +Y toward image bottom, normalized extent [0,1] x [0,1].
 * Values may legitimately lie outside [0,1]; transformation math does not clamp.
 */
data class SourceNormalizedPoint(
    val x: Float,
    val y: Float,
) {
    init {
        require(x.isFinite() && y.isFinite()) { "Coordinates must be finite: x=$x, y=$y" }
    }
    operator fun plus(other: SourceNormalizedPoint) = SourceNormalizedPoint(x + other.x, y + other.y)
    operator fun minus(other: SourceNormalizedPoint) = SourceNormalizedPoint(x - other.x, y - other.y)
    operator fun times(scale: Float) = SourceNormalizedPoint(x * scale, y * scale)
}

/**
 * Aspect-correct 2D analysis coordinate:
 * xAnalysis = xSource * sourceWidth / sourceHeight
 * yAnalysis = ySource
 * One analysis-space unit corresponds to one source-frame height.
 * Used for Euclidean distances, angles, reach radii, and circle intersections.
 */
data class AspectCorrectPoint(
    val x: Float,
    val y: Float,
) {
    init {
        require(x.isFinite() && y.isFinite()) { "Coordinates must be finite: x=$x, y=$y" }
    }
    operator fun plus(other: AspectCorrectPoint) = AspectCorrectPoint(x + other.x, y + other.y)
    operator fun minus(other: AspectCorrectPoint) = AspectCorrectPoint(x - other.x, y - other.y)
    operator fun times(scale: Float) = AspectCorrectPoint(x * scale, y * scale)

    fun dot(other: AspectCorrectPoint): Float = x * other.x + y * other.y
    fun length(): Float = kotlin.math.sqrt(x * x + y * y)

    fun normalized(): AspectCorrectPoint? {
        val len = length()
        return if (len > 1e-6f && len.isFinite()) AspectCorrectPoint(x / len, y / len) else null
    }
}

/**
 * Transient normalized coordinate relative to a crop [0,1] x [0,1].
 * Never persisted as analysis results.
 */
data class CropLocalPoint(
    val x: Float,
    val y: Float,
) {
    init {
        require(x.isFinite() && y.isFinite()) { "Coordinates must be finite: x=$x, y=$y" }
    }
}

/**
 * Final drawing pixel coordinate on a view/canvas.
 */
data class CanvasPoint(
    val x: Float,
    val y: Float,
) {
    init {
        require(x.isFinite() && y.isFinite()) { "Coordinates must be finite: x=$x, y=$y" }
    }
    operator fun plus(other: CanvasPoint) = CanvasPoint(x + other.x, y + other.y)
    operator fun minus(other: CanvasPoint) = CanvasPoint(x - other.x, y - other.y)
}

/**
 * 2D rectangle in canvas pixel space.
 */
data class CanvasRect(
    val left: Float,
    val top: Float,
    val right: Float,
    val bottom: Float,
) {
    val width: Float get() = (right - left).coerceAtLeast(0f)
    val height: Float get() = (bottom - top).coerceAtLeast(0f)

    fun contains(p: CanvasPoint): Boolean =
        p.x >= left && p.x <= right && p.y >= top && p.y <= bottom

    fun intersect(other: CanvasRect): CanvasRect {
        val l = maxOf(left, other.left)
        val t = maxOf(top, other.top)
        val r = minOf(right, other.right)
        val b = minOf(bottom, other.bottom)
        return if (l < r && t < b) CanvasRect(l, t, r, b) else CanvasRect(0f, 0f, 0f, 0f)
    }
}

enum class CanonicalOrientation {
    UPRIGHT_UNMIRRORED,
}

/**
 * Spatial geometry of the source video frame.
 */
data class FrameGeometry(
    val sourceWidth: Int,
    val sourceHeight: Int,
    val canonicalOrientation: CanonicalOrientation = CanonicalOrientation.UPRIGHT_UNMIRRORED,
) {
    init {
        require(sourceWidth > 0) { "sourceWidth must be strictly positive, was $sourceWidth" }
        require(sourceHeight > 0) { "sourceHeight must be strictly positive, was $sourceHeight" }
    }

    val aspectRatio: Float = sourceWidth.toFloat() / sourceHeight.toFloat()
}

/**
 * Requested normalized crop within the canonical source frame [0,1] x [0,1].
 */
data class NormalizedCrop(
    val left: Float,
    val top: Float,
    val right: Float,
    val bottom: Float,
) {
    init {
        require(left.isFinite() && right.isFinite() && top.isFinite() && bottom.isFinite()) {
            "Crop bounds must be finite: left=$left, top=$top, right=$right, bottom=$bottom"
        }
        require(left >= 0f && right <= 1f && left < right) {
            "Invalid horizontal crop range: left=$left, right=$right"
        }
        require(top >= 0f && bottom <= 1f && top < bottom) {
            "Invalid vertical crop range: top=$top, bottom=$bottom"
        }
    }

    val width: Float get() = right - left
    val height: Float get() = bottom - top

    companion object {
        val FULL = NormalizedCrop(0f, 0f, 1f, 1f)
    }

    /**
     * Converts normalized crop to deterministic applied pixel crop.
     * Rounding strategy: standard round-to-nearest integer.
     */
    fun toAppliedPixelCrop(frameGeometry: FrameGeometry): AppliedPixelCrop {
        val l = (left * frameGeometry.sourceWidth).roundToInt().coerceIn(0, frameGeometry.sourceWidth - 1)
        val t = (top * frameGeometry.sourceHeight).roundToInt().coerceIn(0, frameGeometry.sourceHeight - 1)
        val r = (right * frameGeometry.sourceWidth).roundToInt().coerceIn(l + 1, frameGeometry.sourceWidth)
        val b = (bottom * frameGeometry.sourceHeight).roundToInt().coerceIn(t + 1, frameGeometry.sourceHeight)
        return AppliedPixelCrop(l, t, r, b)
    }
}

/**
 * Deterministic applied pixel crop:
 * left inclusive, top inclusive, right exclusive, bottom exclusive.
 */
data class AppliedPixelCrop(
    val left: Int,
    val top: Int,
    val right: Int,
    val bottom: Int,
) {
    init {
        require(left >= 0) { "AppliedPixelCrop left must be non-negative, was $left" }
        require(top >= 0) { "AppliedPixelCrop top must be non-negative, was $top" }
        require(right > left) { "AppliedPixelCrop right ($right) must be greater than left ($left)" }
        require(bottom > top) { "AppliedPixelCrop bottom ($bottom) must be greater than top ($top)" }
    }

    val width: Int get() = right - left
    val height: Int get() = bottom - top
    val aspectRatio: Float get() = width.toFloat() / height.toFloat()

    /**
     * Reconstructs exact normalized crop from applied pixel crop.
     */
    fun toNormalizedCrop(frameGeometry: FrameGeometry): NormalizedCrop {
        return NormalizedCrop(
            left = left.toFloat() / frameGeometry.sourceWidth.toFloat(),
            top = top.toFloat() / frameGeometry.sourceHeight.toFloat(),
            right = right.toFloat() / frameGeometry.sourceWidth.toFloat(),
            bottom = bottom.toFloat() / frameGeometry.sourceHeight.toFloat(),
        )
    }
}

enum class ContentScaleMode {
    FIT,
    CROP,
}

/**
 * Alignment bias in [-1, +1].
 * horizontalBias: -1 = start/left, 0 = center, +1 = end/right
 * verticalBias: -1 = top, 0 = center, +1 = bottom
 */
data class Alignment(
    val horizontalBias: Float = 0f,
    val verticalBias: Float = 0f,
) {
    companion object {
        val CENTER = Alignment(0f, 0f)
        val TOP_START = Alignment(-1f, -1f)
        val TOP_CENTER = Alignment(0f, -1f)
        val BOTTOM_CENTER = Alignment(0f, 1f)
        val BOTTOM_END = Alignment(1f, 1f)
    }
}

/**
 * Uniform zoom and canvas pixel pan.
 */
data class ZoomPan(
    val zoom: Float = 1f,
    val panX: Float = 0f,
    val panY: Float = 0f,
) {
    init {
        require(zoom.isFinite() && zoom > 0f) { "Zoom must be finite and strictly positive, was $zoom" }
        require(panX.isFinite() && panY.isFinite()) { "Pan coordinates must be finite: panX=$panX, panY=$panY" }
    }
}

enum class HitTestResult {
    INSIDE_IMAGE,
    OUTSIDE_IMAGE,
    OUTSIDE_VIEWPORT,
}

/**
 * Resolved display transform representing how a selected source image region
 * maps to the viewport/canvas.
 */
data class ResolvedDisplayTransform(
    val viewportBounds: CanvasRect,
    val displayedImageBounds: CanvasRect,
    val contentClippingBounds: CanvasRect,
    val scaleX: Float,
    val scaleY: Float,
    val selectedCrop: NormalizedCrop,
    val appliedPixelCrop: AppliedPixelCrop,
    val contentScaleMode: ContentScaleMode,
    val alignment: Alignment,
    val zoomPan: ZoomPan,
    val frameGeometry: FrameGeometry,
)
