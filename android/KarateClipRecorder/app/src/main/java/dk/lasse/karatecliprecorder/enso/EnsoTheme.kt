package dk.lasse.karatecliprecorder.enso

/** App-level design token for every Enso background in the application. */
object EnsoThemeTokens {
    /** Product-accent Enso used where activity semantics do not apply, such as Continue. */
    val ensoBaseColor: Int = 0xFFBE000C.toInt()

    /** Warm neutral artwork color for learning and self-practice activities. */
    val ensoPracticeBaseColor: Int = 0xFFC6AE86.toInt()

    /** Muted iron-red artwork color for tests and assessments; it is not an error token. */
    val ensoTestBaseColor: Int = 0xFF9E4032.toInt()
}

/**
 * Maps one theme color onto the shared 15-level ink-density vocabulary.
 *
 * Each canonical gray is treated as the amount of white mixed into the base color. A black base
 * therefore reproduces the source grayscale values exactly, while any other base retains the same
 * relative light/dark structure.
 */
class EnsoTonePalette private constructor(
    val baseColor: Int,
    private val toneColors: IntArray,
) {
    fun colorForTone(tone: Int): Int {
        require(tone in 1..TONE_COUNT) { "Enso tone must be between 1 and $TONE_COUNT." }
        return toneColors[tone - 1]
    }

    fun asList(): List<Int> = toneColors.toList()

    companion object {
        const val TONE_COUNT = 15

        val canonicalGray = intArrayOf(
            14, 30, 52, 71, 90,
            107, 121, 134, 147, 160,
            175, 188, 205, 225, 246,
        )

        fun fromBaseColor(baseColor: Int): EnsoTonePalette = EnsoTonePalette(
            baseColor = baseColor,
            toneColors = canonicalGray.map { gray -> mixWithWhite(baseColor, gray) }.toIntArray(),
        )

        private fun mixWithWhite(baseColor: Int, whiteAmount: Int): Int {
            val baseWeight = 255 - whiteAmount
            fun mix(channel: Int): Int = (channel * baseWeight + 255 * whiteAmount + 127) / 255

            val red = mix((baseColor ushr 16) and 0xFF)
            val green = mix((baseColor ushr 8) and 0xFF)
            val blue = mix(baseColor and 0xFF)
            return (0xFF shl 24) or (red shl 16) or (green shl 8) or blue
        }
    }
}
