package dk.lasse.karateanalyzer.motion

import kotlin.math.abs

/** Offline, amplitude-based hysteresis for bounded interior joint angles (not circular headings).
 * Small same-direction steps accumulate; sub-threshold reversals do not create angular travel.
 * Call separately for each contiguous observed block: missing evidence must never be bridged.
 */
object AngularMotionEvidence {
    const val VERSION = "angular-excursion-hysteresis-v1-provisional"

    data class Sample(val timestampUs: Long, val angleDeg: Double)
    data class Run(val startIndex: Int, val endIndex: Int, val direction: Int)
    data class Result(val meaningfulDeltasDeg: List<Double>, val runs: List<Run>)

    fun characterize(samples: List<Sample>, deadbandDeg: Double): Result {
        require(deadbandDeg.isFinite() && deadbandDeg > 0.0)
        require(samples.all { it.angleDeg.isFinite() && it.angleDeg in 0.0..180.0 })
        require(samples.zipWithNext().all { (a, b) -> b.timestampUs > a.timestampUs })
        val deltas = MutableList(samples.size) { 0.0 }
        if (samples.size < 2) return Result(deltas, emptyList())
        val runs = mutableListOf<Run>()
        var anchor = 0
        var extreme = 0
        var direction = 0
        fun finish() {
            if (direction == 0) return
            var envelope = samples[anchor].angleDeg
            var firstMovingEdge: Int? = null
            for (i in anchor + 1..extreme) {
                val change = samples[i].angleDeg - envelope
                if (change * direction > 1e-5) {
                    deltas[i] = change
                    envelope = samples[i].angleDeg
                    if (firstMovingEdge == null) firstMovingEdge = i
                }
            }
            firstMovingEdge?.let { runs += Run(it - 1, extreme, direction) }
        }
        for (i in 1 until samples.size) {
            val angle = samples[i].angleDeg
            if (direction == 0) {
                val change = angle - samples[anchor].angleDeg
                if (abs(change) >= deadbandDeg) {
                    direction = if (change > 0) 1 else -1
                    extreme = i
                }
            } else if ((angle - samples[extreme].angleDeg) * direction > 1e-5) {
                extreme = i
            } else if ((samples[extreme].angleDeg - angle) * direction >= deadbandDeg) {
                finish()
                anchor = extreme
                extreme = i
                direction = -direction
            }
        }
        finish()
        return Result(deltas.toList(), runs.toList())
    }
}
