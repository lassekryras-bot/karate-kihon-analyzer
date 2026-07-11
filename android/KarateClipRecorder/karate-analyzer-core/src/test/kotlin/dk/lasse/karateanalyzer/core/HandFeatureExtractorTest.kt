package dk.lasse.karateanalyzer.core

import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class HandFeatureExtractorTest {
    private val extractor = HandFeatureExtractor()

    @Test fun pointGeometryCalculatesAnglesAndRejectsDegenerateValues() {
        assertEquals(0f, Point3(1f, 0f, 0f).dot(Point3(0f, 1f, 0f))!!, 0.001f)
        assertEquals(5f, Point3(3f, 4f, 0f).magnitude()!!, 0.001f)
        assertEquals(90f, angleBetweenThreePoints(Point3(1f, 0f, 0f), Point3(0f, 0f, 0f), Point3(0f, 1f, 0f))!!, 0.001f)
        assertNull(Point3(0f, 0f, 0f).normalized())
        assertNull(safeDivide(1f, 0f))
        assertEquals(Point3(2f, 3f, 4f), midpoint(Point3(1f, 2f, 3f), Point3(3f, 4f, 5f)))
        assertNull(midpoint(null, Point3(3f, 4f, 5f)))
        assertNull(midpoint(Point3(Float.NaN, 2f, 3f), Point3(3f, 4f, 5f)))
        assertNull(midpoint(Point3(Float.POSITIVE_INFINITY, 2f, 3f), Point3(3f, 4f, 5f)))
    }

    @Test fun openPalmHasHighExtensionAndOrthogonalPalmSystem() {
        val features = extractor.extract(openHand(Handedness.RIGHT))
        assertEquals(175f, features.index.pipAngleDegrees!!, 1f)
        assertEquals(175f, features.index.dipAngleDegrees!!, 1f)
        assertTrue(features.openPalmScore!! > 0.9f)
        assertTrue(features.index.extensionScore!! > 0.9f)
        assertOrthonormal(features.palmCoordinateSystem!!)
        assertFinite(features)
    }

    @Test fun deliberatelySkewedPalmSystemIsOrthonormal() {
        val palm = extractor.extract(openHand(Handedness.RIGHT, skewPalm = true)).palmCoordinateSystem!!
        assertOrthonormal(palm)
    }

    @Test fun curledFingersHaveHighCurlAndAggregateCurl() {
        val features = extractor.extract(openHand(Handedness.RIGHT, pipAngle = 70f, dipAngle = 70f))
        assertEquals(70f, features.index.pipAngleDegrees!!, 1f)
        assertEquals(70f, features.index.dipAngleDegrees!!, 1f)
        assertTrue(features.fourFingerCurlScore!! > 0.9f)
        assertTrue(features.index.curlScore!! > 0.9f)
        assertTrue(features.openPalmScore!! < 0.1f)
    }

    @Test fun partialBentFingersProduceMiddleScores() {
        val features = extractor.extract(openHand(Handedness.RIGHT, pipAngle = 125f, dipAngle = 125f))
        assertEquals(125f, features.index.pipAngleDegrees!!, 1f)
        assertEquals(125f, features.index.dipAngleDegrees!!, 1f)
        assertTrue(features.index.curlScore!! in 0.25f..0.75f)
        assertTrue(features.index.extensionScore!! in 0.25f..0.75f)
    }

    @Test fun translationAndScaleInvariantRatiosAndScores() {
        val base = extractor.extract(openHand(Handedness.RIGHT))
        val moved = extractor.extract(openHand(Handedness.RIGHT, scale = 3f, offset = Point3(10f, -7f, 2f)))
        assertEquals(base.index.tipToMcpRatio!!, moved.index.tipToMcpRatio!!, 0.001f)
        assertEquals(base.openPalmScore!!, moved.openPalmScore!!, 0.001f)
    }

    @Test fun mirroredHandsProduceComparableScores() {
        val right = extractor.extract(openHand(Handedness.RIGHT, pipAngle = 125f, dipAngle = 125f))
        val left = extractor.extract(openHand(Handedness.LEFT, pipAngle = 125f, dipAngle = 125f))
        assertEquals(right.fourFingerCurlScore!!, left.fourFingerCurlScore!!, 0.001f)
        assertEquals(right.openPalmScore!!, left.openPalmScore!!, 0.001f)
    }

    @Test fun thumbCrossingUsesAnatomicalPalmAxisForAllHandednessValues() {
        val rightExtended = extractor.extract(openHand(Handedness.RIGHT, thumbCrossing = false)).thumb
        val rightCrossed = extractor.extract(openHand(Handedness.RIGHT, thumbCrossing = true)).thumb
        val leftExtended = extractor.extract(openHand(Handedness.LEFT, thumbCrossing = false)).thumb
        val leftCrossed = extractor.extract(openHand(Handedness.LEFT, thumbCrossing = true)).thumb
        val unknownExtended = extractor.extract(openHand(Handedness.UNKNOWN, thumbCrossing = false)).thumb
        val unknownCrossed = extractor.extract(openHand(Handedness.UNKNOWN, thumbCrossing = true)).thumb
        assertEquals(false, rightExtended.crossesPalmAxis)
        assertEquals(true, rightCrossed.crossesPalmAxis)
        assertEquals(rightExtended.crossesPalmAxis, leftExtended.crossesPalmAxis)
        assertEquals(rightCrossed.crossesPalmAxis, leftCrossed.crossesPalmAxis)
        assertEquals(false, unknownExtended.crossesPalmAxis)
        assertEquals(true, unknownCrossed.crossesPalmAxis)
        assertNotNull(rightExtended.tipToIndexMcpRatio)
    }

    @Test fun missingThumbTipProducesNullThumbTipMeasurements() {
        val features = extractor.extract(openHand(Handedness.RIGHT, missing = setOf(HandLandmarkId.THUMB_TIP)))
        assertNull(features.thumb.ipAngleDegrees)
        assertNull(features.thumb.tipToPalmRatio)
        assertNull(features.thumb.crossesPalmAxis)
    }

    @Test fun missingOneFingerJointNullsDependentMeasurementsWithoutCrashing() {
        val features = extractor.extract(openHand(Handedness.RIGHT, missing = setOf(HandLandmarkId.INDEX_PIP)))
        assertNull(features.index.pipAngleDegrees)
        assertNull(features.index.mcpAngleDegrees)
        assertNotNull(features.middle.extensionScore)
    }

    @Test fun qualityDecreasesForPredictedAndInterpolatedInputs() {
        val observed = extractor.extract(openHand(Handedness.RIGHT))
        val predicted = extractor.extract(openHand(Handedness.RIGHT, source = LandmarkSource.PREDICTED))
        val interpolated = extractor.extract(openHand(Handedness.RIGHT, source = LandmarkSource.INTERPOLATED))
        assertTrue(predicted.dataQuality < observed.dataQuality)
        assertTrue(interpolated.dataQuality < observed.dataQuality)
        assertEquals(1f, predicted.estimatedLandmarkRatio, 0.001f)
    }

    @Test fun aggregateRequiresConfiguredMinimumUsableFingers() {
        val strict = HandFeatureExtractor(HandFeatureExtractorConfiguration(minimumAggregateFingerCount = 4))
        val features = strict.extract(openHand(Handedness.RIGHT, missing = setOf(HandLandmarkId.INDEX_PIP)))
        assertNull(features.openPalmScore)
        assertNull(features.fourFingerCurlScore)
    }

    @Test fun degeneratePalmWidthLeavesNormalizedMeasurementsNull() {
        val features = extractor.extract(openHand(Handedness.RIGHT, scale = 0f))
        assertNull(features.palmCoordinateSystem)
        assertNull(features.palmWidth)
        assertNull(features.index.tipToPalmRatio)
    }

    @Test fun noisyButValidGeometryDoesNotProduceNanOrInfinity() {
        assertFinite(extractor.extract(openHand(Handedness.RIGHT, noise = 0.03f)))
    }

    private fun assertFinite(features: HandFeatures) {
        val floats = listOfNotNull(features.palmWidth, features.index.mcpAngleDegrees, features.index.pipAngleDegrees, features.index.dipAngleDegrees, features.index.curlScore, features.openPalmScore, features.fourFingerCurlScore, features.dataQuality)
        assertTrue(floats.all { it.isFinite() })
    }

    private fun assertOrthonormal(palm: PalmCoordinateSystem) {
        assertEquals(1f, palm.xAxis.magnitude()!!, 0.001f)
        assertEquals(1f, palm.yAxis.magnitude()!!, 0.001f)
        assertEquals(1f, palm.zAxis!!.magnitude()!!, 0.001f)
        assertEquals(0f, palm.xAxis.dot(palm.yAxis)!!, 0.001f)
        assertEquals(0f, palm.xAxis.dot(palm.zAxis)!!, 0.001f)
        assertEquals(0f, palm.yAxis.dot(palm.zAxis)!!, 0.001f)
        val cross = palm.xAxis.cross(palm.yAxis)!!.normalized()!!
        assertTrue(cross.dot(palm.zAxis)!! > 0.999f)
    }

    private fun openHand(handedness: Handedness, pipAngle: Float = 175f, dipAngle: Float = 175f, scale: Float = 1f, offset: Point3 = Point3(0f, 0f, 0f), thumbCrossing: Boolean = false, missing: Set<HandLandmarkId> = emptySet(), source: LandmarkSource = LandmarkSource.OBSERVED, noise: Float = 0f, skewPalm: Boolean = false): TrackedHandFrame {
        val mirror = if (handedness == Handedness.LEFT) -1f else 1f
        val map = mutableMapOf<HandLandmarkId, Point3>()
        map[HandLandmarkId.WRIST] = p(0f, 0f, scale, offset, mirror)
        finger(map, HandLandmarkId.INDEX_MCP, HandLandmarkId.INDEX_PIP, HandLandmarkId.INDEX_DIP, HandLandmarkId.INDEX_TIP, -0.6f, 1f, pipAngle, dipAngle, scale, offset, mirror)
        finger(map, HandLandmarkId.MIDDLE_MCP, HandLandmarkId.MIDDLE_PIP, HandLandmarkId.MIDDLE_DIP, HandLandmarkId.MIDDLE_TIP, -0.2f, if (skewPalm) 1.25f else 1f, pipAngle, dipAngle, scale, offset, mirror)
        finger(map, HandLandmarkId.RING_MCP, HandLandmarkId.RING_PIP, HandLandmarkId.RING_DIP, HandLandmarkId.RING_TIP, 0.2f, if (skewPalm) 0.95f else 1f, pipAngle, dipAngle, scale, offset, mirror)
        finger(map, HandLandmarkId.LITTLE_MCP, HandLandmarkId.LITTLE_PIP, HandLandmarkId.LITTLE_DIP, HandLandmarkId.LITTLE_TIP, 0.6f, if (skewPalm) 0.75f else 1f, pipAngle, dipAngle, scale, offset, mirror)
        val tx = if (thumbCrossing) 0.45f else -1.1f
        map[HandLandmarkId.THUMB_CMC] = p(-0.75f, 0.45f, scale, offset, mirror)
        map[HandLandmarkId.THUMB_MCP] = p(-0.95f, 0.9f, scale, offset, mirror)
        map[HandLandmarkId.THUMB_IP] = p((tx - 0.2f), 1.1f, scale, offset, mirror)
        map[HandLandmarkId.THUMB_TIP] = p(tx, 1.15f, scale, offset, mirror)
        val landmarks = HandLandmarkId.entries.associateWith { id ->
            val base = map[id]?.let { Point3(it.x + noise * (id.ordinal % 3), it.y - noise * (id.ordinal % 2), it.z) }
            if (id in missing || base == null) LandmarkSample(null, 0f, LandmarkSource.MISSING) else LandmarkSample(base, 1f, source)
        }
        return TrackedHandFrame(10L, handedness, landmarks)
    }

    private fun finger(map: MutableMap<HandLandmarkId, Point3>, mcp: HandLandmarkId, pip: HandLandmarkId, dip: HandLandmarkId, tip: HandLandmarkId, x: Float, mcpY: Float, pipAngle: Float, dipAngle: Float, scale: Float, offset: Point3, mirror: Float) {
        val firstLength = 0.7f
        val secondLength = 0.6f
        val thirdLength = 0.5f
        val firstDirection = 90.0
        val secondDirection = 270.0 - pipAngle.toDouble()
        val thirdDirection = secondDirection + 180.0 - dipAngle.toDouble()
        val mcpPoint = localPoint(x, mcpY)
        val pipPoint = mcpPoint + vector(firstLength, firstDirection)
        val dipPoint = pipPoint + vector(secondLength, secondDirection)
        val tipPoint = dipPoint + vector(thirdLength, thirdDirection)
        map[mcp] = transform(mcpPoint, scale, offset, mirror)
        map[pip] = transform(pipPoint, scale, offset, mirror)
        map[dip] = transform(dipPoint, scale, offset, mirror)
        map[tip] = transform(tipPoint, scale, offset, mirror)
    }

    private fun localPoint(x: Float, y: Float) = Point3(x, y, 0f)

    private fun vector(length: Float, degrees: Double): Point3 {
        val radians = degrees * PI / 180.0
        return Point3((cos(radians) * length).toFloat(), (sin(radians) * length).toFloat(), 0f)
    }

    private fun p(x: Float, y: Float, scale: Float, offset: Point3, mirror: Float) = transform(localPoint(x, y), scale, offset, mirror)

    private fun transform(point: Point3, scale: Float, offset: Point3, mirror: Float) = Point3(point.x * mirror * scale + offset.x, point.y * scale + offset.y, offset.z)
}
