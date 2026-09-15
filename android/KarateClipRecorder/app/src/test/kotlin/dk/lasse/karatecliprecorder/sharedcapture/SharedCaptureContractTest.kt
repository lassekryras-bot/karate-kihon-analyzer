package dk.lasse.karatecliprecorder.sharedcapture

import org.junit.Test
import kotlin.test.*

class SharedCaptureContractTest {
    @Test fun semanticPromptsMapThroughOneCatalog() {
        assertEquals(dk.lasse.karatecliprecorder.orders.TrainingOrder.YOI,
            CapturePromptCatalog.trainingOrder(CapturePrompt.START))
        assertEquals(dk.lasse.karatecliprecorder.orders.TrainingOrder.SESSION_COMPLETE,
            CapturePromptCatalog.trainingOrder(CapturePrompt.RECORDING_COMPLETE))
        assertNull(CapturePromptCatalog.trainingOrder(CapturePrompt.FINISHING))
    }
    @Test fun captureTypeTriggerAndUiPolicyAreIndependent() {
        val photo = SharedCaptureRequests.readyOsuSelfie()
        assertEquals(CaptureType.PHOTO, photo.captureType)
        assertEquals(CaptureTrigger.VOICE, photo.trigger)
        assertEquals(CaptureCamera.FRONT, photo.camera)
        assertNull(photo.startBlock())
        assertEquals(CapabilityAccess.LOCKED, SharedCapturePolicies.structuredSelfie.cameraAndLens)
        assertEquals(CapabilityAccess.HIDDEN, SharedCapturePolicies.structuredSelfie.defaultTrigger)
        assertEquals(CapabilityAccess.EDITABLE, SharedCapturePolicies.recordAndAnalyze.cameraAndLens)
    }

    @Test fun reservedCueModesCanBeRepresentedButCannotStart() {
        fun request(mode: CaptureCueMode) = SharedCaptureRequest(CaptureType.VIDEO,
            CaptureTrigger.STANDARD_TOUCH, "test", plannedRepetitions = 3, cueMode = mode,
            camera = CaptureCamera.REAR)
        assertEquals(CaptureStartBlock.UNSUPPORTED_SELF_CUED, request(CaptureCueMode.SELF_CUED).startBlock())
        assertEquals(CaptureStartBlock.UNSUPPORTED_FREE_AUTO_COUNT, request(CaptureCueMode.FREE_AUTO_COUNT).startBlock())
    }

    @Test fun appCuedRequestRequiresCountAndCadenceWithoutInventingFreeCadence() {
        val missing = SharedCaptureRequest(CaptureType.VIDEO, CaptureTrigger.STANDARD_TOUCH,
            "test", cueMode = CaptureCueMode.APP_CUED, camera = CaptureCamera.REAR)
        assertEquals(CaptureStartBlock.MISSING_PLANNED_COUNT, missing.startBlock())
        assertEquals(CaptureStartBlock.MISSING_CADENCE, missing.copy(plannedRepetitions = 3).startBlock())
        val free = SharedCaptureRequest(CaptureType.VIDEO, CaptureTrigger.CALLER_CONTROLLED,
            "test", camera = CaptureCamera.REAR, countdown = CaptureCountdown.NONE,
            autoStop = CaptureAutoStop.MANUAL)
        assertNull(free.cadenceMs)
        assertNull(free.startBlock())
    }

    @Test fun promptSelectionIsSemanticAndResultCentersPersistedIdentity() {
        val request = SharedCaptureRequests.recordAndAnalyze("Punches", "Punches", 10, 1000, false)
        assertEquals(CapturePrompt.FINISHED, request.completionPrompt)
        assertFalse(request.spokenMovementCues)
        assertTrue(request.operationalVoicePrompts)
        val result = PersistedCaptureResult("capture", "session", CaptureType.VIDEO,
            CaptureOutcome.FORCE_STOPPED, true, 1_000_000)
        assertEquals("capture", result.captureId)
        assertEquals(CaptureOutcome.FORCE_STOPPED, result.outcome)
    }
}
