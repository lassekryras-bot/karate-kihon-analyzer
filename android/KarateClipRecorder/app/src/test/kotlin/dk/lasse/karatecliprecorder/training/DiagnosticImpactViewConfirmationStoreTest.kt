package dk.lasse.karatecliprecorder.training

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue
import org.json.JSONObject
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE)
class DiagnosticImpactViewConfirmationStoreTest {
    private val context = requireNotNull(MotionActivityPlans.context(MotionActivityPlans.ALTERNATING_PUNCH))
    private val planEvent = SessionEvent(
        sessionId = "session",
        type = MotionActivityPlans.EVENT_TYPE,
        timestampUs = 0,
        data = context.planId,
        timingSource = MotionActivityPlans.CAPTURE_ORIGIN,
    )
    private val planEvidence = MotionActivityPlans.Evidence(context, planEvent.sessionEventId, planEvent.timingSource)

    @Test fun confirmationIsScopedToExactRecordingPlanAndDebugBuild() {
        val confirmationEvent = DiagnosticImpactViewConfirmationStore.event("session", "recording", context)
        val confirmation = DiagnosticImpactViewConfirmationStore.current(
            listOf(planEvent, confirmationEvent),
            "recording",
            planEvidence,
            debugBuild = true,
        )

        assertEquals("recording", confirmation?.recordingId)
        assertEquals(context.planId, confirmation?.activityPlanId)
        assertEquals(context.planVersion, confirmation?.activityPlanVersion)
        assertEquals(DiagnosticImpactViewConfirmationStore.PROFILE_ID, confirmation?.approval()?.profileId)
        assertTrue(confirmation?.approval()?.source?.contains(confirmationEvent.sessionEventId) == true)
        assertNull(DiagnosticImpactViewConfirmationStore.current(
            listOf(planEvent, confirmationEvent), "other-recording", planEvidence, debugBuild = true))
        assertNull(DiagnosticImpactViewConfirmationStore.current(
            listOf(planEvent, confirmationEvent), "recording", planEvidence, debugBuild = false))
    }

    @Test fun duplicateOrMalformedConfirmationIsRejected() {
        val confirmation = DiagnosticImpactViewConfirmationStore.event("session", "recording", context)
        assertNull(DiagnosticImpactViewConfirmationStore.current(
            listOf(planEvent, confirmation, confirmation.copy(sessionEventId = trainingId())),
            "recording",
            planEvidence,
            debugBuild = true,
        ))
        assertNull(DiagnosticImpactViewConfirmationStore.current(
            listOf(planEvent, confirmation.copy(data = "{}")),
            "recording",
            planEvidence,
            debugBuild = true,
        ))
    }

    @Test fun mismatchedPlanAndVersionAreRejected() {
        val confirmation = DiagnosticImpactViewConfirmationStore.event("session", "recording", context)
        val otherPlan = requireNotNull(MotionActivityPlans.context(MotionActivityPlans.FRONT_KICK))
        assertNull(DiagnosticImpactViewConfirmationStore.current(
            listOf(planEvent, confirmation),
            "recording",
            MotionActivityPlans.Evidence(otherPlan, "other", MotionActivityPlans.CAPTURE_ORIGIN),
            debugBuild = true,
        ))
        val wrongVersion = JSONObject(requireNotNull(confirmation.data))
            .put("activityPlanVersion", "wrong")
        assertNull(DiagnosticImpactViewConfirmationStore.current(
            listOf(planEvent, confirmation.copy(data = wrongVersion.toString())),
            "recording",
            planEvidence,
            debugBuild = true,
        ))
    }
}
