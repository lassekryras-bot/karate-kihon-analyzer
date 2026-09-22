package dk.lasse.karatecliprecorder.movement

import dk.lasse.karateanalyzer.core.*
import dk.lasse.karateanalyzer.geometry.CanonicalGeometryCodec
import dk.lasse.karateanalyzer.geometry.CanonicalGeometryDescriptor
import dk.lasse.karateanalyzer.geometry.CanonicalOrientation
import dk.lasse.karateanalyzer.geometry.SourceToCanonicalTransform
import dk.lasse.karateanalyzer.geometry.TransformOrder
import dk.lasse.karatecliprecorder.training.*
import dk.lasse.karatecliprecorder.training.LandmarkTrack
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import kotlin.test.*

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28])
class MovementPresentationMapperTest {

    private fun portraitDescriptor(recordingId: String, trackId: String? = null) =
        CanonicalGeometryDescriptor(
            geometryId = "geom_$recordingId",
            recordingId = recordingId,
            landmarkTrackId = trackId,
            canonicalWidth = 1080,
            canonicalHeight = 1920,
            canonicalOrientation = CanonicalOrientation.UPRIGHT_UNMIRRORED,
            sourceToCanonicalTransform = SourceToCanonicalTransform(0, false, order = TransformOrder.ROTATION_THEN_MIRROR),
        )

    private fun landscapeDescriptor(recordingId: String, trackId: String? = null) =
        CanonicalGeometryDescriptor(
            geometryId = "geom_$recordingId",
            recordingId = recordingId,
            landmarkTrackId = trackId,
            canonicalWidth = 1920,
            canonicalHeight = 1080,
            canonicalOrientation = CanonicalOrientation.UPRIGHT_UNMIRRORED,
            sourceToCanonicalTransform = SourceToCanonicalTransform(0, false, order = TransformOrder.ROTATION_THEN_MIRROR),
        )

    @Test
    fun straightPunchTargetAnalysisMapsToKeyResultsAndDebugData() {
        val movement = SessionMovement(
            sessionId = "session_123",
            startUs = 1_000_000L,
            endUs = 2_000_000L,
            playbackStartUs = 800_000L,
            playbackEndUs = 2_200_000L,
            segmentationSource = "qom_segmenter",
            segmentationVersion = "2.1",
            analysisFrameUs = 1_500_000L,
            runId = "run_abc",
        )
        val recording = MasterRecording(
            sessionId = "session_123",
            filePath = "recordings/video.mp4",
            createdAtMs = 1000L,
        )
        val analysis = MovementAnalysis(
            movementId = movement.movementId,
            analyzerKey = StraightPunchMovementAdapter.policy.analyzerKey,
            analyzerVersion = "1",
            landmarkTrackId = "track_xyz",
            state = AnalysisState.COMPLETED,
        )
        val measurements = listOf(
            MeasurementResult(
                analysisId = analysis.analysisId,
                measurementKey = TrainingMeasurements.PUNCH_CLOSEST_TARGET,
                calculationVersion = "1",
                valueType = ValueType.CATEGORICAL,
                categoricalValue = "CHUDAN",
                side = BodySide.LEFT,
                state = ResultState.VALID,
                occurrenceUs = 1_500_000L,
                frameIndex = 45L,
            ),
            MeasurementResult(
                analysisId = analysis.analysisId,
                measurementKey = TrainingMeasurements.PUNCH_TARGET_ANGLE_ERROR_DEG,
                calculationVersion = "1",
                valueType = ValueType.NUMERIC,
                numericValue = 4.5,
                side = BodySide.LEFT,
                state = ResultState.VALID,
                occurrenceUs = 1_500_000L,
                frameIndex = 45L,
            ),
            MeasurementResult(
                analysisId = analysis.analysisId,
                measurementKey = TrainingMeasurements.PUNCH_TARGET_CLASSIFICATION_MARGIN_DEG,
                calculationVersion = "1",
                valueType = ValueType.NUMERIC,
                numericValue = 12.3,
                side = BodySide.LEFT,
                state = ResultState.VALID,
                occurrenceUs = 1_500_000L,
                frameIndex = 45L,
            )
        )
        val evidence = MovementEvidence(
            movement = movement,
            recording = recording,
            observation = ObservationContext(movement.movementId, nearerSide = BodySide.LEFT),
            labels = emptyList(),
            events = emptyList(),
            analyses = listOf(analysis),
            measurements = measurements,
            landmarkTracks = emptyList(),
        )

        val presentation = MovementPresentationMapper.map(evidence, displayedNumber = 1)

        assertEquals(1, presentation.displayedNumber)
        assertEquals("Straight punch", presentation.activityTitle)
        assertEquals("Straight punch · Left arm", presentation.contextSubtitle)
        assertEquals(1_500_000L, presentation.canonicalImpactUs)
        assertEquals(PlayerMode.VIDEO, presentation.preferredInitialMode)

        // Key Results
        val targetResult = presentation.keyResults.first { it.id == "target_height" }
        assertTrue(targetResult.isAvailable)
        assertTrue(targetResult.formattedValue.contains("Chūdan"))
        assertTrue(targetResult.formattedValue.contains("+4.5° high"))
        assertEquals(PlayerMode.ANALYSIS, targetResult.preferredMode)
        assertEquals(1_500_000L, targetResult.focusTimestampUs)

        // Needs Attention is empty when no findings exist
        assertTrue(presentation.findings.isEmpty())

        // Debug Data
        val debug = presentation.debugData
        assertEquals(movement.movementId, debug.movementId)
        assertEquals(1_000_000L, debug.durationUs)
        assertEquals(1_500_000L, debug.canonicalAnalysisTimestampUs)
        assertEquals(45L, debug.canonicalFrameIndex)
        assertEquals("LEFT", debug.activeArm)
        assertEquals("straight_punch_target", debug.analyzerKey)
        assertEquals("2.1", debug.segmenterVersion)
        assertEquals("track_xyz", debug.landmarkTrackId)
        assertEquals("run_abc", debug.runId)
    }

    @Test
    fun speedSlotsAreUnavailableWithoutAnalyzerSpeedMeasurement() {
        val movement = SessionMovement(
            sessionId = "session_123",
            startUs = 1_000_000L,
            endUs = 2_000_000L,
            playbackStartUs = 800_000L,
            playbackEndUs = 2_200_000L,
            segmentationSource = "qom_segmenter",
            segmentationVersion = "1",
            analysisFrameUs = 1_500_000L,
        )
        val recording = MasterRecording(
            sessionId = "session_123",
            filePath = "recordings/video.mp4",
            createdAtMs = 1000L,
        )

        val evidence = MovementEvidence(
            movement = movement,
            recording = recording,
            observation = ObservationContext(movement.movementId, nearerSide = BodySide.LEFT),
            labels = emptyList(),
            events = emptyList(),
            analyses = emptyList(),
            measurements = emptyList(),
            landmarkTracks = emptyList(),
        )

        val presentation = MovementPresentationMapper.map(evidence, displayedNumber = 2)

        val maxSpeedResult = presentation.keyResults.first { it.id == "max_speed" }
        assertFalse(maxSpeedResult.isAvailable)
        assertEquals("Unavailable", maxSpeedResult.formattedValue)
        assertEquals(PlayerMode.GRAPH, maxSpeedResult.preferredMode)

        val hikiteResult = presentation.keyResults.first { it.id == "hikite_max_speed" }
        assertFalse(hikiteResult.isAvailable)
        assertEquals("Unavailable", hikiteResult.formattedValue)

        // An arbitrary key cannot establish an approved metric/unit contract
        val analysis = MovementAnalysis(
            movementId = movement.movementId,
            analyzerKey = "straight_punch_target",
            analyzerVersion = "1",
            landmarkTrackId = "track_1",
            state = AnalysisState.COMPLETED,
        )
        val speedMeasurement = MeasurementResult(
            analysisId = analysis.analysisId,
            measurementKey = "PUNCH_MAX_SPEED",
            calculationVersion = "1",
            valueType = ValueType.NUMERIC,
            numericValue = 5.2,
            side = BodySide.LEFT,
            state = ResultState.VALID,
            occurrenceUs = 1_400_000L,
        )
        val evidenceWithSpeed = evidence.copy(
            analyses = listOf(analysis),
            measurements = listOf(speedMeasurement),
        )
        val presentationWithSpeed = MovementPresentationMapper.map(evidenceWithSpeed, displayedNumber = 2)
        val mappedSpeed = presentationWithSpeed.keyResults.first { it.id == "max_speed" }
        assertFalse(mappedSpeed.isAvailable)
        assertEquals("Unavailable", mappedSpeed.formattedValue)
    }

    @Test
    fun qualitativeFindingEmptyForProcessingStatusPartial() {
        val movement = SessionMovement(
            sessionId = "session_123",
            startUs = 500_000L,
            endUs = 1_500_000L,
            playbackStartUs = 200_000L,
            playbackEndUs = 1_800_000L,
            segmentationSource = "qom_segmenter",
            segmentationVersion = "1",
            analysisFrameUs = 1_000_000L,
        )
        val recording = MasterRecording(
            sessionId = "session_123",
            filePath = "recordings/video.mp4",
            createdAtMs = 1000L,
        )
        val completedAnalysis = MovementAnalysis(
            movementId = movement.movementId,
            analyzerKey = "straight_punch_target",
            analyzerVersion = "1",
            landmarkTrackId = "track_1",
            state = AnalysisState.COMPLETED,
        )
        val elbowMeasurement = MeasurementResult(
            analysisId = completedAnalysis.analysisId,
            measurementKey = TrainingMeasurements.PUNCH_ELBOW_ANGLE,
            calculationVersion = "1",
            valueType = ValueType.NUMERIC,
            numericValue = 138.0,
            side = BodySide.RIGHT,
            state = ResultState.VALID,
            occurrenceUs = 1_000_000L,
        )
        val cleanEvidence = MovementEvidence(
            movement = movement,
            recording = recording,
            observation = ObservationContext(movement.movementId, nearerSide = BodySide.RIGHT),
            labels = emptyList(),
            events = emptyList(),
            analyses = listOf(completedAnalysis),
            measurements = listOf(elbowMeasurement),
            landmarkTracks = emptyList(),
        )

        // Presentation layer must NOT fabricate a finding from elbow angle < 150
        val cleanPresentation = MovementPresentationMapper.map(cleanEvidence, displayedNumber = 1)
        assertTrue(cleanPresentation.findings.isEmpty(), "Needs Attention must be empty when analyzer reported no findings")

        // Processing status PARTIAL is not a technique fault and must NOT be mapped to findings
        val partialAnalysis = completedAnalysis.copy(
            state = AnalysisState.PARTIAL,
            reason = "Target outside reach circle",
        )
        val partialEvidence = cleanEvidence.copy(analyses = listOf(partialAnalysis))
        val partialPresentation = MovementPresentationMapper.map(partialEvidence, displayedNumber = 1)
        assertTrue(partialPresentation.findings.isEmpty(), "Processing status PARTIAL must not be presented as a technique fault")
        assertEquals("Target outside reach circle", partialPresentation.debugData.reason)
    }

    @Test
    fun persistedGeometryJsonRendersTargetRaysWithoutRecalculation() {
        val movement = SessionMovement(
            sessionId = "session_123",
            startUs = 500_000L,
            endUs = 1_500_000L,
            playbackStartUs = 200_000L,
            playbackEndUs = 1_800_000L,
            segmentationSource = "qom_segmenter",
            segmentationVersion = "1",
            analysisFrameUs = 1_000_000L,
        )
        val recording = MasterRecording(
            sessionId = "session_123",
            filePath = "recordings/video.mp4",
            createdAtMs = 1000L,
            canonicalGeometryJson = CanonicalGeometryCodec.encode(portraitDescriptor("session_123")),
        )
        val geometryJson = """
            {
                "contract": "straight_punch_geometry_v1",
                "coordinateSpace": "normalized_upright_unmirrored_image",
                "timestampUs": 1000000,
                "frameIndex": 30,
                "activeSide": "RIGHT",
                "origin": {"x": 0.5, "y": 0.4},
                "rays": [
                    {"targetType": "JODAN", "endpointX": 0.8, "endpointY": 0.25, "isClosest": false},
                    {"targetType": "CHUDAN", "endpointX": 0.85, "endpointY": 0.45, "isClosest": true},
                    {"targetType": "GEDAN", "endpointX": 0.75, "endpointY": 0.65, "isClosest": false}
                ]
            }
        """.trimIndent()
        val analysis = MovementAnalysis(
            movementId = movement.movementId,
            analyzerKey = "straight_punch_target",
            analyzerVersion = "1",
            landmarkTrackId = "track_1",
            state = AnalysisState.COMPLETED,
            geometryJson = geometryJson,
        )
        val evidence = MovementEvidence(
            movement = movement,
            recording = recording,
            observation = ObservationContext(movement.movementId, nearerSide = BodySide.RIGHT),
            labels = emptyList(),
            events = emptyList(),
            analyses = listOf(analysis),
            measurements = emptyList(),
            landmarkTracks = emptyList(),
        )

        // Supply frames to verify arm skeleton extraction alongside persisted rays
        val shoulder = Point3(0.5f, 0.4f, 0.0f)
        val elbow = Point3(0.65f, 0.42f, 0.0f)
        val wrist = Point3(0.85f, 0.45f, 0.0f)
        val frame = PoseFrame(
            timestampMs = 1000L,
            landmarks = mapOf(
                PoseLandmarkId.RIGHT_SHOULDER to PoseLandmarkSample(position = shoulder, visibility = 0.9f, presence = 0.9f, source = LandmarkSource.OBSERVED),
                PoseLandmarkId.RIGHT_ELBOW to PoseLandmarkSample(position = elbow, visibility = 0.9f, presence = 0.9f, source = LandmarkSource.OBSERVED),
                PoseLandmarkId.RIGHT_WRIST to PoseLandmarkSample(position = wrist, visibility = 0.9f, presence = 0.9f, source = LandmarkSource.OBSERVED),
            )
        )

        val presentation = MovementPresentationMapper.map(
            evidence,
            displayedNumber = 1,
            frames = listOf(frame),
        )

        val overlay = presentation.overlayDefinition
        assertNotNull(overlay)
        // Verify active arm extracted from impact frame
        val arm = overlay.arm
        assertNotNull(arm)
        assertEquals(0.5f, arm.shoulder.x, 0.001f)
        assertEquals(0.4f, arm.shoulder.y, 0.001f)
        assertEquals(0.65f, arm.elbow.x, 0.001f)
        assertEquals(0.85f, arm.wrist.x, 0.001f)

        // Verify rays parsed from persisted geometryJson
        assertEquals(3, overlay.rays.size)
        val chudanRay = overlay.rays.first { it.targetType == "CHUDAN" }
        assertEquals(0.5f, chudanRay.origin.x, 0.001f)
        assertNotNull(chudanRay.targetPoint)
        assertEquals(0.85f, chudanRay.targetPoint!!.x, 0.001f)
        assertEquals(0.45f, chudanRay.targetPoint!!.y, 0.001f)
        assertTrue(chudanRay.isClosest)

        val jodanRay = overlay.rays.first { it.targetType == "JODAN" }
        assertNotNull(jodanRay.targetPoint)
        assertEquals(0.8f, jodanRay.targetPoint!!.x, 0.001f)
        assertEquals(0.25f, jodanRay.targetPoint!!.y, 0.001f)
        assertFalse(jodanRay.isClosest)

        val gedanRay = overlay.rays.first { it.targetType == "GEDAN" }
        assertNotNull(gedanRay.targetPoint)
        assertEquals(0.75f, gedanRay.targetPoint!!.x, 0.001f)
        assertEquals(0.65f, gedanRay.targetPoint!!.y, 0.001f)
        assertFalse(gedanRay.isClosest)
    }

    @Test
    fun absentGeometryJsonProducesEmptyRays() {
        val movement = SessionMovement(
            sessionId = "session_123",
            startUs = 500_000L,
            endUs = 1_500_000L,
            playbackStartUs = 200_000L,
            playbackEndUs = 1_800_000L,
            segmentationSource = "qom_segmenter",
            segmentationVersion = "1",
            analysisFrameUs = 1_000_000L,
        )
        val recording = MasterRecording(
            sessionId = "session_123",
            filePath = "recordings/video.mp4",
            createdAtMs = 1000L,
        )
        val analysis = MovementAnalysis(
            movementId = movement.movementId,
            analyzerKey = "straight_punch_target",
            analyzerVersion = "1",
            landmarkTrackId = "track_1",
            state = AnalysisState.COMPLETED,
            geometryJson = null,
        )
        val evidence = MovementEvidence(
            movement = movement,
            recording = recording,
            observation = ObservationContext(movement.movementId, nearerSide = BodySide.RIGHT),
            labels = emptyList(),
            events = emptyList(),
            analyses = listOf(analysis),
            measurements = emptyList(),
            landmarkTracks = emptyList(),
        )
        val presentation = MovementPresentationMapper.map(evidence, displayedNumber = 1)
        assertNull(presentation.overlayDefinition)
    }

    @Test
    fun landscapeRecordingRespectsCanonicalAspectRatioAndEmitsCompleteDiagnostics() {
        val movement = SessionMovement(
            sessionId = "session_landscape",
            startUs = 1_000_000L,
            endUs = 2_000_000L,
            playbackStartUs = 800_000L,
            playbackEndUs = 2_200_000L,
            segmentationSource = "qom_segmenter",
            segmentationVersion = "2.1",
            analysisFrameUs = 1_500_000L,
        )
        // Canonical landscape recording dimensions: 1920x1080
        val recording = MasterRecording(
            sessionId = "session_landscape",
            filePath = "recordings/landscape.mp4",
            createdAtMs = 1000L,
            width = 1920,
            height = 1080,
            canonicalGeometryJson = CanonicalGeometryCodec.encode(landscapeDescriptor("session_landscape")),
        )
        val analysis = MovementAnalysis(
            movementId = movement.movementId,
            analyzerKey = "straight_punch_target",
            analyzerVersion = "1",
            landmarkTrackId = "track_landscape",
            state = AnalysisState.COMPLETED,
        )
        val measurements = listOf(
            MeasurementResult(
                analysisId = analysis.analysisId,
                measurementKey = TrainingMeasurements.PUNCH_CLOSEST_TARGET,
                calculationVersion = "1",
                valueType = ValueType.CATEGORICAL,
                categoricalValue = "CHUDAN",
                side = BodySide.RIGHT,
                state = ResultState.VALID,
                occurrenceUs = 1_500_000L,
            )
        )
        val evidence = MovementEvidence(
            movement = movement,
            recording = recording,
            observation = ObservationContext(movement.movementId, nearerSide = BodySide.RIGHT),
            labels = emptyList(),
            events = emptyList(),
            analyses = listOf(analysis),
            measurements = measurements,
            landmarkTracks = emptyList(),
        )

        // Torso with dx = 0.3, dy = 0.4:
        // In landscape (16/9), dx_aspect = 0.3 * (16/9) = 0.5333, dy = 0.4 -> length = sqrt(0.5333^2 + 0.4^2) = 0.6667
        // If mistakenly evaluated with portrait (9/16), length would be 0.4341
        val frame = PoseFrame(
            timestampMs = 1500L,
            landmarks = mapOf(
                PoseLandmarkId.LEFT_SHOULDER to PoseLandmarkSample(position = Point3(0.45f, 0.2f, 0f), visibility = 0.95f, presence = 0.95f),
                PoseLandmarkId.RIGHT_SHOULDER to PoseLandmarkSample(position = Point3(0.55f, 0.2f, 0f), visibility = 0.95f, presence = 0.95f),
                PoseLandmarkId.LEFT_HIP to PoseLandmarkSample(position = Point3(0.15f, 0.6f, 0f), visibility = 0.95f, presence = 0.95f),
                PoseLandmarkId.RIGHT_HIP to PoseLandmarkSample(position = Point3(0.25f, 0.6f, 0f), visibility = 0.95f, presence = 0.95f),
                PoseLandmarkId.LEFT_EAR to PoseLandmarkSample(position = Point3(0.48f, 0.1f, 0f), visibility = 0.95f, presence = 0.95f),
                PoseLandmarkId.RIGHT_EAR to PoseLandmarkSample(position = Point3(0.52f, 0.1f, 0f), visibility = 0.95f, presence = 0.95f),
                PoseLandmarkId.NOSE to PoseLandmarkSample(position = Point3(0.5f, 0.12f, 0f), visibility = 0.95f, presence = 0.95f),
                PoseLandmarkId.MOUTH_LEFT to PoseLandmarkSample(position = Point3(0.48f, 0.15f, 0f), visibility = 0.95f, presence = 0.95f),
                PoseLandmarkId.MOUTH_RIGHT to PoseLandmarkSample(position = Point3(0.52f, 0.15f, 0f), visibility = 0.95f, presence = 0.95f),
                PoseLandmarkId.RIGHT_ELBOW to PoseLandmarkSample(position = Point3(0.6f, 0.3f, 0f), visibility = 0.95f, presence = 0.95f),
                PoseLandmarkId.RIGHT_WRIST to PoseLandmarkSample(position = Point3(0.7f, 0.35f, 0f), visibility = 0.95f, presence = 0.95f),
            ),
        )

        val presentation = MovementPresentationMapper.map(
            evidence = evidence,
            displayedNumber = 1,
            frames = listOf(frame),
        )

        val debug = presentation.debugData
        val bh = debug.bodyHeightDebug
        assertNotNull(bh)

        // Verify aspect ratio calculation: torso length must be 0.6667, NOT 0.4341
        assertEquals(0.6667f, bh.currentTorsoLength ?: 0f, 0.002f)

        // Verify Section 48 diagnostic items
        assertEquals(1_500_000L, bh.selectedTimestampUs)
        assertEquals(1, bh.requestedRadius)
        assertEquals(listOf(1_500_000L), bh.torsoContributingTimestampsUs)
        assertEquals(listOf(1_500_000L), bh.headContributingTimestampsUs)
        assertEquals(1, bh.torsoUsableCount)
        assertEquals(1, bh.headUsableCount)
        assertEquals("VALID", bh.chudanStatus)
        assertEquals("VALID", bh.gedanStatus)
        assertEquals("VALID", bh.jodanStatus)
        assertEquals("CHUDAN_TORSO_RATIO_045_V1", bh.chudanEstimatorId)
        assertEquals("GEDAN_TORSO_RATIO_080_V1", bh.gedanEstimatorId)
        assertEquals("JODAN_MOUTH_NOSE_110_V1", bh.jodanEstimatorId)
        assertEquals(1, bh.perSampleObservations.size)
        assertEquals(1_500_000L, bh.perSampleObservations[0].timestampUs)
        assertTrue(bh.perSampleObservations[0].isTorsoValid)
        assertTrue(bh.perSampleObservations[0].isHeadValid)

        // Verify overlay frame geometry matches recording
        val overlay = presentation.overlayDefinition
        assertNotNull(overlay)
        assertEquals(1920, overlay.frameGeometry.sourceWidth)
        assertEquals(1080, overlay.frameGeometry.sourceHeight)
    }

    @Test
    fun straightPunchTargetAnalysisRotated90DegreesAppliesPersistedRotation() {
        val movement = SessionMovement(
            sessionId = "session_rot_90",
            startUs = 1_000_000L,
            endUs = 2_000_000L,
            playbackStartUs = 800_000L,
            playbackEndUs = 2_200_000L,
            segmentationSource = "qom_segmenter",
            segmentationVersion = "2.1",
            analysisFrameUs = 1_500_000L,
            runId = "run_90",
        )
        // Stored encoded dimensions 1920x1080 tagged 90 degrees
        val desc90 = CanonicalGeometryDescriptor(
            geometryId = "geom_90",
            recordingId = "session_rot_90",
            canonicalWidth = 1080,
            canonicalHeight = 1920,
            canonicalOrientation = CanonicalOrientation.UPRIGHT_UNMIRRORED,
            sourceToCanonicalTransform = SourceToCanonicalTransform(90, false, order = TransformOrder.ROTATION_THEN_MIRROR),
            encodedWidth = 1920,
            encodedHeight = 1080,
            containerRotation = 90,
        )
        val recording = MasterRecording(
            sessionId = "session_rot_90",
            filePath = "recordings/video_90.mp4",
            createdAtMs = 1000L,
            width = 1920,
            height = 1080,
            rotation = 90,
            canonicalGeometryJson = CanonicalGeometryCodec.encode(desc90),
        )
        val analysis = MovementAnalysis(
            movementId = movement.movementId,
            analyzerKey = StraightPunchMovementAdapter.policy.analyzerKey,
            analyzerVersion = "1",
            landmarkTrackId = "track_90",
            state = AnalysisState.COMPLETED,
        )
        val measurements = listOf(
            MeasurementResult(
                analysisId = analysis.analysisId,
                measurementKey = TrainingMeasurements.PUNCH_CLOSEST_TARGET,
                calculationVersion = "1",
                valueType = ValueType.CATEGORICAL,
                categoricalValue = "CHUDAN",
                side = BodySide.RIGHT,
                state = ResultState.VALID,
                occurrenceUs = 1_500_000L,
            )
        )
        val evidence = MovementEvidence(
            movement = movement,
            recording = recording,
            observation = ObservationContext(movement.movementId, nearerSide = BodySide.RIGHT),
            labels = emptyList(),
            events = emptyList(),
            analyses = listOf(analysis),
            measurements = measurements,
            landmarkTracks = emptyList(),
        )

        // Torso with dx = 0.3, dy = 0.4:
        // When rotated 90 degrees, canonical geometry is 1080x1920 (aspect 9/16 = 0.5625)
        // dx_aspect = 0.3 * (9/16) = 0.16875, dy = 0.4 -> length = sqrt(0.16875^2 + 0.4^2) = 0.4341
        // Without rotation correction, it would mistakenly calculate landscape 0.6667
        val frame = PoseFrame(
            timestampMs = 1500L,
            landmarks = mapOf(
                PoseLandmarkId.LEFT_SHOULDER to PoseLandmarkSample(position = Point3(0.45f, 0.2f, 0f), visibility = 0.95f, presence = 0.95f),
                PoseLandmarkId.RIGHT_SHOULDER to PoseLandmarkSample(position = Point3(0.55f, 0.2f, 0f), visibility = 0.95f, presence = 0.95f),
                PoseLandmarkId.LEFT_HIP to PoseLandmarkSample(position = Point3(0.15f, 0.6f, 0f), visibility = 0.95f, presence = 0.95f),
                PoseLandmarkId.RIGHT_HIP to PoseLandmarkSample(position = Point3(0.25f, 0.6f, 0f), visibility = 0.95f, presence = 0.95f),
                PoseLandmarkId.LEFT_EAR to PoseLandmarkSample(position = Point3(0.48f, 0.1f, 0f), visibility = 0.95f, presence = 0.95f),
                PoseLandmarkId.RIGHT_EAR to PoseLandmarkSample(position = Point3(0.52f, 0.1f, 0f), visibility = 0.95f, presence = 0.95f),
                PoseLandmarkId.NOSE to PoseLandmarkSample(position = Point3(0.5f, 0.12f, 0f), visibility = 0.95f, presence = 0.95f),
                PoseLandmarkId.MOUTH_LEFT to PoseLandmarkSample(position = Point3(0.48f, 0.15f, 0f), visibility = 0.95f, presence = 0.95f),
                PoseLandmarkId.MOUTH_RIGHT to PoseLandmarkSample(position = Point3(0.52f, 0.15f, 0f), visibility = 0.95f, presence = 0.95f),
                PoseLandmarkId.RIGHT_ELBOW to PoseLandmarkSample(position = Point3(0.6f, 0.3f, 0f), visibility = 0.95f, presence = 0.95f),
                PoseLandmarkId.RIGHT_WRIST to PoseLandmarkSample(position = Point3(0.7f, 0.35f, 0f), visibility = 0.95f, presence = 0.95f),
            ),
        )

        val presentation = MovementPresentationMapper.map(
            evidence = evidence,
            displayedNumber = 1,
            frames = listOf(frame),
        )

        val debug = presentation.debugData
        val bh = debug.bodyHeightDebug
        assertNotNull(bh)

        // Torso length must be 0.4341 (portrait aspect corrected), NOT 0.6667 (landscape)
        assertEquals(0.4341f, bh.currentTorsoLength ?: 0f, 0.002f)

        // Overlay frame geometry must be canonical 1080x1920
        val overlay = presentation.overlayDefinition
        assertNotNull(overlay)
        assertEquals(1080, overlay.frameGeometry.sourceWidth)
        assertEquals(1920, overlay.frameGeometry.sourceHeight)
    }

    @Test
    fun straightPunchTargetAnalysisRotated270DegreesAppliesPersistedRotation() {
        val movement = SessionMovement(
            sessionId = "session_rot_270",
            startUs = 1_000_000L,
            endUs = 2_000_000L,
            playbackStartUs = 800_000L,
            playbackEndUs = 2_200_000L,
            segmentationSource = "qom_segmenter",
            segmentationVersion = "2.1",
            analysisFrameUs = 1_500_000L,
            runId = "run_270",
        )
        // Stored encoded dimensions 1920x1080 tagged 270 degrees
        val desc270 = CanonicalGeometryDescriptor(
            geometryId = "geom_270",
            recordingId = "session_rot_270",
            canonicalWidth = 1080,
            canonicalHeight = 1920,
            canonicalOrientation = CanonicalOrientation.UPRIGHT_UNMIRRORED,
            sourceToCanonicalTransform = SourceToCanonicalTransform(270, false, order = TransformOrder.ROTATION_THEN_MIRROR),
            encodedWidth = 1920,
            encodedHeight = 1080,
            containerRotation = 270,
        )
        val recording = MasterRecording(
            sessionId = "session_rot_270",
            filePath = "recordings/video_270.mp4",
            createdAtMs = 1000L,
            width = 1920,
            height = 1080,
            rotation = 270,
            canonicalGeometryJson = CanonicalGeometryCodec.encode(desc270),
        )
        val analysis = MovementAnalysis(
            movementId = movement.movementId,
            analyzerKey = StraightPunchMovementAdapter.policy.analyzerKey,
            analyzerVersion = "1",
            landmarkTrackId = "track_270",
            state = AnalysisState.COMPLETED,
        )
        val measurements = listOf(
            MeasurementResult(
                analysisId = analysis.analysisId,
                measurementKey = TrainingMeasurements.PUNCH_CLOSEST_TARGET,
                calculationVersion = "1",
                valueType = ValueType.CATEGORICAL,
                categoricalValue = "CHUDAN",
                side = BodySide.RIGHT,
                state = ResultState.VALID,
                occurrenceUs = 1_500_000L,
            )
        )
        val evidence = MovementEvidence(
            movement = movement,
            recording = recording,
            observation = ObservationContext(movement.movementId, nearerSide = BodySide.RIGHT),
            labels = emptyList(),
            events = emptyList(),
            analyses = listOf(analysis),
            measurements = measurements,
            landmarkTracks = emptyList(),
        )

        val frame = PoseFrame(
            timestampMs = 1500L,
            landmarks = mapOf(
                PoseLandmarkId.LEFT_SHOULDER to PoseLandmarkSample(position = Point3(0.45f, 0.2f, 0f), visibility = 0.95f, presence = 0.95f),
                PoseLandmarkId.RIGHT_SHOULDER to PoseLandmarkSample(position = Point3(0.55f, 0.2f, 0f), visibility = 0.95f, presence = 0.95f),
                PoseLandmarkId.LEFT_HIP to PoseLandmarkSample(position = Point3(0.15f, 0.6f, 0f), visibility = 0.95f, presence = 0.95f),
                PoseLandmarkId.RIGHT_HIP to PoseLandmarkSample(position = Point3(0.25f, 0.6f, 0f), visibility = 0.95f, presence = 0.95f),
                PoseLandmarkId.LEFT_EAR to PoseLandmarkSample(position = Point3(0.48f, 0.1f, 0f), visibility = 0.95f, presence = 0.95f),
                PoseLandmarkId.RIGHT_EAR to PoseLandmarkSample(position = Point3(0.52f, 0.1f, 0f), visibility = 0.95f, presence = 0.95f),
                PoseLandmarkId.NOSE to PoseLandmarkSample(position = Point3(0.5f, 0.12f, 0f), visibility = 0.95f, presence = 0.95f),
                PoseLandmarkId.MOUTH_LEFT to PoseLandmarkSample(position = Point3(0.48f, 0.15f, 0f), visibility = 0.95f, presence = 0.95f),
                PoseLandmarkId.MOUTH_RIGHT to PoseLandmarkSample(position = Point3(0.52f, 0.15f, 0f), visibility = 0.95f, presence = 0.95f),
                PoseLandmarkId.RIGHT_ELBOW to PoseLandmarkSample(position = Point3(0.6f, 0.3f, 0f), visibility = 0.95f, presence = 0.95f),
                PoseLandmarkId.RIGHT_WRIST to PoseLandmarkSample(position = Point3(0.7f, 0.35f, 0f), visibility = 0.95f, presence = 0.95f),
            ),
        )

        val presentation = MovementPresentationMapper.map(
            evidence = evidence,
            displayedNumber = 1,
            frames = listOf(frame),
        )

        val debug = presentation.debugData
        val bh = debug.bodyHeightDebug
        assertNotNull(bh)

        assertEquals(0.4341f, bh.currentTorsoLength ?: 0f, 0.002f)

        val overlay = presentation.overlayDefinition
        assertNotNull(overlay)
        assertEquals(1080, overlay.frameGeometry.sourceWidth)
        assertEquals(1920, overlay.frameGeometry.sourceHeight)
    }

    @Test
    fun resolveCanonicalFrameGeometryMatrix() {
        // Track descriptor with 90 degrees rotation -> 1080x1920
        val trackDesc90 = CanonicalGeometryDescriptor(
            geometryId = "geom_track_90",
            recordingId = "rec_matrix",
            landmarkTrackId = "track_90",
            canonicalWidth = 1080,
            canonicalHeight = 1920,
            sourceToCanonicalTransform = SourceToCanonicalTransform(90, false, order = TransformOrder.ROTATION_THEN_MIRROR),
        )
        val track90 = LandmarkTrack(
            landmarkTrackId = "track_90",
            recordingId = "rec_matrix",
            pipelineKey = "pipe",
            pipelineVersion = "1",
            configuration = "cfg",
            filePath = "tracks/90.mls",
            canonicalGeometryJson = CanonicalGeometryCodec.encode(trackDesc90),
        )
        val recording = MasterRecording(
            recordingId = "rec_matrix",
            sessionId = "s_matrix",
            filePath = "",
            createdAtMs = 0,
        )
        val g90 = MovementPresentationMapper.resolveCanonicalFrameGeometry(
            recording = recording,
            track = track90,
        )
        assertNotNull(g90)
        assertEquals(1080, g90.sourceWidth)
        assertEquals(1920, g90.sourceHeight)

        // Recording descriptor with 270 degrees rotation -> 1080x1920
        val recDesc270 = CanonicalGeometryDescriptor(
            geometryId = "geom_rec_270",
            recordingId = "rec_270",
            canonicalWidth = 1080,
            canonicalHeight = 1920,
            sourceToCanonicalTransform = SourceToCanonicalTransform(270, false, order = TransformOrder.ROTATION_THEN_MIRROR),
        )
        val g270 = MovementPresentationMapper.resolveCanonicalFrameGeometry(
            recording = MasterRecording(
                recordingId = "rec_270",
                sessionId = "s_270",
                filePath = "",
                createdAtMs = 0,
                canonicalGeometryJson = CanonicalGeometryCodec.encode(recDesc270),
            ),
        )
        assertNotNull(g270)
        assertEquals(1080, g270.sourceWidth)
        assertEquals(1920, g270.sourceHeight)

        // Recording descriptor with 0 degrees rotation (landscape) -> 1920x1080
        val recDesc0 = CanonicalGeometryDescriptor(
            geometryId = "geom_rec_0",
            recordingId = "rec_0",
            canonicalWidth = 1920,
            canonicalHeight = 1080,
            sourceToCanonicalTransform = SourceToCanonicalTransform(0, false, order = TransformOrder.ROTATION_THEN_MIRROR),
        )
        val g0 = MovementPresentationMapper.resolveCanonicalFrameGeometry(
            recording = MasterRecording(
                recordingId = "rec_0",
                sessionId = "s_0",
                filePath = "",
                createdAtMs = 0,
                canonicalGeometryJson = CanonicalGeometryCodec.encode(recDesc0),
            ),
        )
        assertNotNull(g0)
        assertEquals(1920, g0.sourceWidth)
        assertEquals(1080, g0.sourceHeight)

        // Without descriptor and without video file, returns null (UNKNOWN_GEOMETRY without guessing)
        val gUnknown = MovementPresentationMapper.resolveCanonicalFrameGeometry(
            recording = MasterRecording(
                recordingId = "rec_unknown",
                sessionId = "s_unknown",
                filePath = "",
                createdAtMs = 0,
                width = 1080,
                height = 1920,
                rotation = 90,
            ),
        )
        assertNull(gUnknown, "Must return null without inventing 1080x1920 or guessing orientation")
    }
}
