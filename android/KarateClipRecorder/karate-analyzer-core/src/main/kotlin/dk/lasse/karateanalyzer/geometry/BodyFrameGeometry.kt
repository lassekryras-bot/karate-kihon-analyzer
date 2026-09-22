package dk.lasse.karateanalyzer.geometry

import kotlin.math.sqrt

/**
 * Reference frame policies supported by the Landmark Geometry Core.
 */
enum class BodyFrameReferencePolicy {
    /** Fixed canonical recording image frame. */
    IMAGE,

    /** Instantaneous body frame evaluated at each requested sample. */
    CURRENT_BODY,

    /** Explicit immutable body frame snapshot frozen at interval start. */
    START_BODY,

    /** Explicit immutable calibration snapshot accepted by neutral stillness policy. */
    NEUTRAL_BODY,
}

/**
 * 2D vector / point in the upward-positive aspect-correct metric space:
 * x = xSource * (W / H)
 * y = -ySource
 * One unit corresponds to canonical image height; y increases upward.
 */
data class UpwardMetricPoint(
    val x: Float,
    val y: Float,
) {
    init {
        require(x.isFinite() && y.isFinite()) { "Coordinates must be finite: x=$x, y=$y" }
    }

    operator fun plus(other: UpwardMetricPoint) = UpwardMetricPoint(x + other.x, y + other.y)
    operator fun minus(other: UpwardMetricPoint) = UpwardMetricPoint(x - other.x, y - other.y)
    operator fun times(scale: Float) = UpwardMetricPoint(x * scale, y * scale)

    fun dot(other: UpwardMetricPoint): Float = x * other.x + y * other.y
    fun length(): Float = sqrt(x * x + y * y)

    fun normalized(): UpwardMetricPoint? {
        val len = length()
        return if (len > 1e-6f && len.isFinite()) UpwardMetricPoint(x / len, y / len) else null
    }

    fun toSourceNormalized(frameGeometry: FrameGeometry): SourceNormalizedPoint {
        return SourceNormalizedPoint(
            x = x / frameGeometry.aspectRatio,
            y = -y,
        )
    }

    fun toAspectCorrectPoint(): AspectCorrectPoint {
        return AspectCorrectPoint(x, -y)
    }

    companion object {
        fun fromSource(point: SourceNormalizedPoint, frameGeometry: FrameGeometry): UpwardMetricPoint {
            return UpwardMetricPoint(
                x = point.x * frameGeometry.aspectRatio,
                y = -point.y,
            )
        }

        fun fromAspectCorrect(point: AspectCorrectPoint): UpwardMetricPoint {
            return UpwardMetricPoint(point.x, -point.y)
        }
    }
}

/**
 * Immutable snapshot freezing body frame origin, orthonormal axes, and scale.
 */
data class BodyFrameSnapshot(
    val snapshotId: String,
    val recordingId: String? = null,
    val trackId: String? = null,
    val timestampUs: Long,
    val origin: UpwardMetricPoint,
    val upAxis: UpwardMetricPoint,
    val acrossAxis: UpwardMetricPoint,
    val torsoLength: Float,
    val isFrozenScale: Boolean = true,
    val frameGeometry: FrameGeometry,
    val shoulderCenterSource: SourceNormalizedPoint? = null,
    val hipCenterSource: SourceNormalizedPoint? = null,
    val torsoCenterSource: SourceNormalizedPoint? = null,
) {
    init {
        require(snapshotId.isNotBlank()) { "snapshotId cannot be blank" }
        require(torsoLength.isFinite() && torsoLength > 0f) { "torsoLength must be positive, was $torsoLength" }
    }
}

/**
 * Result of constructing a body frame from torso anchors.
 */
data class BodyFrameConstructionResult(
    val snapshot: BodyFrameSnapshot?,
    val state: MeasurementResultState,
    val failureReason: String? = null,
)

/**
 * Authoritative construction and validation of 2D body frames from torso evidence.
 */
object BodyFrameGeometry {

    const val DEFAULT_MINIMUM_TORSO_LENGTH = 0.05f

    /**
     * Constructs an orthonormal body frame snapshot from shoulder and hip center anchors.
     * Enforces the upward-positive aspect-correct metric convention:
     * - S = shoulder midpoint
     * - Hc = hip midpoint
     * - O = (S + Hc) / 2
     * - L = length(S - Hc)
     * - U = (S - Hc) / L (body up)
     * - R = (Uy, -Ux) (body across, points toward image right for upright posture)
     */
    fun constructFromAnchors(
        shoulderCenter: SourceNormalizedPoint,
        hipCenter: SourceNormalizedPoint,
        frameGeometry: FrameGeometry,
        timestampUs: Long,
        snapshotId: String = "snap_$timestampUs",
        minimumTorsoLength: Float = DEFAULT_MINIMUM_TORSO_LENGTH,
        recordingId: String? = null,
        trackId: String? = null,
        isFrozenScale: Boolean = true,
    ): BodyFrameConstructionResult {
        val s = UpwardMetricPoint.fromSource(shoulderCenter, frameGeometry)
        val hc = UpwardMetricPoint.fromSource(hipCenter, frameGeometry)

        val torsoVec = s - hc // Direction: Hip -> Shoulder (Upward)
        val torsoLength = torsoVec.length()

        if (torsoLength < minimumTorsoLength) {
            return BodyFrameConstructionResult(
                snapshot = null,
                state = MeasurementResultState.UNAVAILABLE,
                failureReason = "degenerate_torso_axis: length $torsoLength < minimum $minimumTorsoLength",
            )
        }

        val upAxis = torsoVec.normalized() ?: return BodyFrameConstructionResult(
            snapshot = null,
            state = MeasurementResultState.UNAVAILABLE,
            failureReason = "degenerate_torso_axis: cannot normalize torso vector",
        )

        // In upward-positive convention: R = (Uy, -Ux)
        val acrossAxis = UpwardMetricPoint(upAxis.y, -upAxis.x)

        val origin = (s + hc) * 0.5f
        val torsoCenterSource = SourceNormalizedPoint(
            (shoulderCenter.x + hipCenter.x) * 0.5f,
            (shoulderCenter.y + hipCenter.y) * 0.5f,
        )

        val snapshot = BodyFrameSnapshot(
            snapshotId = snapshotId,
            recordingId = recordingId,
            trackId = trackId,
            timestampUs = timestampUs,
            origin = origin,
            upAxis = upAxis,
            acrossAxis = acrossAxis,
            torsoLength = torsoLength,
            isFrozenScale = isFrozenScale,
            frameGeometry = frameGeometry,
            shoulderCenterSource = shoulderCenter,
            hipCenterSource = hipCenter,
            torsoCenterSource = torsoCenterSource,
        )

        return BodyFrameConstructionResult(
            snapshot = snapshot,
            state = MeasurementResultState.AVAILABLE,
        )
    }
}

