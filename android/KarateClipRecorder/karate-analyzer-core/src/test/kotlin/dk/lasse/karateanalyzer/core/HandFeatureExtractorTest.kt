package dk.lasse.karateanalyzer.core

import kotlin.math.abs
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
    }

    @Test fun openPalmHasHighExtensionAndOrthogonalPalmSystem() {
        val features = extractor.extract(openHand(Handedness.RIGHT))
        assertTrue(features.openPalmScore!! > 0.9f)
        assertTrue(features.index.extensionScore!! > 0.9f)
        val palm = features.palmCoordinateSystem!!
        assertTrue(abs(palm.xAxis.dot(palm.yAxis)!!) < 0.001f)
        assertNotNull(palm.zAxis)
        assertFinite(features)
    }

    @Test fun curledFingersHaveHighCurlAndAggregateCurl() {
        val features = extractor.extract(openHand(Handedness.RIGHT, curl = 1f))
        assertTrue(features.fourFingerCurlScore!! > 0.8f)
        assertTrue(features.index.curlScore!! > 0.8f)
        assertTrue(features.openPalmScore!! < 0.2f)
    }

    @Test fun partialBentFingersProduceMiddleScores() {
        val features = extractor.extract(openHand(Handedness.RIGHT, curl = 0.5f))
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
        val right = extractor.extract(openHand(Handedness.RIGHT, curl = 0.5f))
        val left = extractor.extract(openHand(Handedness.LEFT, curl = 0.5f))
        assertEquals(right.fourFingerCurlScore!!, left.fourFingerCurlScore!!, 0.001f)
        assertEquals(right.openPalmScore!!, left.openPalmScore!!, 0.001f)
    }

    @Test fun thumbExtendedAndCrossingAreSeparated() {
        val extended = extractor.extract(openHand(Handedness.RIGHT, thumbCrossing = false)).thumb
        val crossed = extractor.extract(openHand(Handedness.RIGHT, thumbCrossing = true)).thumb
        assertEquals(false, extended.crossesPalmAxis)
        assertEquals(true, crossed.crossesPalmAxis)
        assertNotNull(extended.tipToIndexMcpRatio)
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

    private fun openHand(handedness: Handedness, curl: Float = 0f, scale: Float = 1f, offset: Point3 = Point3(0f, 0f, 0f), thumbCrossing: Boolean = false, missing: Set<HandLandmarkId> = emptySet(), source: LandmarkSource = LandmarkSource.OBSERVED, noise: Float = 0f): TrackedHandFrame {
        val mirror = if (handedness == Handedness.LEFT) -1f else 1f
        val map = mutableMapOf<HandLandmarkId, Point3>()
        map[HandLandmarkId.WRIST] = p(0f, 0f, scale, offset, mirror)
        finger(map, HandLandmarkId.INDEX_MCP, HandLandmarkId.INDEX_PIP, HandLandmarkId.INDEX_DIP, HandLandmarkId.INDEX_TIP, -0.6f, curl, scale, offset, mirror)
        finger(map, HandLandmarkId.MIDDLE_MCP, HandLandmarkId.MIDDLE_PIP, HandLandmarkId.MIDDLE_DIP, HandLandmarkId.MIDDLE_TIP, -0.2f, curl, scale, offset, mirror)
        finger(map, HandLandmarkId.RING_MCP, HandLandmarkId.RING_PIP, HandLandmarkId.RING_DIP, HandLandmarkId.RING_TIP, 0.2f, curl, scale, offset, mirror)
        finger(map, HandLandmarkId.LITTLE_MCP, HandLandmarkId.LITTLE_PIP, HandLandmarkId.LITTLE_DIP, HandLandmarkId.LITTLE_TIP, 0.6f, curl, scale, offset, mirror)
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

    private fun finger(map: MutableMap<HandLandmarkId, Point3>, mcp: HandLandmarkId, pip: HandLandmarkId, dip: HandLandmarkId, tip: HandLandmarkId, x: Float, curl: Float, scale: Float, offset: Point3, mirror: Float) {
        map[mcp] = p(x, 1f, scale, offset, mirror)
        map[pip] = p(x, 1.7f, scale, offset, mirror)
        map[dip] = p(x + 0.45f * curl, 2.3f - 0.35f * curl, scale, offset, mirror)
        map[tip] = p(x + 0.85f * curl, 2.9f - 1.0f * curl, scale, offset, mirror)
    }

    private fun p(x: Float, y: Float, scale: Float, offset: Point3, mirror: Float) = Point3(x * mirror * scale + offset.x, y * scale + offset.y, offset.z)
}
