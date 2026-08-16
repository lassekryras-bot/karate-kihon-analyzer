package dk.lasse.karatecliprecorder.learningpath

import dk.lasse.karatecliprecorder.enso.EnsoLibrary
import dk.lasse.karatecliprecorder.enso.EnsoVariant
import dk.lasse.karatecliprecorder.learningartwork.LearningArtworkForeground

enum class LearningPathId { JODAN_PUNCH, CHUDAN_PUNCH, GEDAN_PUNCH, JAPANESE_COUNTING }

enum class LearningStepType { REGULAR, MILESTONE }

enum class LearningProgressState { COMPLETED, CURRENT, AVAILABLE, LOCKED }

enum class LearningDestination { JODAN_SESSION, JAPANESE_COUNTING_PRACTICE, JAPANESE_COUNTING_TEST }

data class LearningStepMetadata(
    val estimatedMinutes: Int? = null,
    val cameraRequired: Boolean = false,
)

data class LearningStep(
    val id: String,
    val number: Int,
    val title: String,
    val description: String,
    val progressState: LearningProgressState,
    val metadata: LearningStepMetadata? = null,
    val destination: LearningDestination? = null,
)

data class LearningMilestone(
    val title: String,
    val description: String,
    val progressState: LearningProgressState,
)

data class LearningPath(
    val id: LearningPathId,
    val title: String,
    val subtitle: String,
    val category: String,
    val artwork: LearningArtworkForeground,
    val ensoVariant: EnsoVariant,
    val progressValue: Int,
    val steps: List<LearningStep>,
    val milestone: LearningMilestone,
) {
    val totalSteps: Int get() = steps.size
}

object LearningPathCatalog {
    fun create(): List<LearningPath> {
        val ensos = EnsoLibrary().newShuffleBag()
        return listOf(
            jodan(ensos.next()),
            punchingPath(
                id = LearningPathId.CHUDAN_PUNCH,
                title = "Chūdan Punch",
                subtitle = "Body-height straight punch",
                artwork = LearningArtworkForeground.CHUDAN_PUNCH,
                enso = ensos.next(),
            ),
            punchingPath(
                id = LearningPathId.GEDAN_PUNCH,
                title = "Gedan Punch",
                subtitle = "Lower-height straight punch",
                artwork = LearningArtworkForeground.GEDAN_PUNCH,
                enso = ensos.next(),
            ),
            japaneseCounting(ensos.next()),
        )
    }

    private fun jodan(enso: EnsoVariant) = LearningPath(
        id = LearningPathId.JODAN_PUNCH,
        title = "Jōdan Punch",
        subtitle = "Head-height straight punch",
        category = "Punching",
        artwork = LearningArtworkForeground.JODAN_PUNCH,
        ensoVariant = enso,
        progressValue = 4,
        steps = listOf(
            step(1, "Introduction", "What is the technique and why it matters.", LearningProgressState.COMPLETED),
            step(2, "Target height", "Understand the correct head height target.", LearningProgressState.COMPLETED),
            step(3, "Body calibration", "Calibrate your body for accurate feedback.", LearningProgressState.COMPLETED),
            step(
                4,
                "Controlled technique",
                "Practise the movement slowly with focus on correct form.",
                LearningProgressState.CURRENT,
                LearningStepMetadata(estimatedMinutes = 3, cameraRequired = true),
                LearningDestination.JODAN_SESSION,
            ),
            step(5, "Repetition", "Perform 10 alternating punches.", LearningProgressState.AVAILABLE),
            step(6, "Combination / variation", "Combine the punch with movement.", LearningProgressState.LOCKED),
            step(7, "Final challenge", "Show that you can do it with confidence.", LearningProgressState.LOCKED),
        ),
        milestone = LearningMilestone(
            title = "Jōdan Foundations",
            description = "Complete all steps to unlock this milestone.",
            progressState = LearningProgressState.LOCKED,
        ),
    )

    private fun punchingPath(
        id: LearningPathId,
        title: String,
        subtitle: String,
        artwork: LearningArtworkForeground,
        enso: EnsoVariant,
    ): LearningPath = LearningPath(
        id = id,
        title = title,
        subtitle = subtitle,
        category = "Punching",
        artwork = artwork,
        ensoVariant = enso,
        progressValue = 0,
        steps = listOf(
            step(1, "Introduction", "Learn the purpose and key principles.", LearningProgressState.CURRENT),
            step(2, "Target height", "Understand the correct target area.", LearningProgressState.LOCKED),
            step(3, "Body calibration", "Calibrate your body for accurate feedback.", LearningProgressState.LOCKED),
            step(4, "Controlled technique", "Practise slowly with correct form.", LearningProgressState.LOCKED),
            step(5, "Repetition", "Build consistency through repetition.", LearningProgressState.LOCKED),
            step(6, "Final challenge", "Perform the technique with confidence.", LearningProgressState.LOCKED),
        ),
        milestone = LearningMilestone(
            title = "$title Foundations",
            description = "Complete all steps to unlock this milestone.",
            progressState = LearningProgressState.LOCKED,
        ),
    )

    private fun japaneseCounting(enso: EnsoVariant) = LearningPath(
        id = LearningPathId.JAPANESE_COUNTING,
        title = "Japanese Basic Counting",
        subtitle = "Learn karate counting",
        category = "Japanese",
        artwork = LearningArtworkForeground.JAPANESE_COUNTING,
        ensoVariant = enso,
        progressValue = 0,
        steps = listOf(
            step(
                1,
                "Practice",
                "Learn and practise Japanese counting number by number.",
                LearningProgressState.CURRENT,
                destination = LearningDestination.JAPANESE_COUNTING_PRACTICE,
            ),
            step(
                2,
                "Test",
                "Count from 1 to 10 without assistance.",
                LearningProgressState.LOCKED,
                destination = LearningDestination.JAPANESE_COUNTING_TEST,
            ),
        ),
        milestone = LearningMilestone(
            title = "Counting Foundations",
            description = "Complete Practice and Test to earn this milestone.",
            progressState = LearningProgressState.LOCKED,
        ),
    )

    private fun step(
        number: Int,
        title: String,
        description: String,
        state: LearningProgressState,
        metadata: LearningStepMetadata? = null,
        destination: LearningDestination? = null,
    ) = LearningStep(
        id = "step-$number",
        number = number,
        title = title,
        description = description,
        progressState = state,
        metadata = metadata,
        destination = destination,
    )
}
