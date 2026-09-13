package dk.lasse.karatecliprecorder

/** The user action that can resolve the current platform permission state. */
enum class PermissionRequestRoute {
    NONE,
    RUNTIME_DIALOG,
    APP_SETTINGS,
}

/**
 * Request history distinguishes an unrequested permission from Android no longer offering a
 * runtime dialog. It is never used as permission state; granted/denied always comes from Android.
 */
object PermissionRequestPolicy {
    fun route(
        granted: Boolean,
        requestedBefore: Boolean,
        shouldShowRationale: Boolean,
    ): PermissionRequestRoute = when {
        granted -> PermissionRequestRoute.NONE
        !requestedBefore || shouldShowRationale -> PermissionRequestRoute.RUNTIME_DIALOG
        else -> PermissionRequestRoute.APP_SETTINGS
    }
}
