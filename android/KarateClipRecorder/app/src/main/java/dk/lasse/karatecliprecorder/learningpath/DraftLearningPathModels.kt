package dk.lasse.karatecliprecorder.learningpath

import android.content.Context
import dk.lasse.karatecliprecorder.R
import org.json.JSONObject

const val KARATE_BASICS_PATH_ID = "karate-basics"

enum class DraftActivityType { CONDITIONAL_PROFILE, PLACEHOLDER }

data class DraftActivityDefinition(
    val id: String,
    val title: String,
    val type: DraftActivityType,
    val prerequisites: List<String>,
)

data class DraftSectionDefinition(
    val id: String,
    val title: String,
    val activities: List<DraftActivityDefinition>,
)

data class DraftLearningPathDefinition(
    val id: String,
    val title: String,
    val purpose: String,
    val sections: List<DraftSectionDefinition>,
) {
    val activities: List<DraftActivityDefinition> get() = sections.flatMap(DraftSectionDefinition::activities)
}

enum class DraftActivityProgressState { COMPLETED, AVAILABLE, LOCKED }

data class ResolvedDraftActivity(
    val definition: DraftActivityDefinition,
    val section: DraftSectionDefinition,
    val progressState: DraftActivityProgressState,
)

data class ResolvedDraftLearningPath(
    val definition: DraftLearningPathDefinition,
    val sections: List<Pair<DraftSectionDefinition, List<ResolvedDraftActivity>>>,
) {
    val activities: List<ResolvedDraftActivity> get() = sections.flatMap { it.second }
    val completedCount: Int get() = activities.count { it.progressState == DraftActivityProgressState.COMPLETED }
    val totalCount: Int get() = activities.size
}

/** Loads the editable curriculum graph; the renderer contains no activity ordering or prerequisites. */
object DraftLearningPathCatalog {
    fun karateBasics(context: Context): DraftLearningPathDefinition = parse(
        context.resources.openRawResource(R.raw.karate_basics_path).bufferedReader().use { it.readText() },
    )

    internal fun parse(json: String): DraftLearningPathDefinition {
        val root = JSONObject(json)
        val sectionsJson = root.getJSONArray("sections")
        val sections = buildList {
            for (sectionIndex in 0 until sectionsJson.length()) {
                val sectionJson = sectionsJson.getJSONObject(sectionIndex)
                val activitiesJson = sectionJson.getJSONArray("activities")
                add(DraftSectionDefinition(
                    id = sectionJson.getString("id"),
                    title = sectionJson.getString("title"),
                    activities = buildList {
                        for (activityIndex in 0 until activitiesJson.length()) {
                            val activityJson = activitiesJson.getJSONObject(activityIndex)
                            val prerequisitesJson = activityJson.getJSONArray("prerequisites")
                            add(DraftActivityDefinition(
                                id = activityJson.getString("id"),
                                title = activityJson.getString("title"),
                                type = when (activityJson.getString("type")) {
                                    "conditional" -> DraftActivityType.CONDITIONAL_PROFILE
                                    else -> DraftActivityType.PLACEHOLDER
                                },
                                prerequisites = buildList {
                                    for (index in 0 until prerequisitesJson.length()) {
                                        add(prerequisitesJson.getString(index))
                                    }
                                },
                            ))
                        }
                    },
                ))
            }
        }
        val path = DraftLearningPathDefinition(
            id = root.getString("id"),
            title = root.getString("title"),
            purpose = root.getString("purpose"),
            sections = sections,
        )
        validate(path)
        return path
    }

    private fun validate(path: DraftLearningPathDefinition) {
        val ids = path.activities.map(DraftActivityDefinition::id)
        require(ids.size == ids.toSet().size) { "Draft activity ids must be unique" }
        require(path.id == KARATE_BASICS_PATH_ID) { "Unexpected draft path id: ${path.id}" }
        val knownIds = ids.toSet()
        path.activities.forEach { activity ->
            require(activity.prerequisites.all(knownIds::contains)) {
                "${activity.id} has an unknown prerequisite"
            }
        }
    }
}

object DraftLearningPathProgressResolver {
    fun resolve(
        path: DraftLearningPathDefinition,
        completedActivityIds: Set<String>,
        activeProfileExists: Boolean,
    ): ResolvedDraftLearningPath {
        val effectiveCompleted = completedActivityIds.toMutableSet().apply {
            if (activeProfileExists) {
                path.activities.filter { it.type == DraftActivityType.CONDITIONAL_PROFILE }
                    .mapTo(this, DraftActivityDefinition::id)
            }
        }
        return ResolvedDraftLearningPath(
            definition = path,
            sections = path.sections.map { section ->
                section to section.activities.map { activity ->
                    val state = when {
                        activity.id in effectiveCompleted -> DraftActivityProgressState.COMPLETED
                        activity.prerequisites.all(effectiveCompleted::contains) -> DraftActivityProgressState.AVAILABLE
                        else -> DraftActivityProgressState.LOCKED
                    }
                    ResolvedDraftActivity(activity, section, state)
                }
            },
        )
    }

    fun nextAvailable(
        path: DraftLearningPathDefinition,
        completedActivityIds: Set<String>,
        activeProfileExists: Boolean,
        afterActivityId: String? = null,
    ): ResolvedDraftActivity? {
        val resolved = resolve(path, completedActivityIds, activeProfileExists)
        val current = resolved.activities.firstOrNull { it.definition.id == afterActivityId }
        val sameSectionNext = current?.let { selected ->
            resolved.sections.firstOrNull { it.first.id == selected.section.id }
                ?.second
                ?.dropWhile { it.definition.id != selected.definition.id }
                ?.drop(1)
                ?.firstOrNull { it.progressState == DraftActivityProgressState.AVAILABLE }
        }
        return sameSectionNext ?: resolved.activities
            .dropWhile { afterActivityId != null && it.definition.id != afterActivityId }
            .drop(if (afterActivityId == null) 0 else 1)
            .firstOrNull { it.progressState == DraftActivityProgressState.AVAILABLE }
            ?: resolved.activities.firstOrNull { it.progressState == DraftActivityProgressState.AVAILABLE }
    }
}
