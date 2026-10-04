package dk.lasse.karatecliprecorder.training

import android.content.Context
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.test.core.app.ApplicationProvider
import dk.lasse.karateanalyzer.capture.LateralSide
import dk.lasse.karateanalyzer.capture.qom.MotionBodyProfile
import dk.lasse.karateanalyzer.capture.qom.QomFrameEvidence
import dk.lasse.karateanalyzer.core.*
import dk.lasse.karateanalyzer.geometry.*
import dk.lasse.karateanalyzer.impact.*
import org.json.JSONObject
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import kotlin.math.abs
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28])
class ImpactAnalysisEvidencePersistenceTest {
    @Test fun completeImpactEvidenceSurvivesRoomReopenWithoutRerun() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val databaseName = "impact-evidence-${trainingId()}"
        context.getDatabasePath(databaseName).parentFile?.mkdirs()
        fun open() = Room.databaseBuilder(context, KarateTrainingDatabase::class.java, databaseName)
            .setJournalMode(RoomDatabase.JournalMode.TRUNCATE)
            .allowMainThreadQueries().build()
        var database = open()
        try {
            var repository = TrainingRepository(database)
            val user = TrainingUser()
            repository.createUser(user)
            val session = RecordingSession(userId = user.userId, startedAtMs = 1)
            val recording = MasterRecording(recordingId = "recording", sessionId = session.sessionId,
                filePath = "fixture.mp4", createdAtMs = 1)
            repository.beginSession(session, recording)
            val geometry = CanonicalGeometryDescriptor("geometry", recordingId = recording.recordingId,
                landmarkTrackId = "track", canonicalWidth = 1000, canonicalHeight = 1000)
            repository.addTrack(LandmarkTrack(
                landmarkTrackId = "track", recordingId = recording.recordingId, pipelineKey = "fixture",
                pipelineVersion = "1", configuration = "test", filePath = "fixture.mls",
                sourceState = SourceState.AVAILABLE, state = ProcessingState.COMPLETED,
                canonicalGeometryJson = CanonicalGeometryCodec.encode(geometry),
            ))
            val movement = SessionMovement(sessionId = session.sessionId, startUs = 0, endUs = 720_000,
                playbackStartUs = 0, playbackEndUs = 720_000, segmentationSource = "test",
                segmentationVersion = "qom-test", segmentationTrackId = "track")
            repository.addMovement(movement, ObservationContext(movement.movementId))

            val approval = ImpactViewApproval(MotionActivityPlans.ALTERNATING_PUNCH, "test-side-view-v1", "test-fixture-only")
            val profile = ImpactAnalysisProfile(
                activityProfileId = approval.activityPlanId,
                side = LateralSide.RIGHT,
                weapon = WeaponPointDefinition.COMPOSITE_HAND,
                limbFamily = ImpactLimbFamily.UPPER_LIMB,
                approvedViewProfile = approval.profileId,
            )
            val frames = completedImpactFrames()
            val original = ImpactAnalyzer.analyze(ImpactMovementInput(
                movementId = movement.movementId,
                logicalStartTimestampUs = movement.startUs,
                logicalEndTimestampUs = movement.endUs,
                frames = frames,
                qomTimeline = frames.map { frame ->
                    val distanceFromPeak = abs(frame.timestampMs - 180L).toDouble()
                    QomFrameEvidence(frame.timestampMs, MotionBodyProfile.PUNCH, emptyMap(), 1.0,
                        (1.0 - distanceFromPeak / 500.0).coerceAtLeast(0.0), 3, 3, true)
                },
                segmenterVersion = movement.segmentationVersion,
                landmarkTrackId = "track",
                canonicalGeometry = geometry,
                bodyScale = BodyScaleEvidence(0.80, "body-scale-fixture", "v1"),
                profile = profile,
            ))
            assertEquals(ImpactAnalysisStatus.COMPLETED, original.status)
            assertTrue(original.debugEvidence.isNotEmpty())
            assertNotNull(original.terminalTransitionTimestampUs)
            assertNotNull(original.stableWindowStartTimestampUs)
            assertNotNull(original.stableWindowEndTimestampUs)
            assertNotNull(original.stableRepresentativeTimestampUs)
            assertNotNull(original.stableRepresentativeWeaponPoint)
            assertNotNull(original.stableRepresentativeArticulation)
            val analysis = MovementAnalysis(movementId = movement.movementId,
                analyzerKey = MovementMotionAnalysis.policy.analyzerKey, analyzerVersion = "1", landmarkTrackId = "track",
                state = AnalysisState.PARTIAL,
                geometryJson = JSONObject().put("impactEvidence", ImpactAnalysisEvidenceCodec.encode(profile, approval, original)).toString())
            repository.saveAnalysis(analysis, emptyList())

            database.close()
            database = open()
            repository = TrainingRepository(database)
            val reopened = assertNotNull(repository.movementEvidence(movement.movementId))
            val json = JSONObject(assertNotNull(reopened.analyses.single().geometryJson))
            val persisted = ImpactAnalysisEvidenceCodec.decode(json.getJSONObject("impactEvidence"))
            assertEquals(approval, persisted.viewApproval)
            assertEquals(profile, persisted.profile)
            // JSON numbers do not retain the IEEE-754 sign bit of zero; every analytical value remains equal.
            assertEquals(original.jsonCanonical(), persisted.result)
            assertEquals(original.quality, persisted.result.quality)
            assertEquals(original.provenance, persisted.result.provenance)
            assertEquals(original.debugEvidence.map { it.jsonCanonical() }, persisted.result.debugEvidence)
        } finally {
            database.close()
            context.deleteDatabase(databaseName)
        }
    }

    /** Mirrors the shared-core clear-punch contract with app-owned IDs for persistence verification. */
    private fun completedImpactFrames() = (0L..720L step 20).map { timestampMs ->
        val progress = when {
            timestampMs < 80L -> timestampMs / 80.0 * 0.25
            timestampMs < 140L -> 0.25
            timestampMs < 320L -> 0.25 + (timestampMs - 140L) / 180.0 * 0.75
            else -> 1.0
        }
        fun sample(x: Double, y: Double) = PoseLandmarkSample(Point3(x.toFloat(), y.toFloat(), 0f),
            visibility = .95f, presence = .95f, source = LandmarkSource.OBSERVED)
        val landmarks = mutableMapOf(
            PoseLandmarkId.LEFT_SHOULDER to sample(.40, .40),
            PoseLandmarkId.RIGHT_SHOULDER to sample(.55, .40),
            PoseLandmarkId.LEFT_HIP to sample(.42, .62),
            PoseLandmarkId.RIGHT_HIP to sample(.53, .62),
            PoseLandmarkId.RIGHT_ELBOW to sample(.58 + .16 * progress, .49 - .07 * progress),
        )
        val weapon = sample(.50 + .41 * progress, .49 - .07 * progress)
        for (id in listOf(PoseLandmarkId.RIGHT_WRIST, PoseLandmarkId.RIGHT_THUMB,
            PoseLandmarkId.RIGHT_INDEX, PoseLandmarkId.RIGHT_PINKY)) {
            landmarks[id] = weapon
        }
        PoseFrame(timestampMs, landmarks)
    }

    private fun ImpactAnalysisResult.jsonCanonical() = copy(
        travelProgressAtTransition = travelProgressAtTransition.jsonCanonicalOrNull(),
        articulationProgressAtTransition = articulationProgressAtTransition.jsonCanonicalOrNull(),
        selectedWeaponSpeedAtTransition = selectedWeaponSpeedAtTransition.jsonCanonicalOrNull(),
        angularSpeedAtTransition = angularSpeedAtTransition.jsonCanonicalOrNull(),
        terminalTransitionScore = terminalTransitionScore.jsonCanonicalOrNull(),
        qomPeakRollingArea = qomPeakRollingArea.jsonCanonicalOrNull(),
        qomRollingAreaAtTransition = qomRollingAreaAtTransition.jsonCanonicalOrNull(),
        confidence = confidence.jsonCanonicalOrNull(),
        debugEvidence = debugEvidence.map { it.jsonCanonical() },
    )

    private fun ImpactDebugSample.jsonCanonical() = copy(
        weaponConfidence = weaponConfidence.jsonCanonical(),
        accumulatedTravel = accumulatedTravel.jsonCanonical(),
        travelProgress = travelProgress.jsonCanonical(),
        weaponSpeed = weaponSpeed.jsonCanonical(),
        proximalAngleDeg = proximalAngleDeg.jsonCanonical(),
        jointAngleDeg = jointAngleDeg.jsonCanonical(),
        proximalProgress = proximalProgress.jsonCanonical(),
        jointProgress = jointProgress.jsonCanonical(),
        additiveArticulationProgress = additiveArticulationProgress.jsonCanonical(),
        articulationProgress = articulationProgress.jsonCanonical(),
        angularSpeedDegPerSec = angularSpeedDegPerSec.jsonCanonical(),
        motionEnvelope = motionEnvelope.jsonCanonical(),
        motionFallPerSec = motionFallPerSec.jsonCanonical(),
        progressGate = progressGate.jsonCanonical(),
        postTransitionStability = postTransitionStability.jsonCanonical(),
        terminalTransitionScore = terminalTransitionScore.jsonCanonical(),
        qomRollingArea = qomRollingArea.jsonCanonicalOrNull(),
    )

    private fun Double.jsonCanonical() = if (this == 0.0) 0.0 else this
    private fun Double?.jsonCanonicalOrNull() = this?.jsonCanonical()
}
