package dk.lasse.karateanalyzer.geometry

import org.junit.Assert.*
import org.junit.Test
import kotlin.math.abs

class FrameGeometryMathTest {

    private val portrait = FrameGeometry(1080, 1920) // aspect = 9/16 = 0.5625
    private val landscape = FrameGeometry(1920, 1080) // aspect = 16/9 = 1.7778
    private val square = FrameGeometry(1000, 1000)

    @Test
    fun testSourceToAspectCorrectAndBack() {
        val src = SourceNormalizedPoint(0.5f, 0.5f)
        val aspectP = FrameGeometryMath.sourceToAspectCorrect(src, portrait)
        assertEquals(0.5f * (1080f / 1920f), aspectP.x, 1e-5f)
        assertEquals(0.5f, aspectP.y, 1e-5f)

        val restored = FrameGeometryMath.aspectCorrectToSource(aspectP, portrait)
        assertEquals(src.x, restored.x, 1e-5f)
        assertEquals(src.y, restored.y, 1e-5f)
    }

    @Test
    fun testDistanceAndSegmentLength() {
        val p1 = AspectCorrectPoint(0f, 0f)
        val p2 = AspectCorrectPoint(3f, 4f)
        assertEquals(5f, FrameGeometryMath.distance(p1, p2), 1e-5f)
        assertEquals(5f, FrameGeometryMath.segmentLength(p1, p2), 1e-5f)
    }

    @Test
    fun testSignedAngleDeg() {
        val right = AspectCorrectPoint(1f, 0f)
        val down = AspectCorrectPoint(0f, 1f) // +Y is downward
        val up = AspectCorrectPoint(0f, -1f) // -Y is upward

        // Clockwise from right to down is +90 deg in image coords
        assertEquals(90f, FrameGeometryMath.signedAngleDeg(right, down), 1e-3f)
        // Counter-clockwise from right to up is -90 deg
        assertEquals(-90f, FrameGeometryMath.signedAngleDeg(right, up), 1e-3f)
    }

    @Test
    fun testAbsoluteAngleDeg() {
        val v1 = AspectCorrectPoint(1f, 0f)
        val v2 = AspectCorrectPoint(0f, -1f)
        assertEquals(90f, FrameGeometryMath.absoluteAngleDeg(v1, v2), 1e-3f)
    }

    @Test
    fun testCircleHorizontalLineIntersections() {
        val center = AspectCorrectPoint(0.5f, 0.5f)
        val radius = 0.2f

        // Case 1: Line misses circle (0 intersections)
        val missLine = 0.8f // distance = 0.3 > 0.2
        val missResult = FrameGeometryMath.circleHorizontalLineIntersections(center, radius, missLine)
        assertTrue(missResult.isEmpty())

        // Case 2: Line is tangent to circle (1 intersection)
        val tangentLine = 0.7f // distance = 0.2 == radius
        val tangentResult = FrameGeometryMath.circleHorizontalLineIntersections(center, radius, tangentLine)
        assertEquals(1, tangentResult.size)
        assertEquals(0.5f, tangentResult[0].x, 1e-4f)
        assertEquals(0.7f, tangentResult[0].y, 1e-4f)

        // Case 3: Line cuts through circle (2 intersections)
        val cutLine = 0.5f // center line, distance = 0
        val cutResult = FrameGeometryMath.circleHorizontalLineIntersections(center, radius, cutLine)
        assertEquals(2, cutResult.size)
        assertEquals(0.3f, cutResult[0].x, 1e-4f)
        assertEquals(0.7f, cutResult[1].x, 1e-4f)
    }

    @Test
    fun testAspectCorrectRadiusToSourceEllipse() {
        val radius = 0.2f
        val (radX, radY) = FrameGeometryMath.aspectCorrectRadiusToSourceEllipse(radius, portrait)
        assertEquals(radius / portrait.aspectRatio, radX, 1e-5f)
        assertEquals(radius, radY, 1e-5f)
    }
}

