package dk.lasse.karatecliprecorder.training

import dk.lasse.karateanalyzer.geometry.*
import org.json.JSONObject

/** Append-only session events preserve historical calibration and result ownership without a schema fork. */
object ImageBodyScaleStore {
    const val EVENT = "image_body_scale_calibration"
    const val REVOKED = "image_body_scale_revoked"
    fun encode(c: ImageBodyScaleCalibration): String = JSONObject()
        .put("id", c.calibrationId).put("geometry", CanonicalGeometryCodec.encode(c.geometry))
        .put("frameUs", c.frameTimestampUs).put("frameIndex", c.frameIndex)
        .put("headX", c.headTop.x).put("headY", c.headTop.y).put("floorX", c.floorAtFeet.x).put("floorY", c.floorAtFeet.y)
        .put("fromUs", c.validFromUs).put("untilUs", c.validUntilUs)
        .put("unchangedConfirmed", c.cameraAndPositionConfirmedUnchanged).put("method", c.methodVersion)
        .put("pointSource", "user_confirmed_recording_frame").put("savedAtMs", System.currentTimeMillis()).toString()

    fun decode(data: String?): ImageBodyScaleCalibration? = runCatching {
        val j = JSONObject(requireNotNull(data))
        ImageBodyScaleCalibration(j.getString("id"), requireNotNull(CanonicalGeometryCodec.decode(j.getString("geometry"))),
            j.getLong("frameUs"), j.getInt("frameIndex"), SourceNormalizedPoint(j.getDouble("headX").toFloat(), j.getDouble("headY").toFloat()),
            SourceNormalizedPoint(j.getDouble("floorX").toFloat(), j.getDouble("floorY").toFloat()), j.getLong("fromUs"), j.getLong("untilUs"),
            j.getBoolean("unchangedConfirmed"), j.getString("method"))
    }.getOrNull()

    fun current(events: List<SessionEvent>): ImageBodyScaleCalibration? {
        val revoked = events.filter { it.type == REVOKED }.mapNotNull { it.data }.toSet()
        val latest = events.filter { it.type == EVENT }.mapNotNull { event ->
            val c = decode(event.data) ?: return@mapNotNull null
            c to runCatching { JSONObject(event.data!!).getLong("savedAtMs") }.getOrDefault(0)
        }.maxByOrNull { it.second }?.first
        return latest?.takeIf { it.calibrationId !in revoked }
    }
}
