package dk.lasse.karateanalyzer.impact

import dk.lasse.karateanalyzer.capture.LateralSide
import dk.lasse.karateanalyzer.capture.qom.MotionBodyProfile
import dk.lasse.karateanalyzer.capture.qom.QomFrameEvidence
import dk.lasse.karateanalyzer.core.LandmarkSource
import dk.lasse.karateanalyzer.core.Point3
import dk.lasse.karateanalyzer.core.PoseFrame
import dk.lasse.karateanalyzer.core.PoseLandmarkId
import dk.lasse.karateanalyzer.core.PoseLandmarkSample
import dk.lasse.karateanalyzer.geometry.CanonicalGeometryDescriptor
import dk.lasse.karateanalyzer.geometry.FrameGeometryMath
import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.test.assertFailsWith

class ImpactAnalyzerTest {
    @Test
    fun `clear punch produces deterministic transition stable window debug evidence and provenance`() {
        val input = input()
        val first = ImpactAnalyzer.analyze(input)
        val second = ImpactAnalyzer.analyze(input)

        assertEquals(first, second)
        assertEquals(ImpactAnalysisStatus.COMPLETED, first.status)
        assertNotNull(first.terminalTransitionTimestampUs)
        assertEquals(first.terminalTransitionTimestampUs, first.selectedObservedTimestampUs)
        assertNull(first.terminalTransitionEstimateTimestampUs)
        assertTrue(first.qomPeakTimestampUs!! <= first.terminalTransitionTimestampUs!!)
        assertTrue(first.terminalTransitionTimestampUs!! < first.stableWindowStartTimestampUs!!)
        assertTrue(first.stableWindowStartTimestampUs!! <= first.stableRepresentativeTimestampUs!!)
        assertTrue(first.stableRepresentativeTimestampUs!! <= first.stableWindowEndTimestampUs!!)
        assertEquals(input.frames.size, first.debugEvidence.size)
        assertEquals(ImpactAnalyzer.ANALYZER_VERSION, first.provenance.analyzerVersion)
        assertEquals(input.profile.configVersion, first.provenance.configurationVersion)
        assertEquals("track-1", first.provenance.landmarkTrackId)
        assertEquals("geometry-1", first.provenance.frameGeometryId)
        assertEquals("body-scale-fixture", first.provenance.bodyScaleSourceId)
        assertEquals(input.profile.spatialDeadbandBodyHeightRatio, first.provenance.calibration.spatialDeadbandBodyHeightRatio)
        assertEquals(input.profile.positionMedianSamples, first.provenance.calibration.positionMedianSamples)
        val representativeFrame = input.frames.single {
            it.timestampMs * 1000L == first.stableRepresentativeTimestampUs
        }
        val representativeWrist = representativeFrame.landmarks[PoseLandmarkId.RIGHT_WRIST]!!.position!!
        assertEquals(representativeWrist.x, first.stableRepresentativeWeaponPoint!!.x, 1e-6f)
        assertEquals(representativeWrist.y, first.stableRepresentativeWeaponPoint!!.y, 1e-6f)
    }

    @Test
    fun `left right mirrored movement has the same event timing`() {
        val right = ImpactAnalyzer.analyze(input())
        val left = ImpactAnalyzer.analyze(input(side = LateralSide.LEFT, mirrored = true))

        assertEquals(ImpactAnalysisStatus.COMPLETED, right.status)
        assertEquals(ImpactAnalysisStatus.COMPLETED, left.status)
        assertEquals(right.terminalTransitionTimestampUs, left.terminalTransitionTimestampUs)
        assertEquals(right.stableWindowStartTimestampUs, left.stableWindowStartTimestampUs)
    }

    @Test
    fun `time based windows remain comparable at 30 and 60 fps`() {
        val at30 = ImpactAnalyzer.analyze(input(frameIntervalMs = 33L))
        val at60 = ImpactAnalyzer.analyze(input(frameIntervalMs = 16L))

        assertEquals(ImpactAnalysisStatus.COMPLETED, at30.status)
        assertEquals(ImpactAnalysisStatus.COMPLETED, at60.status)
        assertTrue(abs(at30.terminalTransitionTimestampUs!! - at60.terminalTransitionTimestampUs!!) <= 70_000L)
        assertTrue(at30.stableWindowEndTimestampUs!! - at30.stableWindowStartTimestampUs!! >= 100_000L)
        assertTrue(at60.stableWindowEndTimestampUs!! - at60.stableWindowStartTimestampUs!! >= 100_000L)
    }

    @Test
    fun `timestamp jitter is used directly for rates and confirmation`() {
        val regular = ImpactAnalyzer.analyze(input())
        val jitteredTimes = buildList {
            var time = 0L
            var index = 0
            while (time <= 720L) {
                add(time)
                time += if (index++ % 2 == 0) 17L else 23L
            }
        }
        val jittered = ImpactAnalyzer.analyze(input(timestampsMs = jitteredTimes))

        assertEquals(ImpactAnalysisStatus.COMPLETED, jittered.status)
        assertTrue(abs(regular.terminalTransitionTimestampUs!! - jittered.terminalTransitionTimestampUs!!) <= 60_000L)
        assertTrue(jittered.debugEvidence.zipWithNext().any { (a, b) -> b.timestampUs - a.timestampUs == 17_000L })
        assertTrue(jittered.debugEvidence.zipWithNext().any { (a, b) -> b.timestampUs - a.timestampUs == 23_000L })
    }

    @Test
    fun `early pause is rejected until progress and the post qom collapse`() {
        val result = ImpactAnalyzer.analyze(input())
        val earlyPauseEndUs = 140_000L

        assertEquals(ImpactAnalysisStatus.COMPLETED, result.status)
        assertTrue(result.terminalTransitionTimestampUs!! > earlyPauseEndUs)
        assertTrue(result.travelProgressAtTransition!! > 0.70)
        assertTrue(result.articulationProgressAtTransition!! > 0.50)
    }

    @Test
    fun `resumed motion closes but does not redefine the first stable terminal window`() {
        val result = ImpactAnalyzer.analyze(input(resumeAtMs = 620L, endMs = 900L))

        assertEquals(ImpactAnalysisStatus.COMPLETED, result.status)
        assertTrue(result.stableWindowEndTimestampUs!! < 700_000L)
        assertTrue(result.stableWindowEndTimestampUs!! < 900_000L)
    }

    @Test
    fun `short hold abstains when no configured stable window exists`() {
        val profile = profile().copy(
            configVersion = "test_short_hold_v1",
            postTransitionConfirmationUs = 60_000L,
            stableMinimumDurationUs = 240_000L,
        )
        val result = ImpactAnalyzer.analyze(input(endMs = 560L, profile = profile))

        assertEquals(ImpactAnalysisStatus.ABSTAINED, result.status)
        assertEquals(ImpactAbstentionReason.NO_STABLE_TERMINAL_WINDOW, result.abstentionReason)
    }

    @Test
    fun `insufficient projected travel abstains instead of fabricating a terminal event`() {
        val result = ImpactAnalyzer.analyze(input(weaponTravel = 0.01))

        assertEquals(ImpactAnalysisStatus.ABSTAINED, result.status)
        assertEquals(ImpactAbstentionReason.INSUFFICIENT_MEASURABLE_MOTION, result.abstentionReason)
    }

    @Test
    fun `spatial motion immediately below and above the body scaled per sample deadband diverges`() {
        val noSmoothing = profile().copy(
            configVersion = "test_no_smoothing_v1",
            positionMedianSamples = 1,
            positionMeanSamples = 1,
        )
        val below = ImpactAnalyzer.analyze(input(weaponTravel = 0.17, profile = noSmoothing))
        val above = ImpactAnalyzer.analyze(input(weaponTravel = 0.19, profile = noSmoothing))

        assertEquals(ImpactAbstentionReason.INSUFFICIENT_MEASURABLE_MOTION, below.abstentionReason)
        assertEquals(ImpactAnalysisStatus.COMPLETED, above.status)
    }

    @Test
    fun `missing weapon missing scale and invalid timestamps have explicit reasons`() {
        val missingWeapon = ImpactAnalyzer.analyze(input(missingWeapon = true))
        val missingScale = ImpactAnalyzer.analyze(input().copy(bodyScale = null))
        val duplicateFrames = input().frames.toMutableList().also { it[2] = it[2].copy(timestampMs = it[1].timestampMs) }
        val invalidTime = ImpactAnalyzer.analyze(input().copy(frames = duplicateFrames))

        assertEquals(ImpactAbstentionReason.WEAPON_TRACK_UNAVAILABLE, missingWeapon.abstentionReason)
        assertEquals(ImpactAbstentionReason.BODY_SCALE_UNAVAILABLE, missingScale.abstentionReason)
        assertEquals(ImpactAbstentionReason.INVALID_TIMESTAMPS, invalidTime.abstentionReason)
    }

    @Test
    fun `missing selected limb geometry abstains separately from weapon evidence`() {
        val result = ImpactAnalyzer.analyze(input(missingElbow = true))
        assertEquals(ImpactAnalysisStatus.ABSTAINED, result.status)
        assertEquals(ImpactAbstentionReason.LIMB_GEOMETRY_UNAVAILABLE, result.abstentionReason)
    }

    @Test
    fun `wrapped angle difference crosses representation boundary by two degrees`() {
        assertEquals(2.0, FrameGeometryMath.wrappedAngleDifferenceDeg(-179.0, 179.0), 1e-9)
        assertEquals(-2.0, FrameGeometryMath.wrappedAngleDifferenceDeg(179.0, -179.0), 1e-9)
    }

    @Test
    fun `provisional calibration cannot change without a new configuration version`() {
        assertFailsWith<IllegalArgumentException> {
            profile().copy(stableMinimumDurationUs = 120_000L)
        }
    }

    private fun input(
        side: LateralSide = LateralSide.RIGHT,
        mirrored: Boolean = false,
        frameIntervalMs: Long = 20L,
        timestampsMs: List<Long>? = null,
        endMs: Long = 720L,
        resumeAtMs: Long? = null,
        weaponTravel: Double = 0.41,
        missingWeapon: Boolean = false,
        missingElbow: Boolean = false,
        profile: ImpactAnalysisProfile = profile(side),
    ): ImpactMovementInput {
        val times = timestampsMs ?: generateSequence(0L) { it + frameIntervalMs }.takeWhile { it <= endMs }.toList()
        val frames = times.map { timestamp ->
            punchFrame(timestamp, side, mirrored, resumeAtMs, weaponTravel, missingWeapon, missingElbow)
        }
        val qom = times.map { timestamp ->
            val distanceFromPeak = abs(timestamp - 180L).toDouble()
            QomFrameEvidence(
                timestampMs = timestamp,
                profile = MotionBodyProfile.PUNCH,
                blockQom = emptyMap(),
                aggregateQom = 1.0,
                rollingArea = (1.0 - distanceFromPeak / 500.0).coerceAtLeast(0.0),
                availableBlockCount = 3,
                totalActiveBlockCount = 3,
                isAvailable = true,
            )
        }
        return ImpactMovementInput(
            movementId = "movement-1",
            logicalStartTimestampUs = times.first() * 1000L,
            logicalEndTimestampUs = times.last() * 1000L,
            frames = frames,
            qomTimeline = qom,
            segmenterVersion = "activity_qom_hysteresis_v1",
            landmarkTrackId = "track-1",
            canonicalGeometry = CanonicalGeometryDescriptor(
                geometryId = "geometry-1",
                recordingId = "recording-1",
                landmarkTrackId = "track-1",
                canonicalWidth = 1000,
                canonicalHeight = 1000,
                sourceHash = "source-hash",
                trackHash = "track-hash",
            ),
            bodyScale = BodyScaleEvidence(0.80, "body-scale-fixture", "v1"),
            profile = profile,
        )
    }

    private fun profile(side: LateralSide = LateralSide.RIGHT) = ImpactAnalysisProfile(
        activityProfileId = "straight-punch-side-v1",
        side = side,
        weapon = WeaponPointDefinition.COMPOSITE_HAND,
        limbFamily = ImpactLimbFamily.UPPER_LIMB,
        approvedViewProfile = "approved-side",
    )

    private fun punchFrame(
        timestampMs: Long,
        side: LateralSide,
        mirrored: Boolean,
        resumeAtMs: Long?,
        weaponTravel: Double,
        missingWeapon: Boolean,
        missingElbow: Boolean,
    ): PoseFrame {
        val progress = when {
            timestampMs < 80L -> timestampMs / 80.0 * 0.25
            timestampMs < 140L -> 0.25
            timestampMs < 320L -> 0.25 + (timestampMs - 140L) / 180.0 * 0.75
            resumeAtMs != null && timestampMs >= resumeAtMs -> (1.0 - (timestampMs - resumeAtMs) / 160.0 * 0.35).coerceAtLeast(0.65)
            else -> 1.0
        }
        fun x(value: Double): Float = if (mirrored) (1.0 - value).toFloat() else value.toFloat()
        fun sample(px: Double, py: Double) = PoseLandmarkSample(
            position = Point3(x(px), py.toFloat(), 0f),
            worldPosition = null,
            visibility = 0.95f,
            presence = 0.95f,
            source = LandmarkSource.OBSERVED,
        )
        val landmarks = mutableMapOf<PoseLandmarkId, PoseLandmarkSample>()
        landmarks[PoseLandmarkId.LEFT_SHOULDER] = sample(0.40, 0.40)
        landmarks[PoseLandmarkId.RIGHT_SHOULDER] = sample(0.55, 0.40)
        landmarks[PoseLandmarkId.LEFT_HIP] = sample(0.42, 0.62)
        landmarks[PoseLandmarkId.RIGHT_HIP] = sample(0.53, 0.62)

        val selectedShoulder = if (side == LateralSide.RIGHT) PoseLandmarkId.RIGHT_SHOULDER else PoseLandmarkId.LEFT_SHOULDER
        val selectedElbow = if (side == LateralSide.RIGHT) PoseLandmarkId.RIGHT_ELBOW else PoseLandmarkId.LEFT_ELBOW
        val selectedWrist = if (side == LateralSide.RIGHT) PoseLandmarkId.RIGHT_WRIST else PoseLandmarkId.LEFT_WRIST
        val selectedThumb = if (side == LateralSide.RIGHT) PoseLandmarkId.RIGHT_THUMB else PoseLandmarkId.LEFT_THUMB
        val selectedIndex = if (side == LateralSide.RIGHT) PoseLandmarkId.RIGHT_INDEX else PoseLandmarkId.LEFT_INDEX
        val selectedPinky = if (side == LateralSide.RIGHT) PoseLandmarkId.RIGHT_PINKY else PoseLandmarkId.LEFT_PINKY
        val shoulderBase = if (side == LateralSide.RIGHT) 0.55 else 0.40
        landmarks[selectedShoulder] = sample(shoulderBase, 0.40)
        if (!missingElbow) landmarks[selectedElbow] = sample(shoulderBase + 0.03 + 0.16 * progress, 0.49 - 0.07 * progress)
        if (!missingWeapon) {
            val weaponX = shoulderBase - 0.05 + weaponTravel * progress
            val weaponY = 0.49 - 0.07 * progress
            for (id in listOf(selectedWrist, selectedThumb, selectedIndex, selectedPinky)) {
                landmarks[id] = sample(weaponX, weaponY)
            }
        }
        return PoseFrame(timestampMs, landmarks)
    }
}
