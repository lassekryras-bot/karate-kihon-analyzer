package dk.lasse.karatecliprecorder

import kotlin.test.Test
import kotlin.test.assertEquals

class PermissionRequestPolicyTest {
    @Test fun liveGrantedStateNeedsNoAction() {
        assertEquals(
            PermissionRequestRoute.NONE,
            PermissionRequestPolicy.route(granted = true, requestedBefore = true, shouldShowRationale = false),
        )
    }

    @Test fun neverRequestedAndNormallyDeniedPermissionsUseTheRuntimeDialog() {
        assertEquals(
            PermissionRequestRoute.RUNTIME_DIALOG,
            PermissionRequestPolicy.route(granted = false, requestedBefore = false, shouldShowRationale = false),
        )
        assertEquals(
            PermissionRequestRoute.RUNTIME_DIALOG,
            PermissionRequestPolicy.route(granted = false, requestedBefore = true, shouldShowRationale = true),
        )
    }

    @Test fun permanentlyDeniedPermissionUsesAppSettings() {
        assertEquals(
            PermissionRequestRoute.APP_SETTINGS,
            PermissionRequestPolicy.route(granted = false, requestedBefore = true, shouldShowRationale = false),
        )
    }

    @Test fun refreshedPlatformStateCanChangeInEitherDirection() {
        val denied = PermissionRequestPolicy.route(false, requestedBefore = true, shouldShowRationale = false)
        val granted = PermissionRequestPolicy.route(true, requestedBefore = true, shouldShowRationale = false)
        val revokedAgain = PermissionRequestPolicy.route(false, requestedBefore = true, shouldShowRationale = false)

        assertEquals(PermissionRequestRoute.APP_SETTINGS, denied)
        assertEquals(PermissionRequestRoute.NONE, granted)
        assertEquals(PermissionRequestRoute.APP_SETTINGS, revokedAgain)
    }
}
