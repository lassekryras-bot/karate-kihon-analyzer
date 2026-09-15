# Movement Landmark Stream v1

MLS is the app's durable landmark evidence format, not a MediaPipe format.
MediaPipe is the current producer. Room stores ownership, pipeline/model hash,
decoder/configuration, source/processing state and SHA-256. Dense frames stay in
`files/training/landmarks/<landmarkTrackUuid>.mls`.

## Exact legacy layout

Inspection of `training/LandmarkFiles.kt` found this `karate_pose_track_v1`
layout. All integers/floats are Java DataInput/DataOutput big endian; `UTF`
means Java modified UTF-8 prefixed by an unsigned 16-bit byte length.

1. UTF `karate_pose_track_v1`.
2. Signed int32 frame count (positive).
3. For each frame: signed int64 recording-relative microseconds; signed int32
   landmark count.
4. For each landmark: UTF `PoseLandmarkId.name`; normalized-image point;
   world point; float32 visibility; float32 presence; UTF `LandmarkSource.name`.
5. Each point: one-byte boolean presence, followed when present by three float32
   values x, y, z. No bytes follow the last frame.

The old producer used `.pose`, staged with `.pending`, and multiplied the core
millisecond timestamp by 1000. It did not contain a metadata block. Landmark
identity/source are enum names, not ordinals. Map order is not semantically
significant. Useful legacy files retain a dedicated reader branch.

## Transition decision and MLS bytes

**This is a real header transition, not a byte-for-byte alias.** New writes use:

1. UTF `movement_landmark_stream_v1`.
2. Signed int32 version `1`.
3. UTF interpretation contract, exactly:
   `time=recording-relative-us;resolution=ms;image=normalized-upright-unmirrored;world=meters-hip-origin;identity=PoseLandmarkId;source=LandmarkSource`
4. Signed int32 frame count, then the same frame/landmark payload as above.

The reader dispatches by actual magic. Unknown magic/version/interpretation is
rejected. Old bytes are not renamed or silently declared MLS. Existing legacy
Room rows retain null format metadata after migration; new tracks explicitly
identify MLS v1. Extending identity/source enums or interpretation requires
reader compatibility review and potentially a new format version.

Timestamps must be nonnegative and strictly increasing. The current core model
and producer have millisecond resolution; persisted microseconds must therefore
be divisible by 1000. This is not a claim of microsecond sampling accuracy.
Normalized coordinates refer to the upright decoded video image, without preview
mirroring. World coordinates use the producer's hip-centered meter convention.
Confidence is raw landmark visibility/presence, not technique validity.
Frames with no detected pose retain their timestamp and an empty landmark map.

MLS contains no labels, segmentation, measurements, scores or coaching output.

## Publication and recovery

Create a new UUID `.mls.tmp` exclusively; write header and frames; flush, sync and
close; parse/validate the complete staging file; compute SHA-256; rename to the
previously nonexistent `.mls`; only then mark Room complete. Public reads reject
`.tmp` and `.pending`. Checksums, frame counts, timestamp order, identities,
duplicate landmarks and EOF are checked. The private writer validation is the
only staging-read path.

A published file with a PROCESSING Room track is validated and recovered without
MediaPipe decoding. A completed valid file is reused across reopen. A corrupt
file is marked failed and surfaced; explicit retry can create a new track UUID.
An interrupted unpublished track (including stale `.mls.tmp`) is marked failed,
never promoted. Retry writes a new UUID, retaining the failed file for diagnosis.
MP4 deletion leaves independent MLS evidence intact.

The bundled queue adds cooperative cancellation checks at frame/write boundaries
and a shared deletion/publication fence around staging-file creation, publication
and Room completion. It does not change MLS bytes or version. Cancellation may
remove the current unpublished staging file; process-death remnants are explicitly
marked failed on retry. User-facing Delete recording removes the full owned
graph plus MP4/MLS/staging files; the lower-level media-only delete still preserves
independent MLS evidence.
