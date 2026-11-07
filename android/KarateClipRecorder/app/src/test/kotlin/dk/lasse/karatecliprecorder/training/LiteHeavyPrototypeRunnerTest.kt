package dk.lasse.karatecliprecorder.training

import dk.lasse.karateanalyzer.capture.qom.QomEndBoundaryKind
import dk.lasse.karateanalyzer.capture.qom.QomDetectorConfig
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
            assertEquals("test-build", manifest.getString("appBuildIdentity"))
            assertEquals("all-or-nothing-no-resume-v1", manifest.getString("publicationSemantics"))
            assertEquals(64, manifest.getString("sourceContentSha256").length)
            assertEquals(1_500, manifest.getJSONObject("segmenterConfig").getJSONObject("qom")
                .getLong("maximumFrameGapMs"))
            assertEquals("pose_lite", manifest.getJSONObject("liteTrack").getString("modelKey"))
            assertEquals("lite-prototype-1", manifest.getJSONObject("liteTrack")
                .getJSONObject("geometryBinding").getString("landmarkTrackId"))
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
    fun `nonempty partial Lite extraction creates unknown windows for leading interior and trailing gaps`() {
        val root = createTempDirectory("lite-heavy-coverage-gaps").toFile()
        try {
            val video = File(root, "recording.mp4").apply { writeBytes(byteArrayOf(1)) }
            val result = runner(
                root = File(root, "artifacts"),
                liteFrames = frames(600, 700, 1_200, 1_300, 2_500, 2_600, 3_000, 3_400),
                heavyFrames = frames(0, 500, 1_000, 1_500, 2_000, 2_500, 3_000, 3_500, 4_000),
                segmenterConfig = RetrospectiveSegmenterConfig(
                    qomConfig = QomDetectorConfig(maximumFrameGapMs = 500),
                ),
                liteMovementIntervals = listOf(1_200L to 1_400L),
            ).run("recording-1", video, 4_000)

            val unknown = result.plan.windows.filter {
                it.candidate.kind == PrototypeCandidateKind.UNKNOWN_EVIDENCE
            }.map { it.candidate.startTimestampMs to it.candidate.endTimestampMs }
            assertEquals(listOf(0L to 600L, 1_300L to 2_500L, 3_400L to 4_000L), unknown)
            assertEquals(4, result.plan.windows.size)
        } finally {
            root.deleteRecursively()
        }
    }

    @Test
    fun `publication cancellation removes every staged prototype artifact`() {
        val root = createTempDirectory("lite-heavy-cancel").toFile()
        try {
            val video = File(root, "recording.mp4").apply { writeBytes(byteArrayOf(1)) }
            val productionSentinel = File(root, "production.db").apply { writeText("unchanged") }
            var publications = 0
            assertFailsWith<IllegalStateException> {
                runner(
                    root = File(root, "artifacts"),
                    liteFrames = frames(0, 1_000, 2_000, 3_000),
                    heavyFrames = frames(0, 1_000, 2_000, 3_000),
                    publication = { operation ->
                        publications += 1
                        if (publications == 5) error("cancelled before final publication")
                        operation()
                    },
                ).run("recording-1", video, 3_000)
            }
            assertEquals("unchanged", productionSentinel.readText())
            assertTrue(File(root, "artifacts").walkTopDown().none {
                it.name == "manifest.json" || it.extension == "mls" || it.name.endsWith(".pending")
            })
        } finally {
            root.deleteRecursively()
        }
    }

    @Test
    fun `second Heavy window failure removes first successful Heavy window and leaves production untouched`() {
        val root = createTempDirectory("lite-heavy-heavy-failure").toFile()
        try {
            val video = File(root, "recording.mp4").apply { writeBytes(byteArrayOf(1)) }
            val productionSentinel = File(root, "production.db").apply { writeText("unchanged") }
            assertFailsWith<IllegalStateException> {
                runner(
                    root = File(root, "artifacts"),
                    liteFrames = frames(0, 1_000, 2_000, 3_000, 4_000),
                    heavyFrames = frames(0, 500, 1_000, 1_500, 2_000, 2_500, 3_000, 3_500, 4_000),
                    liteMovementIntervals = listOf(500L to 700L, 2_500L to 2_700L),
                    failHeavyCall = 2,
                ).run("recording-1", video, 4_000)
            }
            assertEquals("unchanged", productionSentinel.readText())
            assertTrue(File(root, "artifacts").walkTopDown().none {
                it.name == "manifest.json" || it.extension == "mls" || it.name.endsWith(".pending")
            })
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
        segmenterConfig: RetrospectiveSegmenterConfig = RetrospectiveSegmenterConfig(
            qomConfig = QomDetectorConfig(maximumFrameGapMs = 1_500),
        ),
        liteMovementIntervals: List<Pair<Long, Long>> = listOf(1_000L to 2_000L),
        failHeavyCall: Int? = null,
        publication: (() -> Unit) -> Unit = { it() },
    ): LiteHeavyPrototypeRunner {
        var heavyCalls = 0
        return LiteHeavyPrototypeRunner(
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
            ): VideoPoseWindowResult {
                heavyCalls += 1
                if (heavyCalls == failHeavyCall) error("Heavy window failure")
                return VideoPoseWindowResult(
                    window,
                    heavyFrames.filter { it.timestampMs in window.startTimestampMs..window.endTimestampMs },
                )
            }
        },
        liteModel = PrototypeModelAssetIdentity("pose_lite", "lite-model-sha", "lite-config"),
        heavyModel = PrototypeModelAssetIdentity("pose_heavy", "heavy-model-sha", "heavy-config"),
        segmenterConfig = segmenterConfig,
        canonicalGeometry = CanonicalGeometryDescriptor(
            geometryId = "geometry-recording-1",
            recordingId = "recording-1",
            canonicalWidth = 1_080,
            canonicalHeight = 1_920,
        ),
        appBuildIdentity = "test-build",
        publication = publication,
        elapsedRealtimeMs = elapsed,
        idFactory = { "prototype-1" },
        segment = { sequenceId, path, frames ->
            val movements = if (sequenceId == "recording-1") liteMovementIntervals.mapIndexed { index, interval ->
                movement(path, interval.first, interval.second, index + 1)
            } else emptyList()
            result(sequenceId, path, frames, movements)
        },
    )
    }

    private fun movement(path: String, start: Long, end: Long, number: Int = 1) =
        dk.lasse.karateanalyzer.capture.retrospective.SessionMovement(
        movementNumber = number,
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
