package dk.lasse.karateanalyzer.audiocue

import java.security.MessageDigest

enum class AudioAnchorMethod {
    CUMULATIVE_ABSOLUTE_AMPLITUDE
}

data class AudioCueAsset(
    val cueId: String,
    val resourceName: String,
    val sha256Hex: String,
    val durationUs: Long,
    val anchorOffsetUs: Long,
    val packageVersionId: String,
    val sampleRate: Int? = 44100,
    val channelCount: Int? = 1,
    val sourceFormat: String? = "WAV_PCM_16BIT",
    val diagnosticAnchorsUs: Map<String, Long> = emptyMap(),
) {
    val anchorOffsetMs: Long get() = (anchorOffsetUs + 500L) / 1000L

    init {
        require(cueId.isNotBlank()) { "cueId must not be blank" }
        require(resourceName.isNotBlank()) { "resourceName must not be blank" }
        require(sha256Hex.length == 64) { "sha256Hex must be 64 characters hex" }
        require(durationUs >= 0) { "durationUs must be non-negative" }
        require(anchorOffsetUs >= 0) { "anchorOffsetUs must be non-negative" }
        require(anchorOffsetUs <= durationUs) { "anchorOffsetUs must not exceed durationUs" }
        require(packageVersionId.isNotBlank()) { "packageVersionId must not be blank" }
    }
}

data class AudioCuePackage(
    val packageId: String,
    val version: String,
    val anchorMethod: AudioAnchorMethod = AudioAnchorMethod.CUMULATIVE_ABSOLUTE_AMPLITUDE,
    val anchorFraction: Double = 0.10,
    val analyzerVersion: String = "cumulative_absolute_amplitude_v1",
    val createdAtMs: Long = 0L,
    val assets: Map<String, AudioCueAsset> = emptyMap(),
) {
    val packageVersionId: String get() = "$packageId:$version"

    init {
        require(packageId.isNotBlank()) { "packageId must not be blank" }
        require(version.isNotBlank()) { "version must not be blank" }
        require(anchorFraction in 0.0..1.0) { "anchorFraction must be between 0.0 and 1.0" }
    }

    fun getAsset(cueId: String): AudioCueAsset =
        assets[cueId] ?: throw IllegalArgumentException("Asset for cue $cueId not found in package $packageVersionId")

    fun findAsset(cueId: String): AudioCueAsset? = assets[cueId]

    /**
     * Minimum cadence in microseconds required between consecutive cues A and B to prevent audio truncation:
     * cadence >= duration(A) - anchor(A) + anchor(B)
     */
    fun minSafeCadenceUs(cueIdA: String, cueIdB: String): Long {
        val a = getAsset(cueIdA)
        val b = getAsset(cueIdB)
        return (a.durationUs - a.anchorOffsetUs + b.anchorOffsetUs).coerceAtLeast(0L)
    }

    /**
     * Minimum cadence in microseconds across a specific sequence of cue IDs to prevent audio cutoff:
     * max(duration(A) - anchor(A) + anchor(B)) for all consecutive pairs in sequence.
     */
    fun minSafeCadenceUs(sequence: List<String>): Long {
        if (sequence.size < 2) return 0L
        var maxRequired = 0L
        for (i in 0 until sequence.size - 1) {
            val req = minSafeCadenceUs(sequence[i], sequence[i + 1])
            if (req > maxRequired) maxRequired = req
        }
        return maxRequired
    }

    /**
     * Minimum safe cadence in milliseconds across a sequence of cue IDs (rounded up to nearest ms).
     */
    fun minSafeCadenceMs(sequence: List<String>): Long {
        val us = minSafeCadenceUs(sequence)
        return (us + 999L) / 1000L
    }

    /**
     * Verifies whether a candidate cadence in milliseconds safely accommodates the sequence without audio cutoff.
     */
    fun isCadenceSafe(cadenceMs: Long, sequence: List<String>): Boolean =
        cadenceMs >= minSafeCadenceMs(sequence)
}

sealed interface PackageIntegrityResult {
    val isValid: Boolean

    data object Valid : PackageIntegrityResult {
        override val isValid: Boolean = true
    }

    data class HashMismatch(
        val cueId: String,
        val resourceName: String,
        val expectedHash: String,
        val actualHash: String,
    ) : PackageIntegrityResult {
        override val isValid: Boolean = false
        val message: String get() =
            "Asset integrity check failed for $cueId ($resourceName): expected SHA-256 $expectedHash, got $actualHash"
    }

    data class MissingAsset(
        val cueId: String,
        val resourceName: String,
    ) : PackageIntegrityResult {
        override val isValid: Boolean = false
        val message: String get() = "Missing audio resource for $cueId ($resourceName)"
    }
}

object AudioCuePackageIntegrity {
    fun sha256Hex(bytes: ByteArray): String {
        val digest = MessageDigest.getInstance("SHA-256")
        val hash = digest.digest(bytes)
        val hexChars = CharArray(hash.size * 2)
        val hexArray = "0123456789abcdef".toCharArray()
        for (i in hash.indices) {
            val v = hash[i].toInt() and 0xFF
            hexChars[i * 2] = hexArray[v ushr 4]
            hexChars[i * 2 + 1] = hexArray[v and 0x0F]
        }
        return String(hexChars)
    }

    fun verify(
        pkg: AudioCuePackage,
        audioByteProvider: (AudioCueAsset) -> ByteArray?,
    ): PackageIntegrityResult {
        for (asset in pkg.assets.values) {
            val bytes = audioByteProvider(asset)
                ?: return PackageIntegrityResult.MissingAsset(asset.cueId, asset.resourceName)
            val actualHash = sha256Hex(bytes)
            if (!actualHash.equals(asset.sha256Hex, ignoreCase = true)) {
                return PackageIntegrityResult.HashMismatch(
                    cueId = asset.cueId,
                    resourceName = asset.resourceName,
                    expectedHash = asset.sha256Hex,
                    actualHash = actualHash,
                )
            }
        }
        return PackageIntegrityResult.Valid
    }
}

/**
 * Documents and enforces timing evidence semantics:
 * - `cue_playback_start`: App playback request/start-command timestamp (SoundPool invocation).
 *   Note: SoundPool and Android audio-routing may introduce device-specific hardware latency
 *   before sound waves physically leave the speaker.
 * - `spoken_count`: Package-defined estimated audible cue timestamp: `playback_start + immutable A10 offset`.
 *   This serves as the authoritative semantic cue reference for reaction and movement analysis.
 * - Future optional calibration: Device/audio-output latency correction applied during downstream
 *   analysis, without mutating either historical event.
 * - `LEGACY_FILE_START_CUE`: Captures captured prior to versioned audio packages, anchored at file-start.
 */
object AudioCueTimingProvenance {
    const val LEGACY_FILE_START_CUE = "LEGACY_FILE_START_CUE"
    const val AUDIO_CUE_ANCHOR_A10 = "audio_cue_anchor_A10"

    fun provenanceFor(packageVersionId: String?, timingSource: String? = null): String {
        if (packageVersionId != null) {
            return packageVersionId
        }
        if (timingSource != null && timingSource.startsWith("audio_cue_anchor")) {
            return timingSource
        }
        return LEGACY_FILE_START_CUE
    }
}

object AudioCuePackageRegistry {
    private val registry = mutableMapOf<String, AudioCuePackage>(
        JapaneseCountAudioPackage.PACKAGE_VERSION_ID to JapaneseCountAudioPackage.V1
    )

    fun register(pkg: AudioCuePackage) {
        registry[pkg.packageVersionId] = pkg
    }

    fun getPackage(packageVersionId: String): AudioCuePackage =
        registry[packageVersionId]
            ?: throw IllegalArgumentException("Audio cue package version not found: '$packageVersionId'")

    fun findPackage(packageVersionId: String?): AudioCuePackage? =
        packageVersionId?.let { registry[it] }

    fun allPackages(): List<AudioCuePackage> = registry.values.toList()
}
