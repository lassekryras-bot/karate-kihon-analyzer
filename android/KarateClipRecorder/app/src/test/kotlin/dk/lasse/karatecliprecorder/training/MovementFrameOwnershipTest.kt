package dk.lasse.karatecliprecorder.training

import dk.lasse.karateanalyzer.capture.qom.QomDetectorConfig
import dk.lasse.karateanalyzer.core.PoseFrame
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class MovementFrameOwnershipTest {
    @Test
    fun sharedBoundaryBelongsToLaterExperimentalMovementOnly() {
        val movements = listOf(
            movement(0L, 1_000L, QomDetectorConfig.SIDE_VIEW_ALTERNATING_PUNCH_V2),
            movement(1_000L, 2_000L, QomDetectorConfig.SIDE_VIEW_ALTERNATING_PUNCH_V2),
        )
        val frames = listOf(PoseFrame(0L, emptyMap()), PoseFrame(1L, emptyMap()), PoseFrame(2L, emptyMap()))

        assertFalse(MovementFrameOwnership.ownsTimestamp(0, movements, 1_000L, 2_000L))
        assertTrue(MovementFrameOwnership.ownsTimestamp(1, movements, 1_000L, 2_000L))
        assertEquals(listOf(0L, 2L), MovementFrameOwnership.frames(0, movements, frames, 2_000L).map { it.timestampMs })
        assertEquals(listOf(0L, 1L, 2L), MovementFrameOwnership.frames(1, movements, frames, 2_000L).map { it.timestampMs })
        assertTrue(MovementFrameOwnership.ownsTimestamp(1, movements, 2_000L, 2_000L), "Recording EOF remains owned")
    }

    @Test
    fun v2NonSharedEndAndLastDetectedEndAreNotOwnedUnlessTheyAreRecordingEof() {
        val movements = listOf(movement(0L, 1_000L, QomDetectorConfig.SIDE_VIEW_ALTERNATING_PUNCH_V2))

        assertFalse(MovementFrameOwnership.ownsTimestamp(0, movements, 1_000L, 2_000L))
        assertTrue(MovementFrameOwnership.ownsTimestamp(0, movements, 1_000L, 1_000L))
    }

    @Test
    fun v1HistoricalSharedAndTerminalOwnershipIsPreserved() {
        val movements = listOf(
            movement(0L, 1_000L, QomDetectorConfig.SIDE_VIEW_ALTERNATING_PUNCH_V1),
            movement(1_000L, 2_000L, QomDetectorConfig.SIDE_VIEW_ALTERNATING_PUNCH_V1),
        )

        assertFalse(MovementFrameOwnership.ownsTimestamp(0, movements, 1_000L, 2_000L))
        assertTrue(MovementFrameOwnership.ownsTimestamp(1, movements, 2_000L, 3_000L))
    }

    @Test
    fun legacyClosedIntervalsRemainUnchanged() {
        val movements = listOf(movement(0L, 1_000L, QomDetectorConfig.SUPPORTED_WORLD_V2), movement(1_000L, 2_000L, QomDetectorConfig.SUPPORTED_WORLD_V2))
        assertTrue(MovementFrameOwnership.ownsTimestamp(0, movements, 1_000L, 2_000L))
    }

    private fun movement(startUs: Long, endUs: Long, version: String) = SessionMovement(
        sessionId = "session",
        startUs = startUs,
        endUs = endUs,
        playbackStartUs = startUs,
        playbackEndUs = endUs,
        segmentationSource = "test",
        segmentationVersion = version,
        segmentationTrackId = "track",
    )
}
