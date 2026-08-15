package dk.lasse.karatecliprecorder.learning

/** Incomplete attempts stay live until the user stops them; only ten count inputs auto-stop. */
object JapaneseCountListeningPolicy {
    fun shouldAutoStop(
        recognizedCountInputs: Int,
        countLimit: Int = JapaneseCountSequence.expected.size,
    ): Boolean = recognizedCountInputs >= countLimit
}
