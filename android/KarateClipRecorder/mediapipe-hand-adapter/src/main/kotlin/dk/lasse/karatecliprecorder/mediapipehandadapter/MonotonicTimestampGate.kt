package dk.lasse.karatecliprecorder.mediapipehandadapter

/** Small testable helper for accepting only strictly increasing frame timestamps. */
class MonotonicTimestampGate {
    private var latestTimestampMs: Long? = null

    fun tryAccept(timestampMs: Long): Boolean {
        val latest = latestTimestampMs
        if (latest != null && timestampMs <= latest) return false
        latestTimestampMs = timestampMs
        return true
    }

    fun reset() {
        latestTimestampMs = null
    }
}
