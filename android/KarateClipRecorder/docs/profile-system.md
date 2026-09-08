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

`AvatarView` renders transparent portraits and clips the original painted layers with the source artwork transparency mask.
Hosts own the surface: the editor preview and portrait cards use the app card
surface; `ProfileAvatarButton` supplies its own circular backdrop and clipping.
Skin, hair, gi, and belt/accessory rendering remain independent of this backdrop.

The editor's character carousel uses a close portrait crop, a fixed red selection
frame behind the moving cards, faded edges, and gray browsing chevrons. A swipe
settles one character at a time before updating the editor selection. See all
opens all twelve characters in a three-column grid; the selected character has a
red outline and choosing another synchronizes the carousel and preview. These
choices remain editor changes until Save changes is used. The full chooser has
no gender or age filters; artwork classification is deferred to a future avatar
overhaul.

## Integrated flows and follow-ups

Integrated now:

- Japanese counting Practice/Test and the guided Jōdan completion write active-profile progress.
- Guided Jōdan and Punch Heights completions create profile-owned session rows.
- Camera setup and Punch Heights files use per-profile directories; camera setup also writes a
  profile-owned calibration record.
- Manage profiles offers confirmed, profile-scoped training-history cleanup. It removes the selected profile's punch-height capture folder and structured session rows, preserving other profiles, learning progress and calibration. Legacy shared recordings remain because ownership is unknown.

Follow-up seams:

- Existing guided-video media is still produced by the established CameraX recorder. Its database
  session row is profile-owned, but moving all clip/metadata directories below a profile folder is
  intentionally deferred to the recorder/storage migration rather than changing camera code here.
- Analyzer result payloads remain compact file references. A future schema can normalize metrics
  without replacing profile IDs or existing learning/session rows.
- Height, dominant side, and experience are persisted nullable profile fields. Dedicated detail
  editors and recommendation logic can be added without a database migration.

## Profile management UI review

Profile has a title-only header and flat cards. Manage profiles uses user icons,
with current selection shown beneath profile details. Profile creation remains
on the main Profile page. Selecting a profile in Manage profiles opens its
management actions; Delete profile requires a separate confirmation. The editor
no longer contains deletion controls.

The step-by-step creation flow remains a planned follow-up. Manage profiles now
supports Reset learning progress with explicit confirmation, scoped to the selected
profile. Training history, calibration, identity, and other profiles remain intact.
Remove calibration data and Delete coaching history are Coming soon actions and
do not change data. Delete profile retains its separate confirmation.

The reviewed SVGs retain their original painted paths and semantic color roles.
Their transparency masks are exported by the normal asset generator and cached
as native clipping paths. The navigation avatar container supplies its own circle.

Body & calibration now provides optional height, forearm length and lower-leg length
in centimetres. Version 2 adds nullable limb-length columns without replacing
existing profiles. Blank fields clear measurements; invalid or nonpositive values
are rejected. These values are stored only and do not yet change analyzer output.
Profile removes duplicate Karate and Training rows and uses a smaller identity card.

The measurements destination is labelled Body measurements. Its Profile row shows
a red 0/3, 1/3 or 2/3 for missing measurements and a green check with 3/3 when all
three are saved. Camera calibration records do not affect this indicator.

Settings groups permissions, sound and voice, training preferences, appearance, developer tools and About. Camera setup and duplicate data/help/privacy entries are removed. About describes local storage, Android backup and possible online speech recognition; those platform behaviors are unchanged.
