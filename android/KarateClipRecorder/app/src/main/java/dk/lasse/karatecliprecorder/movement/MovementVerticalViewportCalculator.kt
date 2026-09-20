package dk.lasse.karatecliprecorder.movement

import dk.lasse.karateanalyzer.core.PoseFrame
import dk.lasse.karateanalyzer.core.PoseLandmarkId
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

data class VerticalBounds(
    val topY: Float,
    val bottomY: Float,
) {
    init {
        require(topY in 0f..1f) { "topY must be in [0, 1], was $topY" }
        require(bottomY in 0f..1f) { "bottomY must be in [0, 1], was $bottomY" }
        require(topY < bottomY) { "topY must be < bottomY, was topY=$topY, bottomY=$bottomY" }
    }

    val height: Float get() = bottomY - topY

    companion object {
        val FULL = VerticalBounds(0f, 1f)
    }
}

object MovementVerticalViewportCalculator {

    private val HEAD_LANDMARKS = listOf(
        PoseLandmarkId.NOSE,
        PoseLandmarkId.LEFT_EYE,
        PoseLandmarkId.RIGHT_EYE,
        PoseLandmarkId.LEFT_EAR,
        PoseLandmarkId.RIGHT_EAR,
    )

    private val FOOT_LANDMARKS = listOf(
        PoseLandmarkId.LEFT_ANKLE,
        PoseLandmarkId.RIGHT_ANKLE,
        PoseLandmarkId.LEFT_HEEL,
        PoseLandmarkId.RIGHT_HEEL,
        PoseLandmarkId.LEFT_FOOT_INDEX,
        PoseLandmarkId.RIGHT_FOOT_INDEX,
    )

    const val MIN_WINDOW_HEIGHT = 0.45f
    const val HEADROOM_RATIO = 0.15f
    const val FOOT_MARGIN_RATIO = 0.08f

    /**
     * Derives stable multi-frame vertical bounds (topY, bottomY) across movement frames.
     * Never crops horizontally. Falls back cleanly to full uncropped frame [0, 1]
     * if landmarks are unavailable, sparse, or degenerate.
     */
    fun computeVerticalBounds(
        frames: List<PoseFrame>,
        startUs: Long,
        endUs: Long,
    ): VerticalBounds {
        if (frames.isEmpty() || endUs <= startUs) return VerticalBounds.FULL

        val startMs = startUs / 1000L
        val endMs = endUs / 1000L

        val headYs = mutableListOf<Float>()
        val footYs = mutableListOf<Float>()

        frames.asSequence()
            .filter { it.timestampMs in startMs..endMs }
            .forEach { f ->
                val observedHead = HEAD_LANDMARKS.mapNotNull { id ->
                    f.landmarks[id]?.takeIf { it.isObserved() }?.position?.y
                }
                val observedFoot = FOOT_LANDMARKS.mapNotNull { id ->
                    f.landmarks[id]?.takeIf { it.isObserved() }?.position?.y
                }

                if (observedHead.isNotEmpty() && observedFoot.isNotEmpty()) {
                    val minHead = observedHead.minOrNull()!!
                    val maxFoot = observedFoot.maxOrNull()!!
                    if (maxFoot > minHead + 0.10f) {
                        headYs.add(minHead)
                        footYs.add(maxFoot)
                    }
                }
            }

        if (headYs.size < 3 || footYs.size < 3) return VerticalBounds.FULL

        headYs.sort()
        footYs.sort()

        // Use 10th percentile for head (near highest extent) and 90th percentile for feet (near lowest extent)
        fun percentile(list: List<Float>, p: Float): Float {
            val idx = ((list.size - 1) * p).roundToInt().coerceIn(0, list.size - 1)
            return list[idx]
        }

        val topHeadY = percentile(headYs, 0.10f)
        val bottomFootY = percentile(footYs, 0.90f)
        val athleteHeight = bottomFootY - topHeadY
        if (athleteHeight < 0.15f) return VerticalBounds.FULL

        val headroom = athleteHeight * HEADROOM_RATIO
        val footMargin = athleteHeight * FOOT_MARGIN_RATIO

        var rawTop = (topHeadY - headroom).coerceIn(0f, 1f)
        var rawBottom = (bottomFootY + footMargin).coerceIn(0f, 1f)

        if (rawBottom <= rawTop + 0.05f) return VerticalBounds.FULL

        val currentHeight = rawBottom - rawTop
        if (currentHeight < MIN_WINDOW_HEIGHT) {
            val mid = (rawTop + rawBottom) * 0.5f
            val half = MIN_WINDOW_HEIGHT * 0.5f
            rawTop = max(0f, mid - half)
            rawBottom = min(1f, mid + half)
            if (rawBottom - rawTop < MIN_WINDOW_HEIGHT) {
                if (rawTop == 0f) {
                    rawBottom = min(1f, MIN_WINDOW_HEIGHT)
                } else if (rawBottom == 1f) {
                    rawTop = max(0f, 1f - MIN_WINDOW_HEIGHT)
                }
            }
        }

        if (rawBottom <= rawTop) return VerticalBounds.FULL

        return VerticalBounds(rawTop, rawBottom)
    }
}

