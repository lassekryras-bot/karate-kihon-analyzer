package dk.lasse.karateanalyzer.capture

import dk.lasse.karateanalyzer.core.Point3
import dk.lasse.karateanalyzer.core.PoseLandmarkId
import dk.lasse.karateanalyzer.core.angleBetweenThreePoints
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.sin

/**
 * Reusable causal time-based smoothing component for biomechanical kinematic signals (Task 5H).
 * Strictly backward-looking: only samples at or before the current observation timestamp influence calculations.
 * Preserves historical samples across seamless rearm to prevent warm-up dead zones.
 */
internal class CausalKinematicSmoother(
    private val config: KinematicsConfig = KinematicsConfig(),
) {
    private val history = mutableListOf<RelativePose>()

    fun accept(pose: RelativePose) {
        require(history.lastOrNull()?.let { pose.timestampMs > it.timestampMs } != false)
        history.add(pose)
        val cutoff = pose.timestampMs - maxOf(400L, config.windowDurationMs)
        while (history.isNotEmpty() && history.first().timestampMs < cutoff) {
            history.removeAt(0)
        }
    }

    /**
     * Preserves confirmed stable window samples on seamless rearm so that filtered kinematics
     * are immediately available for the next movement without warm-up dead time.
     */
    fun preserveConfirmedWindow(stableStartTimestampMs: Long, stableEndTimestampMs: Long) {
        val preserved = history.filter { it.timestampMs in stableStartTimestampMs..stableEndTimestampMs }
        history.clear()
        if (preserved.isNotEmpty()) {
            history.addAll(preserved)
        }
    }

    fun reset() {
        history.clear()
    }

    /**
     * Gathers backward window samples for timestamp [currentTimestampMs] and verifies usability criteria:
     * - Sample count >= minimumSamples (3)
     * - Time span >= windowDurationMs * minimumWindowSpanRatio (60 ms for 100 ms window)
     * - All specified [requiredLandmarks] have confidence >= minimumLandmarkConfidence (0.50)
     */
    fun getUsableWindow(
        currentTimestampMs: Long,
        requiredLandmarks: List<PoseLandmarkId>,
    ): List<RelativePose>? {
        val windowStart = currentTimestampMs - config.windowDurationMs
        val window = history.filter { it.timestampMs in windowStart..currentTimestampMs }
        if (window.size < config.minimumSamples) return null
        if (window.last().timestampMs != currentTimestampMs) return null

        val spanMs = window.last().timestampMs - window.first().timestampMs
        val requiredSpanMs = (config.windowDurationMs * config.minimumWindowSpanRatio).toLong()
        if (spanMs < requiredSpanMs) return null

        for (pose in window) {
            for (lm in requiredLandmarks) {
                val conf = pose.confidences[lm] ?: 0.0
                if (!conf.isFinite() || conf < config.minimumLandmarkConfidence) return null
                val point = pose.points[lm] ?: return null
                if (!point.x.isFinite() || !point.y.isFinite() || !point.z.isFinite()) return null
            }
        }
        return window
    }

    private fun effectiveSpanSeconds(window: List<RelativePose>): Double? {
        val startT = if (window.size >= 2) (window[0].timestampMs + window[1].timestampMs) * 0.5 else window.first().timestampMs.toDouble()
        val endT = if (window.size >= 2) (window[window.size - 2].timestampMs + window.last().timestampMs) * 0.5 else window.last().timestampMs.toDouble()
        val spanSec = (endT - startT) / 1000.0
        return if (spanSec > 0.0) spanSec else null
    }

    /**
     * Computes causal smoothed scalar joint angular velocity and directional coherence.
     */
    fun smoothScalarAngleRate(
        window: List<RelativePose>,
        jointA: PoseLandmarkId,
        jointB: PoseLandmarkId,
        jointC: PoseLandmarkId,
    ): Pair<Double, Double>? {
        val angles = window.mapNotNull { pose ->
            val a = pose.points[jointA] ?: return@mapNotNull null
            val b = pose.points[jointB] ?: return@mapNotNull null
            val c = pose.points[jointC] ?: return@mapNotNull null
            angleBetweenThreePoints(a, b, c)?.toDouble()
        }
        if (angles.size != window.size) return null

        val spanSec = effectiveSpanSeconds(window) ?: return null

        val startAngle = if (angles.size >= 2) (angles[0] + angles[1]) * 0.5 else angles.first()
        val endAngle = if (angles.size >= 2) (angles[angles.size - 2] + angles.last()) * 0.5 else angles.last()
        val smoothedRate = minOf(abs(endAngle - startAngle) / spanSec, config.maximumAngularVelocity)

        // Directional coherence (|net change| / sum of step changes)
        val netChange = abs(angles.last() - angles.first())
        var stepSum = 0.0
        for (i in 1 until angles.size) {
            stepSum += abs(angles[i] - angles[i - 1])
        }
        val coherence = if (stepSum > 1.0) minOf(1.0, netChange / stepSum) else 0.0

        return Pair(smoothedRate, coherence)
    }

    /**
     * Computes causal smoothed spherical geodesic orientation change rate and coherence for a 3D unit vector.
     */
    fun smoothOrientationRate(
        window: List<RelativePose>,
        originId: PoseLandmarkId,
        targetId: PoseLandmarkId,
    ): Pair<Double, Double>? {
        val vectors = window.mapNotNull { pose ->
            val p1 = pose.points[originId] ?: return@mapNotNull null
            val p2 = pose.points[targetId] ?: return@mapNotNull null
            KinematicsMath.unit(p2 - p1)
        }
        if (vectors.size != window.size) return null

        val spanSec = effectiveSpanSeconds(window) ?: return null

        val startVec = if (vectors.size >= 2) {
            KinematicsMath.unit(vectors[0] + vectors[1]) ?: vectors.first()
        } else vectors.first()

        val endVec = if (vectors.size >= 2) {
            KinematicsMath.unit(vectors[vectors.size - 2] + vectors.last()) ?: vectors.last()
        } else vectors.last()

        val netAngle = KinematicsMath.angleBetweenVectorsDeg(startVec, endVec) ?: 0.0
        val netTravel = KinematicsMath.angleBetweenVectorsDeg(vectors.first(), vectors.last()) ?: 0.0
        val smoothedRate = minOf(netAngle / spanSec, config.maximumAngularVelocity)

        var stepSum = 0.0
        for (i in 1 until vectors.size) {
            val stepAngle = KinematicsMath.angleBetweenVectorsDeg(vectors[i - 1], vectors[i]) ?: 0.0
            stepSum += stepAngle
        }
        val coherence = if (stepSum > 1.0) minOf(1.0, netTravel / stepSum) else 0.0

        return Pair(smoothedRate, coherence)
    }

    /**
     * Computes causal smoothed transverse pelvis yaw rotation rate and coherence with circular angle wrapping.
     */
    fun smoothPelvisYawRate(window: List<RelativePose>): Pair<Double, Double>? {
        val yaws = window.mapNotNull { pose ->
            val lh = pose.points[PoseLandmarkId.LEFT_HIP] ?: return@mapNotNull null
            val rh = pose.points[PoseLandmarkId.RIGHT_HIP] ?: return@mapNotNull null
            val hx = (lh.x - rh.x).toDouble()
            val hz = (lh.z - rh.z).toDouble()
            if (hx * hx + hz * hz < 1e-12) return@mapNotNull null
            val yaw = atan2(hz, hx) * (180.0 / Math.PI)
            if (yaw.isFinite()) yaw else null
        }
        if (yaws.size != window.size) return null

        val spanSec = effectiveSpanSeconds(window) ?: return null

        val startYaw = if (yaws.size >= 2) {
            val sinMean = (sin(Math.toRadians(yaws[0])) + sin(Math.toRadians(yaws[1]))) * 0.5
            val cosMean = (cos(Math.toRadians(yaws[0])) + cos(Math.toRadians(yaws[1]))) * 0.5
            Math.toDegrees(atan2(sinMean, cosMean))
        } else yaws.first()

        val endYaw = if (yaws.size >= 2) {
            val sinMean = (sin(Math.toRadians(yaws[yaws.size - 2])) + sin(Math.toRadians(yaws.last()))) * 0.5
            val cosMean = (cos(Math.toRadians(yaws[yaws.size - 2])) + cos(Math.toRadians(yaws.last()))) * 0.5
            Math.toDegrees(atan2(sinMean, cosMean))
        } else yaws.last()

        val netYaw = abs(KinematicsMath.circularDiffDeg(endYaw, startYaw))
        val smoothedRate = minOf(netYaw / spanSec, config.maximumAngularVelocity)

        val netYawCoherence = abs(KinematicsMath.circularDiffDeg(yaws.last(), yaws.first()))
        var stepSum = 0.0
        for (i in 1 until yaws.size) {
            stepSum += abs(KinematicsMath.circularDiffDeg(yaws[i], yaws[i - 1]))
        }
        val coherence = if (stepSum > 1.0) minOf(1.0, netYawCoherence / stepSum) else 0.0

        return Pair(smoothedRate, coherence)
    }

    /**
     * Computes causal smoothed torso-relative linear speed, signed radial speed, and translational coherence.
     */
    fun smoothTorsoRelativeSpeed(
        window: List<RelativePose>,
        landmarkId: PoseLandmarkId,
        maxSpeed: Double = 8.0,
    ): Triple<Double, Double, Double>? {
        val positions = window.mapNotNull { pose -> pose.points[landmarkId] }
        if (positions.size != window.size) return null

        val spanSec = effectiveSpanSeconds(window) ?: return null

        val startPos = if (positions.size >= 2) (positions[0] + positions[1]) * 0.5f else positions.first()
        val endPos = if (positions.size >= 2) (positions[positions.size - 2] + positions.last()) * 0.5f else positions.last()

        val netDist = KinematicsMath.distance(startPos, endPos)
        val linearSpeed = minOf(netDist / spanSec, maxSpeed)

        // Radial distance from torso frame origin (0, 0, 0)
        val origin = Point3(0f, 0f, 0f)
        val rStart = KinematicsMath.distance(startPos, origin)
        val rEnd = KinematicsMath.distance(endPos, origin)
        val radialSpeed = (rEnd - rStart) / spanSec

        val totalTravel = KinematicsMath.distance(positions.first(), positions.last())
        var stepSum = 0.0
        for (i in 1 until positions.size) {
            stepSum += KinematicsMath.distance(positions[i - 1], positions[i])
        }
        val coherence = if (stepSum > 0.02) minOf(1.0, totalTravel / stepSum) else 0.0

        return Triple(linearSpeed, radialSpeed, coherence)
    }
}
