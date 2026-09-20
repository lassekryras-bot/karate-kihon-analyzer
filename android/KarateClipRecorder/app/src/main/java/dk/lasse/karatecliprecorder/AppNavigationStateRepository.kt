package dk.lasse.karatecliprecorder

import android.content.Context
import dk.lasse.karatecliprecorder.learningpath.DraftLearningPathProgressResolver
import dk.lasse.karatecliprecorder.profile.Profile
import dk.lasse.karatecliprecorder.profile.ProfileRepository

/**
 * Authoritative global repository for progressive top-level app navigation state.
 *
 * Owns destination availability, canonical ordering, unlock announcement state,
 * and safe fallback resolution for the active training profile across all pages.
 */
class AppNavigationStateRepository(
    private val context: Context,
    private val preferences: AppPreferences,
    private val profileRepository: ProfileRepository,
) {
    private val listeners = linkedSetOf<(AppNavigationState) -> Unit>()
    private var currentDestination: AppDestination = AppDestination.HOME

    private val profileObserver: (Profile) -> Unit = {
        reconcileStateOnProfileChange()
    }

    init {
        profileRepository.addActiveProfileListener(profileObserver)
    }

    fun dispose() {
        profileRepository.removeActiveProfileListener(profileObserver)
    }

    fun addListener(listener: (AppNavigationState) -> Unit) {
        listeners += listener
        listener(currentNavigationState())
    }

    fun removeListener(listener: (AppNavigationState) -> Unit) {
        listeners -= listener
    }

    /**
     * Resolves the authoritative list of visible destinations based on active profile
     * and learning path completion status, always preserved in canonical ordering.
     */
    fun resolveVisibleDestinations(): List<AppDestination> {
        val active = profileRepository.resolveActiveProfile()
            ?: return listOf(AppDestination.HOME)

        val learningComplete = DraftLearningPathProgressResolver.hasCompletedAppLearningPath(
            context = context,
            repository = profileRepository,
            profileId = active.id,
        )

        val destinations = if (learningComplete) {
            listOf(
                AppDestination.HOME,
                AppDestination.LEARNING,
                AppDestination.TRAIN,
                AppDestination.PROGRESS,
                AppDestination.SETTINGS,
            )
        } else {
            listOf(AppDestination.HOME, AppDestination.LEARNING)
        }

        return destinations.distinct().sortedBy { it.ordinal }
    }

    /**
     * Returns the current immutable navigation state.
     */
    fun currentNavigationState(): AppNavigationState {
        val visible = resolveVisibleDestinations()
        val resolvedSelected = if (currentDestination in visible) {
            currentDestination
        } else if (AppDestination.HOME in visible) {
            AppDestination.HOME
        } else {
            visible.first()
        }
        currentDestination = resolvedSelected

        val learningComplete = visible.containsAll(listOf(AppDestination.TRAIN, AppDestination.PROGRESS, AppDestination.SETTINGS))
        val isNewlyUnlocked = (AppDestination.LEARNING in visible) && !learningComplete && !preferences.hasAcknowledgedLearningUnlock
        val newlyUnlocked = if (isNewlyUnlocked) setOf(AppDestination.LEARNING) else emptySet()
        val attention = if (isNewlyUnlocked) AppDestination.LEARNING else null

        return AppNavigationState(
            visibleDestinations = visible,
            selectedDestination = resolvedSelected,
            newlyUnlockedDestinations = newlyUnlocked,
            attentionDestination = attention,
        )
    }

    /**
     * Updates the selected destination. If the target is not visible, safely falls back to HOME.
     * Automatically acknowledges unlock for LEARNING when navigated to.
     */
    fun selectDestination(destination: AppDestination): AppDestination {
        val visible = resolveVisibleDestinations()
        val target = if (destination in visible) destination else AppDestination.HOME
        currentDestination = target
        if (target == AppDestination.LEARNING && !preferences.hasAcknowledgedLearningUnlock) {
            preferences.hasAcknowledgedLearningUnlock = true
        }
        notifyListeners()
        return target
    }

    fun onProfileCompleted() {
        preferences.isProfileSetupComplete = true
        notifyListeners()
    }

    fun onLearningOpened() {
        preferences.hasAcknowledgedLearningUnlock = true
        notifyListeners()
    }

    fun onLearningCompleted() {
        notifyListeners()
    }

    private fun reconcileStateOnProfileChange() {
        val visible = resolveVisibleDestinations()
        if (currentDestination !in visible) {
            currentDestination = AppDestination.HOME
        }
        notifyListeners()
    }

    private fun notifyListeners() {
        val state = currentNavigationState()
        listeners.toList().forEach { it(state) }
    }
}
