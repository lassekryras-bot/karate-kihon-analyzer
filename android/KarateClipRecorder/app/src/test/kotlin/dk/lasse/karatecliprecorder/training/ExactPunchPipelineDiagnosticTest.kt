package dk.lasse.karatecliprecorder.training

import dk.lasse.karateanalyzer.capture.LateralSide
import dk.lasse.karateanalyzer.capture.qom.MotionBodyProfile
import dk.lasse.karateanalyzer.capture.qom.QomFrameEvidence
import dk.lasse.karateanalyzer.core.LandmarkSource
import dk.lasse.karateanalyzer.core.Point3
import dk.lasse.karateanalyzer.core.PoseFrame
import dk.lasse.karateanalyzer.core.PoseLandmarkId
import dk.lasse.karateanalyzer.core.PoseLandmarkSample
import dk.lasse.karateanalyzer.core.PunchHeightAnalyzer
import dk.lasse.karateanalyzer.geometry.AspectCorrectPoint
import dk.lasse.karateanalyzer.geometry.CanonicalGeometryCodec
import dk.lasse.karateanalyzer.geometry.FrameGeometry
import dk.lasse.karateanalyzer.geometry.FrameGeometryMath
import dk.lasse.karateanalyzer.geometry.SourceNormalizedPoint
import dk.lasse.karateanalyzer.impact.BodyScaleEvidence
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File
import kotlin.math.abs
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

    private fun JSONObject.nullableString(key: String): String? =
        if (!has(key) || isNull(key)) null else getString(key)
}
