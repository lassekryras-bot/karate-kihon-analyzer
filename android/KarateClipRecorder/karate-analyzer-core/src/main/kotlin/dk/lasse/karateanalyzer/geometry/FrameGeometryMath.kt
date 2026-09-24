package dk.lasse.karateanalyzer.geometry

import kotlin.math.abs
import kotlin.math.acos
import kotlin.math.atan2
import kotlin.math.sqrt

/**
 * Pure analyzer/core geometry math.
 * Does not depend on Compose, Canvas, viewport dimensions, or screen size.
 * Aspect-correct space has xAnalysis = xSource * sourceWidth / sourceHeight, yAnalysis = ySource.
 */
object FrameGeometryMath {

    private const val EPSILON = 1e-6f

    fun sourceToAspectCorrect(
        point: SourceNormalizedPoint,
        frameGeometry: FrameGeometry,
    ): AspectCorrectPoint {
        return AspectCorrectPoint(
            x = point.x * frameGeometry.aspectRatio,
            y = point.y,
        )
    }

    fun aspectCorrectToSource(
        point: AspectCorrectPoint,
        frameGeometry: FrameGeometry,
    ): SourceNormalizedPoint {
        return SourceNormalizedPoint(
            x = point.x / frameGeometry.aspectRatio,
            y = point.y,
        )
    }

    fun distance(p1: AspectCorrectPoint, p2: AspectCorrectPoint): Float {
        val dx = p2.x - p1.x
        val dy = p2.y - p1.y
        return sqrt(dx * dx + dy * dy)
    }

    fun segmentLength(p1: AspectCorrectPoint, p2: AspectCorrectPoint): Float = distance(p1, p2)

    /**
     * Signed angle from [v1] to [v2] in degrees in [-180, 180].
     * With screen Y pointing downward:
     * Positive angle corresponds to clockwise rotation in the image plane.
     * Negative angle corresponds to counter-clockwise rotation.
     */
    fun signedAngleDeg(v1: AspectCorrectPoint, v2: AspectCorrectPoint): Float {
        val len1 = v1.length()
        val len2 = v2.length()
        require(len1 > EPSILON && len2 > EPSILON) { "Vectors must be non-zero to compute angle" }

        val dot = v1.x * v2.x + v1.y * v2.y
        val det = v1.x * v2.y - v1.y * v2.x
        val angleRad = atan2(det.toDouble(), dot.toDouble())
        return Math.toDegrees(angleRad).toFloat()
    }

    /**
     * Absolute unsigned angle between [v1] and [v2] in degrees in [0, 180].
     */
    fun absoluteAngleDeg(v1: AspectCorrectPoint, v2: AspectCorrectPoint): Float {
        val len1 = v1.length()
        val len2 = v2.length()
        require(len1 > EPSILON && len2 > EPSILON) { "Vectors must be non-zero to compute angle" }

        val cosVal = ((v1.x * v2.x + v1.y * v2.y) / (len1 * len2)).coerceIn(-1f, 1f)
        return Math.toDegrees(acos(cosVal.toDouble())).toFloat()
    }

    /**
     * Angle of vector [v] relative to positive X-axis (+X = 0 deg) in [-180, 180].
     * Because Y is downward:
     * +90 deg is straight down (+Y)
     * -90 deg is straight up (-Y)
     * 180 / -180 deg is straight left (-X)
     */
    fun rayAngleDeg(v: AspectCorrectPoint): Float {
        require(v.length() > EPSILON) { "Vector must be non-zero" }
        return Math.toDegrees(atan2(v.y.toDouble(), v.x.toDouble())).toFloat()
    }

    /** Normalizes an angle to [-180, 180], preserving the shortest circular representation. */
    fun wrapAngleDeg(angleDeg: Double): Double {
        require(angleDeg.isFinite()) { "Angle must be finite, was $angleDeg" }
        var wrapped = angleDeg % 360.0
        if (wrapped > 180.0) wrapped -= 360.0
        if (wrapped < -180.0) wrapped += 360.0
        return wrapped
    }

    /** Signed shortest difference from [fromDeg] to [toDeg]. */
    fun wrappedAngleDifferenceDeg(toDeg: Double, fromDeg: Double): Double =
        wrapAngleDeg(toDeg - fromDeg)

    /**
     * Scalar projection of [point] onto the axis defined by [axisOrigin] and unit direction [axisDirectionUnit].
     */
    fun projectPointOntoAxis(
        point: AspectCorrectPoint,
        axisOrigin: AspectCorrectPoint,
        axisDirectionUnit: AspectCorrectPoint,
    ): Float {
        val dx = point.x - axisOrigin.x
        val dy = point.y - axisOrigin.y
        return dx * axisDirectionUnit.x + dy * axisDirectionUnit.y
    }

    /**
     * Projected point of [point] onto the axis defined by [axisOrigin] and unit direction [axisDirectionUnit].
     */
    fun projectPointOntoAxisPoint(
        point: AspectCorrectPoint,
        axisOrigin: AspectCorrectPoint,
        axisDirectionUnit: AspectCorrectPoint,
    ): AspectCorrectPoint {
        val t = projectPointOntoAxis(point, axisOrigin, axisDirectionUnit)
        return AspectCorrectPoint(
            x = axisOrigin.x + axisDirectionUnit.x * t,
            y = axisOrigin.y + axisDirectionUnit.y * t,
        )
    }

    /**
     * Intersections between a circle at [center] with [radius] and a horizontal line at [horizontalLineY].
     * Evaluated in aspect-correct space.
     * Returns:
     * - 0 elements if line does not intersect circle (|horizontalLineY - center.y| > radius)
     * - 1 element if line is tangent to circle
     * - 2 elements if line cuts through circle (sorted left to right by x)
     */
    fun circleHorizontalLineIntersections(
        center: AspectCorrectPoint,
        radius: Float,
        horizontalLineY: Float,
    ): List<AspectCorrectPoint> {
        require(radius >= 0f) { "Radius must be non-negative, was $radius" }
        val dy = abs(horizontalLineY - center.y)

        if (dy > radius + EPSILON) {
            return emptyList()
        }

        if (abs(dy - radius) <= EPSILON) {
            return listOf(AspectCorrectPoint(center.x, horizontalLineY))
        }

        val dx = sqrt((radius * radius - dy * dy).coerceAtLeast(0f))
        return listOf(
            AspectCorrectPoint(center.x - dx, horizontalLineY),
            AspectCorrectPoint(center.x + dx, horizontalLineY),
        )
    }

    /**
     * Converts an aspect-correct Euclidean radius [radius] into normalized source frame
     * semi-axes (radiusX, radiusY).
     * Since xAnalysis = xSource * aspect, radiusX = radius / aspect.
     */
    fun aspectCorrectRadiusToSourceEllipse(
        radius: Float,
        frameGeometry: FrameGeometry,
    ): Pair<Float, Float> {
        return Pair(radius / frameGeometry.aspectRatio, radius)
    }
}
