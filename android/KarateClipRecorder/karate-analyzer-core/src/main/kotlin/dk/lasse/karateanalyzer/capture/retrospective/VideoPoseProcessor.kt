package dk.lasse.karateanalyzer.capture.retrospective

import dk.lasse.karateanalyzer.core.PoseFrame
import java.io.File

/**
 * Interface for offline video pose extraction.
 * Processes a finalized continuous session video file and yields an ordered timeline of PoseFrames
 * with monotonically increasing video presentation timestamps.
 */
interface VideoPoseProcessor {
    /**
     * Authoritatively processes a finalized video file using MediaPipe in VIDEO mode.
     *
     * @param videoFile Master session recording MP4.
     * @param onProgress Callback invoked periodically with progress fraction (0f..1f) and current video PTS in ms.
     * @return Ordered list of [PoseFrame] entries spanning the full video duration.
     */
    fun processVideo(
        videoFile: File,
        onProgress: (progressFraction: Float, currentTimestampMs: Long) -> Unit = { _, _ -> },
    ): List<PoseFrame>
}

/** Inclusive recording-relative source-time request for isolated prototype extraction. */
data class VideoTimeWindow(
    val startTimestampMs: Long,
    val endTimestampMs: Long,
) {
    init {
        require(startTimestampMs >= 0)
        require(endTimestampMs >= startTimestampMs)
    }
}

/**
 * Window extraction result with explicit observed coverage. Requested padding is not evidence when
 * no frame was actually produced at that source timestamp.
 */
data class VideoPoseWindowResult(
    val requestedWindow: VideoTimeWindow,
    val frames: List<PoseFrame>,
    val actualStartTimestampMs: Long? = frames.firstOrNull()?.timestampMs,
    val actualEndTimestampMs: Long? = frames.lastOrNull()?.timestampMs,
    val trackingStateResetAtWindowStart: Boolean = true,
) {
    init {
        require(frames.zipWithNext().all { (first, second) -> first.timestampMs < second.timestampMs })
        require((actualStartTimestampMs == null) == (actualEndTimestampMs == null))
        if (actualStartTimestampMs != null && actualEndTimestampMs != null) {
            require(actualStartTimestampMs >= requestedWindow.startTimestampMs)
            require(actualEndTimestampMs <= requestedWindow.endTimestampMs)
            require(actualStartTimestampMs == frames.first().timestampMs)
            require(actualEndTimestampMs == frames.last().timestampMs)
        } else {
            require(frames.isEmpty())
        }
    }
}

/** Optional capability used only by the isolated Lite/Heavy prototype. */
interface WindowedVideoPoseProcessor : VideoPoseProcessor {
    fun processWindow(
        videoFile: File,
        window: VideoTimeWindow,
        onProgress: (progressFraction: Float, currentTimestampMs: Long) -> Unit = { _, _ -> },
    ): VideoPoseWindowResult
}
