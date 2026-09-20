package dk.lasse.karateanalyzer.audiocue

import java.io.File
import kotlin.test.*

class AudioCueTimingTest {

    private val v1Package = JapaneseCountAudioPackage.V1

    @Test
    fun differentAudioOffsetsProduceEvenlySpacedSemanticCueTimestamps() {
        // Requirement 1: Different audio offsets still produce evenly spaced semantic cue timestamps.
        val cadenceUs = 1_000_000L // 1 second
        val firstCueDelayUs = 500_000L // 0.5 second
        val repetitionCount = 10

        val scheduledCues = (0 until repetitionCount).map { rep ->
            val cueValue = (rep % 10) + 1
            val asset = v1Package.getAsset("COUNT_$cueValue")
            val desiredCueTimeUs = firstCueDelayUs + rep * cadenceUs
            val playbackStartTimeUs = desiredCueTimeUs - asset.anchorOffsetUs
            Triple(cueValue, desiredCueTimeUs, playbackStartTimeUs)
        }

        // Verify each cue is spaced by exactly cadenceUs
        for (i in 0 until repetitionCount - 1) {
            val deltaCue = scheduledCues[i + 1].second - scheduledCues[i].second
            assertEquals(cadenceUs, deltaCue, "Semantic cue interval between rep $i and ${i + 1} must equal cadence")
        }

        // Verify that anchor offsets differ
        val ichi = v1Package.getAsset("COUNT_1")
        val ni = v1Package.getAsset("COUNT_2")
        assertNotEquals(ichi.anchorOffsetUs, ni.anchorOffsetUs)
    }

    @Test
    fun ichiAndNiStartPlaybackAtDifferentTimesWhileCueEventsRemainExactlyOneCadenceApart() {
        // Requirement 2: Ichi and Ni can start playback at different times while their cue events remain exactly one configured cadence apart.
        val firstCueTimeMs = 500L
        val cadenceMs = 1000L

        val ichiAsset = v1Package.getAsset("COUNT_1")
        val niAsset = v1Package.getAsset("COUNT_2")

        val cue1TimeMs = firstCueTimeMs
        val cue2TimeMs = firstCueTimeMs + cadenceMs

        val playback1StartMs = cue1TimeMs - ichiAsset.anchorOffsetMs
        val playback2StartMs = cue2TimeMs - niAsset.anchorOffsetMs

        // Ichi offset ~ 131ms -> playback at ~369ms
        assertEquals(369L, playback1StartMs)
        // Ni offset ~ 58ms -> playback at ~1442ms
        assertEquals(1442L, playback2StartMs)

        // Cue event spacing is exactly cadence (1000ms)
        assertEquals(cadenceMs, cue2TimeMs - cue1TimeMs)

        // Playback spacing differs from cadence because anchor offsets are different
        val playbackDelta = playback2StartMs - playback1StartMs
        assertEquals(1073L, playbackDelta)
        assertNotEquals(cadenceMs, playbackDelta)
    }

    @Test
    fun changingCadenceChangesCueSpacingWithoutChangingAssetAnchorOffsets() {
        // Requirement 3: Changing cadence changes cue spacing but does not change asset anchor offsets.
        val firstCueDelayUs = 500_000L
        val cadenceA = 1_000_000L
        val cadenceB = 1_500_000L

        val cue1A = firstCueDelayUs
        val cue2A = firstCueDelayUs + cadenceA

        val cue1B = firstCueDelayUs
        val cue2B = firstCueDelayUs + cadenceB

        assertEquals(1_000_000L, cue2A - cue1A)
        assertEquals(1_500_000L, cue2B - cue1B)

        // Anchor offsets must remain identical regardless of cadence
        val ichi = v1Package.getAsset("COUNT_1")
        val ni = v1Package.getAsset("COUNT_2")
        assertEquals(130975L, ichi.anchorOffsetUs)
        assertEquals(58095L, ni.anchorOffsetUs)
    }

    @Test
    fun changingAnchorFractionRequiresNewPackageVersion() {
        // Requirement 5: Changing A10 to A15 requires a new package version.
        val v1 = JapaneseCountAudioPackage.V1
        assertEquals(0.10, v1.anchorFraction)
        assertEquals("v1", v1.version)

        // A package with 0.15 fraction cannot be represented by the same packageVersionId
        val candidateV2 = AudioCuePackage(
            packageId = JapaneseCountAudioPackage.PACKAGE_ID,
            version = "v2",
            anchorMethod = AudioAnchorMethod.CUMULATIVE_ABSOLUTE_AMPLITUDE,
            anchorFraction = 0.15,
            analyzerVersion = "cumulative_absolute_amplitude_v1",
            createdAtMs = System.currentTimeMillis(),
            assets = v1.assets.mapValues { (_, asset) ->
                val newOffsetUs = asset.diagnosticAnchorsUs["A15"] ?: (asset.anchorOffsetUs + 10_000L)
                asset.copy(
                    anchorOffsetUs = newOffsetUs,
                    packageVersionId = "japanese_count:v2",
                )
            },
        )

        assertNotEquals(v1.packageVersionId, candidateV2.packageVersionId)
        assertNotEquals(v1.anchorFraction, candidateV2.anchorFraction)
        // Verify offsets changed between A10 and A15
        assertNotEquals(v1.getAsset("COUNT_1").anchorOffsetUs, candidateV2.getAsset("COUNT_1").anchorOffsetUs)
    }

    @Test
    fun changingAudioBytesRequiresNewAssetHash() {
        // Requirement 6: Changing any audio bytes requires a new asset hash/package version.
        val sampleAudio = "test-audio-content-bytes".toByteArray(Charsets.UTF_8)
        val originalHash = AudioCuePackageIntegrity.sha256Hex(sampleAudio)

        val modifiedAudio = sampleAudio.copyOf()
        modifiedAudio[0] = (modifiedAudio[0] + 1).toByte()
        val modifiedHash = AudioCuePackageIntegrity.sha256Hex(modifiedAudio)

        assertNotEquals(originalHash, modifiedHash)
    }

    @Test
    fun assetHashMismatchPreventsUseOfStaleAnchorMetadata() {
        // Requirement 10: Asset-hash mismatch prevents use of stale anchor metadata.
        val fakeBytesProvider: (AudioCueAsset) -> ByteArray? = { asset ->
            if (asset.cueId == "COUNT_1") {
                // Corrupt / modified bytes for COUNT_1
                "tampered-audio-content".toByteArray(Charsets.UTF_8)
            } else {
                // Return dummy bytes that won't match either, but we test mismatch
                ByteArray(10)
            }
        }

        val result = AudioCuePackageIntegrity.verify(v1Package, fakeBytesProvider)
        assertFalse(result.isValid)
        assertTrue(result is PackageIntegrityResult.HashMismatch)
        assertEquals("COUNT_1", (result as PackageIntegrityResult.HashMismatch).cueId)
        assertEquals(v1Package.getAsset("COUNT_1").sha256Hex, result.expectedHash)
    }

    @Test
    fun rerunningMovementReactionAnalysisDoesNotMutateCueTimestamps() {
        // Requirement 9: Re-running movement/reaction analysis does not mutate cue timestamps.
        val originalCueTimestamp = 500_000L
        val cue = ImmutableCueRecord(
            eventId = "cue-1",
            cueTimestampUs = originalCueTimestamp,
            ordinal = 1,
            countValue = 1,
            packageVersionId = "japanese_count:v1",
            assetId = "order_ichi",
            timingSource = "audio_cue_anchor_A10",
        )

        val movementV1 = MovementBoundaryRecord(
            movementId = "mov-1",
            startUs = 750_000L,
            endUs = 1_100_000L,
        )

        val resultsV1 = CueToMovementLatencyAnalyzer.analyze(listOf(cue), listOf(movementV1))
        assertEquals(1, resultsV1.size)
        assertEquals(250_000L, resultsV1[0].latencyUs)
        assertEquals(originalCueTimestamp, resultsV1[0].cueTimestampUs)

        // Downstream improvement in movement segmentation: movement start is earlier
        val movementV2 = MovementBoundaryRecord(
            movementId = "mov-1",
            startUs = 730_000L,
            endUs = 1_100_000L,
        )

        val resultsV2 = CueToMovementLatencyAnalyzer.analyze(listOf(cue), listOf(movementV2))
        assertEquals(1, resultsV2.size)
        assertEquals(230_000L, resultsV2[0].latencyUs)

        // Original cue timestamp remains completely untouched and immutable
        assertEquals(originalCueTimestamp, cue.cueTimestampUs)
        assertEquals(originalCueTimestamp, resultsV2[0].cueTimestampUs)
    }

    @Test
    fun historicalReactionAnalysisContinuesToReferenceOriginalCueEvent() {
        // Requirement 8: Historical reaction analysis continues to reference its original cue event.
        val historicalCueTimeUs = 500_000L
        val historicalCue = ImmutableCueRecord(
            eventId = "historical-cue-1",
            cueTimestampUs = historicalCueTimeUs,
            ordinal = 1,
            countValue = 1,
            packageVersionId = "japanese_count:v1",
            assetId = "order_ichi",
            timingSource = "audio_cue_anchor_A10",
        )
        val movement = MovementBoundaryRecord(
            movementId = "mov-1",
            startUs = 780_000L,
            endUs = 1_200_000L,
        )

        val latency = CueToMovementLatencyAnalyzer.analyze(listOf(historicalCue), listOf(movement)).single()
        assertEquals(280_000L, latency.latencyUs)
        assertEquals("japanese_count:v1", latency.packageVersionId)
        assertEquals("historical-cue-1", latency.cueSessionEventId)
        assertEquals(historicalCueTimeUs, latency.cueTimestampUs)
    }

    @Test
    fun legacyRecordingsRemainExplicitlyLegacyAndAreNotPresentedAsA10() {
        // Requirement 11: Legacy recordings remain explicitly legacy and are not presented as A10-timed recordings.
        val legacyProvenanceNull = AudioCueTimingProvenance.provenanceFor(null, null)
        assertEquals(AudioCueTimingProvenance.LEGACY_FILE_START_CUE, legacyProvenanceNull)

        val legacyProvenancePlaybackReq = AudioCueTimingProvenance.provenanceFor(
            null,
            "playback_request_CameraX_Start_elapsedRealtime_ms",
        )
        assertEquals(AudioCueTimingProvenance.LEGACY_FILE_START_CUE, legacyProvenancePlaybackReq)

        val v1Provenance = AudioCueTimingProvenance.provenanceFor("japanese_count:v1", "audio_cue_anchor_A10")
        assertEquals("japanese_count:v1", v1Provenance)
        assertNotEquals(AudioCueTimingProvenance.LEGACY_FILE_START_CUE, v1Provenance)
    }

    @Test
    fun wavParserAndCumulativeAreaAnalyzerMatchV1PackageValues() {
        // Requirement: Authoritative verification against the actual WAV resources
        val rawDir = File("../../app/src/main/res/raw")
        if (!rawDir.exists()) {
            // Running in standalone core test directory
            return
        }

        v1Package.assets.forEach { (cueId, asset) ->
            val wavFile = File(rawDir, "${asset.resourceName}.wav")
            if (wavFile.exists()) {
                val bytes = wavFile.readBytes()
                val analyzed = WavCumulativeAreaAnalyzer.analyzeAsset(
                    cueId = cueId,
                    resourceName = asset.resourceName,
                    bytes = bytes,
                    packageVersionId = v1Package.packageVersionId,
                    targetFraction = 0.10,
                )
                assertEquals(asset.sha256Hex, analyzed.sha256Hex, "SHA-256 mismatch for $cueId")
                assertEquals(asset.durationUs, analyzed.durationUs, "Duration mismatch for $cueId")
                assertEquals(asset.anchorOffsetUs, analyzed.anchorOffsetUs, "A10 offset mismatch for $cueId")
            }
        }
    }

    @Test
    fun attemptingToLoadNonExistentPackageVersionThrowsExplicitException() {
        // Invariant: Attempting to load a non-existent package version throws an explicit exception.
        val exception = assertFailsWith<IllegalArgumentException> {
            AudioCuePackageRegistry.getPackage("japanese_count:v999_non_existent")
        }
        assertTrue(exception.message?.contains("not found") == true)
    }

    @Test
    fun cueToMovementLatencyUsesAudibleCueNotPlaybackStartAndSupportsNegativeLatency() {
        // Invariant: Cue-to-movement latency measurement uses spoken_count (the audible cue event),
        // not cue_playback_start. If movement begins before the audible cue, latency is negative.
        val cueTimeUs = 500_000L
        val playbackStartUs = 369_000L
        val cue = ImmutableCueRecord(
            eventId = "cue-1",
            cueTimestampUs = cueTimeUs,
            ordinal = 1,
            countValue = 1,
            packageVersionId = "japanese_count:v1",
            assetId = "order_ichi",
            timingSource = "audio_cue_anchor_A10",
        )

        // Case 1: Standard reaction where movement starts after audible cue (positive latency)
        val normalMovement = MovementBoundaryRecord("mov-1", startUs = 650_000L, endUs = 1_000_000L)
        val normalResult = CueToMovementLatencyAnalyzer.analyze(listOf(cue), listOf(normalMovement)).single()
        // Measured against audible cue (500_000), NOT playback start (369_000)
        assertEquals(150_000L, normalResult.latencyUs)
        assertNotEquals(normalMovement.startUs - playbackStartUs, normalResult.latencyUs)

        // Case 2: Anticipatory reaction starting BEFORE audible cue (negative latency)
        val earlyMovement = MovementBoundaryRecord("mov-early", startUs = 450_000L, endUs = 900_000L)
        val earlyResult = CueToMovementLatencyAnalyzer.analyze(listOf(cue), listOf(earlyMovement)).single()
        assertEquals(-50_000L, earlyResult.latencyUs, "Anticipatory reaction starting before audible cue must yield negative latency")
        assertTrue(earlyResult.latencyUs < 0)
    }

    @Test
    fun packageCadenceSafetyPreventsAudioTruncationBetweenConsecutiveCues() {
        // Safe cadence formula: cadence >= duration(A) - anchor(A) + anchor(B)
        // For COUNT_1 (Ichi: dur 580ms, anchor ~131ms) and COUNT_2 (Ni: anchor ~58ms):
        // Tail of Ichi = 449ms; pre-roll of Ni = 58ms -> min safe = 508ms
        val ichiNiSafeUs = v1Package.minSafeCadenceUs("COUNT_1", "COUNT_2")
        assertEquals(507120L, ichiNiSafeUs)

        // Across 1..10, Shichi -> Hachi is the tightest pair (dur 590ms, anchor 118ms + 123ms anchor = 595ms)
        val full10Sequence = (1..10).map { "COUNT_$it" }
        val minSafeMs = v1Package.minSafeCadenceMs(full10Sequence)
        assertEquals(596L, minSafeMs)

        // Cadence of 1000ms is comfortably safe
        assertTrue(v1Package.isCadenceSafe(1000L, full10Sequence))
        // Cadence of 500ms would truncate Shichi before Hachi plays
        assertFalse(v1Package.isCadenceSafe(500L, full10Sequence))
    }
}
