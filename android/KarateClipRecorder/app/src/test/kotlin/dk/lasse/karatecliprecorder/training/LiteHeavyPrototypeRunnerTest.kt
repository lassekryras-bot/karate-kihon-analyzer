package dk.lasse.karatecliprecorder.training

import dk.lasse.karateanalyzer.capture.qom.QomEndBoundaryKind
import dk.lasse.karateanalyzer.capture.retrospective.*
import dk.lasse.karateanalyzer.core.PoseFrame
import dk.lasse.karateanalyzer.geometry.CanonicalGeometryDescriptor
import org.json.JSONObject
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.io.File
import kotlin.io.path.createTempDirectory
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

@RunWith(RobolectricTestRunner::class)
class LiteHeavyPrototypeRunnerTest {
    @Test
    fun `publishes separate Lite and Heavy MLS plus manifest without production storage`() {
        val root = createTempDirectory("lite-heavy-prototype").toFile()
        try {
            val video = File(root, "recording.mp4").apply { writeBytes(byteArrayOf(1)) }
            val liteFrames = frames(0, 1_000, 2_000, 3_000)
            val heavyFrames = frames(33, 1_000, 2_000, 2_967)
            val clock = listOf(0L, 10L, 110L, 120L, 220L, 230L).iterator()
            val result = runner(
                root = File(root, "artifacts"),
                liteFrames = liteFrames,
                heavyFrames = heavyFrames,
                elapsed = { clock.next() },
            ).run("recording-1", video, 3_000)

            assertTrue(result.artifactDirectory.isDirectory)
            assertTrue(File(result.artifactDirectory, "lite.mls").isFile)
            assertTrue(File(result.artifactDirectory, "heavy-window-1.mls").isFile)
            assertTrue(result.manifestFile.isFile)
            assertFalse(File(result.artifactDirectory.parentFile, "prototype-1.pending").exists())
            assertEquals(100, result.liteElapsedMs)
            assertEquals(100, result.heavyElapsedMs)
            assertEquals(230, result.totalElapsedMs)

            val manifest = JSONObject(result.manifestFile.readText())
            assertEquals(false, manifest.getBoolean("productionActivated"))
            assertEquals(false, manifest.getBoolean("calibrationApplied"))
            assertEquals("pose_lite", manifest.getJSONObject("liteTrack").getString("modelKey"))
            val heavyTrack = manifest.getJSONArray("windows").getJSONObject(0).getJSONObject("heavyTrack")
            assertEquals("pose_heavy", heavyTrack.getString("modelKey"))
            assertEquals(33, heavyTrack.getLong("actualStartTimestampMs"))
            assertEquals(2_967, heavyTrack.getLong("actualEndTimestampMs"))
            assertNotEquals(
                manifest.getJSONObject("liteTrack").getString("landmarkTrackId"),
                heavyTrack.getString("landmarkTrackId"),
            )
            assertTrue(
                manifest.getJSONArray("windows").getJSONObject(0).getJSONArray("uncertainty")
                    .toString().contains("ACTUAL_COVERAGE_NARROWER"),
            )
        } finally {
            root.deleteRecursively()
        }
    }

    @Test
    fun `empty Lite extraction publishes no manifest or MLS`() {
        val root = createTempDirectory("lite-heavy-empty").toFile()
        try {
            val video = File(root, "recording.mp4").apply { writeBytes(byteArrayOf(1)) }
            val artifacts = File(root, "artifacts")
            assertFailsWith<IllegalArgumentException> {
                runner(artifacts, emptyList(), emptyList()).run("recording-1", video, 3_000)
            }
            assertTrue(artifacts.walkTopDown().none { it.name == "manifest.json" || it.extension == "mls" })
        } finally {
            root.deleteRecursively()
        }
    }

    private fun runner(
        root: File,
        liteFrames: List<PoseFrame>,
        heavyFrames: List<PoseFrame>,
        elapsed: () -> Long = { 0L },
    ) = LiteHeavyPrototypeRunner(
        outputRoot = root,
        liteProcessor = object : VideoPoseProcessor {
            override fun processVideo(videoFile: File, onProgress: (Float, Long) -> Unit): List<PoseFrame> =
                liteFrames
        },
        heavyProcessor = object : WindowedVideoPoseProcessor {
            override fun processVideo(videoFile: File, onProgress: (Float, Long) -> Unit): List<PoseFrame> =
                heavyFrames

            override fun processWindow(
                videoFile: File,
                window: VideoTimeWindow,
                onProgress: (Float, Long) -> Unit,
            ) = VideoPoseWindowResult(window, heavyFrames)
        },
        liteModel = PrototypeModelAssetIdentity("pose_lite", "lite-model-sha", "lite-config"),
        heavyModel = PrototypeModelAssetIdentity("pose_heavy", "heavy-model-sha", "heavy-config"),
        segmenterConfig = RetrospectiveSegmenterConfig(),
        canonicalGeometry = CanonicalGeometryDescriptor(
            geometryId = "geometry-recording-1",
            recordingId = "recording-1",
            canonicalWidth = 1_080,
            canonicalHeight = 1_920,
        ),
        elapsedRealtimeMs = elapsed,
        idFactory = { "prototype-1" },
        segment = { sequenceId, path, frames ->
            val movements = if (sequenceId == "recording-1") {
                listOf(movement(path, 1_000, 2_000))
            } else {
                listOf(movement(path, 1_000, 2_000))
            }
            result(sequenceId, path, frames, movements)
        },
    )

    private fun movement(path: String, start: Long, end: Long) =
        dk.lasse.karateanalyzer.capture.retrospective.SessionMovement(
        movementNumber = 1,
        sourceRecordingPath = path,
        logicalStartTimestampMs = start,
        logicalEndTimestampMs = end,
        retainedStartTimestampMs = start,
        retainedEndTimestampMs = end,
        endBoundaryKind = QomEndBoundaryKind.STOP_GATE,
        )

    private fun result(
        sequenceId: String,
        path: String,
        frames: List<PoseFrame>,
        movements: List<dk.lasse.karateanalyzer.capture.retrospective.SessionMovement>,
    ) = RetrospectiveSessionResult(
        sequenceId = sequenceId,
        masterVideoPath = path,
        totalFrames = frames.size,
        totalDurationMs = frames.lastOrNull()?.timestampMs ?: 0,
        detectedMovementCount = movements.size,
        movements = movements,
        kinematicsTimeline = emptyList(),
        stats = RetrospectiveSignalStats(0.0, 0.0, 0.0, 0.0, 1.0),
    )

    private fun frames(vararg timestamps: Long) = timestamps.map { PoseFrame(it, emptyMap()) }
}
