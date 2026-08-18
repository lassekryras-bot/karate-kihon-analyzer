package dk.lasse.karatecliprecorder.learningpath

import dk.lasse.karatecliprecorder.profile.LearningProgress
import dk.lasse.karatecliprecorder.profile.LearningStatus

sealed interface RecentLearningTarget {
    val activityTitle: String
    val pathTitle: String
    val completedCount: Int
    val totalCount: Int

    data class Standard(
        val path: LearningPath,
        val step: LearningStep,
    ) : RecentLearningTarget {
        override val activityTitle: String get() = step.title
        override val pathTitle: String get() = path.title
        override val completedCount: Int get() = path.progressValue
        override val totalCount: Int get() = path.totalSteps
    }

    data class Draft(
        val path: ResolvedDraftLearningPath,
        val activity: ResolvedDraftActivity,
    ) : RecentLearningTarget {
        override val activityTitle: String get() = activity.definition.title
        override val pathTitle: String get() = path.definition.title
        override val completedCount: Int get() = path.completedCount
        override val totalCount: Int get() = path.totalCount
    }
}

/** Resolves Home's continue card from the newest persisted activity timestamp across all paths. */
internal object RecentLearningResolver {
    fun resolve(
        standardPaths: List<LearningPath>,
        karateBasics: DraftLearningPathDefinition,
        records: List<LearningProgress>,
        activeProfileExists: Boolean,
    ): RecentLearningTarget {
        val resolvedStandard = LearningPathProgressResolver.resolve(standardPaths, records)
        val standardByKey = resolvedStandard.flatMap { path ->
            path.steps.map { step -> (path.id.name to step.id) to RecentLearningTarget.Standard(path, step) }
        }.toMap()
        val completedDraftIds = records.asSequence()
            .filter { it.learningPathId == karateBasics.id && it.status == LearningStatus.COMPLETED }
            .mapTo(mutableSetOf()) { it.activityId }
        val resolvedDraft = DraftLearningPathProgressResolver.resolve(
            path = karateBasics,
            completedActivityIds = completedDraftIds,
            activeProfileExists = activeProfileExists,
        )
        val draftByKey = resolvedDraft.activities.associate { activity ->
            (karateBasics.id to activity.definition.id) to RecentLearningTarget.Draft(resolvedDraft, activity)
        }
        records.sortedByDescending(LearningProgress::lastUpdatedAt).forEach { record ->
            standardByKey[record.learningPathId to record.activityId]?.let { return it }
            draftByKey[record.learningPathId to record.activityId]?.let { return it }
        }

        val fallbackPath = resolvedStandard.firstOrNull { it.id == LearningPathId.JODAN_PUNCH }
            ?: resolvedStandard.first()
        val fallbackStep = fallbackPath.steps.firstOrNull {
            it.progressState == LearningProgressState.CURRENT || it.progressState == LearningProgressState.AVAILABLE
        } ?: fallbackPath.steps.last()
        return RecentLearningTarget.Standard(fallbackPath, fallbackStep)
    }
}
