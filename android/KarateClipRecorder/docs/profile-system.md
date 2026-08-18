# Local trainee profile system

## Ownership and storage

`ProfileRepository` is the only application entry point for profile identity and trainee-owned
records. It stores profiles, learning progress, training sessions, and calibrations in
`trainee_profiles.db` through `ProfileDatabase`. Every owned table has a `profile_id` foreign key
with `ON DELETE CASCADE`. The selected profile ID remains in `AppPreferences` because selection is
small app-navigation state; theme, audio, camera, and developer settings remain global.

The repository repairs a missing or stale active ID and seeds a local `Trainee` profile when the
database is empty. Deleting the last profile immediately creates a replacement, so callers never
observe an active ID that points to a deleted row.

## Active context

Activity-scoped views share one repository instance. Active-profile listeners update the reusable
top-level avatar button and rebuild profile-scoped Train/Progress summaries without restarting the
activity. Profile, editor, and management views are passive secondary destinations.

## Avatar assets

The original 12 vectorizer SVG files are retained in `app/src/main/avatar-sources`. Run
`tools/generate_avatar_assets.ps1` after source-art changes. The generator preserves SVG path order
and emits semantic path models under `app/src/main/assets/avatars`, classifying background, outline,
skin, hair, gi/fixed paint, and belt/accessory families. `AvatarView` parses each model once, caches
the paths, and applies per-instance curated skin/hair gradients plus the selected Kyokushin belt.

## Integrated flows and follow-ups

Integrated now:

- Japanese counting Practice/Test and the guided Jōdan completion write active-profile progress.
- Guided Jōdan and Punch Heights completions create profile-owned session rows.
- Camera setup and Punch Heights files use per-profile directories; camera setup also writes a
  profile-owned calibration record.
- Clearing global training history clears the structured session rows after filesystem deletion.

Follow-up seams:

- Existing guided-video media is still produced by the established CameraX recorder. Its database
  session row is profile-owned, but moving all clip/metadata directories below a profile folder is
  intentionally deferred to the recorder/storage migration rather than changing camera code here.
- Analyzer result payloads remain compact file references. A future schema can normalize metrics
  without replacing profile IDs or existing learning/session rows.
- Height, dominant side, and experience are persisted nullable profile fields. Dedicated detail
  editors and recommendation logic can be added without a database migration.
