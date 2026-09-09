package dk.lasse.karatecliprecorder.profile

import org.json.JSONObject

/** Immutable recording-owner measurements; never resolve through the current viewer. */
data class BodyMeasurementSnapshot(
    val profileId: String,
    val capturedAtMs: Long,
    val forearmLengthCm: Float?,
    val lowerLegLengthCm: Float?,
    val heightCm: Float?,
) {
    fun toJson(): JSONObject = JSONObject()
        .put("contract", "body_measurements_v1")
        .put("source", "recording_owner_profile_snapshot")
        .put("profile_id", profileId)
        .put("captured_at_ms", capturedAtMs)
        .put("forearm_length_m", forearmLengthCm?.div(100.0) ?: JSONObject.NULL)
        .put("lower_leg_length_m", lowerLegLengthCm?.div(100.0) ?: JSONObject.NULL)
        .put("height_m", heightCm?.div(100.0) ?: JSONObject.NULL)

    companion object {
        fun from(profile: Profile, capturedAtMs: Long = System.currentTimeMillis()) = BodyMeasurementSnapshot(
            profile.id, capturedAtMs, profile.forearmLengthCm, profile.lowerLegLengthCm, profile.heightCm,
        )
    }
}
