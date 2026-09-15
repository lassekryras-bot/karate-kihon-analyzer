package dk.lasse.karatecliprecorder

import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import dk.lasse.karateanalyzer.capture.retrospective.CueEvent
import dk.lasse.karateanalyzer.capture.retrospective.CueTimeline
import dk.lasse.karateanalyzer.capture.retrospective.RetrospectiveCadence
import dk.lasse.karateanalyzer.capture.retrospective.RetrospectiveSegmenterConfig
import dk.lasse.karateanalyzer.capture.retrospective.RetrospectiveSessionResult
import dk.lasse.karateanalyzer.capture.retrospective.RetrospectiveSessionSegmenter
import dk.lasse.karateanalyzer.capture.retrospective.VideoPoseProcessor
import dk.lasse.karatecliprecorder.captureprofile.SelectedCaptureProfile
import dk.lasse.karatecliprecorder.learning.JapaneseCountLesson
import java.io.File
import java.util.concurrent.Executor
import java.util.concurrent.Executors

/**
 * Controller for guided jodan punch practice sessions.
 *
 * Implements continuous CameraX master recording:
 * 1. A single continuous recording captures the entire drill.
 * 2. Audio/cadence events are tracked on a [CueTimeline].
 * 3. Upon drill completion, the finalized master video is authoritatively processed offline
 *    via [VideoPoseProcessor] and [RetrospectiveSessionSegmenter], producing logical
 *    per-movement intervals without cutting physical MP4 files.
 */
class GuidedJodanSessionController(
    private val recordingAdapter: SessionRecordingAdapter,
    private val videoPoseProcessor: VideoPoseProcessor? = null,
    private val onStateChanged: (GuidedSessionState) -> Unit,
    private val onPromptChanged: (String) -> Unit,
    private val onStrikeChanged: (GuidedStrikePlan?) -> Unit,
    private val onSavedClipCountChanged: (Int) -> Unit,
    private val onComplete: (GuidedSessionResult) -> Unit,
    private val onError: (String) -> Unit,
    var captureProfile: SelectedCaptureProfile? = null,
    private val countdownBeforeTrainingMs: Long = DEFAULT_COUNTDOWN_BEFORE_TRAINING_MS,
    private val postProcessingExecutor: Executor = Executors.newSingleThreadExecutor(),
    private val persistedProcessor: ((String, (Float, Long) -> Unit) -> Int)? = null,
) {
    private val handler = Handler(Looper.getMainLooper())
    private val plan = createPlan()
    private val clipResults = mutableListOf<GuidedClipResult>()
    private val cueEvents = mutableListOf<CueEvent>()
    private var sessionStartMonotonicMs = 0L
    private var masterRecordingResult: RecordingResult? = null
    private var activePlan: GuidedStrikePlan? = null
    private var currentPlanIndex = 0
    private var running = false
    private var cancelled = false
    private var generation = 0L
    private var expectedRecordingSessionId: String? = null

    fun start() {
        if (running) return
        generation++
        recordingAdapter.beginMeasurementSession()
        running = true
        cancelled = false
        currentPlanIndex = 0
        activePlan = null
        clipResults.clear()
        cueEvents.clear()
        sessionStartMonotonicMs = 0L
        masterRecordingResult = null
        expectedRecordingSessionId = null
        onSavedClipCountChanged(0)
        onStrikeChanged(null)
        onStateChanged(GuidedSessionState.READY)
        onPromptChanged("Setup / ready")
        handler.postDelayed({ showYoi() }, countdownBeforeTrainingMs.coerceAtLeast(0L))
    }

    fun cancel() {
        if (!running) return
        cancelled = true
        generation++
        running = false
        handler.removeCallbacksAndMessages(null)
        recordingAdapter.stopRecording()
        recordingAdapter.endMeasurementSession()
        activePlan = null
        onStateChanged(GuidedSessionState.CANCELLED)
        onPromptChanged("Session cancelled")
    }

    fun handleRecordingSaved(result: RecordingResult) {
        if (result.sessionId != null && result.sessionId != expectedRecordingSessionId) return
        masterRecordingResult = result
        if (!running || cancelled) return

        val sessionId = result.sessionId
        val persistent = persistedProcessor
        if (sessionId != null && persistent != null) {
            val attempt = generation
            onStateChanged(GuidedSessionState.ANALYZING)
            onPromptChanged("Analyzing practice session...")
            postProcessingExecutor.execute {
                val processed = runCatching { persistent(sessionId) { fraction, _ ->
                    handler.post { if (running && !cancelled && generation == attempt)
                        onPromptChanged("Analyzing movements... ${(fraction * 100).toInt()}%") }
                } }
                handler.post {
                    if (!running || cancelled || generation != attempt) return@post
                    running = false
                    recordingAdapter.endMeasurementSession()
                    handler.removeCallbacksAndMessages(null)
                    processed.fold(onSuccess = { count ->
                        onSavedClipCountChanged(count)
                        onStateChanged(GuidedSessionState.COMPLETE)
                        onPromptChanged("$count movements found")
                        onComplete(GuidedSessionResult(plan.size, count, "training-session:$sessionId", true,
                            masterVideoPath = result.absolutePath, sessionId = sessionId, userId = result.userId))
                    }, onFailure = { error ->
                        onStateChanged(GuidedSessionState.FAILED)
                        onPromptChanged("Recording saved. Analysis is incomplete.")
                        onError(error.message ?: "Session processing failed")
                    })
                }
            }
            return
        }

        val processor = videoPoseProcessor
        if (processor == null) {
            // Immediate completion when no offline processor is configured (e.g. lightweight tests)
            finishSession(completed = true, masterResult = result, retroResult = null)
        } else {
            onStateChanged(GuidedSessionState.ANALYZING)
            onPromptChanged("Analyzing practice session...")
            postProcessingExecutor.execute {
                val masterFile = File(result.absolutePath)
                val frames = runCatching {
                    processor.processVideo(masterFile) { fraction, _ ->
                        val pct = (fraction * 100).toInt()
                        handler.post {
                            if (running && !cancelled) {
                                onPromptChanged("Analyzing movements... $pct%")
                            }
                        }
                    }
                }.getOrElse { error ->
                    handler.post { onError(error.message ?: "Pose analysis failed") }
                    emptyList()
                }

                val cueTimeline = CueTimeline(
                    sessionRecordingId = result.fileName,
                    cues = cueEvents.toList(),
                )

                val segmenter = RetrospectiveSessionSegmenter(
                    RetrospectiveSegmenterConfig(cadence = RetrospectiveCadence.REPETITIONS),
                )

                val retroResult = segmenter.segment(
                    sequenceId = result.fileName,
                    masterVideoPath = result.absolutePath,
                    frames = frames,
                    cueTimeline = cueTimeline,
                )

                handler.post {
                    if (running && !cancelled) {
                        onSavedClipCountChanged(retroResult.detectedMovementCount)
                        finishSession(completed = true, masterResult = result, retroResult = retroResult)
                    }
                }
            }
        }
    }

    fun handleRecordingError(message: String) {
        if (!running) return
        running = false
        recordingAdapter.stopRecording()
        recordingAdapter.endMeasurementSession()
        activePlan?.let { clipResults.add(GuidedClipResult(it, null)) }
        activePlan = null
        handler.removeCallbacksAndMessages(null)
        onStateChanged(GuidedSessionState.FAILED)
        onPromptChanged("Session failed")
        onError(message)
        if (persistedProcessor == null) finishSession(completed = false, masterResult = null, retroResult = null)
    }

    private fun showYoi() {
        if (!running || cancelled) return
        onStateChanged(GuidedSessionState.YOI)
        onPromptChanged("Yoi")

        // Start continuous master video recording
        val masterFileName = "master_guided_jodan_${System.currentTimeMillis()}"
        val previousSessionId = recordingAdapter.currentRecordingSessionId()
        recordingAdapter.startRecording(masterFileName)
        if (!running || cancelled) return
        expectedRecordingSessionId = recordingAdapter.currentRecordingSessionId()
        if (expectedRecordingSessionId != null && expectedRecordingSessionId == previousSessionId) {
            handleRecordingError("The previous recording is still finishing. Start a new session after it is saved.")
            return
        }
        sessionStartMonotonicMs = SystemClock.elapsedRealtime()
        recordingAdapter.recordSessionEvent("YOI", sessionStartMonotonicMs)
        cueEvents.add(
            CueEvent(
                id = "cue-yoi",
                cueName = "YOI",
                monotonicTimestampMs = sessionStartMonotonicMs,
                videoTimestampMs = 0L,
            ),
        )

        handler.postDelayed({ promptNextStrike() }, YOI_DELAY_MS)
    }

    private fun promptNextStrike() {
        if (!running || cancelled) return
        val nextPlan = plan.getOrNull(currentPlanIndex) ?: run {
            finishContinuousRecording()
            return
        }
        activePlan = nextPlan
        currentPlanIndex += 1

        val now = SystemClock.elapsedRealtime()
        recordingAdapter.recordSessionEvent(nextPlan.japaneseCount, now)
        val videoTs = if (sessionStartMonotonicMs > 0) now - sessionStartMonotonicMs else 0L
        cueEvents.add(
            CueEvent(
                id = "cue-${nextPlan.index}",
                cueName = nextPlan.japaneseCount,
                monotonicTimestampMs = now,
                videoTimestampMs = videoTs,
            ),
        )

        onStateChanged(GuidedSessionState.PROMPTING_STRIKE)
        onStrikeChanged(nextPlan)
        onPromptChanged(nextPlan.japaneseCount)

        // Allow cadence time for strike execution before prompting next count
        handler.postDelayed({ promptNextStrike() }, STRIKE_CADENCE_INTERVAL_MS)
    }

    private fun finishContinuousRecording() {
        if (!running || cancelled) return
        activePlan = null
        onStrikeChanged(null)
        onStateChanged(GuidedSessionState.SAVING)
        onPromptChanged("Finalizing session recording...")
        recordingAdapter.recordSessionEvent("STOP", SystemClock.elapsedRealtime())
        recordingAdapter.stopRecording()
    }

    private fun finishSession(
        completed: Boolean,
        masterResult: RecordingResult?,
        retroResult: RetrospectiveSessionResult?,
    ) {
        running = false
        recordingAdapter.endMeasurementSession()
        handler.removeCallbacksAndMessages(null)
        val metadataFile = recordingAdapter.createGuidedSessionFile(METADATA_FILE_NAME)
        metadataFile.writeText(buildMetadataJson(completed, masterResult, retroResult), Charsets.UTF_8)
        onStateChanged(if (completed) GuidedSessionState.COMPLETE else GuidedSessionState.FAILED)
        val count = retroResult?.detectedMovementCount ?: 0
        onPromptChanged(if (completed) "Session complete ($count movements)" else "Session incomplete")
        onComplete(
            GuidedSessionResult(
                expectedClipCount = plan.size,
                savedClipCount = count,
                metadataPath = metadataFile.absolutePath,
                completed = completed,
                masterVideoPath = masterResult?.absolutePath,
                retrospectiveResult = retroResult,
            ),
        )
    }

    private fun buildMetadataJson(
        completed: Boolean,
        masterResult: RecordingResult?,
        retroResult: RetrospectiveSessionResult?,
    ): String {
        val masterPath = masterResult?.absolutePath.orEmpty()
        val movementsJson = retroResult?.movements?.joinToString(separator = ",\n") { m ->
            """
    {
      "movement_number": ${m.movementNumber},
      "logical_start_ms": ${m.logicalStartTimestampMs},
      "logical_end_ms": ${m.logicalEndTimestampMs},
      "duration_ms": ${m.durationMs},
      "retained_start_ms": ${m.retainedStartTimestampMs},
      "retained_end_ms": ${m.retainedEndTimestampMs},
      "preferred_snapshot_ms": ${m.preferredSnapshotTimestampMs ?: "null"},
      "associated_cue": ${m.associatedCue?.let { "\"${it.cueName}\"" } ?: "null"},
      "analysis_status": "${m.analysisStatus.name}"
    }""".trimEnd()
        } ?: ""

        val legacyClipsJson = plan.joinToString(separator = ",\n") { strike ->
            val movement = retroResult?.movements?.getOrNull(strike.index - 1)
            """
    {
      "strike_index": ${strike.index},
      "japanese_count": "${strike.japaneseCount}",
      "expected_side": "${strike.expectedSide.metadataValue}",
      "file_name": "${strike.fileName}",
      "saved": ${movement != null || completed},
      "logical_start_ms": ${movement?.logicalStartTimestampMs ?: "null"},
      "logical_end_ms": ${movement?.logicalEndTimestampMs ?: "null"},
      "path": "${masterPath.escapeJson()}"
    }""".trimEnd()
        }

        return """
{
  "schema_version": "android-guided-jodan-session-v2",
  "session_type": "continuous_master_recording_retrospective_v2",
  "master_video_path": "${masterPath.escapeJson()}",
  "total_frames": ${retroResult?.totalFrames ?: 0},
  "total_duration_ms": ${retroResult?.totalDurationMs ?: 0},
  "expected_strike_count": ${plan.size},
  "detected_movement_count": ${retroResult?.detectedMovementCount ?: (if (completed) plan.size else 0)},
  "camera_profile": ${buildCameraProfileJson()},
  "movements": [
$movementsJson
  ],
  "clips": [
$legacyClipsJson
  ],
  "completed": $completed,
  "successful_clip_count": ${retroResult?.detectedMovementCount ?: (if (completed) plan.size else 0)}
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
        const val DEFAULT_COUNTDOWN_BEFORE_TRAINING_MS = 3_000L
        private const val YOI_DELAY_MS = 1_200L
        private const val STRIKE_CADENCE_INTERVAL_MS = 1_800L
        private const val METADATA_FILE_NAME = "guided_jodan_session_metadata.json"

        fun createPlan(): List<GuidedStrikePlan> {
            return JapaneseCountLesson.items.map { count ->
                val strikeIndex = count.number.toInt()
                GuidedStrikePlan(
                    index = strikeIndex,
                    japaneseCount = count.japanese,
                    expectedSide = if (strikeIndex % 2 == 1) StrikeSide.RIGHT else StrikeSide.LEFT,
                )
            }
        }
    }
}
