package dk.lasse.karateanalyzer.capture.qom

import dk.lasse.karateanalyzer.core.LandmarkSource
import dk.lasse.karateanalyzer.core.Point3
import dk.lasse.karateanalyzer.core.PoseFrame
import dk.lasse.karateanalyzer.core.PoseLandmarkId
import dk.lasse.karateanalyzer.core.PoseLandmarkSample
import kotlin.math.abs
import kotlin.test.*

class QomMathematicalUnitTest {

    private fun sample(
        pos: Point3,
        world: Point3 = pos,
        vis: Float = 0.95f,
        pres: Float = 0.95f,
    ) = PoseLandmarkSample(
        position = pos,
        worldPosition = world,
        visibility = vis,
        presence = pres,
        source = LandmarkSource.OBSERVED,
    )

    private fun createBasePose(
        wristOffset: Float = 0f,
        kneeOffset: Float = 0f,
        torsoOffset: Float = 0f,
        headOffset: Float = 0f,
        globalShift: Point3 = Point3(0f, 0f, 0f),
    ): Map<PoseLandmarkId, PoseLandmarkSample> {
        val base = mutableMapOf(
            PoseLandmarkId.NOSE to sample(Point3(0f + headOffset, -1.7f, 0f) + globalShift),
            PoseLandmarkId.LEFT_EAR to sample(Point3(-0.12f, -1.65f, 0f) + globalShift),
            PoseLandmarkId.RIGHT_EAR to sample(Point3(0.12f, -1.65f, 0f) + globalShift),
            PoseLandmarkId.LEFT_SHOULDER to sample(Point3(-0.25f, -1f + torsoOffset, 0f) + globalShift),
            PoseLandmarkId.RIGHT_SHOULDER to sample(Point3(0.25f, -1f + torsoOffset, 0f) + globalShift),
            PoseLandmarkId.LEFT_ELBOW to sample(Point3(-0.45f, -0.8f, 0f) + globalShift),
            PoseLandmarkId.RIGHT_ELBOW to sample(Point3(0.45f, -0.8f, 0f) + globalShift),
            PoseLandmarkId.LEFT_WRIST to sample(Point3(-0.65f + wristOffset, -0.5f, 0f) + globalShift),
            PoseLandmarkId.LEFT_THUMB to sample(Point3(-0.68f + wristOffset, -0.45f, 0f) + globalShift),
            PoseLandmarkId.LEFT_INDEX to sample(Point3(-0.67f + wristOffset, -0.42f, 0f) + globalShift),
            PoseLandmarkId.LEFT_PINKY to sample(Point3(-0.63f + wristOffset, -0.43f, 0f) + globalShift),
            PoseLandmarkId.RIGHT_WRIST to sample(Point3(0.65f, -0.5f, 0f) + globalShift),
            PoseLandmarkId.RIGHT_THUMB to sample(Point3(0.68f, -0.45f, 0f) + globalShift),
            PoseLandmarkId.RIGHT_INDEX to sample(Point3(0.67f, -0.42f, 0f) + globalShift),
            PoseLandmarkId.RIGHT_PINKY to sample(Point3(0.63f, -0.43f, 0f) + globalShift),
            PoseLandmarkId.LEFT_HIP to sample(Point3(-0.2f, 0f, 0f) + globalShift),
            PoseLandmarkId.RIGHT_HIP to sample(Point3(0.2f, 0f, 0f) + globalShift),
            PoseLandmarkId.LEFT_KNEE to sample(Point3(-0.25f, 1f + kneeOffset, 0f) + globalShift),
            PoseLandmarkId.RIGHT_KNEE to sample(Point3(0.25f, 1f, 0f) + globalShift),
            PoseLandmarkId.LEFT_ANKLE to sample(Point3(-0.25f, 2f + kneeOffset, 0f) + globalShift),
            PoseLandmarkId.LEFT_HEEL to sample(Point3(-0.25f, 2.05f + kneeOffset, -0.05f) + globalShift),
            PoseLandmarkId.LEFT_FOOT_INDEX to sample(Point3(-0.25f, 2.1f + kneeOffset, 0.1f) + globalShift),
            PoseLandmarkId.RIGHT_ANKLE to sample(Point3(0.25f, 2f, 0f) + globalShift),
            PoseLandmarkId.RIGHT_HEEL to sample(Point3(0.25f, 2.05f, -0.05f) + globalShift),
            PoseLandmarkId.RIGHT_FOOT_INDEX to sample(Point3(0.25f, 2.1f, 0.1f) + globalShift),
        )
        return base
    }

    @Test
    fun addingSameXyzTranslationLeavesHipRelativeQomUnchanged() {
        val extractorA = QomMotionEvidenceExtractor(profile = MotionBodyProfile.PUNCH)
        val extractorB = QomMotionEvidenceExtractor(profile = MotionBodyProfile.PUNCH)

        val shift = Point3(12.5f, -8.3f, 4.2f)

        for (i in 0..10) {
            val t = i * 33L
            val offset = if (i >= 5) (i - 4) * 0.05f else 0f

            val frameA = PoseFrame(t, createBasePose(wristOffset = offset))
            val frameB = PoseFrame(t, createBasePose(wristOffset = offset, globalShift = shift))

            val evA = extractorA.extract(frameA)
            val evB = extractorB.extract(frameB)

            if (evA.aggregateQom != null && evB.aggregateQom != null) {
                assertEquals(evA.aggregateQom!!, evB.aggregateQom!!, 1e-5)
                assertEquals(evA.rollingArea, evB.rollingArea, 1e-5)
            }
        }
    }

    @Test
    fun staticPoseProducesApproximatelyZeroSignalAfterStartup() {
        val extractor = QomMotionEvidenceExtractor(profile = MotionBodyProfile.PUNCH)
        for (i in 0..10) {
            val ev = extractor.extract(PoseFrame(i * 33L, createBasePose()))
            if (i >= 4) {
                assertTrue(ev.isAvailable)
                assertEquals(0.0, ev.aggregateQom ?: 0.0, 1e-4)
                assertEquals(0.0, ev.rollingArea, 1e-4)
            }
        }
    }

    @Test
    fun nonIncreasingTimestampsAreRejected() {
        val extractor = QomMotionEvidenceExtractor()
        extractor.extract(PoseFrame(100L, createBasePose()))
        assertFailsWith<IllegalArgumentException> {
            extractor.extract(PoseFrame(100L, createBasePose()))
        }
        assertFailsWith<IllegalArgumentException> {
            extractor.extract(PoseFrame(90L, createBasePose()))
        }
    }

    @Test
    fun timestampGapsSafelyResetHistory() {
        val extractor = QomMotionEvidenceExtractor(config = QomDetectorConfig(maximumFrameGapMs = 500L))
        extractor.extract(PoseFrame(100L, createBasePose()))
        extractor.extract(PoseFrame(133L, createBasePose()))
        extractor.extract(PoseFrame(166L, createBasePose()))

        // Large gap of 1000ms
        val postGap = extractor.extract(PoseFrame(1166L, createBasePose()))
        assertFalse(postGap.isAvailable, "First frame after gap reset should be in filter startup")
        assertEquals(0.0, postGap.rollingArea)
    }

    @Test
    fun compositeExtremityCalculatesConfidenceWeightedCenter() {
        val frame = PoseFrame(
            timestampMs = 0L,
            landmarks = mapOf(
                PoseLandmarkId.LEFT_WRIST to sample(Point3(0f, 0f, 0f), vis = 1f, pres = 1f),
                PoseLandmarkId.LEFT_THUMB to sample(Point3(1f, 0f, 0f), vis = 0.5f, pres = 0.5f), // conf = 0.25
                PoseLandmarkId.LEFT_INDEX to sample(Point3(0f, 1f, 0f), vis = 0.5f, pres = 0.5f), // conf = 0.25
                PoseLandmarkId.LEFT_PINKY to sample(Point3(0f, 0f, 1f), vis = 0.5f, pres = 0.5f), // conf = 0.25
            ),
        )
        val comp = ExtremityPointComposer.compositePoint(frame, MotionBodyProfile.PUNCH_HAND_LANDMARKS_LEFT)
        assertNotNull(comp)
        val (center, conf) = comp
        // sumConf = 1.0 + 0.25 + 0.25 + 0.25 = 1.75
        // x = (0*1.0 + 1*0.25) / 1.75 = 0.25 / 1.75 = 1/7
        assertEquals(1f / 7f, center.x, 1e-4f)
        assertEquals(1f / 7f, center.y, 1e-4f)
        assertEquals(1f / 7f, center.z, 1e-4f)
        // average confidence across 4 constituents: (1.0 + 0.25 + 0.25 + 0.25) / 4 = 1.75 / 4 = 0.4375
        assertEquals(0.4375, conf, 1e-4)
    }

    @Test
    fun compositePointZeroConfidenceFallsBackToArithmeticMean() {
        val frame = PoseFrame(
            timestampMs = 0L,
            landmarks = mapOf(
                PoseLandmarkId.LEFT_ANKLE to sample(Point3(0f, 0f, 0f), vis = 0f, pres = 0f),
                PoseLandmarkId.LEFT_HEEL to sample(Point3(3f, 0f, 0f), vis = 0f, pres = 0f),
                PoseLandmarkId.LEFT_FOOT_INDEX to sample(Point3(0f, 6f, 0f), vis = 0f, pres = 0f),
            ),
        )
        val comp = ExtremityPointComposer.compositePoint(frame, MotionBodyProfile.KICK_FOOT_LANDMARKS_LEFT)
        assertNotNull(comp)
        val (center, conf) = comp
        assertEquals(1f, center.x, 1e-4f)
        assertEquals(2f, center.y, 1e-4f)
        assertEquals(0.0, conf, 1e-4)
    }

    @Test
    fun singleFramePositionSpikeIsReducedByMedian3() {
        val extractor = QomMotionEvidenceExtractor(profile = MotionBodyProfile.PUNCH)
        val baseline = createBasePose()
        val spike = createBasePose(wristOffset = 2.0f) // Sudden 2-meter jump for 1 frame

        extractor.extract(PoseFrame(100L, baseline))
        extractor.extract(PoseFrame(133L, baseline))
        extractor.extract(PoseFrame(166L, baseline))
        val spikeEv = extractor.extract(PoseFrame(200L, spike))
        val recoveryEv = extractor.extract(PoseFrame(233L, baseline))

        // In 3-sample median: [baseline, baseline, spike] -> median is baseline!
        // So the spike is suppressed by median filtering at frame 200 and frame 233
        assertTrue(spikeEv.rollingArea < 0.05, "Spike must not trigger 0.08 gate: was ${spikeEv.rollingArea}")
        assertTrue(recoveryEv.rollingArea < 0.05, "Recovery must remain quiet: was ${recoveryEv.rollingArea}")
    }

    @Test
    fun punchProfileIgnoresLegMotionAndKickProfileIgnoresArmMotion() {
        val punchExtractor = QomMotionEvidenceExtractor(profile = MotionBodyProfile.PUNCH)
        val kickExtractor = QomMotionEvidenceExtractor(profile = MotionBodyProfile.KICK)

        // 1. Leg motion only
        for (i in 0..10) {
            val t = i * 33L
            val legOffset = (i * 0.1f)
            val frame = PoseFrame(t, createBasePose(kneeOffset = legOffset))
            val punchEv = punchExtractor.extract(frame)
            val kickEv = kickExtractor.extract(frame)

            if (i >= 4) {
                assertEquals(0.0, punchEv.aggregateQom ?: 0.0, 1e-4, "Punch must ignore leg motion")
                assertTrue((kickEv.aggregateQom ?: 0.0) > 0.05, "Kick must detect leg motion")
            }
        }

        // 2. Arm motion only
        punchExtractor.reset()
        kickExtractor.reset()
        for (i in 0..10) {
            val t = i * 33L
            val armOffset = (i * 0.1f)
            val frame = PoseFrame(t, createBasePose(wristOffset = armOffset))
            val punchEv = punchExtractor.extract(frame)
            val kickEv = kickExtractor.extract(frame)

            if (i >= 4) {
                assertTrue((punchEv.aggregateQom ?: 0.0) > 0.05, "Punch must detect arm motion")
                assertEquals(0.0, kickEv.aggregateQom ?: 0.0, 1e-4, "Kick must ignore arm motion")
            }
        }
    }

    @Test
    fun headMotionHasZeroEffectOnQom() {
        val extractorA = QomMotionEvidenceExtractor(profile = MotionBodyProfile.PUNCH)
        val extractorB = QomMotionEvidenceExtractor(profile = MotionBodyProfile.PUNCH)

        for (i in 0..10) {
            val t = i * 33L
            val armOffset = if (i >= 5) (i - 4) * 0.05f else 0f
            val headOffset = (i * 0.2f) // Wild head motion

            val frameA = PoseFrame(t, createBasePose(wristOffset = armOffset))
            val frameB = PoseFrame(t, createBasePose(wristOffset = armOffset, headOffset = headOffset))

            val evA = extractorA.extract(frameA)
            val evB = extractorB.extract(frameB)

            if (evA.aggregateQom != null && evB.aggregateQom != null) {
                assertEquals(evA.aggregateQom!!, evB.aggregateQom!!, 1e-5)
                assertEquals(evA.rollingArea, evB.rollingArea, 1e-5)
            }
        }
    }

    @Test
    fun equalBlockAggregationWeightsEachBlockEqually() {
        // Punch has 3 blocks: LEFT_ARM (2 pts), RIGHT_ARM (2 pts), TORSO (4 pts)
        // Despite Torso having 4 points and arms having 2 points each,
        // aggregate QoM is exactly the unweighted mean of the 3 block QoM values: (L + R + T) / 3
        val extractor = QomMotionEvidenceExtractor(profile = MotionBodyProfile.PUNCH)
        for (i in 0..10) {
            val t = i * 33L
            val offset = i * 0.05f
            val frame = PoseFrame(t, createBasePose(wristOffset = offset, torsoOffset = offset * 0.5f))
            val ev = extractor.extract(frame)
            if (i >= 4) {
                val la = ev.blockQom[MotionBlockId.LEFT_ARM]!!
                val ra = ev.blockQom[MotionBlockId.RIGHT_ARM]!!
                val to = ev.blockQom[MotionBlockId.TORSO]!!
                val expectedMean = (la + ra + to) / 3.0
                assertEquals(expectedMean, ev.aggregateQom!!, 1e-5)
            }
        }
    }

    @Test
    fun hysteresisGatesControlMovingStateAndImmediateRearm() {
        val segmenter = QomMovementSegmenter(
            config = QomDetectorConfig(
                startGateMeters = 0.08,
                stopGateMeters = 0.04,
                preRollMs = 150L,
                postRollMs = 200L,
            ),
        )

        fun evidence(t: Long, area: Double) = QomFrameEvidence(
            timestampMs = t,
            profile = MotionBodyProfile.PUNCH,
            blockQom = emptyMap(),
            aggregateQom = 1.0,
            rollingArea = area,
            availableBlockCount = 3,
            totalActiveBlockCount = 3,
            isAvailable = true,
        )

        assertEquals(QomSegmentState.STILL, segmenter.state)

        // Below start gate
        var snap = segmenter.accept(evidence(100L, 0.05))
        assertEquals(QomSegmentState.STILL, snap.state)
        assertNull(snap.completedSegment)

        // Cross start gate (0.08)
        snap = segmenter.accept(evidence(200L, 0.085))
        assertEquals(QomSegmentState.MOVING, snap.state)
        assertEquals(200L, snap.activeStartBoundaryMs)
        assertNull(snap.completedSegment)

        // Dip between gates (0.06): maintains MOVING
        snap = segmenter.accept(evidence(300L, 0.06))
        assertEquals(QomSegmentState.MOVING, snap.state)
        assertNull(snap.completedSegment)

        // Fall to or below stop gate (0.04): completes segment and returns to STILL
        snap = segmenter.accept(evidence(500L, 0.035))
        assertEquals(QomSegmentState.STILL, snap.state)
        assertNotNull(snap.completedSegment)
        val seg1 = snap.completedSegment!!
        assertEquals(1, seg1.movementNumber)
        assertEquals(200L, seg1.logicalStartTimestampMs)
        assertEquals(500L, seg1.logicalEndTimestampMs)
        assertEquals(300L, seg1.durationMs)
        assertEquals(50L, seg1.retainedStartTimestampMs) // 200 - 150
        assertEquals(700L, seg1.retainedEndTimestampMs) // 500 + 200

        // Immediate re-arm: next movement can start on the very next frame
        snap = segmenter.accept(evidence(600L, 0.09))
        assertEquals(QomSegmentState.MOVING, snap.state)
        assertEquals(600L, snap.activeStartBoundaryMs)
        assertEquals(2, segmenter.currentMovementNumber)
    }
}

