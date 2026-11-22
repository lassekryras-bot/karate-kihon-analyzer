package dk.lasse.karatecliprecorder.training

import dk.lasse.karateanalyzer.capture.qom.SideViewAlternatingPunchSegmenter
import dk.lasse.karateanalyzer.capture.qom.SideViewAlternatingPunchConfig
import dk.lasse.karateanalyzer.capture.qom.QomEndBoundaryKind
import dk.lasse.karateanalyzer.capture.qom.QomDetectorConfig
import dk.lasse.karateanalyzer.capture.qom.DerivedLandmarkRepairRefusalReason
import dk.lasse.karateanalyzer.capture.qom.DerivedLandmarkProvenance
import dk.lasse.karateanalyzer.capture.retrospective.CueEvent
import dk.lasse.karateanalyzer.capture.retrospective.CueTimeline
import dk.lasse.karateanalyzer.capture.retrospective.RetrospectiveSegmenterConfig
import dk.lasse.karateanalyzer.capture.retrospective.RetrospectiveSessionSegmenter
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File
import kotlin.test.assertEquals
import kotlin.test.assertTrue as assertThat

class ExactSideViewSegmentationReplayTest {
    @Test
    fun privateReferenceMlsReplaysWithDocumentedRobustEndpointDeltas() {
        val path = System.getenv("KARATE_SIDE_VIEW_SEGMENTATION_MLS")
        assumeTrue("Private side-view replay MLS was not supplied", !path.isNullOrBlank())
        val frames = LandmarkFiles.read(File(path!!), expectedFormatId = LandmarkFiles.FORMAT_ID)

        val result = SideViewAlternatingPunchSegmenter().segment(frames)
        val endpointsUs = result.windows.map { it.startTimestampMs * 1_000L to it.endTimestampMs * 1_000L }
        println("side-view robust replay state=${result.state} candidates=${result.candidateCount} windows=${result.windows.size}")
        endpointsUs.forEachIndexed { index, endpoint -> println("${index + 1},${endpoint.first},${endpoint.second}") }

        assertEquals(20, result.candidateCount)
        assertEquals(QomEndBoundaryKind.EVIDENCE_GAP_WITHOUT_STOP_EVIDENCE, result.windows[1].endBoundaryKind)
        assertEquals(QomEndBoundaryKind.STOP_GATE, result.windows[2].endBoundaryKind)
        val executableReferenceEndpointsUs = listOf(
            1_367_000L to 2_134_000L,
            2_167_000L to 2_801_000L,
            2_801_000L to 3_434_000L,
            3_701_000L to 4_568_000L,
            4_568_000L to 5_301_000L,
            5_668_000L to 6_435_000L,
            6_635_000L to 7_269_000L,
            7_602_000L to 8_403_000L,
            8_569_000L to 9_203_000L,
            9_636_000L to 10_704_000L,
            10_904_000L to 11_771_000L,
            11_771_000L to 12_604_000L,
            12_738_000L to 13_338_000L,
            13_638_000L to 14_538_000L,
            14_705_000L to 15_439_000L,
            15_839_000L to 16_639_000L,
            16_639_000L to 17_406_000L,
            17_640_000L to 18_440_000L,
            18_640_000L to 19_407_000L,
            19_707_000L to 20_674_000L,
        )
        val approvedExclusionEndpointsUs = listOf(
            1_367_000L to 2_134_000L,
            2_167_000L to 2_434_000L,
            2_901_000L to 3_434_000L,
            3_701_000L to 4_568_000L,
            4_568_000L to 5_301_000L,
            5_668_000L to 6_435_000L,
            6_635_000L to 7_269_000L,
            7_602_000L to 8_403_000L,
            8_569_000L to 9_203_000L,
            9_636_000L to 10_704_000L,
            10_904_000L to 11_771_000L,
            11_771_000L to 12_604_000L,
            12_738_000L to 13_338_000L,
            13_638_000L to 14_538_000L,
            14_705_000L to 15_439_000L,
            15_839_000L to 16_639_000L,
            16_639_000L to 17_406_000L,
            17_640_000L to 18_440_000L,
            18_640_000L to 19_374_000L,
            19_707_000L to 20_674_000L,
        )
        assertEquals(approvedExclusionEndpointsUs, endpointsUs)
        val historicalV1EndpointsUs = SideViewAlternatingPunchSegmenter(
            SideViewAlternatingPunchConfig.from(QomDetectorConfig.sideViewAlternatingPunchV1()),
        ).segment(frames).windows.map { it.startTimestampMs * 1_000L to it.endTimestampMs * 1_000L }
        assertEquals(approvedExclusionEndpointsUs, historicalV1EndpointsUs)
        val changedEndpoints = executableReferenceEndpointsUs.indices.flatMap { index ->
            buildList {
                val reference = executableReferenceEndpointsUs[index]
                val robust = endpointsUs[index]
                if (reference.first != robust.first) add("${index + 1}-start" to (robust.first - reference.first))
                if (reference.second != robust.second) add("${index + 1}-end" to (robust.second - reference.second))
            }
        }.toMap()
        assertEquals(mapOf(
            "2-end" to -367_000L,
            "3-start" to 100_000L,
            "19-end" to -33_000L,
        ), changedEndpoints)

        val refused = result.derivedFrames.flatMap { frame -> frame.landmarks.values }
            .filter { it.repairRefusalReason == DerivedLandmarkRepairRefusalReason.ANCHOR_SPAN_EXCEEDS_LIMIT }
        assertThat(refused.any { it.repairAnchorSpanMs == 233L && it.rejectedObservedPoint != null })
        assertThat(refused.all { it.point == null })
        assertThat(result.derivedFrames.any { frame ->
            frame.landmarks.values.any { it.provenance == DerivedLandmarkProvenance.UNRESOLVED_REJECTED } &&
                frame.instantaneousArmQom != null
        }, "Usable landmarks may still contribute while rejected points remain excluded")

        val retrospective = RetrospectiveSessionSegmenter(RetrospectiveSegmenterConfig(
            qomConfig = QomDetectorConfig.sideViewAlternatingPunchV2(),
        ))
        val withoutCues = retrospective.segment("recording", "private.mls", frames)
        val withCues = retrospective.segment(
            "recording",
            "private.mls",
            frames,
            CueTimeline("recording", result.windows.mapIndexed { index, window ->
                CueEvent("cue-${index + 1}", "${index + 1}", window.startTimestampMs, window.startTimestampMs)
            }),
        )
        fun movementEndpoints(result: dk.lasse.karateanalyzer.capture.retrospective.RetrospectiveSessionResult) =
            result.movements.map { it.logicalStartTimestampMs to it.logicalEndTimestampMs }
        assertEquals(movementEndpoints(withoutCues), movementEndpoints(withCues), "Cues cannot alter detection")
        assertEquals(20, withCues.movements.count { it.associatedCue != null })
        assertEquals(1_217L, withCues.movements.first().retainedStartTimestampMs)
        assertEquals(2_334L, withCues.movements.first().retainedEndTimestampMs)
        assertEquals(
            QomEndBoundaryKind.EVIDENCE_GAP_WITHOUT_STOP_EVIDENCE,
            withCues.movements[1].endBoundaryKind,
        )
    }
}
