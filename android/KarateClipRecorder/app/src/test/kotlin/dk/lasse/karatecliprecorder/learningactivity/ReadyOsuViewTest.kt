package dk.lasse.karatecliprecorder.learningactivity

import android.view.ViewGroup
import androidx.test.core.app.ApplicationProvider
import kotlin.test.Test
import kotlin.test.assertNotNull
import kotlin.test.assertNotSame
import kotlin.test.assertEquals
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class ReadyOsuViewTest {
    @Test fun livePreviewSurvivesCameraStageTransitionsAndRetry() {
        val view = ReadyOsuView(
            context = ApplicationProvider.getApplicationContext(),
            onExit = {}, pathPosition = "2 / 3", onStart = {},
            onReplayModel = {}, onTryResponding = {}, onCancelPrompt = {},
            onStopListening = {}, onTryAgain = {}, onContinueWithoutVoice = {},
            onFinish = {}, onPracticeAgain = {}, onContinue = {},
        )
        listOf(
            ReadyOsuPhase.PREPARING_CAMERA, ReadyOsuPhase.PROMPTING,
            ReadyOsuPhase.LISTENING, ReadyOsuPhase.CHECKING,
            ReadyOsuPhase.CAPTURING, ReadyOsuPhase.ERROR,
            ReadyOsuPhase.PREPARING_CAMERA, ReadyOsuPhase.PROMPTING,
        ).forEach { phase ->
            val previousParent = view.cameraPreview.parent as? ViewGroup
            val presentation = ReadyOsuPresentation(ReadyOsuState(phase = phase))
            view.render(presentation)
            if (presentation.cameraActive) {
                assertNotNull(view.cameraPreview.parent)
                assertNotSame(previousParent, view.cameraPreview.parent)
                previousParent?.let { assertEquals(-1, it.indexOfChild(view.cameraPreview)) }
            }
        }
    }
}
