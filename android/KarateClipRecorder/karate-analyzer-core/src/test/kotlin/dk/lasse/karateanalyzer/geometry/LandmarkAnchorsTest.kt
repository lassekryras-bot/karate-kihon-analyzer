package dk.lasse.karateanalyzer.geometry

import dk.lasse.karateanalyzer.core.Point3
import dk.lasse.karateanalyzer.core.PoseFrame
import dk.lasse.karateanalyzer.core.PoseLandmarkId
import dk.lasse.karateanalyzer.core.PoseLandmarkSample
import org.junit.Assert.*
import org.junit.Test

class LandmarkAnchorsTest {

    private fun sample(x: Float, y: Float, conf: Float = 0.9f) =
        PoseLandmarkSample(
            position = Point3(x, y, 0f),
            visibility = conf,
            presence = conf,
            source = dk.lasse.karateanalyzer.core.LandmarkSource.OBSERVED,
        )

    @Test
    fun bilateralTorsoExtractionCompleteSample() {
        val landmarks = mapOf(
            PoseLandmarkId.LEFT_SHOULDER to sample(0.4f, 0.2f),
            PoseLandmarkId.RIGHT_SHOULDER to sample(0.6f, 0.2f),
            PoseLandmarkId.LEFT_HIP to sample(0.42f, 0.6f),
            PoseLandmarkId.RIGHT_HIP to sample(0.58f, 0.6f),
        )
        val frame = PoseFrame(timestampMs = 100, landmarks = landmarks)

        val torso = LandmarkAnchors.extractTorsoAnchors(frame, confidenceThreshold = 0.55f)
        assertTrue(torso.isTorsoValid)
        assertNotNull(torso.shoulderCenter)
        assertNotNull(torso.hipCenter)
        assertNotNull(torso.torsoCenter)

        assertEquals(0.5f, torso.shoulderCenter!!.x, 1e-5f)
        assertEquals(0.2f, torso.shoulderCenter!!.y, 1e-5f)
        assertEquals(0.5f, torso.hipCenter!!.x, 1e-5f)
        assertEquals(0.6f, torso.hipCenter!!.y, 1e-5f)
        assertEquals(0.5f, torso.torsoCenter!!.x, 1e-5f)
        assertEquals(0.4f, torso.torsoCenter!!.y, 1e-5f)
    }

    @Test
    fun missingShoulderOrHipFailsTorso() {
        // Missing right shoulder
        val frameMissingRightShoulder = PoseFrame(
            timestampMs = 100,
            landmarks = mapOf(
                PoseLandmarkId.LEFT_SHOULDER to sample(0.4f, 0.2f),
                PoseLandmarkId.LEFT_HIP to sample(0.42f, 0.6f),
                PoseLandmarkId.RIGHT_HIP to sample(0.58f, 0.6f),
            ),
        )
        val torso1 = LandmarkAnchors.extractTorsoAnchors(frameMissingRightShoulder)
        assertFalse(torso1.isTorsoValid)
        assertNull(torso1.shoulderCenter)
        assertEquals("missing_shoulder_landmarks", torso1.failureReason)

        // Missing left hip
        val frameMissingLeftHip = PoseFrame(
            timestampMs = 100,
            landmarks = mapOf(
                PoseLandmarkId.LEFT_SHOULDER to sample(0.4f, 0.2f),
                PoseLandmarkId.RIGHT_SHOULDER to sample(0.6f, 0.2f),
                PoseLandmarkId.RIGHT_HIP to sample(0.58f, 0.6f),
            ),
        )
        val torso2 = LandmarkAnchors.extractTorsoAnchors(frameMissingLeftHip)
        assertFalse(torso2.isTorsoValid)
        assertNull(torso2.hipCenter)
        assertEquals("missing_hip_landmarks", torso2.failureReason)
    }

    @Test
    fun lowConfidenceLandmarkRejected() {
        val frameLowConf = PoseFrame(
            timestampMs = 100,
            landmarks = mapOf(
                PoseLandmarkId.LEFT_SHOULDER to sample(0.4f, 0.2f, conf = 0.3f), // Below 0.55
                PoseLandmarkId.RIGHT_SHOULDER to sample(0.6f, 0.2f, conf = 0.9f),
                PoseLandmarkId.LEFT_HIP to sample(0.42f, 0.6f, conf = 0.9f),
                PoseLandmarkId.RIGHT_HIP to sample(0.58f, 0.6f, conf = 0.9f),
            ),
        )
        val torso = LandmarkAnchors.extractTorsoAnchors(frameLowConf, confidenceThreshold = 0.55f)
        assertFalse(torso.isTorsoValid)
    }

    @Test
    fun headAnchorStrategies() {
        val landmarks = mapOf(
            PoseLandmarkId.LEFT_EAR to sample(0.45f, 0.12f),
            PoseLandmarkId.RIGHT_EAR to sample(0.55f, 0.12f),
            PoseLandmarkId.LEFT_EYE to sample(0.47f, 0.10f),
            PoseLandmarkId.RIGHT_EYE to sample(0.53f, 0.10f),
        )
        val frame = PoseFrame(timestampMs = 100, landmarks = landmarks)

        val earMidpoint = LandmarkAnchors.extractHeadEarMidpointV1(frame)
        assertNotNull(earMidpoint)
        assertEquals(0.5f, earMidpoint!!.x, 1e-5f)
        assertEquals(0.12f, earMidpoint.y, 1e-5f)

        val composite = LandmarkAnchors.extractHeadEyesEarsCompositeV1(frame)
        assertNotNull(composite)
        assertEquals(0.5f, composite!!.x, 1e-5f)
        assertEquals(0.11f, composite.y, 1e-5f)
    }

    @Test
    fun temporalMedianAggregation() {
        val points = listOf(
            SourceNormalizedPoint(0.1f, 0.5f),
            SourceNormalizedPoint(0.3f, 0.7f),
            SourceNormalizedPoint(0.2f, 0.9f),
        )
        val medianPoint = LandmarkAnchors.aggregateTemporalMedian(points)
        assertNotNull(medianPoint)
        assertEquals(0.2f, medianPoint!!.x, 1e-5f)
        assertEquals(0.7f, medianPoint.y, 1e-5f)
    }
}
