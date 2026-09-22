package dk.lasse.karateanalyzer.geometry

import kotlin.math.acos
import kotlin.math.atan2
import kotlin.math.sqrt

/**
 * Directed infinite line in upward metric space, defined by an ordered pair of points.
 */
data class DirectedLandmarkLine(
    val start: UpwardMetricPoint,
    val end: UpwardMetricPoint,
) {
    val vector: UpwardMetricPoint get() = end - start
    val length: Float get() = vector.length()
    val isDegenerate: Boolean get() = length <= 1e-6f || !length.isFinite()
}

/**
 * Finite segment in upward metric space between two ordered endpoints.
 */
data class LandmarkSegment(
    val start: UpwardMetricPoint,
    val end: UpwardMetricPoint,
) {
    val vector: UpwardMetricPoint get() = end - start
    val length: Float get() = vector.length()
    val isDegenerate: Boolean get() = length <= 1e-6f || !length.isFinite()
}

/**
 * Pure instantaneous geometric relations between points, lines, and segments.
 */
object LandmarkRelations {

    private const val EPSILON = 1e-6f

    fun distance(p1: UpwardMetricPoint, p2: UpwardMetricPoint): Float {
        val dx = p2.x - p1.x
        val dy = p2.y - p1.y
        return sqrt(dx * dx + dy * dy)
    }

    /**
     * Signed perpendicular distance from [point] to infinite directed [line].
     * Uses 2D cross product: cross(line.vector, point - line.start) / line.length.
     * Returns positive when point is to the left of the directed line in upward metric space.
     * Returns null if [line] is degenerate.
     */
    fun signedDistanceToLine(
        point: UpwardMetricPoint,
        line: DirectedLandmarkLine,
    ): Float? {
        if (line.isDegenerate) return null
        val v = line.vector
        val d = point - line.start
        val cross = v.x * d.y - v.y * d.x
        return cross / line.length
    }

    /**
     * Shortest Euclidean distance from [point] to finite [segment].
     */
    fun distanceToSegment(
        point: UpwardMetricPoint,
        segment: LandmarkSegment,
    ): Float {
        if (segment.isDegenerate) {
            return distance(point, segment.start)
        }
        val v = segment.vector
        val d = point - segment.start
        val lenSq = v.x * v.x + v.y * v.y
        val t = ((d.x * v.x + d.y * v.y) / lenSq).coerceIn(0f, 1f)
        val projection = segment.start + (v * t)
        return distance(point, projection)
    }

    /**
     * Absolute unsigned angle between [line1] and [line2] in [0, 180] degrees.
     * Returns null if either line is degenerate.
     */
    fun unsignedAngleBetweenLines(
        line1: DirectedLandmarkLine,
        line2: DirectedLandmarkLine,
    ): Float? {
        if (line1.isDegenerate || line2.isDegenerate) return null
        val v1 = line1.vector
        val v2 = line2.vector
        val dot = v1.dot(v2)
        val cosVal = (dot / (line1.length * line2.length)).coerceIn(-1f, 1f)
        return Math.toDegrees(acos(cosVal.toDouble())).toFloat()
    }

    /**
     * Signed angle from [line1] to [line2] in [-180, 180) degrees.
     * Counterclockwise positive in upward metric convention.
     * Opposite vectors normalize to -180 degrees.
     * Returns null if either line is degenerate.
     */
    fun signedAngleBetweenLines(
        line1: DirectedLandmarkLine,
        line2: DirectedLandmarkLine,
    ): Float? {
        if (line1.isDegenerate || line2.isDegenerate) return null
        val v1 = line1.vector
        val v2 = line2.vector
        val dot = v1.x * v2.x + v1.y * v2.y
        val cross = v1.x * v2.y - v1.y * v2.x
        val angleRad = atan2(cross.toDouble(), dot.toDouble())
        var angleDeg = Math.toDegrees(angleRad).toFloat()
        // Wrap [180] to -180 to satisfy [-180, 180) specification contract
        if (angleDeg >= 180f - 1e-4f) {
            angleDeg = -180f
        }
        return angleDeg
    }

    /**
     * Joint angle at [vertex] between directed vectors (vertex -> a) and (vertex -> c),
     * evaluated in [0, 180] degrees.
     */
    fun jointAngle(
        a: UpwardMetricPoint,
        vertex: UpwardMetricPoint,
        c: UpwardMetricPoint,
    ): Float? {
        val line1 = DirectedLandmarkLine(vertex, a)
        val line2 = DirectedLandmarkLine(vertex, c)
        return unsignedAngleBetweenLines(line1, line2)
    }
}

