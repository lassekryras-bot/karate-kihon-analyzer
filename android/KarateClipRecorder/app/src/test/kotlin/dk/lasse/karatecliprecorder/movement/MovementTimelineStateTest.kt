package dk.lasse.karatecliprecorder.movement

import org.junit.Test
import kotlin.test.*

class MovementTimelineStateTest {

    @Test
    fun clampingWithinPlaybackBounds() {
        val state = MovementTimelineState(
            playbackStartUs = 200_000L,
            playbackEndUs = 1_800_000L,
            initialTimestampUs = 500_000L,
        )

        assertEquals(500_000L, state.currentTimestampUs)
        assertEquals(1_600_000L, state.durationUs)

        state.seekUs(100_000L)
        assertEquals(200_000L, state.currentTimestampUs)

        state.seekUs(2_000_000L)
        assertEquals(1_800_000L, state.currentTimestampUs)
    }

    @Test
    fun modeSwitchingPreservesCurrentTimestamp() {
        val state = MovementTimelineState(
            playbackStartUs = 0L,
            playbackEndUs = 1_000_000L,
            initialTimestampUs = 450_000L,
            initialMode = PlayerMode.ANALYSIS,
        )

        assertEquals(PlayerMode.ANALYSIS, state.currentMode)
        assertEquals(450_000L, state.currentTimestampUs)

        state.setMode(PlayerMode.VIDEO)
        assertEquals(PlayerMode.VIDEO, state.currentMode)
        assertEquals(450_000L, state.currentTimestampUs)

        state.setMode(PlayerMode.GRAPH)
        assertEquals(PlayerMode.GRAPH, state.currentMode)
        assertEquals(450_000L, state.currentTimestampUs)
    }

    @Test
    fun frameSteppingUsesKnownSampleTimestamps() {
        val samples = listOf(200_000L, 250_000L, 310_000L, 380_000L, 500_000L)
        val state = MovementTimelineState(
            playbackStartUs = 200_000L,
            playbackEndUs = 600_000L,
            initialTimestampUs = 310_000L,
            knownSampleTimestampsUs = samples,
        )

        state.stepNextSample()
        assertEquals(380_000L, state.currentTimestampUs)

        state.stepNextSample()
        assertEquals(500_000L, state.currentTimestampUs)

        // At end of samples
        state.stepNextSample()
        assertEquals(500_000L, state.currentTimestampUs)

        state.stepPreviousSample()
        assertEquals(380_000L, state.currentTimestampUs)

        state.stepPreviousSample()
        assertEquals(310_000L, state.currentTimestampUs)

        state.stepPreviousSample()
        assertEquals(250_000L, state.currentTimestampUs)

        state.stepPreviousSample()
        assertEquals(200_000L, state.currentTimestampUs)

        // At start of samples
        state.stepPreviousSample()
        assertEquals(200_000L, state.currentTimestampUs)
    }

    @Test
    fun seekProgressCalculatesCorrectMicrosecondOffset() {
        val state = MovementTimelineState(
            playbackStartUs = 1_000_000L,
            playbackEndUs = 2_000_000L,
        )

        state.seekProgress(0.0)
        assertEquals(1_000_000L, state.currentTimestampUs)

        state.seekProgress(0.5)
        assertEquals(1_500_000L, state.currentTimestampUs)

        state.seekProgress(1.0)
        assertEquals(2_000_000L, state.currentTimestampUs)
    }

    @Test
    fun playbackRateAndListenerUpdates() {
        val state = MovementTimelineState(
            playbackStartUs = 0L,
            playbackEndUs = 1_000_000L,
        )

        var notifiedPlaying: Boolean? = null
        var notifiedRate: Double? = null

        state.addListener(object : MovementTimelineState.TimelineListener {
            override fun onPlaybackStateChanged(isPlaying: Boolean) {
                notifiedPlaying = isPlaying
            }
            override fun onPlaybackRateChanged(rate: Double) {
                notifiedRate = rate
            }
        })

        state.setPlaying(true)
        assertTrue(state.isPlaying)
        assertEquals(true, notifiedPlaying)

        state.setPlaybackRate(0.5)
        assertEquals(0.5, state.playbackRate)
        assertEquals(0.5, notifiedRate)
    }

    @Test
    fun frameSteppingDisabledWithoutDiscreteSamples() {
        val state = MovementTimelineState(
            playbackStartUs = 0L,
            playbackEndUs = 1_000_000L,
            initialTimestampUs = 500_000L,
            knownSampleTimestampsUs = emptyList(),
        )

        assertFalse(state.canStepSamples)
        // Stepping should not alter timestamp when no discrete samples exist
        state.stepNextSample()
        assertEquals(500_000L, state.currentTimestampUs)

        state.stepPreviousSample()
        assertEquals(500_000L, state.currentTimestampUs)
    }

    @Test
    fun selectedPlotKeyUpdatesAndNotifies() {
        val state = MovementTimelineState(
            playbackStartUs = 0L,
            playbackEndUs = 1_000_000L,
            initialPlotKey = "initial_plot",
        )

        assertEquals("initial_plot", state.selectedPlotKey)
        var notifiedKey: String? = null
        state.addListener(object : MovementTimelineState.TimelineListener {
            override fun onSelectedPlotKeyChanged(key: String?) {
                notifiedKey = key
            }
        })

        state.setSelectedPlotKey("speed")
        assertEquals("speed", state.selectedPlotKey)
        assertEquals("speed", notifiedKey)
    }

    @Test
    fun opensPausedAtCanonicalImpactShowingReplayState() {
        val state = MovementTimelineState(
            playbackStartUs = 100_000L,
            playbackEndUs = 900_000L,
            initialTimestampUs = 600_000L,
            canonicalImpactUs = 600_000L,
            replayStartUs = 100_000L,
        )

        assertEquals(600_000L, state.currentTimestampUs)
        assertFalse(state.isPlaying)
        assertEquals(PlaybackControlState.PAUSED_REPLAY, state.playbackControlState)
    }

    @Test
    fun clickingReplayAtOrBeyondImpactSeeksToStartAndPlaysForward() {
        val state = MovementTimelineState(
            playbackStartUs = 100_000L,
            playbackEndUs = 900_000L,
            initialTimestampUs = 600_000L,
            canonicalImpactUs = 600_000L,
            replayStartUs = 100_000L,
        )

        assertEquals(PlaybackControlState.PAUSED_REPLAY, state.playbackControlState)

        // Toggle play or replay
        state.togglePlayOrReplay()

        // Should seek to replayStartUs (100_000L) and start playing
        assertEquals(100_000L, state.currentTimestampUs)
        assertTrue(state.isPlaying)
        assertEquals(PlaybackControlState.PLAYING, state.playbackControlState)
    }

    @Test
    fun playbackHaltsAtCanonicalImpactTimestamp() {
        val state = MovementTimelineState(
            playbackStartUs = 100_000L,
            playbackEndUs = 900_000L,
            initialTimestampUs = 100_000L,
            canonicalImpactUs = 600_000L,
            replayStartUs = 100_000L,
        )

        state.setPlaying(true)
        assertTrue(state.isPlaying)

        // Advance before impact
        state.updatePlaybackPositionUs(400_000L)
        assertTrue(state.isPlaying)
        assertEquals(400_000L, state.currentTimestampUs)

        // Advance to or past canonical impact
        state.updatePlaybackPositionUs(650_000L)
        assertFalse(state.isPlaying)
        assertEquals(600_000L, state.currentTimestampUs)
        assertEquals(PlaybackControlState.PAUSED_REPLAY, state.playbackControlState)
    }

    @Test
    fun postImpactIntervalCanBeManuallyInspected() {
        val state = MovementTimelineState(
            playbackStartUs = 100_000L,
            playbackEndUs = 900_000L,
            initialTimestampUs = 600_000L,
            canonicalImpactUs = 600_000L,
            replayStartUs = 100_000L,
        )

        // Manual seek past canonical impact
        state.seekUs(750_000L)
        assertEquals(750_000L, state.currentTimestampUs)
        assertFalse(state.isPlaying)
        assertEquals(PlaybackControlState.PAUSED_REPLAY, state.playbackControlState)

        // Clicking replay rewinds back to replayStartUs
        state.togglePlayOrReplay()
        assertEquals(100_000L, state.currentTimestampUs)
        assertTrue(state.isPlaying)
    }

    @Test
    fun missingCanonicalImpactFallsBackToFullRange() {
        val state = MovementTimelineState(
            playbackStartUs = 100_000L,
            playbackEndUs = 900_000L,
            initialTimestampUs = 100_000L,
            canonicalImpactUs = null,
        )

        assertEquals(PlaybackControlState.PAUSED_PLAY, state.playbackControlState)

        state.setPlaying(true)
        state.updatePlaybackPositionUs(500_000L)
        assertTrue(state.isPlaying)

        // Plays all the way to playbackEndUs
        state.updatePlaybackPositionUs(900_000L)
        assertFalse(state.isPlaying)
        assertEquals(900_000L, state.currentTimestampUs)
        assertEquals(PlaybackControlState.PAUSED_REPLAY, state.playbackControlState)
    }
}
