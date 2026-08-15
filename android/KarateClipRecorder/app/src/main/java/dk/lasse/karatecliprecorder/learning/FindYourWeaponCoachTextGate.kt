package dk.lasse.karatecliprecorder.learning

class FindYourWeaponCoachTextGate(
    private val minimumDisplayMs: Long = DEFAULT_MINIMUM_DISPLAY_MS,
) {
    private var displayedText: String = ""
    private var lastChangedAtMs: Long = Long.MIN_VALUE

    init {
        require(minimumDisplayMs >= 0) { "minimumDisplayMs must be >= 0" }
    }

    fun displayText(candidateText: String, nowMs: Long, force: Boolean = false): String {
        if (candidateText == displayedText) {
            return displayedText
        }
        if (displayedText.isEmpty() || force || nowMs - lastChangedAtMs >= minimumDisplayMs) {
            displayedText = candidateText
            lastChangedAtMs = nowMs
        }
        return displayedText
    }

    fun reset() {
        displayedText = ""
        lastChangedAtMs = Long.MIN_VALUE
    }

    companion object {
        const val DEFAULT_MINIMUM_DISPLAY_MS = 2_000L
    }
}
