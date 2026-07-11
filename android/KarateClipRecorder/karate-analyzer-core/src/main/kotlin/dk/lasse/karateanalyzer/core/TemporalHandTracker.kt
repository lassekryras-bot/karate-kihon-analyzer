package dk.lasse.karateanalyzer.core

import kotlin.math.max
import kotlin.math.min

data class TrackingConfiguration(
    val maximumPredictionGapMs: Long = 150,
    val maximumInterpolationGapMs: Long = 250,
    val smoothingFactor: Float = 0.35f,
    val minimumObservedConfidence: Float = 0.5f,
)

data class LandmarkTrack(
    val landmarkId: HandLandmarkId,
    val lastObservedSample: LandmarkSample? = null,
    val lastObservedTimestampMs: Long? = null,
    val lastStabilizedSample: LandmarkSample? = null,
    val lastStabilizedTimestampMs: Long? = null,
    val velocityPerMs: Point3? = null,
    val pendingGapFrames: List<PendingGapFrame> = emptyList(),
)

data class PendingGapFrame(
    val timestampMs: Long,
)

data class TrackedHandFrame(
    val timestampMs: Long,
    val handedness: Handedness,
    val landmarks: Map<HandLandmarkId, LandmarkSample>,
    val interpolatedFrames: List<HandFrame> = emptyList(),
)

class TemporalHandTracker(
    private val configuration: TrackingConfiguration = TrackingConfiguration(),
) {
    private val tracks = mutableMapOf<HandLandmarkId, LandmarkTrack>()
    private var lastHandedness: Handedness = Handedness.UNKNOWN
    private var lastHandednessTimestampMs: Long? = null

    fun reset() {
        tracks.clear()
        lastHandedness = Handedness.UNKNOWN
        lastHandednessTimestampMs = null
    }

    fun track(frame: HandFrame): TrackedHandFrame {
        val stabilized = mutableMapOf<HandLandmarkId, LandmarkSample>()
        val backfill = mutableMapOf<Long, MutableMap<HandLandmarkId, LandmarkSample>>()

        for (id in HandLandmarkId.entries) {
            val observed = frame.landmarks[id]
            val previous = tracks[id] ?: LandmarkTrack(id)
            val result = if (observed.isUsableObservation()) {
                observe(id, frame.timestampMs, observed!!, previous, backfill)
            } else {
                predictOrMiss(id, frame.timestampMs, previous)
            }
            tracks[id] = result.first
            stabilized[id] = result.second
        }

        val handedness = stabilizeHandedness(frame)
        val interpolatedFrames = backfill.entries
            .sortedBy { it.key }
            .map { (timestamp, landmarks) -> HandFrame(timestamp, handedness, landmarks) }

        return TrackedHandFrame(frame.timestampMs, handedness, stabilized, interpolatedFrames)
    }

    private fun observe(
        id: HandLandmarkId,
        timestampMs: Long,
        observed: LandmarkSample,
        previous: LandmarkTrack,
        backfill: MutableMap<Long, MutableMap<HandLandmarkId, LandmarkSample>>,
    ): Pair<LandmarkTrack, LandmarkSample> {
        val raw = observed.position ?: return LandmarkTrack(id) to missing()
        val previousStabilized = previous.lastStabilizedSample?.position
        val elapsedSinceStabilized = previous.lastStabilizedTimestampMs?.let { timestampMs - it }
        val shouldSmooth = previousStabilized != null && elapsedSinceStabilized != null && elapsedSinceStabilized <= configuration.maximumInterpolationGapMs
        val smoothed = if (shouldSmooth) lerp(previousStabilized!!, raw, configuration.smoothingFactor.coerceIn(0f, 1f)) else raw
        val sample = observed.copy(position = smoothed, source = LandmarkSource.OBSERVED)

        val elapsedSinceObserved = previous.lastObservedTimestampMs?.let { timestampMs - it }
        if (elapsedSinceObserved != null && elapsedSinceObserved in 1..configuration.maximumInterpolationGapMs) {
            val start = previous.lastObservedSample?.position
            if (start != null && elapsedSinceObserved > 1) {
                previous.pendingGapFrames
                    .filter { it.timestampMs > previous.lastObservedTimestampMs && it.timestampMs < timestampMs }
                    .forEach { pending ->
                        val ratio = (pending.timestampMs - previous.lastObservedTimestampMs).toFloat() / elapsedSinceObserved.toFloat()
                        backfill.getOrPut(pending.timestampMs) { mutableMapOf() }[id] = LandmarkSample(
                            position = lerp(start, smoothed, ratio),
                            confidence = min(previous.lastObservedSample.confidence, observed.confidence),
                            source = LandmarkSource.INTERPOLATED,
                        )
                    }
            }
        }

        val velocity = previous.lastObservedSample?.position
            ?.takeIf { previous.lastObservedTimestampMs != null && timestampMs > previous.lastObservedTimestampMs }
            ?.let { (smoothed - it) * (1f / (timestampMs - previous.lastObservedTimestampMs!!).toFloat()) }

        return LandmarkTrack(id, sample, timestampMs, sample, timestampMs, velocity ?: previous.velocityPerMs) to sample
    }

    private fun predictOrMiss(id: HandLandmarkId, timestampMs: Long, previous: LandmarkTrack): Pair<LandmarkTrack, LandmarkSample> {
        val lastObservedTimestamp = previous.lastObservedTimestampMs ?: return previous to missing()
        val gap = timestampMs - lastObservedTimestamp
        val lastObservedPosition = previous.lastObservedSample?.position
        if (gap !in 1..configuration.maximumInterpolationGapMs || lastObservedPosition == null) {
            val sample = missing()
            return previous.copy(
                lastStabilizedSample = sample,
                lastStabilizedTimestampMs = timestampMs,
                pendingGapFrames = emptyList(),
            ) to sample
        }
        if (gap > configuration.maximumPredictionGapMs) {
            val sample = missing()
            return previous.copy(
                lastStabilizedSample = sample,
                lastStabilizedTimestampMs = timestampMs,
                pendingGapFrames = previous.pendingGapFrames + PendingGapFrame(timestampMs),
            ) to sample
        }
        val velocity = previous.velocityPerMs ?: Point3(0f, 0f, 0f)
        val predictedPosition = lastObservedPosition + velocity * gap.toFloat()
        val confidenceRatio = 1f - (gap.toFloat() / configuration.maximumPredictionGapMs.toFloat()).coerceIn(0f, 1f)
        val sample = LandmarkSample(predictedPosition, previous.lastObservedSample.confidence * confidenceRatio, LandmarkSource.PREDICTED)
        return previous.copy(
            lastStabilizedSample = sample,
            lastStabilizedTimestampMs = timestampMs,
            pendingGapFrames = previous.pendingGapFrames + PendingGapFrame(timestampMs),
        ) to sample
    }

    private fun LandmarkSample?.isUsableObservation(): Boolean =
        this != null && source == LandmarkSource.OBSERVED && position != null && confidence >= configuration.minimumObservedConfidence

    private fun stabilizeHandedness(frame: HandFrame): Handedness {
        if (frame.handedness != Handedness.UNKNOWN) {
            lastHandedness = frame.handedness
            lastHandednessTimestampMs = frame.timestampMs
            return frame.handedness
        }
        val lastTimestamp = lastHandednessTimestampMs
        return if (lastTimestamp != null && frame.timestampMs - lastTimestamp <= configuration.maximumPredictionGapMs) lastHandedness else Handedness.UNKNOWN
    }

    private fun missing() = LandmarkSample(null, 0f, LandmarkSource.MISSING)

    private fun lerp(start: Point3, end: Point3, amount: Float): Point3 {
        val t = max(0f, min(1f, amount))
        return start + (end - start) * t
    }
}
