package dk.lasse.karatecliprecorder.training

import android.media.MediaMetadataRetriever
import dk.lasse.karateanalyzer.geometry.CanonicalGeometryCodec
import dk.lasse.karateanalyzer.geometry.CanonicalGeometryDescriptor
import dk.lasse.karateanalyzer.geometry.CanonicalOrientation
import dk.lasse.karateanalyzer.geometry.SourceToCanonicalTransform
import dk.lasse.karateanalyzer.geometry.TransformOrder
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowMediaMetadataRetriever
import java.io.File
import kotlin.test.*

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28])
class CanonicalGeometryStorageAdapterTest {

    private lateinit var tempDir: File
    private lateinit var videoFile: File

    @Before
    fun setup() {
        tempDir = kotlin.io.path.createTempDirectory("canon_geom_test").toFile()
        videoFile = File(tempDir, "sample_recording.mp4").apply {
            writeBytes(ByteArray(256))
        }
    }

    @After
    fun tearDown() {
        ShadowMediaMetadataRetriever.reset()
        tempDir.deleteRecursively()
    }

    @Test
    fun strictTrackLevelSelectionReturnsMatchingTrackDescriptorDirectly() {
        val recording = MasterRecording(
            recordingId = "rec_100",
            sessionId = "session_100",
            filePath = "recordings/video.mp4",
            createdAtMs = 1000L,
        )
        val trackDescriptor = CanonicalGeometryDescriptor(
            geometryId = "geom_track_1",
            recordingId = "rec_100",
            landmarkTrackId = "track_1",
            canonicalWidth = 1080,
            canonicalHeight = 1920,
            canonicalOrientation = CanonicalOrientation.UPRIGHT_UNMIRRORED,
            sourceToCanonicalTransform = SourceToCanonicalTransform(90, false, order = TransformOrder.ROTATION_THEN_MIRROR),
            adapterVersion = "v1",
            encodedWidth = 1920,
            encodedHeight = 1080,
            containerRotation = 90,
        )
        val track = LandmarkTrack(
            landmarkTrackId = "track_1",
            recordingId = "rec_100",
            pipelineKey = "pipe",
            pipelineVersion = "1",
            configuration = "cfg",
            filePath = "tracks/1.mls",
            canonicalGeometryJson = CanonicalGeometryCodec.encode(trackDescriptor),
        )

        var persistCalled = false
        val resolved = CanonicalGeometryStorageAdapter.resolveTrackGeometry(
            recording = recording,
            track = track,
            fileResolver = { null },
            onPersistDescriptor = { _, _, _ -> persistCalled = true },
        )

        assertEquals("geom_track_1", resolved.geometryId)
        assertEquals(1080, resolved.canonicalWidth)
        assertEquals(1920, resolved.canonicalHeight)
        assertEquals(90, resolved.sourceToCanonicalTransform.rotationDegrees)
        assertFalse(persistCalled, "Should not persist when already present on track")
    }

    @Test
    fun trackDescriptorWithMismatchedRecordingOrTrackIdentityIsRejected() {
        val recording = MasterRecording(
            recordingId = "rec_correct",
            sessionId = "session_correct",
            filePath = "recordings/video.mp4",
            createdAtMs = 1000L,
        )
        // Mismatched recordingId in descriptor JSON
        val badDescriptor = CanonicalGeometryDescriptor(
            geometryId = "geom_wrong",
            recordingId = "rec_WRONG",
            landmarkTrackId = "track_1",
            canonicalWidth = 1080,
            canonicalHeight = 1920,
        )
        val track = LandmarkTrack(
            landmarkTrackId = "track_1",
            recordingId = "rec_correct",
            pipelineKey = "pipe",
            pipelineVersion = "1",
            configuration = "cfg",
            filePath = "tracks/1.mls",
            canonicalGeometryJson = CanonicalGeometryCodec.encode(badDescriptor),
        )

        // Without video file, rejection leads to UNKNOWN
        val resolved = CanonicalGeometryStorageAdapter.resolveTrackGeometry(
            recording = recording,
            track = track,
            fileResolver = { null },
        )
        assertFalse(resolved.isAvailable)
        assertSame(CanonicalGeometryDescriptor.UNKNOWN, resolved)
    }

    @Test
    fun reprocessingDoesNotOverwriteHistoricalTrackGeometry() {
        val recording = MasterRecording(
            recordingId = "rec_shared",
            sessionId = "session_shared",
            filePath = "recordings/video.mp4",
            createdAtMs = 1000L,
        )
        val historicalDescriptor = CanonicalGeometryDescriptor(
            geometryId = "geom_hist",
            recordingId = "rec_shared",
            landmarkTrackId = "track_historical",
            canonicalWidth = 1080,
            canonicalHeight = 1920,
            sourceToCanonicalTransform = SourceToCanonicalTransform(90, false, order = TransformOrder.ROTATION_THEN_MIRROR),
        )
        val historicalTrack = LandmarkTrack(
            landmarkTrackId = "track_historical",
            recordingId = "rec_shared",
            pipelineKey = "pipe",
            pipelineVersion = "1",
            configuration = "cfg",
            filePath = "tracks/hist.mls",
            canonicalGeometryJson = CanonicalGeometryCodec.encode(historicalDescriptor),
        )

        val newTrackDescriptor = CanonicalGeometryDescriptor(
            geometryId = "geom_new",
            recordingId = "rec_shared",
            landmarkTrackId = "track_reprocessed",
            canonicalWidth = 1080,
            canonicalHeight = 1920,
            sourceToCanonicalTransform = SourceToCanonicalTransform(270, false, order = TransformOrder.ROTATION_THEN_MIRROR),
        )
        val reprocessedTrack = LandmarkTrack(
            landmarkTrackId = "track_reprocessed",
            recordingId = "rec_shared",
            pipelineKey = "pipe",
            pipelineVersion = "2",
            configuration = "cfg_v2",
            filePath = "tracks/new.mls",
            canonicalGeometryJson = CanonicalGeometryCodec.encode(newTrackDescriptor),
        )

        val resolvedHistorical = CanonicalGeometryStorageAdapter.resolveTrackGeometry(
            recording = recording,
            track = historicalTrack,
            fileResolver = { null },
        )
        val resolvedReprocessed = CanonicalGeometryStorageAdapter.resolveTrackGeometry(
            recording = recording,
            track = reprocessedTrack,
            fileResolver = { null },
        )

        assertEquals("geom_hist", resolvedHistorical.geometryId)
        assertEquals(90, resolvedHistorical.sourceToCanonicalTransform.rotationDegrees)

        assertEquals("geom_new", resolvedReprocessed.geometryId)
        assertEquals(270, resolvedReprocessed.sourceToCanonicalTransform.rotationDegrees)
    }

    @Test
    fun trackLackingDescriptorAdaptsRecordingDescriptorAndPersists() {
        val recDescriptor = CanonicalGeometryDescriptor(
            geometryId = "geom_rec",
            recordingId = "rec_200",
            canonicalWidth = 720,
            canonicalHeight = 1280,
            sourceToCanonicalTransform = SourceToCanonicalTransform(90, false, order = TransformOrder.ROTATION_THEN_MIRROR),
        )
        val recording = MasterRecording(
            recordingId = "rec_200",
            sessionId = "session_200",
            filePath = "recordings/video.mp4",
            createdAtMs = 1000L,
            canonicalGeometryJson = CanonicalGeometryCodec.encode(recDescriptor),
        )
        val track = LandmarkTrack(
            landmarkTrackId = "track_needs_geom",
            recordingId = "rec_200",
            pipelineKey = "pipe",
            pipelineVersion = "1",
            configuration = "cfg",
            filePath = "tracks/200.mls",
            canonicalGeometryJson = null,
        )

        var persistedJson: String? = null
        var persistedTrack: LandmarkTrack? = null
        val resolved = CanonicalGeometryStorageAdapter.resolveTrackGeometry(
            recording = recording,
            track = track,
            fileResolver = { null },
            onPersistDescriptor = { t, _, json ->
                persistedTrack = t
                persistedJson = json
            },
        )

        assertEquals(720, resolved.canonicalWidth)
        assertEquals(1280, resolved.canonicalHeight)
        assertEquals("track_needs_geom", resolved.landmarkTrackId)
        assertNotNull(persistedJson)
        assertEquals("track_needs_geom", persistedTrack?.landmarkTrackId)
    }

    @Test
    fun legacyRecoveryWithRelativeRecordingReferenceAndRotations() {
        val testCases = listOf(
            Triple(90, Pair(1920, 1080), Pair(1080, 1920)),
            Triple(270, Pair(1920, 1080), Pair(1080, 1920)),
            Triple(0, Pair(1920, 1080), Pair(1920, 1080)),
            Triple(180, Pair(1920, 1080), Pair(1920, 1080)),
        )

        for ((rotation, encodedDim, expectedCanonical) in testCases) {
            ShadowMediaMetadataRetriever.reset()
            val relativePath = "subfolder/rel_video_$rotation.mp4"
            val absFile = File(tempDir, relativePath).apply {
                parentFile?.mkdirs()
                writeBytes(ByteArray(128))
            }

            ShadowMediaMetadataRetriever.addMetadata(
                absFile.absolutePath,
                MediaMetadataRetriever.METADATA_KEY_VIDEO_ROTATION,
                rotation.toString(),
            )
            ShadowMediaMetadataRetriever.addMetadata(
                absFile.absolutePath,
                MediaMetadataRetriever.METADATA_KEY_VIDEO_WIDTH,
                encodedDim.first.toString(),
            )
            ShadowMediaMetadataRetriever.addMetadata(
                absFile.absolutePath,
                MediaMetadataRetriever.METADATA_KEY_VIDEO_HEIGHT,
                encodedDim.second.toString(),
            )

            val recording = MasterRecording(
                recordingId = "rec_$rotation",
                sessionId = "sess_$rotation",
                filePath = relativePath,
                createdAtMs = 1000L,
            )
            val track = LandmarkTrack(
                landmarkTrackId = "trk_$rotation",
                recordingId = "rec_$rotation",
                pipelineKey = "p",
                pipelineVersion = "1",
                configuration = "c",
                filePath = "f.mls",
            )

            val resolved = CanonicalGeometryStorageAdapter.resolveTrackGeometry(
                recording = recording,
                track = track,
                fileResolver = { path -> File(tempDir, path) },
            )

            assertTrue(resolved.isAvailable, "Rotation $rotation should be available")
            assertEquals(expectedCanonical.first, resolved.canonicalWidth, "Width mismatch for $rotation deg")
            assertEquals(expectedCanonical.second, resolved.canonicalHeight, "Height mismatch for $rotation deg")
            assertEquals(rotation, resolved.sourceToCanonicalTransform.rotationDegrees)
            assertEquals(TransformOrder.ROTATION_THEN_MIRROR, resolved.sourceToCanonicalTransform.order)
            assertFalse(resolved.sourceToCanonicalTransform.isMirrored)
        }
    }

    @Test
    fun legacyRecoverySupportsMirroring() {
        ShadowMediaMetadataRetriever.reset()
        ShadowMediaMetadataRetriever.addMetadata(
            videoFile.absolutePath,
            MediaMetadataRetriever.METADATA_KEY_VIDEO_ROTATION,
            "0",
        )
        ShadowMediaMetadataRetriever.addMetadata(
            videoFile.absolutePath,
            MediaMetadataRetriever.METADATA_KEY_VIDEO_WIDTH,
            "1280",
        )
        ShadowMediaMetadataRetriever.addMetadata(
            videoFile.absolutePath,
            MediaMetadataRetriever.METADATA_KEY_VIDEO_HEIGHT,
            "720",
        )

        val recording = MasterRecording(
            recordingId = "rec_mirror",
            sessionId = "sess_mirror",
            filePath = videoFile.name,
            createdAtMs = 1000L,
        )

        val descriptor = CanonicalGeometryStorageAdapter.recoverFromVideoFile(
            videoFile = videoFile,
            recording = recording,
            landmarkTrackId = "track_m",
            isMirrored = true,
        )

        assertNotNull(descriptor)
        assertTrue(descriptor.isAvailable)
        assertTrue(descriptor.sourceToCanonicalTransform.isMirrored)
        assertEquals(TransformOrder.ROTATION_THEN_MIRROR, descriptor.sourceToCanonicalTransform.order)
        assertEquals(0, descriptor.sourceToCanonicalTransform.rotationDegrees)
        assertEquals(1280, descriptor.canonicalWidth)
        assertEquals(720, descriptor.canonicalHeight)
    }

    @Test
    fun saveAndReloadWithVideoFileRemoval() {
        ShadowMediaMetadataRetriever.reset()
        ShadowMediaMetadataRetriever.addMetadata(
            videoFile.absolutePath,
            MediaMetadataRetriever.METADATA_KEY_VIDEO_ROTATION,
            "90",
        )
        ShadowMediaMetadataRetriever.addMetadata(
            videoFile.absolutePath,
            MediaMetadataRetriever.METADATA_KEY_VIDEO_WIDTH,
            "1920",
        )
        ShadowMediaMetadataRetriever.addMetadata(
            videoFile.absolutePath,
            MediaMetadataRetriever.METADATA_KEY_VIDEO_HEIGHT,
            "1080",
        )

        var recording = MasterRecording(
            recordingId = "rec_persist",
            sessionId = "sess_persist",
            filePath = videoFile.absolutePath,
            createdAtMs = 1000L,
        )
        var track = LandmarkTrack(
            landmarkTrackId = "trk_persist",
            recordingId = "rec_persist",
            pipelineKey = "p",
            pipelineVersion = "1",
            configuration = "c",
            filePath = "f.mls",
        )

        // Initial recovery from file and persistence
        val initialResolved = CanonicalGeometryStorageAdapter.resolveTrackGeometry(
            recording = recording,
            track = track,
            fileResolver = { File(it) },
            onPersistDescriptor = { updatedTrack, updatedRecording, json ->
                if (updatedTrack != null) track = updatedTrack
                recording = updatedRecording
            },
        )
        assertTrue(initialResolved.isAvailable)
        assertEquals(1080, initialResolved.canonicalWidth)
        assertEquals(1920, initialResolved.canonicalHeight)
        assertNotNull(track.canonicalGeometryJson)
        assertNotNull(recording.canonicalGeometryJson)

        // Delete underlying video file
        assertTrue(videoFile.delete(), "Video file should be deleted")
        assertFalse(videoFile.exists())

        // Reload track geometry with file removed
        val reloaded = CanonicalGeometryStorageAdapter.resolveTrackGeometry(
            recording = recording,
            track = track,
            fileResolver = { File(it) },
        )

        assertTrue(reloaded.isAvailable, "Should still resolve from persisted descriptor even after video file removal")
        assertEquals(1080, reloaded.canonicalWidth)
        assertEquals(1920, reloaded.canonicalHeight)
        assertEquals(90, reloaded.sourceToCanonicalTransform.rotationDegrees)
    }

    @Test
    fun recoveryImpossibleReturnsUnknownGeometryWithoutGuessing() {
        val recording = MasterRecording(
            recordingId = "rec_ghost",
            sessionId = "sess_ghost",
            filePath = "non_existent/video.mp4",
            createdAtMs = 1000L,
            width = 1920,
            height = 1080,
            rotation = 90,
            canonicalGeometryJson = null,
        )
        val track = LandmarkTrack(
            landmarkTrackId = "trk_ghost",
            recordingId = "rec_ghost",
            pipelineKey = "p",
            pipelineVersion = "1",
            configuration = "c",
            filePath = "f.mls",
            canonicalGeometryJson = null,
        )

        val resolved = CanonicalGeometryStorageAdapter.resolveTrackGeometry(
            recording = recording,
            track = track,
            fileResolver = { File(tempDir, it) },
        )

        assertFalse(resolved.isAvailable)
        assertSame(CanonicalGeometryDescriptor.UNKNOWN, resolved)
        assertEquals(0, resolved.canonicalWidth)
        assertEquals(0, resolved.canonicalHeight)
    }

    @Test
    fun anotherTrackDescriptorOnRecordingIsRejectedAndNeverRelabelled() {
        // Recording has a descriptor belonging to Track B
        val trackBDescriptor = CanonicalGeometryDescriptor(
            geometryId = "geom_track_B",
            recordingId = "rec_shared",
            landmarkTrackId = "track_B",
            canonicalWidth = 1080,
            canonicalHeight = 1920,
            sourceToCanonicalTransform = SourceToCanonicalTransform(90, false, order = TransformOrder.ROTATION_THEN_MIRROR),
        )
        val recording = MasterRecording(
            recordingId = "rec_shared",
            sessionId = "session_shared",
            filePath = "recordings/video.mp4",
            createdAtMs = 1000L,
            canonicalGeometryJson = CanonicalGeometryCodec.encode(trackBDescriptor),
        )

        // Requesting geometry for Track A
        val trackA = LandmarkTrack(
            landmarkTrackId = "track_A",
            recordingId = "rec_shared",
            pipelineKey = "pipe",
            pipelineVersion = "1",
            configuration = "cfg",
            filePath = "tracks/A.mls",
            canonicalGeometryJson = null,
        )

        var persisted = false
        val resolved = CanonicalGeometryStorageAdapter.resolveTrackGeometry(
            recording = recording,
            track = trackA,
            fileResolver = { null }, // no video file
            onPersistDescriptor = { _, _, _ -> persisted = true },
        )

        // Must reject Track B's descriptor rather than relabelling it as Track A!
        assertSame(CanonicalGeometryDescriptor.UNKNOWN, resolved)
        assertFalse(persisted, "Should not persist Track B descriptor for Track A")
    }

    @Test
    fun recordingLevelDescriptorIsAdaptedWithCleanGeometryIdForTrack() {
        val recDescriptor = CanonicalGeometryDescriptor(
            geometryId = "geom_recording_level",
            recordingId = "rec_clean",
            landmarkTrackId = null, // Recording-level descriptor
            canonicalWidth = 720,
            canonicalHeight = 1280,
            sourceToCanonicalTransform = SourceToCanonicalTransform(90, false, order = TransformOrder.ROTATION_THEN_MIRROR),
        )
        val recording = MasterRecording(
            recordingId = "rec_clean",
            sessionId = "sess_clean",
            filePath = "recordings/video.mp4",
            createdAtMs = 1000L,
            canonicalGeometryJson = CanonicalGeometryCodec.encode(recDescriptor),
        )
        val trackA = LandmarkTrack(
            landmarkTrackId = "track_alpha",
            recordingId = "rec_clean",
            pipelineKey = "pipe",
            pipelineVersion = "1",
            configuration = "cfg",
            filePath = "tracks/alpha.mls",
            canonicalGeometryJson = null,
        )

        val resolved = CanonicalGeometryStorageAdapter.resolveTrackGeometry(
            recording = recording,
            track = trackA,
            fileResolver = { null },
        )

        assertTrue(resolved.isAvailable)
        assertEquals("track_alpha", resolved.landmarkTrackId)
        assertEquals("geom_rec_clean_track_alpha", resolved.geometryId)
        assertEquals(720, resolved.canonicalWidth)
        assertEquals(1280, resolved.canonicalHeight)
    }
}
