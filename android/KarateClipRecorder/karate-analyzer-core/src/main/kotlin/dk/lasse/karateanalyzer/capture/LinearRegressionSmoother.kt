package dk.lasse.karateanalyzer.capture

import kotlin.math.abs
import kotlin.math.sqrt

/**
 * Timestamped 2D coordinate point for causal linear regression smoothing.
 * Coordinates are fixed-camera 2D coordinates (optionally scaled by reference scale L_ref).
 */
data class TimestampedPoint2D(
    val timestampSec: Double,
    val x: Double,
    val y: Double,
    val confidence: Double,
)

/**
 * Timestamped scalar angle for causal linear regression smoothing.
 */
data class TimestampedAngle(
    val timestampSec: Double,
    val angleDeg: Double,
    val confidence: Double,
    val isUndirectedAxis: Boolean = false,
)

/**
 * Result of short-window signed linear regression fitting for 2D translation.
 * Preserves signed vx, vy alongside speed magnitude v_trans.
 */
data class Velocity2D(
    val vx: Double,
    val vy: Double,
    val speed: Double,
    val sampleCount: Int,
    val spanMs: Long,
    val quality: Double,
)

/**
 * Result of short-window angular regression fitting after circular unwrapping.
 */
data class AngularVelocity(
    val signedRateDegPerSec: Double,
    val speedDegPerSec: Double,
    val sampleCount: Int,
    val spanMs: Long,
    val quality: Double,
)

/**
 * Causal least-squares linear regression smoother for biomechanical signals.
 * Strictly backward-looking over trailing window [t0 - windowDurationSec, t0].
 *
 * Implements Mathematical Contract Sections 4, 5, 6, 9, 10.
 */
class LinearRegressionSmoother(
    val windowDurationMs: Long = 100L,
    val minimumSamples: Int = 3,
    val minimumWindowSpanRatio: Double = 0.60,
    val minimumLandmarkConfidence: Double = 0.50,
) {
    private val windowDurationSec = windowDurationMs / 1000.0
    private val minSpanSec = windowDurationSec * minimumWindowSpanRatio
    private val epsilon = 1e-9

    /**
     * Estimates signed velocity (vx, vy) and speed magnitude sqrt(vx^2 + vy^2)
     * using ordinary least-squares linear regression on signed coordinates:
     *
     *   vx = sum(tau_i * x_i) / sum(tau_i^2)
     *   vy = sum(tau_i * y_i) / sum(tau_i^2)
     *   speed = sqrt(vx^2 + vy^2)
     *
     * Signed jitter cancels inside the fit before taking the Euclidean norm.
     */
    fun fitVelocity2D(
        samples: List<TimestampedPoint2D>,
        targetTimeSec: Double,
    ): Velocity2D? {
        val minTime = targetTimeSec - windowDurationSec - 1e-6
        val window = samples.filter { it.timestampSec in minTime..targetTimeSec }

        if (window.isEmpty() || abs(window.last().timestampSec - targetTimeSec) > 1e-4) return null
        if (window.size < minimumSamples) return null
        val spanSec = window.last().timestampSec - window.first().timestampSec
        if (spanSec < minSpanSec) return null

        var minConf = Double.MAX_VALUE
        for (s in window) {
            if (!s.x.isFinite() || !s.y.isFinite() || !s.confidence.isFinite() || s.confidence < minimumLandmarkConfidence) {
                return null
            }
            if (s.confidence < minConf) minConf = s.confidence
        }

        val n = window.size
        val meanT = window.sumOf { it.timestampSec } / n

        var sumTauSq = 0.0
        var sumTauX = 0.0
        var sumTauY = 0.0

        for (s in window) {
            val tau = s.timestampSec - meanT
            sumTauSq += tau * tau
            sumTauX += tau * s.x
            sumTauY += tau * s.y
        }

        if (sumTauSq <= epsilon) return null

        val vx = sumTauX / sumTauSq
        val vy = sumTauY / sumTauSq
        val speed = sqrt(vx * vx + vy * vy)

        return Velocity2D(
            vx = vx,
            vy = vy,
            speed = speed,
            sampleCount = n,
            spanMs = (spanSec * 1000.0).toLong(),
            quality = minConf,
        )
    }

    /**
     * Unwraps scalar circular angles across short window and computes angular velocity
     * via ordinary least-squares linear regression on the locally continuous sequence:
     *
     *   omega = sum(tau_i * theta'_i) / sum(tau_i^2)
     *
     * For directed 360 deg angles: dtheta' = ((dtheta + 180) % 360) - 180
     * For undirected 180 deg axes: dtheta' = ((dtheta + 90) % 180) - 90
     */
    fun fitAngularRate(
        samples: List<TimestampedAngle>,
        targetTimeSec: Double,
    ): AngularVelocity? {
        val minTime = targetTimeSec - windowDurationSec - 1e-6
        val window = samples.filter { it.timestampSec in minTime..targetTimeSec }

        if (window.isEmpty() || abs(window.last().timestampSec - targetTimeSec) > 1e-4) return null
        if (window.size < minimumSamples) return null
        val spanSec = window.last().timestampSec - window.first().timestampSec
        if (spanSec < minSpanSec) return null

        var minConf = Double.MAX_VALUE
        for (s in window) {
            if (!s.angleDeg.isFinite() || !s.confidence.isFinite() || s.confidence < minimumLandmarkConfidence) {
                return null
            }
            if (s.confidence < minConf) minConf = s.confidence
        }

        // Circular unwrapping
        val isUndirected = window.first().isUndirectedAxis
        val unwrapped = DoubleArray(window.size)
        unwrapped[0] = window[0].angleDeg

        for (i in 1 until window.size) {
            val rawDiff = window[i].angleDeg - window[i - 1].angleDeg
            val diff = if (isUndirected) {
                unwrapDifference180(rawDiff)
            } else {
                unwrapDifference360(rawDiff)
            }
            unwrapped[i] = unwrapped[i - 1] + diff
        }

        val n = window.size
        val meanT = window.sumOf { it.timestampSec } / n

        var sumTauSq = 0.0
        var sumTauTheta = 0.0

        for (i in 0 until n) {
            val tau = window[i].timestampSec - meanT
            sumTauSq += tau * tau
            sumTauTheta += tau * unwrapped[i]
        }

        if (sumTauSq <= epsilon) return null

        val signedRate = sumTauTheta / sumTauSq
        val speed = abs(signedRate)

        return AngularVelocity(
            signedRateDegPerSec = signedRate,
            speedDegPerSec = speed,
            sampleCount = n,
            spanMs = (spanSec * 1000.0).toLong(),
            quality = minConf,
        )
    }

    companion object {
        fun unwrapDifference360(diffDeg: Double): Double {
            var d = (diffDeg + 180.0) % 360.0
            if (d < 0.0) d += 360.0
            return d - 180.0
        }

        fun unwrapDifference180(diffDeg: Double): Double {
            var d = (diffDeg + 90.0) % 180.0
            if (d < 0.0) d += 180.0
            return d - 90.0
        }
    }
}
