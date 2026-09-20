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
}
