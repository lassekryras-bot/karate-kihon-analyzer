package dk.lasse.karatecliprecorder.training

import android.content.Context

data class AssistedCaptureSetup(val repetitions: Int = 10, val cadenceMs: Long = 1000,
                                val spokenCounting: Boolean = true,
                                val expectedActivity: String = "Alternating straight punches",
                                val expectedCategory: String = "Punches") {
    init {
        require(repetitions in 1..1000)
        require(cadenceMs in MIN_CADENCE_MS..10_000 && cadenceMs % 100 == 0L)
    }
    val firstCueDelayMs: Long get() = 500
    companion object {
        // Longest bundled count is 590 ms; leave 110 ms between samples.
        const val MIN_CADENCE_MS = 700L
        const val ACTIVITY_KEY = "record_and_analyze_assisted_v1"
    }
}

/** User-scoped API; development shares defaults. Switching scope needs no Room schema change. */
class TrainingPreferences(context: Context, private val sharedDevelopmentDefaults: Boolean = true) {
    private val preferences = context.applicationContext.getSharedPreferences("training_preferences", Context.MODE_PRIVATE)
    private fun key(userId: String) = if (sharedDevelopmentDefaults) "development" else "user.$userId"
    fun read(userId: String): AssistedCaptureSetup {
        val key = key(userId)
        return runCatching { AssistedCaptureSetup(preferences.getInt("$key.count", 10),
            preferences.getLong("$key.cadenceMs", 1000), preferences.getBoolean("$key.spoken", true)) }
            .getOrDefault(AssistedCaptureSetup())
    }
    fun save(userId: String, setup: AssistedCaptureSetup) {
        val key = key(userId)
        preferences.edit().putInt("$key.count", setup.repetitions).putLong("$key.cadenceMs", setup.cadenceMs)
            .putBoolean("$key.spoken", setup.spokenCounting).apply()
    }
}
