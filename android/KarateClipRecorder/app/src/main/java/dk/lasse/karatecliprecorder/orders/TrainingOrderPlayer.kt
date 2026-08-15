package dk.lasse.karatecliprecorder.orders

interface TrainingOrderPlayer {
    fun play(order: TrainingOrder, onComplete: (() -> Unit)? = null)
    fun stop()
    fun release()
}
