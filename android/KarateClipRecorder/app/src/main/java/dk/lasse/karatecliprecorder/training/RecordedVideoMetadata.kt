package dk.lasse.karatecliprecorder.training

import android.media.MediaExtractor
import android.media.MediaFormat
import android.media.MediaMetadataRetriever
import java.io.File

data class RecordedVideoMetadata(
    val durationUs: Long,
    val width: Int,
    val height: Int,
    val frameRate: Double?,
    val verificationResult: FpsVerificationResult? = null,
    val rotation: Int = 0,
    val encodedWidth: Int = if (rotation == 90 || rotation == 270) height else width,
    val encodedHeight: Int = if (rotation == 90 || rotation == 270) width else height,
) {
    companion object {
        /** Verify a decodable, video-only finalized source before marking it AVAILABLE. */
        fun read(file: File, expectedFps: Int? = null): RecordedVideoMetadata {
            check(file.isFile && file.length() > 0) { "Finalized MP4 is missing" }
            val extractor = MediaExtractor()
            try {
                extractor.setDataSource(file.absolutePath)
                check((0 until extractor.trackCount).none {
                    extractor.getTrackFormat(it).getString(MediaFormat.KEY_MIME)?.startsWith("audio/") == true
                }) { "Expected a video-only recording" }
            } finally { extractor.release() }
            val reader = MediaMetadataRetriever()
            try {
                reader.setDataSource(file.absolutePath)
                val durationMs = reader.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLongOrNull() ?: 0
                val rawWidth = reader.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_WIDTH)?.toIntOrNull() ?: 0
                val rawHeight = reader.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_HEIGHT)?.toIntOrNull() ?: 0
                val rotation = reader.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_ROTATION)?.toIntOrNull() ?: 0
                check(durationMs > 0 && rawWidth > 0 && rawHeight > 0) { "Finalized MP4 has no readable video" }
                val frame = reader.getFrameAtTime(0)
                check(frame != null) { "Finalized MP4 cannot be decoded" }
                val canonicalWidth = if (rotation == 90 || rotation == 270) {
                    if (frame.width > frame.height) rawHeight else frame.width
                } else {
                    frame.width
                }
                val canonicalHeight = if (rotation == 90 || rotation == 270) {
                    if (frame.width > frame.height) rawWidth else frame.height
                } else {
                    frame.height
                }
                frame.recycle()
                val captureFps = reader.extractMetadata(MediaMetadataRetriever.METADATA_KEY_CAPTURE_FRAMERATE)?.toDoubleOrNull()
                    ?.takeIf { it.isFinite() && it > 0 }

                val verification = expectedFps?.let { RecordedVideoFpsVerifier.verify(file, it) }
                val finalFps = verification?.actualFps ?: captureFps

                return RecordedVideoMetadata(
                    durationUs = Math.multiplyExact(durationMs, 1000),
                    width = canonicalWidth,
                    height = canonicalHeight,
                    frameRate = finalFps,
                    verificationResult = verification,
                    rotation = rotation,
                    encodedWidth = rawWidth,
                    encodedHeight = rawHeight,
                )
            } finally { reader.release() }
        }
    }
}
