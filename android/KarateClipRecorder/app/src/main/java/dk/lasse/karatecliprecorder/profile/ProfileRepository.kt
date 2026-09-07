package dk.lasse.karatecliprecorder.profile

import android.content.Context
import dk.lasse.karatecliprecorder.AppPreferences

/** Single application entry point for active-trainee identity and owned persistent data. */
class ProfileRepository(
    context: Context,
    private val preferences: AppPreferences,
) {
    private val database = ProfileDatabase(context)
    private val listeners = linkedSetOf<(Profile) -> Unit>()

    init {
        ensureDefaultProfile()
    }

    fun listProfiles(): List<Profile> = database.profiles()

    fun createProfile(profile: Profile): Profile {
        database.insertProfile(profile.copy(name = profile.name.trim()))
        if (preferences.activeProfileId == null) preferences.activeProfileId = profile.id
        notifyActiveChanged()
        return profile
    }

    fun updateProfile(profile: Profile): Profile {
        val existing = requireNotNull(database.profile(profile.id)) { "Profile does not exist: ${profile.id}" }
        val updated = profile.copy(
            name = profile.name.trim(),
            createdAt = existing.createdAt,
            updatedAt = System.currentTimeMillis(),
        )
        database.updateProfile(updated)
        if (updated.id == activeProfile().id) notifyActiveChanged()
        return updated
    }

    fun deleteProfile(profileId: String) {
        if (!database.deleteProfile(profileId)) return
        val remaining = database.profiles()
        if (remaining.isEmpty()) {
            val replacement = Profile.default()
            database.insertProfile(replacement)
            preferences.activeProfileId = replacement.id
        } else if (preferences.activeProfileId == profileId || database.profile(preferences.activeProfileId.orEmpty()) == null) {
            preferences.activeProfileId = remaining.first().id
        }
        notifyActiveChanged()
    }

    fun activeProfile(): Profile {
        preferences.activeProfileId?.let(database::profile)?.let { return it }
        val repaired = database.profiles().firstOrNull() ?: Profile.default().also(database::insertProfile)
        preferences.activeProfileId = repaired.id
        return repaired
    }

    fun switchActiveProfile(profileId: String): Profile {
        val profile = requireNotNull(database.profile(profileId)) { "Profile does not exist: $profileId" }
        if (preferences.activeProfileId != profileId) {
            preferences.activeProfileId = profileId
            notifyActiveChanged(profile)
        }
        return profile
    }

    fun addActiveProfileListener(listener: (Profile) -> Unit) {
        listeners += listener
        listener(activeProfile())
    }

    fun removeActiveProfileListener(listener: (Profile) -> Unit) {
        listeners -= listener
    }

    fun learningProgress(profileId: String = activeProfile().id) = database.learningProgress(profileId)

    fun saveLearningProgress(progress: LearningProgress) {
        database.upsertLearningProgress(progress)
        if (progress.profileId == activeProfile().id) notifyActiveChanged()
    }

    fun saveActiveLearningProgress(
        learningPathId: String,
        activityId: String,
        status: LearningStatus,
        completedAt: Long? = if (status == LearningStatus.COMPLETED) System.currentTimeMillis() else null,
    ) = saveLearningProgress(
        LearningProgress(activeProfile().id, learningPathId, activityId, status, completedAt = completedAt),
    )

    /** Updates recency without turning a replayed completed activity back into in-progress. */
    fun touchActiveLearningActivity(learningPathId: String, activityId: String) {
        val existing = learningProgress().firstOrNull {
            it.learningPathId == learningPathId && it.activityId == activityId
        }
        saveLearningProgress(LearningProgress(
            profileId = activeProfile().id,
            learningPathId = learningPathId,
            activityId = activityId,
            status = existing?.status ?: LearningStatus.IN_PROGRESS,
            completedAt = existing?.completedAt,
        ))
    }

    fun trainingSessions(profileId: String = activeProfile().id) = database.trainingSessions(profileId)

    fun saveTrainingSession(session: TrainingSession) {
        database.insertTrainingSession(session)
        if (session.profileId == activeProfile().id) notifyActiveChanged()
    }

    fun clearAllTrainingSessions() {
        database.clearTrainingSessions()
        notifyActiveChanged()
    }

    fun calibrations(profileId: String = activeProfile().id) = database.calibrations(profileId)

    fun saveCalibration(calibration: Calibration) {
        database.upsertCalibration(calibration)
        if (calibration.profileId == activeProfile().id) notifyActiveChanged()
    }

    internal fun close() = database.close()

    private fun ensureDefaultProfile() {
        if (database.profiles().isEmpty()) database.insertProfile(Profile.default())
        activeProfile()
    }

    private fun notifyActiveChanged(profile: Profile = activeProfile()) {
        listeners.toList().forEach { it(profile) }
    }
}
