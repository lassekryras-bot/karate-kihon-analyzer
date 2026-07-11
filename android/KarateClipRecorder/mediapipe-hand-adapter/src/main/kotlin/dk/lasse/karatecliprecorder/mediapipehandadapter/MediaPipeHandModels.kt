package dk.lasse.karatecliprecorder.mediapipehandadapter

/** The Gesture Recognizer task bundle expected in app assets. */
const val GESTURE_RECOGNIZER_MODEL_ASSET_PATH = "mediapipe/gesture_recognizer.task"
const val MEDIAPIPE_HAND_LANDMARK_COUNT = 21

/** Adapter-facing point independent of MediaPipe runtime classes. */
data class MediaPipePoint3(
    val x: Float,
    val y: Float,
    val z: Float,
    val presence: Float? = null,
    val visibility: Float? = null,
)

data class MediaPipeHandObservation(
    val normalizedLandmarks: List<MediaPipePoint3>,
    val worldLandmarks: List<MediaPipePoint3>? = null,
    val handednessLabel: String? = null,
    val handednessScore: Float? = null,
    val openPalmScore: Float? = null,
    val closedFistScore: Float? = null,
    val timestampMs: Long,
)

data class DetectedHand(
    val frameTimestampMs: Long,
    val handednessLabel: String?,
    val handConfidence: Float,
    val landmarks: List<MediaPipePoint3>,
    val worldLandmarks: List<MediaPipePoint3>?,
    val openPalmScore: Float?,
    val closedFistScore: Float?,
) {
    val isValid: Boolean = landmarks.size == MEDIAPIPE_HAND_LANDMARK_COUNT
    val usableLandmarkCount: Int = landmarks.count { it.hasFiniteCoordinates() }
}

class MissingGestureRecognizerModelException(assetPath: String) :
    IllegalStateException("Expected MediaPipe Gesture Recognizer model asset at $assetPath")

/** Validates that the configured Gesture Recognizer .task bundle is packaged. */
class GestureRecognizerModelAssetValidator(
    private val assetExists: (String) -> Boolean,
    private val assetPath: String = GESTURE_RECOGNIZER_MODEL_ASSET_PATH,
) {
    fun validate(): String {
        if (!assetExists(assetPath)) throw MissingGestureRecognizerModelException(assetPath)
        return assetPath
    }
}

internal fun Float?.finiteUnitOrNull(): Float? = this?.takeIf { it.isFinite() }?.coerceIn(0f, 1f)

internal fun MediaPipePoint3.hasFiniteCoordinates(): Boolean = x.isFinite() && y.isFinite() && z.isFinite()
