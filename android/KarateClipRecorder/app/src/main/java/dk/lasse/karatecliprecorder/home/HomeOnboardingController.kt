package dk.lasse.karatecliprecorder.home

import android.content.Context
import dk.lasse.karatecliprecorder.AppDestination
import dk.lasse.karatecliprecorder.AppHeaderState
import dk.lasse.karatecliprecorder.AppNavigationState
import dk.lasse.karatecliprecorder.AppNavigationStateRepository
import dk.lasse.karatecliprecorder.AppPreferences
import dk.lasse.karatecliprecorder.HeaderAttentionTarget
import dk.lasse.karatecliprecorder.HeaderTrailingAction
import dk.lasse.karatecliprecorder.R
import dk.lasse.karatecliprecorder.learningpath.DraftLearningPathProgressResolver
import dk.lasse.karatecliprecorder.profile.Profile
import dk.lasse.karatecliprecorder.profile.ProfileAvatarState
import dk.lasse.karatecliprecorder.profile.ProfileRepository

/**
 * Authoritative controller coordinating Dojo Sensei Home presentation states:
 *
 * State A (NO_PROFILE_HOME): Unknown profile, Home destination only, Sensei welcome, "Create profile" CTA.
 * State B (ACTIVE_PROFILE_LEARNING_HOME): Active profile exists, app learning path incomplete,
 *                                        Home + Learning destinations, Sensei directs to Learning, "Continue Learning" CTA.
 * State C (ACTIVE_PROFILE_TRAINING_HOME): Active profile exists, app learning path complete,
 *                                        all navigation unlocked, Sensei greeting, "Record 30 straight punches" CTA.
 */
class HomeOnboardingController(
    private val context: Context,
    private val preferences: AppPreferences,
    private val profileRepository: ProfileRepository,
    private val onOpenProfile: () -> Unit,
    private val onOpenLearning: () -> Unit,
    private val onCreateProfile: () -> Unit = onOpenProfile,
    private val onRecordStraightPunches: (Int) -> Unit = {},
    val navigationRepository: AppNavigationStateRepository = AppNavigationStateRepository(context, preferences, profileRepository),
) {
    private val listeners = linkedSetOf<(SenseiHomeState, AppHeaderState, AppNavigationState) -> Unit>()
    private val profileObserver: (Profile) -> Unit = { notifyAllListeners() }

    val phase: HomeOnboardingPhase
        get() = when (currentMode()) {
            SenseiHomeMode.NO_PROFILE -> HomeOnboardingPhase.WELCOME
            SenseiHomeMode.ACTIVE_PROFILE_LEARNING -> HomeOnboardingPhase.LEARNING_DISCOVERY
            SenseiHomeMode.ACTIVE_PROFILE_TRAINING -> HomeOnboardingPhase.COMPLETED
        }

    init {
        profileRepository.addActiveProfileListener(profileObserver)
    }

    fun dispose() {
        profileRepository.removeActiveProfileListener(profileObserver)
    }

    fun addListener(listener: (SenseiHomeState, AppHeaderState, AppNavigationState) -> Unit) {
        listeners += listener
        notifyCurrentState(listener)
    }

    fun removeListener(listener: (SenseiHomeState, AppHeaderState, AppNavigationState) -> Unit) {
        listeners -= listener
    }

    fun currentMode(): SenseiHomeMode {
        val active = profileRepository.resolveActiveProfile() ?: return SenseiHomeMode.NO_PROFILE
        val learningComplete = DraftLearningPathProgressResolver.hasCompletedAppLearningPath(
            context = context,
            repository = profileRepository,
            profileId = active.id,
        )
        return if (learningComplete) {
            SenseiHomeMode.ACTIVE_PROFILE_TRAINING
        } else {
            SenseiHomeMode.ACTIVE_PROFILE_LEARNING
        }
    }

    fun onStartTapped() {
        onCreateProfile()
    }

    fun onProfileCompleted() {
        preferences.isProfileSetupComplete = true
        preferences.homeOnboardingPhase = phase.name
        navigationRepository.onProfileCompleted()
        notifyAllListeners()
    }

    fun onProfileCancelled() {
        notifyAllListeners()
    }

    fun onLearningOpened() {
        preferences.hasAcknowledgedLearningUnlock = true
        navigationRepository.onLearningOpened()
        notifyAllListeners()
    }

    fun executeAction(action: HomeAction) {
        when (action) {
            is HomeAction.CreateProfile -> onCreateProfile()
            is HomeAction.ContinueLearning -> onOpenLearning()
            is HomeAction.RecordStraightPunches -> onRecordStraightPunches(action.repetitions)
            is HomeAction.PrimaryCta -> action.onClick()
            is HomeAction.Guidance -> Unit
        }
    }

    fun currentHomeState(): SenseiHomeState = when (val mode = currentMode()) {
        SenseiHomeMode.NO_PROFILE -> SenseiHomeState(
            mode = mode,
            message = SenseiMessage(
                title = context.getString(R.string.home_sensei_welcome_title),
                body = context.getString(R.string.home_sensei_no_profile_body),
            ),
            action = HomeAction.CreateProfile,
            actionContent = HomeActionCardContent(
                category = context.getString(R.string.home_first_step_label),
                title = context.getString(R.string.home_first_step_title),
                body = context.getString(R.string.home_no_profile_step_body),
                buttonLabel = context.getString(R.string.home_action_create_profile),
            ),
        )
        SenseiHomeMode.ACTIVE_PROFILE_LEARNING -> {
            val active = profileRepository.activeProfile()
            SenseiHomeState(
                mode = mode,
                message = SenseiMessage(
                    title = context.getString(R.string.home_sensei_named_greeting, active.name),
                    body = context.getString(R.string.home_sensei_learning_body),
                ),
                action = HomeAction.ContinueLearning,
                actionContent = HomeActionCardContent(
                    category = context.getString(R.string.home_learning_action_category),
                    title = context.getString(R.string.home_learning_action_title),
                    body = context.getString(R.string.home_learning_action_body),
                    buttonLabel = context.getString(R.string.home_action_continue_learning),
                ),
            )
        }
        SenseiHomeMode.ACTIVE_PROFILE_TRAINING -> {
            val active = profileRepository.activeProfile()
            val reps = DEFAULT_RECOMMENDED_PUNCH_REPETITIONS
            SenseiHomeState(
                mode = mode,
                message = SenseiMessage(
                    title = context.getString(R.string.home_sensei_named_greeting, active.name),
                    body = context.getString(R.string.home_sensei_training_body),
                ),
                action = HomeAction.RecordStraightPunches(reps),
                actionContent = HomeActionCardContent(
                    category = context.getString(R.string.home_training_action_category),
                    title = context.getString(R.string.home_training_action_title),
                    body = context.getString(R.string.home_training_action_body, reps),
                    buttonLabel = context.getString(R.string.home_action_record_punches, reps),
                ),
            )
        }
    }

    fun currentHeaderState(): AppHeaderState {
        val active = profileRepository.resolveActiveProfile()
        val showUnknown = active == null
        val subtitle = if (showUnknown || active.name.isBlank()) {
            context.getString(R.string.home_welcome)
        } else {
            context.getString(R.string.home_welcome_named, active.name)
        }
        val avatarState = if (showUnknown) {
            ProfileAvatarState.UnknownProfile
        } else {
            ProfileAvatarState.ActiveWithAvatar(active)
        }
        val onAvatarClick = if (showUnknown) onCreateProfile else onOpenProfile
        return AppHeaderState(
            title = context.getString(R.string.app_display_name),
            subtitle = subtitle,
            trailingAction = HeaderTrailingAction.ProfileShortcut(avatarState, onAvatarClick),
            attentionTarget = HeaderAttentionTarget.NONE,
        )
    }

    fun currentNavigationState(): AppNavigationState = navigationRepository.currentNavigationState()

    fun notifyAllListeners() {
        val homeState = currentHomeState()
        val headerState = currentHeaderState()
        val navState = currentNavigationState()
        listeners.toList().forEach { it(homeState, headerState, navState) }
    }

    private fun notifyCurrentState(listener: (SenseiHomeState, AppHeaderState, AppNavigationState) -> Unit) {
        listener(currentHomeState(), currentHeaderState(), currentNavigationState())
    }
}
