package dk.lasse.karateanalyzer.geometry

import dk.lasse.karateanalyzer.impact.BodyScaleEvidence

/** User-confirmed upright head-top to floor height in this recording, not physical stature.
 * Validity is explicitly scoped to an unchanged camera/zoom/subject-depth interval.
 * No automatic camera or depth-change detector is implied by this contract.
 */
data class ImageBodyScaleCalibration(
    val calibrationId: String,
    val geometry: CanonicalGeometryDescriptor,
    val frameTimestampUs: Long,
    val frameIndex: Int,
    val headTop: SourceNormalizedPoint,
    val floorAtFeet: SourceNormalizedPoint,
    val validFromUs: Long,
    val validUntilUs: Long,
    val cameraAndPositionConfirmedUnchanged: Boolean,
    val methodVersion: String = METHOD_VERSION,
) {
    companion object { const val METHOD_VERSION = "manual-upright-image-height-v1" }
}

object ImageBodyScaleProvider {
    fun evidence(calibration: ImageBodyScaleCalibration?, geometry: CanonicalGeometryDescriptor,
                 fromUs: Long, untilUs: Long): BodyScaleEvidence? {
        val c = calibration ?: return null
        if (!geometry.isAvailable || geometry.landmarkTrackId.isNullOrBlank() || c.geometry != geometry ||
            c.methodVersion != ImageBodyScaleCalibration.METHOD_VERSION || c.calibrationId.isBlank() ||
            !c.cameraAndPositionConfirmedUnchanged || c.frameTimestampUs < 0 || c.frameIndex < 0 ||
            c.validFromUs < 0 || c.validUntilUs <= c.validFromUs || fromUs < c.validFromUs || untilUs > c.validUntilUs ||
            untilUs <= fromUs || c.frameTimestampUs !in c.validFromUs..c.validUntilUs) return null
        if (listOf(c.headTop, c.floorAtFeet).any { it.x !in 0f..1f || it.y !in 0f..1f } ||
            c.floorAtFeet.y <= c.headTop.y) return null
        val frame = geometry.toFrameGeometry()
        val top = FrameGeometryMath.sourceToAspectCorrect(c.headTop, frame)
        val bottom = FrameGeometryMath.sourceToAspectCorrect(c.floorAtFeet, frame)
        val height = FrameGeometryMath.distance(top, AspectCorrectPoint(top.x, bottom.y)).toDouble()
        return BodyScaleEvidence(height, c.calibrationId, c.methodVersion).takeIf { it.isUsable }
    }
}
