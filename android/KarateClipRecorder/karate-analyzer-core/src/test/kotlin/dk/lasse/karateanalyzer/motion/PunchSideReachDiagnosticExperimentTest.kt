package dk.lasse.karateanalyzer.motion

import kotlin.math.PI
import kotlin.math.acos
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.sin
import kotlin.math.sqrt
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Bounded, test-only geometry experiments for the unresolved straight-punch side rule.
 *
 * These tests deliberately do not call or change the production side resolver. They record
 * counterexamples that any future rule must explain before its semantics or thresholds are approved.
 */
class PunchSideReachDiagnosticExperimentTest {
    @Test
    fun `elbow opening and shoulder reach can increase while the hand moves toward the torso`() {
        val torso = Point2(0.0, 0.0)
        val shoulder = Point2(2.0, 0.0)
        val start = equalSegmentArm(shoulder, hand = Point2(1.5, 0.0), segmentLength = 1.0)
        val end = equalSegmentArm(shoulder, hand = Point2(1.0, 0.0), segmentLength = 1.0)

        assertTrue(end.elbowAngleDegrees > start.elbowAngleDegrees)
        assertTrue(end.shoulderToHandReach > start.shoulderToHandReach)
        assertTrue(end.hand.distanceTo(torso) < start.hand.distanceTo(torso))

        assertEquals(0.5, start.shoulderToHandReach, 1e-9)
        assertEquals(1.0, end.shoulderToHandReach, 1e-9)
        assertEquals(1.5, start.hand.distanceTo(torso), 1e-9)
        assertEquals(1.0, end.hand.distanceTo(torso), 1e-9)
    }

    @Test
    fun `euclidean reach from aspect-correct 2D inputs is invariant under rigid transforms and reflection`() {
        val shoulder = Point2(0.25, -0.4)
        val hand = Point2(1.15, 0.3)
        val baseline = shoulder.distanceTo(hand)
        val angle = 0.73
        val translation = Point2(4.2, -2.7)

        val transformedShoulder = shoulder.rotate(angle) + translation
        val transformedHand = hand.rotate(angle) + translation
        val reflectedShoulder = shoulder.reflectHorizontally()
        val reflectedHand = hand.reflectHorizontally()

        assertEquals(baseline, transformedShoulder.distanceTo(transformedHand), 1e-9)
        assertEquals(baseline, reflectedShoulder.distanceTo(reflectedHand), 1e-9)
    }

    @Test
    fun `fixed 3D reach changes under out-of-plane projection`() {
        val shoulder = Point3(0.0, 0.0, 0.0)
        val handFacingImagePlane = Point3(1.0, 0.0, 0.0)
        val handRotatedOutOfPlane = Point3(cos(PI / 3.0), 0.0, sin(PI / 3.0))

        assertEquals(1.0, shoulder.distanceTo(handFacingImagePlane), 1e-9)
        assertEquals(1.0, shoulder.distanceTo(handRotatedOutOfPlane), 1e-9)
        assertEquals(1.0, shoulder.projectToImage().distanceTo(handFacingImagePlane.projectToImage()), 1e-9)
        assertEquals(0.5, shoulder.projectToImage().distanceTo(handRotatedOutOfPlane.projectToImage()), 1e-9)
    }

    @Test
    fun `instantaneous torso scaling can manufacture normalized reach growth`() {
        val unchangedReach = 1.0
        val initialTorsoLength = 1.0
        val shortenedTorsoLength = 0.8

        assertEquals(1.0, unchangedReach / initialTorsoLength, 1e-9)
        assertEquals(1.25, unchangedReach / shortenedTorsoLength, 1e-9)
    }

    @Test
    fun `interval overlap excludes later and earlier disjoint episodes`() {
        val elbowOpening = Episode(100, 220)
        val simultaneousReachExtension = Episode(140, 210)
        val laterRecovery = Episode(230, 330)
        val earlierUnrelatedReach = Episode(0, 90)

        assertEquals(70, elbowOpening.overlapDuration(simultaneousReachExtension))
        assertEquals(0, elbowOpening.overlapDuration(laterRecovery))
        assertEquals(0, elbowOpening.overlapDuration(earlierUnrelatedReach))
    }

    private fun equalSegmentArm(shoulder: Point2, hand: Point2, segmentLength: Double): ArmPose {
        val reach = shoulder.distanceTo(hand)
        require(reach in 0.0..(2.0 * segmentLength))
        val midpoint = Point2((shoulder.x + hand.x) / 2.0, (shoulder.y + hand.y) / 2.0)
        val halfReach = reach / 2.0
        val perpendicularHeight = sqrt(segmentLength * segmentLength - halfReach * halfReach)
        val direction = Point2((hand.x - shoulder.x) / reach, (hand.y - shoulder.y) / reach)
        val elbow = midpoint + Point2(-direction.y, direction.x) * perpendicularHeight
        val shoulderVector = shoulder - elbow
        val handVector = hand - elbow
        val cosine = (shoulderVector dot handVector) /
            (shoulderVector.length() * handVector.length())
        val elbowAngle = acos(cosine.coerceIn(-1.0, 1.0)) * 180.0 / PI
        return ArmPose(hand, reach, elbowAngle)
    }

    private data class ArmPose(
        val hand: Point2,
        val shoulderToHandReach: Double,
        val elbowAngleDegrees: Double,
    )

    private data class Episode(val start: Long, val end: Long) {
        fun overlapDuration(other: Episode): Long =
            (minOf(end, other.end) - maxOf(start, other.start)).coerceAtLeast(0)
    }

    private data class Point2(val x: Double, val y: Double) {
        operator fun plus(other: Point2) = Point2(x + other.x, y + other.y)
        operator fun minus(other: Point2) = Point2(x - other.x, y - other.y)
        operator fun times(scale: Double) = Point2(x * scale, y * scale)
        infix fun dot(other: Point2): Double = x * other.x + y * other.y
        fun length(): Double = hypot(x, y)
        fun distanceTo(other: Point2): Double = (this - other).length()
        fun rotate(angle: Double) = Point2(
            x = x * cos(angle) - y * sin(angle),
            y = x * sin(angle) + y * cos(angle),
        )
        fun reflectHorizontally() = Point2(-x, y)
    }

    private data class Point3(val x: Double, val y: Double, val z: Double) {
        fun distanceTo(other: Point3): Double = sqrt(
            (x - other.x) * (x - other.x) +
                (y - other.y) * (y - other.y) +
                (z - other.z) * (z - other.z),
        )
        fun projectToImage() = Point2(x, y)
    }
}
