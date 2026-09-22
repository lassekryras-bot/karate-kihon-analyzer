package dk.lasse.karateanalyzer.geometry

import org.junit.Assert.*
import org.junit.Test

class CanonicalGeometryTest {

    @Test
    fun descriptorValidationAndAvailability() {
        val valid = CanonicalGeometryDescriptor(
            geometryId = "geom_123",
            contractVersion = CanonicalGeometryDescriptor.CONTRACT_VERSION,
            recordingId = "rec_456",
            landmarkTrackId = "track_789",
            canonicalWidth = 1080,
            canonicalHeight = 1920,
            canonicalOrientation = CanonicalOrientation.UPRIGHT_UNMIRRORED,
            sourceToCanonicalTransform = SourceToCanonicalTransform(
                rotationDegrees = 90,
                isMirrored = false,
                scaleX = 1f,
                scaleY = 1f,
                order = TransformOrder.ROTATION_THEN_MIRROR,
            ),
            adapterVersion = "android_decoder_v1",
            encodedWidth = 1920,
            encodedHeight = 1080,
            containerRotation = 90,
        )

        assertTrue(valid.isAvailable)
        val fg = valid.toFrameGeometry()
        assertEquals(1080, fg.sourceWidth)
        assertEquals(1920, fg.sourceHeight)
        assertEquals(CanonicalOrientation.UPRIGHT_UNMIRRORED, fg.canonicalOrientation)

        val unknown = CanonicalGeometryDescriptor.UNKNOWN
        assertFalse(unknown.isAvailable)
        assertEquals(CanonicalGeometryDescriptor.UNKNOWN_GEOMETRY_ID, unknown.geometryId)
        assertThrows(IllegalStateException::class.java) {
            unknown.toFrameGeometry()
        }
    }

    @Test
    fun transformOrderAndRotationValidation() {
        val t = SourceToCanonicalTransform(
            rotationDegrees = 270,
            isMirrored = true,
            order = TransformOrder.ROTATION_THEN_MIRROR,
        )
        assertEquals(270, t.rotationDegrees)
        assertTrue(t.isMirrored)
        assertEquals(TransformOrder.ROTATION_THEN_MIRROR, t.order)

        assertThrows(IllegalArgumentException::class.java) {
            SourceToCanonicalTransform(rotationDegrees = 45)
        }
    }

    @Test
    fun codecRoundTripPreservesAllFields() {
        val original = CanonicalGeometryDescriptor(
            geometryId = "geom_test_roundtrip",
            contractVersion = "canonical-geometry-v1",
            recordingId = "rec_test_1",
            landmarkTrackId = "track_test_2",
            canonicalWidth = 1080,
            canonicalHeight = 1920,
            canonicalOrientation = CanonicalOrientation.UPRIGHT_UNMIRRORED,
            sourceToCanonicalTransform = SourceToCanonicalTransform(
                rotationDegrees = 90,
                isMirrored = true,
                scaleX = 1.0f,
                scaleY = 1.0f,
                order = TransformOrder.ROTATION_THEN_MIRROR,
            ),
            adapterVersion = "unit_test_adapter_v1",
            sourceHash = "sha256_video_mock",
            trackHash = "sha256_track_mock",
            encodedWidth = 1920,
            encodedHeight = 1080,
            containerRotation = 90,
        )

        val json = CanonicalGeometryCodec.encode(original)
        assertNotNull(json)
        assertTrue(json.contains("\"geometryId\":\"geom_test_roundtrip\""))
        assertTrue(json.contains("\"rotationDegrees\":90"))
        assertTrue(json.contains("\"isMirrored\":true"))
        assertTrue(json.contains("\"order\":\"ROTATION_THEN_MIRROR\""))

        val decoded = CanonicalGeometryCodec.decode(json)
        assertNotNull(decoded)
        assertEquals(original, decoded)
    }

    @Test
    fun unknownGeometryCodecRoundTrip() {
        val json = CanonicalGeometryCodec.encode(CanonicalGeometryDescriptor.UNKNOWN)
        val decoded = CanonicalGeometryCodec.decode(json)
        assertNotNull(decoded)
        assertFalse(decoded!!.isAvailable)
        assertEquals(CanonicalGeometryDescriptor.UNKNOWN_GEOMETRY_ID, decoded.geometryId)
    }
}

