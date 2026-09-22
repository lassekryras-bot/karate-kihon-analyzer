package dk.lasse.karateanalyzer.geometry

import org.junit.Assert.*
import org.junit.Test

class LandmarkRelationsTest {

    @Test
    fun distanceBetweenPoints() {
        val p1 = UpwardMetricPoint(0f, 0f)
        val p2 = UpwardMetricPoint(3f, 4f)
        assertEquals(5f, LandmarkRelations.distance(p1, p2), 1e-5f)
    }

    @Test
    fun signedDistanceToLine() {
        // Directed line along +Y: start at (0, 0), end at (0, 1)
        val line = DirectedLandmarkLine(UpwardMetricPoint(0f, 0f), UpwardMetricPoint(0f, 1f))

        // Point on the left (+X negative): (-2, 0.5)
        val pLeft = UpwardMetricPoint(-2f, 0.5f)
        val dLeft = LandmarkRelations.signedDistanceToLine(pLeft, line)
        assertNotNull(dLeft)
        assertEquals(2f, dLeft!!, 1e-4f) // Left is positive

        // Point on the right (+X positive): (3, 0.5)
        val pRight = UpwardMetricPoint(3f, 0.5f)
        val dRight = LandmarkRelations.signedDistanceToLine(pRight, line)
        assertNotNull(dRight)
        assertEquals(-3f, dRight!!, 1e-4f) // Right is negative

        // Point on line
        val pOnLine = UpwardMetricPoint(0f, 0.7f)
        assertEquals(0f, LandmarkRelations.signedDistanceToLine(pOnLine, line)!!, 1e-4f)
    }

    @Test
    fun distanceToSegment() {
        // Horizontal segment from (1, 0) to (5, 0)
        val seg = LandmarkSegment(UpwardMetricPoint(1f, 0f), UpwardMetricPoint(5f, 0f))

        // Point above middle: projects onto segment
        val pMid = UpwardMetricPoint(3f, 4f)
        assertEquals(4f, LandmarkRelations.distanceToSegment(pMid, seg), 1e-4f)

        // Point to the left: closest to start (1, 0)
        val pLeft = UpwardMetricPoint(-2f, 4f) // dx = 3, dy = 4 -> dist = 5
        assertEquals(5f, LandmarkRelations.distanceToSegment(pLeft, seg), 1e-4f)

        // Point to the right: closest to end (5, 0)
        val pRight = UpwardMetricPoint(8f, 4f) // dx = 3, dy = 4 -> dist = 5
        assertEquals(5f, LandmarkRelations.distanceToSegment(pRight, seg), 1e-4f)
    }

    @Test
    fun anglesBetweenLinesAndWrapContract() {
        val lineX = DirectedLandmarkLine(UpwardMetricPoint(0f, 0f), UpwardMetricPoint(1f, 0f))
        val lineY = DirectedLandmarkLine(UpwardMetricPoint(0f, 0f), UpwardMetricPoint(0f, 1f))
        val lineNegX = DirectedLandmarkLine(UpwardMetricPoint(0f, 0f), UpwardMetricPoint(-1f, 0f))
        val lineNegY = DirectedLandmarkLine(UpwardMetricPoint(0f, 0f), UpwardMetricPoint(0f, -1f))

        // +X to +Y is 90 deg counterclockwise
        assertEquals(90f, LandmarkRelations.unsignedAngleBetweenLines(lineX, lineY)!!, 1e-4f)
        assertEquals(90f, LandmarkRelations.signedAngleBetweenLines(lineX, lineY)!!, 1e-4f)

        // +Y to +X is -90 deg
        assertEquals(-90f, LandmarkRelations.signedAngleBetweenLines(lineY, lineX)!!, 1e-4f)

        // Opposite vectors: +X to -X
        // Specification contract: signed angle in [-180, 180) with opposite vectors normalizing to -180
        assertEquals(180f, LandmarkRelations.unsignedAngleBetweenLines(lineX, lineNegX)!!, 1e-4f)
        assertEquals(-180f, LandmarkRelations.signedAngleBetweenLines(lineX, lineNegX)!!, 1e-4f)

        // Degenerate line handling
        val degen = DirectedLandmarkLine(UpwardMetricPoint(0f, 0f), UpwardMetricPoint(0f, 0f))
        assertNull(LandmarkRelations.unsignedAngleBetweenLines(lineX, degen))
        assertNull(LandmarkRelations.signedAngleBetweenLines(lineX, degen))
    }

    @Test
    fun jointAngleVertexOrdering() {
        val a = UpwardMetricPoint(0f, 1f)
        val vertex = UpwardMetricPoint(0f, 0f)
        val c = UpwardMetricPoint(1f, 0f)

        val angle = LandmarkRelations.jointAngle(a, vertex, c)
        assertNotNull(angle)
        assertEquals(90f, angle!!, 1e-4f)

        // Collinear extended (180 deg)
        val straight = UpwardMetricPoint(0f, -1f)
        assertEquals(180f, LandmarkRelations.jointAngle(a, vertex, straight)!!, 1e-4f)

        // Folded on itself (0 deg)
        assertEquals(0f, LandmarkRelations.jointAngle(a, vertex, a)!!, 1e-4f)
    }
}

