package dk.lasse.karateanalyzer.capture

/**
 * Cadence profiles for continuous movement capture.
 * Dictates completion/settling dwell expectations:
 * - NORMAL: conservative capture (120ms settling window, 100ms quiet dwell)
 * - REPETITIONS: discrete repeated techniques with shorter settling requirement
 * - COMBINATION: parked placeholder for multi-technique combinations
 */
enum class RecordingCadence {
    NORMAL,
    REPETITIONS,
    COMBINATION,
}

/**
 * Evidence scopes for declaring that an activity-specific movement has settled.
 * Start detection remains strictly whole-body regardless of scope.
 */
enum class SettlingEvidenceScope {
    WHOLE_BODY,
    UPPER_BODY,
    LOWER_BODY,
}

data class BaseRecordingProfile(
    val cadence: RecordingCadence = RecordingCadence.NORMAL,
    val settlingEvidenceScope: SettlingEvidenceScope = SettlingEvidenceScope.WHOLE_BODY,
    val translationMovingThreshold: Double = 0.70, // Lref/s
    val translationQuietThreshold: Double = when (cadence) {
        RecordingCadence.REPETITIONS -> 0.55
        else -> 0.45
    },
    val angularMovingThreshold: Double = 35.0,     // deg/s
    val angularQuietThreshold: Double = 20.0,      // deg/s
    val startDwellMs: Long = 50L,
    val quietDwellMs: Long = when (cadence) {
        RecordingCadence.REPETITIONS -> 80L
        else -> 100L
    },
    val settlingWindowMs: Long = 120L,
    val maxMovingInterruptionMs: Long = 30L,
    val preRollMs: Long = 150L,
    val postRollMs: Long = 200L,
) {
    init {
        require(startDwellMs >= 0L && quietDwellMs >= 0L)
        require(settlingWindowMs >= quietDwellMs) {
            "settlingWindowMs ($settlingWindowMs) must be >= quietDwellMs ($quietDwellMs)"
        }
        require(maxMovingInterruptionMs >= 0L)
        require(preRollMs >= 0L && postRollMs >= 0L)
        require(translationMovingThreshold >= translationQuietThreshold && translationQuietThreshold >= 0.0)
        require(angularMovingThreshold >= angularQuietThreshold && angularQuietThreshold >= 0.0)
    }

    companion object {
        fun normal(scope: SettlingEvidenceScope = SettlingEvidenceScope.WHOLE_BODY) = BaseRecordingProfile(
            cadence = RecordingCadence.NORMAL,
            settlingEvidenceScope = scope,
        )

        fun repetitions(scope: SettlingEvidenceScope = SettlingEvidenceScope.UPPER_BODY) = BaseRecordingProfile(
            cadence = RecordingCadence.REPETITIONS,
            settlingEvidenceScope = scope,
        )
    }
}
