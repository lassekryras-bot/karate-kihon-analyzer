package dk.lasse.karatecliprecorder.orders

interface TrainingOrderPlayer {
    fun play(order: TrainingOrder)
    fun stop()
    fun release()
}
