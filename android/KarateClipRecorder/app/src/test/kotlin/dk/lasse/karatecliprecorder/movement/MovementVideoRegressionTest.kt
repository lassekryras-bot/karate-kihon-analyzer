package dk.lasse.karatecliprecorder.movement

import android.content.Context
import android.graphics.RectF
import android.media.MediaPlayer
import android.media.PlaybackParams
import androidx.test.core.app.ApplicationProvider
import java.io.File
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import kotlin.test.*

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28])
class MovementVideoRegressionTest {
    private val context = ApplicationProvider.getApplicationContext<Context>()

    private class Decoder : MediaPlayer() {
        val seeks = mutableListOf<Pair<Long, Int>>()
        var completion: OnSeekCompleteListener? = null
        override fun seekTo(msec: Long, mode: Int) { seeks.add(msec to mode) }
        override fun setOnSeekCompleteListener(listener: OnSeekCompleteListener?) { completion = listener }
        override fun getPlaybackParams(): PlaybackParams = PlaybackParams().setSpeed(1f)
        override fun isPlaying(): Boolean = false
        override fun getVideoWidth(): Int = 1080
        override fun getVideoHeight(): Int = 1920
        fun completeSeek() { completion?.onSeekComplete(this) }
    }

    private fun state() = MovementTimelineState(500_000L, 1_350_000L, 820_000L, PlayerMode.ANALYSIS)
    private fun data() = MovementPresentationData(1, "Straight punch", "Left arm", 820_000L,
        PlayerMode.ANALYSIS, emptyList(), emptyList(), emptyList(), emptyMap(), null,
        MovementDebugData("m", "s", 500_000L, 1_350_000L, 500_000L, 1_350_000L, 850_000L, 1), emptyList())

    @Test fun compactPlaybackObservationsCannotSeekBackToTheMovementBoundary() {
        val state = state()
        val view = MovementPresentationPlayerView(context, state, null)
        val decoder = Decoder()
        try {
            view.onVideoPrepared(decoder)
            assertEquals(listOf(820L to MediaPlayer.SEEK_CLOSEST), decoder.seeks)
            state.setPlaying(true)
            view.onVideoPosition(0) // A stale position while the initial seek is pending.
            assertEquals(820_000L, state.currentTimestampUs)
            decoder.completeSeek()
            listOf(330, 450, 560, 700, 900).forEach(view::onVideoPosition)
            assertEquals(900_000L, state.currentTimestampUs)
            assertEquals(1, decoder.seeks.size, "Playback must not feed another seek to the decoder")
            state.setPlaying(false)
            state.seekUs(610_000L)
            assertEquals(610L to MediaPlayer.SEEK_CLOSEST, decoder.seeks.last())
            decoder.completeSeek()
            state.setPlaying(true)
            view.onVideoPosition(1_370)
            assertEquals(1_350_000L, state.currentTimestampUs)
            assertFalse(state.isPlaying)
            assertEquals(2, decoder.seeks.size)
        } finally { state.setPlaying(false); decoder.release() }
    }

    @Test fun expandedPlaybackObservationsDoNotSeekAndExplicitSeeksStayExact() {
        val state = state()
        val dialog = MovementExpandedInspectionDialog(context, state, null, data())
        val decoder = Decoder()
        try {
            dialog.onVideoPrepared(decoder)
            state.setPlaying(true)
            dialog.onVideoPosition(0)
            assertEquals(820_000L, state.currentTimestampUs)
            decoder.completeSeek()
            listOf(330, 450, 560, 700, 900).forEach(dialog::onVideoPosition)
            assertEquals(900_000L, state.currentTimestampUs)
            assertEquals(listOf(820L to MediaPlayer.SEEK_CLOSEST), decoder.seeks)
            state.setPlaying(false)
            state.seekUs(610_000L)
            assertEquals(610L to MediaPlayer.SEEK_CLOSEST, decoder.seeks.last())
            dialog.cleanup()
            dialog.onVideoPosition(1000)
            assertEquals(610_000L, state.currentTimestampUs)
        } finally { dialog.cleanup(); decoder.release() }
    }

    @Test fun playbackUpdatesNotifyRenderersWithoutIssuingSeekCommands() {
        val state = state()
        val timestamps = mutableListOf<Long>()
        val seeks = mutableListOf<Long>()
        state.addListener(object : MovementTimelineState.TimelineListener {
            override fun onTimestampChanged(timestampUs: Long, progress: Double) { timestamps.add(timestampUs) }
            override fun onSeekRequested(timestampUs: Long) { seeks.add(timestampUs) }
        })
        state.updatePlaybackPositionUs(300_000L)
        state.updatePlaybackPositionUs(600_000L)
        state.updatePlaybackPositionUs(1_500_000L)
        assertEquals(listOf(500_000L, 600_000L, 1_350_000L), timestamps)
        assertTrue(seeks.isEmpty())
        state.seekUs(800_000L)
        assertEquals(listOf(800_000L), seeks)
    }

    @Test fun rapidScrubbingCoalescesAndOldDecoderCallbacksCannotClearANewSeek() {
        val seeking = MovementVideoSeeking()
        val first = Decoder()
        val replacement = Decoder()
        try {
            seeking.onPrepared(first)
            seeking.seekToUs(800_000L)
            seeking.seekToUs(900_000L)
            seeking.seekToUs(1_100_000L)
            assertEquals(listOf(800L to MediaPlayer.SEEK_CLOSEST), first.seeks)
            first.completeSeek()
            assertTrue(seeking.isPending)
            assertEquals(1_100L to MediaPlayer.SEEK_CLOSEST, first.seeks.last())
            first.completeSeek()
            assertFalse(seeking.isPending)
            seeking.onPrepared(replacement)
            seeking.seekToUs(950_000L)
            first.completeSeek()
            assertTrue(seeking.isPending)
            replacement.completeSeek()
            assertFalse(seeking.isPending)
        } finally { seeking.clear(); first.release(); replacement.release() }
    }

    @Test fun portraitOverlayFitsBeforeVideoRelayoutAndRedrawsWhenLayoutChanges() {
        val file = File.createTempFile("movement-viewport", ".mp4", context.cacheDir)
        val state = state()
        val view = MovementPresentationPlayerView(context, state, file)
        val decoder = Decoder()
        try {
            view.overlayView.layout(0, 0, 650, 380)
            view.videoView.layout(0, 0, 650, 380) // Initial size, before media metadata.
            assertNull(view.overlayView.videoBoundsProvider(), "Do not draw stretched geometry before metadata")
            val overlayShadow = shadowOf(view.overlayView)
            overlayShadow.clearWasInvalidated()
            view.onVideoPrepared(decoder)
            assertTrue(overlayShadow.wasInvalidated())
            assertBounds(RectF(218.125f, 0f, 431.875f, 380f), view.overlayView.videoBoundsProvider())
            overlayShadow.clearWasInvalidated()
            view.videoView.layout(218, 0, 432, 380) // VideoView's eventual portrait size.
            assertTrue(overlayShadow.wasInvalidated(), "A sibling layout change must redraw the paused overlay")
            assertBounds(RectF(218.125f, 0f, 431.875f, 380f), view.overlayView.videoBoundsProvider())
            assertEquals(820_000L, state.currentTimestampUs, "No timeline interaction was needed")
        } finally { view.setPlaybackActive(false); decoder.release(); file.delete() }
    }

    @Test fun expandedOverlayAlsoWaitsForMetadataAndTracksVideoLayout() {
        val file = File.createTempFile("movement-expanded-viewport", ".mp4", context.cacheDir)
        val dialog = MovementExpandedInspectionDialog(context, state(), file, data())
        val decoder = Decoder()
        try {
            dialog.overlayView.layout(0, 0, 650, 380)
            dialog.videoView.layout(0, 0, 650, 380)
            assertNull(dialog.overlayView.videoBoundsProvider())
            dialog.onVideoPrepared(decoder)
            assertBounds(RectF(218.125f, 0f, 431.875f, 380f), dialog.overlayView.videoBoundsProvider())
            shadowOf(dialog.overlayView).clearWasInvalidated()
            dialog.videoView.layout(218, 0, 432, 380)
            assertTrue(shadowOf(dialog.overlayView).wasInvalidated())
        } finally { dialog.cleanup(); decoder.release(); file.delete() }
    }

    @Test fun landscapeVideoIsLetterboxedWithoutStretchingTheOverlay() {
        assertBounds(RectF(0f, 133.4375f, 380f, 347.1875f),
            movementVideoBounds(RectF(0f, 50f, 380f, 430.625f), 1920, 1080))
        assertNull(movementVideoBounds(RectF(0f, 0f, 650f, 380f), 0, 0))
    }

    private fun assertBounds(expected: RectF, actual: RectF?) {
        assertNotNull(actual)
        assertEquals(expected.left, actual.left, 0.01f)
        assertEquals(expected.top, actual.top, 0.01f)
        assertEquals(expected.right, actual.right, 0.01f)
        assertEquals(expected.bottom, actual.bottom, 0.01f)
    }
}
