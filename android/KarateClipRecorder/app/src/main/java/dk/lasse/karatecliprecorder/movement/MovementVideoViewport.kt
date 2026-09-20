package dk.lasse.karatecliprecorder.movement

import android.graphics.RectF
import android.media.MediaPlayer
import android.view.Gravity
import android.widget.FrameLayout
import android.widget.VideoView

data class MovementViewportGeometry(
    val viewportRect: RectF,
    val videoRect: RectF,
    val visibleTopY: Float,
    val visibleBottomY: Float,
)

/** Fit normalized evidence to the displayed image, including the first prepared frame. */
internal fun bindMovementVideoViewport(
    cropContainer: FrameLayout?,
    video: VideoView,
    overlay: MovementAnalysisOverlayView,
    hasVideo: Boolean,
    verticalBoundsProvider: () -> VerticalBounds = { VerticalBounds.FULL },
    player: () -> MediaPlayer?,
) {
    overlay.videoBoundsProvider = {
        if (!hasVideo) {
            RectF(0f, 0f, overlay.width.toFloat(), overlay.height.toFloat())
        } else {
            runCatching {
                val decoder = player() ?: return@runCatching null
                val vw = decoder.videoWidth
                val vh = decoder.videoHeight
                if (vw <= 0 || vh <= 0) return@runCatching null
                movementVideoBounds(
                    RectF(0f, 0f, overlay.width.toFloat(), overlay.height.toFloat()),
                    vw,
                    vh,
                    verticalBoundsProvider(),
                )
            }.getOrNull()
        }
    }
    // VideoView changes size after preparation. Its sibling overlay's own size
    // need not change, so it otherwise keeps its old drawing until a user action.
    video.addOnLayoutChangeListener { _, _, _, _, _, _, _, _, _ -> overlay.invalidate() }
}

internal fun bindMovementVideoViewport(
    video: VideoView,
    overlay: MovementAnalysisOverlayView,
    hasVideo: Boolean,
    verticalBoundsProvider: () -> VerticalBounds = { VerticalBounds.FULL },
    player: () -> MediaPlayer?,
) = bindMovementVideoViewport(null, video, overlay, hasVideo, verticalBoundsProvider, player)

internal fun movementVideoViewportGeometry(
    viewBounds: RectF,
    videoWidth: Int,
    videoHeight: Int,
    verticalBounds: VerticalBounds = VerticalBounds.FULL,
): MovementViewportGeometry? {
    if (videoWidth <= 0 || videoHeight <= 0 || viewBounds.width() <= 0 || viewBounds.height() <= 0) return null
    val deltaY = verticalBounds.height
    if (deltaY <= 0.001f) return null

    val framedAspect = (videoWidth.toFloat()) / (videoHeight.toFloat() * deltaY)
    val viewAspect = viewBounds.width() / viewBounds.height()

    val displayWidth: Float
    val displayHeight: Float
    val displayLeft: Float
    val displayTop: Float

    if (viewAspect > framedAspect) {
        displayHeight = viewBounds.height()
        displayWidth = displayHeight * framedAspect
        displayLeft = viewBounds.centerX() - displayWidth * 0.5f
        displayTop = viewBounds.top
    } else {
        displayWidth = viewBounds.width()
        displayHeight = displayWidth / framedAspect
        displayLeft = viewBounds.left
        displayTop = viewBounds.centerY() - displayHeight * 0.5f
    }

    val viewportRect = RectF(displayLeft, displayTop, displayLeft + displayWidth, displayTop + displayHeight)

    val fullVideoHeight = displayHeight / deltaY
    val fullVideoTop = displayTop - verticalBounds.topY * fullVideoHeight
    val fullVideoBottom = fullVideoTop + fullVideoHeight
    val fullVideoLeft = displayLeft
    val fullVideoRight = displayLeft + displayWidth

    val videoRect = RectF(fullVideoLeft, fullVideoTop, fullVideoRight, fullVideoBottom)

    return MovementViewportGeometry(
        viewportRect = viewportRect,
        videoRect = videoRect,
        visibleTopY = verticalBounds.topY,
        visibleBottomY = verticalBounds.bottomY,
    )
}

internal fun movementVideoBounds(
    viewBounds: RectF,
    videoWidth: Int,
    videoHeight: Int,
    verticalBounds: VerticalBounds = VerticalBounds.FULL,
): RectF? = movementVideoViewportGeometry(viewBounds, videoWidth, videoHeight, verticalBounds)?.videoRect

internal fun applyMovementVideoViewport(
    cropContainer: FrameLayout,
    video: VideoView,
    overlay: MovementAnalysisOverlayView,
    viewBounds: RectF,
    videoWidth: Int,
    videoHeight: Int,
    verticalBounds: VerticalBounds,
) {
    val geometry = movementVideoViewportGeometry(viewBounds, videoWidth, videoHeight, verticalBounds) ?: return

    // 1. Position and size cropContainer to viewportRect within viewBounds
    val cLp = (cropContainer.layoutParams as? FrameLayout.LayoutParams) ?: FrameLayout.LayoutParams(0, 0)
    cLp.width = geometry.viewportRect.width().toInt()
    cLp.height = geometry.viewportRect.height().toInt()
    cLp.leftMargin = geometry.viewportRect.left.toInt()
    cLp.topMargin = geometry.viewportRect.top.toInt()
    cLp.gravity = Gravity.TOP or Gravity.START
    cropContainer.layoutParams = cLp

    cropContainer.clipChildren = true
    cropContainer.clipToOutline = true
    cropContainer.outlineProvider = android.view.ViewOutlineProvider.BOUNDS

    // 2. Position and size video inside cropContainer
    val fullH = geometry.videoRect.height()
    val vLp = (video.layoutParams as? FrameLayout.LayoutParams) ?: FrameLayout.LayoutParams(0, 0)
    vLp.width = geometry.viewportRect.width().toInt()
    vLp.height = fullH.toInt()
    vLp.leftMargin = 0
    vLp.topMargin = (-verticalBounds.topY * fullH).toInt()
    vLp.gravity = Gravity.TOP or Gravity.START
    video.layoutParams = vLp

    // 3. Position and size overlay to exactly match cropContainer
    val oLp = (overlay.layoutParams as? FrameLayout.LayoutParams) ?: FrameLayout.LayoutParams(0, 0)
    oLp.width = geometry.viewportRect.width().toInt()
    oLp.height = geometry.viewportRect.height().toInt()
    oLp.leftMargin = 0
    oLp.topMargin = 0
    oLp.gravity = Gravity.TOP or Gravity.START
    overlay.layoutParams = oLp

    overlay.invalidate()
}

internal fun applyMovementVideoViewport(
    video: VideoView,
    viewBounds: RectF,
    videoWidth: Int,
    videoHeight: Int,
    verticalBounds: VerticalBounds,
) {
    val bounds = movementVideoBounds(viewBounds, videoWidth, videoHeight, verticalBounds) ?: return
    val lp = video.layoutParams as? FrameLayout.LayoutParams ?: return
    lp.width = bounds.width().toInt()
    lp.height = bounds.height().toInt()
    lp.leftMargin = bounds.left.toInt()
    lp.topMargin = bounds.top.toInt()
    lp.gravity = Gravity.TOP or Gravity.START
    video.layoutParams = lp
}
