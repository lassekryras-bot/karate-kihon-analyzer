package dk.lasse.karateanalyzer.geometry

import org.junit.Assert.*
import org.junit.Test
import kotlin.math.cos
import kotlin.math.sin

class BodyFrameGeometryTest {

    private val fg = FrameGeometry(1080, 1920)

    @Test
    fun uprightFixtureTorsoHeightsAndAxes() {
        val shoulder = SourceNormalizedPoint(0.5f, 0.2f)
        val hip = SourceNormalizedPoint(0.5f, 0.6f)

        val res = BodyFrameGeometry.constructFromAnchors(
            shoulderCenter = shoulder,
            hipCenter = hip,
            frameGeometry = fg,
            timestampUs = 100_000L,
            snapshotId = "snap_upright",
        )

        assertEquals(MeasurementResultState.AVAILABLE, res.state)
        val snap = res.snapshot!!

        // In upward-positive convention: +Y is UP, +X is RIGHT
        // Shoulder is above hip: Up axis is (0, 1)
        assertEquals(0f, snap.upAxis.x, 1e-4f)
        assertEquals(1f, snap.upAxis.y, 1e-4f)

        // Across axis is (Uy, -Ux) = (1, 0), pointing image right
        assertEquals(1f, snap.acrossAxis.x, 1e-4f)
        assertEquals(0f, snap.acrossAxis.y, 1e-4f)

        // Check body coordinates of shoulder and hip
        val shoulderPos = LandmarkCoordinates.bodyPositionOf(shoulder, snap)
        val hipPos = LandmarkCoordinates.bodyPositionOf(hip, snap)

        assertEquals(0f, shoulderPos.lateralOffset, 1e-4f)
        assertEquals(0.5f, shoulderPos.bodyHeight, 1e-4f)

        assertEquals(0f, hipPos.lateralOffset, 1e-4f)
        assertEquals(-0.5f, hipPos.bodyHeight, 1e-4f)

        // Torso center has body height 0 and lateral offset 0
        val centerPos = LandmarkCoordinates.bodyPositionOf(snap.torsoCenterSource!!, snap)
        assertEquals(0f, centerPos.lateralOffset, 1e-4f)
        assertEquals(0f, centerPos.bodyHeight, 1e-4f)
    }

    @Test
    fun bodyLeanInvariance() {
        // Construct upright body
        val s0 = SourceNormalizedPoint(0.5f, 0.3f)
        val h0 = SourceNormalizedPoint(0.5f, 0.7f)
        val snap0 = BodyFrameGeometry.constructFromAnchors(s0, h0, fg, 0L, "s0").snapshot!!

        // Test point: right arm at body coordinates (lateral = 0.4, height = 0.5)
        val armPosExpected = BodyPosition(lateralOffset = 0.4f, bodyHeight = 0.5f, snapshotId = "s_tilted")

        // Now lean the body by angle theta (30 deg)
        val theta = Math.toRadians(30.0).toFloat()
        val c = cos(theta)
        val s = sin(theta)

        // Center origin in metric space
        val originMetric = snap0.origin
        val halfL = snap0.torsoLength * 0.5f

        // Rotated up vector
        val upTilted = UpwardMetricPoint(-s, c)
        val acrossTilted = UpwardMetricPoint(c, s)

        val sMetric = originMetric + upTilted * halfL
        val hMetric = originMetric - upTilted * halfL

        val sTilted = sMetric.toSourceNormalized(fg)
        val hTilted = hMetric.toSourceNormalized(fg)

        val snapTilted = BodyFrameGeometry.constructFromAnchors(sTilted, hTilted, fg, 0L, "s_tilted").snapshot!!

        // The point evaluated in the tilted frame
        val armMetric = snapTilted.origin + (snapTilted.acrossAxis * 0.4f + snapTilted.upAxis * 0.5f) * snapTilted.torsoLength
        val armPosTilted = LandmarkCoordinates.bodyPositionOf(armMetric, snapTilted)

        // Body coordinates remain completely invariant under torso tilt!
        assertEquals(0.4f, armPosTilted.lateralOffset, 1e-4f)
        assertEquals(0.5f, armPosTilted.bodyHeight, 1e-4f)
    }

    @Test
    fun wholeBodyTranslationAndScaleInvariance() {
        val s = SourceNormalizedPoint(0.5f, 0.2f)
        val h = SourceNormalizedPoint(0.5f, 0.6f)
        val snap1 = BodyFrameGeometry.constructFromAnchors(s, h, fg, 0L, "s1").snapshot!!

        // Translate whole body by (+0.1, +0.05)
        val sTrans = SourceNormalizedPoint(0.6f, 0.25f)
        val hTrans = SourceNormalizedPoint(0.6f, 0.65f)
        val snapTrans = BodyFrameGeometry.constructFromAnchors(sTrans, hTrans, fg, 0L, "s_trans").snapshot!!

        // Shoulder relative body position remains (+0.5 height, 0 lateral)
        val sTransPos = LandmarkCoordinates.bodyPositionOf(sTrans, snapTrans)
        assertEquals(0f, sTransPos.lateralOffset, 1e-4f)
        assertEquals(0.5f, sTransPos.bodyHeight, 1e-4f)

        // Image displacement detects the whole body translation
        val disp = snapTrans.origin - snap1.origin
        assertTrue(disp.length() > 0.05f)
    }

    @Test
    fun degenerateTorsoLengthRejected() {
        val s = SourceNormalizedPoint(0.5f, 0.5f)
        val h = SourceNormalizedPoint(0.5f, 0.51f) // Length ~ 0.01 < 0.05 minimum

        val res = BodyFrameGeometry.constructFromAnchors(s, h, fg, 0L, "snap_deg", minimumTorsoLength = 0.05f)
        assertEquals(MeasurementResultState.UNAVAILABLE, res.state)
        assertNull(res.snapshot)
        assertTrue(res.failureReason?.contains("degenerate_torso_axis") == true)
    }
}

