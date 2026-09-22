package dk.lasse.karateanalyzer.geometry

import org.junit.Assert.*
import org.junit.Test

class LandmarkCoordinatesTest {

    private val fg = FrameGeometry(1080, 1920)

    @Test
    fun roundTripImageToBodyToImage() {
        val s = SourceNormalizedPoint(0.5f, 0.25f)
        val h = SourceNormalizedPoint(0.5f, 0.65f)
        val snapshot = BodyFrameGeometry.constructFromAnchors(s, h, fg, 100_000L, "snap_rt").snapshot!!

        val testPoints = listOf(
            SourceNormalizedPoint(0.5f, 0.25f), // Shoulder center
            SourceNormalizedPoint(0.5f, 0.65f), // Hip center
            SourceNormalizedPoint(0.5f, 0.45f), // Torso center
            SourceNormalizedPoint(0.2f, 0.35f), // Hand on left
            SourceNormalizedPoint(0.8f, 0.40f), // Hand on right
            SourceNormalizedPoint(1.2f, -0.1f), // Outside image boundaries
        )

        for (pt in testPoints) {
            val bodyPos = LandmarkCoordinates.bodyPositionOf(pt, snapshot)
            val recovered = LandmarkCoordinates.imagePositionOf(bodyPos, snapshot)

            assertEquals("X mismatch for $pt", pt.x, recovered.x, 1e-4f)
            assertEquals("Y mismatch for $pt", pt.y, recovered.y, 1e-4f)
        }
    }

    @Test
    fun snapshotIdMismatchThrows() {
        val s = SourceNormalizedPoint(0.5f, 0.25f)
        val h = SourceNormalizedPoint(0.5f, 0.65f)
        val snapshot1 = BodyFrameGeometry.constructFromAnchors(s, h, fg, 100_000L, "snap_1").snapshot!!
        val snapshot2 = BodyFrameGeometry.constructFromAnchors(s, h, fg, 200_000L, "snap_2").snapshot!!

        val bodyPos = LandmarkCoordinates.bodyPositionOf(SourceNormalizedPoint(0.3f, 0.3f), snapshot1)

        assertThrows(IllegalArgumentException::class.java) {
            LandmarkCoordinates.imagePositionOf(bodyPos, snapshot2)
        }
    }
}

