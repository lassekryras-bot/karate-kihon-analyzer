package dk.lasse.karatecliprecorder.training

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import dk.lasse.karateanalyzer.capture.retrospective.VideoPoseProcessor
import dk.lasse.karateanalyzer.core.StraightPunchPairedArmAnalyzer
import dk.lasse.karateanalyzer.geometry.CanonicalGeometryCodec
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File

/** Private, opt-in replay. Review labels are comparators only and never analyzer inputs. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28])
class ExactPairedArmSideReplayTest {
    @Test fun `exact movements expose paired full-window evidence`() {
        val inputPath = System.getenv("KARATE_PUNCH_DIAGNOSTIC_JSON")
        val mlsPath = System.getenv("KARATE_PUNCH_DIAGNOSTIC_MLS")
        assumeTrue("Private exact replay inputs were not supplied", !inputPath.isNullOrBlank() && !mlsPath.isNullOrBlank())
        val input = JSONObject(File(inputPath!!).readText())
        val geometry = requireNotNull(CanonicalGeometryCodec.decode(input.getString("geometry")))
        val frames = LandmarkFiles.read(File(mlsPath!!), input.getString("trackHash"), LandmarkFiles.FORMAT_ID)
        val movements = input.getJSONArray("movements")
        val output = JSONArray()
        for (index in 0 until movements.length()) {
            val movement = movements.getJSONObject(index)
            val result = StraightPunchPairedArmAnalyzer.analyze(
                movement.getString("movementId"), input.getString("trackId"), geometry,
                movement.getLong("startUs"), movement.getLong("endUs"), frames)
            output.put(JSONObject()
                .put("ordinal", index + 1)
                .put("reviewSide", movement.getString("reviewSide"))
                .put("oldSide", movement.opt("currentSide"))
                .put("oldReason", movement.opt("sideReason"))
                .put("newSide", result.side?.name)
                .put("newReason", result.decisionReason.name)
                .put("score", result.pairedScoreMeters)
                .put("axisStatus", result.forwardAxis.status.name)
                .put("axis", result.forwardAxis.axis?.let { JSONArray(listOf(it.x, it.y, it.z)) })
                .put("axisCoverage", result.forwardAxis.coverage)
                .put("validCoverage", result.validCoverage)
                .put("supportMode", result.supportMode.name)
                .put("leftContribution", result.leftArm.normalizedContributionMeters)
                .put("rightContribution", result.rightArm.normalizedContributionMeters)
                .put("leftTravel", result.leftArm.signedNetTravelMeters)
                .put("rightTravel", result.rightArm.signedNetTravelMeters)
                .put("leftSupportUs", result.leftArm.supportedDurationUs)
                .put("rightSupportUs", result.rightArm.supportedDurationUs)
                .put("bilateralSupportUs", result.bilateralSupportedDurationUs))
        }
        val report = JSONObject().put("contract", "exact-paired-arm-side-replay-v1").put("movements", output)
        val outputPath = System.getenv("KARATE_PUNCH_PAIRED_OUTPUT")
        if (outputPath.isNullOrBlank()) println(report.toString(2)) else File(outputPath).writeText(report.toString(2))
    }

    @Test fun `exact movements pass through processor persist and reopen paired provenance`() {
        val inputPath = System.getenv("KARATE_PUNCH_DIAGNOSTIC_JSON")
        val mlsPath = System.getenv("KARATE_PUNCH_DIAGNOSTIC_MLS")
        assumeTrue("Private exact replay inputs were not supplied", !inputPath.isNullOrBlank() && !mlsPath.isNullOrBlank())
        val input = JSONObject(File(inputPath!!).readText())
        val exactGeometry = requireNotNull(CanonicalGeometryCodec.decode(input.getString("geometry")))
        val frames = LandmarkFiles.read(File(mlsPath!!), input.getString("trackHash"), LandmarkFiles.FORMAT_ID)
        val sourceMovements = input.getJSONArray("movements")
        val context = ApplicationProvider.getApplicationContext<Context>()
        val databaseName = "exact-paired-replay.db"
        val directory = kotlin.io.path.createTempDirectory("exact-paired-replay").toFile()
        context.deleteDatabase(databaseName)
        context.getDatabasePath(databaseName).parentFile?.mkdirs()
        fun open() = Room.databaseBuilder(context, KarateTrainingDatabase::class.java, databaseName)
            .allowMainThreadQueries().build()
        var database = open()
        try {
            var repository = TrainingRepository(database)
            val user = TrainingUser()
            repository.createUser(user)
            val session = RecordingSession(userId = user.userId, startedAtMs = 1,
                activityKey = "guided_jodan_session", guided = true, expectedRepetitions = 10)
            val video = File(directory, "source.mp4").apply { writeText("exact replay uses retained MLS") }
            repository.beginSession(session, MasterRecording(recordingId = exactGeometry.recordingId,
                sessionId = session.sessionId, filePath = video.path, createdAtMs = 1,
                width = exactGeometry.encodedWidth ?: exactGeometry.canonicalWidth,
                height = exactGeometry.encodedHeight ?: exactGeometry.canonicalHeight,
                rotation = exactGeometry.containerRotation ?: exactGeometry.sourceToCanonicalTransform.rotationDegrees,
                canonicalGeometryJson = CanonicalGeometryCodec.encode(exactGeometry.copy(landmarkTrackId = null))))
            repository.finishRecording(session.sessionId, frames.last().timestampMs * 1_000L)
            repository.addEvent(SessionEvent(sessionId = session.sessionId, type = MotionActivityPlans.EVENT_TYPE,
                timestampUs = 0, data = MotionActivityPlans.ALTERNATING_PUNCH,
                timingSource = MotionActivityPlans.CAPTURE_ORIGIN))
            for (index in 0 until sourceMovements.length()) {
                val source = sourceMovements.getJSONObject(index)
                repository.addEvent(SessionEvent(sessionId = session.sessionId, type = "spoken_count",
                    timestampUs = source.getLong("startUs"), data = "ordinal=${index + 1}"))
            }
            repository.enqueue(session.sessionId)
            val decoder = object : VideoPoseProcessor {
                override fun processVideo(videoFile: File, onProgress: (Float, Long) -> Unit) = frames
            }
            val processor = TrainingSessionProcessor(repository, directory, decoder, "exact-retained-track-v1")
            kotlin.test.assertEquals(10, processor.process(session.sessionId))

            fun retainedRows(repo: TrainingRepository): JSONArray = JSONArray().apply {
                for (index in 0 until sourceMovements.length()) {
                    val expected = sourceMovements.getJSONObject(index)
                    val evidence = kotlin.test.assertNotNull(repo.movementEvidence(session.sessionId, index + 1))
                    kotlin.test.assertEquals(expected.getLong("startUs"), evidence.movement.startUs)
                    kotlin.test.assertEquals(expected.getLong("endUs"), evidence.movement.endUs)
                    kotlin.test.assertEquals(expected.getString("segmentationEndBoundaryKind"),
                        evidence.movement.segmentationEndBoundaryKind)
                    val analysis = evidence.analyses.single { it.analyzerKey == MovementMotionAnalysis.policy.analyzerKey }
                    val json = JSONObject(kotlin.test.assertNotNull(analysis.geometryJson))
                    kotlin.test.assertEquals("movement-motion-v3", json.getString("contract"))
                    kotlin.test.assertEquals(expected.getString("reviewSide"), json.getString("side"))
                    kotlin.test.assertEquals("PAIRED_ARM_SCORE", json.getString("sideReason"))
                    val paired = json.getJSONObject("pairedArmEvidence")
                    val left = paired.getJSONObject("left")
                    val right = paired.getJSONObject("right")
                    kotlin.test.assertEquals(paired.getDouble("scoreMeters"),
                        left.getDouble("normalizedContributionMeters") + right.getDouble("normalizedContributionMeters"), 1e-8)
                    put(JSONObject()
                        .put("ordinal", index + 1)
                        .put("startUs", expected.getLong("startUs"))
                        .put("endUs", expected.getLong("endUs"))
                        .put("reviewSide", expected.getString("reviewSide"))
                        .put("oldSide", expected.opt("currentSide"))
                        .put("oldReason", expected.opt("sideReason"))
                        .put("newSide", json.getString("side"))
                        .put("newReason", json.getString("sideReason"))
                        .put("pairedScoreMeters", paired.getDouble("scoreMeters"))
                        .put("supportMode", paired.getString("supportMode"))
                        .put("validCoverage", paired.getDouble("validCoverage"))
                        .put("axisStatus", paired.getString("axisStatus"))
                        .put("axisSource", paired.optString("axisSource"))
                        .put("leftContributionMeters", left.getDouble("normalizedContributionMeters"))
                        .put("rightContributionMeters", right.getDouble("normalizedContributionMeters"))
                        .put("leftTravelMeters", left.getDouble("signedNetTravelMeters"))
                        .put("rightTravelMeters", right.getDouble("signedNetTravelMeters"))
                        .put("leftSupportUs", left.getLong("supportedDurationUs"))
                        .put("rightSupportUs", right.getLong("supportedDurationUs"))
                        .put("bilateralSupportUs", paired.getLong("bilateralSupportedDurationUs")))
                }
            }
            val beforeClose = retainedRows(repository)
            database.close()
            database = open()
            repository = TrainingRepository(database)
            kotlin.test.assertEquals(beforeClose.toString(), retainedRows(repository).toString())
            val report = JSONObject().put("contract", "exact-paired-arm-normal-path-replay-v1")
                .put("movements", beforeClose)
            val outputPath = System.getenv("KARATE_PUNCH_PAIRED_PROCESSOR_OUTPUT")
            if (outputPath.isNullOrBlank()) println(report.toString(2)) else File(outputPath).writeText(report.toString(2))
        } finally {
            database.close()
            context.deleteDatabase(databaseName)
            directory.deleteRecursively()
        }
    }
}
