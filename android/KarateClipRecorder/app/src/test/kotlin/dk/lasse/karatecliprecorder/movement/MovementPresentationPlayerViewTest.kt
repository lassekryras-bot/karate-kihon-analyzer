package dk.lasse.karatecliprecorder.movement

import android.content.Context
import android.media.MediaPlayer
import android.media.PlaybackParams
import android.view.View
import androidx.test.core.app.ApplicationProvider
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import kotlin.test.*

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28])
class MovementPresentationPlayerViewTest {
    private val context = ApplicationProvider.getApplicationContext<Context>()

    /** Models Android's nonzero-speed autoplay, and records actual decoder calls. */
    private class RecordingMediaPlayer(private val reportPlayingImmediately: Boolean = true) : MediaPlayer() {
        val appliedRates = mutableListOf<Float>()
        var decoderPlaying = false
        var pauseCalls = 0
        var invalidPauseCalls = 0
        private var hasStarted = false
        private var rate = 1f
        override fun getPlaybackParams(): PlaybackParams = PlaybackParams().setSpeed(rate)
        override fun setPlaybackParams(params: PlaybackParams) {
            rate = params.speed
            appliedRates.add(rate)
            decoderPlaying = rate != 0f
            hasStarted = hasStarted || decoderPlaying
        }
        override fun pause() {
            pauseCalls++
            if (!hasStarted) invalidPauseCalls++
            decoderPlaying = false
        }
        override fun isPlaying(): Boolean = decoderPlaying && reportPlayingImmediately
    }

    private fun state() = MovementTimelineState(0L, 1_000_000L)
    private fun data() = MovementPresentationData(1, "Straight punch", "Left arm", 500_000L,
        PlayerMode.VIDEO, emptyList(), emptyList(), emptyList(), emptyMap(), null,
        MovementDebugData("m1", "s1", 0L, 1_000_000L, 0L, 1_000_000L, 1_000_000L, 1),
        emptyList(), analysisNotice = "Analysis evidence is unavailable for this movement.")

    @Test fun initialPreparationDoesNotPauseAnUnstartedDecoder() {
        val state = state()
        val view = MovementPresentationPlayerView(context, state, null)
        val dialog = MovementExpandedInspectionDialog(context, state, null, data())
        val compactMedia = RecordingMediaPlayer()
        val expandedMedia = RecordingMediaPlayer()
        try {
            view.onVideoPrepared(compactMedia)
            view.setPlaybackActive(false)
            dialog.onVideoPrepared(expandedMedia)
            assertEquals(0, compactMedia.invalidPauseCalls)
            assertEquals(0, expandedMedia.invalidPauseCalls)
            assertFalse(compactMedia.decoderPlaying)
            assertFalse(expandedMedia.decoderPlaying)
        } finally { dialog.cleanup(); compactMedia.release(); expandedMedia.release() }
    }

    @Test fun speedAutostartIsPausedEvenWhenIsPlayingHasNotCaughtUp() {
        val state = state()
        val view = MovementPresentationPlayerView(context, state, null)
        val media = RecordingMediaPlayer(reportPlayingImmediately = false)
        try {
            view.onVideoPrepared(media)
            state.setPlaybackRate(0.25)
            assertEquals(listOf(0.25f), media.appliedRates)
            assertFalse(media.decoderPlaying)
            assertEquals(1, media.pauseCalls)
            assertEquals(0, media.invalidPauseCalls)
        } finally { media.release() }
    }

    @Test fun changingPlaybackRateWhilePausedPausesTheActualDecoder() {
        val state = state()
        val view = MovementPresentationPlayerView(context, state, null)
        val media = RecordingMediaPlayer()
        try {
            view.onVideoPrepared(media)
            val pauses = media.pauseCalls
            state.setPlaybackRate(0.5)
            assertEquals(listOf(0.5f), media.appliedRates)
            assertTrue(media.pauseCalls > pauses)
            assertFalse(media.decoderPlaying)
            assertFalse(state.isPlaying)
        } finally { media.release() }
    }

    @Test fun reactivationAppliesSpeedChangedDuringExpandedInspection() {
        val state = state()
        val view = MovementPresentationPlayerView(context, state, null)
        val media = RecordingMediaPlayer()
        try {
            view.onVideoPrepared(media)
            view.setPlaybackActive(false)
            val pauses = media.pauseCalls
            state.setPlaybackRate(0.25)
            assertTrue(media.appliedRates.isEmpty())
            assertEquals(pauses, media.pauseCalls, "Inactive rate changes must not touch the decoder")
            view.setPlaybackActive(true)
            assertEquals(listOf(0.25f), media.appliedRates)
            assertFalse(media.decoderPlaying)
        } finally { media.release() }
    }

    @Test fun preparationWhileInactiveDefersRateUntilOwnershipReturns() {
        val state = state()
        val view = MovementPresentationPlayerView(context, state, null)
        val media = RecordingMediaPlayer()
        try {
            view.setPlaybackActive(false)
            state.setPlaybackRate(0.1)
            view.onVideoPrepared(media)
            assertTrue(media.appliedRates.isEmpty())
            assertFalse(media.decoderPlaying)
            view.setPlaybackActive(true)
            assertEquals(listOf(0.1f), media.appliedRates)
            assertFalse(media.decoderPlaying)
        } finally { media.release() }
    }

    @Test fun expandedPreparationAndRateChangesPreservePausedIntent() {
        val state = state().apply { setPlaybackRate(0.25) }
        val dialog = MovementExpandedInspectionDialog(context, state, null, data())
        val media = RecordingMediaPlayer()
        try {
            dialog.onVideoPrepared(media)
            assertEquals(listOf(0.25f), media.appliedRates)
            assertFalse(media.decoderPlaying)
            state.setPlaybackRate(0.5)
            assertEquals(listOf(0.25f, 0.5f), media.appliedRates)
            assertFalse(media.decoderPlaying)
        } finally { dialog.cleanup(); media.release() }
    }

    @Test fun rateChangeWhilePlayingDoesNotPauseDecoder() {
        val state = state()
        val view = MovementPresentationPlayerView(context, state, null)
        val media = RecordingMediaPlayer()
        try {
            view.onVideoPrepared(media)
            state.setPlaying(true)
            val pauses = media.pauseCalls
            state.setPlaybackRate(0.5)
            assertEquals(listOf(0.5f), media.appliedRates)
            assertTrue(media.decoderPlaying)
            assertEquals(pauses, media.pauseCalls)
        } finally { state.setPlaying(false); media.release() }
    }

    @Test fun graphPlaybackAndRateChangesKeepTheHiddenDecoderPaused() {
        val state = state()
        val view = MovementPresentationPlayerView(context, state, null)
        val media = RecordingMediaPlayer()
        try {
            view.onVideoPrepared(media)
            state.setPlaying(true)
            state.setPlaybackRate(0.5)
            assertTrue(media.decoderPlaying)
            state.setMode(PlayerMode.GRAPH)
            assertFalse(media.decoderPlaying)
            state.setPlaybackRate(0.25)
            assertFalse(media.decoderPlaying)
            assertTrue(state.isPlaying)
        } finally { state.setPlaying(false); media.release() }
    }

    @Test fun cleanedDialogCannotChangePlaybackOrReceiveRateUpdatesAgain() {
        val state = state()
        val dialog = MovementExpandedInspectionDialog(context, state, null, data())
        val media = RecordingMediaPlayer()
        try {
            dialog.onVideoPrepared(media)
            dialog.cleanup()
            state.setPlaying(true) // Compact player now owns playback.
            dialog.cleanup()
            state.setPlaybackRate(0.25)
            assertTrue(state.isPlaying, "Repeated cleanup must not stop the new owner")
            assertTrue(media.appliedRates.isEmpty())
        } finally { state.setPlaying(false); media.release() }
    }

    @Test fun bothPlayersExplainUnavailableAnalysisAndVideo() {
        val state = state()
        val view = MovementPresentationPlayerView(context, state, null)
        val dialog = MovementExpandedInspectionDialog(context, state, null, data())
        try {
            state.setMode(PlayerMode.ANALYSIS)
            assertEquals(View.VISIBLE, view.unavailableLabel.visibility)
            assertEquals(View.VISIBLE, dialog.unavailableLabel.visibility)
            assertTrue(view.unavailableLabel.text.contains("Analysis evidence is unavailable"))
            assertTrue(dialog.unavailableLabel.text.contains("Analysis evidence is unavailable"))
            state.setMode(PlayerMode.VIDEO)
            assertTrue(view.unavailableLabel.text.contains("Video media is unavailable"))
            assertTrue(dialog.unavailableLabel.text.contains("Video media is unavailable"))
        } finally { dialog.cleanup() }
    }
}
