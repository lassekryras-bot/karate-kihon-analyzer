package dk.lasse.karateanalyzer.geometry

import org.junit.Assert.*
import org.junit.Test

class LandmarkTrajectoriesTest {

    @Test
    fun outAndBackDisplacementVersusTravelDistance() {
        val s0 = TrajectorySample(0L, UpwardMetricPoint(0f, 0f))
        val s1 = TrajectorySample(33_333L, UpwardMetricPoint(1f, 0f))
        val s2 = TrajectorySample(66_666L, UpwardMetricPoint(0f, 0f))

        // Displacement: end minus start = (0, 0)
        val disp = LandmarkTrajectories.displacementOf(s0, s2)
        assertEquals(0f, disp.magnitude, 1e-5f)
        assertEquals(0f, disp.vector.x, 1e-5f)
        assertEquals(0f, disp.vector.y, 1e-5f)

        // Travel distance: 1.0 + 1.0 = 2.0
        val travel = LandmarkTrajectories.travelDistanceOf(listOf(s0, s1, s2))
        assertEquals(MeasurementResultState.AVAILABLE, travel.state)
        assertEquals(2f, travel.totalDistance, 1e-5f)
        assertEquals(2, travel.validEdgeCount)
        assertEquals(0, travel.gapCount)
    }

    @Test
    fun relativeDisplacementCancelsSharedTranslation() {
        // Body (reference) moves +1.0 forward
        val refStart = TrajectorySample(0L, UpwardMetricPoint(0.5f, 0.5f))
        val refEnd = TrajectorySample(100_000L, UpwardMetricPoint(1.5f, 0.5f))

        // Hand (subject) moves +1.5 forward (1.0 from body + 0.5 extension)
        val subStart = TrajectorySample(0L, UpwardMetricPoint(0.5f, 0.5f))
        val subEnd = TrajectorySample(100_000L, UpwardMetricPoint(2.0f, 0.5f))

        val relDisp = LandmarkTrajectories.relativeDisplacementOf(
            subjectStart = subStart,
            subjectEnd = subEnd,
            referenceStart = refStart,
            referenceEnd = refEnd,
        )

        assertEquals(0.5f, relDisp.vector.x, 1e-5f)
        assertEquals(0f, relDisp.vector.y, 1e-5f)
        assertEquals(0.5f, relDisp.magnitude, 1e-5f)
    }

    @Test
    fun travelDistanceGapsNotBridged() {
        val s0 = TrajectorySample(0L, UpwardMetricPoint(0f, 0f))
        val s1 = TrajectorySample(33_333L, UpwardMetricPoint(1f, 0f))
        // Gap of 200ms (> 100ms maxGapUs)
        val s2 = TrajectorySample(233_333L, UpwardMetricPoint(5f, 0f))
        val s3 = TrajectorySample(266_666L, UpwardMetricPoint(6f, 0f))

        val travel = LandmarkTrajectories.travelDistanceOf(listOf(s0, s1, s2, s3), maxGapUs = 100_000L)

        assertEquals(MeasurementResultState.PARTIAL, travel.state)
        // Edge 0->1 is 1.0; Edge 2->3 is 1.0. Edge 1->2 is not bridged! Total = 2.0
        assertEquals(2f, travel.totalDistance, 1e-5f)
        assertEquals(2, travel.validEdgeCount)
        assertEquals(1, travel.gapCount)
        assertEquals(2, travel.observedSpans.size)
    }

    @Test
    fun pathDeviationAndTieBreaking() {
        // Reference line along +X axis (y = 0)
        val refLine = DirectedLandmarkLine(UpwardMetricPoint(0f, 0f), UpwardMetricPoint(1f, 0f))

        val samples = listOf(
            TrajectorySample(10_000L, UpwardMetricPoint(0.1f, 0.2f)),
            TrajectorySample(20_000L, UpwardMetricPoint(0.2f, 0.5f)), // Max 0.5 at 20_000
            TrajectorySample(30_000L, UpwardMetricPoint(0.3f, 0.5f)), // Equal max 0.5 at 30_000
            TrajectorySample(40_000L, UpwardMetricPoint(0.4f, 0.1f)),
        )

        val res = LandmarkTrajectories.pathDeviationOf(samples, refLine)
        assertNotNull(res)
        assertEquals(0.5f, res!!.maxDeviation, 1e-5f)

        // Deterministic earliest-timestamp tie breaking: 20_000L rather than 30_000L
        assertEquals(20_000L, res.maxDeviationTimestampUs)

        // Sample RMS: sqrt((0.04 + 0.25 + 0.25 + 0.01) / 4) = sqrt(0.55 / 4) = sqrt(0.1375) ~ 0.3708
        val expectedRms = kotlin.math.sqrt(0.55 / 4.0).toFloat()
        assertEquals(expectedRms, res.sampleRms, 1e-4f)
    }

    @Test
    fun backwardFiniteDifferenceVelocity() {
        val s0 = TrajectorySample(0L, UpwardMetricPoint(0f, 0f))
        val s1 = TrajectorySample(50_000L, UpwardMetricPoint(0.1f, 0f)) // 50ms, dx = 0.1 -> vx = 2.0/s
        val s2 = TrajectorySample(100_000L, UpwardMetricPoint(0.1f, 0.2f)) // 50ms, dy = 0.2 -> vy = 4.0/s

        val vels = LandmarkTrajectories.velocityAndSpeedOf(listOf(s0, s1, s2))
        assertEquals(2, vels.size)

        assertEquals(0L, vels[0].startTimestampUs)
        assertEquals(50_000L, vels[0].endTimestampUs)
        assertEquals(2.0f, vels[0].speed, 1e-4f)

        assertEquals(50_000L, vels[1].startTimestampUs)
        assertEquals(100_000L, vels[1].endTimestampUs)
        assertEquals(4.0f, vels[1].speed, 1e-4f)
    }

    @Test
    fun velocityDoesNotBridgeInvalidSamples() {
        val s0 = TrajectorySample(0L, UpwardMetricPoint(0f, 0f), isValid = true)
        val s1 = TrajectorySample(50_000L, UpwardMetricPoint(0.1f, 0f), isValid = false) // Invalid sample
        val s2 = TrajectorySample(100_000L, UpwardMetricPoint(0.2f, 0f), isValid = true)

        val vels = LandmarkTrajectories.velocityAndSpeedOf(listOf(s0, s1, s2))
        // Because s1 is invalid, edges 0->1 and 1->2 are rejected. 0->2 MUST NOT be bridged.
        assertTrue(vels.isEmpty())
    }
}
