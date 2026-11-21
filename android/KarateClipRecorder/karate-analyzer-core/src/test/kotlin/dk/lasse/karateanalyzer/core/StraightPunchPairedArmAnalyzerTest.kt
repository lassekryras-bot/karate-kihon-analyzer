package dk.lasse.karateanalyzer.core

import dk.lasse.karateanalyzer.capture.LateralSide
import dk.lasse.karateanalyzer.geometry.CanonicalGeometryDescriptor
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.test.assertFailsWith
import kotlin.math.cos
import kotlin.math.sin

class StraightPunchPairedArmAnalyzerTest {
    private val left = listOf(PoseLandmarkId.LEFT_ELBOW, PoseLandmarkId.LEFT_WRIST, PoseLandmarkId.LEFT_THUMB,
        PoseLandmarkId.LEFT_INDEX, PoseLandmarkId.LEFT_PINKY)
    private val right = listOf(PoseLandmarkId.RIGHT_ELBOW, PoseLandmarkId.RIGHT_WRIST, PoseLandmarkId.RIGHT_THUMB,
        PoseLandmarkId.RIGHT_INDEX, PoseLandmarkId.RIGHT_PINKY)

    @Test fun `right and left exchanges resolve independently including repeated side`() {
        val rightResult = analyze(exchange = LateralSide.RIGHT)
        val leftResult = analyze(exchange = LateralSide.LEFT)
        val repeatedRight = analyze(exchange = LateralSide.RIGHT)
        assertEquals(LateralSide.RIGHT, rightResult.side)
        assertEquals(LateralSide.LEFT, leftResult.side)
        assertEquals(LateralSide.RIGHT, repeatedRight.side)
        assertTrue(rightResult.pairedScoreMeters > 0.0 && leftResult.pairedScoreMeters < 0.0)
        assertEquals(rightResult.pairedScoreMeters,
            rightResult.leftArm.normalizedContributionMeters + rightResult.rightArm.normalizedContributionMeters, 1e-8)
    }

    @Test fun `brief arm label swap and variable confidence recover before end`() {
        val result = analyze(exchange = LateralSide.RIGHT) { time, samples ->
            if (time == 250L) {
                for (index in left.indices) {
                    val l = samples.getValue(left[index])
                    val r = samples.getValue(right[index])
                    samples[left[index]] = r.copy(visibility = .60f, presence = .60f)
                    samples[right[index]] = l.copy(visibility = .95f, presence = .95f)
                }
            } else if (time in 300L..350L) {
                right.forEach { samples[it] = samples.getValue(it).copy(visibility = .60f, presence = .60f) }
            }
        }
        assertEquals(LateralSide.RIGHT, result.side)
        assertEquals(PairedArmSupportMode.BILATERAL, result.supportMode)
    }

    @Test fun `one arm resolves while neither arm abstains`() {
        val one = analyze(exchange = LateralSide.RIGHT) { _, samples -> left.forEach(samples::remove) }
        assertEquals(LateralSide.RIGHT, one.side)
        assertEquals(PairedArmSupportMode.SINGLE_RIGHT, one.supportMode)
        val neither = analyze(exchange = LateralSide.RIGHT) { _, samples -> (left + right).forEach(samples::remove) }
        assertNull(neither.side)
        assertEquals(PairedArmDecisionReason.INSUFFICIENT_VALID_COVERAGE, neither.decisionReason)
    }

    @Test fun `gaps reset filters and nonuniform timestamps use actual elapsed time`() {
        val regular = analyze(LateralSide.RIGHT)
        val nonuniform = analyze(LateralSide.RIGHT, listOf(0, 35, 90, 145, 230, 300, 385, 470, 535, 600))
        assertEquals(LateralSide.RIGHT, nonuniform.side)
        assertTrue(kotlin.math.abs(regular.pairedScoreMeters - nonuniform.pairedScoreMeters) < .08)
        val gapped = analyze(LateralSide.RIGHT, listOf(0, 50, 100, 450, 500, 550, 600))
        assertNull(gapped.side)
        assertTrue(gapped.decisionReason in setOf(PairedArmDecisionReason.INSUFFICIENT_VALID_COVERAGE,
            PairedArmDecisionReason.FORWARD_AXIS_UNRELIABLE))
        assertTrue(kotlin.math.abs(gapped.pairedScoreMeters) < kotlin.math.abs(regular.pairedScoreMeters))
    }

    @Test fun `fixed roster confidence normalization discounts missing landmarks`() {
        val complete = analyze(LateralSide.RIGHT)
        val sparseRight = analyze(LateralSide.RIGHT) { _, samples -> right.filter { it != PoseLandmarkId.RIGHT_WRIST }.forEach(samples::remove) }
        assertEquals(LateralSide.RIGHT, sparseRight.side)
        assertTrue(sparseRight.rightArm.normalizedContributionMeters < complete.rightArm.normalizedContributionMeters)
        assertTrue(sparseRight.leftArm.normalizedContributionMeters > complete.leftArm.normalizedContributionMeters)
    }

    @Test fun `forward axis reversal or missing front evidence abstains`() {
        val reversed = analyze(LateralSide.RIGHT) { time, samples ->
            if ((time / 50) % 2L == 0L) samples[PoseLandmarkId.NOSE] = sample(0.0, .7, -.25)
        }
        assertNull(reversed.side)
        assertEquals(ForwardAxisStatus.UNRELIABLE, reversed.forwardAxis.status)
        val unavailable = analyze(LateralSide.RIGHT) { _, samples ->
            samples.remove(PoseLandmarkId.NOSE); samples.remove(PoseLandmarkId.MOUTH_LEFT); samples.remove(PoseLandmarkId.MOUTH_RIGHT)
        }
        assertNull(unavailable.side)
        assertEquals(ForwardAxisStatus.UNAVAILABLE, unavailable.forwardAxis.status)
    }

    @Test fun `intermittent lost axis reference is not bridged into coverage`() {
        val result = analyze(LateralSide.RIGHT) { time, samples ->
            if ((time / 50) % 2L == 1L) {
                samples.remove(PoseLandmarkId.NOSE)
                samples.remove(PoseLandmarkId.MOUTH_LEFT)
                samples.remove(PoseLandmarkId.MOUTH_RIGHT)
            }
        }
        assertNull(result.side)
        assertEquals(ForwardAxisStatus.UNRELIABLE, result.forwardAxis.status)
        assertEquals(0.0, result.forwardAxis.coverage, 1e-9)
    }

    @Test fun `invalid confidence is missing and timestamps must be strictly ordered`() {
        val invalid = analyze(LateralSide.RIGHT) { _, samples ->
            (left + right).forEach { id -> samples[id] = samples.getValue(id).copy(visibility = Float.NaN) }
        }
        assertNull(invalid.side)
        assertEquals(PairedArmDecisionReason.INSUFFICIENT_VALID_COVERAGE, invalid.decisionReason)
        assertFailsWith<IllegalArgumentException> { analyze(LateralSide.RIGHT, listOf(0, 50, 50, 100)) }
        assertFailsWith<IllegalArgumentException> { analyze(LateralSide.RIGHT, listOf(0, 100, 50, 150)) }
    }

    @Test fun `anatomical axis survives oblique rotation and coordinate reflection`() {
        fun transformed(angleDeg: Double, reflectX: Boolean) = analyze(LateralSide.RIGHT) { _, samples ->
            val angle = Math.toRadians(angleDeg)
            samples.replaceAll { _, value ->
                val p = value.worldPosition!!
                val x0 = if (reflectX) -p.x.toDouble() else p.x.toDouble()
                val x = x0 * cos(angle) + p.z * sin(angle)
                val z = -x0 * sin(angle) + p.z * cos(angle)
                value.copy(worldPosition = Point3(x.toFloat(), p.y, z.toFloat()))
            }
        }
        assertEquals(LateralSide.RIGHT, transformed(37.0, false).side)
        assertEquals(LateralSide.RIGHT, transformed(-52.0, true).side)
    }

    @Test fun `small full-window cancellation stays uncertain`() {
        val result = analyze(LateralSide.RIGHT) { time, samples ->
            val triangular = (if (time <= 300) time / 300.0 else (600 - time) / 300.0) * .01
            val z = triangular.toFloat()
            left.forEach { id -> samples[id] = samples.getValue(id).let { it.copy(worldPosition = it.worldPosition!!.copy(z = -z)) } }
            right.forEach { id -> samples[id] = samples.getValue(id).let { it.copy(worldPosition = it.worldPosition!!.copy(z = z)) } }
        }
        assertNull(result.side)
        assertEquals(PairedArmDecisionReason.SCORE_WITHIN_UNCERTAINTY_BAND, result.decisionReason)
    }

    private fun analyze(
        exchange: LateralSide,
        times: List<Long> = (0L..600L step 50L).toList(),
        edit: (Long, MutableMap<PoseLandmarkId, PoseLandmarkSample>) -> Unit = { _, _ -> },
    ): StraightPunchPairedArmResult {
        val frames = times.map { time ->
            val progress = time / 600.0
            val direction = if (exchange == LateralSide.RIGHT) 1.0 else -1.0
            val samples = mutableMapOf(
                PoseLandmarkId.LEFT_SHOULDER to sample(-.20, .50, 0.0),
                PoseLandmarkId.RIGHT_SHOULDER to sample(.20, .50, 0.0),
                PoseLandmarkId.LEFT_HIP to sample(-.15, 0.0, 0.0),
                PoseLandmarkId.RIGHT_HIP to sample(.15, 0.0, 0.0),
                PoseLandmarkId.NOSE to sample(0.0, .70, .25),
                PoseLandmarkId.MOUTH_LEFT to sample(-.03, .65, .22),
                PoseLandmarkId.MOUTH_RIGHT to sample(.03, .65, .22),
            )
            left.forEach { samples[it] = sample(-.25, .30, -direction * progress * .40) }
            right.forEach { samples[it] = sample(.25, .30, direction * progress * .40) }
            edit(time, samples)
            PoseFrame(time, samples)
        }
        return StraightPunchPairedArmAnalyzer.analyze("m", "track",
            CanonicalGeometryDescriptor("g", recordingId = "recording", landmarkTrackId = "track",
                canonicalWidth = 1000, canonicalHeight = 1000),
            times.first() * 1_000L, times.last() * 1_000L, frames)
    }

    private fun sample(x: Double, y: Double, z: Double) = PoseLandmarkSample(
        position = Point3(x.toFloat(), y.toFloat(), z.toFloat()),
        worldPosition = Point3(x.toFloat(), y.toFloat(), z.toFloat()),
        visibility = .95f,
        presence = .95f,
        source = LandmarkSource.OBSERVED,
    )
}
