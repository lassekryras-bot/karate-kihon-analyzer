package dk.lasse.karatecliprecorder.profile

import kotlin.test.assertEquals
import kotlin.test.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class BodyMeasurementSnapshotTest {
    @Test fun snapshotRetainsRecordingOwnerValuesAfterProfileChanges() {
        val original = Profile.default().copy(forearmLengthCm = 30f, lowerLegLengthCm = 50f, heightCm = 186f)
        val snapshot = BodyMeasurementSnapshot.from(original, 123L)
        val edited = original.copy(forearmLengthCm = 35f)
        assertEquals(30f, snapshot.forearmLengthCm)
        assertEquals(35f, edited.forearmLengthCm)
        val json = snapshot.toJson()
        assertEquals(original.id, json.getString("profile_id"))
        assertEquals(123L, json.getLong("captured_at_ms"))
        assertEquals(0.3, json.getDouble("forearm_length_m"), 0.000001)
        assertEquals(0.5, json.getDouble("lower_leg_length_m"), 0.000001)
    }

    @Test fun absentMeasurementsStayExplicitlyMissing() {
        val json = BodyMeasurementSnapshot.from(Profile.default()).toJson()
        assertTrue(json.isNull("forearm_length_m"))
        assertTrue(json.isNull("lower_leg_length_m"))
    }
}
