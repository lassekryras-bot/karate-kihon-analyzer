package dk.lasse.karatecliprecorder.learningpath

import dk.lasse.karatecliprecorder.profile.LearningProgress
import dk.lasse.karatecliprecorder.profile.LearningStatus

/** Resolves catalog defaults plus the active trainee's persisted activity progress. */
internal object LearningPathProgressResolver {
    fun resolve(paths: List<LearningPath>, records: List<LearningProgress>): List<LearningPath> {
        val recordsByPath = records.groupBy { it.learningPathId }
        return paths.map { path ->
            val byActivity = recordsByPath[path.id.name].orEmpty().associateBy { it.activityId }
            var prerequisiteComplete = true
            val steps = path.steps.map { step ->
                val record = byActivity[step.id]
                val state = when {
                    record?.status == LearningStatus.COMPLETED -> LearningProgressState.COMPLETED
                    record?.status == LearningStatus.IN_PROGRESS -> LearningProgressState.CURRENT
                    step.progressState == LearningProgressState.COMPLETED -> LearningProgressState.COMPLETED
                    prerequisiteComplete -> LearningProgressState.CURRENT
                    else -> LearningProgressState.LOCKED
                }
                prerequisiteComplete = state == LearningProgressState.COMPLETED
                step.copy(progressState = state)
            }
            val completedCount = steps.count { it.progressState == LearningProgressState.COMPLETED }
            path.copy(
                progressValue = completedCount,
                steps = steps,
                milestone = path.milestone.copy(
                    progressState = if (completedCount == steps.size) {
                        LearningProgressState.COMPLETED
                    } else {
                        LearningProgressState.LOCKED
                    },
                ),
            )
        }
    }

    fun continuePath(paths: List<LearningPath>, records: List<LearningProgress>): LearningPath {
        require(paths.isNotEmpty()) { "At least one learning path is required" }
        val resolved = resolve(paths, records)
        val knownIds = paths.mapTo(mutableSetOf()) { it.id.name }
        val recentPathId = records.asSequence()
            .filter { it.learningPathId in knownIds }
            .maxByOrNull { it.lastUpdatedAt }
            ?.learningPathId
        return resolved.firstOrNull { it.id.name == recentPathId }
            ?: resolved.firstOrNull { it.id == LearningPathId.JODAN_PUNCH }
            ?: resolved.first()
    }
}
