package dk.lasse.karatecliprecorder.mediapipeposeadapter

import android.content.Context
import android.graphics.Bitmap
import android.media.MediaExtractor
import android.media.MediaFormat
import android.media.MediaMetadataRetriever
import com.google.mediapipe.framework.image.BitmapImageBuilder
import com.google.mediapipe.framework.image.MPImage
import com.google.mediapipe.tasks.core.BaseOptions
import com.google.mediapipe.tasks.core.Delegate
import com.google.mediapipe.tasks.vision.core.RunningMode
import com.google.mediapipe.tasks.vision.poselandmarker.PoseLandmarker
import dk.lasse.karateanalyzer.capture.retrospective.VideoPoseProcessor
import dk.lasse.karateanalyzer.core.PoseFrame
import java.io.File

/**
 * Sequential offline video pose decoder.
 *
 * Implements [VideoPoseProcessor] using MediaPipe in [RunningMode.VIDEO].
 * Decodes frames sequentially while preserving real media presentation timestamps (PTS),
 * feeding [PoseLandmarker.detectForVideo] strictly in PTS order without synthetic timestamp increments.
 */
class SequentialVideoPoseDecoder(
    private val context: Context,
    private val delegate: Delegate = Delegate.CPU,
    private val modelAssetPath: String = POSE_LANDMARKER_MODEL_ASSET_PATH,
) : VideoPoseProcessor {

    override fun processVideo(
        videoFile: File,
        onProgress: (progressFraction: Float, currentTimestampMs: Long) -> Unit,
    ): List<PoseFrame> {
        require(videoFile.exists()) { "Master video file does not exist: ${videoFile.absolutePath}" }

        // 1. Validate MediaPipe model asset
        PoseLandmarkerModelAssetValidator(
            assetExists = { path ->
                runCatching {
                    context.assets.open(path).use { }
                    true
                }.getOrDefault(false)
            },
            path = modelAssetPath,
        ).validate()

        // 2. Extract media metadata and exact presentation timestamps (PTS) using MediaExtractor
        val extractor = MediaExtractor()
        val ptsUsList = mutableListOf<Long>()
        var videoTrackIndex = -1
        var durationUs = 0L

        try {
            extractor.setDataSource(videoFile.absolutePath)
            for (i in 0 until extractor.trackCount) {
                val trackFormat = extractor.getTrackFormat(i)
                val mime = trackFormat.getString(MediaFormat.KEY_MIME) ?: ""
                if (mime.startsWith("video/")) {
                    videoTrackIndex = i
                    if (trackFormat.containsKey(MediaFormat.KEY_DURATION)) {
                        durationUs = trackFormat.getLong(MediaFormat.KEY_DURATION)
                    }
                    break
                }
            }

            if (videoTrackIndex >= 0) {
                extractor.selectTrack(videoTrackIndex)
                while (extractor.sampleTime >= 0) {
                    ptsUsList.add(extractor.sampleTime)
                    extractor.advance()
                }
            }
        } finally {
            extractor.release()
        }

        val totalFrames = ptsUsList.size

        // 3. Initialize PoseLandmarker in VIDEO mode
        val options = PoseLandmarker.PoseLandmarkerOptions.builder()
            .setBaseOptions(
                BaseOptions.builder()
                    .setModelAssetPath(modelAssetPath)
                    .setDelegate(delegate)
                    .build()
            )
            .setRunningMode(RunningMode.VIDEO)
            .setNumPoses(1)
            .setMinPoseDetectionConfidence(0.5f)
            .setMinPosePresenceConfidence(0.5f)
            .setMinTrackingConfidence(0.5f)
            .setOutputSegmentationMasks(false)
            .build()

        val landmarker = PoseLandmarker.createFromOptions(context, options)
        val retriever = MediaMetadataRetriever()
        retriever.setDataSource(videoFile.absolutePath)

        val frames = ArrayList<PoseFrame>(totalFrames)
        var lastProcessedPtsMs = -1L

        try {
            for ((index, ptsUs) in ptsUsList.withIndex()) {
                val ptsMs = ptsUs / 1000L

                // Strictly enforce monotonically increasing PTS as required by detectForVideo
                // Skip duplicated millisecond frames to preserve real media PTS without synthetic increments
                if (ptsMs <= lastProcessedPtsMs) {
                    continue
                }

                // Decode frame at exact presentation timestamp
                val bitmap: Bitmap? = retriever.getFrameAtTime(
                    ptsUs,
                    MediaMetadataRetriever.OPTION_CLOSEST,
                )

                if (bitmap != null) {
                    var mpImage: MPImage? = null
                    try {
                        mpImage = BitmapImageBuilder(bitmap).build()
                        val result = landmarker.detectForVideo(mpImage, ptsMs)
                        val poseFrame = MediaPipePoseResultMapper.map(result)
                        frames.add(poseFrame)
                        lastProcessedPtsMs = ptsMs
                    } finally {
                        mpImage?.close()
                        if (!bitmap.isRecycled) {
                            bitmap.recycle()
                        }
                    }
                }

                val fraction = if (totalFrames > 0) (index + 1).toFloat() / totalFrames else 1f
                onProgress(fraction, ptsMs)
            }
        } finally {
            retriever.release()
            landmarker.close()
        }

        return frames
    }
}

