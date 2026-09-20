package dk.lasse.karatecliprecorder.movement

import dk.lasse.karateanalyzer.core.*
import dk.lasse.karatecliprecorder.training.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import kotlin.test.*

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28])
class MovementPresentationMapperTest {

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
}
