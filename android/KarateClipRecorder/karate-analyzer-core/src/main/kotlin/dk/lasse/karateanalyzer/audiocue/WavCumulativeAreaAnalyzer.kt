package dk.lasse.karateanalyzer.audiocue

import java.io.ByteArrayInputStream
import java.io.InputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.abs
import kotlin.math.roundToLong

data class WavAudioData(
    val sampleRate: Int,
    val channelCount: Int,
    val bitsPerSample: Int,
    val samples: DoubleArray, // normalized or raw amplitude per frame (averaged across channels if multi-channel)
    val durationUs: Long,
    val sha256Hex: String,
)

object WavCumulativeAreaAnalyzer {

    fun parseWav(bytes: ByteArray): WavAudioData {
        val sha256Hex = AudioCuePackageIntegrity.sha256Hex(bytes)
        val buffer = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)

        // Check RIFF header
        val riff = ByteArray(4)
        buffer.get(riff)
        check(String(riff) == "RIFF") { "Not a valid RIFF file" }
        buffer.int // chunkSize
        val wave = ByteArray(4)
        buffer.get(wave)
        check(String(wave) == "WAVE") { "Not a valid WAVE file" }

        var sampleRate = 0
        var channelCount = 0
        var bitsPerSample = 0
        var audioFormat = 0
        var pcmData: ByteArray? = null

        while (buffer.remaining() >= 8) {
            val chunkIdBytes = ByteArray(4)
            buffer.get(chunkIdBytes)
            val chunkId = String(chunkIdBytes)
            val chunkSize = buffer.int

            when (chunkId) {
                "fmt " -> {
                    audioFormat = buffer.short.toInt() and 0xFFFF
                    channelCount = buffer.short.toInt() and 0xFFFF
                    sampleRate = buffer.int
                    buffer.int // byteRate
                    buffer.short // blockAlign
                    bitsPerSample = buffer.short.toInt() and 0xFFFF
                    // Skip any extra format bytes if present
                    val extra = chunkSize - 16
                    if (extra > 0) {
                        buffer.position(buffer.position() + extra)
                    }
                }
                "data" -> {
                    val dataBytes = ByteArray(chunkSize.coerceAtMost(buffer.remaining()))
                    buffer.get(dataBytes)
                    pcmData = dataBytes
                }
                else -> {
                    // Skip unknown chunk
                    val skip = chunkSize.coerceAtMost(buffer.remaining())
                    buffer.position(buffer.position() + skip)
                }
            }
        }

        check(audioFormat == 1) { "Only PCM audio format is supported (found: $audioFormat)" }
        check(bitsPerSample == 16) { "Only 16-bit PCM audio is supported (found: $bitsPerSample)" }
        check(channelCount in 1..2) { "Only mono or stereo audio is supported (found: $channelCount)" }
        check(sampleRate > 0) { "Invalid sample rate: $sampleRate" }
        val data = requireNotNull(pcmData) { "Missing data chunk in WAV file" }

        val bytesPerFrame = channelCount * 2
        val numFrames = data.size / bytesPerFrame
        val sampleBuffer = ByteBuffer.wrap(data).order(ByteOrder.LITTLE_ENDIAN)
        val amplitudes = DoubleArray(numFrames)

        for (i in 0 until numFrames) {
            var sum = 0.0
            for (c in 0 until channelCount) {
                val sampleShort = sampleBuffer.short.toDouble()
                sum += abs(sampleShort)
            }
            amplitudes[i] = sum / channelCount
        }

        val durationUs = ((numFrames.toDouble() / sampleRate) * 1_000_000.0).roundToLong()

        return WavAudioData(
            sampleRate = sampleRate,
            channelCount = channelCount,
            bitsPerSample = bitsPerSample,
            samples = amplitudes,
            durationUs = durationUs,
            sha256Hex = sha256Hex,
        )
    }

    /**
     * Compute cumulative absolute amplitude and find the offset in integer microseconds
     * for a given fraction (e.g. 0.10 for A10).
     */
    fun calculateAnchorOffsetUs(samples: DoubleArray, sampleRate: Int, fraction: Double): Long {
        require(fraction in 0.0..1.0) { "fraction must be between 0.0 and 1.0" }
        if (samples.isEmpty()) return 0L

        val cumsum = DoubleArray(samples.size)
        var total = 0.0
        for (i in samples.indices) {
            total += samples[i]
            cumsum[i] = total
        }

        if (total == 0.0) return 0L

        val target = total * fraction
        var targetIndex = 0
        while (targetIndex < cumsum.size && cumsum[targetIndex] < target) {
            targetIndex++
        }
        if (targetIndex >= cumsum.size) {
            targetIndex = cumsum.size - 1
        }

        return ((targetIndex.toDouble() / sampleRate) * 1_000_000.0).roundToLong()
    }

    /**
     * Analyze WAV bytes and build a full AudioCueAsset.
     */
    fun analyzeAsset(
        cueId: String,
        resourceName: String,
        bytes: ByteArray,
        packageVersionId: String,
        targetFraction: Double = 0.10,
    ): AudioCueAsset {
        val parsed = parseWav(bytes)
        val anchorOffsetUs = calculateAnchorOffsetUs(parsed.samples, parsed.sampleRate, targetFraction)

        val diagnosticFractions = listOf(
            "A5" to 0.05,
            "A10" to 0.10,
            "A15" to 0.15,
            "A20" to 0.20,
            "A98" to 0.98,
        )
        val diagnosticAnchors = diagnosticFractions.associate { (label, frac) ->
            label to calculateAnchorOffsetUs(parsed.samples, parsed.sampleRate, frac)
        }

        return AudioCueAsset(
            cueId = cueId,
            resourceName = resourceName,
            sha256Hex = parsed.sha256Hex,
            durationUs = parsed.durationUs,
            anchorOffsetUs = anchorOffsetUs,
            packageVersionId = packageVersionId,
            sampleRate = parsed.sampleRate,
            channelCount = parsed.channelCount,
            sourceFormat = "WAV_PCM_16BIT",
            diagnosticAnchorsUs = diagnosticAnchors,
        )
    }
}

