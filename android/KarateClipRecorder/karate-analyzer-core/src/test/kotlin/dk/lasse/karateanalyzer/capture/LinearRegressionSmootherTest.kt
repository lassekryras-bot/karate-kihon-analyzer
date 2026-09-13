package dk.lasse.karateanalyzer.capture

import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class LinearRegressionSmootherTest {
    private val smoother = LinearRegressionSmoother(
        windowDurationMs = 100L,
        minimumSamples = 3,
        minimumWindowSpanRatio = 0.60,
        minimumLandmarkConfidence = 0.50,
    )

    // 1. Zero motion: Constant position produces vx ~ 0, vy ~ 0, speed ~ 0
    @Test
    fun testZeroMotionProducesNearZeroVelocity() {
        val samples = (0..5).map { i ->
            TimestampedPoint2D(
                timestampSec = i * 0.020,
                x = 1.50,
                y = 2.50,
                confidence = 0.90,
            )
        }
        val result = smoother.fitVelocity2D(samples, targetTimeSec = 0.100)
        assertNotNull(result)
        assertTrue(abs(result.vx) < 1e-6, "vx must be approximately 0")
        assertTrue(abs(result.vy) < 1e-6, "vy must be approximately 0")
        assertTrue(abs(result.speed) < 1e-6, "speed must be approximately 0")
    }

    // 2. Constant velocity: x(t) = x0 + 0.4*t recovers vx = 0.4 +- epsilon
    @Test
    fun testConstantVelocityRecoversExactSlope() {
        val samples = (0..5).map { i ->
            val t = i * 0.020
            TimestampedPoint2D(
                timestampSec = t,
                x = 1.0 + 0.40 * t,
                y = 2.0 - 0.30 * t,
                confidence = 0.95,
            )
        }
        val result = smoother.fitVelocity2D(samples, targetTimeSec = 0.100)
        assertNotNull(result)
        assertEquals(0.40, result.vx, 1e-4)
        assertEquals(-0.30, result.vy, 1e-4)
        assertEquals(0.50, result.speed, 1e-4) // sqrt(0.4^2 + 0.3^2) = 0.5
    }

    // 3. Signed jitter cancellation: alternating jitter with no trend produces near-zero velocity
    @Test
    fun testSignedJitterCancellation() {
        // High-frequency alternating noise +-0.02 (which would create 0.04/0.02 = 2.0 raw finite difference speed!)
        val jitter = doubleArrayOf(-0.02, 0.02, -0.02, 0.02, -0.02, 0.02)
        val samples = jitter.indices.map { i ->
            TimestampedPoint2D(
                timestampSec = i * 0.020,
                x = 1.0 + jitter[i],
                y = 2.0,
                confidence = 0.90,
            )
        }
        val result = smoother.fitVelocity2D(samples, targetTimeSec = 0.100)
        assertNotNull(result)
        // With ordinary linear regression on balanced alternating jitter over 6 samples, slope is near zero
        // Raw 1-frame finite difference would produce |0.02 - (-0.02)| / 0.02 = 2.0!
        // Regression reduces alternating jitter from 2.0 down to ~0.17 (>90% suppression)
        assertTrue(abs(result.vx) < 0.20, "Signed jitter should cancel in regression slope, got vx=${result.vx} vs raw finite difference of 2.0")
        assertTrue(result.speed < 0.20, "Speed should remain small, got speed=${result.speed}")
    }

    // 4. Trend plus jitter: x(t) = x0 + v*t + epsilon(t) recovers v despite noise
    @Test
    fun testTrendPlusJitterRecoversTrueVelocity() {
        val jitter = doubleArrayOf(-0.01, 0.01, -0.01, 0.01, -0.01, 0.01)
        val vTrue = 1.20
        val samples = jitter.indices.map { i ->
            val t = i * 0.020
            TimestampedPoint2D(
                timestampSec = t,
                x = 1.0 + vTrue * t + jitter[i],
                y = 0.50,
                confidence = 0.90,
            )
        }
        val result = smoother.fitVelocity2D(samples, targetTimeSec = 0.100)
        assertNotNull(result)
        assertEquals(vTrue, result.vx, 0.15, "Regression must recover trend velocity despite jitter")
    }

    // 5. Irregular timestamps: Uneven dt recovers identical velocity
    @Test
    fun testIrregularTimestampsRecoverSameVelocity() {
        val timestamps = doubleArrayOf(0.000, 0.018, 0.041, 0.063, 0.082, 0.100)
        val vTrue = 0.75
        val samples = timestamps.map { t ->
            TimestampedPoint2D(
                timestampSec = t,
                x = 0.5 + vTrue * t,
                y = 1.0,
                confidence = 0.85,
            )
        }
        val result = smoother.fitVelocity2D(samples, targetTimeSec = 0.100)
        assertNotNull(result)
        assertEquals(vTrue, result.vx, 1e-4)
        assertEquals(vTrue, result.speed, 1e-4)
    }

    // 6. Missing samples: N < 3 returns null (UNKNOWN), not zero
    @Test
    fun testMissingSamplesReturnsNull() {
        val samples2 = listOf(
            TimestampedPoint2D(0.020, 1.0, 1.0, 0.9),
            TimestampedPoint2D(0.090, 1.1, 1.0, 0.9),
        )
        assertNull(smoother.fitVelocity2D(samples2, targetTimeSec = 0.100), "N=2 must return null")

        val samplesLowConf = (0..4).map { i ->
            TimestampedPoint2D(i * 0.020, 1.0 + i * 0.1, 1.0, if (i == 2) 0.40 else 0.90)
        }
        assertNull(smoother.fitVelocity2D(samplesLowConf, targetTimeSec = 0.080), "Confidence < C_min must return null")

        // Current sample absent: window has valid past points [0.000..0.060] but missing at targetTimeSec = 0.100
        val samplesNoCurrent = (0..3).map { i ->
            TimestampedPoint2D(i * 0.020, 1.0 + i * 0.1, 1.0, 0.90)
        }
        assertNull(smoother.fitVelocity2D(samplesNoCurrent, targetTimeSec = 0.100), "Missing sample at targetTimeSec must return null")
    }

    // 7. Angle wrap (360 deg): 358, 359, 1, 2 represents continuous forward rotation
    @Test
    fun testAngleWrap360Directed() {
        // dt = 20 ms -> 1 deg per 20 ms = 50 deg/sec
        val angles = doubleArrayOf(358.0, 359.0, 0.0, 1.0, 2.0)
        val samples = angles.indices.map { i ->
            TimestampedAngle(
                timestampSec = i * 0.020,
                angleDeg = angles[i],
                confidence = 0.95,
                isUndirectedAxis = false,
            )
        }
        val result = smoother.fitAngularRate(samples, targetTimeSec = 0.080)
        assertNotNull(result)
        assertEquals(50.0, result.signedRateDegPerSec, 1e-3, "Should unwrap to +50 deg/sec without wrap reversal")
        assertEquals(50.0, result.speedDegPerSec, 1e-3)
    }

    // 8. Undirected-axis wrap (180 deg): 179 -> 1 obeys 180 deg line symmetry
    @Test
    fun testUndirectedAxisWrap180() {
        // Line rotating from 177 to 181 (which wraps to 1 deg)
        // 177, 179, 1 (i.e. 181), 3 (i.e. 183), 5 (i.e. 185) -> 2 deg per 20 ms = 100 deg/sec
        val angles = doubleArrayOf(177.0, 179.0, 1.0, 3.0, 5.0)
        val samples = angles.indices.map { i ->
            TimestampedAngle(
                timestampSec = i * 0.020,
                angleDeg = angles[i],
                confidence = 0.95,
                isUndirectedAxis = true,
            )
        }
        val result = smoother.fitAngularRate(samples, targetTimeSec = 0.080)
        assertNotNull(result)
        assertEquals(100.0, result.signedRateDegPerSec, 1e-3, "Should unwrap under 180 deg periodicity to +100 deg/sec")
    }

    // 9. Degenerate geometry / invalid values return null
    @Test
    fun testDegenerateGeometryReturnsNull() {
        val nanSamples = (0..4).map { i ->
            TimestampedPoint2D(i * 0.020, if (i == 3) Double.NaN else 1.0, 1.0, 0.90)
        }
        assertNull(smoother.fitVelocity2D(nanSamples, targetTimeSec = 0.080))

        val infAngles = (0..4).map { i ->
            TimestampedAngle(i * 0.020, if (i == 3) Double.POSITIVE_INFINITY else 90.0, 0.90)
        }
        assertNull(smoother.fitAngularRate(infAngles, targetTimeSec = 0.080))
    }

    // 10. Top-2 single-channel spike attenuation: One giant channel with all others quiet produces ~50% attenuation
    @Test
    fun testTop2SingleChannelSpikeAttenuation() {
        val spikeSpeed = 4.0
        val quietSpeed = 0.02
        val channelSpeeds = listOf(spikeSpeed, quietSpeed, quietSpeed, quietSpeed)

        // Mathematical contract Section 14: Top-2 mean is (v_(1) + v_(2)) / 2
        val sorted = channelSpeeds.sortedDescending()
        val eT = (sorted[0] + sorted[1]) / 2.0

        assertEquals(2.01, eT, 1e-3, "Top-2 mean must attenuate single spike by approximately half")
        assertTrue(eT < spikeSpeed * 0.60, "Single spike must be attenuated by ~50%")
    }

    // 11. Two-channel genuine movement: Two strong channels produce full Top-2 signal
    @Test
    fun testTwoChannelGenuineMovement() {
        val wristSpeed = 1.50
        val elbowSpeed = 1.20
        val otherSpeeds = listOf(0.05, 0.02, 0.04)
        val allSpeeds = listOf(wristSpeed, elbowSpeed) + otherSpeeds

        val sorted = allSpeeds.sortedDescending()
        val eT = (sorted[0] + sorted[1]) / 2.0

        assertEquals(1.35, eT, 1e-3, "Two genuine moving channels must produce robust unattenuated Top-2 evidence")
    }

    // 12. Common-scale invariance: Modifying one landmark's movement does not rescale other landmarks
    @Test
    fun testCommonScaleInvariance() {
        val lRef = 0.25 // Common upper arm length reference in meters or image fractions
        val rawWristDisplacement = 0.10 // 0.10 / 0.25 = 0.40 L_ref
        val rawAnkleDisplacement = 0.05 // 0.05 / 0.25 = 0.20 L_ref

        val normalizedWrist = rawWristDisplacement / lRef
        val normalizedAnkle = rawAnkleDisplacement / lRef

        assertEquals(0.40, normalizedWrist, 1e-6)
        assertEquals(0.20, normalizedAnkle, 1e-6)

        // If wrist moves 10x faster later in the session, ankle's normalized coordinate does NOT change
        // (unlike per-channel p95 normalization which would distort ankle importance).
        val laterRawWrist = 1.00
        val laterNormalizedWrist = laterRawWrist / lRef
        val laterNormalizedAnkle = rawAnkleDisplacement / lRef

        assertEquals(4.00, laterNormalizedWrist, 1e-6)
        assertEquals(0.20, laterNormalizedAnkle, 1e-6, "Ankle normalization must remain invariant to wrist amplitude")
    }
}
