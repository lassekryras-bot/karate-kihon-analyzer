package dk.lasse.karateanalyzer.core

import kotlin.math.acos
import kotlin.math.isFinite
import kotlin.math.sqrt

private const val EPSILON = 1e-6f

/** Returns true when all coordinates are finite analyzer values. */
fun Point3.isFinitePoint(): Boolean = x.isFinite() && y.isFinite() && z.isFinite()

/** Dot product for two [Point3] values treated as vectors. Returns null for non-finite input. */
fun Point3.dot(other: Point3): Float? = if (isFinitePoint() && other.isFinitePoint()) x * other.x + y * other.y + z * other.z else null

/** Cross product for two [Point3] values treated as vectors. Returns null for non-finite input. */
fun Point3.cross(other: Point3): Point3? = if (isFinitePoint() && other.isFinitePoint()) {
    Point3(y * other.z - z * other.y, z * other.x - x * other.z, x * other.y - y * other.x)
} else null

/** Vector magnitude. Returns null for non-finite or degenerate values. */
fun Point3.magnitude(): Float? {
    if (!isFinitePoint()) return null
    val squared = x * x + y * y + z * z
    if (!squared.isFinite() || squared <= EPSILON * EPSILON) return null
    return sqrt(squared)
}

/** Unit vector in the same direction, or null when the vector is degenerate. */
fun Point3.normalized(): Point3? {
    val mag = magnitude() ?: return null
    return Point3(x / mag, y / mag, z / mag).takeIf { it.isFinitePoint() }
}

/** Distance between two finite points, or null when unavailable/degenerate. */
fun distanceBetween(a: Point3?, b: Point3?): Float? = if (a != null && b != null) (a - b).magnitude() else null

/** Safe clamp that rejects NaN/infinity and inverted ranges. */
fun safeClamp(value: Float?, min: Float = 0f, max: Float = 1f): Float? {
    if (value == null || !value.isFinite() || !min.isFinite() || !max.isFinite() || min > max) return null
    return value.coerceIn(min, max)
}

/** Safe division that returns null instead of NaN/infinity or division by zero. */
fun safeDivide(numerator: Float?, denominator: Float?): Float? {
    if (numerator == null || denominator == null || !numerator.isFinite() || !denominator.isFinite()) return null
    if (kotlin.math.abs(denominator) <= EPSILON) return null
    val result = numerator / denominator
    return result.takeIf { it.isFinite() }
}

/** Average of finite points. Returns null when no valid points are provided. */
fun averageOfPoints(points: Iterable<Point3?>): Point3? {
    var count = 0
    var sx = 0f; var sy = 0f; var sz = 0f
    for (p in points) {
        if (p != null && p.isFinitePoint()) { count++; sx += p.x; sy += p.y; sz += p.z }
    }
    return if (count == 0) null else Point3(sx / count, sy / count, sz / count).takeIf { it.isFinitePoint() }
}

/** Midpoint of two finite points. */
fun midpoint(a: Point3?, b: Point3?): Point3? =
    if (a != null && b != null && a.isFinitePoint() && b.isFinitePoint()) averageOfPoints(listOf(a, b)) else null

/**
 * Angle at [vertex] formed by [first]-[vertex]-[third], in degrees.
 * Returns null when any point is missing or either ray is degenerate.
 */
fun angleBetweenThreePoints(first: Point3?, vertex: Point3?, third: Point3?): Float? {
    if (first == null || vertex == null || third == null) return null
    val a = first - vertex
    val b = third - vertex
    val denominator = (a.magnitude() ?: return null) * (b.magnitude() ?: return null)
    val cos = safeClamp(safeDivide(a.dot(b), denominator), -1f, 1f) ?: return null
    val degrees = (acos(cos.toDouble()) * 180.0 / Math.PI).toFloat()
    return degrees.takeIf { it.isFinite() }
}
