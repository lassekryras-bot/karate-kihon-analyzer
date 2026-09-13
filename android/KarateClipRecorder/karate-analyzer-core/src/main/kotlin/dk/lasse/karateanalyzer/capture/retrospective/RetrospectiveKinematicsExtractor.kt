package dk.lasse.karateanalyzer.capture.retrospective

import dk.lasse.karateanalyzer.core.Point3
import dk.lasse.karateanalyzer.core.PoseFrame
import dk.lasse.karateanalyzer.core.PoseLandmarkId
import kotlin.math.abs
import kotlin.math.acos
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sqrt

/**
 * Centered kinematic frame containing both raw physical kinematics and session-normalized evidence.
 */
data class RetrospectiveKinematicFrame(
    val timestampMs: Long,
    val frameIndex: Int,
    val rawTranslationEvidence: Double?,
    val rawAngularEvidence: Double?,
    val normalizedTranslationEvidence: Double?,
    val normalizedAngularEvidence: Double?,
    val combinedMovementEvidence: Double?,
    val isMoving: Boolean,
    val isQuiet: Boolean,
)

/**
 * Signal summary statistics for the analyzed session.
 */
data class RetrospectiveSignalStats(
    val translationQ20: Double,
    val translationQ90: Double,
    val angularQ20: Double,
    val angularQ90: Double,
    val referenceScale: Double,
)

/**
 * Authoritative retrospective kinematic feature extractor.
 *
 * Uses centered temporal regression windows (default [-50ms, +50ms]) over the complete
 * landmark timeline to eliminate causal clearing lag at movement completion.
 * Computes Top-2 translation speed and Top-2 joint angular rate, followed by session-relative
 * q20/q90 normalization.
 */
class RetrospectiveKinematicsExtractor(
    val config: RetrospectiveSegmenterConfig = RetrospectiveSegmenterConfig(),
) {
    fun extract(frames: List<PoseFrame>): Pair<List<RetrospectiveKinematicFrame>, RetrospectiveSignalStats> {
        val n = frames.size
        if (n == 0) {
            return emptyList<RetrospectiveKinematicFrame>() to RetrospectiveSignalStats(0.0, 0.0, 0.0, 0.0, 0.1580)
        }

        val timestamps = frames.map { it.timestampMs }
        val timesSec = timestamps.map { it / 1000.0 }

        // 1. Compute reference scale L_ref: median upper-arm length
        val armLengths = mutableListOf<Double>()
        for (frame in frames) {
            for (side in listOf("LEFT", "RIGHT")) {
                val shId = if (side == "LEFT") PoseLandmarkId.LEFT_SHOULDER else PoseLandmarkId.RIGHT_SHOULDER
                val elId = if (side == "LEFT") PoseLandmarkId.LEFT_ELBOW else PoseLandmarkId.RIGHT_ELBOW
                val sh = frame.landmarks[shId]?.position
                val el = frame.landmarks[elId]?.position
                if (sh != null && el != null && sh.x.isFinite() && sh.y.isFinite() && el.x.isFinite() && el.y.isFinite()) {
                    val dx = (sh.x - el.x).toDouble()
                    val dy = (sh.y - el.y).toDouble()
                    val d = sqrt(dx * dx + dy * dy)
                    if (d > 0.05) armLengths.add(d)
                }
            }
        }
        val lRef = if (armLengths.isNotEmpty()) {
            armLengths.sorted()[armLengths.size / 2]
        } else 0.1580

        // 2. Extract landmark 2D coordinates
        fun getCoord(frame: PoseFrame, id: PoseLandmarkId): Pair<Double, Double>? {
            val p = frame.landmarks[id]?.position ?: return null
            return if (p.x.isFinite() && p.y.isFinite()) Pair(p.x.toDouble(), p.y.toDouble()) else null
        }

        val wristL = frames.map { getCoord(it, PoseLandmarkId.LEFT_WRIST) }
        val wristR = frames.map { getCoord(it, PoseLandmarkId.RIGHT_WRIST) }
        val ankleL = frames.map { getCoord(it, PoseLandmarkId.LEFT_ANKLE) }
        val ankleR = frames.map { getCoord(it, PoseLandmarkId.RIGHT_ANKLE) }
        val kneeL = frames.map { getCoord(it, PoseLandmarkId.LEFT_KNEE) }
        val kneeR = frames.map { getCoord(it, PoseLandmarkId.RIGHT_KNEE) }

        // 3. Compute joint angles (in degrees)
        fun computeAngle(p1: Pair<Double, Double>?, p2: Pair<Double, Double>?, p3: Pair<Double, Double>?): Double? {
            if (p1 == null || p2 == null || p3 == null) return null
            val v1x = p1.first - p2.first
            val v1y = p1.second - p2.second
            val v2x = p3.first - p2.first
            val v2y = p3.second - p2.second
            val n1 = sqrt(v1x * v1x + v1y * v1y)
            val n2 = sqrt(v2x * v2x + v2y * v2y)
            if (n1 < 1e-6 || n2 < 1e-6) return null
            val cosA = ((v1x * v2x + v1y * v2y) / (n1 * n2)).coerceIn(-1.0, 1.0)
            return Math.toDegrees(acos(cosA))
        }

        val elbowAngleL = frames.indices.map { i ->
            computeAngle(
                getCoord(frames[i], PoseLandmarkId.LEFT_SHOULDER),
                getCoord(frames[i], PoseLandmarkId.LEFT_ELBOW),
                getCoord(frames[i], PoseLandmarkId.LEFT_WRIST),
            )
        }
        val elbowAngleR = frames.indices.map { i ->
            computeAngle(
                getCoord(frames[i], PoseLandmarkId.RIGHT_SHOULDER),
                getCoord(frames[i], PoseLandmarkId.RIGHT_ELBOW),
                getCoord(frames[i], PoseLandmarkId.RIGHT_WRIST),
            )
        }
        val kneeAngleL = frames.indices.map { i ->
            computeAngle(
                getCoord(frames[i], PoseLandmarkId.LEFT_HIP),
                getCoord(frames[i], PoseLandmarkId.LEFT_KNEE),
                getCoord(frames[i], PoseLandmarkId.LEFT_ANKLE),
            )
        }
        val kneeAngleR = frames.indices.map { i ->
            computeAngle(
                getCoord(frames[i], PoseLandmarkId.RIGHT_HIP),
                getCoord(frames[i], PoseLandmarkId.RIGHT_KNEE),
                getCoord(frames[i], PoseLandmarkId.RIGHT_ANKLE),
            )
        }

        // 4. Centered linear regression derivatives
        val halfMs = config.halfWindowDurationMs
        fun centeredSpeed2D(series: List<Pair<Double, Double>?>): List<Double?> {
            val res = ArrayList<Double?>(n)
            for (i in 0 until n) {
                val tCurr = timestamps[i]
                val tMin = tCurr - halfMs
                val tMax = tCurr + halfMs

                var sumTauSq = 0.0
                var sumTauX = 0.0
                var sumTauY = 0.0
                var count = 0
                var firstT = 0L
                var lastT = 0L

                // Collect points in window
                val windowIdxs = mutableListOf<Int>()
                for (j in max(0, i - 15)..min(n - 1, i + 15)) {
                    val tj = timestamps[j]
                    if (tj in tMin..tMax && series[j] != null) {
                        windowIdxs.add(j)
                    }
                }

                if (windowIdxs.size >= config.minimumSamplesInWindow &&
                    (timestamps[windowIdxs.last()] - timestamps[windowIdxs.first()]) >= config.minimumWindowSpanMs
                ) {
                    val meanT = windowIdxs.map { timesSec[it] }.average()
                    for (idx in windowIdxs) {
                        val tau = timesSec[idx] - meanT
                        val pt = series[idx]!!
                        sumTauSq += tau * tau
                        sumTauX += tau * pt.first
                        sumTauY += tau * pt.second
                    }
                    if (sumTauSq > 1e-9) {
                        val vx = sumTauX / sumTauSq
                        val vy = sumTauY / sumTauSq
                        val speedNorm = sqrt(vx * vx + vy * vy) / lRef
                        res.add(speedNorm)
                    } else {
                        res.add(null)
                    }
                } else {
                    res.add(null)
                }
            }
            return res
        }

        fun centeredAngularSpeed(series: List<Double?>): List<Double?> {
            val res = ArrayList<Double?>(n)
            for (i in 0 until n) {
                val tCurr = timestamps[i]
                val tMin = tCurr - halfMs
                val tMax = tCurr + halfMs

                val windowIdxs = mutableListOf<Int>()
                for (j in max(0, i - 15)..min(n - 1, i + 15)) {
                    val tj = timestamps[j]
                    if (tj in tMin..tMax && series[j] != null) {
                        windowIdxs.add(j)
                    }
                }

                if (windowIdxs.size >= config.minimumSamplesInWindow &&
                    (timestamps[windowIdxs.last()] - timestamps[windowIdxs.first()]) >= config.minimumWindowSpanMs
                ) {
                    val meanT = windowIdxs.map { timesSec[it] }.average()
                    val meanAngle = windowIdxs.map { series[it]!! }.average()
                    var sumTauSq = 0.0
                    var sumTauAngle = 0.0
                    for (idx in windowIdxs) {
                        val tau = timesSec[idx] - meanT
                        val ang = series[idx]!! - meanAngle
                        sumTauSq += tau * tau
                        sumTauAngle += tau * ang
                    }
                    if (sumTauSq > 1e-9) {
                        res.add(abs(sumTauAngle / sumTauSq))
                    } else {
                        res.add(null)
                    }
                } else {
                    res.add(null)
                }
            }
            return res
        }

        val speedWristL = centeredSpeed2D(wristL)
        val speedWristR = centeredSpeed2D(wristR)
        val speedAnkleL = centeredSpeed2D(ankleL)
        val speedAnkleR = centeredSpeed2D(ankleR)
        val speedKneeL = centeredSpeed2D(kneeL)
        val speedKneeR = centeredSpeed2D(kneeR)

        val velElbowL = centeredAngularSpeed(elbowAngleL)
        val velElbowR = centeredAngularSpeed(elbowAngleR)
        val velKneeL = centeredAngularSpeed(kneeAngleL)
        val velKneeR = centeredAngularSpeed(kneeAngleR)

        // 5. Aggregate Top-2 translation speed and Top-2 angular speed
        val top2Trans = DoubleArray(n) { Double.NaN }
        val top2Ang = DoubleArray(n) { Double.NaN }

        for (i in 0 until n) {
            val tCandidates = listOfNotNull(speedWristL[i], speedWristR[i], speedAnkleL[i], speedAnkleR[i], speedKneeL[i], speedKneeR[i])
            if (tCandidates.size >= 2) {
                val sorted = tCandidates.sortedDescending()
                top2Trans[i] = (sorted[0] + sorted[1]) / 2.0
            } else if (tCandidates.size == 1) {
                top2Trans[i] = tCandidates[0]
            }

            val aCandidates = listOfNotNull(velElbowL[i], velElbowR[i], velKneeL[i], velKneeR[i])
            if (aCandidates.size >= 2) {
                val sorted = aCandidates.sortedDescending()
                top2Ang[i] = (sorted[0] + sorted[1]) / 2.0
            } else if (aCandidates.size == 1) {
                top2Ang[i] = aCandidates[0]
            }
        }

        // 6. Compute session-relative q20 and q90 percentiles
        fun computePercentile(data: DoubleArray, p: Double): Double {
            val valid = data.filter { it.isFinite() }.sorted()
            if (valid.isEmpty()) return 0.0
            val rank = (p / 100.0) * (valid.size - 1)
            val low = rank.toInt()
            val high = min(low + 1, valid.size - 1)
            val weight = rank - low
            return valid[low] * (1.0 - weight) + valid[high] * weight
        }

        val q20Trans = computePercentile(top2Trans, config.q20Percentile)
        val q90Trans = computePercentile(top2Trans, config.q90Percentile)
        val q20Ang = computePercentile(top2Ang, config.q20Percentile)
        val q90Ang = computePercentile(top2Ang, config.q90Percentile)

        val stats = RetrospectiveSignalStats(
            translationQ20 = q20Trans,
            translationQ90 = q90Trans,
            angularQ20 = q20Ang,
            angularQ90 = q90Ang,
            referenceScale = lRef,
        )

        val transSpan = max(q90Trans - q20Trans, 1e-4)
        val angSpan = max(q90Ang - q20Ang, 1e-4)

        // 7. Normalize signals and compute combined movement evidence
        val timeline = ArrayList<RetrospectiveKinematicFrame>(n)
        for (i in 0 until n) {
            val rawEt = if (top2Trans[i].isFinite()) top2Trans[i] else null
            val rawEa = if (top2Ang[i].isFinite()) top2Ang[i] else null

            val normEt = rawEt?.let { ((it - q20Trans) / transSpan).coerceIn(0.0, 2.0) }
            val normEa = rawEa?.let { ((it - q20Ang) / angSpan).coerceIn(0.0, 2.0) }

            val comb = when {
                normEt != null && normEa != null -> max(normEt, normEa)
                normEt != null -> normEt
                normEa != null -> normEa
                else -> null
            }

            val isMoving = (comb ?: 0.0) >= config.startThresholdNormalized
            val isQuiet = (comb ?: Double.MAX_VALUE) <= config.quietThresholdNormalized

            timeline.add(
                RetrospectiveKinematicFrame(
                    timestampMs = timestamps[i],
                    frameIndex = i,
                    rawTranslationEvidence = rawEt,
                    rawAngularEvidence = rawEa,
                    normalizedTranslationEvidence = normEt,
                    normalizedAngularEvidence = normEa,
                    combinedMovementEvidence = comb,
                    isMoving = isMoving,
                    isQuiet = isQuiet,
                )
            )
        }

        return timeline to stats
    }
}

