package dk.lasse.karatecliprecorder.training

import android.media.MediaMetadataRetriever
import dk.lasse.karateanalyzer.geometry.CanonicalGeometryCodec
import dk.lasse.karateanalyzer.geometry.CanonicalGeometryDescriptor
import dk.lasse.karateanalyzer.geometry.CanonicalOrientation
import dk.lasse.karateanalyzer.geometry.SourceToCanonicalTransform
import dk.lasse.karateanalyzer.geometry.TransformOrder
import java.io.File

object CanonicalGeometryStorageAdapter {

    /**
     * Resolves the canonical geometry descriptor for a specific analysis track.
     * Enforces strict track-level selection and identity validation.
     * If unpersisted, performs legacy recovery using the storage-resolved video file
     * outside the UI thread, persisting the recovered descriptor into the database.
     * If the video file is unavailable/unresolvable, returns UNKNOWN_GEOMETRY.
     * Never assumes 0 deg rotation or fabricates 1080x1920 defaults.
     */
    fun resolveTrackGeometry(
        recording: MasterRecording,
        track: LandmarkTrack?,
        fileResolver: (String) -> File? = { File(it) },
        onPersistDescriptor: ((track: LandmarkTrack?, recording: MasterRecording, descriptorJson: String) -> Unit)? = null,
    ): CanonicalGeometryDescriptor {
        // 1. Strict track-level selection: Check track's persisted descriptor first
        if (track != null && !track.canonicalGeometryJson.isNullOrBlank()) {
            val decoded = CanonicalGeometryCodec.decode(track.canonicalGeometryJson)
            if (decoded != null && decoded.isAvailable) {
                // Validate recording and track identity
                val matchesRecording = decoded.recordingId == recording.recordingId || decoded.recordingId == recording.sessionId
                val matchesTrack = decoded.landmarkTrackId == null || decoded.landmarkTrackId == track.landmarkTrackId
                if (matchesRecording && matchesTrack) {
                    return decoded
                }
            }
        }

        // 2. Check recording's persisted descriptor
        if (!recording.canonicalGeometryJson.isNullOrBlank()) {
            val decoded = CanonicalGeometryCodec.decode(recording.canonicalGeometryJson)
            if (decoded != null && decoded.isAvailable) {
                val matchesRecording = decoded.recordingId == recording.recordingId || decoded.recordingId == recording.sessionId
                val matchesTrack = if (track != null) {
                    decoded.landmarkTrackId == null || decoded.landmarkTrackId == track.landmarkTrackId
                } else {
                    decoded.landmarkTrackId == null
                }
                if (matchesRecording && matchesTrack) {
                    if (decoded.landmarkTrackId != null) {
                        return decoded
                    }
                    // If track is present and lacks descriptor, adapt recording-level descriptor (landmarkTrackId == null) for track
                    if (track != null && track.canonicalGeometryJson.isNullOrBlank()) {
                        val trackDescriptor = CanonicalGeometryDescriptor(
                            geometryId = "geom_${recording.recordingId}_${track.landmarkTrackId}",
                            contractVersion = decoded.contractVersion,
                            recordingId = recording.recordingId,
                            landmarkTrackId = track.landmarkTrackId,
                            canonicalWidth = decoded.canonicalWidth,
                            canonicalHeight = decoded.canonicalHeight,
                            canonicalOrientation = decoded.canonicalOrientation,
                            sourceToCanonicalTransform = decoded.sourceToCanonicalTransform,
                            adapterVersion = decoded.adapterVersion,
                            encodedWidth = decoded.encodedWidth,
                            encodedHeight = decoded.encodedHeight,
                            containerRotation = decoded.containerRotation,
                        )
                        val json = CanonicalGeometryCodec.encode(trackDescriptor)
                        onPersistDescriptor?.invoke(track.copy(canonicalGeometryJson = json), recording, json)
                        return trackDescriptor
                    }
                    return decoded
                }
            }
        }

        // 3. Legacy recovery via storage-resolved video file
        val videoFile = fileResolver(recording.filePath)
        if (videoFile != null && videoFile.isFile && videoFile.length() > 0) {
            val recoveredTrackDesc = recoverFromVideoFile(
                videoFile = videoFile,
                recording = recording,
                landmarkTrackId = track?.landmarkTrackId,
            )
            if (recoveredTrackDesc != null && recoveredTrackDesc.isAvailable) {
                val trackJson = CanonicalGeometryCodec.encode(recoveredTrackDesc)
                val updatedTrack = track?.copy(canonicalGeometryJson = trackJson)

                // Persist recording-level descriptor (landmarkTrackId = null) for the recording
                val recordingDesc = if (track == null || recoveredTrackDesc.landmarkTrackId == null) {
                    recoveredTrackDesc
                } else {
                    recoverFromVideoFile(
                        videoFile = videoFile,
                        recording = recording,
                        landmarkTrackId = null,
                    ) ?: recoveredTrackDesc
                }
                val recJson = CanonicalGeometryCodec.encode(recordingDesc)
                val updatedRecording = recording.copy(
                    rotation = recordingDesc.sourceToCanonicalTransform.rotationDegrees,
                    canonicalGeometryJson = recJson,
                )
                onPersistDescriptor?.invoke(updatedTrack, updatedRecording, trackJson)
                return recoveredTrackDesc
            }
        }

        // 4. Recovery impossible: return UNKNOWN_GEOMETRY
        // NEVER invent 0° rotation or 1080x1920 defaults!
        return CanonicalGeometryDescriptor.UNKNOWN
    }

    /**
     * Recovers geometry from a valid video file using MediaMetadataRetriever and the
     * MediaPipe/retriever decoded frame contract (decoded bitmaps are already rotation-corrected).
     */
    fun recoverFromVideoFile(
        videoFile: File,
        recording: MasterRecording,
        landmarkTrackId: String?,
        isMirrored: Boolean = false,
    ): CanonicalGeometryDescriptor? {
        val retriever = MediaMetadataRetriever()
        try {
            retriever.setDataSource(videoFile.absolutePath)

            val rotationStr = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_ROTATION)
            val containerRotation = rotationStr?.toIntOrNull() ?: 0

            val encWStr = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_WIDTH)
            val encHStr = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_HEIGHT)
            val encW = encWStr?.toIntOrNull() ?: recording.width
            val encH = encHStr?.toIntOrNull() ?: recording.height

            if (encW == null || encH == null || encW <= 0 || encH <= 0) {
                return null
            }

            // MediaPipe / MediaMetadataRetriever contract:
            // retriever.getFrameAtTime outputs rotation-corrected bitmaps.
            // If containerRotation is 90 or 270, canonical decoded dimensions are swapped:
            val (canonicalWidth, canonicalHeight) = if (containerRotation == 90 || containerRotation == 270) {
                Pair(encH, encW)
            } else {
                Pair(encW, encH)
            }

            val transform = SourceToCanonicalTransform(
                rotationDegrees = containerRotation,
                isMirrored = isMirrored,
                order = TransformOrder.ROTATION_THEN_MIRROR,
            )

            return CanonicalGeometryDescriptor(
                geometryId = "geom_recovered_${recording.recordingId}_${landmarkTrackId ?: "rec"}",
                contractVersion = CanonicalGeometryDescriptor.CONTRACT_VERSION,
                recordingId = recording.recordingId,
                landmarkTrackId = landmarkTrackId,
                canonicalWidth = canonicalWidth,
                canonicalHeight = canonicalHeight,
                canonicalOrientation = CanonicalOrientation.UPRIGHT_UNMIRRORED,
                sourceToCanonicalTransform = transform,
                adapterVersion = "legacy_recovery_v1",
                encodedWidth = encW,
                encodedHeight = encH,
                containerRotation = containerRotation,
            )
        } catch (_: Exception) {
            return null
        } finally {
            runCatching { retriever.release() }
        }
    }
}
