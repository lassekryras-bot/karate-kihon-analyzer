package dk.lasse.karatecliprecorder.profile

import java.util.UUID

enum class Gender(val displayName: String) { FEMALE("Female"), MALE("Male") }

enum class AgeGroup(val displayName: String) { CHILD("Child"), ADULT("Adult") }

/** Keep this declaration order aligned with Kyokushin rank progression. */
enum class BeltRank(val displayName: String) {
    WHITE("White"),
    ORANGE("Orange"),
    BLUE("Blue"),
    YELLOW("Yellow"),
    GREEN("Green"),
    BROWN("Brown"),
    BLACK("Black"),
}

enum class DominantSide(val displayName: String) { LEFT("Left"), RIGHT("Right"), BOTH("Both") }

enum class ExperienceLevel(val displayName: String) {
    BEGINNER("Beginner"),
    INTERMEDIATE("Intermediate"),
    ADVANCED("Advanced"),
}

enum class LearningStatus { NOT_STARTED, IN_PROGRESS, COMPLETED }

enum class TrainingMode { LEARN, PRACTICE, SKILL_COACH }

data class Profile(
    val id: String = UUID.randomUUID().toString(),
    val name: String,
    val gender: Gender,
    val ageGroup: AgeGroup,
    val avatarBaseId: String,
    val skinTonePosition: Float,
    val hairColorPosition: Float,
    val beltRank: BeltRank,
    val heightCm: Float? = null,
    val dominantSide: DominantSide? = null,
    val experienceLevel: ExperienceLevel? = null,
    val createdAt: Long = System.currentTimeMillis(),
    val updatedAt: Long = createdAt,
) {
    init {
        require(name.isNotBlank()) { "Profile name cannot be blank" }
        require(avatarBaseId in AVATAR_BASE_IDS) { "Unknown avatar base: $avatarBaseId" }
        require(skinTonePosition in 0f..1f) { "Skin tone must be normalized" }
        require(hairColorPosition in 0f..1f) { "Hair color must be normalized" }
        require(heightCm == null || heightCm > 0f) { "Height must be positive" }
    }

    companion object {
        val AVATAR_BASE_IDS = (1..12).map { "avatar_%02d".format(it) }

        fun default(now: Long = System.currentTimeMillis()) = Profile(
            name = "Trainee",
            gender = Gender.FEMALE,
            ageGroup = AgeGroup.ADULT,
            avatarBaseId = AVATAR_BASE_IDS.first(),
            skinTonePosition = 0.5f,
            hairColorPosition = 0.35f,
            beltRank = BeltRank.WHITE,
            createdAt = now,
            updatedAt = now,
        )
    }
}

data class LearningProgress(
    val profileId: String,
    val learningPathId: String,
    val activityId: String,
    val status: LearningStatus,
    val lastUpdatedAt: Long = System.currentTimeMillis(),
    val completedAt: Long? = null,
)

data class TrainingSession(
    val id: String = UUID.randomUUID().toString(),
    val profileId: String,
    val mode: TrainingMode,
    val skillOrActivityId: String,
    val startedAt: Long = System.currentTimeMillis(),
    val completedAt: Long? = null,
    val resultPayload: String? = null,
)

data class Calibration(
    val id: String = UUID.randomUUID().toString(),
    val profileId: String,
    val calibrationType: String,
    val payload: String,
    val updatedAt: Long = System.currentTimeMillis(),
)
