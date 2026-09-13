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

