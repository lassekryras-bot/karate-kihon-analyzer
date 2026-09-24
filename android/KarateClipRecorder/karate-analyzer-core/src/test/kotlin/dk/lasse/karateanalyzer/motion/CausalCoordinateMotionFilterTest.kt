package dk.lasse.karateanalyzer.motion

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull

class CausalCoordinateMotionFilterTest {
    @Test
    fun `median mean filter derives speed from actual timestamps`() {
        val filter = CausalCoordinateMotionFilter(coordinateCount = 1, medianSamples = 1, meanSamples = 1)
        filter.accept(0L, listOf(0.0), 1.0)
        val fast = filter.accept(20_000L, listOf(0.02), 1.0)
        val slow = filter.accept(60_000L, listOf(0.04), 1.0)

        assertEquals(1.0, assertNotNull(fast.speed), 1e-9)
        assertEquals(0.5, assertNotNull(slow.speed), 1e-9)
        assertEquals(0.04, slow.deltaSeconds)
    }

    @Test
    fun `three sample median then mean rejects an isolated spike causally`() {
        val filter = CausalCoordinateMotionFilter(coordinateCount = 1)
        filter.accept(0L, listOf(0.0), 1.0)
        filter.accept(10_000L, listOf(100.0), 1.0)
        filter.accept(20_000L, listOf(0.0), 1.0)
        val output = filter.accept(30_000L, listOf(0.0), 1.0)

        assertEquals(16.666666666666668, output.coordinates.single(), 1e-9)
    }
}
