# Permission Status UI Verification and Fix

**Status:** OPEN  
**Source:** Short design chat, 2026-09-10  
**Area:** Android app / Settings / Permissions

## Goal

Verify that the Permissions screen reflects the real Android camera and microphone permission state and uses a consistent visual language. Implement only what is missing.

## Requirement

For both `Manifest.permission.CAMERA` and `Manifest.permission.RECORD_AUDIO`:

- Read the actual Android runtime permission state.
- Granted -> show `Allowed` in green and use a check mark.
- Not granted -> show `Not allowed` in red and use an X.
- Keep the camera/microphone icon as the primary permission icon.
- Associate the check/X with that icon rather than using a separate shield/check metaphor.
- If a right-side status icon is retained, it must use the same check/X language as the left-side status.
- Do not keep a separate app-local permission state that can disagree with Android.

## Settings and lifecycle behavior

Verify whether the current app already does the following; add it only if needed:

- Normal Android runtime permission request while the permission can still be requested in-app.
- A route to Android app settings when the permission must be changed manually, e.g. after permanent denial.
- Re-check permission state when returning from Android settings so the UI updates without restarting the app.

Suitable settings intent:

```kotlin
Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS).apply {
    data = Uri.fromParts("package", context.packageName, null)
}
```

## Verify first

Before editing code:

1. Locate the current Permissions screen implementation.
2. Check how camera and microphone permission state is obtained.
3. Confirm whether the displayed state matches Android.
4. Confirm whether returning from system settings refreshes the state.
5. Confirm the denied/permanently-denied flow.
6. Compare the current icons and colors with the requirement above.

If the current implementation already satisfies a point, preserve it.

## Acceptance criteria

- Camera status matches Android camera permission.
- Microphone status matches Android microphone permission.
- Granted = green + `Allowed` + check.
- Denied = red + `Not allowed` + X.
- Camera/microphone remains the primary icon.
- No redundant shield/check metaphor remains unless it represents a separate documented concept.
- Permission changes made in Android settings are reflected after returning to the app.
- Users can reach Android app settings when required.
- Existing correct permission behavior is preserved.

## Suggested tests

Test camera and microphone independently for:

- never requested;
- granted;
- denied;
- permanently denied / settings required;
- denied -> granted in system settings;
- granted -> denied in system settings;
- correct refresh when returning to the app.

## Visual reference

The source chat included a screenshot of the current Permissions card. The important visual requirement is captured above: camera/microphone + check/X, with `Allowed`/`Not allowed` using matching green/red state colors. If the original screenshot is committed later, place it under `docs/backlog/assets/permission-status-ui/` and link it here.
