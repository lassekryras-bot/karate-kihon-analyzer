package dk.lasse.karatecliprecorder.mediapipehandadapter

import dk.lasse.karateanalyzer.core.HandFrame
import dk.lasse.karateanalyzer.core.HandLandmarkId
import dk.lasse.karateanalyzer.core.Handedness
import dk.lasse.karateanalyzer.core.LandmarkSample
import dk.lasse.karateanalyzer.core.LandmarkSource
import dk.lasse.karateanalyzer.core.Point3

/** Number of landmarks emitted by MediaPipe hand and gesture tasks for one hand. */
const val MEDIAPIPE_HAND_LANDMARK_COUNT = 21

/**
 * Expected Android asset path for a MediaPipe Gesture Recognizer or Hand Landmarker .task model.
 *
 * Codex intentionally does not commit the binary model. Developers must place the downloaded
 * model at `src/main/assets/mediapipe/gesture_recognizer.task` (packaged as this asset path), or
 * pass an alternate asset path when wiring MediaPipe. The adapter must fail clearly if the asset
 * is absent and must not download models at runtime.
 */
const val EXPECTED_MEDIAPIPE_HAND_MODEL_ASSET_PATH = "mediapipe/gesture_recognizer.task"

/** Adapter-facing point model to keep mapper unit tests independent of a camera or .task model. */
data class MediaPipePoint(
    val x: Float,
    val y: Float,
    val z: Float,
    val confidence: Float? = null,
)

data class MediaPipeHandedness(
    val label: String?,
    val confidence: Float? = null,
)

/** Canned gesture scores exposed as observations only; karate-analyzer-core remains the judge. */
data class MediaPipeGestureScores(
    val openPalm: Float?,
    val closedFist: Float?,
)

data class DetectedHand(
    val frame: HandFrame,
    val confidence: Float,
    val worldLandmarks: Map<HandLandmarkId, LandmarkSample> = emptyMap(),
    val gestureScores: MediaPipeGestureScores? = null,
)

interface ActiveHandSelector {
    fun select(candidates: List<DetectedHand>): DetectedHand?
}

object HighestConfidenceActiveHandSelector : ActiveHandSelector {
    override fun select(candidates: List<DetectedHand>): DetectedHand? = candidates.maxByOrNull { it.confidence }
}

class MediaPipeModelAssetMissingException(assetPath: String) :
    IllegalStateException("MediaPipe model asset '$assetPath' is missing. Place the .task model in src/main/assets/$assetPath; runtime downloads and placeholder models are not supported.")

/**
 * Validates model presence using an injected asset opener so this module does not force Android
 * framework types into JVM tests.
 */
class MediaPipeModelAssetValidator(
    private val openAsset: (String) -> java.io.InputStream,
) {
    fun requirePresent(assetPath: String = EXPECTED_MEDIAPIPE_HAND_MODEL_ASSET_PATH) {
        try {
            openAsset(assetPath).use { }
        } catch (missing: java.io.IOException) {
            throw MediaPipeModelAssetMissingException(assetPath)
        }
    }
}

/**
 * Maps MediaPipe hand observations into analyzer-neutral frames.
 *
 * MediaPipe handedness labels are produced for the input image stream; a mirrored front-camera
 * preview can make left and right appear visually reversed. This mapper preserves MediaPipe's
 * reported coordinates and labels exactly: it does not convert normalized coordinates to pixels,
 * rotate, align to tutorial guides, or mirror geometry silently.
 */
class MediaPipeHandFrameMapper {
    fun map(
        timestampMs: Long,
        normalizedLandmarks: List<MediaPipePoint>,
        worldLandmarks: List<MediaPipePoint>?,
        handedness: MediaPipeHandedness?,
    ): HandFrame = mapDetectedHand(timestampMs, normalizedLandmarks, worldLandmarks, handedness).frame

    fun mapDetectedHands(candidates: List<MediaPipeHandObservation>): List<DetectedHand> =
        candidates.map { mapDetectedHand(it.timestampMs, it.normalizedLandmarks, it.worldLandmarks, it.handedness, it.gestureScores) }

    fun mapDetectedHand(
        timestampMs: Long,
        normalizedLandmarks: List<MediaPipePoint>,
        worldLandmarks: List<MediaPipePoint>?,
        handedness: MediaPipeHandedness?,
        gestureScores: MediaPipeGestureScores? = null,
    ): DetectedHand {
        val imageSamples = mapLandmarks(normalizedLandmarks)
        val worldSamples = worldLandmarks?.let(::mapLandmarks).orEmpty()
        val confidence = handedness?.confidence?.clamp01()
            ?: imageSamples.values.map { it.confidence }.maxOrNull()
            ?: 0f
        return DetectedHand(
            frame = HandFrame(timestampMs, handedness.toCoreHandedness(), imageSamples),
            confidence = confidence,
            worldLandmarks = worldSamples,
            gestureScores = gestureScores,
        )
    }

    private fun mapLandmarks(points: List<MediaPipePoint>): Map<HandLandmarkId, LandmarkSample> {
        if (points.size != MEDIAPIPE_HAND_LANDMARK_COUNT) return missingSamples()
        return mediaPipeLandmarkIds.mapIndexed { index, id -> id to points[index].toSample() }.toMap()
    }

    private fun MediaPipePoint.toSample(): LandmarkSample {
        val point = Point3(x, y, z)
        return if (!point.isFinite()) {
            LandmarkSample(null, 0f, LandmarkSource.MISSING)
        } else {
            LandmarkSample(point, (confidence ?: 1f).clamp01(), LandmarkSource.OBSERVED)
        }
    }

    private fun MediaPipeHandedness?.toCoreHandedness(): Handedness = when (this?.label?.trim()?.lowercase()) {
        "left" -> Handedness.LEFT
        "right" -> Handedness.RIGHT
        else -> Handedness.UNKNOWN
    }
}

data class MediaPipeHandObservation(
    val timestampMs: Long,
    val normalizedLandmarks: List<MediaPipePoint>,
    val worldLandmarks: List<MediaPipePoint>? = null,
    val handedness: MediaPipeHandedness? = null,
    val gestureScores: MediaPipeGestureScores? = null,
)

val mediaPipeLandmarkIds: List<HandLandmarkId> = listOf(
    HandLandmarkId.WRIST,
    HandLandmarkId.THUMB_CMC,
    HandLandmarkId.THUMB_MCP,
    HandLandmarkId.THUMB_IP,
    HandLandmarkId.THUMB_TIP,
    HandLandmarkId.INDEX_MCP,
    HandLandmarkId.INDEX_PIP,
    HandLandmarkId.INDEX_DIP,
    HandLandmarkId.INDEX_TIP,
    HandLandmarkId.MIDDLE_MCP,
    HandLandmarkId.MIDDLE_PIP,
    HandLandmarkId.MIDDLE_DIP,
    HandLandmarkId.MIDDLE_TIP,
    HandLandmarkId.RING_MCP,
    HandLandmarkId.RING_PIP,
    HandLandmarkId.RING_DIP,
    HandLandmarkId.RING_TIP,
    HandLandmarkId.LITTLE_MCP,
    HandLandmarkId.LITTLE_PIP,
    HandLandmarkId.LITTLE_DIP,
    HandLandmarkId.LITTLE_TIP,
)

private fun missingSamples(): Map<HandLandmarkId, LandmarkSample> =
    HandLandmarkId.entries.associateWith { LandmarkSample(null, 0f, LandmarkSource.MISSING) }

private fun Point3.isFinite(): Boolean = x.isFinite() && y.isFinite() && z.isFinite()
private fun Float.clamp01(): Float = if (isFinite()) coerceIn(0f, 1f) else 0f
