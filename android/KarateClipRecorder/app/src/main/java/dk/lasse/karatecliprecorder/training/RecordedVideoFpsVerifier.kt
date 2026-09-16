package dk.lasse.karatecliprecorder.training

import android.media.MediaExtractor
import android.media.MediaFormat
import android.media.MediaMetadataRetriever
import java.io.File
import java.util.Locale

data class FpsVerificationResult(
    val actualFps: Double?,
    val isVerified: Boolean,
    val details: String,
)

object RecordedVideoFpsVerifier {

    /**
     * Inspects the finalized MP4 recording and evaluates whether the actual
     * encoded video frame rate matches the requested target (e.g. 60 or 30 fps),
     * accounting for normal slight tolerances (such as 59.94 fps or 29.97 fps).
     */
    fun verify(file: File, requestedFps: Int): FpsVerificationResult {
        val measuredFps = extractActualFps(file) ?: return FpsVerificationResult(
            actualFps = null,
            isVerified = true,
            details = "FPS could not be extracted from media container.",
        )

        return evaluateFps(measuredFps, requestedFps)
    }

    fun evaluateFps(measuredFps: Double, requestedFps: Int): FpsVerificationResult {
        val formattedFps = String.format(Locale.US, "%.2f", measuredFps)
        return when (requestedFps) {
            60 -> {
                if (measuredFps in 55.0..65.0) {
                    FpsVerificationResult(
                        actualFps = measuredFps,
                        isVerified = true,
                        details = "Confirmed ~60fps recording ($formattedFps fps).",
                    )
                } else {
                    FpsVerificationResult(
                        actualFps = measuredFps,
                        isVerified = false,
                        details = "Requested 60fps, but output video was recorded at $formattedFps fps (target frame rate not met).",
                    )
                }
            }
            30 -> {
                if (measuredFps in 26.0..35.0) {
                    FpsVerificationResult(
                        actualFps = measuredFps,
                        isVerified = true,
                        details = "Confirmed ~30fps recording ($formattedFps fps).",
                    )
                } else {
                    FpsVerificationResult(
                        actualFps = measuredFps,
                        isVerified = false,
                        details = "Requested 30fps, but output video was recorded at $formattedFps fps.",
                    )
                }
            }
            else -> {
                val matches = kotlin.math.abs(measuredFps - requestedFps) < 5.0
                FpsVerificationResult(
                    actualFps = measuredFps,
                    isVerified = matches,
                    details = "Output video recorded at $formattedFps fps (requested ${requestedFps}fps).",
                )
            }
        }
    }

    private fun extractActualFps(file: File): Double? = runCatching {
        if (!file.isFile || file.length() == 0L) return null

        var captureRate: Double? = null
        var frameCountFps: Double? = null

        val retriever = MediaMetadataRetriever()
        try {
            retriever.setDataSource(file.absolutePath)
            captureRate = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_CAPTURE_FRAMERATE)?.toDoubleOrNull()
                ?.takeIf { it.isFinite() && it > 0 }

            val durationMs = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLongOrNull() ?: 0L
            val frameCount = runCatching {
                retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_FRAME_COUNT)?.toIntOrNull()
            }.getOrNull()

            if (frameCount != null && frameCount > 0 && durationMs > 100) {
                frameCountFps = (frameCount.toDouble() * 1000.0) / durationMs
            }
        } finally {
            retriever.release()
        }

        if (captureRate != null) return captureRate
        if (frameCountFps != null) return frameCountFps

        // Fallback: check track format and sample timestamps via MediaExtractor
        var extractorFps: Double? = null
        val extractor = MediaExtractor()
        try {
            extractor.setDataSource(file.absolutePath)
            val videoTrackIndex = (0 until extractor.trackCount).firstOrNull {
                extractor.getTrackFormat(it).getString(MediaFormat.KEY_MIME)?.startsWith("video/") == true
            }

            if (videoTrackIndex != null) {
                val format = extractor.getTrackFormat(videoTrackIndex)
                if (format.containsKey(MediaFormat.KEY_FRAME_RATE)) {
                    val rate = format.getInteger(MediaFormat.KEY_FRAME_RATE).toDouble()
                    if (rate > 0) extractorFps = rate
                }

                if (extractorFps == null) {
                    extractor.selectTrack(videoTrackIndex)
                    var count = 0
                    var firstSampleTimeUs = -1L
                    var lastSampleTimeUs = -1L
                    while (count < 120 && extractor.sampleTime >= 0) {
                        val time = extractor.sampleTime
                        if (firstSampleTimeUs < 0) firstSampleTimeUs = time
                        lastSampleTimeUs = time
                        count++
                        if (!extractor.advance()) break
                    }
                    if (count > 5 && lastSampleTimeUs > firstSampleTimeUs) {
                        val durationSec = (lastSampleTimeUs - firstSampleTimeUs) / 1_000_000.0
                        if (durationSec > 0.1) {
                            extractorFps = (count - 1) / durationSec
                        }
                    }
                }
            }
        } finally {
            extractor.release()
        }

        extractorFps
    }.getOrNull()
}

