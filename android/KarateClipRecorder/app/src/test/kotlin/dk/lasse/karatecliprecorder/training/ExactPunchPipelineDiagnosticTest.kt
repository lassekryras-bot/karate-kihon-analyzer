package dk.lasse.karatecliprecorder.training

import dk.lasse.karateanalyzer.capture.LateralSide
import dk.lasse.karateanalyzer.capture.qom.ExtremityPointComposer
import dk.lasse.karateanalyzer.capture.qom.MotionBodyProfile
import dk.lasse.karateanalyzer.capture.qom.QomFrameEvidence
import dk.lasse.karateanalyzer.core.ActiveArm
import dk.lasse.karateanalyzer.core.BodyReference
import dk.lasse.karateanalyzer.core.LandmarkSource
import dk.lasse.karateanalyzer.core.Point3
import dk.lasse.karateanalyzer.core.PoseFrame
import dk.lasse.karateanalyzer.core.PoseLandmarkId
import dk.lasse.karateanalyzer.core.PoseLandmarkSample
import dk.lasse.karateanalyzer.core.PunchHeightAnalyzer
import dk.lasse.karateanalyzer.core.SideViewChinEstimator
import dk.lasse.karateanalyzer.core.StraightPunchTargetCalculator
import dk.lasse.karateanalyzer.core.TrackedPoseFrame
import dk.lasse.karateanalyzer.core.VisibleSide
import dk.lasse.karateanalyzer.geometry.AspectCorrectPoint
import dk.lasse.karateanalyzer.geometry.CanonicalGeometryCodec
import dk.lasse.karateanalyzer.geometry.FrameGeometry
import dk.lasse.karateanalyzer.geometry.FrameGeometryMath
import dk.lasse.karateanalyzer.geometry.SourceNormalizedPoint
import dk.lasse.karateanalyzer.impact.BodyScaleEvidence
import dk.lasse.karateanalyzer.impact.ImpactAnalysisProfile
import dk.lasse.karateanalyzer.impact.ImpactAnalyzer
import dk.lasse.karateanalyzer.impact.ImpactLimbFamily
import dk.lasse.karateanalyzer.impact.ImpactMovementInput
import dk.lasse.karateanalyzer.impact.WeaponPointDefinition
import dk.lasse.karateanalyzer.motion.CausalCoordinateMotionFilter
import dk.lasse.karateanalyzer.motion.FourLimbMotionCharacterizer
import dk.lasse.karateanalyzer.motion.FourLimbMotionInput
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File
import kotlin.math.abs
import kotlin.math.sqrt
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Isolated diagnostics for the unresolved straight-punch side/view semantics.
 * Nothing in this file is called by production code. Exact-recording replay is opt-in and private.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28])
class ExactPunchPipelineDiagnosticTest {
    private data class ExperimentalConfig(
        val version: String = "episode_concurrent_distal_v1_experimental",
        val minimumConfidence: Float = 0.55f,
        val minimumCoverage: Double = 0.60,
        val minimumSamples: Int = 3,
        val maximumGapUs: Long = 100_000L,
        val bodyHeightRatioSweep: List<Double> = listOf(0.05, 0.10, 0.15),
    )

    private data class EpisodeEvidence(
        val eligible: Boolean,
        val validSamples: Int,
        val boundedSamples: Int,
        val coverage: Double,
        val maximumGapUs: Long?,
        val minimumConfidence: Double?,
        val signedSourceXNet: Double?,
        val signedSourceXMaximumExcursion: Double?,
        val signedBodyHeightRatioNet: Double?,
        val radialAspectNet: Double?,
        val radialBodyHeightRatioNet: Double?,
        val identityStatus: String = "ANATOMICAL_LANDMARK_LABEL_WITHOUT_INDEPENDENT_CONTINUITY_PROOF",
        val reason: String? = null,
    )

    private val config = ExperimentalConfig()

    @Test
    fun `experimental evaluator exposes invariants and counterexamples`() {
        val portrait = FrameGeometry(1080, 1920)
        val sameAspectSmallerFrame = FrameGeometry(720, 1280)
        val landscape = FrameGeometry(1920, 1080)
        val base = syntheticEpisode(
            wrist = listOf(.40 to .50, .30 to .50, .20 to .50),
            anchor = listOf(.50 to .50, .50 to .50, .50 to .50),
        )
        val translated = syntheticEpisode(
            wrist = listOf(.50 to .60, .40 to .60, .30 to .60),
            anchor = listOf(.60 to .60, .60 to .60, .60 to .60),
        )
        val reflected = syntheticEpisode(
            wrist = listOf(.60 to .50, .70 to .50, .80 to .50),
            anchor = listOf(.50 to .50, .50 to .50, .50 to .50),
        )
        val baseEvidence = evaluate(base, portrait, 0L, 80_000L, .50)
        val translatedEvidence = evaluate(translated, portrait, 0L, 80_000L, .50)
        val reflectedEvidence = evaluate(reflected, portrait, 0L, 80_000L, .50)
        val sameAspectEvidence = evaluate(base, sameAspectSmallerFrame, 0L, 80_000L, .50)
        val landscapeEvidence = evaluate(base, landscape, 0L, 80_000L, .50)

        assertEquals(baseEvidence.signedSourceXNet!!, translatedEvidence.signedSourceXNet!!, 1e-6)
        assertEquals(baseEvidence.radialAspectNet!!, translatedEvidence.radialAspectNet!!, 1e-6)
        assertEquals(baseEvidence.signedSourceXNet, sameAspectEvidence.signedSourceXNet)
        assertEquals(baseEvidence.radialAspectNet, sameAspectEvidence.radialAspectNet)
        assertEquals(-baseEvidence.signedSourceXNet!!, reflectedEvidence.signedSourceXNet!!, 1e-6)
        assertEquals(baseEvidence.radialAspectNet!!, reflectedEvidence.radialAspectNet!!, 1e-6)
        assertEquals(baseEvidence.signedSourceXNet, landscapeEvidence.signedSourceXNet)
        assertTrue(landscapeEvidence.radialAspectNet!! > baseEvidence.radialAspectNet!!)

        val scaledSubject = syntheticEpisode(
            wrist = listOf(.45 to .50, .40 to .50, .35 to .50),
            anchor = listOf(.50 to .50, .50 to .50, .50 to .50),
        )
        val scaledEvidence = evaluate(scaledSubject, portrait, 0L, 80_000L, .25)
        assertEquals(baseEvidence.signedBodyHeightRatioNet!!, scaledEvidence.signedBodyHeightRatioNet!!, 1e-6)
        assertEquals(baseEvidence.radialBodyHeightRatioNet!!, scaledEvidence.radialBodyHeightRatioNet!!, 1e-6)

        val extensionReturn = syntheticEpisode(
            wrist = listOf(.40 to .50, .20 to .50, .40 to .50),
            anchor = listOf(.50 to .50, .50 to .50, .50 to .50),
        )
        val extensionReturnEvidence = evaluate(extensionReturn, portrait, 0L, 80_000L, .50)
        assertEquals(0.0, extensionReturnEvidence.signedSourceXNet!!, 1e-6)
        assertTrue(extensionReturnEvidence.signedSourceXMaximumExcursion!! > 0.0)

        val movingAnchor = syntheticEpisode(
            wrist = listOf(.40 to .50, .40 to .50, .40 to .50),
            anchor = listOf(.50 to .50, .55 to .50, .60 to .50),
        )
        assertTrue(abs(evaluate(movingAnchor, portrait, 0L, 80_000L, .50).signedSourceXNet!!) > 0.0)

        val missingMiddle = base.mapIndexed { index, frame ->
            if (index == 1) frame.copy(landmarks = frame.landmarks - PoseLandmarkId.LEFT_WRIST) else frame
        }
        assertFalse(evaluate(missingMiddle, portrait, 0L, 80_000L, .50).eligible)

        val staticFarHand = syntheticEpisode(
            wrist = listOf(.80 to .50, .80 to .50, .80 to .50),
            anchor = listOf(.50 to .50, .50 to .50, .50 to .50),
            wristId = PoseLandmarkId.RIGHT_WRIST,
        )
        assertEquals(0.0, evaluate(staticFarHand, portrait, 0L, 80_000L, .50, LateralSide.RIGHT).radialAspectNet!!, 1e-6)
    }

    @Test
    fun `retained reference candidate keeps metric dependencies independent`() {
        exactGeometryForDiagnostic = FrameGeometry(1080, 1920)
        exactFramesForDiagnostic = (0L..1_600L step 50L).map(::retainedFixtureFrame)
        val withoutFarArm = retainedReferenceDiagnostic(1_500_000L, 1_600_000L, ActiveArm.LEFT)
        assertEquals("AVAILABLE", withoutFarArm.getString("status"))
        assertTrue(withoutFarArm.has("elbowAngleDeg") && !withoutFarArm.isNull("elbowAngleDeg"))

        exactFramesForDiagnostic = (0L..1_600L step 50L).map { retainedFixtureFrame(it, includeHead = false) }
        val withoutHead = retainedReferenceDiagnostic(1_500_000L, 1_600_000L, ActiveArm.LEFT)
        assertEquals("CHIN_EVIDENCE_UNAVAILABLE", withoutHead.getString("chinSource"))
        val headlessTargets = withoutHead.getJSONArray("targets")
        assertEquals("ABSTAINED", (0 until headlessTargets.length())
            .map { headlessTargets.getJSONObject(it) }.single { it.getString("target") == "JODAN" }.getString("state"))
        assertEquals("VALID", (0 until headlessTargets.length())
            .map { headlessTargets.getJSONObject(it) }.single { it.getString("target") == "CHUDAN" }.getString("state"))

        exactFramesForDiagnostic = (0L..1_600L step 50L).map { retainedFixtureFrame(it, includeRightHip = false) }
        assertEquals("UNAVAILABLE", retainedReferenceDiagnostic(1_500_000L, 1_600_000L, ActiveArm.LEFT).getString("status"))

        exactFramesForDiagnostic = (0L..1_600L step 50L).map { time ->
            retainedFixtureFrame(time, includeLeftWrist = time != 1_600L)
        }
        val missingActiveArm = retainedReferenceDiagnostic(1_500_000L, 1_600_000L, ActiveArm.LEFT)
        assertEquals("ABSTAINED", missingActiveArm.getString("evaluationState"))
        assertEquals("missing_required_arm_landmarks", missingActiveArm.getString("evaluationReason"))

        exactFramesForDiagnostic = listOf(retainedFixtureFrame(1_600L))
        assertEquals("UNAVAILABLE", retainedReferenceDiagnostic(1_500_000L, 1_600_000L, ActiveArm.LEFT).getString("status"))
    }

    @Test
    fun `retained reference window brackets required duration on nonuniform timestamps`() {
        exactGeometryForDiagnostic = FrameGeometry(1080, 1920)
        exactFramesForDiagnostic = listOf(
            0L, 137L, 281L, 419L, 563L, 708L,
            856L, 1_003L, 1_154L, 1_307L, 1_461L, 1_618L,
        ).map(::retainedFixtureFrame)

        val result = retainedReferenceDiagnostic(1_618_000L, 1_618_000L, ActiveArm.LEFT)

        assertEquals("AVAILABLE", result.getString("status"))
        assertEquals(0L, result.getLong("snapshotStartUs"))
        assertEquals(1_618_000L, result.getLong("snapshotEndUs"))
        assertEquals(12, result.getInt("sampleCount"))
    }

    @Test
    fun `weapon only terminal probe rejects a guard return and selects only the final stable suffix`() {
        val geometry = FrameGeometry(1080, 1920)
        val extensionWithPause = listOf(.42, .34, .26, .26, .26, .18, .12, .12, .12, .12, .12, .12, .12, .12)
            .mapIndexed { index, handX -> handTerminalFixtureFrame(index * 40L, handX) }
        val completed = selectedHandTerminalProbe(
            extensionWithPause, geometry, 0L, 520_000L, 0.50, LateralSide.LEFT,
        )
        assertEquals("OBSERVED_APPROACH_DECELERATION_STABLE_ENDPOINT", completed.status)
        assertTrue(requireNotNull(completed.stableSuffixStartUs) >= 200_000L)

        val returnToGuard = listOf(.42, .32, .22, .12, .22, .32, .42, .42, .42, .42, .42, .42, .42, .42)
            .mapIndexed { index, handX -> handTerminalFixtureFrame(index * 40L, handX) }
        val returned = selectedHandTerminalProbe(
            returnToGuard, geometry, 0L, 520_000L, 0.50, LateralSide.LEFT,
        )
        assertEquals("ENDPOINT_NOT_AT_MAXIMUM_EXTENSION", returned.status)
    }

    @Test
    fun `weapon only terminal probe is torso translation invariant and resets on roster loss`() {
        val geometry = FrameGeometry(1080, 1920)
        val hand = listOf(.42, .34, .26, .18, .12, .12, .12, .12)
        val base = hand.mapIndexed { index, x -> handTerminalFixtureFrame(index * 40L, x) }
        val translated = hand.mapIndexed { index, x ->
            handTerminalFixtureFrame(index * 40L, x + .10, torsoTranslationX = .10)
        }
        val baseResult = selectedHandTerminalProbe(base, geometry, 0L, 280_000L, 0.50, LateralSide.LEFT)
        val translatedResult = selectedHandTerminalProbe(translated, geometry, 0L, 280_000L, 0.50, LateralSide.LEFT)
        assertEquals(baseResult.status, translatedResult.status)
        assertEquals(
            requireNotNull(baseResult.outwardExcursionBodyHeightRatio),
            requireNotNull(translatedResult.outwardExcursionBodyHeightRatio),
            1e-6,
        )

        val rosterLoss = base.mapIndexed { index, frame ->
            if (index == 3) frame.copy(landmarks = frame.landmarks - PoseLandmarkId.LEFT_INDEX) else frame
        }
        val reset = selectedHandTerminalProbe(rosterLoss, geometry, 0L, 280_000L, 0.50, LateralSide.LEFT)
        assertEquals("INSUFFICIENT_POST_FILTER_WARMUP_SUPPORT", reset.status)
    }

    @Test
    fun `weapon only terminal probe slow approach classification is stable across sample rates`() {
        val geometry = FrameGeometry(1080, 1920)
        fun fixture(stepMs: Long) = (0L..800L step stepMs).map { timeMs ->
            val handX = if (timeMs <= 400L) .42 - .30 * timeMs / 400.0 else .12
            handTerminalFixtureFrame(timeMs, handX)
        }
        val at25Fps = selectedHandTerminalProbe(fixture(40L), geometry, 0L, 800_000L, .50, LateralSide.LEFT)
        val at50Fps = selectedHandTerminalProbe(fixture(20L), geometry, 0L, 800_000L, .50, LateralSide.LEFT)
        assertEquals("OBSERVED_APPROACH_DECELERATION_STABLE_ENDPOINT", at25Fps.status)
        assertEquals(at25Fps.status, at50Fps.status)
        assertTrue(requireNotNull(at25Fps.outwardExcursionBodyHeightRatio) > .02)
        assertTrue(requireNotNull(at50Fps.outwardExcursionBodyHeightRatio) > .02)
    }

    @Test
    fun `private exact replay reports selected hand terminal feasibility without changing impact semantics`() {
        val inputPath = System.getenv("KARATE_PUNCH_DIAGNOSTIC_JSON")
        val mlsPath = System.getenv("KARATE_PUNCH_DIAGNOSTIC_MLS")
        assumeTrue("Private exact replay inputs were not supplied", !inputPath.isNullOrBlank() && !mlsPath.isNullOrBlank())
        val input = JSONObject(File(inputPath!!).readText())
        val geometry = requireNotNull(CanonicalGeometryCodec.decode(input.getString("geometry"))).toFrameGeometry()
        val frames = LandmarkFiles.read(
            File(mlsPath!!), input.getString("trackHash"), LandmarkFiles.FORMAT_ID,
        )
        val bodyHeight = input.getJSONObject("bodyScale").getDouble("heightAspectCorrect")
        val movements = input.getJSONArray("movements")
        val results = JSONArray()
        for (index in 0 until movements.length()) {
            val movement = movements.getJSONObject(index)
            if (movement.getString("reviewSide") != "RIGHT") continue
            val result = selectedHandTerminalProbe(
                frames = frames,
                geometry = geometry,
                startUs = movement.getLong("startUs"),
                endUs = movement.getLong("endUs"),
                bodyHeightAspectCorrect = bodyHeight,
                side = LateralSide.RIGHT,
                endBoundaryKind = movement.opt("segmentationEndBoundaryKind")
                    .takeUnless { it == null || it == JSONObject.NULL }?.toString(),
            )
            results.put(result.toJson(index + 1, movement))
        }
        val report = JSONObject()
            .put("contract", "selected-hand-terminal-feasibility-v1-diagnostic")
            .put("productionSemantics", "UNCHANGED_IMPACT_ANALYZER_V2_REMAINS_ARTICULATION_REQUIRED")
            .put("movements", results)
        val outputPath = System.getenv("KARATE_PUNCH_HAND_TERMINAL_OUTPUT")
        if (outputPath.isNullOrBlank()) println(report.toString(2)) else File(outputPath).writeText(report.toString(2))
    }

    @Test
    fun `private exact replay exposes impact sampling sensitivity without changing thresholds`() {
        val inputPath = System.getenv("KARATE_PUNCH_DIAGNOSTIC_JSON")
        val mlsPath = System.getenv("KARATE_PUNCH_DIAGNOSTIC_MLS")
        assumeTrue("Private exact replay inputs were not supplied", !inputPath.isNullOrBlank() && !mlsPath.isNullOrBlank())
        val input = JSONObject(File(inputPath!!).readText())
        val geometry = requireNotNull(CanonicalGeometryCodec.decode(input.getString("geometry")))
        val frames = LandmarkFiles.read(File(mlsPath!!), input.getString("trackHash"), LandmarkFiles.FORMAT_ID)
        val scaleJson = input.getJSONObject("bodyScale")
        val bodyScale = BodyScaleEvidence(
            scaleJson.getDouble("heightAspectCorrect"), scaleJson.getString("sourceId"), scaleJson.getString("sourceVersion"),
        )
        val outputs = JSONArray()
        val movements = input.getJSONArray("movements")
        for (index in 0 until movements.length()) {
            val ordinal = index + 1
            if (ordinal !in setOf(3, 5, 7)) continue
            val movement = movements.getJSONObject(index)
            val startUs = movement.getLong("startUs")
            val endUs = movement.getLong("endUs")
            val bounded = frames.filter { it.timestampMs * 1000L in startUs..endUs }
            val qom = movement.getJSONArray("qom").let { values ->
                (0 until values.length()).map { qomIndex ->
                    val value = values.getJSONObject(qomIndex)
                    QomFrameEvidence(
                        timestampMs = value.getLong("timeUs") / 1000L,
                        profile = MotionBodyProfile.PUNCH,
                        blockQom = emptyMap(),
                        aggregateQom = null,
                        rollingArea = value.getDouble("rollingArea"),
                        availableBlockCount = 0,
                        totalActiveBlockCount = 0,
                        isAvailable = true,
                    )
                }
            }
            val variants = JSONArray()
            for (stride in 1..3) {
                val sampled = bounded.filterIndexed { sampleIndex, _ -> sampleIndex % stride == 0 }.toMutableList()
                if (sampled.last() != bounded.last()) sampled += bounded.last()
                val limbs = FourLimbMotionCharacterizer.characterize(FourLimbMotionInput(
                    movementId = movement.getString("movementId"),
                    landmarkTrackId = input.getString("trackId"),
                    logicalStartTimestampUs = startUs,
                    logicalEndTimestampUs = endUs,
                    frames = sampled,
                    canonicalGeometry = geometry,
                    segmenterVersion = movement.getString("segmentationVersion"),
                ))
                val result = ImpactAnalyzer.analyze(ImpactMovementInput(
                    movementId = movement.getString("movementId"),
                    logicalStartTimestampUs = startUs,
                    logicalEndTimestampUs = endUs,
                    frames = sampled,
                    qomTimeline = qom,
                    segmenterVersion = movement.getString("segmentationVersion"),
                    landmarkTrackId = input.getString("trackId"),
                    canonicalGeometry = geometry,
                    bodyScale = bodyScale,
                    profile = ImpactAnalysisProfile(
                        activityProfileId = MotionActivityPlans.ALTERNATING_PUNCH,
                        side = LateralSide.LEFT,
                        weapon = WeaponPointDefinition.COMPOSITE_HAND,
                        limbFamily = ImpactLimbFamily.UPPER_LIMB,
                        approvedViewProfile = "diagnostic-sampling-replay-only",
                    ),
                    limbEvidence = limbs,
                ))
                val transitionPeak = result.debugEvidence.maxByOrNull { it.terminalTransitionScore }
                variants.put(JSONObject()
                    .put("stride", stride)
                    .put("frameCount", sampled.size)
                    .put("minimumIntervalUs", sampled.zipWithNext().minOfOrNull { (a, b) -> (b.timestampMs - a.timestampMs) * 1000L })
                    .put("maximumIntervalUs", sampled.zipWithNext().maxOfOrNull { (a, b) -> (b.timestampMs - a.timestampMs) * 1000L })
                    .put("status", result.status.name)
                    .put("reason", result.abstentionReason?.name)
                    .put("usableSamples", result.quality.usableSampleCount)
                    .put("terminalUs", result.terminalTransitionTimestampUs)
                    .put("stableStartUs", result.stableWindowStartTimestampUs)
                    .put("stableEndUs", result.stableWindowEndTimestampUs)
                    .put("peakTransitionUs", transitionPeak?.timestampUs)
                    .put("peakTransitionScore", transitionPeak?.terminalTransitionScore)
                    .put("stableTimestampsUs", JSONArray(result.debugEvidence.filter { it.stableState }.map { it.timestampUs })))
            }
            outputs.put(JSONObject()
                .put("ordinal", ordinal)
                .put("movementId", movement.getString("movementId"))
                .put("variants", variants))
        }
        val report = JSONObject()
            .put("contract", "impact-sampling-sensitivity-v1-diagnostic")
            .put("resampling", "OBSERVED_FRAME_SUBSAMPLING_ONLY_NO_INTERPOLATION")
            .put("thresholds", "UNCHANGED_IMPACT_CONFIG_V1_PROVISIONAL")
            .put("movements", outputs)
        val outputPath = System.getenv("KARATE_PUNCH_RATE_OUTPUT")
        if (outputPath.isNullOrBlank()) println(report.toString(2)) else File(outputPath).writeText(report.toString(2))
    }

    @Test
    fun `private exact replay produces episode and hypothetical impact diagnostics`() {
        val inputPath = System.getenv("KARATE_PUNCH_DIAGNOSTIC_JSON")
        val mlsPath = System.getenv("KARATE_PUNCH_DIAGNOSTIC_MLS")
        assumeTrue("Private exact replay inputs were not supplied", !inputPath.isNullOrBlank() && !mlsPath.isNullOrBlank())

        val input = JSONObject(File(inputPath!!).readText())
        val geometry = requireNotNull(CanonicalGeometryCodec.decode(input.getString("geometry")))
        val frameGeometry = geometry.toFrameGeometry()
        val frames = LandmarkFiles.read(
            File(mlsPath!!),
            expectedHash = input.getString("trackHash"),
            expectedFormatId = LandmarkFiles.FORMAT_ID,
        )
        exactFramesForDiagnostic = frames
        exactGeometryForDiagnostic = frameGeometry
        val scaleJson = input.getJSONObject("bodyScale")
        val bodyScale = BodyScaleEvidence(
            bodyHeightAspectCorrect = scaleJson.getDouble("heightAspectCorrect"),
            sourceId = scaleJson.getString("sourceId"),
            sourceVersion = scaleJson.getString("sourceVersion"),
        )
        val approval = ImpactViewApproval(
            activityPlanId = MotionActivityPlans.ALTERNATING_PUNCH,
            profileId = "hypothetical-exact-replay-side-view-v1",
            source = "diagnostic-only-not-production-approval",
        )
        val outputs = JSONArray()
        val leftRetentionByThreshold = config.bodyHeightRatioSweep.associateWith { mutableListOf<Int>() }
        val falseLeftRetentionByThreshold = config.bodyHeightRatioSweep.associateWith { mutableListOf<Int>() }
        val movements = input.getJSONArray("movements")

        for (index in 0 until movements.length()) {
            val ordinal = index + 1
            val item = movements.getJSONObject(index)
            val episode = item.optJSONObject("selectedEpisode")
            val evidence = episode?.let {
                evaluate(
                    frames = frames,
                    geometry = frameGeometry,
                    startUs = it.getLong("startUs"),
                    endUs = it.getLong("endUs"),
                    frozenBodyHeightAspectCorrect = bodyScale.bodyHeightAspectCorrect,
                    side = LateralSide.valueOf(item.getString("currentSide")),
                )
            }
            config.bodyHeightRatioSweep.forEach { threshold ->
                val retained = evidence?.eligible == true &&
                    abs(evidence.signedBodyHeightRatioNet ?: 0.0) >= threshold &&
                    (evidence.radialBodyHeightRatioNet ?: Double.NEGATIVE_INFINITY) >= threshold
                if (retained && item.getString("reviewSide") == "LEFT") leftRetentionByThreshold.getValue(threshold).add(ordinal)
                if (retained && item.getString("reviewSide") == "RIGHT") falseLeftRetentionByThreshold.getValue(threshold).add(ordinal)
            }

            val movement = SessionMovement(
                movementId = item.getString("movementId"),
                sessionId = item.getString("sessionId"),
                startUs = item.getLong("startUs"),
                endUs = item.getLong("endUs"),
                playbackStartUs = item.getLong("playbackStartUs"),
                playbackEndUs = item.getLong("playbackEndUs"),
                segmentationSource = item.getString("segmentationSource"),
                segmentationVersion = item.getString("segmentationVersion"),
                segmentationTrackId = item.getString("segmentationTrackId"),
                runId = input.getString("runId"),
                analysisFrameUs = item.getLong("analysisFrameUs"),
                segmentationEndBoundaryKind = item.nullableString("segmentationEndBoundaryKind"),
            )
            var pipeline: MovementAnalysisPipelineResult? = null
            if (item.getString("reviewSide") == "LEFT") {
                val qom = item.getJSONArray("qom").let { values ->
                    (0 until values.length()).map { qomIndex ->
                        val value = values.getJSONObject(qomIndex)
                        QomFrameEvidence(
                            timestampMs = value.getLong("timeUs") / 1000L,
                            profile = MotionBodyProfile.PUNCH,
                            blockQom = emptyMap(),
                            aggregateQom = null,
                            rollingArea = value.getDouble("rollingArea"),
                            availableBlockCount = 0,
                            totalActiveBlockCount = 0,
                            isAvailable = true,
                        )
                    }
                }
                val limbs = MovementMotionAnalysis.characterize(movement, input.getString("trackId"), frames, geometry)
                pipeline = MovementAnalysisPipeline.evaluate(
                    movement = movement,
                    trackId = input.getString("trackId"),
                    frames = frames,
                    geometry = geometry,
                    activityContext = MotionActivityPlans.context(MotionActivityPlans.ALTERNATING_PUNCH),
                    qom = qom,
                    limbs = limbs,
                    bodyScale = bodyScale,
                    viewApproval = approval,
                    analyzers = listOf(StraightPunchMovementAdapter.policy.analyzerKey),
                    videoWidth = geometry.canonicalWidth,
                    videoHeight = geometry.canonicalHeight,
                )
            }
            outputs.put(outputJson(ordinal, item, evidence, pipeline))
        }

        for (threshold in config.bodyHeightRatioSweep) {
            assertEquals(listOf(1, 3, 5, 7, 9), leftRetentionByThreshold.getValue(threshold))
            assertTrue(falseLeftRetentionByThreshold.getValue(threshold).isEmpty())
        }
        val report = JSONObject()
            .put("contract", "exact-punch-executable-diagnostic-v1")
            .put("experimentalConfig", JSONObject()
                .put("version", config.version)
                .put("minimumConfidence", config.minimumConfidence)
                .put("minimumCoverage", config.minimumCoverage)
                .put("minimumSamples", config.minimumSamples)
                .put("maximumGapUs", config.maximumGapUs)
                .put("bodyHeightRatioSweep", JSONArray(config.bodyHeightRatioSweep))
                .put("scaleSourceId", bodyScale.sourceId)
                .put("scaleSourceVersion", bodyScale.sourceVersion)
                .put("viewInput", "HYPOTHETICAL_DIAGNOSTIC_ONLY"))
            .put("movements", outputs)
        val outputPath = System.getenv("KARATE_PUNCH_DIAGNOSTIC_OUTPUT")
        if (outputPath.isNullOrBlank()) println(report.toString(2)) else File(outputPath).writeText(report.toString(2))
    }

    private data class HandTerminalProbeResult(
        val status: String,
        val boundedFrameCount: Int,
        val eligibleFrameCount: Int,
        val trailingContinuousSampleCount: Int,
        val supportStartUs: Long?,
        val supportEndUs: Long?,
        val supportDurationUs: Long?,
        val filterWarmupEndUs: Long?,
        val peakSpeedUs: Long?,
        val maximumExtensionUs: Long?,
        val stableSuffixStartUs: Long?,
        val stableSuffixDurationUs: Long?,
        val outwardExcursionBodyHeightRatio: Double?,
        val endpointRetreatBodyHeightRatio: Double?,
        val minimumConfidence: Double?,
        val trace: List<FilteredHandTerminalSample>,
    ) {
        fun toJson(ordinal: Int, movement: JSONObject) = JSONObject()
            .put("ordinal", ordinal)
            .put("movementId", movement.getString("movementId"))
            .put("startUs", movement.getLong("startUs"))
            .put("endUs", movement.getLong("endUs"))
            .put("segmentationEndBoundaryKind", movement.opt("segmentationEndBoundaryKind"))
            .put("status", status)
            .put("boundedFrameCount", boundedFrameCount)
            .put("eligibleFrameCount", eligibleFrameCount)
            .put("trailingContinuousSampleCount", trailingContinuousSampleCount)
            .put("supportStartUs", supportStartUs)
            .put("supportEndUs", supportEndUs)
            .put("supportDurationUs", supportDurationUs)
            .put("filterWarmupEndUs", filterWarmupEndUs)
            .put("peakSpeedUs", peakSpeedUs)
            .put("maximumExtensionUs", maximumExtensionUs)
            .put("stableSuffixStartUs", stableSuffixStartUs)
            .put("stableSuffixDurationUs", stableSuffixDurationUs)
            .put("outwardExcursionBodyHeightRatio", outwardExcursionBodyHeightRatio)
            .put("endpointRetreatBodyHeightRatio", endpointRetreatBodyHeightRatio)
            .put("minimumConfidence", minimumConfidence)
            .put("trace", JSONArray(trace.mapIndexed { index, sample -> JSONObject()
                .put("timeUs", sample.observation.timestampUs)
                .put("fullyWarmed", index >= HAND_TERMINAL_FILTER_WARMUP_SAMPLES - 1)
                .put("radiusBodyHeightRatio", sample.radiusBodyHeightRatio)
                .put("radialVelocityBodyHeightsPerSec", sample.radialVelocityBodyHeightsPerSec)
                .put("speedBodyHeightsPerSec", sample.speedBodyHeightsPerSec)
                .put("distanceToEndpointBodyHeightRatio", sample.distanceToEndpointBodyHeightRatio) }))
            .put("handRosterPolicy", "FIXED_FULL_WRIST_THUMB_INDEX_PINKY_OBSERVED_AT_0_55")
            .put("bodyMotionPolicy", "ASPECT_CORRECT_HAND_MINUS_BILATERAL_SHOULDER_HIP_CENTER")
            .put("continuityPolicy", "RESET_ON_MISSING_ROSTER_OR_GAP_OVER_100000_US")
            .put("terminalPolicy", "POST_WARMUP_OUTWARD_EXCURSION_THEN_TIMESTAMP_NORMALIZED_DECELERATION_THEN_100000_US_FIXED_ENDPOINT_STABILITY")
            .put("identityStatus", "ANATOMICAL_LANDMARK_LABELS_WITHOUT_INDEPENDENT_IDENTITY_CONTINUITY_PROOF")
    }

    private data class HandTerminalObservation(
        val timestampUs: Long,
        val x: Double,
        val y: Double,
        val confidence: Double,
    )

    private data class FilteredHandTerminalSample(
        val observation: HandTerminalObservation,
        val x: Double,
        val y: Double,
        val radiusBodyHeightRatio: Double,
        val radialVelocityBodyHeightsPerSec: Double,
        val speedBodyHeightsPerSec: Double,
        var distanceToEndpointBodyHeightRatio: Double = 0.0,
    )

    private fun selectedHandTerminalProbe(
        frames: List<PoseFrame>,
        geometry: FrameGeometry,
        startUs: Long,
        endUs: Long,
        bodyHeightAspectCorrect: Double,
        side: LateralSide,
        endBoundaryKind: String? = null,
    ): HandTerminalProbeResult {
        val bounded = frames.filter { it.timestampMs * 1000L in startUs..endUs }
        val handIds = if (side == LateralSide.LEFT) MotionBodyProfile.PUNCH_HAND_LANDMARKS_LEFT
            else MotionBodyProfile.PUNCH_HAND_LANDMARKS_RIGHT
        val torsoIds = listOf(
            PoseLandmarkId.LEFT_SHOULDER, PoseLandmarkId.RIGHT_SHOULDER,
            PoseLandmarkId.LEFT_HIP, PoseLandmarkId.RIGHT_HIP,
        )
        fun observation(frame: PoseFrame): HandTerminalObservation? {
            val hand = handIds.map { id -> frame.landmarks[id]?.takeIf { it.isObserved(HAND_TERMINAL_MINIMUM_CONFIDENCE) } }
            if (hand.any { it?.position == null }) return null
            val torso = torsoIds.map { id -> frame.landmarks[id]?.takeIf { it.isObserved(HAND_TERMINAL_MINIMUM_CONFIDENCE) } }
            if (torso.any { it?.position == null }) return null
            val composite = ExtremityPointComposer.compositeSourcePoint(frame, handIds) ?: return null
            val torsoPoints = torso.map { requireNotNull(it?.position) }
            val torsoCenter = SourceNormalizedPoint(
                torsoPoints.map { it.x }.average().toFloat(),
                torsoPoints.map { it.y }.average().toFloat(),
            )
            val handPoint = FrameGeometryMath.sourceToAspectCorrect(composite.point, geometry)
            val anchorPoint = FrameGeometryMath.sourceToAspectCorrect(torsoCenter, geometry)
            return HandTerminalObservation(
                timestampUs = frame.timestampMs * 1000L,
                x = (handPoint.x - anchorPoint.x).toDouble(),
                y = (handPoint.y - anchorPoint.y).toDouble(),
                confidence = composite.confidence,
            )
        }

        val eligible = bounded.mapNotNull(::observation)
        val trailing = ArrayDeque<HandTerminalObservation>()
        for (frame in bounded) {
            val sample = observation(frame)
            if (sample == null) {
                trailing.clear()
            } else {
                if (trailing.lastOrNull()?.let { sample.timestampUs - it.timestampUs > HAND_TERMINAL_MAXIMUM_GAP_US } == true) {
                    trailing.clear()
                }
                trailing.addLast(sample)
            }
        }

        fun result(
            status: String,
            samples: List<FilteredHandTerminalSample> = emptyList(),
            peakIndex: Int? = null,
            maximumIndex: Int? = null,
            stableStartIndex: Int? = null,
        ): HandTerminalProbeResult {
            val observations = samples.map { it.observation }.ifEmpty { trailing.toList() }
            val maximumRadius = maximumIndex?.let { samples[it].radiusBodyHeightRatio }
            val endpointRadius = samples.lastOrNull()?.radiusBodyHeightRatio
            val baselineRadius = samples.getOrNull(HAND_TERMINAL_FILTER_WARMUP_SAMPLES - 1)?.radiusBodyHeightRatio
            return HandTerminalProbeResult(
                status = status,
                boundedFrameCount = bounded.size,
                eligibleFrameCount = eligible.size,
                trailingContinuousSampleCount = trailing.size,
                supportStartUs = observations.firstOrNull()?.timestampUs,
                supportEndUs = observations.lastOrNull()?.timestampUs,
                supportDurationUs = observations.takeIf { it.isNotEmpty() }?.let { it.last().timestampUs - it.first().timestampUs },
                filterWarmupEndUs = samples.getOrNull(HAND_TERMINAL_FILTER_WARMUP_SAMPLES - 1)?.observation?.timestampUs,
                peakSpeedUs = peakIndex?.let { samples[it].observation.timestampUs },
                maximumExtensionUs = maximumIndex?.let { samples[it].observation.timestampUs },
                stableSuffixStartUs = stableStartIndex?.let { samples[it].observation.timestampUs },
                stableSuffixDurationUs = stableStartIndex?.let { samples.last().observation.timestampUs - samples[it].observation.timestampUs },
                outwardExcursionBodyHeightRatio = if (maximumRadius == null || baselineRadius == null) null
                    else maximumRadius - baselineRadius,
                endpointRetreatBodyHeightRatio = if (maximumRadius == null || endpointRadius == null) null
                    else maximumRadius - endpointRadius,
                minimumConfidence = observations.minOfOrNull { it.confidence },
                trace = samples,
            )
        }

        if (bodyHeightAspectCorrect <= 0.0 || !bodyHeightAspectCorrect.isFinite()) return result("BODY_SCALE_UNAVAILABLE")
        if (trailing.size < 3 || endUs - trailing.last().timestampUs > HAND_TERMINAL_MAXIMUM_GAP_US) {
            return result("TERMINAL_CONTINUOUS_HAND_SUPPORT_UNAVAILABLE")
        }
        val filter = CausalCoordinateMotionFilter(2)
        val samples = trailing.map { observation ->
            val filtered = filter.accept(observation.timestampUs, listOf(observation.x, observation.y), observation.confidence)
            val radius = sqrt(filtered.coordinates[0] * filtered.coordinates[0] + filtered.coordinates[1] * filtered.coordinates[1])
            val previous = filtered.previousCoordinates
            val rawStep = if (previous == null) 0.0 else sqrt(
                (filtered.coordinates[0] - previous[0]) * (filtered.coordinates[0] - previous[0]) +
                    (filtered.coordinates[1] - previous[1]) * (filtered.coordinates[1] - previous[1]),
            )
            val dt = filtered.deltaSeconds?.takeIf { it > 0.0 }
            val previousRadius = previous?.let { sqrt(it[0] * it[0] + it[1] * it[1]) }
            FilteredHandTerminalSample(
                observation = observation,
                x = filtered.coordinates[0],
                y = filtered.coordinates[1],
                radiusBodyHeightRatio = radius / bodyHeightAspectCorrect,
                radialVelocityBodyHeightsPerSec = if (dt == null || previousRadius == null) 0.0
                    else (radius - previousRadius) / bodyHeightAspectCorrect / dt,
                speedBodyHeightsPerSec = if (dt == null) 0.0 else rawStep / bodyHeightAspectCorrect / dt,
            )
        }
        val endpoint = samples.last()
        samples.forEach { sample ->
            sample.distanceToEndpointBodyHeightRatio = sqrt(
                (sample.x - endpoint.x) * (sample.x - endpoint.x) +
                    (sample.y - endpoint.y) * (sample.y - endpoint.y),
            ) / bodyHeightAspectCorrect
        }
        val baselineIndex = HAND_TERMINAL_FILTER_WARMUP_SAMPLES - 1
        if (samples.size - baselineIndex < HAND_TERMINAL_MINIMUM_POST_WARMUP_SAMPLES) {
            return result("INSUFFICIENT_POST_FILTER_WARMUP_SUPPORT", samples)
        }
        val analysisIndices = baselineIndex..samples.lastIndex
        val maximumIndex = analysisIndices.maxByOrNull { samples[it].radiusBodyHeightRatio }
            ?: return result("OUTWARD_APPROACH_NOT_OBSERVED_WITHIN_SUPPORTED_INTERVAL", samples)
        val outwardExcursion = samples[maximumIndex].radiusBodyHeightRatio - samples[baselineIndex].radiusBodyHeightRatio
        var stableStartIndex = samples.lastIndex
        while (stableStartIndex > baselineIndex &&
            samples[stableStartIndex - 1].distanceToEndpointBodyHeightRatio <=
            HAND_TERMINAL_FIXED_SPATIAL_TOLERANCE_BODY_HEIGHT_RATIO
        ) stableStartIndex--
        val stableDurationUs = samples.last().observation.timestampUs - samples[stableStartIndex].observation.timestampUs
        val approachIndices = if (stableStartIndex > baselineIndex) baselineIndex until stableStartIndex else IntRange.EMPTY
        val peakIndex = approachIndices.maxByOrNull { samples[it].radialVelocityBodyHeightsPerSec }
            ?.takeIf { samples[it].radialVelocityBodyHeightsPerSec > 0.0 }
        if (maximumIndex == baselineIndex || outwardExcursion <= HAND_TERMINAL_FIXED_SPATIAL_TOLERANCE_BODY_HEIGHT_RATIO) {
            val boundary = if (endBoundaryKind == "RECORDING_END_WITHOUT_STOP_EVIDENCE")
                "_AT_UNCONFIRMED_RECORDING_END" else ""
            return result("OUTWARD_APPROACH_WITHIN_FIXED_TOLERANCE$boundary", samples,
                peakIndex, maximumIndex, stableStartIndex)
        }
        if (stableDurationUs < HAND_TERMINAL_STABLE_SUFFIX_US) {
            return result("STABLE_ENDPOINT_NOT_OBSERVED", samples, peakIndex,
                maximumIndex = maximumIndex, stableStartIndex = stableStartIndex)
        }
        val measuredPeakIndex = peakIndex
            ?: return result("OUTWARD_RADIAL_VELOCITY_UNAVAILABLE", samples,
                maximumIndex = maximumIndex, stableStartIndex = stableStartIndex)
        if (samples.drop(measuredPeakIndex + 1).none {
                it.radialVelocityBodyHeightsPerSec < samples[measuredPeakIndex].radialVelocityBodyHeightsPerSec
            }) return result("DECELERATION_NOT_OBSERVED", samples, measuredPeakIndex, maximumIndex, stableStartIndex)
        if (samples[maximumIndex].radiusBodyHeightRatio - samples.last().radiusBodyHeightRatio >
            HAND_TERMINAL_FIXED_SPATIAL_TOLERANCE_BODY_HEIGHT_RATIO
        ) {
            return result("ENDPOINT_NOT_AT_MAXIMUM_EXTENSION", samples, measuredPeakIndex, maximumIndex, stableStartIndex)
        }
        val status = if (endBoundaryKind == "RECORDING_END_WITHOUT_STOP_EVIDENCE")
            "ENDPOINT_CANDIDATE_AT_UNCONFIRMED_RECORDING_END"
        else "OBSERVED_APPROACH_DECELERATION_STABLE_ENDPOINT"
        return result(status, samples, measuredPeakIndex, maximumIndex, stableStartIndex)
    }

    private fun handTerminalFixtureFrame(
        timestampMs: Long,
        handX: Double,
        torsoTranslationX: Double = 0.0,
    ): PoseFrame {
        fun sample(x: Double, y: Double) = PoseLandmarkSample(
            position = Point3(x.toFloat(), y.toFloat(), 0f),
            visibility = .95f,
            presence = .95f,
            source = LandmarkSource.OBSERVED,
        )
        val handIds = MotionBodyProfile.PUNCH_HAND_LANDMARKS_LEFT
        return PoseFrame(timestampMs, buildMap {
            handIds.forEach { put(it, sample(handX, .45)) }
            put(PoseLandmarkId.LEFT_SHOULDER, sample(.47 + torsoTranslationX, .30))
            put(PoseLandmarkId.RIGHT_SHOULDER, sample(.53 + torsoTranslationX, .30))
            put(PoseLandmarkId.LEFT_HIP, sample(.47 + torsoTranslationX, .60))
            put(PoseLandmarkId.RIGHT_HIP, sample(.53 + torsoTranslationX, .60))
        })
    }

    private fun evaluate(
        frames: List<PoseFrame>,
        geometry: FrameGeometry,
        startUs: Long,
        endUs: Long,
        frozenBodyHeightAspectCorrect: Double,
        side: LateralSide = LateralSide.LEFT,
    ): EpisodeEvidence {
        val bounded = frames.filter { it.timestampMs * 1000L in startUs..endUs }
        val wristId = if (side == LateralSide.LEFT) PoseLandmarkId.LEFT_WRIST else PoseLandmarkId.RIGHT_WRIST
        data class Sample(val timeUs: Long, val sourceX: Double, val radialAspect: Double, val confidence: Double)
        val samples = bounded.mapNotNull { frame ->
            val ids = listOf(
                wristId,
                PoseLandmarkId.LEFT_SHOULDER,
                PoseLandmarkId.RIGHT_SHOULDER,
                PoseLandmarkId.LEFT_HIP,
                PoseLandmarkId.RIGHT_HIP,
            )
            val observed = ids.map { id -> frame.landmarks[id]?.takeIf { it.isObserved(config.minimumConfidence) } }
            if (observed.any { it == null }) return@mapNotNull null
            val points = observed.map { requireNotNull(it?.position) }
            val wrist = points.first()
            val anchor = Point3(
                points.drop(1).map { it.x }.average().toFloat(),
                points.drop(1).map { it.y }.average().toFloat(),
                points.drop(1).map { it.z }.average().toFloat(),
            )
            val wristAspect = FrameGeometryMath.sourceToAspectCorrect(SourceNormalizedPoint(wrist.x, wrist.y), geometry)
            val anchorAspect = FrameGeometryMath.sourceToAspectCorrect(SourceNormalizedPoint(anchor.x, anchor.y), geometry)
            Sample(
                timeUs = frame.timestampMs * 1000L,
                sourceX = (wrist.x - anchor.x).toDouble(),
                radialAspect = FrameGeometryMath.distance(wristAspect, anchorAspect).toDouble(),
                confidence = observed.minOf { requireNotNull(it).confidence.toDouble() },
            )
        }
        val coverage = if (bounded.isEmpty()) 0.0 else samples.size.toDouble() / bounded.size.toDouble()
        val maximumGap = samples.zipWithNext().maxOfOrNull { (a, b) -> b.timeUs - a.timeUs }
        val reason = when {
            samples.size < config.minimumSamples -> "INSUFFICIENT_SAMPLES"
            coverage < config.minimumCoverage -> "INSUFFICIENT_COVERAGE"
            maximumGap != null && maximumGap > config.maximumGapUs -> "EXCESSIVE_GAP"
            frozenBodyHeightAspectCorrect <= 0.0 -> "FROZEN_BODY_SCALE_UNAVAILABLE"
            else -> null
        }
        if (samples.isEmpty()) return EpisodeEvidence(false, 0, bounded.size, coverage, null, null,
            null, null, null, null, null, reason ?: "NO_ELIGIBLE_SAMPLES")
        val sourceNet = samples.last().sourceX - samples.first().sourceX
        val sourceMaximumExcursion = samples.maxOf { abs(it.sourceX - samples.first().sourceX) }
        val radialNet = samples.last().radialAspect - samples.first().radialAspect
        return EpisodeEvidence(
            eligible = reason == null,
            validSamples = samples.size,
            boundedSamples = bounded.size,
            coverage = coverage,
            maximumGapUs = maximumGap,
            minimumConfidence = samples.minOf { it.confidence },
            signedSourceXNet = sourceNet,
            signedSourceXMaximumExcursion = sourceMaximumExcursion,
            signedBodyHeightRatioNet = sourceNet * geometry.aspectRatio / frozenBodyHeightAspectCorrect,
            radialAspectNet = radialNet,
            radialBodyHeightRatioNet = radialNet / frozenBodyHeightAspectCorrect,
            reason = reason,
        )
    }

    private fun syntheticEpisode(
        wrist: List<Pair<Double, Double>>,
        anchor: List<Pair<Double, Double>>,
        wristId: PoseLandmarkId = PoseLandmarkId.LEFT_WRIST,
    ): List<PoseFrame> = wrist.indices.map { index ->
        fun sample(point: Pair<Double, Double>) = PoseLandmarkSample(
            position = Point3(point.first.toFloat(), point.second.toFloat(), 0f),
            visibility = .95f,
            presence = .95f,
            source = LandmarkSource.OBSERVED,
        )
        val a = anchor[index]
        PoseFrame(index * 40L, mapOf(
            wristId to sample(wrist[index]),
            PoseLandmarkId.LEFT_SHOULDER to sample(a.first - .05 to a.second - .10),
            PoseLandmarkId.RIGHT_SHOULDER to sample(a.first + .05 to a.second - .10),
            PoseLandmarkId.LEFT_HIP to sample(a.first - .05 to a.second + .10),
            PoseLandmarkId.RIGHT_HIP to sample(a.first + .05 to a.second + .10),
        ))
    }

    private fun retainedFixtureFrame(
        timestampMs: Long,
        includeRightHip: Boolean = true,
        includeLeftWrist: Boolean = true,
        includeHead: Boolean = true,
    ): PoseFrame {
        fun sample(x: Double, y: Double, z: Double = 0.0) = PoseLandmarkSample(
            position = Point3(x.toFloat(), y.toFloat(), z.toFloat()),
            visibility = .95f,
            presence = .95f,
            source = LandmarkSource.OBSERVED,
        )
        val landmarks = mutableMapOf(
            PoseLandmarkId.LEFT_SHOULDER to sample(.48, .30, -.10),
            PoseLandmarkId.RIGHT_SHOULDER to sample(.52, .30, .10),
            PoseLandmarkId.LEFT_HIP to sample(.48, .60, -.10),
            PoseLandmarkId.LEFT_ELBOW to sample(.34, .38, -.10),
            PoseLandmarkId.LEFT_INDEX to sample(.17, .38, -.10),
            PoseLandmarkId.LEFT_PINKY to sample(.18, .39, -.10),
        )
        if (includeRightHip) landmarks[PoseLandmarkId.RIGHT_HIP] = sample(.52, .60, .10)
        if (includeLeftWrist) landmarks[PoseLandmarkId.LEFT_WRIST] = sample(.20, .38, -.10)
        if (includeHead) {
            landmarks[PoseLandmarkId.NOSE] = sample(.47, .18, -.10)
            landmarks[PoseLandmarkId.MOUTH_LEFT] = sample(.46, .20, -.10)
            landmarks[PoseLandmarkId.LEFT_EAR] = sample(.49, .18, -.10)
            landmarks[PoseLandmarkId.LEFT_EYE] = sample(.47, .16, -.10)
        }
        return PoseFrame(timestampMs, landmarks)
    }

    private fun outputJson(
        ordinal: Int,
        input: JSONObject,
        evidence: EpisodeEvidence?,
        pipeline: MovementAnalysisPipelineResult?,
    ): JSONObject {
        val impact = pipeline?.motion?.impact
        val downstream = pipeline?.downstream?.singleOrNull()
        val transitionPeak = impact?.debugEvidence?.maxByOrNull { it.terminalTransitionScore }
        val setup = impact?.stableRepresentativeTimestampUs?.let { representativeUs ->
            bodyReferenceDiagnostic(representativeUs)
        }
        val retainedReference = impact?.stableRepresentativeTimestampUs?.let { representativeUs ->
            retainedReferenceDiagnostic(
                movementStartUs = input.getLong("startUs"),
                representativeUs = representativeUs,
                activeArm = if (input.getString("currentSide") == "LEFT") ActiveArm.LEFT else ActiveArm.RIGHT,
            )
        }
        val retainedReferenceSweep = impact?.stableRepresentativeTimestampUs?.let {
            JSONArray(RETAINED_REFERENCE_DURATION_SWEEP_US.map { requiredDurationUs ->
                retainedReferenceSweepDiagnostic(input.getLong("startUs"), requiredDurationUs)
            })
        }
        return JSONObject()
            .put("ordinal", ordinal)
            .put("movementId", input.getString("movementId"))
            .put("reviewSide", input.getString("reviewSide"))
            .put("currentSide", input.nullableString("currentSide"))
            .put("episodeEvidence", evidence?.let { JSONObject()
                .put("eligible", it.eligible)
                .put("validSamples", it.validSamples)
                .put("boundedSamples", it.boundedSamples)
                .put("coverage", it.coverage)
                .put("maximumGapUs", it.maximumGapUs)
                .put("minimumConfidence", it.minimumConfidence)
                .put("signedSourceXNet", it.signedSourceXNet)
                .put("signedSourceXMaximumExcursion", it.signedSourceXMaximumExcursion)
                .put("signedBodyHeightRatioNet", it.signedBodyHeightRatioNet)
                .put("radialAspectNet", it.radialAspectNet)
                .put("radialBodyHeightRatioNet", it.radialBodyHeightRatioNet)
                .put("identityStatus", it.identityStatus)
                .put("reason", it.reason) })
            .put("hypotheticalImpact", pipeline?.let { JSONObject()
                .put("status", impact?.status?.name)
                .put("reason", impact?.abstentionReason?.name)
                .put("terminalUs", impact?.terminalTransitionTimestampUs)
                .put("stableStartUs", impact?.stableWindowStartTimestampUs)
                .put("stableEndUs", impact?.stableWindowEndTimestampUs)
                .put("representativeUs", impact?.stableRepresentativeTimestampUs)
                .put("usableSamples", impact?.quality?.usableSampleCount)
                .put("trackingCoverage", impact?.quality?.trackingCoverage)
                .put("maximumTransitionScore", transitionPeak?.terminalTransitionScore)
                .put("maximumTransitionScoreUs", transitionPeak?.timestampUs)
                .put("stableSampleCount", impact?.debugEvidence?.count { it.stableState })
                .put("stableTimestampsUs", JSONArray(impact?.debugEvidence.orEmpty()
                    .filter { it.stableState }.map { it.timestampUs }))
                .put("bodyReferencePrerequisite", setup)
                .put("retainedReferenceCandidate", retainedReference)
                .put("retainedReferenceDurationSweep", retainedReferenceSweep)
                .put("downstreamState", downstream?.analysis?.state?.name)
                .put("downstreamReason", downstream?.analysis?.reason)
                .put("measurements", JSONArray(downstream?.measurements.orEmpty().map { result -> JSONObject()
                    .put("key", result.measurementKey)
                    .put("state", result.state.name)
                    .put("numericValue", result.numericValue)
                    .put("categoricalValue", result.categoricalValue)
                    .put("reason", result.reason)
                    .put("occurrenceUs", result.occurrenceUs) })) })
    }

    private lateinit var exactFramesForDiagnostic: List<PoseFrame>
    private lateinit var exactGeometryForDiagnostic: FrameGeometry

    private fun bodyReferenceDiagnostic(untilUs: Long): JSONObject {
        val analyzer = PunchHeightAnalyzer()
        var setupReadyAtUs: Long? = null
        var initializationReadyAtUs: Long? = null
        var maximumInitializationProgress = 0f
        val setupGuidanceCounts = linkedMapOf<String, Int>()
        for (frame in exactFramesForDiagnostic.asSequence().filter { it.timestampMs * 1000L <= untilUs }) {
            if (setupReadyAtUs == null) {
                val setup = analyzer.processSetup(frame)
                setupGuidanceCounts[setup.guidance.name] = (setupGuidanceCounts[setup.guidance.name] ?: 0) + 1
                if (setup.usable) setupReadyAtUs = frame.timestampMs * 1000L
            } else if (initializationReadyAtUs == null) {
                val initialization = analyzer.processBodyInitialization(
                    frame,
                    PunchHeightAnalyzer.DEFAULT_CHIN_PROJECTION_MULTIPLIER,
                )
                maximumInitializationProgress = maxOf(maximumInitializationProgress, initialization.progress)
                if (initialization.bodyReference != null) initializationReadyAtUs = frame.timestampMs * 1000L
            }
        }
        return JSONObject()
            .put("setupReadyAtUs", setupReadyAtUs)
            .put("initializationReadyAtUs", initializationReadyAtUs)
            .put("maximumInitializationProgress", maximumInitializationProgress)
            .put("setupGuidanceCounts", JSONObject(setupGuidanceCounts as Map<*, *>))
    }

    private data class ReferenceSample(
        val frame: PoseFrame,
        val shoulder: Point3,
        val hip: Point3,
        val torsoLength: Float,
        val confidence: Float,
        val nearerSide: VisibleSide,
    )

    private data class ReferenceSnapshot(
        val startUs: Long,
        val endUs: Long,
        val sampleCount: Int,
        val maximumJitterRatio: Float,
        val reference: BodyReference,
        val chinSource: String,
    )

    private fun retainedReferenceDiagnostic(
        movementStartUs: Long,
        representativeUs: Long,
        activeArm: ActiveArm,
        requiredDurationUs: Long = RETAINED_REFERENCE_BASELINE_DURATION_US,
    ): JSONObject {
        val snapshot = findRetainedReference(movementStartUs, requiredDurationUs)
            ?: return JSONObject().put("status", "UNAVAILABLE")
                .put("reason", "NO_STABLE_PRE_MOVEMENT_TORSO_REFERENCE")
                .put("search", retainedReferenceSearchDiagnostics(movementStartUs, requiredDurationUs))
        val representativeFrame = exactFramesForDiagnostic.singleOrNull { it.timestampMs * 1000L == representativeUs }
            ?: return JSONObject().put("status", "UNAVAILABLE")
                .put("reason", "REPRESENTATIVE_FRAME_UNAVAILABLE")
        val reach = StraightPunchMovementAdapter.computeStableArmReach(
            frames = exactFramesForDiagnostic,
            startUs = movementStartUs,
            impactUs = representativeUs,
            activeArm = activeArm,
            aspectRatio = exactGeometryForDiagnostic.aspectRatio,
        )
        val evaluation = StraightPunchTargetCalculator(
            aspectRatio = exactGeometryForDiagnostic.aspectRatio,
            neutralChinPoint = snapshot.reference.chinPoint,
        ).evaluate(
            frame = representativeFrame,
            bodyReference = snapshot.reference,
            activeArm = activeArm,
            stableArmReachRadius = reach,
            stableArmReachProvenance = if (reach == null) null else "diagnostic_existing_multi_frame_reach",
        )
        return JSONObject()
            .put("status", "AVAILABLE")
            .put("policyVersion", "retained_video_reference_v1_experimental")
            .put("requiredDurationUs", requiredDurationUs)
            .put("snapshotStartUs", snapshot.startUs)
            .put("snapshotEndUs", snapshot.endUs)
            .put("sampleCount", snapshot.sampleCount)
            .put("maximumJitterRatio", snapshot.maximumJitterRatio)
            .put("confidence", snapshot.reference.confidence)
            .put("visibleSide", snapshot.reference.visibleSide.name)
            .put("visibleSideSource", "BILATERAL_SHOULDER_HIP_DEPTH_CONSENSUS")
            .put("chinSource", snapshot.chinSource)
            .put("activeArm", activeArm.name)
            .put("activeArmSource", "EXPLICIT_RESOLVED_STRIKING_SIDE")
            .put("stableArmReach", reach)
            .put("evaluationState", evaluation.state.name)
            .put("evaluationReason", evaluation.reason)
            .put("closestTarget", evaluation.closestTarget?.name)
            .put("targetAngleErrorDeg", evaluation.targetAngleErrorDeg)
            .put("classificationMarginDeg", evaluation.classificationMarginDeg)
            .put("elbowAngleDeg", evaluation.elbowAngleDeg)
            .put("targets", JSONArray(evaluation.targetResults.values.map { target -> JSONObject()
                .put("target", target.targetType.name)
                .put("state", target.state.name)
                .put("reason", target.reason)
                .put("errorDeg", target.errorDeg) }))
    }

    private fun retainedReferenceSweepDiagnostic(cutoffUs: Long, requiredDurationUs: Long): JSONObject {
        val search = retainedReferenceSearchDiagnostics(cutoffUs, requiredDurationUs)
        val snapshot = findRetainedReference(cutoffUs, requiredDurationUs)
        return JSONObject()
            .put("requiredDurationUs", requiredDurationUs)
            .put("minimumSampleCount", RETAINED_REFERENCE_MIN_SAMPLES)
            .put("maximumGapUs", RETAINED_REFERENCE_MAX_GAP_US)
            .put("maximumAllowedJitterRatio", RETAINED_REFERENCE_MAX_JITTER_RATIO)
            .put("endpointInclusion", "latest sample at-or-before cutoff; earliest retained sample brackets duration inclusively")
            .put("jitterCoordinateSpace", "SOURCE_NORMALIZED_XY")
            .put("jitterFormula", "max shoulder-or-hip 2D distance from window mean divided by each sample torso length")
            .put("search", search)
            .put("qualifyingCandidate", snapshot?.let { candidate -> JSONObject()
                .put("startUs", candidate.startUs)
                .put("endUs", candidate.endUs)
                .put("sampleCount", candidate.sampleCount)
                .put("maximumJitterRatio", candidate.maximumJitterRatio)
                .put("neutralPostureConfirmation", "NOT_ESTABLISHED_BY_NUMERIC_STABILITY") })
    }

    private fun findRetainedReference(cutoffUs: Long, requiredDurationUs: Long): ReferenceSnapshot? {
        val window = ArrayDeque<ReferenceSample>()
        var latest: ReferenceSnapshot? = null
        for (frame in exactFramesForDiagnostic.asSequence().filter { it.timestampMs * 1000L <= cutoffUs }) {
            val sample = referenceSample(frame)
            if (sample == null) {
                window.clear()
                continue
            }
            val timeUs = frame.timestampMs * 1000L
            if (window.lastOrNull()?.let { timeUs - it.frame.timestampMs * 1000L > RETAINED_REFERENCE_MAX_GAP_US } == true) {
                window.clear()
            }
            window.addLast(sample)
            retainDurationBracket(window, timeUs, requiredDurationUs)
            if (window.size < RETAINED_REFERENCE_MIN_SAMPLES ||
                timeUs - window.first().frame.timestampMs * 1000L < requiredDurationUs
            ) continue
            val shoulder = averagePoint(window.map { it.shoulder })
            val hip = averagePoint(window.map { it.hip })
            val torsoLength = distance2d(shoulder, hip)
            val jitter = window.maxOf {
                maxOf(distance2d(it.shoulder, shoulder), distance2d(it.hip, hip)) / it.torsoLength
            }
            if (jitter > RETAINED_REFERENCE_MAX_JITTER_RATIO || torsoLength <= 0f) continue
            val leftCount = window.count { it.nearerSide == VisibleSide.LEFT }
            val rightCount = window.count { it.nearerSide == VisibleSide.RIGHT }
            val visibleSide = when {
                leftCount.toDouble() / window.size >= 0.80 -> VisibleSide.LEFT
                rightCount.toDouble() / window.size >= 0.80 -> VisibleSide.RIGHT
                else -> VisibleSide.UNKNOWN
            }
            val torsoAxis = Point3(
                (hip.x - shoulder.x) / torsoLength,
                (hip.y - shoulder.y) / torsoLength,
                (hip.z - shoulder.z) / torsoLength,
            )
            val base = BodyReference(
                visibleSide = visibleSide,
                shoulderPoint = shoulder,
                hipPoint = hip,
                torsoAxis = torsoAxis,
                torsoLength = torsoLength,
                confidence = window.map { it.confidence }.average().toFloat(),
            )
            val chin = if (visibleSide == VisibleSide.UNKNOWN) null else {
                SideViewChinEstimator().estimate(
                    TrackedPoseFrame(window.last().frame.timestampMs, window.last().frame.landmarks),
                    base,
                    PunchHeightAnalyzer.DEFAULT_CHIN_PROJECTION_MULTIPLIER,
                ).takeIf { it.captureEligible }
            }
            latest = ReferenceSnapshot(
                startUs = window.first().frame.timestampMs * 1000L,
                endUs = timeUs,
                sampleCount = window.size,
                maximumJitterRatio = jitter,
                reference = base.copy(chinPoint = chin?.smoothedPoint),
                chinSource = chin?.source?.name
                    ?: if (visibleSide == VisibleSide.UNKNOWN) "VISIBLE_SIDE_UNRESOLVED" else "CHIN_EVIDENCE_UNAVAILABLE",
            )
        }
        return latest
    }

    private fun retainedReferenceSearchDiagnostics(cutoffUs: Long, requiredDurationUs: Long): JSONObject {
        val window = ArrayDeque<ReferenceSample>()
        var eligibleTorsoFrames = 0
        var longestContinuousUs = 0L
        var segmentStartUs: Long? = null
        var evaluatedDurationWindows = 0
        var minimumJitter: Float? = null
        var minimumJitterStartUs: Long? = null
        var minimumJitterEndUs: Long? = null
        var qualifyingCandidateWindows = 0
        val qualifyingCandidateSpans = mutableListOf<JSONObject>()
        for (frame in exactFramesForDiagnostic.asSequence().filter { it.timestampMs * 1000L <= cutoffUs }) {
            val timeUs = frame.timestampMs * 1000L
            val sample = referenceSample(frame)
            if (sample == null) {
                window.clear()
                segmentStartUs = null
                continue
            }
            eligibleTorsoFrames++
            if (window.lastOrNull()?.let { timeUs - it.frame.timestampMs * 1000L > RETAINED_REFERENCE_MAX_GAP_US } == true) {
                window.clear()
                segmentStartUs = null
            }
            if (segmentStartUs == null) segmentStartUs = timeUs
            longestContinuousUs = maxOf(longestContinuousUs, timeUs - segmentStartUs)
            window.addLast(sample)
            retainDurationBracket(window, timeUs, requiredDurationUs)
            if (window.size < RETAINED_REFERENCE_MIN_SAMPLES ||
                timeUs - window.first().frame.timestampMs * 1000L < requiredDurationUs
            ) continue
            evaluatedDurationWindows++
            val shoulder = averagePoint(window.map { it.shoulder })
            val hip = averagePoint(window.map { it.hip })
            val jitter = window.maxOf {
                maxOf(distance2d(it.shoulder, shoulder), distance2d(it.hip, hip)) / it.torsoLength
            }
            if (minimumJitter == null || jitter < minimumJitter) {
                minimumJitter = jitter
                minimumJitterStartUs = window.first().frame.timestampMs * 1000L
                minimumJitterEndUs = timeUs
            }
            if (jitter <= RETAINED_REFERENCE_MAX_JITTER_RATIO) {
                qualifyingCandidateWindows++
                qualifyingCandidateSpans += JSONObject()
                    .put("startUs", window.first().frame.timestampMs * 1000L)
                    .put("endUs", timeUs)
                    .put("sampleCount", window.size)
                    .put("maximumJitterRatio", jitter)
                    .put("neutralPostureConfirmation", "NOT_ESTABLISHED_BY_NUMERIC_STABILITY")
            }
        }
        return JSONObject()
            .put("cutoffUs", cutoffUs)
            .put("eligibleTorsoFrames", eligibleTorsoFrames)
            .put("longestContinuousUs", longestContinuousUs)
            .put("requiredDurationUs", requiredDurationUs)
            .put("minimumSampleCount", RETAINED_REFERENCE_MIN_SAMPLES)
            .put("evaluatedDurationWindows", evaluatedDurationWindows)
            .put("qualifyingCandidateWindows", qualifyingCandidateWindows)
            .put("qualifyingCandidateSpans", JSONArray(qualifyingCandidateSpans))
            .put("minimumJitterRatio", minimumJitter)
            .put("minimumJitterWindowStartUs", minimumJitterStartUs)
            .put("minimumJitterWindowEndUs", minimumJitterEndUs)
            .put("maximumAllowedJitterRatio", RETAINED_REFERENCE_MAX_JITTER_RATIO)
    }

    private fun retainDurationBracket(
        window: ArrayDeque<ReferenceSample>,
        endUs: Long,
        requiredDurationUs: Long,
    ) {
        while (window.size > 1) {
            val nextStartUs = window.elementAt(1).frame.timestampMs * 1000L
            if (endUs - nextStartUs < requiredDurationUs) break
            window.removeFirst()
        }
    }

    private fun referenceSample(frame: PoseFrame): ReferenceSample? {
        fun observed(id: PoseLandmarkId) = frame.landmarks[id]?.takeIf { it.isObserved(config.minimumConfidence) }
        val leftShoulder = observed(PoseLandmarkId.LEFT_SHOULDER) ?: return null
        val rightShoulder = observed(PoseLandmarkId.RIGHT_SHOULDER) ?: return null
        val leftHip = observed(PoseLandmarkId.LEFT_HIP) ?: return null
        val rightHip = observed(PoseLandmarkId.RIGHT_HIP) ?: return null
        val ls = requireNotNull(leftShoulder.position)
        val rs = requireNotNull(rightShoulder.position)
        val lh = requireNotNull(leftHip.position)
        val rh = requireNotNull(rightHip.position)
        val shoulder = midpoint(ls, rs)
        val hip = midpoint(lh, rh)
        val torsoLength = distance2d(shoulder, hip)
        if (torsoLength <= 0f) return null
        val leftDepth = (ls.z + lh.z) * .5f
        val rightDepth = (rs.z + rh.z) * .5f
        val depthMargin = abs(leftDepth - rightDepth)
        val nearerSide = when {
            depthMargin <= .01f -> VisibleSide.UNKNOWN
            leftDepth < rightDepth -> VisibleSide.LEFT
            else -> VisibleSide.RIGHT
        }
        return ReferenceSample(
            frame = frame,
            shoulder = shoulder,
            hip = hip,
            torsoLength = torsoLength,
            confidence = listOf(leftShoulder, rightShoulder, leftHip, rightHip).minOf { it.confidence },
            nearerSide = nearerSide,
        )
    }

    private fun midpoint(a: Point3, b: Point3) =
        Point3((a.x + b.x) * .5f, (a.y + b.y) * .5f, (a.z + b.z) * .5f)

    private fun averagePoint(points: List<Point3>) = Point3(
        points.map { it.x }.average().toFloat(),
        points.map { it.y }.average().toFloat(),
        points.map { it.z }.average().toFloat(),
    )

    private fun distance2d(a: Point3, b: Point3): Float {
        val dx = b.x - a.x
        val dy = b.y - a.y
        return sqrt(dx * dx + dy * dy)
    }

    private fun JSONObject.nullableString(key: String): String? =
        if (!has(key) || isNull(key)) null else getString(key)

    private companion object {
        const val HAND_TERMINAL_MINIMUM_CONFIDENCE = 0.55f
        const val HAND_TERMINAL_MAXIMUM_GAP_US = 100_000L
        const val HAND_TERMINAL_STABLE_SUFFIX_US = 100_000L
        const val HAND_TERMINAL_FILTER_WARMUP_SAMPLES = 5
        const val HAND_TERMINAL_MINIMUM_POST_WARMUP_SAMPLES = 3
        const val HAND_TERMINAL_FIXED_SPATIAL_TOLERANCE_BODY_HEIGHT_RATIO = 0.02
        const val RETAINED_REFERENCE_BASELINE_DURATION_US = 1_500_000L
        const val RETAINED_REFERENCE_MIN_SAMPLES = 12
        const val RETAINED_REFERENCE_MAX_GAP_US = 500_000L
        const val RETAINED_REFERENCE_MAX_JITTER_RATIO = 0.025f
        val RETAINED_REFERENCE_DURATION_SWEEP_US = listOf(1_500_000L, 1_200_000L, 1_000_000L, 750_000L, 500_000L)
    }
}
