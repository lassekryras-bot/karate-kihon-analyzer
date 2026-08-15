package dk.lasse.karatecliprecorder.enso

import dk.lasse.karatecliprecorder.R
import java.util.ArrayDeque
import kotlin.random.Random

enum class EnsoVariant(
    val number: Int,
    internal val rawResourceId: Int,
) {
    ENSO_01(1, R.raw.enso_01),
    ENSO_02(2, R.raw.enso_02),
    ENSO_03(3, R.raw.enso_03),
    ENSO_04(4, R.raw.enso_04),
    ENSO_05(5, R.raw.enso_05),
    ENSO_06(6, R.raw.enso_06),
    ENSO_07(7, R.raw.enso_07),
    ENSO_08(8, R.raw.enso_08),
    ENSO_09(9, R.raw.enso_09),
    ENSO_10(10, R.raw.enso_10),
    ENSO_11(11, R.raw.enso_11),
    ENSO_12(12, R.raw.enso_12),
    ENSO_13(13, R.raw.enso_13),
    ENSO_14(14, R.raw.enso_14),
    ENSO_15(15, R.raw.enso_15),
    ENSO_16(16, R.raw.enso_16),
    ENSO_17(17, R.raw.enso_17),
    ENSO_18(18, R.raw.enso_18),
    ENSO_19(19, R.raw.enso_19),
    ENSO_20(20, R.raw.enso_20),
    ;

    val debugLabel: String get() = number.toString().padStart(2, '0')

    companion object {
        val all: List<EnsoVariant> = values().toList()
    }
}

/** Immutable selection owned by one visible learning-artwork instance. */
class EnsoInstance internal constructor(val variant: EnsoVariant)

/** Central selection API for the complete 20-variant library. */
class EnsoLibrary(private val random: Random = Random.Default) {
    val variants: List<EnsoVariant> get() = EnsoVariant.all

    fun createInstance(previous: EnsoInstance? = null): EnsoInstance {
        val candidates = if (previous == null) {
            variants
        } else {
            variants.filterNot { it == previous.variant }
        }
        return EnsoInstance(candidates[random.nextInt(candidates.size)])
    }

    fun newShuffleBag(): EnsoShuffleBag = EnsoShuffleBag(variants, random)
}

/** Assigns every variant once per shuffled cycle and avoids repeats across cycle boundaries. */
class EnsoShuffleBag internal constructor(
    private val variants: List<EnsoVariant>,
    private val random: Random,
) {
    private val remaining = ArrayDeque<EnsoVariant>()
    private var lastVariant: EnsoVariant? = null

    fun next(): EnsoVariant {
        if (remaining.isEmpty()) refill()
        return remaining.removeFirst().also { lastVariant = it }
    }

    private fun refill() {
        val shuffled = variants.shuffled(random).toMutableList()
        if (shuffled.size > 1 && shuffled.first() == lastVariant) {
            val replacement = shuffled.indexOfFirst { it != lastVariant }
            val first = shuffled[0]
            shuffled[0] = shuffled[replacement]
            shuffled[replacement] = first
        }
        remaining.addAll(shuffled)
    }
}
