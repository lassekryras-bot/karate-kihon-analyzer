package dk.lasse.karateanalyzer.capture.qom

import dk.lasse.karateanalyzer.capture.LateralSide
import dk.lasse.karateanalyzer.core.LandmarkSource
import dk.lasse.karateanalyzer.core.Point3
import dk.lasse.karateanalyzer.core.PoseFrame
import dk.lasse.karateanalyzer.core.PoseLandmarkId
import dk.lasse.karateanalyzer.core.PoseLandmarkSample
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class SideViewAlternatingPunchSegmenterTest {
    @Test
    fun centroidRosterIsExplicitAndIncludesBothFootIndices() {
        val roster = SideViewAlternatingPunchSegmenter.CENTROID_ROSTER

        assertEquals(22, roster.size)
        assertTrue(PoseLandmarkId.LEFT_FOOT_INDEX in roster)
        assertTrue(PoseLandmarkId.RIGHT_FOOT_INDEX in roster)
        assertFalse(PoseLandmarkId.NOSE in roster)
        assertFalse(PoseLandmarkId.LEFT_EAR in roster)
    }

    @Test
    fun versionFactoryRequiresObservedWorldEvidenceAndExplicitLeftRepair() {
        val config = QomDetectorConfig.sideViewAlternatingPunchV1()

        assertEquals(QomDetectorConfig.SIDE_VIEW_ALTERNATING_PUNCH_V1, config.detectorVersion)
        assertEquals(QomEvidencePolicy.OBSERVED_WORLD_V2, config.evidencePolicy)
        assertEquals(LateralSide.LEFT, config.sideViewRepairSide)
        assertEquals(
            SideViewAlternatingPunchConfig(version = QomDetectorConfig.SIDE_VIEW_ALTERNATING_PUNCH_V1),
            SideViewAlternatingPunchConfig.from(config),
        )
        assertEquals(
            QomDetectorConfig.SIDE_VIEW_ALTERNATING_PUNCH_V2,
            SideViewAlternatingPunchConfig.from(QomDetectorConfig.sideViewAlternatingPunchV2()).version,
        )
        assertFailsWith<IllegalArgumentException> {
            SideViewAlternatingPunchConfig.from(config.copy(rollingAreaDurationMs = 200L))
        }
        assertFailsWith<IllegalArgumentException> {
            config.copy(sideViewRepairSide = null)
        }
        assertFailsWith<IllegalArgumentException> {
            config.copy(sideViewRepairSide = LateralSide.RIGHT)
        }
    }

    @Test
    fun rejectedLeftArmEvidenceIsInterpolatedOnlyWithinTheAnchorLimit() {
        val result = SideViewAlternatingPunchSegmenter().segment(
            listOf(
                frame(0L),
                frame(100L, leftElbowX = -2f),
                frame(200L),
            ),
        )

        val repaired = result.derivedFrames[1].landmarks.getValue(PoseLandmarkId.LEFT_ELBOW)
        assertEquals(DerivedLandmarkProvenance.INTERPOLATED, repaired.provenance)
        assertEquals(0L, repaired.leftAnchorTimestampMs)
        assertEquals(200L, repaired.rightAnchorTimestampMs)
        assertEquals(200L, repaired.repairAnchorSpanMs)
        assertEquals(-1f, repaired.point?.x)
        assertNull(repaired.rejectedObservedPoint)
    }

    @Test
    fun rejectedEvidenceBeyondAnchorLimitIsExcludedButAuditable() {
        val result = SideViewAlternatingPunchSegmenter().segment(
            listOf(
                frame(0L),
                frame(100L),
                frame(200L, leftElbowX = -2f),
                frame(300L, leftElbowX = -2f),
                frame(400L),
            ),
        )

        val rejected = result.derivedFrames[2].landmarks.getValue(PoseLandmarkId.LEFT_ELBOW)
        assertEquals(DerivedLandmarkProvenance.UNRESOLVED_REJECTED, rejected.provenance)
        assertNull(rejected.point)
        assertEquals(-2f, rejected.rejectedObservedPoint?.x)
        assertEquals(100L, rejected.leftAnchorTimestampMs)
        assertEquals(400L, rejected.rightAnchorTimestampMs)
        assertEquals(300L, rejected.repairAnchorSpanMs)
        assertEquals(DerivedLandmarkRepairRefusalReason.ANCHOR_SPAN_EXCEEDS_LIMIT, rejected.repairRefusalReason)
        assertTrue(result.unresolvedRejectedCount > 0)
    }

    @Test
    fun missingIndividualPointRepairsIndependentlyOfValidUpperArmReference() {
        val repaired = SideViewAlternatingPunchSegmenter().segment(listOf(
            frame(0L),
            frame(100L, omitted = setOf(PoseLandmarkId.LEFT_WRIST)),
            frame(200L),
        )).derivedFrames[1].landmarks.getValue(PoseLandmarkId.LEFT_WRIST)
        assertEquals(DerivedLandmarkProvenance.INTERPOLATED, repaired.provenance)
        assertEquals(200L, repaired.repairAnchorSpanMs)
        assertNull(repaired.repairRefusalReason)

        val unresolved = SideViewAlternatingPunchSegmenter().segment(listOf(
            frame(0L),
            frame(100L, omitted = setOf(PoseLandmarkId.LEFT_WRIST)),
            frame(200L, omitted = setOf(PoseLandmarkId.LEFT_WRIST)),
            frame(300L, omitted = setOf(PoseLandmarkId.LEFT_WRIST)),
            frame(400L),
        )).derivedFrames[2].landmarks.getValue(PoseLandmarkId.LEFT_WRIST)
        assertEquals(DerivedLandmarkProvenance.UNRESOLVED_MISSING, unresolved.provenance)
        assertEquals(400L, unresolved.repairAnchorSpanMs)
        assertEquals(DerivedLandmarkRepairRefusalReason.ANCHOR_SPAN_EXCEEDS_LIMIT, unresolved.repairRefusalReason)
    }

    @Test
    fun unresolvedRejectedCoordinatesDoNotContributeToMotionAndHistoryRestarts() {
        fun replay(rejectedX: Float): SideViewAlternatingPunchResult =
            SideViewAlternatingPunchSegmenter().segment(listOf(
                frame(0L, handOffset = 0f),
                frame(33L, handOffset = .02f),
                frame(66L, leftElbowX = rejectedX, handOffset = 4f),
                frame(99L, leftElbowX = rejectedX, handOffset = 8f),
                frame(330L, handOffset = .08f),
                frame(363L, handOffset = .10f),
                frame(396L, handOffset = .12f),
            ))

        val moderateRejectedRaw = replay(-2f)
        val extremeRejectedRaw = replay(-20f)
        assertEquals(
            moderateRejectedRaw.qomTimeline.map { it.aggregateQom to it.rollingArea },
            extremeRejectedRaw.qomTimeline.map { it.aggregateQom to it.rollingArea },
            "Changing rejected raw coordinates must not change the detector signal",
        )
        assertEquals(-2f, moderateRejectedRaw.derivedFrames[2].landmarks
            .getValue(PoseLandmarkId.LEFT_ELBOW).rejectedObservedPoint?.x)
        assertEquals(-20f, extremeRejectedRaw.derivedFrames[2].landmarks
            .getValue(PoseLandmarkId.LEFT_ELBOW).rejectedObservedPoint?.x)
    }

    @Test
    fun missingEvidenceTruncatesAnEstablishedCandidateWithoutClaimingQuiet() {
        val frames = buildList {
            repeat(30) { index -> add(frame(index * 33L, handOffset = if (index % 2 == 0) 0f else .001f)) }
            repeat(12) { step -> add(frame((30 + step) * 33L, handOffset = step * .12f)) }
            val gapStart = 30L * 33L + 12L * 33L + 800L
            repeat(30) { index -> add(frame(gapStart + index * 33L, handOffset = 1.32f + if (index % 2 == 0) 0f else .001f)) }
        }

        val result = SideViewAlternatingPunchSegmenter().segment(frames)
        val gapWindow = result.windows.singleOrNull {
            it.endBoundaryKind == QomEndBoundaryKind.EVIDENCE_GAP_WITHOUT_STOP_EVIDENCE
        }
        assertNotNull(gapWindow, "Missing evidence must expose a truncated boundary rather than a stop gate")
    }

    @Test
    fun missingUpperArmReferenceAndDegenerateQuietSignalAbstainSafely() {
        val missingReference = SideViewAlternatingPunchSegmenter().segment(
            listOf(frame(0L, omitLeftShoulder = true), frame(33L, omitLeftShoulder = true), frame(66L, omitLeftShoulder = true)),
        )
        assertEquals(SideViewAlternatingPunchState.ABSTAINED, missingReference.state)
        assertEquals("left_upper_arm_reference_insufficient", missingReference.reason)

        val still = SideViewAlternatingPunchSegmenter().segment((0..30).map { frame(it * 33L) })
        assertEquals(SideViewAlternatingPunchState.ABSTAINED, still.state)
        assertTrue(still.windows.isEmpty())
        assertNull(still.thresholds)
    }

    @Test
    fun irregularTimestampsArePreservedAndLongGapResetsMotionEvidence() {
        val timestamps = listOf(0L, 31L, 67L, 101L, 900L, 934L, 971L, 1_009L)
        val result = SideViewAlternatingPunchSegmenter().segment(
            timestamps.mapIndexed { index, timestamp -> frame(timestamp, handOffset = index * .03f) },
        )

        assertEquals(timestamps, result.derivedFrames.map { it.timestampMs })
        assertEquals(timestamps, result.qomTimeline.map { it.timestampMs })
        assertFalse(result.qomTimeline[4].isAvailable)
        assertEquals("side_view_punch_signal_unavailable", result.qomTimeline[4].reason)
    }

    @Test
    fun sharedBoundaryBelongsToLaterWindowWhileFinalEndRemainsOwned() {
        val result = SideViewAlternatingPunchResult(
            detectorVersion = QomDetectorConfig.SIDE_VIEW_ALTERNATING_PUNCH_V2,
            state = SideViewAlternatingPunchState.COMPLETED,
            reason = "test",
            candidateCount = 2,
            windows = listOf(
                SideViewPunchWindow(1, 5, 100L, 500L),
                SideViewPunchWindow(5, 9, 500L, 900L),
            ),
            derivedFrames = (0..9).map { index ->
                SideViewDerivedFrame(index * 100L, emptyMap(), null, null, null, null)
            },
            qomTimeline = emptyList(),
            thresholds = SideViewAlternatingPunchThresholds(1.0, 1.0, 1.0, 1.0),
            upperArmReferenceSampleCount = 0,
            upperArmMedian = null,
            upperArmMad = null,
            unresolvedRejectedCount = 0,
        )

        assertEquals(0, result.owningWindowIndex(4))
        assertEquals(1, result.owningWindowIndex(5))
        assertEquals(1, result.owningWindowIndex(9))
        assertNull(result.owningWindowIndex(10))
    }

    @Test
    fun v2ReferenceSmoothingDoesNotCrossConfiguredGap() {
        fun replay(preGapOffset: Float) = SideViewAlternatingPunchSegmenter().segment(
            listOf(
                frame(0L, handOffset = preGapOffset),
                frame(100L, handOffset = preGapOffset),
                frame(1_000L, handOffset = 0.2f),
                frame(1_100L, handOffset = 0.3f),
                frame(1_200L, handOffset = 0.4f),
            ),
        )

        assertEquals(
            replay(-20f).derivedFrames[2].smoothedReferenceCenter,
            replay(20f).derivedFrames[2].smoothedReferenceCenter,
        )
    }

    @Test
    fun v2RetainsSignedPerArmEvidenceAlongsideAbsoluteQom() {
        val result = SideViewAlternatingPunchSegmenter().segment(
            (0..12).map { index ->
                val offset = if (index <= 6) index * 0.2f else (12 - index) * 0.2f
                frame(index * 100L, handOffset = offset)
            },
        )
        val signed = result.derivedFrames.mapNotNull { it.signedLeftArmXVelocity }

        assertTrue(signed.any { it > 0.0 })
        assertTrue(signed.any { it < 0.0 })
        assertTrue(result.derivedFrames.any { it.instantaneousArmQom != null })
    }

    @Test
    fun v1HistoricalOwnershipExcludesNonfinalEndsAndIncludesLastDetectedEndEvenBeforeEof() {
        val result = SideViewAlternatingPunchResult(
            detectorVersion = QomDetectorConfig.SIDE_VIEW_ALTERNATING_PUNCH_V1,
            state = SideViewAlternatingPunchState.COMPLETED,
            reason = "test",
            candidateCount = 1,
            windows = listOf(
                SideViewPunchWindow(1, 3, 100L, 300L),
                SideViewPunchWindow(4, 5, 400L, 500L),
            ),
            derivedFrames = (0..7).map { SideViewDerivedFrame(it * 100L, emptyMap(), null, null, null, null) },
            qomTimeline = emptyList(),
            thresholds = SideViewAlternatingPunchThresholds(1.0, 1.0, 1.0, 1.0),
            upperArmReferenceSampleCount = 0,
            upperArmMedian = null,
            upperArmMad = null,
            unresolvedRejectedCount = 0,
        )

        assertNull(result.owningWindowIndex(3))
        assertEquals(1, result.owningWindowIndex(5))
    }

    private fun frame(
        timestampMs: Long,
        leftElbowX: Float = -1f,
        omitLeftShoulder: Boolean = false,
        handOffset: Float = 0f,
        omitted: Set<PoseLandmarkId> = emptySet(),
    ): PoseFrame {
        val landmarks = SideViewAlternatingPunchSegmenter.CENTROID_ROSTER.associateWith { id ->
            val point = when (id) {
                PoseLandmarkId.LEFT_SHOULDER -> Point3(0f, 0f, 0f)
                PoseLandmarkId.LEFT_ELBOW -> Point3(leftElbowX, 0f, 0f)
                PoseLandmarkId.LEFT_HIP -> Point3(0f, 1f, 0f)
                PoseLandmarkId.RIGHT_HIP -> Point3(1f, 1f, 0f)
                PoseLandmarkId.LEFT_WRIST,
                PoseLandmarkId.LEFT_THUMB,
                PoseLandmarkId.LEFT_INDEX,
                PoseLandmarkId.LEFT_PINKY -> Point3(id.ordinal / 20f + handOffset, id.ordinal / 30f, 0f)
                else -> Point3(id.ordinal / 20f, id.ordinal / 30f, 0f)
            }
            sample(point)
        }.toMutableMap()
        if (omitLeftShoulder) landmarks.remove(PoseLandmarkId.LEFT_SHOULDER)
        omitted.forEach(landmarks::remove)
        return PoseFrame(timestampMs, landmarks)
    }

    private fun sample(point: Point3) = PoseLandmarkSample(
        position = point,
        worldPosition = point,
        visibility = .95f,
        presence = .95f,
        source = LandmarkSource.OBSERVED,
    )
}
