package dk.lasse.karatecliprecorder.movement

import android.content.Context
import android.graphics.RectF
import android.widget.FrameLayout
import android.widget.VideoView
import androidx.test.core.app.ApplicationProvider
import dk.lasse.karateanalyzer.core.LandmarkSource
import dk.lasse.karateanalyzer.core.Point3
import dk.lasse.karateanalyzer.core.PoseFrame
import dk.lasse.karateanalyzer.core.PoseLandmarkId
import dk.lasse.karateanalyzer.core.PoseLandmarkSample
import org.junit.Test
import org.junit.runner.RunWith
import android.media.MediaPlayer
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import kotlin.math.abs
import kotlin.test.*

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28])
class MovementVideoViewportTest {

    private class DummyDecoder : MediaPlayer() {
        override fun getVideoWidth(): Int = 1080
        override fun getVideoHeight(): Int = 1920
    }

    private fun sample(x: Float, y: Float) =
        PoseLandmarkSample(Point3(x, y, 0f), Point3(x, y, 0f), 0.9f, 0.9f, LandmarkSource.OBSERVED)

    private fun buildFrame(timestampMs: Long, headY: Float, footY: Float): PoseFrame {
        val map = mutableMapOf<PoseLandmarkId, PoseLandmarkSample>()
        map[PoseLandmarkId.NOSE] = sample(0.5f, headY)
        map[PoseLandmarkId.LEFT_EYE] = sample(0.48f, headY)
        map[PoseLandmarkId.RIGHT_EYE] = sample(0.52f, headY)
        map[PoseLandmarkId.LEFT_SHOULDER] = sample(0.45f, headY + 0.15f)
        map[PoseLandmarkId.RIGHT_SHOULDER] = sample(0.55f, headY + 0.15f)
        map[PoseLandmarkId.LEFT_HIP] = sample(0.47f, headY + 0.40f)
        map[PoseLandmarkId.RIGHT_HIP] = sample(0.53f, headY + 0.40f)
        map[PoseLandmarkId.LEFT_ANKLE] = sample(0.46f, footY - 0.05f)
        map[PoseLandmarkId.RIGHT_ANKLE] = sample(0.54f, footY - 0.05f)
        map[PoseLandmarkId.LEFT_HEEL] = sample(0.46f, footY)
        map[PoseLandmarkId.RIGHT_HEEL] = sample(0.54f, footY)
        return PoseFrame(timestampMs, map)
    }

    @Test
    fun standardAspectFitWithFullFramePreservesWholeVideo() {
        val viewBounds = RectF(0f, 0f, 1000f, 1000f)
        // 16:9 video (1920 x 1080) in 1:1 view -> letterboxed vertically
        val bounds = movementVideoBounds(viewBounds, 1920, 1080, VerticalBounds.FULL)
        assertNotNull(bounds)
        assertEquals(0f, bounds.left)
        assertEquals(1000f, bounds.right)
        val expectedHeight = 1000f * (1080f / 1920f) // 562.5f
        assertEquals(expectedHeight, bounds.height(), 0.1f)
        assertEquals((1000f - expectedHeight) / 2f, bounds.top, 0.1f)
    }

    @Test
    fun verticalFramingAlignsFramedBoundsToDisplayEdges() {
        val viewBounds = RectF(0f, 0f, 1000f, 1000f)
        val verticalBounds = VerticalBounds(topY = 0.20f, bottomY = 0.80f) // height = 0.60
        // Video 1080 x 1920 (portrait) in 1:1 view
        val bounds = movementVideoBounds(viewBounds, 1080, 1920, verticalBounds)
        assertNotNull(bounds)

        // The framed region: y in [0.20, 0.80]
        // When y = 0.20: pixel Y should map to displayTop (within viewBounds)
        val topPx = bounds.top + verticalBounds.topY * bounds.height()
        val bottomPx = bounds.top + verticalBounds.bottomY * bounds.height()

        assertTrue(topPx >= viewBounds.top - 0.1f)
        assertTrue(bottomPx <= viewBounds.bottom + 0.1f)

        // Full width must never be cropped horizontally
        assertTrue(bounds.width() >= 0f)
        assertTrue(bounds.left >= viewBounds.left - 0.1f && bounds.right <= viewBounds.right + 0.1f)
    }

    @Test
    fun calculatorDerivesStablePercentileBoundsAcrossFrames() {
        // Create 10 frames where head is around y = 0.20 with a single noisy outlier at y = 0.05
        // Feet around y = 0.85 with a single noisy outlier at y = 0.98
        val frames = mutableListOf<PoseFrame>()
        frames.add(buildFrame(100L, headY = 0.05f, footY = 0.85f)) // outlier spike
        for (i in 1..8) {
            frames.add(buildFrame(100L + i * 50L, headY = 0.20f, footY = 0.85f))
        }
        frames.add(buildFrame(550L, headY = 0.20f, footY = 0.98f)) // outlier spike

        val bounds = MovementVerticalViewportCalculator.computeVerticalBounds(frames, 100_000L, 550_000L)

        // The 10th/90th percentile should filter out the single-frame outliers
        // Expected head near 0.20, feet near 0.85 -> athlete height ~ 0.65
        // Headroom = 0.65 * 0.15 = 0.0975 -> topY ~ 0.20 - 0.0975 ≈ 0.1025
        // Foot margin = 0.65 * 0.08 = 0.052 -> bottomY ~ 0.85 + 0.052 ≈ 0.902
        assertTrue(bounds.topY > 0.06f, "Outlier at 0.05 should be rejected, was ${bounds.topY}")
        assertTrue(bounds.bottomY < 0.97f, "Outlier at 0.98 should be rejected, was ${bounds.bottomY}")
        assertTrue(bounds.height >= MovementVerticalViewportCalculator.MIN_WINDOW_HEIGHT)
    }

    @Test
    fun calculatorPreservesMinimumWindowHeight() {
        // Very small athlete in center of frame: head at 0.48, feet at 0.54 (height = 0.06)
        // Wait, height < 0.15 falls back to full frame
        // If athlete is 0.20 tall (head at 0.40, feet at 0.60):
        val frames = (1..5).map { i ->
            buildFrame(i * 100L, headY = 0.40f, footY = 0.60f)
        }
        val bounds = MovementVerticalViewportCalculator.computeVerticalBounds(frames, 100_000L, 500_000L)

        // Window height should be at least MIN_WINDOW_HEIGHT (0.45f)
        assertTrue(bounds.height >= MovementVerticalViewportCalculator.MIN_WINDOW_HEIGHT - 0.001f)
    }

    @Test
    fun calculatorFallsBackToFullFrameWhenLandmarksUnavailable() {
        val emptyBounds = MovementVerticalViewportCalculator.computeVerticalBounds(emptyList(), 0L, 1_000_000L)
        assertEquals(VerticalBounds.FULL, emptyBounds)

        // Only 1 frame (below minimum 3 frames)
        val singleFrame = listOf(buildFrame(100L, headY = 0.2f, footY = 0.8f))
        val sparseBounds = MovementVerticalViewportCalculator.computeVerticalBounds(singleFrame, 0L, 1_000_000L)
        assertEquals(VerticalBounds.FULL, sparseBounds)
    }

    @Test
    fun viewportContainerGeometryEnforcesSourceIntervalOnly() {
        val viewBounds = RectF(0f, 0f, 1000f, 2000f)
        val verticalBounds = VerticalBounds(topY = 0.20f, bottomY = 0.80f)
        val geometry = movementVideoViewportGeometry(viewBounds, 1080, 1920, verticalBounds)
        assertNotNull(geometry)

        // Framed aspect: 1080 / (1920 * 0.6) = 1080 / 1152 = 0.9375
        // View aspect: 1000 / 2000 = 0.5 < 0.9375 -> width is constrained to 1000f
        assertEquals(1000f, geometry.viewportRect.width(), 0.01f)
        val expectedDisplayHeight = 1000f / (1080f / (1920f * 0.60f)) // 1066.6667f
        assertEquals(expectedDisplayHeight, geometry.viewportRect.height(), 0.1f)
        assertEquals(0f, geometry.viewportRect.left, 0.01f)
        val expectedDisplayTop = 1000f - expectedDisplayHeight * 0.5f // 466.6667f
        assertEquals(expectedDisplayTop, geometry.viewportRect.top, 0.1f)

        // Full video height scaled to crop
        val fullVideoHeight = expectedDisplayHeight / 0.60f // 1777.7778f
        assertEquals(fullVideoHeight, geometry.videoRect.height(), 0.1f)

        // Top and bottom mapping strictly aligns to viewport container bounds
        val sourceTopYInView = geometry.videoRect.top + 0.20f * fullVideoHeight
        val sourceBottomYInView = geometry.videoRect.top + 0.80f * fullVideoHeight
        assertEquals(geometry.viewportRect.top, sourceTopYInView, 0.1f)
        assertEquals(geometry.viewportRect.bottom, sourceBottomYInView, 0.1f)

        // Horizontal source coverage remains strictly X in [0, 1]
        assertEquals(viewBounds.width(), geometry.viewportRect.width(), 0.01f)
        assertEquals(geometry.viewportRect.left, geometry.videoRect.left, 0.01f)
        assertEquals(geometry.viewportRect.right, geometry.videoRect.right, 0.01f)
    }

    @Test
    fun clippingContainerLayoutEnforcesClippingAndCorrectMargins() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val cropContainer = FrameLayout(context)
        val videoView = VideoView(context)
        val state = MovementTimelineState(0L, 1_000_000L)
        val overlayView = MovementAnalysisOverlayView(context, state)

        cropContainer.addView(videoView)
        cropContainer.addView(overlayView)

        val viewBounds = RectF(0f, 0f, 1000f, 2000f)
        val verticalBounds = VerticalBounds(topY = 0.20f, bottomY = 0.80f)

        applyMovementVideoViewport(
            cropContainer = cropContainer,
            video = videoView,
            overlay = overlayView,
            viewBounds = viewBounds,
            videoWidth = 1080,
            videoHeight = 1920,
            verticalBounds = verticalBounds,
        )

        // Verify clipping flags
        assertTrue(cropContainer.clipChildren, "cropContainer must clip children")
        assertTrue(cropContainer.clipToOutline, "cropContainer must clip to outline")
        assertEquals(android.view.ViewOutlineProvider.BOUNDS, cropContainer.outlineProvider)

        // Verify cropContainer LayoutParams
        val cLp = cropContainer.layoutParams as FrameLayout.LayoutParams
        assertEquals(1000, cLp.width)
        assertEquals(1066, cLp.height)
        assertEquals(466, cLp.topMargin)
        assertEquals(0, cLp.leftMargin)

        // Verify videoView LayoutParams inside cropContainer
        val vLp = videoView.layoutParams as FrameLayout.LayoutParams
        assertEquals(1000, vLp.width)
        assertEquals(1777, vLp.height)
        val expectedTopMargin = (-0.20f * (1000f / (1080f / 1152f) / 0.60f)).toInt() // -355
        assertEquals(expectedTopMargin, vLp.topMargin)

        // Verify overlayView LayoutParams inside cropContainer
        val oLp = overlayView.layoutParams as FrameLayout.LayoutParams
        assertEquals(1000, oLp.width)
        assertEquals(1066, oLp.height)
        assertEquals(0, oLp.topMargin)
        assertEquals(0, oLp.leftMargin)

        // Verify overlay's bounds mapping when bound to cropContainer
        bindMovementVideoViewport(cropContainer, videoView, overlayView, hasVideo = true, { verticalBounds }) { DummyDecoder() }
        overlayView.layout(0, 0, 1000, 1066)
        val boundsInOverlay = overlayView.videoBoundsProvider?.invoke()
        assertNotNull(boundsInOverlay)
        assertEquals(0f, boundsInOverlay.left, 0.5f)
        assertEquals(1000f, boundsInOverlay.right, 1.0f)

        val fullH = 1066f / 0.60f
        assertEquals(-0.20f * fullH, boundsInOverlay.top, 0.5f)

        // Normalized source Y = 0.20 maps to overlay Y = 0 (top of visible container)
        val yAtTop = boundsInOverlay.top + 0.20f * boundsInOverlay.height()
        assertEquals(0f, yAtTop, 0.5f)

        // Normalized source Y = 0.80 maps to overlay Y = 1066 (bottom of visible container)
        val yAtBottom = boundsInOverlay.top + 0.80f * boundsInOverlay.height()
        assertEquals(1066f, yAtBottom, 0.5f)

        // Source rows outside interval fall outside container bounds
        val yAboveCrop = boundsInOverlay.top + 0.10f * boundsInOverlay.height()
        assertTrue(yAboveCrop < 0f, "Row Y=0.10 must be negative (clipped)")

        val yBelowCrop = boundsInOverlay.top + 0.90f * boundsInOverlay.height()
        assertTrue(yBelowCrop > 1066f, "Row Y=0.90 must be beyond overlay height (clipped)")
    }
}

