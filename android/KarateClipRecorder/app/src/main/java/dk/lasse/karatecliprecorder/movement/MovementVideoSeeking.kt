package dk.lasse.karatecliprecorder.movement

import android.media.MediaPlayer

/** Exact-frame seeks, with at most one pending decoder request and the latest user target. */
internal class MovementVideoSeeking {
    private var player: MediaPlayer? = null
    private var pendingMs: Long? = null
    private var queuedMs: Long? = null
    val isPending: Boolean get() = pendingMs != null

    fun onPrepared(preparedPlayer: MediaPlayer) {
        clear()
        player = preparedPlayer
        preparedPlayer.setOnSeekCompleteListener {
            if (player === preparedPlayer) {
                val completedMs = pendingMs
                val nextMs = queuedMs
                pendingMs = null
                queuedMs = null
                if (nextMs != null && nextMs != completedMs) issueSeek(nextMs)
            }
        }
    }

    fun seekToUs(timestampUs: Long) {
        // The shared timeline retains requests made before preparation/recreation.
        if (player == null) return
        val targetMs = timestampUs / 1000L
        if (isPending) queuedMs = targetMs else issueSeek(targetMs)
    }

    private fun issueSeek(targetMs: Long) {
        val decoder = player ?: return
        pendingMs = targetMs
        runCatching {
            // VideoView.seekTo(int) uses a sync-frame seek, which can land before
            // a short movement's start and cannot provide known-sample stepping.
            decoder.seekTo(targetMs, MediaPlayer.SEEK_CLOSEST)
        }.onFailure { clear() }
    }

    fun clear() {
        player = null
        pendingMs = null
        queuedMs = null
    }
}
