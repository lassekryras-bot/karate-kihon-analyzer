package dk.lasse.karatecliprecorder

import android.os.Handler
import android.os.Looper
import dk.lasse.karatecliprecorder.captureprofile.SelectedCaptureProfile
import java.io.File

class GuidedJodanSessionController(
    private val recordingAdapter: CameraXRecordingAdapter,
    private val onStateChanged: (GuidedSessionState) -> Unit,
    private val onPromptChanged: (String) -> Unit,
    private val onStrikeChanged: (GuidedStrikePlan?) -> Unit,
    private val onSavedClipCountChanged: (Int) -> Unit,
    private val onComplete: (GuidedSessionResult) -> Unit,
    private val onError: (String) -> Unit,
    var captureProfile: SelectedCaptureProfile? = null,
) {
    private val handler = Handler(Looper.getMainLooper())
    private val plan = createPlan()
    private val clipResults = mutableListOf<GuidedClipResult>()
    private var activePlan: GuidedStrikePlan? = null
    private var currentPlanIndex = 0
    private var running = false
    private var cancelled = false

    fun start() {
        if (running) return
        running = true
        cancelled = false
        currentPlanIndex = 0
        activePlan = null
        clipResults.clear()
        onSavedClipCountChanged(0)
        onStrikeChanged(null)
        onStateChanged(GuidedSessionState.READY)
        onPromptChanged("Setup / ready")
        handler.postDelayed({ showYoi() }, READY_DELAY_MS)
    }

    fun cancel() {
        if (!running) return
        cancelled = true
        running = false
        handler.removeCallbacksAndMessages(null)
        recordingAdapter.stopRecording()
        activePlan = null
        onStateChanged(GuidedSessionState.CANCELLED)
        onPromptChanged("Session cancelled")
    }

    fun handleRecordingSaved(result: RecordingResult) {
        val finishedPlan = activePlan ?: return
        clipResults.add(GuidedClipResult(finishedPlan, result))
        activePlan = null
        onSavedClipCountChanged(clipResults.count { it.saved })

        if (!running || cancelled) return
        if (currentPlanIndex >= plan.size) {
            finishSession(completed = true)
        } else {
            handler.postDelayed({ promptNextStrike() }, BETWEEN_STRIKE_PAUSE_MS)
        }
    }

    fun handleRecordingError(message: String) {
        if (!running) return
        running = false
        activePlan?.let { clipResults.add(GuidedClipResult(it, null)) }
        activePlan = null
        handler.removeCallbacksAndMessages(null)
        onStateChanged(GuidedSessionState.FAILED)
        onPromptChanged("Session failed")
        onError(message)
        finishSession(completed = false)
    }

    private fun showYoi() {
        if (!running || cancelled) return
        onStateChanged(GuidedSessionState.YOI)
        onPromptChanged("Yoi")
        handler.postDelayed({ promptNextStrike() }, YOI_DELAY_MS)
    }

    private fun promptNextStrike() {
        if (!running || cancelled) return
        val nextPlan = plan.getOrNull(currentPlanIndex) ?: run {
            finishSession(completed = true)
            return
        }
        activePlan = nextPlan
        currentPlanIndex += 1
        onStateChanged(GuidedSessionState.PROMPTING_STRIKE)
        onStrikeChanged(nextPlan)
        onPromptChanged(nextPlan.japaneseCount)
        handler.postDelayed({ startCurrentRecording() }, PROMPT_DELAY_MS)
    }

    private fun startCurrentRecording() {
        if (!running || cancelled) return
        val nextPlan = activePlan ?: return
        onStateChanged(GuidedSessionState.RECORDING)
        onPromptChanged("Recording ${nextPlan.japaneseCount}")
        recordingAdapter.startRecording(nextPlan.fileName)
        handler.postDelayed({
            onStateChanged(GuidedSessionState.SAVING)
            recordingAdapter.stopRecording()
        }, FIXED_CLIP_DURATION_MS)
    }

    private fun finishSession(completed: Boolean) {
        running = false
        handler.removeCallbacksAndMessages(null)
        onStateChanged(GuidedSessionState.SAVING)
        val metadataFile = recordingAdapter.createGuidedSessionFile(METADATA_FILE_NAME)
        metadataFile.writeText(buildMetadataJson(completed), Charsets.UTF_8)
        onStateChanged(if (completed) GuidedSessionState.COMPLETE else GuidedSessionState.FAILED)
        onPromptChanged(if (completed) "Session complete" else "Session incomplete")
        onComplete(
            GuidedSessionResult(
                expectedClipCount = plan.size,
                savedClipCount = clipResults.count { it.saved },
                metadataPath = metadataFile.absolutePath,
                completed = completed,
            ),
        )
    }

    private fun buildMetadataJson(completed: Boolean): String {
        val byIndex = clipResults.associateBy { it.plan.index }
        val clipsJson = plan.joinToString(separator = ",\n") { strike ->
            val clip = byIndex[strike.index]
            val path = clip?.recordingResult?.absolutePath.orEmpty()
            """
    {
      "strike_index": ${strike.index},
      "japanese_count": "${strike.japaneseCount}",
      "expected_side": "${strike.expectedSide.metadataValue}",
      "file_name": "${strike.fileName}",
      "saved": ${clip?.saved == true},
      "path": "${path.escapeJson()}"
    }""".trimEnd()
        }
        return """
{
  "schema_version": "android-guided-jodan-session-v1",
  "session_type": "jodan_fixed_duration_clip_session",
  "expected_strike_count": ${plan.size},
  "fixed_clip_duration_ms": $FIXED_CLIP_DURATION_MS,
  "camera_profile": ${buildCameraProfileJson()},
  "clips": [
$clipsJson
  ],
  "completed": $completed,
  "successful_clip_count": ${clipResults.count { it.saved }}
}
""".trimStart()
    }

    private fun buildCameraProfileJson(): String {
        val profile = captureProfile ?: return "null"
        val selectedFpsRange = profile.selectedFpsRange?.let {
            """{
      "min_fps": ${it.minFps},
      "max_fps": ${it.maxFps}
    }""".trimIndent()
        } ?: "null"
        val supportedQualities = profile.supportedQualityNames.joinToString(prefix = "[", postfix = "]") { "\"${it.escapeJson()}\"" }
        val supportedFpsRanges = profile.supportedFpsRanges.joinToString(prefix = "[", postfix = "]") {
            """{
      "min_fps": ${it.minFps},
      "max_fps": ${it.maxFps}
    }""".trimIndent()
        }
        return """{
    "selected_quality": "${profile.selectedQualityTier.name}",
    "selected_camerax_quality": "${profile.selectedCameraXQualityName.escapeJson()}",
    "target_width": ${profile.targetWidth?.toString() ?: "null"},
    "target_height": ${profile.targetHeight?.toString() ?: "null"},
    "preferred_target_fps": ${profile.preferredTargetFps},
    "selected_fps_range": $selectedFpsRange,
    "supported_qualities": $supportedQualities,
    "supported_fps_ranges": $supportedFpsRanges,
    "selection_reason": "${profile.selectionReason.escapeJson()}"
  }""".trimIndent()
    }

    private fun String.escapeJson(): String = buildString {
        this@escapeJson.forEach { char ->
            when (char) {
                '\\' -> append("\\\\")
                '"' -> append("\\\"")
                '\n' -> append("\\n")
                '\r' -> append("\\r")
                '\t' -> append("\\t")
                else -> append(char)
            }
        }
    }

    companion object {
        const val FIXED_CLIP_DURATION_MS = 4_000L
        private const val READY_DELAY_MS = 500L
        private const val YOI_DELAY_MS = 1_000L
        private const val PROMPT_DELAY_MS = 500L
        private const val BETWEEN_STRIKE_PAUSE_MS = 500L
        private const val METADATA_FILE_NAME = "guided_jodan_session_metadata.json"

        fun createPlan(): List<GuidedStrikePlan> {
            val counts = listOf("Ichi", "Ni", "San", "Shi", "Go", "Roku", "Shichi", "Hachi", "Ku", "Ju")
            return counts.mapIndexed { index, count ->
                val strikeIndex = index + 1
                GuidedStrikePlan(
                    index = strikeIndex,
                    japaneseCount = count,
                    expectedSide = if (strikeIndex % 2 == 1) StrikeSide.RIGHT else StrikeSide.LEFT,
                )
            }
        }
    }
}
