package dk.lasse.karatecliprecorder.mediapipehandadapter

import dk.lasse.karateanalyzer.core.HandFrame
import dk.lasse.karateanalyzer.core.HandLandmarkId
import dk.lasse.karateanalyzer.core.Handedness
import dk.lasse.karateanalyzer.core.LandmarkSample
import dk.lasse.karateanalyzer.core.LandmarkSource
import dk.lasse.karateanalyzer.core.Point3

/** Maps pure adapter DTOs into analyzer-core frames without importing MediaPipe classes. */
class MediaPipeHandFrameMapper {
    fun toDetectedHands(observations: List<MediaPipeHandObservation>): List<DetectedHand> = observations.mapNotNull(::toDetectedHand)

    fun toHandFrame(hand: DetectedHand): HandFrame = HandFrame(
        timestampMs = hand.frameTimestampMs,
        handedness = hand.handednessLabel.toHandedness(),
        landmarks = HandLandmarkId.entries.associateWith { id ->
            val point = hand.landmarks.getOrNull(id.ordinal)
            if (point == null) {
                LandmarkSample(null, 0f, LandmarkSource.MISSING)
            } else {
                LandmarkSample(
                    position = Point3(point.x, point.y, point.z),
                    confidence = pointConfidence(point, hand.handConfidence),
                    source = LandmarkSource.OBSERVED,
                )
            }
        },
    )

    private fun toDetectedHand(observation: MediaPipeHandObservation): DetectedHand? {
        if (observation.normalizedLandmarks.size != MEDIAPIPE_HAND_LANDMARK_COUNT) return null
        val handConfidence = observation.handednessScore.finiteUnitOrNull() ?: 0f
        return DetectedHand(
            frameTimestampMs = observation.timestampMs,
            handednessLabel = observation.handednessLabel,
            handConfidence = handConfidence,
            landmarks = observation.normalizedLandmarks,
            worldLandmarks = observation.worldLandmarks?.takeIf { it.size == MEDIAPIPE_HAND_LANDMARK_COUNT },
            openPalmScore = observation.openPalmScore.finiteUnitOrNull(),
            closedFistScore = observation.closedFistScore.finiteUnitOrNull(),
        )
    }

    private fun pointConfidence(point: MediaPipePoint3, handConfidence: Float): Float =
        point.presence.finiteUnitOrNull()
            ?: point.visibility.finiteUnitOrNull()
            ?: handConfidence.finiteUnitOrNull()
            ?: 0f

    private fun String?.toHandedness(): Handedness = when (this?.trim()?.lowercase()) {
        "left" -> Handedness.LEFT
        "right" -> Handedness.RIGHT
        else -> Handedness.UNKNOWN
    }
}

/** Selects among already-valid detected hands using real hand-level confidence. */
class HighestConfidenceActiveHandSelector {
    fun select(hands: List<DetectedHand>): DetectedHand? = hands
        .asSequence()
        .filter { it.isValid }
        .maxByOrNull { it.handConfidence }
}
