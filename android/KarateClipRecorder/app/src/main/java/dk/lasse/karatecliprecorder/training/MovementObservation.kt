package dk.lasse.karatecliprecorder.training

import dk.lasse.karateanalyzer.core.PoseFrame
import dk.lasse.karateanalyzer.core.PoseLandmarkId
import kotlin.math.abs
import kotlin.math.atan2

/** Observed geometry only. This estimate never grants measurement validity. */
object MovementObservation {
    fun estimate(movement: SessionMovement, frames: List<PoseFrame>): ObservationContext {
        data class Sample(val angle: Double, val side: BodySide, val confidence: Double, val front: Boolean)
        val samples = frames.filter { it.timestampMs * 1000 in movement.startUs..movement.endUs }.mapNotNull { frame ->
            val left = frame.landmarks[PoseLandmarkId.LEFT_SHOULDER]?.takeIf { it.isObserved(0.6f) } ?: return@mapNotNull null
            val right = frame.landmarks[PoseLandmarkId.RIGHT_SHOULDER]?.takeIf { it.isObserved(0.6f) } ?: return@mapNotNull null
            val l = left.worldPosition ?: return@mapNotNull null
            val r = right.worldPosition ?: return@mapNotNull null
            val dx = (r.x - l.x).toDouble()
            val dz = (r.z - l.z).toDouble()
            if (!dx.isFinite() || !dz.isFinite() || dx * dx + dz * dz < 0.0025) return@mapNotNull null
            val angle = Math.toDegrees(atan2(abs(dz), abs(dx)))
            val nose = frame.landmarks[PoseLandmarkId.NOSE]?.takeIf { it.isObserved(0.6f) }?.worldPosition
            Sample(angle, if (abs(dz) < 0.03) BodySide.UNKNOWN else if (l.z < r.z) BodySide.LEFT else BodySide.RIGHT,
                minOf(left.confidence, right.confidence).toDouble(), nose != null && nose.z < (l.z + r.z) / 2 - 0.03f)
        }
        if (samples.size < 3) return ObservationContext(movement.movementId)
        val sorted = samples.sortedBy { it.angle }
        val angle = sorted[sorted.size / 2].angle
        val changing = sorted.last().angle - sorted.first().angle > 25
        val nearer = samples.groupingBy { it.side }.eachCount().maxBy { it.value }
            .takeIf { it.value.toDouble() / samples.size >= 0.8 }?.key ?: BodySide.UNKNOWN
        val view = when {
            changing -> ObservedView.OTHER
            angle <= 25 && samples.count { it.front }.toDouble() / samples.size >= 0.8 -> ObservedView.FRONT
            angle >= 65 && nearer == BodySide.LEFT -> ObservedView.LEFT_SIDE
            angle >= 65 && nearer == BodySide.RIGHT -> ObservedView.RIGHT_SIDE
            else -> ObservedView.UNKNOWN
        }
        return ObservationContext(movement.movementId, view, nearer, angle, samples.minOf { it.confidence },
            "world_shoulders_v1; median acute angle; landmark confidence, not calibrated orientation confidence; changing=$changing")
    }
}
