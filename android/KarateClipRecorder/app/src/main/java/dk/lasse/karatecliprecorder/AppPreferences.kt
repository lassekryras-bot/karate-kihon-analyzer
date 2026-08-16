package dk.lasse.karatecliprecorder

import android.content.Context
import androidx.appcompat.app.AppCompatDelegate

enum class AppTheme(
    val persistedValue: String,
    val displayName: String,
    val nightMode: Int,
) {
    SYSTEM("system", "System default", AppCompatDelegate.MODE_NIGHT_FOLLOW_SYSTEM),
    LIGHT("light", "Light", AppCompatDelegate.MODE_NIGHT_NO),
    DARK("dark", "Dark", AppCompatDelegate.MODE_NIGHT_YES),
    ;

    companion object {
        fun fromPersistedValue(value: String?): AppTheme = entries.firstOrNull {
            it.persistedValue == value
        } ?: SYSTEM
    }
}

/** Small SharedPreferences-backed store for user-facing app preferences. */
class AppPreferences(context: Context) {
    private val preferences = context.applicationContext.getSharedPreferences(
        PREFERENCES_NAME,
        Context.MODE_PRIVATE,
    )

    var trainingSounds: Boolean
        get() = preferences.getBoolean(KEY_TRAINING_SOUNDS, true)
        set(value) = preferences.edit().putBoolean(KEY_TRAINING_SOUNDS, value).apply()

    var voiceGuidance: Boolean
        get() = preferences.getBoolean(KEY_VOICE_GUIDANCE, true)
        set(value) = preferences.edit().putBoolean(KEY_VOICE_GUIDANCE, value).apply()

    var countdownSeconds: Int
        get() = preferences.getInt(KEY_COUNTDOWN_SECONDS, DEFAULT_COUNTDOWN_SECONDS)
            .takeIf(COUNTDOWN_OPTIONS_SECONDS::contains) ?: DEFAULT_COUNTDOWN_SECONDS
        set(value) {
            require(value in COUNTDOWN_OPTIONS_SECONDS) { "Unsupported countdown value: $value" }
            preferences.edit().putInt(KEY_COUNTDOWN_SECONDS, value).apply()
        }

    var defaultPracticeDurationMinutes: Int
        get() = preferences.getInt(KEY_PRACTICE_DURATION_MINUTES, DEFAULT_PRACTICE_DURATION_MINUTES)
            .takeIf(PRACTICE_DURATION_OPTIONS_MINUTES::contains) ?: DEFAULT_PRACTICE_DURATION_MINUTES
        set(value) {
            require(value in PRACTICE_DURATION_OPTIONS_MINUTES) { "Unsupported practice duration: $value" }
            preferences.edit().putInt(KEY_PRACTICE_DURATION_MINUTES, value).apply()
        }

    var theme: AppTheme
        get() = AppTheme.fromPersistedValue(preferences.getString(KEY_THEME, AppTheme.SYSTEM.persistedValue))
        set(value) = preferences.edit().putString(KEY_THEME, value.persistedValue).apply()

    var developerMode: Boolean
        get() = preferences.getBoolean(KEY_DEVELOPER_MODE, false)
        set(value) = preferences.edit().putBoolean(KEY_DEVELOPER_MODE, value).apply()

    companion object {
        val COUNTDOWN_OPTIONS_SECONDS = listOf(0, 3, 5, 10)
        val PRACTICE_DURATION_OPTIONS_MINUTES = listOf(5, 10, 15, 20, 30)
        const val DEFAULT_COUNTDOWN_SECONDS = 3
        const val DEFAULT_PRACTICE_DURATION_MINUTES = 10

        private const val PREFERENCES_NAME = "karate_kihon_analyzer_preferences"
        private const val KEY_TRAINING_SOUNDS = "training_sounds"
        private const val KEY_VOICE_GUIDANCE = "voice_guidance"
        private const val KEY_COUNTDOWN_SECONDS = "countdown_seconds"
        private const val KEY_PRACTICE_DURATION_MINUTES = "default_practice_duration_minutes"
        private const val KEY_THEME = "theme"
        private const val KEY_DEVELOPER_MODE = "developer_mode"
    }
}
