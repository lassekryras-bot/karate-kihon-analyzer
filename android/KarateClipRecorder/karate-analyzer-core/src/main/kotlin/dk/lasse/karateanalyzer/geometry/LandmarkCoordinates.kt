package dk.lasse.karateanalyzer.geometry

/**
 * 2D position in a specific body frame, expressed in torso lengths.
 * - [lateralOffset]: signed perpendicular offset from the body center line (positive toward body across / image right).
 * - [bodyHeight]: signed position along body up relative to torso center (-0.5 at hip center, +0.5 at shoulder center).
 * - [snapshotId]: stable identity of the exact body frame snapshot used for projection.
 */
data class BodyPosition(
    val lateralOffset: Float,
    val bodyHeight: Float,
    val snapshotId: String,
) {
    init {
        require(lateralOffset.isFinite()) { "lateralOffset must be finite, was $lateralOffset" }
        require(bodyHeight.isFinite()) { "bodyHeight must be finite, was $bodyHeight" }
        require(snapshotId.isNotBlank()) { "snapshotId cannot be blank" }
    }
}

/**
 * Coordinate transformations between canonical image frames and body reference frames.
 */
object LandmarkCoordinates {

    /**
     * Maps an upward-positive metric point Q into body coordinates relative to [snapshot].
     * lateral = dot(Q - O, R) / L
     * height  = dot(Q - O, U) / L
     */
    fun bodyPositionOf(
        pointMetric: UpwardMetricPoint,
        snapshot: BodyFrameSnapshot,
    ): BodyPosition {
        val delta = pointMetric - snapshot.origin
        val l = snapshot.torsoLength
        val lateral = delta.dot(snapshot.acrossAxis) / l
        val height = delta.dot(snapshot.upAxis) / l
        return BodyPosition(
            lateralOffset = lateral,
            bodyHeight = height,
            snapshotId = snapshot.snapshotId,
        )
    }

    /**
     * Maps a source-normalized point into body coordinates relative to [snapshot].
     */
    fun bodyPositionOf(
        pointSource: SourceNormalizedPoint,
        snapshot: BodyFrameSnapshot,
    ): BodyPosition {
        val metric = UpwardMetricPoint.fromSource(pointSource, snapshot.frameGeometry)
        return bodyPositionOf(metric, snapshot)
    }

    /**
     * Exact inverse transformation: maps a [BodyPosition] back to upward metric space.
     * Enforces snapshot identity verification.
     */
    fun metricPositionOf(
        bodyPosition: BodyPosition,
        snapshot: BodyFrameSnapshot,
    ): UpwardMetricPoint {
        require(bodyPosition.snapshotId == snapshot.snapshotId) {
            "Snapshot identity mismatch: bodyPosition was evaluated against '${bodyPosition.snapshotId}', but snapshot is '${snapshot.snapshotId}'"
        }
        val l = snapshot.torsoLength
        val offsetVector = (snapshot.acrossAxis * bodyPosition.lateralOffset + snapshot.upAxis * bodyPosition.bodyHeight) * l
        return snapshot.origin + offsetVector
    }

    /**
     * Exact inverse transformation: maps a [BodyPosition] back to canonical source-normalized coordinates.
     */
    fun imagePositionOf(
        bodyPosition: BodyPosition,
        snapshot: BodyFrameSnapshot,
    ): SourceNormalizedPoint {
        val metric = metricPositionOf(bodyPosition, snapshot)
        return metric.toSourceNormalized(snapshot.frameGeometry)
    }
}

