package dk.lasse.karateanalyzer.observation

enum class Evidence { TRUE, FALSE, UNKNOWN }

enum class UnknownEvidencePolicy { RESET, PAUSE_WITH_GRACE, REMAIN_UNRESOLVED }

class NonIncreasingTimestampException(previous: Long, current: Long) :
    IllegalArgumentException("Timestamp $current must be greater than $previous")

internal fun requireIncreasing(previous: Long?, current: Long) {
    if (previous != null && current <= previous) throw NonIncreasingTimestampException(previous, current)
}

data class DwellSnapshot(
    val requiredDurationMs: Long,
    val accumulatedDurationMs: Long,
    val firstSupportingTimestampMs: Long?,
    val latestSupportingTimestampMs: Long?,
    val currentEvidence: Evidence?,
    val fulfilled: Boolean,
    val unknownSinceTimestampMs: Long?,
)

class SustainedCondition(
    private val requiredDurationMs: Long,
    private val unknownPolicy: UnknownEvidencePolicy = UnknownEvidencePolicy.RESET,
    private val unknownGraceMs: Long = 0L,
) {
    init {
        require(requiredDurationMs >= 0L)
        require(unknownGraceMs >= 0L)
    }

    private var lastTimestampMs: Long? = null
    private var firstSupportingTimestampMs: Long? = null
    private var latestSupportingTimestampMs: Long? = null
    private var unknownSinceTimestampMs: Long? = null
    private var currentEvidence: Evidence? = null
    private var accumulatedDurationMs = 0L
    private var fulfilled = false

    fun update(timestampMs: Long, evidence: Evidence): DwellSnapshot {
        requireIncreasing(lastTimestampMs, timestampMs)
        val elapsedMs = lastTimestampMs?.let { timestampMs - it } ?: 0L
        val previousEvidence = currentEvidence
        lastTimestampMs = timestampMs
        currentEvidence = evidence
        when (evidence) {
            Evidence.TRUE -> {
                if (firstSupportingTimestampMs == null) firstSupportingTimestampMs = timestampMs
                if (previousEvidence == Evidence.TRUE) accumulatedDurationMs += elapsedMs
                latestSupportingTimestampMs = timestampMs
                unknownSinceTimestampMs = null
                if (accumulatedDurationMs >= requiredDurationMs) fulfilled = true
            }
            Evidence.FALSE -> clearSupport()
            Evidence.UNKNOWN -> when (unknownPolicy) {
                UnknownEvidencePolicy.RESET -> clearSupport()
                UnknownEvidencePolicy.REMAIN_UNRESOLVED -> unknownSinceTimestampMs = unknownSinceTimestampMs ?: timestampMs
                UnknownEvidencePolicy.PAUSE_WITH_GRACE -> {
                    val unknownSince = unknownSinceTimestampMs ?: timestampMs.also { unknownSinceTimestampMs = it }
                    if (timestampMs - unknownSince > unknownGraceMs) clearSupport()
                }
            }
        }
        return snapshot()
    }

    fun snapshot() = DwellSnapshot(
        requiredDurationMs = requiredDurationMs,
        accumulatedDurationMs = accumulatedDurationMs,
        firstSupportingTimestampMs = firstSupportingTimestampMs,
        latestSupportingTimestampMs = latestSupportingTimestampMs,
        currentEvidence = currentEvidence,
        fulfilled = fulfilled,
        unknownSinceTimestampMs = unknownSinceTimestampMs,
    )

    fun reset() {
        lastTimestampMs = null
        currentEvidence = null
        clearSupport()
    }

    private fun clearSupport() {
        firstSupportingTimestampMs = null
        latestSupportingTimestampMs = null
        unknownSinceTimestampMs = null
        accumulatedDurationMs = 0L
        fulfilled = false
    }
}

enum class TimeoutStatus { DISARMED, WAITING, TIMED_OUT }

data class TimeoutSnapshot(val status: TimeoutStatus, val armedAtMs: Long?, val deadlineMs: Long?)

class TimeoutTracker {
    private var armedAtMs: Long? = null
    private var deadlineMs: Long? = null
    private var lastTimestampMs: Long? = null

    fun arm(timestampMs: Long, durationMs: Long): TimeoutSnapshot {
        require(durationMs >= 0L)
        armedAtMs = timestampMs
        deadlineMs = Math.addExact(timestampMs, durationMs)
        lastTimestampMs = null
        return snapshot(timestampMs)
    }

    fun update(timestampMs: Long): TimeoutSnapshot {
        val armed = armedAtMs ?: return TimeoutSnapshot(TimeoutStatus.DISARMED, null, null)
        require(timestampMs >= armed) { "Timestamp $timestampMs precedes arm time $armed" }
        requireIncreasing(lastTimestampMs, timestampMs)
        lastTimestampMs = timestampMs
        return snapshot(timestampMs)
    }

    fun reset() {
        armedAtMs = null
        deadlineMs = null
        lastTimestampMs = null
    }

    fun armTimestamp(): Long? = armedAtMs

    private fun snapshot(nowMs: Long) = TimeoutSnapshot(
        status = if (nowMs >= checkNotNull(deadlineMs)) TimeoutStatus.TIMED_OUT else TimeoutStatus.WAITING,
        armedAtMs = armedAtMs,
        deadlineMs = deadlineMs,
    )
}

data class TimestampedValue<T>(val timestampMs: Long, val value: T)

class TimestampedHistory<T>(private val capacity: Int, private val maxAgeMs: Long) : Iterable<TimestampedValue<T>> {
    init {
        require(capacity > 0)
        require(maxAgeMs >= 0L)
    }

    private val entries = ArrayDeque<TimestampedValue<T>>()

    fun add(timestampMs: Long, value: T) {
        requireIncreasing(entries.lastOrNull()?.timestampMs, timestampMs)
        entries.addLast(TimestampedValue(timestampMs, value))
        while (entries.size > capacity || timestampMs - entries.first().timestampMs > maxAgeMs) entries.removeFirst()
    }

    fun values(): List<TimestampedValue<T>> = entries.toList()
    fun reset() = entries.clear()
    override fun iterator(): Iterator<TimestampedValue<T>> = values().iterator()
}

data class TransitionRecord<S>(
    val previousState: S,
    val newState: S,
    val decisionTimestampMs: Long,
    val estimatedBoundaryTimestampMs: Long? = null,
    val trigger: String,
    val quality: Double? = null,
    val diagnostics: Map<String, String> = emptyMap(),
)
