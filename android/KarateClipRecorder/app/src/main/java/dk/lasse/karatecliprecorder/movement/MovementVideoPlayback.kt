package dk.lasse.karatecliprecorder.movement

import android.media.MediaPlayer
import android.widget.VideoView

/** Setting a nonzero MediaPlayer speed can start it. Always restore playback intent. */
internal fun applyMovementPlaybackRate(
    player: MediaPlayer,
    video: VideoView,
    rate: Double,
    shouldPlay: Boolean,
) {
    var speedApplied = false
    try {
        val params = player.playbackParams.allowDefaults()
        if (params.speed != rate.toFloat()) {
            params.speed = rate.toFloat()
            player.playbackParams = params
            speedApplied = true
        }
    } finally {
        if (!shouldPlay) {
            // Pause the actual decoder as well as VideoView's pending-start intent.
            try {
                // isPlaying can lag behind setPlaybackParams. A successful speed
                // change has already entered Started, even if it still reports false.
                // Without that change, pause is invalid on a newly Prepared decoder.
                if (speedApplied || player.isPlaying) player.pause()
            } finally {
                video.pause()
            }
        }
    }
}
