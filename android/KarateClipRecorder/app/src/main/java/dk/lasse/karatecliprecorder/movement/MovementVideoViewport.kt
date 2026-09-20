package dk.lasse.karatecliprecorder.movement

import android.graphics.RectF
import android.media.MediaPlayer
import android.widget.VideoView
import kotlin.math.min

/** Fit normalized evidence to the displayed image, including the first prepared frame. */
internal fun bindMovementVideoViewport(
    video: VideoView,
    overlay: MovementAnalysisOverlayView,
    hasVideo: Boolean,
    player: () -> MediaPlayer?,
) {
    overlay.videoBoundsProvider = {
        if (!hasVideo) {
            RectF(0f, 0f, overlay.width.toFloat(), overlay.height.toFloat())
        } else {
            runCatching {
                val decoder = player()
                movementVideoBounds(
                    RectF(video.left.toFloat(), video.top.toFloat(), video.right.toFloat(), video.bottom.toFloat()),
                    decoder?.videoWidth ?: 0,
                    decoder?.videoHeight ?: 0,
                )
            }.getOrNull()
        }
    }
    // VideoView changes size after preparation. Its sibling overlay's own size
    // need not change, so it otherwise keeps its old drawing until a user action.
    video.addOnLayoutChangeListener { _, _, _, _, _, _, _, _, _ -> overlay.invalidate() }
}

internal fun movementVideoBounds(viewBounds: RectF, videoWidth: Int, videoHeight: Int): RectF? {
    if (videoWidth <= 0 || videoHeight <= 0 || viewBounds.width() <= 0 || viewBounds.height() <= 0) return null
    val scale = min(viewBounds.width() / videoWidth, viewBounds.height() / videoHeight)
    val halfWidth = videoWidth * scale / 2f
    val halfHeight = videoHeight * scale / 2f
    return RectF(viewBounds.centerX() - halfWidth, viewBounds.centerY() - halfHeight,
        viewBounds.centerX() + halfWidth, viewBounds.centerY() + halfHeight)
}
