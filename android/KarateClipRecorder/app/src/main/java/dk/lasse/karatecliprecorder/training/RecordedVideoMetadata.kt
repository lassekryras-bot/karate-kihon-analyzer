package dk.lasse.karatecliprecorder.training

import android.media.MediaExtractor
import android.media.MediaFormat
import android.media.MediaMetadataRetriever
import java.io.File

data class RecordedVideoMetadata(val durationUs: Long, val width: Int, val height: Int, val frameRate: Double?) {
    companion object {
        /** Verify a decodable, video-only finalized source before marking it AVAILABLE. */
        fun read(file: File): RecordedVideoMetadata {
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
                val width = reader.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_WIDTH)?.toIntOrNull() ?: 0
                val height = reader.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_HEIGHT)?.toIntOrNull() ?: 0
                check(durationMs > 0 && width > 0 && height > 0) { "Finalized MP4 has no readable video" }
                val frame = reader.getFrameAtTime(0)
                check(frame != null) { "Finalized MP4 cannot be decoded" }
                frame.recycle()
                val fps = reader.extractMetadata(MediaMetadataRetriever.METADATA_KEY_CAPTURE_FRAMERATE)?.toDoubleOrNull()
                    ?.takeIf { it.isFinite() && it > 0 }
                return RecordedVideoMetadata(Math.multiplyExact(durationMs, 1000), width, height, fps)
            } finally { reader.release() }
        }
    }
}
