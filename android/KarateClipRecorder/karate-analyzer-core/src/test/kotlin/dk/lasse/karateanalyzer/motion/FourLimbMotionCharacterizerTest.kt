package dk.lasse.karateanalyzer.motion

import dk.lasse.karateanalyzer.core.*
import dk.lasse.karateanalyzer.geometry.CanonicalGeometryDescriptor
import kotlin.math.cos
import kotlin.math.sin
import kotlin.test.*

class FourLimbMotionCharacterizerTest {
    @Test fun `opening and closing arms retain directional travel without choosing a winner`() {
        val result = analyze { limb, t -> when (limb) {
            LimbId.LEFT_ARM -> 40.0 + progress(t) * 100
            LimbId.RIGHT_ARM -> 150.0 - progress(t) * 100
            else -> 150.0
        } }
        assertEquals(LimbMotionPattern.MOSTLY_OPENING, result.leftArm.motionPattern)
        assertEquals(LimbMotionPattern.MOSTLY_CLOSING, result.rightArm.motionPattern)
        assertEquals(100.0, result.leftArm.metrics!!.positiveAngularTravelDeg, 0.01)
        assertEquals(100.0, result.rightArm.metrics!!.negativeAngularTravelDeg, 0.01)
        assertEquals(0.5, result.activityRanking.activityShares!![LimbId.LEFT_ARM]!!, 0.001)
        assertNull(result.activityRanking.primaryActiveLimb)
        assertEquals(setOf(LimbId.LEFT_LEG, LimbId.RIGHT_LEG), result.activityRanking.stableLimbs)
    }

    @Test fun `stable opposite arm and legs allow dominant articulation`() {
        val result = analyze { limb, t -> if (limb == LimbId.RIGHT_ARM) 40 + progress(t) * 100 else 100.0 }
        assertEquals(LimbId.RIGHT_ARM, result.activityRanking.primaryActiveLimb)
        assertEquals(LimbMotionPattern.STABLE, result.leftArm.motionPattern)
        assertNull(result.leftArm.metrics!!.peakAngularSpeedTimestampUs)
        assertNull(result.leftArm.metrics!!.motionStartTimestampUs)
    }

    @Test fun `knee return has near zero net change and large meaningful travel with reversal`() {
        val result = analyze { limb, t -> if (limb != LimbId.RIGHT_LEG) 150.0 else when {
            t < 200 -> 150.0
            t < 500 -> 150 - (t - 200) / 300.0 * 90
            t < 800 -> 60 + (t - 500) / 300.0 * 90
            else -> 150.0
        } }
        val profile = result.rightLeg
        assertEquals(LimbMotionPattern.CLOSE_OPEN_REVERSAL, profile.motionPattern)
        assertEquals(0.0, profile.metrics!!.netAngleChangeDeg, 0.01)
        assertEquals(180.0, profile.metrics.meaningfulAngularTravelDeg, 0.01)
        assertEquals(500_000L, profile.metrics.reversals.single().timestampUs)
        assertEquals(90.0, profile.metrics.largestReversalDeg!!, 0.01)
        assertEquals(LimbId.RIGHT_LEG, result.activityRanking.primaryActiveLimb)
    }

    @Test fun `bounded jitter remains stable`() {
        val result = analyze { _, t -> 100 + sin(t.toDouble()) * 0.8 }
        result.profiles.values.forEach {
            assertEquals(LimbMotionPattern.STABLE, it.motionPattern)
            assertEquals(0.0, it.metrics!!.meaningfulAngularTravelDeg)
            assertTrue(it.metrics.stableDurationUs >= 900_000)
        }
        assertNull(result.activityRanking.activityShares)
    }

    @Test fun `slow cumulative motion survives sub-deadband frame steps at 30 and 60 fps`() {
        fun run(fps: Int) = FourLimbMotionCharacterizer.characterize(input(
            (0..fps).map { (it * 1000.0 / fps).toLong() },
        ) { limb, t -> if (limb == LimbId.LEFT_ARM) 50 + t * 0.03 else 100.0 })
        val a = run(30).leftArm.metrics!!
        val b = run(60).leftArm.metrics!!
        assertEquals(30.0, a.meaningfulAngularTravelDeg, 0.01)
        assertEquals(a.meaningfulAngularTravelDeg, b.meaningfulAngularTravelDeg, 0.01)
        assertEquals(30.0, a.peakAngularSpeedDegPerSec!!, 0.1)
        assertEquals(a.peakAngularSpeedDegPerSec, b.peakAngularSpeedDegPerSec!!, 0.1)
        assertEquals(0L, a.motionStartTimestampUs)
        assertEquals(a.motionStartTimestampUs, b.motionStartTimestampUs)
        assertNull(a.motionEndTimestampUs)
    }

    @Test fun `settling follows final motion and confirmation follows quiet onset`() {
        val profile = analyze { limb, t -> if (limb != LimbId.LEFT_ARM) 100.0 else when {
            t < 200 -> 50.0
            t < 400 -> 50 + (t - 200) * 0.2
            t < 600 -> 90.0 // earlier pause is not final settling
            t < 800 -> 90 + (t - 600) * 0.2
            else -> 130.0
        } }.leftArm.metrics!!
        assertEquals(200_000L, profile.motionStartTimestampUs)
        assertEquals(800_000L, profile.motionEndTimestampUs)
        assertEquals(900_000L, profile.settlingConfirmedAtTimestampUs)
        assertTrue(profile.motionEndTimestampUs!! >= profile.lastMeaningfulMotionTimestampUs!!)
    }

    @Test fun `short quiet tail is not confirmed settling`() {
        val result = FourLimbMotionCharacterizer.characterize(input((0L..850L step 10).toList()) { limb, t ->
            if (limb == LimbId.LEFT_ARM) 50 + progress(t) * 100 else 100.0
        })
        assertNull(result.leftArm.metrics!!.motionEndTimestampUs)
        assertContains(result.leftArm.qualityFlags, LimbQualityFlag.UNCONFIRMED_SETTLING)
    }

    @Test fun `occluded wrist abstains independently and no global winner is claimed`() {
        val original = input { limb, t -> if (limb == LimbId.LEFT_ARM) 50 + progress(t) * 100 else 100.0 }
        val result = FourLimbMotionCharacterizer.characterize(original.copy(frames = original.frames.map {
            it.copy(landmarks = it.landmarks - PoseLandmarkId.RIGHT_WRIST)
        }))
        assertEquals(LimbAbstentionReason.JOINT_GEOMETRY_UNAVAILABLE, result.rightArm.abstentionReason)
        assertNotNull(result.leftArm.metrics)
        assertEquals(LimbMotionPattern.STABLE, result.rightLeg.motionPattern)
        assertFalse(result.activityRanking.completeCoverage)
        assertNull(result.activityRanking.primaryActiveLimb)
    }

    @Test fun `gap cannot create travel reversal or settling through missing evidence`() {
        val original = input { _, t -> if (t < 500) 60.0 else 150.0 }
        val frames = original.frames.map { if (it.timestampMs in 450..550) it.copy(landmarks = emptyMap()) else it }
        val result = FourLimbMotionCharacterizer.characterize(original.copy(frames = frames))
        result.profiles.values.forEach {
            assertEquals(0.0, it.metrics!!.meaningfulAngularTravelDeg)
            assertTrue(it.metrics.reversals.isEmpty())
            assertTrue(it.metrics.unknownDurationUs >= 100_000)
            assertContains(it.qualityFlags, LimbQualityFlag.GAPS)
            assertTrue(it.samples.any { sample -> sample.angleDeg == null })
        }
    }

    @Test fun `sparse timestamps cannot masquerade as complete coverage`() {
        val result = FourLimbMotionCharacterizer.characterize(input(listOf(0, 50, 950, 1000)) { _, _ -> 100.0 })
        result.profiles.values.forEach { assertEquals(LimbAbstentionReason.TRACKING_QUALITY_INSUFFICIENT, it.abstentionReason) }
    }

    @Test fun `duplicate and decreasing timestamps abstain rather than sorting evidence`() {
        val source = input { _, _ -> 100.0 }
        for (frames in listOf(source.frames + source.frames.last(), source.frames.reversed())) {
            val result = FourLimbMotionCharacterizer.characterize(source.copy(frames = frames))
            result.profiles.values.forEach { assertEquals(LimbAbstentionReason.INVALID_TIMESTAMPS, it.abstentionReason) }
        }
    }

    @Test fun `track mismatch unknown and unsupported geometry abstain`() {
        val source = input { _, _ -> 100.0 }
        for (geometry in listOf(CanonicalGeometryDescriptor.UNKNOWN, source.canonicalGeometry.copy(contractVersion = "future"))) {
            assertEquals(LimbAbstentionReason.FRAME_GEOMETRY_UNAVAILABLE,
                FourLimbMotionCharacterizer.characterize(source.copy(canonicalGeometry = geometry)).leftArm.abstentionReason)
        }
        assertEquals(LimbAbstentionReason.TRACK_IDENTITY_MISMATCH,
            FourLimbMotionCharacterizer.characterize(source.copy(landmarkTrackId = "other")).leftArm.abstentionReason)
    }

    @Test fun `non-finite degenerate and interpolated landmarks never produce motion`() {
        val original = input { _, _ -> 100.0 }
        for (replacement in listOf(
            PoseLandmarkSample(Point3(Float.NaN, 0f, 0f), visibility = 1f, presence = 1f, source = LandmarkSource.OBSERVED),
            original.frames.first().landmarks.getValue(PoseLandmarkId.LEFT_ELBOW),
            original.frames.first().landmarks.getValue(PoseLandmarkId.LEFT_WRIST).copy(source = LandmarkSource.INTERPOLATED),
        )) {
            val result = FourLimbMotionCharacterizer.characterize(original.copy(frames = original.frames.map {
                it.copy(landmarks = it.landmarks + (PoseLandmarkId.LEFT_WRIST to replacement))
            }))
            assertNotNull(result.leftArm.abstentionReason)
            assertNotNull(result.rightArm.metrics)
        }
    }

    @Test fun `aspect ratio correction and mirrored limbs preserve articulation`() {
        val source = input { limb, t -> if (limb == LimbId.LEFT_ARM) 40 + progress(t) * 100 else 100.0 }
        val wide = source.copy(canonicalGeometry = source.canonicalGeometry.copy(canonicalWidth = 2000),
            frames = source.frames.map { frame -> frame.copy(landmarks = frame.landmarks.mapValues { (_, sample) ->
                sample.copy(position = sample.position!!.copy(x = sample.position.x / 2))
            }) })
        val baseline = FourLimbMotionCharacterizer.characterize(source)
        val scaled = FourLimbMotionCharacterizer.characterize(wide)
        assertEquals(baseline.leftArm.metrics, scaled.leftArm.metrics)
        val swaps = LimbId.entries.flatMap { limb ->
            val other = LimbId.entries[limb.ordinal xor 1]
            limb.sourceLandmarks.zip(other.sourceLandmarks)
        }.toMap()
        val mirrored = source.copy(frames = source.frames.map { frame -> frame.copy(landmarks = frame.landmarks.map { (id, sample) ->
            swaps.getValue(id) to sample.copy(position = sample.position!!.copy(x = 1f - sample.position.x))
        }.toMap()) })
        val mirrorResult = FourLimbMotionCharacterizer.characterize(mirrored)
        assertEquals(baseline.leftArm.motionPattern, mirrorResult.rightArm.motionPattern)
        assertEquals(baseline.leftArm.metrics!!.meaningfulAngularTravelDeg, mirrorResult.rightArm.metrics!!.meaningfulAngularTravelDeg, 0.001)
        assertEquals(baseline.leftArm.metrics!!.motionStartTimestampUs, mirrorResult.rightArm.metrics!!.motionStartTimestampUs)
    }

    @Test fun `validated reference windows use robust angles and reject claimed moving references`() {
        val source = input((0L..1200L step 10).toList()) { _, t -> 50 + ((t - 200) / 800.0).coerceIn(0.0, 1.0) * 100 }
            .copy(logicalStartTimestampUs = 200_000, logicalEndTimestampUs = 1_000_000,
                stablePreWindow = LimbEvidenceWindow(0, 200_000), stablePostWindow = LimbEvidenceWindow(1_000_000, 1_200_000))
        val result = FourLimbMotionCharacterizer.characterize(source)
        assertEquals(50.0, result.leftArm.metrics!!.referenceStartAngleDeg, 0.001)
        assertEquals(150.0, result.leftArm.metrics!!.referenceEndAngleDeg, 0.001)
        assertFalse(LimbQualityFlag.BOUNDARY_REFERENCE in result.leftArm.qualityFlags)
        val invalid = source.copy(logicalStartTimestampUs = 400_000, stablePreWindow = LimbEvidenceWindow(200_000, 400_000))
        assertEquals(LimbAbstentionReason.INVALID_STABLE_REFERENCE, FourLimbMotionCharacterizer.characterize(invalid).leftArm.abstentionReason)
    }

    @Test fun `debug deltas are exactly the evidence summarized by the profile`() {
        val result = analyze { _, t -> 50 + progress(t) * 100 }
        result.profiles.values.forEach { profile ->
            assertEquals(profile.metrics!!.meaningfulAngularTravelDeg, profile.samples.sumOf { kotlin.math.abs(it.meaningfulDeltaDeg ?: 0.0) }, 1e-9)
        }
        assertEquals(AngularMotionEvidence.VERSION, result.angularPolicyVersion)
        assertEquals("track", result.landmarkTrackId)
    }

    @Test fun `side resolver uses opening and hikite without guessing ambiguous or missing first repetitions`() {
        val context = ActivitySideContext("punch", "1", LimbInterpretationActivity.STRAIGHT_PUNCH, true)
        val left = analyze { limb, t -> when (limb) {
            LimbId.LEFT_ARM -> 40 + progress(t) * 100
            LimbId.RIGHT_ARM -> 150 - progress(t) * 100
            else -> 100.0
        } }
        assertEquals(dk.lasse.karateanalyzer.capture.LateralSide.LEFT, ActivityStartingSideResolver.resolve(left, context).side)
        val still = analyze { _, _ -> 100.0 }
        assertNull(ActivityStartingSideResolver.resolve(still, context).side)
        val later = left.copy(movementId = "later", logicalStartTimestampUs = 2_000_000, logicalEndTimestampUs = 3_000_000)
        val mismatch = ActivityStartingSideResolver.resolveSequence(listOf(IndexedLimbMovement(0, left), IndexedLimbMovement(1, later)), context)
        assertEquals(AlternationStatus.MISMATCH, mismatch.repetitions.last().alternationStatus)
        assertEquals(dk.lasse.karateanalyzer.capture.LateralSide.LEFT, mismatch.repetitions.last().observed.side)
        val omitted = ActivityStartingSideResolver.resolveSequence(listOf(IndexedLimbMovement(0, left), IndexedLimbMovement(2, later)), context)
        assertEquals(AlternationStatus.MATCH, omitted.repetitions.last().alternationStatus)
        assertNull(ActivityStartingSideResolver.resolveSequence(listOf(IndexedLimbMovement(1, later)), context).startingSide)
        assertNull(ActivityStartingSideResolver.resolveSequence(listOf(IndexedLimbMovement(0, still), IndexedLimbMovement(1, later)), context).startingSide)
        val dual = analyze { limb, t -> if (limb.ordinal < 2) 40 + progress(t) * 100 else 100.0 }
        assertNull(ActivityStartingSideResolver.resolve(dual, context).side)
    }

    @Test fun `kick side needs chamber extension while support remains stable`() {
        val kick = analyze { limb, t -> if (limb != LimbId.RIGHT_LEG) 150.0 else when {
            t < 200 -> 150.0
            t < 500 -> 150 - (t - 200) / 300.0 * 90
            t < 800 -> 60 + (t - 500) / 300.0 * 90
            else -> 150.0
        } }
        assertEquals(dk.lasse.karateanalyzer.capture.LateralSide.RIGHT, ActivityStartingSideResolver.resolve(kick,
            ActivitySideContext("kick", "1", LimbInterpretationActivity.CHAMBER_EXTENSION_KICK, false)).side)
    }

    private fun analyze(angles: (LimbId, Long) -> Double) = FourLimbMotionCharacterizer.characterize(input(angles = angles))
    private fun progress(t: Long) = ((t - 200) / 600.0).coerceIn(0.0, 1.0)

    private fun input(times: List<Long> = (0L..1000L step 10).toList(), angles: (LimbId, Long) -> Double): FourLimbMotionInput {
        val frames = times.map { t ->
            val points = mutableMapOf<PoseLandmarkId, PoseLandmarkSample>()
            fun sample(x: Double, y: Double) = PoseLandmarkSample(Point3(x.toFloat(), y.toFloat(), 0f),
                visibility = 0.95f, presence = 0.95f, source = LandmarkSource.OBSERVED)
            LimbId.entries.forEach { limb ->
                val x = if (limb.ordinal % 2 == 0) 0.3 else 0.7
                val y = if (limb.ordinal < 2) 0.3 else 0.7
                val radians = Math.toRadians(angles(limb, t))
                points[limb.proximal] = sample(x + 0.1, y)
                points[limb.joint] = sample(x, y)
                points[limb.distal] = sample(x + 0.1 * cos(radians), y + 0.1 * sin(radians))
            }
            PoseFrame(t, points)
        }
        return FourLimbMotionInput("movement", "track", times.first() * 1000, times.last() * 1000, frames,
            CanonicalGeometryDescriptor("geometry", recordingId = "recording", landmarkTrackId = "track", canonicalWidth = 1000, canonicalHeight = 1000))
    }
}
