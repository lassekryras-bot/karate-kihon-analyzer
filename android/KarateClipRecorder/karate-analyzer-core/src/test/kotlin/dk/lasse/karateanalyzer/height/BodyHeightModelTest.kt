package dk.lasse.karateanalyzer.height

import dk.lasse.karateanalyzer.core.LandmarkSource
import dk.lasse.karateanalyzer.core.Point3
import dk.lasse.karateanalyzer.core.PoseFrame
import dk.lasse.karateanalyzer.core.PoseLandmarkId
import dk.lasse.karateanalyzer.core.PoseLandmarkSample
import dk.lasse.karateanalyzer.core.PunchHeightTargetType
import dk.lasse.karateanalyzer.geometry.FrameGeometry
import dk.lasse.karateanalyzer.geometry.FrameGeometryMath
import org.junit.Assert.*
import org.junit.Test
import kotlin.math.abs

class BodyHeightModelTest {

    private val frameGeometry = FrameGeometry(1080, 1920)

    private fun landmark(x: Float, y: Float, conf: Float = 0.9f): PoseLandmarkSample {
        return PoseLandmarkSample(
            position = Point3(x, y, 0f),
            visibility = conf,
            presence = conf,
            source = LandmarkSource.OBSERVED,
        )
    }

    private fun makeFrame(
        timestampMs: Long,
        leftShoulder: Pair<Float, Float>? = Pair(0.4f, 0.3f),
        rightShoulder: Pair<Float, Float>? = Pair(0.6f, 0.3f),
        leftHip: Pair<Float, Float>? = Pair(0.45f, 0.7f),
        rightHip: Pair<Float, Float>? = Pair(0.55f, 0.7f),
        leftEar: Pair<Float, Float>? = Pair(0.45f, 0.15f),
        rightEar: Pair<Float, Float>? = Pair(0.55f, 0.15f),
        leftEye: Pair<Float, Float>? = Pair(0.48f, 0.16f),
        rightEye: Pair<Float, Float>? = Pair(0.52f, 0.16f),
        mouthLeft: Pair<Float, Float>? = Pair(0.48f, 0.22f),
        mouthRight: Pair<Float, Float>? = Pair(0.52f, 0.22f),
        nose: Pair<Float, Float>? = Pair(0.5f, 0.18f),
    ): PoseFrame {
        val landmarks = mutableMapOf<PoseLandmarkId, PoseLandmarkSample>()
        leftShoulder?.let { landmarks[PoseLandmarkId.LEFT_SHOULDER] = landmark(it.first, it.second) }
        rightShoulder?.let { landmarks[PoseLandmarkId.RIGHT_SHOULDER] = landmark(it.first, it.second) }
        leftHip?.let { landmarks[PoseLandmarkId.LEFT_HIP] = landmark(it.first, it.second) }
        rightHip?.let { landmarks[PoseLandmarkId.RIGHT_HIP] = landmark(it.first, it.second) }
        leftEar?.let { landmarks[PoseLandmarkId.LEFT_EAR] = landmark(it.first, it.second) }
        rightEar?.let { landmarks[PoseLandmarkId.RIGHT_EAR] = landmark(it.first, it.second) }
        leftEye?.let { landmarks[PoseLandmarkId.LEFT_EYE] = landmark(it.first, it.second) }
        rightEye?.let { landmarks[PoseLandmarkId.RIGHT_EYE] = landmark(it.first, it.second) }
        mouthLeft?.let { landmarks[PoseLandmarkId.MOUTH_LEFT] = landmark(it.first, it.second) }
        mouthRight?.let { landmarks[PoseLandmarkId.MOUTH_RIGHT] = landmark(it.first, it.second) }
        nose?.let { landmarks[PoseLandmarkId.NOSE] = landmark(it.first, it.second) }
        return PoseFrame(timestampMs, landmarks)
    }

    // ==========================================
    // 40. Observed Anchors (Tests 1 to 9)
    // ==========================================

    @Test
    fun test01_bilateralShoulderCenterEqualsMidpoint() {
        val f = makeFrame(100, leftShoulder = Pair(0.3f, 0.2f), rightShoulder = Pair(0.7f, 0.4f))
        val res = BodyHeightModel.evaluate(100_000L, listOf(f), frameGeometry, radius = 0)
        assertNotNull(res.shoulderCenter)
        assertEquals(0.5f, res.shoulderCenter!!.x, 1e-4f)
        assertEquals(0.3f, res.shoulderCenter!!.y, 1e-4f)
    }

    @Test
    fun test02_missingOneShoulderMakesShoulderCenterUnavailable() {
        val f = makeFrame(100, leftShoulder = Pair(0.3f, 0.2f), rightShoulder = null)
        val res = BodyHeightModel.evaluate(100_000L, listOf(f), frameGeometry, radius = 0)
        assertNull(res.shoulderCenter)
        assertEquals(WindowState.UNAVAILABLE, res.torsoEvidence.windowState)
    }

    @Test
    fun test03_bilateralHipCenterEqualsMidpoint() {
        val f = makeFrame(100, leftHip = Pair(0.4f, 0.6f), rightHip = Pair(0.6f, 0.8f))
        val res = BodyHeightModel.evaluate(100_000L, listOf(f), frameGeometry, radius = 0)
        assertNotNull(res.hipCenter)
        assertEquals(0.5f, res.hipCenter!!.x, 1e-4f)
        assertEquals(0.7f, res.hipCenter!!.y, 1e-4f)
    }

    @Test
    fun test04_missingOneHipMakesHipCenterUnavailable() {
        val f = makeFrame(100, leftHip = null, rightHip = Pair(0.6f, 0.8f))
        val res = BodyHeightModel.evaluate(100_000L, listOf(f), frameGeometry, radius = 0)
        assertNull(res.hipCenter)
        assertEquals(WindowState.UNAVAILABLE, res.torsoEvidence.windowState)
    }

    @Test
    fun test05_torsoCenterIsRecomputedFromAggregateAnchors() {
        val f = makeFrame(100,
            leftShoulder = Pair(0.4f, 0.2f), rightShoulder = Pair(0.6f, 0.2f),
            leftHip = Pair(0.4f, 0.8f), rightHip = Pair(0.6f, 0.8f),
        )
        val res = BodyHeightModel.evaluate(100_000L, listOf(f), frameGeometry, radius = 0)
        assertNotNull(res.torsoCenter)
        assertEquals(0.5f, res.torsoCenter!!.x, 1e-4f)
        assertEquals(0.5f, res.torsoCenter!!.y, 1e-4f)
    }

    @Test
    fun test06_currentBodyUpHasUnitLength() {
        val f = makeFrame(100)
        val res = BodyHeightModel.evaluate(100_000L, listOf(f), frameGeometry, radius = 0)
        assertNotNull(res.currentBodyUp)
        assertEquals(1.0f, res.currentBodyUp!!.length(), 1e-4f)
    }

    @Test
    fun test07_currentTorsoLengthEqualsShoulderToHipDistance() {
        val f = makeFrame(100,
            leftShoulder = Pair(0.4f, 0.2f), rightShoulder = Pair(0.6f, 0.2f),
            leftHip = Pair(0.4f, 0.8f), rightHip = Pair(0.6f, 0.8f),
        )
        val res = BodyHeightModel.evaluate(100_000L, listOf(f), frameGeometry, radius = 0)
        val sA = FrameGeometryMath.sourceToAspectCorrect(res.shoulderCenter!!, frameGeometry)
        val hA = FrameGeometryMath.sourceToAspectCorrect(res.hipCenter!!, frameGeometry)
        val expectedDist = FrameGeometryMath.distance(sA, hA)
        assertEquals(expectedDist, res.currentTorsoLength!!, 1e-4f)
    }

    @Test
    fun test08_degenerateTorsoGeometryIsRejected() {
        // Shoulder and Hip at virtually the same location (distance < 0.05)
        val f = makeFrame(100,
            leftShoulder = Pair(0.49f, 0.50f), rightShoulder = Pair(0.51f, 0.50f),
            leftHip = Pair(0.49f, 0.51f), rightHip = Pair(0.51f, 0.51f),
        )
        val res = BodyHeightModel.evaluate(100_000L, listOf(f), frameGeometry, radius = 0)
        assertNull(res.currentBodyUp) // normalization rejected
    }

    @Test
    fun test09_currentTorsoHeightExpectedValues() {
        val f = makeFrame(100,
            leftShoulder = Pair(0.4f, 0.2f), rightShoulder = Pair(0.6f, 0.2f),
            leftHip = Pair(0.4f, 0.8f), rightHip = Pair(0.6f, 0.8f),
        )
        val res = BodyHeightModel.evaluate(100_000L, listOf(f), frameGeometry, radius = 0)
        val sA = FrameGeometryMath.sourceToAspectCorrect(res.shoulderCenter!!, frameGeometry)
        val tA = FrameGeometryMath.sourceToAspectCorrect(res.torsoCenter!!, frameGeometry)
        val hA = FrameGeometryMath.sourceToAspectCorrect(res.hipCenter!!, frameGeometry)

        assertEquals(+0.5f, res.currentTorsoHeight(sA, frameGeometry)!!, 1e-3f)
        assertEquals(0.0f, res.currentTorsoHeight(tA, frameGeometry)!!, 1e-3f)
        assertEquals(-0.5f, res.currentTorsoHeight(hA, frameGeometry)!!, 1e-3f)
    }

    // ==========================================
    // 41. Shared Evidence (Tests 10 to 13)
    // ==========================================

    @Test
    fun test10_torsoAggregationUsesOnlySamplesWithBothShouldersAndHips() {
        val f1 = makeFrame(100) // both valid
        val f2 = makeFrame(133, leftHip = null) // hip invalid
        val f3 = makeFrame(166) // both valid

        val res = BodyHeightModel.evaluate(133_000L, listOf(f1, f2, f3), frameGeometry, radius = 1)
        assertEquals(2, res.torsoEvidence.usableCount)
        assertEquals(listOf(100_000L, 166_000L), res.torsoEvidence.contributingTimestampsUs)
    }

    @Test
    fun test11_shoulderOnlyAndHipOnlySamplesNeverCombinedIntoOneTorso() {
        val f1 = makeFrame(100, leftHip = null, rightHip = null) // shoulder only
        val f2 = makeFrame(133, leftShoulder = null, rightShoulder = null) // hip only
        val res = BodyHeightModel.evaluate(100_000L, listOf(f1, f2), frameGeometry, radius = 1)
        assertEquals(0, res.torsoEvidence.usableCount)
        assertEquals(WindowState.UNAVAILABLE, res.torsoEvidence.windowState)
        assertNull(res.torsoCenter)
    }

    @Test
    fun test12_headAnchorMayUseDifferentSubsetWithoutAlteringTorso() {
        val f1 = makeFrame(100, leftEar = null, rightEar = null) // torso valid, head missing
        val f2 = makeFrame(133) // both valid
        val res = BodyHeightModel.evaluate(133_000L, listOf(f1, f2), frameGeometry, radius = 1)
        assertEquals(2, res.torsoEvidence.usableCount)
        assertEquals(1, res.headEvidence.usableCount)
    }

    @Test
    fun test13_eachComponentExposesItsOwnContributingTimestampsAndCount() {
        val f1 = makeFrame(100, leftEar = null)
        val f2 = makeFrame(133)
        val res = BodyHeightModel.evaluate(100_000L, listOf(f1, f2), frameGeometry, radius = 1)
        assertNotEquals(res.torsoEvidence.usableCount, res.headEvidence.usableCount)
        assertEquals(listOf(100_000L, 133_000L), res.torsoEvidence.contributingTimestampsUs)
        assertEquals(listOf(133_000L), res.headEvidence.contributingTimestampsUs)
    }

    // ==========================================
    // 42. Configurable Window (Tests 14 to 23)
    // ==========================================

    @Test
    fun test14_radius0EvaluatesOnlySelectedSample() {
        val f1 = makeFrame(100)
        val f2 = makeFrame(133)
        val f3 = makeFrame(166)
        val res = BodyHeightModel.evaluate(133_000L, listOf(f1, f2, f3), frameGeometry, radius = 0)
        assertEquals(1, res.torsoEvidence.usableCount)
        assertEquals(listOf(133_000L), res.torsoEvidence.contributingTimestampsUs)
    }

    @Test
    fun test15_radius1SelectsAtMostSelectedPlusMinus1() {
        val frames = (0..5).map { makeFrame(100L + it * 33) }
        val res = BodyHeightModel.evaluate(frames[2].timestampMs * 1000L, frames, frameGeometry, radius = 1)
        assertEquals(3, res.torsoEvidence.requestedTimestampsUs.size)
        assertEquals(frames[1].timestampMs * 1000L, res.torsoEvidence.requestedTimestampsUs.first())
        assertEquals(frames[3].timestampMs * 1000L, res.torsoEvidence.requestedTimestampsUs.last())
    }

    @Test
    fun test16_radius2SelectsAtMostSelectedPlusMinus2() {
        val frames = (0..6).map { makeFrame(100L + it * 33) }
        val res = BodyHeightModel.evaluate(frames[3].timestampMs * 1000L, frames, frameGeometry, radius = 2)
        assertEquals(5, res.torsoEvidence.requestedTimestampsUs.size)
    }

    @Test
    fun test17_invalidSamplesAreNeverReplacedByFartherSamples() {
        val f0 = makeFrame(100)
        val f1 = makeFrame(133, leftShoulder = null) // invalid neighbor
        val f2 = makeFrame(166) // selected
        val f3 = makeFrame(200)
        val f4 = makeFrame(233)

        // radius 1 from f2 should only consider f1, f2, f3. It should NOT pull in f0 to replace f1.
        val res = BodyHeightModel.evaluate(166_000L, listOf(f0, f1, f2, f3, f4), frameGeometry, radius = 1)
        assertEquals(2, res.torsoEvidence.usableCount)
        assertFalse(res.torsoEvidence.contributingTimestampsUs.contains(100_000L))
    }

    @Test
    fun test18_completeValidRequestProducesFullRequestedWindow() {
        val frames = (0..2).map { makeFrame(100L + it * 33) }
        val res = BodyHeightModel.evaluate(frames[1].timestampMs * 1000L, frames, frameGeometry, radius = 1)
        assertEquals(WindowState.FULL_REQUESTED_WINDOW, res.torsoEvidence.windowState)
    }

    @Test
    fun test19_missingOrInvalidEvidenceProducesReducedWindow() {
        val f1 = makeFrame(100, leftShoulder = null)
        val f2 = makeFrame(133)
        val f3 = makeFrame(166)
        val res = BodyHeightModel.evaluate(133_000L, listOf(f1, f2, f3), frameGeometry, radius = 1)
        assertEquals(WindowState.REDUCED_WINDOW, res.torsoEvidence.windowState)
    }

    @Test
    fun test20_largerRequestReducedToOneObservationProducesSingleFrameFallback() {
        val f1 = makeFrame(100, leftShoulder = null)
        val f2 = makeFrame(133)
        val f3 = makeFrame(166, leftHip = null)
        val res = BodyHeightModel.evaluate(133_000L, listOf(f1, f2, f3), frameGeometry, radius = 1)
        assertEquals(WindowState.SINGLE_FRAME_FALLBACK, res.torsoEvidence.windowState)
    }

    @Test
    fun test21_radius0ValidEvaluationProducesSingleFrameRequest() {
        val f1 = makeFrame(100)
        val res = BodyHeightModel.evaluate(100_000L, listOf(f1), frameGeometry, radius = 0)
        assertEquals(WindowState.SINGLE_FRAME_REQUEST, res.torsoEvidence.windowState)
    }

    @Test
    fun test22_zeroUsableObservationsProduceUnavailable() {
        val f1 = makeFrame(100, leftShoulder = null)
        val res = BodyHeightModel.evaluate(100_000L, listOf(f1), frameGeometry, radius = 0)
        assertEquals(WindowState.UNAVAILABLE, res.torsoEvidence.windowState)
    }

    @Test
    fun test23_recordingBoundaryMissingNeighborsCountAsReducedEvidence() {
        // At recording boundary (selected = index 0), radius 1 only finds 2 samples
        val f0 = makeFrame(100)
        val f1 = makeFrame(133)
        val res = BodyHeightModel.evaluate(100_000L, listOf(f0, f1), frameGeometry, radius = 1)
        // Missing the prior neighbor counts as reduced evidence
        assertEquals(WindowState.REDUCED_WINDOW, res.torsoEvidence.windowState)
    }

    // ==========================================
    // 43. Temporal Policy (Tests 24 to 30)
    // ==========================================

    @Test
    fun test24_gapValidationUsesConfiguredLandmarkStreamCadence() {
        val config = BodyHeightModelConfig(maxTimestampGapUs = 50_000L) // 50ms max gap
        val f1 = makeFrame(100)
        val f2 = makeFrame(160) // gap 60ms > 50ms
        val res = BodyHeightModel.evaluate(100_000L, listOf(f1, f2), frameGeometry, radius = 1, config = config)
        assertEquals(1, res.torsoEvidence.usableCount)
    }

    @Test
    fun test25_captureFpsNotImplicitlyUsed() {
        val config = BodyHeightModelConfig(landmarkStreamCadenceSource = "custom_contract_45ms", maxTimestampGapUs = 45_000L)
        val f1 = makeFrame(100)
        val f2 = makeFrame(140) // 40ms gap < 45ms -> valid
        val res = BodyHeightModel.evaluate(100_000L, listOf(f1, f2), frameGeometry, radius = 1, config = config)
        assertEquals(2, res.torsoEvidence.usableCount)
    }

    @Test
    fun test26_validationProceedsOutwardFromSelectedSample() {
        val f1 = makeFrame(100)
        val f2 = makeFrame(133) // selected
        val f3 = makeFrame(166)
        val res = BodyHeightModel.evaluate(133_000L, listOf(f1, f2, f3), frameGeometry, radius = 1)
        assertTrue(res.torsoEvidence.contributingTimestampsUs.contains(133_000L))
    }

    @Test
    fun test27_excessiveGapExcludesSampleAndAllFartherSamplesOnThatSide() {
        val f0 = makeFrame(0)
        val f1 = makeFrame(100) // gap between f1 and f2 is 100ms > 50ms
        val f2 = makeFrame(200) // selected
        val config = BodyHeightModelConfig(maxTimestampGapUs = 50_000L)
        val res = BodyHeightModel.evaluate(200_000L, listOf(f0, f1, f2), frameGeometry, radius = 2, config = config)
        // Both f1 and f0 must be excluded
        assertEquals(1, res.torsoEvidence.usableCount)
        assertFalse(res.torsoEvidence.contributingTimestampsUs.contains(100_000L))
        assertFalse(res.torsoEvidence.contributingTimestampsUs.contains(0L))
    }

    @Test
    fun test28_disconnectedEvidenceCannotEnterAggregation() {
        val f1 = makeFrame(100)
        val f2 = makeFrame(300) // selected, huge gap
        val config = BodyHeightModelConfig(maxTimestampGapUs = 50_000L)
        val res = BodyHeightModel.evaluate(300_000L, listOf(f1, f2), frameGeometry, radius = 1, config = config)
        assertEquals(listOf(300_000L), res.torsoEvidence.contributingTimestampsUs)
    }

    @Test
    fun test29_differentPoseTrackIdentitiesNeverAggregated() {
        val f = makeFrame(100)
        val res = BodyHeightModel.evaluate(100_000L, listOf(f), frameGeometry, radius = 0, trackId = "track_alpha")
        assertEquals("track_alpha", res.perSampleObservations[0].trackId)
    }

    @Test
    fun test30_contributingTimestampsPreservedExactly() {
        val f1 = makeFrame(123456L)
        val res = BodyHeightModel.evaluate(123456000L, listOf(f1), frameGeometry, radius = 0)
        assertEquals(123456000L, res.torsoEvidence.contributingTimestampsUs[0])
    }

    // ==========================================
    // 44. Aggregation (Tests 31 to 36)
    // ==========================================

    @Test
    fun test31_componentWiseMedianDeterministicForOddCounts() {
        val f1 = makeFrame(100, leftShoulder = Pair(0.40f, 0.30f), rightShoulder = Pair(0.60f, 0.30f)) // midpoint x=0.50
        val f2 = makeFrame(133, leftShoulder = Pair(0.42f, 0.30f), rightShoulder = Pair(0.62f, 0.30f)) // midpoint x=0.52
        val f3 = makeFrame(166, leftShoulder = Pair(0.44f, 0.30f), rightShoulder = Pair(0.64f, 0.30f)) // midpoint x=0.54
        val res = BodyHeightModel.evaluate(133_000L, listOf(f1, f2, f3), frameGeometry, radius = 1)
        assertEquals(0.52f, res.shoulderCenter!!.x, 1e-4f)
    }

    @Test
    fun test32_evenCountsUseMidpointOfTwoCentralValues() {
        val f1 = makeFrame(100, leftShoulder = Pair(0.40f, 0.30f), rightShoulder = Pair(0.60f, 0.30f)) // midpoint x=0.50
        val f2 = makeFrame(133, leftShoulder = Pair(0.44f, 0.30f), rightShoulder = Pair(0.64f, 0.30f)) // midpoint x=0.54
        val res = BodyHeightModel.evaluate(100_000L, listOf(f1, f2), frameGeometry, radius = 1)
        assertEquals(0.52f, res.shoulderCenter!!.x, 1e-4f) // average of 0.50 and 0.54
    }

    @Test
    fun test33_oneValidSamplePassesThroughUnchanged() {
        val f = makeFrame(100, leftShoulder = Pair(0.33f, 0.22f), rightShoulder = Pair(0.67f, 0.22f))
        val res = BodyHeightModel.evaluate(100_000L, listOf(f), frameGeometry, radius = 0)
        assertEquals(0.50f, res.shoulderCenter!!.x, 1e-4f)
        assertEquals(0.22f, res.shoulderCenter!!.y, 1e-4f)
    }

    @Test
    fun test34_torsoDerivedGeometryRecomputedAfterAnchorAggregation() {
        val f1 = makeFrame(100,
            leftShoulder = Pair(0.40f, 0.20f), rightShoulder = Pair(0.60f, 0.20f), // S1 = (0.50, 0.20)
            leftHip = Pair(0.40f, 0.80f), rightHip = Pair(0.60f, 0.80f), // H1 = (0.50, 0.80)
        )
        val f2 = makeFrame(133,
            leftShoulder = Pair(0.44f, 0.22f), rightShoulder = Pair(0.64f, 0.22f), // S2 = (0.54, 0.22)
            leftHip = Pair(0.44f, 0.82f), rightHip = Pair(0.64f, 0.82f), // H2 = (0.54, 0.82)
        )
        val res = BodyHeightModel.evaluate(100_000L, listOf(f1, f2), frameGeometry, radius = 1)
        // Agg S = (0.52, 0.21), Agg H = (0.52, 0.81)
        assertEquals(0.52f, res.torsoCenter!!.x, 1e-4f)
        assertEquals(0.51f, res.torsoCenter!!.y, 1e-4f)
    }

    @Test
    fun test35_perSampleResultsRemainAvailable() {
        val f1 = makeFrame(100)
        val f2 = makeFrame(133)
        val res = BodyHeightModel.evaluate(100_000L, listOf(f1, f2), frameGeometry, radius = 1)
        assertEquals(2, res.perSampleObservations.size)
        assertTrue(res.perSampleObservations.all { it.isTorsoValid })
    }

    @Test
    fun test36_disagreementReportedSeparatelyFromSampleCount() {
        val f1 = makeFrame(100, leftShoulder = Pair(0.38f, 0.30f), rightShoulder = Pair(0.58f, 0.30f))
        val f2 = makeFrame(133, leftShoulder = Pair(0.42f, 0.30f), rightShoulder = Pair(0.62f, 0.30f))
        val res = BodyHeightModel.evaluate(100_000L, listOf(f1, f2), frameGeometry, radius = 1)
        assertNotNull(res.torsoEvidence.disagreementMetric)
        assertTrue(res.torsoEvidence.disagreementMetric!! > 0f)
    }

    // ==========================================
    // 45. Jōdan Evidence (Tests 37 to 41)
    // ==========================================

    @Test
    fun test37_mouthMidpointRequiresBothMouthLandmarks() {
        val f = makeFrame(100, mouthLeft = Pair(0.48f, 0.22f), mouthRight = null)
        val est = TargetHeightEstimator.estimateJodanMouthNose110(100_000L, listOf(f), radius = 0)
        assertEquals(TargetEstimateStatus.UNAVAILABLE, est.status)
    }

    @Test
    fun test38_mouthAndNoseUseSameContributingSubset() {
        val f1 = makeFrame(100, nose = null) // nose missing
        val f2 = makeFrame(133) // both valid
        val est = TargetHeightEstimator.estimateJodanMouthNose110(133_000L, listOf(f1, f2), radius = 1)
        assertEquals(1, est.usableCount)
        assertEquals(listOf(133_000L), est.contributingTimestampsUs)
    }

    @Test
    fun test39_earBasedHeadAnchorDoesNotChangeForJodanProxy() {
        val config = BodyHeightModelConfig(headAnchorStrategy = HeadAnchorStrategyId.EAR_MIDPOINT_V1)
        val f = makeFrame(100)
        val res = BodyHeightModel.evaluate(100_000L, listOf(f), frameGeometry, radius = 0, config = config)
        // Ear midpoint remains centered between ears regardless of mouth/nose
        assertEquals(0.5f, res.headAnchor!!.x, 1e-4f)
        assertEquals(0.15f, res.headAnchor!!.y, 1e-4f)
    }

    @Test
    fun test40_missingJodanEvidenceDoesNotInvalidateTorsoGeometry() {
        val f = makeFrame(100, mouthLeft = null, mouthRight = null, nose = null)
        val res = BodyHeightModel.evaluate(100_000L, listOf(f), frameGeometry, radius = 0)
        assertNotNull(res.torsoCenter)
        assertEquals(WindowState.SINGLE_FRAME_REQUEST, res.torsoEvidence.windowState)
    }

    @Test
    fun test41_jodanProvenanceListsActualContributingTimestamps() {
        val f = makeFrame(9999)
        val est = TargetHeightEstimator.estimateJodanMouthNose110(9999000L, listOf(f), radius = 0)
        assertEquals(listOf(9999000L), est.contributingTimestampsUs)
    }

    // ==========================================
    // 46. Compatibility Estimators (Tests 42 to 49)
    // ==========================================

    @Test
    fun test42_chudan45PercentReproducesExistingFormula() {
        val f = makeFrame(100,
            leftShoulder = Pair(0.4f, 0.2f), rightShoulder = Pair(0.6f, 0.2f), // S = (0.5, 0.2)
            leftHip = Pair(0.4f, 0.8f), rightHip = Pair(0.6f, 0.8f), // H = (0.5, 0.8)
        )
        val res = BodyHeightModel.evaluate(100_000L, listOf(f), frameGeometry, radius = 0)
        val est = TargetHeightEstimator.estimateChudanTorsoRatio045(res)
        assertEquals(TargetEstimateStatus.VALID, est.status)
        // C = 0.2 + 0.45 * (0.8 - 0.2) = 0.2 + 0.27 = 0.47
        assertEquals(0.47f, est.point!!.y, 1e-4f)
    }

    @Test
    fun test43_shoulderElevationMovesCompatibilityChudan() {
        val f1 = makeFrame(100,
            leftShoulder = Pair(0.4f, 0.2f), rightShoulder = Pair(0.6f, 0.2f),
            leftHip = Pair(0.4f, 0.8f), rightHip = Pair(0.6f, 0.8f),
        )
        val f2 = makeFrame(100,
            leftShoulder = Pair(0.4f, 0.1f), rightShoulder = Pair(0.6f, 0.1f), // elevated shoulders
            leftHip = Pair(0.4f, 0.8f), rightHip = Pair(0.6f, 0.8f),
        )
        val res1 = BodyHeightModel.evaluate(100_000L, listOf(f1), frameGeometry, radius = 0)
        val res2 = BodyHeightModel.evaluate(100_000L, listOf(f2), frameGeometry, radius = 0)
        val c1 = TargetHeightEstimator.estimateChudanTorsoRatio045(res1)
        val c2 = TargetHeightEstimator.estimateChudanTorsoRatio045(res2)
        assertTrue(c2.point!!.y < c1.point!!.y) // moves upward
    }

    @Test
    fun test44_noShoulderIndependenceClaim() {
        val est = TargetHeightEstimator.estimateChudanTorsoRatio045(
            BodyHeightModel.evaluate(100_000L, listOf(makeFrame(100)), frameGeometry, radius = 0)
        )
        assertEquals(TargetHeightEstimator.CHUDAN_RATIO_045_ID, est.targetId)
    }

    @Test
    fun test45_gedan80PercentReproducesExistingFormula() {
        val f = makeFrame(100,
            leftShoulder = Pair(0.4f, 0.2f), rightShoulder = Pair(0.6f, 0.2f),
            leftHip = Pair(0.4f, 0.8f), rightHip = Pair(0.6f, 0.8f),
        )
        val res = BodyHeightModel.evaluate(100_000L, listOf(f), frameGeometry, radius = 0)
        val est = TargetHeightEstimator.estimateGedanTorsoRatio080(res)
        // G = 0.2 + 0.80 * (0.8 - 0.2) = 0.2 + 0.48 = 0.68
        assertEquals(0.68f, est.point!!.y, 1e-4f)
    }

    @Test
    fun test46_hipLevelGedanEqualsAggregateHipCenter() {
        val f = makeFrame(100)
        val res = BodyHeightModel.evaluate(100_000L, listOf(f), frameGeometry, radius = 0)
        val est = TargetHeightEstimator.estimateGedanHipLevel(res)
        assertEquals(res.hipCenter!!.x, est.point!!.x, 1e-4f)
        assertEquals(res.hipCenter!!.y, est.point!!.y, 1e-4f)
    }

    @Test
    fun test47_mouthNoseJodanReproducesVersionedFormula() {
        val f = makeFrame(100,
            mouthLeft = Pair(0.45f, 0.25f), mouthRight = Pair(0.55f, 0.25f), // mouth = (0.50, 0.25)
            nose = Pair(0.50f, 0.20f), // nose = (0.50, 0.20)
        )
        val est = TargetHeightEstimator.estimateJodanMouthNose110(100_000L, listOf(f), radius = 0)
        // Jodan = (0.50, 0.25) + 1.10 * (0, 0.05) = (0.50, 0.25 + 0.055) = (0.50, 0.305)
        assertEquals(0.50f, est.point!!.x, 1e-4f)
        assertEquals(0.305f, est.point!!.y, 1e-4f)
    }

    @Test
    fun test48_targetValidityIsIndependentByTarget() {
        val f = makeFrame(100, mouthLeft = null) // Jodan unavailable, but torso valid
        val res = BodyHeightModel.evaluate(100_000L, listOf(f), frameGeometry, radius = 0)
        val jodan = TargetHeightEstimator.estimateJodanMouthNose110(100_000L, listOf(f), radius = 0)
        val chudan = TargetHeightEstimator.estimateChudanTorsoRatio045(res)
        assertEquals(TargetEstimateStatus.UNAVAILABLE, jodan.status)
        assertEquals(TargetEstimateStatus.VALID, chudan.status)
    }

    @Test
    fun test49_estimatorAndConfigurationVersionsRetained() {
        val res = BodyHeightModel.evaluate(100_000L, listOf(makeFrame(100)), frameGeometry, radius = 0)
        val est = TargetHeightEstimator.estimateChudanTorsoRatio045(res)
        assertEquals("default_body_height_v2.1", est.configId)
        assertEquals("1", est.estimatorVersion)
    }

    // ==========================================
    // 47. Reproducibility (Tests 50 to 56)
    // ==========================================

    @Test
    fun test50_sameEvidenceProducesSameResult() {
        val f = makeFrame(100)
        val r1 = BodyHeightModel.evaluate(100_000L, listOf(f), frameGeometry, radius = 0)
        val r2 = BodyHeightModel.evaluate(100_000L, listOf(f), frameGeometry, radius = 0)
        assertEquals(r1.torsoCenter!!.x, r2.torsoCenter!!.x, 1e-6f)
        assertEquals(r1.torsoCenter!!.y, r2.torsoCenter!!.y, 1e-6f)
    }

    @Test
    fun test51_landmarkThresholdChangesReflectedInConfigId() {
        val config1 = BodyHeightModelConfig(configId = "conf_strict", landmarkValidityThreshold = 0.8f)
        val config2 = BodyHeightModelConfig(configId = "conf_loose", landmarkValidityThreshold = 0.3f)
        val f = makeFrame(100)
        val r1 = BodyHeightModel.evaluate(100_000L, listOf(f), frameGeometry, config = config1)
        val r2 = BodyHeightModel.evaluate(100_000L, listOf(f), frameGeometry, config = config2)
        assertEquals("conf_strict", r1.configId)
        assertEquals("conf_loose", r2.configId)
    }

    @Test
    fun test52_headAnchorStrategyChangesAlterIdentity() {
        val config = BodyHeightModelConfig(headAnchorStrategy = HeadAnchorStrategyId.EYES_EARS_COMPOSITE_V1)
        val f = makeFrame(100)
        val r = BodyHeightModel.evaluate(100_000L, listOf(f), frameGeometry, config = config)
        // With composite strategy, head anchor is computed
        assertNotNull(r.headAnchor)
    }

    @Test
    fun test53_timestampGapPolicyChangesAlterConfigurationIdentity() {
        val config = BodyHeightModelConfig(configId = "gap_200ms", maxTimestampGapUs = 200_000L)
        val f = makeFrame(100)
        val r = BodyHeightModel.evaluate(100_000L, listOf(f), frameGeometry, config = config)
        assertEquals("gap_200ms", r.configId)
    }

    @Test
    fun test54_cropViewportContentScaleChangesDoNotAffectBodyHeightModel() {
        val f = makeFrame(100)
        // Frame geometry dimensions differ, but canonical source coordinates derived by BodyHeightModel are identical
        val geomPortrait = FrameGeometry(1080, 1920)
        val geomLandscape = FrameGeometry(1920, 1080)
        val r1 = BodyHeightModel.evaluate(100_000L, listOf(f), geomPortrait, radius = 0)
        val r2 = BodyHeightModel.evaluate(100_000L, listOf(f), geomLandscape, radius = 0)
        assertEquals(r1.torsoCenter!!.x, r2.torsoCenter!!.x, 1e-6f)
        assertEquals(r1.torsoCenter!!.y, r2.torsoCenter!!.y, 1e-6f)
    }

    @Test
    fun test55_negativeRadiusIsRejected() {
        assertThrows(IllegalArgumentException::class.java) {
            BodyHeightModel.evaluate(100_000L, listOf(makeFrame(100)), frameGeometry, radius = -1)
        }
    }

    @Test
    fun test56_invalidMinimumTorsoLengthIsRejected() {
        assertThrows(IllegalArgumentException::class.java) {
            BodyHeightModelConfig(minimumTorsoLength = -0.1f)
        }
        assertThrows(IllegalArgumentException::class.java) {
            BodyHeightModelConfig(minimumTorsoLength = Float.NaN)
        }
    }

    @Test
    fun test57_degenerateTorsoCausesChudanUnavailable() {
        // Shoulder and hip at identical Y coordinate (torso length = 0 < 0.05)
        val f = makeFrame(
            100,
            leftShoulder = Pair(0.4f, 0.5f),
            rightShoulder = Pair(0.6f, 0.5f),
            leftHip = Pair(0.4f, 0.5f),
            rightHip = Pair(0.6f, 0.5f),
        )
        val res = BodyHeightModel.evaluate(100_000L, listOf(f), frameGeometry, radius = 0)
        assertNull(res.currentBodyUp)
        assertNull(res.currentTorsoLength)
        assertEquals(WindowState.UNAVAILABLE, res.torsoEvidence.windowState)
        val est = TargetHeightEstimator.estimateChudanTorsoRatio045(res)
        assertEquals(TargetEstimateStatus.UNAVAILABLE, est.status)
        assertEquals("torso_geometry_degenerate_or_unavailable", est.reason)
        assertNull(est.point)
    }

    @Test
    fun test58_multiFrameTrackMismatchIsExcluded() {
        val f1 = makeFrame(100)
        val f2 = makeFrame(133)
        val f3 = makeFrame(166)
        val trackIds = mapOf(100L to "track_1", 133L to "track_1", 166L to "track_2")

        val res = BodyHeightModel.evaluate(133_000L, listOf(f1, f2, f3), frameGeometry, radius = 1, frameTrackIds = trackIds)
        assertEquals(2, res.torsoEvidence.usableCount)
        assertTrue(166_000L in res.torsoEvidence.excludedTimestampsUs)
        assertTrue(res.torsoEvidence.exclusionReasons[166_000L]?.startsWith("track_mismatch") == true)
    }

    @Test
    fun test59_missingHipPopulatesTorsoExclusionReasons() {
        val f1 = makeFrame(100)
        val f2 = makeFrame(133)
        val f3 = makeFrame(166, leftHip = null, rightHip = null)

        val res = BodyHeightModel.evaluate(133_000L, listOf(f1, f2, f3), frameGeometry, radius = 1)
        assertEquals(2, res.torsoEvidence.usableCount)
        assertTrue(166_000L in res.torsoEvidence.excludedTimestampsUs)
        assertEquals("missing_hip_landmarks", res.torsoEvidence.exclusionReasons[166_000L])
    }

    @Test
    fun test60_targetHeightEstimatorInheritsCustomConfigId() {
        val customConfig = BodyHeightModelConfig(configId = "custom_test_config_999")
        val f = makeFrame(100)
        val res = BodyHeightModel.evaluate(100_000L, listOf(f), frameGeometry, radius = 0, config = customConfig)
        assertEquals("custom_test_config_999", res.configId)

        val chudan = TargetHeightEstimator.estimateChudanTorsoRatio045(res)
        assertEquals("custom_test_config_999", chudan.configId)
        assertEquals(TargetEstimateStatus.VALID, chudan.status)

        val gedan = TargetHeightEstimator.estimateGedanTorsoRatio080(res)
        assertEquals("custom_test_config_999", gedan.configId)

        val hip = TargetHeightEstimator.estimateGedanHipLevel(res)
        assertEquals("custom_test_config_999", hip.configId)

        val jodan = TargetHeightEstimator.estimateJodanMouthNose110(100_000L, listOf(f), radius = 0, config = customConfig)
        assertEquals("custom_test_config_999", jodan.configId)

        // Derivative config with non-default threshold
        val derivedConfig = BodyHeightModelConfig(landmarkValidityThreshold = 0.8f)
        val resDerived = BodyHeightModel.evaluate(100_000L, listOf(f), frameGeometry, radius = 0, config = derivedConfig)
        assertEquals(derivedConfig.effectiveConfigId, resDerived.configId)
        val chudanDerived = TargetHeightEstimator.estimateChudanTorsoRatio045(resDerived)
        assertEquals(derivedConfig.effectiveConfigId, chudanDerived.configId)
    }

    @Test
    fun test61_unidentifiedSelectedSamplePreventsConflictingKnownTracksFromMixing() {
        val f1 = makeFrame(100)
        val f2 = makeFrame(133)
        val f3 = makeFrame(166)
        // Selected frame at 133ms has no track ID; neighbor 1 has person_a, neighbor 2 has person_b
        val trackIds = mapOf(100L to "person_a", 166L to "person_b")

        val res = BodyHeightModel.evaluate(133_000L, listOf(f1, f2, f3), frameGeometry, radius = 1, frameTrackIds = trackIds)
        // Conflicting known tracks must not mix; only unidentified selected sample may contribute
        assertEquals(1, res.torsoEvidence.usableCount)
        assertTrue(100_000L in res.torsoEvidence.excludedTimestampsUs)
        assertTrue(166_000L in res.torsoEvidence.excludedTimestampsUs)
        assertTrue(res.torsoEvidence.exclusionReasons[100_000L]?.startsWith("track_mismatch") == true)
        assertTrue(res.torsoEvidence.exclusionReasons[166_000L]?.startsWith("track_mismatch") == true)
    }

    @Test
    fun test62_cadenceSourceAndAggregationMethodAlterEffectiveConfigId() {
        val cCadence = BodyHeightModelConfig(landmarkStreamCadenceSource = "mls_60fps_contract")
        assertNotEquals(BodyHeightModelConfig.DEFAULT_CONFIG_ID, cCadence.effectiveConfigId)
        assertTrue(cCadence.effectiveConfigId.contains("mls_60fps_contract"))

        val cAgg = BodyHeightModelConfig(aggregationMethod = "component_wise_mean_v1")
        assertNotEquals(BodyHeightModelConfig.DEFAULT_CONFIG_ID, cAgg.effectiveConfigId)
        assertTrue(cAgg.effectiveConfigId.contains("component_wise_mean_v1"))
    }
}
