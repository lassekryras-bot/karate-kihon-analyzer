package dk.lasse.karatecliprecorder.learning

import androidx.annotation.RawRes
import kotlin.random.Random

/** Chooses a fresh packaged recording for every playback request. */
class RandomAudioSampleSelector(
    resourceIds: IntArray,
    private val pickIndex: (Int) -> Int = { size -> Random.nextInt(size) },
) {
    private val samples = resourceIds.copyOf()

    init {
        require(samples.isNotEmpty()) { "At least one audio sample is required." }
    }

    @RawRes
    fun nextResourceId(): Int {
        val index = pickIndex(samples.size)
        require(index in samples.indices) { "Audio sample index $index is outside ${samples.indices}." }
        return samples[index]
    }
}
