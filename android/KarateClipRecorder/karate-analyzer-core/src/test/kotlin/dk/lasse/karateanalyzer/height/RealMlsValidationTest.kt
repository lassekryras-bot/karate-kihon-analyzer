package dk.lasse.karateanalyzer.height

import dk.lasse.karateanalyzer.capture.PoseReplayJson
import dk.lasse.karateanalyzer.core.PunchHeightTargetType
import dk.lasse.karateanalyzer.geometry.Alignment
import dk.lasse.karateanalyzer.geometry.CanvasPoint
import dk.lasse.karateanalyzer.geometry.ContentScaleMode
import dk.lasse.karateanalyzer.geometry.FrameGeometry
import dk.lasse.karateanalyzer.geometry.NormalizedCrop
import dk.lasse.karateanalyzer.geometry.OverlayCoordinateTransformer
import dk.lasse.karateanalyzer.geometry.ZoomPan
import org.junit.Assert.*
import org.junit.Test
import java.io.File
import kotlin.math.abs
import kotlin.math.sqrt

class RealMlsValidationTest {

    private fun loadFixture(): dk.lasse.karateanalyzer.capture.PoseReplayFixture {
        // 1. Prefer packaged classpath test resource (portable on clean checkouts and CI)
        val resourceStream = javaClass.classLoader?.getResourceAsStream("fixtures/real-kihon-sample.fixture.json")
            ?: Thread.currentThread().contextClassLoader?.getResourceAsStream("fixtures/real-kihon-sample.fixture.json")
        if (resourceStream != null) {
            val text = resourceStream.bufferedReader().use { it.readText() }
            return PoseReplayJson.decode(text)
        }

        // 2. Fallback to local full fixture if present in repo root
        var dir: File? = File(".").canonicalFile
        while (dir != null) {
            val file = File(dir, "output/task5/real-kihon-10-punch.fixture.json")
            if (file.exists()) {
                return PoseReplayJson.decode(file.readText())
            }
            dir = dir.parentFile
        }

        error("No fixture found: neither packaged resource 'fixtures/real-kihon-sample.fixture.json' nor local 'output/task5/real-kihon-10-punch.fixture.json' exists.")
    }

    @Test
    fun testRealMlsFixtureBodyHeightAndOverlayProjection() {
        val fixture = loadFixture()
        assertTrue("Should have at least 50 frames in real MLS fixture", fixture.frames.size >= 50)

        assertNotNull("Fixture must supply source_width", fixture.sourceWidth)
        assertNotNull("Fixture must supply source_height", fixture.sourceHeight)
        assertEquals(1056, fixture.sourceWidth)
        assertEquals(1354, fixture.sourceHeight)

        // 1056x1354 portrait recording geometry (from input/task5/1000002073.mp4)
        val frameGeometry = FrameGeometry(fixture.sourceWidth!!, fixture.sourceHeight!!)

        // Sample representative timestamps throughout the recording (2s, 5s, 8s, 11s)
        val testTimestampsMs = listOf(2000L, 5000L, 8000L, 11000L)

        for (tsMs in testTimestampsMs) {
            val tsUs = tsMs * 1000L
            val observed = BodyHeightModel.evaluate(
                selectedTimestampUs = tsUs,
                frames = fixture.frames,
                frameGeometry = frameGeometry,
                radius = 2,
            )

            // Torso evidence should be usable
            assertNotNull("Shoulder center should be observed at $tsMs ms", observed.shoulderCenter)
            assertNotNull("Hip center should be observed at $tsMs ms", observed.hipCenter)
            assertNotNull("Torso center should be observed at $tsMs ms", observed.torsoCenter)
            assertNotNull("Current body up should be observed at $tsMs ms", observed.currentBodyUp)
            assertNotNull("Current torso length should be observed at $tsMs ms", observed.currentTorsoLength)
            assertTrue("Torso evidence usable count should be >= 3", observed.torsoEvidence.usableCount >= 3)

            val torsoLen = observed.currentTorsoLength!!
            assertTrue("Observed torso length ($torsoLen) should be substantial (> 0.15)", torsoLen > 0.15f)

            // Current body up should be approximately unit length and pointing upward in image (dy < 0)
            val up = observed.currentBodyUp!!
            val upLen = sqrt(up.x * up.x + up.y * up.y)
            assertEquals("Body up vector should be unit length", 1.0f, upLen, 1e-3f)
            assertTrue("Body up should point upward in image space (y < 0)", up.y < 0f)

            // Chūdan target estimate
            val chudan = TargetHeightEstimator.estimateChudanTorsoRatio045(observed)
            assertEquals("Chūdan should be VALID at $tsMs ms", TargetEstimateStatus.VALID, chudan.status)
            assertNotNull("Chūdan point should be present", chudan.point)
            val cPt = chudan.point!!
            // Chūdan y should be between shoulder y and hip y
            val sY = observed.shoulderCenter!!.y
            val hY = observed.hipCenter!!.y
            assertTrue("Chūdan y ($cPt.y) should be between shoulder ($sY) and hip ($hY)", cPt.y in sY..hY)

            // Gedan target estimate
            val gedan = TargetHeightEstimator.estimateGedanTorsoRatio080(observed)
            assertEquals("Gedan should be VALID at $tsMs ms", TargetEstimateStatus.VALID, gedan.status)
            assertNotNull("Gedan point should be present", gedan.point)
            val gPt = gedan.point!!
            assertTrue("Gedan y ($gPt.y) should be lower (greater y) than Chūdan ($cPt.y)", gPt.y > cPt.y)

            if (tsMs == 2000L) {
                // Independently calculated geometric ground truth at ts = 2000 ms (aspect ratio 1056 / 1354 = 0.779911)
                assertEquals(0.388175f, observed.shoulderCenter!!.x, 1e-4f)
                assertEquals(0.255624f, observed.shoulderCenter!!.y, 1e-4f)
                assertEquals(0.423342f, observed.hipCenter!!.x, 1e-4f)
                assertEquals(0.536442f, observed.hipCenter!!.y, 1e-4f)
                assertEquals(0.405759f, observed.torsoCenter!!.x, 1e-4f)
                assertEquals(0.396033f, observed.torsoCenter!!.y, 1e-4f)
                assertEquals(0.282155f, observed.currentTorsoLength!!, 1e-4f)
                assertEquals(-0.097205f, observed.currentBodyUp!!.x, 1e-4f)
                assertEquals(-0.995264f, observed.currentBodyUp!!.y, 1e-4f)
                assertEquals(0.404000f, cPt.x, 1e-4f)
                assertEquals(0.381992f, cPt.y, 1e-4f)
                assertEquals(0.416309f, gPt.x, 1e-4f)
                assertEquals(0.480279f, gPt.y, 1e-4f)
            }

            // Overlay Coordinate Transformer projections
            // Test 1: Fit scaling on standard viewport
            val fitTransform = OverlayCoordinateTransformer.resolveDisplayTransform(
                viewportWidth = 800f,
                viewportHeight = 1200f,
                contentScale = ContentScaleMode.FIT,
                alignment = Alignment.CENTER,
                frameGeometry = frameGeometry,
            )
            val chudanCanvas = OverlayCoordinateTransformer.sourceToCanvas(cPt, fitTransform)
            assertTrue("Chūdan canvas X should be inside viewport", chudanCanvas.x in 0f..800f)
            assertTrue("Chūdan canvas Y should be inside viewport", chudanCanvas.y in 0f..1200f)

            val chudanRestored = OverlayCoordinateTransformer.canvasToSource(chudanCanvas, fitTransform)
            assertEquals("Source roundtrip X", cPt.x, chudanRestored.x, 1e-4f)
            assertEquals("Source roundtrip Y", cPt.y, chudanRestored.y, 1e-4f)

            // Test 2: Crop scaling with ZoomPan
            val cropTransform = OverlayCoordinateTransformer.resolveDisplayTransform(
                viewportWidth = 600f,
                viewportHeight = 600f,
                contentScale = ContentScaleMode.CROP,
                appliedCrop = NormalizedCrop(0.1f, 0.1f, 0.9f, 0.9f),
                zoomPan = ZoomPan(zoom = 1.25f, panX = 15f, panY = -10f),
                frameGeometry = frameGeometry,
            )
            val hipCanvas = OverlayCoordinateTransformer.sourceToCanvas(observed.hipCenter!!, cropTransform)
            val hipRestored = OverlayCoordinateTransformer.canvasToSource(hipCanvas, cropTransform)
            assertEquals("Crop/zoom roundtrip X", observed.hipCenter!!.x, hipRestored.x, 1e-4f)
            assertEquals("Crop/zoom roundtrip Y", observed.hipCenter!!.y, hipRestored.y, 1e-4f)
        }
    }
}
