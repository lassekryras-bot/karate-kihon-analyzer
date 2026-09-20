package dk.lasse.karatecliprecorder.movement

import java.util.concurrent.CopyOnWriteArrayList

enum class PlayerMode {
    VIDEO,
    ANALYSIS,
    GRAPH,
    POSE,
}

enum class PlaybackControlState {
    PLAYING,
    PAUSED_PLAY,
    PAUSED_REPLAY,
}

data class NamedEvent(
    val name: String,
    val timestampUs: Long,
)

/**
 * Shared timeline state controller for the Movement Presentation Player.
 * Uses real timestamps (in microseconds) as canonical timeline identity.
 */
class MovementTimelineState(
    val playbackStartUs: Long,
    val playbackEndUs: Long,
    initialTimestampUs: Long = playbackStartUs,
    initialMode: PlayerMode = PlayerMode.ANALYSIS,
    initialPlotKey: String? = null,
    val knownSampleTimestampsUs: List<Long> = emptyList(),
    val namedEvents: List<NamedEvent> = emptyList(),
    val canonicalImpactUs: Long? = null,
    val replayStartUs: Long = playbackStartUs,
) {
    init {
        require(playbackStartUs >= 0) { "playbackStartUs must be non-negative" }
        require(playbackEndUs >= playbackStartUs) { "playbackEndUs must be >= playbackStartUs" }
    }

    var currentTimestampUs: Long = initialTimestampUs.coerceIn(playbackStartUs, playbackEndUs)
        private set

    var currentMode: PlayerMode = initialMode
        private set

    var selectedPlotKey: String? = initialPlotKey
        private set

    var isPlaying: Boolean = false
        private set

    var playbackRate: Double = 1.0
        private set

    val durationUs: Long get() = playbackEndUs - playbackStartUs

    val canStepSamples: Boolean get() = knownSampleTimestampsUs.isNotEmpty()

    val progress: Double
        get() = if (durationUs > 0) {
            (currentTimestampUs - playbackStartUs).toDouble() / durationUs.toDouble()
        } else 0.0

    val playbackControlState: PlaybackControlState
        get() = when {
            isPlaying -> PlaybackControlState.PLAYING
            canonicalImpactUs != null && currentTimestampUs >= canonicalImpactUs -> PlaybackControlState.PAUSED_REPLAY
            currentTimestampUs >= playbackEndUs -> PlaybackControlState.PAUSED_REPLAY
            else -> PlaybackControlState.PAUSED_PLAY
        }

    private var lastControlState: PlaybackControlState = playbackControlState

    private fun checkControlStateChange() {
        val newState = playbackControlState
        if (newState != lastControlState) {
            lastControlState = newState
            listeners.forEach { it.onPlaybackControlStateChanged(newState) }
        }
    }

    private val listeners = CopyOnWriteArrayList<TimelineListener>()

    interface TimelineListener {
        fun onTimestampChanged(timestampUs: Long, progress: Double) {}
        fun onSeekRequested(timestampUs: Long) {}
        fun onModeChanged(mode: PlayerMode) {}
        fun onSelectedPlotKeyChanged(key: String?) {}
        fun onPlaybackStateChanged(isPlaying: Boolean) {}
        fun onPlaybackControlStateChanged(controlState: PlaybackControlState) {}
        fun onPlaybackRateChanged(rate: Double) {}
    }

    fun addListener(listener: TimelineListener) {
        listeners.addIfAbsent(listener)
    }

    fun removeListener(listener: TimelineListener) {
        listeners.remove(listener)
    }

    fun seekUs(timestampUs: Long, notify: Boolean = true) {
        val clamped = timestampUs.coerceIn(playbackStartUs, playbackEndUs)
        if (clamped == currentTimestampUs && !notify) return
        currentTimestampUs = clamped
        if (notify) {
            val p = progress
            listeners.forEach { it.onTimestampChanged(clamped, p) }
            listeners.forEach { it.onSeekRequested(clamped) }
        }
        checkControlStateChange()
    }

    /** Observing playback must never send a seek back to the decoder. */
    fun updatePlaybackPositionUs(timestampUs: Long) {
        val targetLimit = canonicalImpactUs ?: playbackEndUs
        val clamped = timestampUs.coerceIn(playbackStartUs, playbackEndUs)
        if (isPlaying && clamped >= targetLimit) {
            currentTimestampUs = targetLimit
            val p = progress
            listeners.forEach { it.onTimestampChanged(targetLimit, p) }
            setPlaying(false)
            return
        }
        currentTimestampUs = clamped
        val p = progress
        listeners.forEach { it.onTimestampChanged(clamped, p) }
        checkControlStateChange()
    }

    fun togglePlayOrReplay() {
        if (isPlaying) {
            setPlaying(false)
        } else {
            if (playbackControlState == PlaybackControlState.PAUSED_REPLAY) {
                seekUs(replayStartUs)
            }
            setPlaying(true)
        }
    }

    fun seekProgress(progress: Double) {
        val p = progress.coerceIn(0.0, 1.0)
        val targetUs = playbackStartUs + (p * durationUs.toDouble()).toLong()
        seekUs(targetUs)
    }

    fun setMode(newMode: PlayerMode) {
        if (currentMode == newMode) return
        currentMode = newMode
        listeners.forEach { it.onModeChanged(newMode) }
    }

    fun setSelectedPlotKey(key: String?) {
        if (selectedPlotKey == key) return
        selectedPlotKey = key
        listeners.forEach { it.onSelectedPlotKeyChanged(key) }
    }

    fun setPlaying(playing: Boolean) {
        if (isPlaying == playing) return
        val targetLimit = canonicalImpactUs ?: playbackEndUs
        if (playing && currentTimestampUs >= targetLimit) {
            seekUs(replayStartUs)
        }
        isPlaying = playing
        listeners.forEach { it.onPlaybackStateChanged(playing) }
        checkControlStateChange()
    }

    fun setPlaybackRate(rate: Double) {
        val clamped = rate.coerceIn(0.1, 1.0)
        if (playbackRate == clamped) return
        playbackRate = clamped
        listeners.forEach { it.onPlaybackRateChanged(clamped) }
    }

    fun stepPreviousSample() {
        if (knownSampleTimestampsUs.isNotEmpty()) {
            val prev = knownSampleTimestampsUs.filter { it < currentTimestampUs }.maxOrNull()
            if (prev != null) {
                seekUs(prev)
            }
        }
    }

    fun stepNextSample() {
        if (knownSampleTimestampsUs.isNotEmpty()) {
            val next = knownSampleTimestampsUs.filter { it > currentTimestampUs }.minOrNull()
            if (next != null) {
                seekUs(next)
            }
        }
    }
}
