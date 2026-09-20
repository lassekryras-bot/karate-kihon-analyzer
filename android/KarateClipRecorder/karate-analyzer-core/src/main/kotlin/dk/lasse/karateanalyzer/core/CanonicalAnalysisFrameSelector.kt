package dk.lasse.karateanalyzer.core

import kotlin.math.sqrt

/**
 * Pure selector for the canonical terminal/theoretical-impact analysis frame of a movement.
 * Consumes the logical movement window and candidate landmark frames to select the canonical
 * frame with explicit provenance.
 */
object CanonicalAnalysisFrameSelector {
    data class CanonicalFrameResult(
        val timestampMs: Long,
        val timestampUs: Long = timestampMs * 1000L,
        val frameIndex: Long? = null,
        val strategy: String = STRATEGY_PEAK_EXTENSION,
    ) {
        companion object {
            const val STRATEGY_PERSISTED_CANONICAL = "persisted_movement_analysis_frame"
            const val STRATEGY_PEAK_EXTENSION = "peak_extension_within_movement_window"
            const val STRATEGY_PREFERRED_SNAPSHOT = "preferred_snapshot_fallback"
            const val STRATEGY_WINDOW_MIDPOINT = "movement_window_midpoint_fallback"
        }
    }

    /**
     * Resolves the canonical frame for downstream analysis.
     * Consumes the persisted [analysisFrameUs] if available to ensure single authoritative ownership,
     * falling back to [select] only when [analysisFrameUs] is null (e.g. unsegmented/legacy movements).
     */
    fun resolve(
        startUs: Long,
        endUs: Long,
        analysisFrameUs: Long?,
        frames: List<PoseFrame>,
    ): Pair<CanonicalFrameResult, PoseFrame>? {
        if (frames.isEmpty()) return null
        if (analysisFrameUs != null) {
            val exactIndex = frames.indexOfFirst { it.timestampMs * 1000L == analysisFrameUs }
            if (exactIndex >= 0) {
                val frame = frames[exactIndex]
                return CanonicalFrameResult(
                    timestampMs = frame.timestampMs,
                    timestampUs = frame.timestampMs * 1000L,
                    frameIndex = exactIndex.toLong(),
                    strategy = CanonicalFrameResult.STRATEGY_PERSISTED_CANONICAL,
                ) to frame
            }
            // Closest frame in movement window, or closest overall
            val window = frames.mapIndexed { idx, f -> idx to f }
                .filter { (_, f) -> f.timestampMs * 1000L in startUs..endUs }
                .ifEmpty { frames.mapIndexed { idx, f -> idx to f } }
            val closest = window.minByOrNull { (_, f) -> kotlin.math.abs(f.timestampMs * 1000L - analysisFrameUs) }
            if (closest != null) {
                return CanonicalFrameResult(
                    timestampMs = closest.second.timestampMs,
                    timestampUs = closest.second.timestampMs * 1000L,
                    frameIndex = closest.first.toLong(),
                    strategy = CanonicalFrameResult.STRATEGY_PERSISTED_CANONICAL,
                ) to closest.second
            }
        }
        val selected = select(startUs, endUs, frames) ?: return null
        val frameIndex = selected.frameIndex?.toInt() ?: frames.indexOfFirst { it.timestampMs == selected.timestampMs }
        val frame = if (frameIndex in frames.indices) frames[frameIndex] else frames.firstOrNull { it.timestampMs == selected.timestampMs }
        return if (frame != null) selected to frame else null
    }

    fun select(
        startUs: Long,
        endUs: Long,
        frames: List<PoseFrame>,
        preferredSnapshotTimestampMs: Long? = null,
    ): CanonicalFrameResult? {
        if (frames.isEmpty()) return null

        val windowFrames = frames.mapIndexed { idx, frame -> idx to frame }
            .filter { (_, frame) -> frame.timestampMs * 1000L in startUs..endUs }

        if (windowFrames.isEmpty()) {
            // If no frame is strictly within [startUs, endUs], find closest frame
            val targetMs = (startUs + endUs) / 2000L
            val closest = frames.mapIndexed { idx, frame -> idx to frame }
                .minByOrNull { (_, frame) -> kotlin.math.abs(frame.timestampMs - targetMs) }
                ?: return null
            return CanonicalFrameResult(
                timestampMs = closest.second.timestampMs,
                frameIndex = closest.first.toLong(),
                strategy = CanonicalFrameResult.STRATEGY_WINDOW_MIDPOINT,
            )
        }

        // Evaluate reach extension across observed arm landmarks
        var bestCandidate: Pair<Int, PoseFrame>? = null
        var maxReach = -1f

        for (candidate in windowFrames) {
            val frame = candidate.second
            val leftReach = reach(frame, PoseLandmarkId.LEFT_SHOULDER, PoseLandmarkId.LEFT_WRIST)
            val rightReach = reach(frame, PoseLandmarkId.RIGHT_SHOULDER, PoseLandmarkId.RIGHT_WRIST)
            val currentReach = maxOf(leftReach, rightReach)
            if (currentReach > maxReach) {
                maxReach = currentReach
                bestCandidate = candidate
            }
        }

        if (bestCandidate != null && maxReach > 0f) {
            return CanonicalFrameResult(
                timestampMs = bestCandidate.second.timestampMs,
                frameIndex = bestCandidate.first.toLong(),
                strategy = CanonicalFrameResult.STRATEGY_PEAK_EXTENSION,
            )
        }

        // Fallback to preferredSnapshotTimestampMs if available
        if (preferredSnapshotTimestampMs != null) {
            val snapshotMatch = windowFrames.firstOrNull { it.second.timestampMs == preferredSnapshotTimestampMs }
            if (snapshotMatch != null) {
                return CanonicalFrameResult(
                    timestampMs = snapshotMatch.second.timestampMs,
                    frameIndex = snapshotMatch.first.toLong(),
                    strategy = CanonicalFrameResult.STRATEGY_PREFERRED_SNAPSHOT,
                )
            }
        }

        val midpointFrame = windowFrames[windowFrames.size / 2]
        return CanonicalFrameResult(
            timestampMs = midpointFrame.second.timestampMs,
            frameIndex = midpointFrame.first.toLong(),
            strategy = CanonicalFrameResult.STRATEGY_WINDOW_MIDPOINT,
        )
    }

    private fun reach(frame: PoseFrame, shoulderId: PoseLandmarkId, wristId: PoseLandmarkId): Float {
        val s = frame.landmarks[shoulderId]?.takeIf { it.isObserved() }?.position ?: return -1f
        val w = frame.landmarks[wristId]?.takeIf { it.isObserved() }?.position ?: return -1f
        val dx = w.x - s.x
        val dy = w.y - s.y
        return sqrt(dx * dx + dy * dy)
    }
}
