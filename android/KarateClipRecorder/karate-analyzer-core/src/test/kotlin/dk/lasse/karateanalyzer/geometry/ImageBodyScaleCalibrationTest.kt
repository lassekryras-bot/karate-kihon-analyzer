package dk.lasse.karateanalyzer.geometry

import kotlin.test.*

class ImageBodyScaleCalibrationTest {
    private val geometry = CanonicalGeometryDescriptor("g", recordingId = "r", landmarkTrackId = "t", canonicalWidth = 1920, canonicalHeight = 1080)
    private val calibration = ImageBodyScaleCalibration("c", geometry, 100_000, 3, SourceNormalizedPoint(.4f, .1f),
        SourceNormalizedPoint(.6f, .9f), 0, 5_000_000, true)

    @Test fun `height is fixed vertical image height independent of horizontal offset or physical stature`() {
        val scale = assertNotNull(ImageBodyScaleProvider.evidence(calibration, geometry, 1_000_000, 2_000_000))
        assertEquals(.8, scale.bodyHeightAspectCorrect, 1e-6)
        assertEquals("c", scale.sourceId)
        assertEquals(scale, ImageBodyScaleProvider.evidence(calibration, geometry, 3_000_000, 4_000_000))
    }

    @Test fun `missing confirmation identity changes invalid points and intervals abstain`() {
        assertNull(ImageBodyScaleProvider.evidence(null, geometry, 0, 1_000))
        assertFailsWith<IllegalArgumentException> { SourceNormalizedPoint(.5f, Float.NaN) }
        listOf(calibration.copy(cameraAndPositionConfirmedUnchanged = false),
            calibration.copy(geometry = geometry.copy(landmarkTrackId = "new")),
            calibration.copy(geometry = geometry.copy(recordingId = "other")),
            calibration.copy(geometry = geometry.copy(canonicalWidth = 1080)),
            calibration.copy(floorAtFeet = SourceNormalizedPoint(.5f, .05f)),
            calibration.copy(frameTimestampUs = 6_000_000), calibration.copy(frameIndex = -1),
            calibration.copy(validUntilUs = 500), calibration.copy(methodVersion = "unknown")
        ).forEach { assertNull(ImageBodyScaleProvider.evidence(it, geometry, 0, 1_000)) }
    }

    @Test fun `portrait calibration taps survive FIT letterboxing round trip`() {
        val frame = FrameGeometry(1080, 1920)
        val transform = OverlayCoordinateTransformer.resolveDisplayTransform(1000f, 600f, ContentScaleMode.FIT, frameGeometry = frame)
        val point = SourceNormalizedPoint(.4f, .1f)
        val restored = OverlayCoordinateTransformer.canvasToSource(OverlayCoordinateTransformer.sourceToCanvas(point, transform), transform)
        assertEquals(point.x, restored.x, 1e-6f)
        assertEquals(point.y, restored.y, 1e-6f)
    }
}
