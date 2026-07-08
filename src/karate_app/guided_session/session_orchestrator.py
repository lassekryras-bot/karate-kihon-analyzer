"""Guided Jodan session orchestration using app-side service abstractions."""

from __future__ import annotations

from uuid import uuid4

from karate_app.guided_session.session_models import (
    CaptureAttemptMetadata,
    CaptureAttemptOutcome,
    CapturedStrikeClip,
    CommandListener,
    GuidedSessionConfigMetadata,
    GuidedSessionMetadata,
    GuidedSessionSummary,
    GuidedSessionType,
    SessionCommand,
    SessionMetadataWriter,
    SessionResult,
    SessionState,
    SpeechPrompter,
    StrikeCaptureConfig,
    StrikeCaptureResult,
    StrikeCaptureState,
    StrikeClipRecorder,
    StrikePlan,
    StrikePlanMetadata,
)
from karate_app.guided_session.session_plan import create_jodan_session_plan
from karate_app.guided_session.strike_capture_controller import StrikeCaptureController

SETUP_INSTRUCTION = (
    "Stand in ready position. Place the phone so your full body is visible. "
    "Say osu when ready."
)
METADATA_SCHEMA_VERSION = "guided-jodan-session-metadata-v2"


class GuidedJodanSessionOrchestrator:
    """Runs a fake-service guided Jodan clip recording session."""

    def __init__(
        self,
        speech_prompter: SpeechPrompter,
        command_listener: CommandListener,
        clip_recorder: StrikeClipRecorder | None,
        metadata_writer: SessionMetadataWriter,
        session_plan: list[StrikePlan] | None = None,
        capture_controller: StrikeCaptureController | None = None,
        capture_config: StrikeCaptureConfig | None = None,
        session_id: str | None = None,
    ) -> None:
        self.speech_prompter = speech_prompter
        self.command_listener = command_listener
        self.clip_recorder = clip_recorder
        self.capture_controller = capture_controller
        self.capture_config = capture_config or StrikeCaptureConfig()
        self.metadata_writer = metadata_writer
        self.session_plan = session_plan or create_jodan_session_plan()
        self.session_id = session_id or str(uuid4())
        self.state = SessionState.IDLE
        self.attempts: list[CaptureAttemptMetadata] = []
        self.skipped_strike_count = 0

    def start_session(self) -> SessionResult:
        clips: list[CapturedStrikeClip] = []
        self.state = SessionState.SETUP
        self.speech_prompter.speak(SETUP_INSTRUCTION)

        self.state = SessionState.WAITING_FOR_OSU
        command = self.command_listener.listen_for_command(
            {SessionCommand.OSU, SessionCommand.STOP}
        )
        if command != SessionCommand.OSU:
            return self._stop(clips)

        self.state = SessionState.YOI
        self.speech_prompter.speak("Yoi.")
        self.state = SessionState.BASELINE_CAPTURE

        for strike_plan in self.session_plan:
            retry_number = 0
            while True:
                if self._received_stop_if_available():
                    self._cancel_capture_if_available()
                    return self._stop(clips)

                self.state = SessionState.PROMPTING_STRIKE
                self.speech_prompter.speak(strike_plan.japanese_count)
                self.state = SessionState.CAPTURING_STRIKE
                capture_result = self._capture_strike(strike_plan)
                self.attempts.append(
                    self._to_attempt_metadata(capture_result, strike_plan, retry_number)
                )

                if capture_result.state == StrikeCaptureState.CLIP_READY:
                    clips.append(self._to_captured_clip(capture_result))
                    self.state = SessionState.STRIKE_COMPLETE
                    self.speech_prompter.speak(f"Clip {strike_plan.index} saved.")
                    break

                if capture_result.state == StrikeCaptureState.CANCELLED:
                    return self._stop(clips)

                if self._is_retryable_capture_timeout(capture_result.state):
                    if retry_number < self.capture_config.max_retries_per_strike:
                        retry_number += 1
                        self.speech_prompter.speak("Punch not detected. Try again.")
                        continue
                    self.skipped_strike_count += 1
                    self.speech_prompter.speak("Skipping this strike.")
                    break

                return self._capture_failed(clips, capture_result)

        self.state = SessionState.COMPLETE
        summary = f"Session complete. {len(clips)} clips saved."
        self.speech_prompter.speak(summary)
        result = SessionResult(
            completed=True,
            stopped_by_user=False,
            clips=clips,
            session_summary=summary,
        )
        self._write_metadata(result)
        return result

    def _received_stop_if_available(self) -> bool:
        command = self.command_listener.listen_for_command(
            {SessionCommand.STOP, SessionCommand.CONTINUE}
        )
        return command == SessionCommand.STOP

    def _capture_strike(self, strike_plan: StrikePlan) -> StrikeCaptureResult:
        if self.capture_controller is not None:
            return self.capture_controller.capture_strike_clip(
                strike_plan=strike_plan,
                config=self.capture_config,
            )
        if self.clip_recorder is None:
            raise ValueError("clip_recorder or capture_controller is required")
        clip = self.clip_recorder.record_strike_clip(strike_plan)
        return StrikeCaptureResult(
            strike_index=clip.strike_index,
            expected_side=clip.expected_side,
            file_name=clip.file_name,
            state=StrikeCaptureState.CLIP_READY,
            capture_mode=self.capture_config.capture_mode,
            capture_reason=clip.capture_reason,
            clip_duration_ms=clip.clip_duration_ms,
            rough_completion_time_ms=clip.rough_completion_time_ms,
            cancelled=False,
        )

    def _is_retryable_capture_timeout(self, state: StrikeCaptureState) -> bool:
        return state in {
            StrikeCaptureState.NO_MOVEMENT_TIMEOUT,
            StrikeCaptureState.INCOMPLETE_STRIKE_TIMEOUT,
            StrikeCaptureState.ACTIVE_STRIKE_TIMEOUT,
        }

    def _cancel_capture_if_available(self) -> None:
        if self.capture_controller is not None:
            self.capture_controller.cancel_capture()

    def _to_captured_clip(self, capture_result: StrikeCaptureResult) -> CapturedStrikeClip:
        return CapturedStrikeClip(
            strike_index=capture_result.strike_index,
            expected_side=capture_result.expected_side,
            file_name=capture_result.file_name,
            capture_reason=capture_result.capture_reason,
            rough_completion_time_ms=capture_result.rough_completion_time_ms,
            clip_duration_ms=capture_result.clip_duration_ms,
            capture_diagnostics=capture_result.diagnostics,
        )

    def _to_attempt_metadata(
        self,
        capture_result: StrikeCaptureResult,
        strike_plan: StrikePlan,
        retry_number: int,
    ) -> CaptureAttemptMetadata:
        outcome = CaptureAttemptOutcome(capture_result.state.value)
        clip_saved = capture_result.state == StrikeCaptureState.CLIP_READY
        return CaptureAttemptMetadata(
            attempt_id=len(self.attempts) + 1,
            strike_index=strike_plan.index,
            expected_side=strike_plan.expected_side,
            japanese_count=strike_plan.japanese_count,
            planned_file_name=strike_plan.file_name,
            outcome=outcome,
            capture_reason=capture_result.capture_reason,
            file_name=capture_result.file_name if clip_saved else None,
            clip_saved=clip_saved,
            retry_number=retry_number,
            capture_mode=capture_result.capture_mode,
            clip_duration_ms=capture_result.clip_duration_ms,
            rough_movement_start_ms=capture_result.rough_movement_start_ms,
            rough_completion_time_ms=capture_result.rough_completion_time_ms,
            post_roll_ms=capture_result.post_roll_ms,
            timeout_ms=capture_result.timeout_ms,
            cancelled=capture_result.cancelled,
            diagnostics=capture_result.diagnostics,
        )

    def _stop(self, clips: list[CapturedStrikeClip]) -> SessionResult:
        self.state = SessionState.STOPPED
        summary = f"Session stopped. {len(clips)} clips saved."
        self.speech_prompter.speak(summary)
        result = SessionResult(False, True, clips, summary)
        self._write_metadata(result)
        return result

    def _capture_failed(
        self, clips: list[CapturedStrikeClip], capture_result: StrikeCaptureResult
    ) -> SessionResult:
        self.state = SessionState.STOPPED
        summary = (
            "Session stopped because capture failed "
            f"on strike {capture_result.strike_index}. {len(clips)} clips saved."
        )
        self.speech_prompter.speak(summary)
        result = SessionResult(False, False, clips, summary)
        self._write_metadata(result)
        return result

    def _write_metadata(self, result: SessionResult) -> None:
        metadata = GuidedSessionMetadata(
            schema_version=METADATA_SCHEMA_VERSION,
            session_id=self.session_id,
            session_type=GuidedSessionType.JODAN_CLIP_SESSION,
            config=GuidedSessionConfigMetadata(
                session_type=GuidedSessionType.JODAN_CLIP_SESSION,
                strike_type="straight_punch",
                target="jodan",
                expected_strike_count=len(self.session_plan),
                capture_mode=self.capture_config.capture_mode,
                fixed_clip_duration_ms=self.capture_config.fixed_clip_duration_ms,
                waiting_for_movement_timeout_ms=self.capture_config.waiting_for_movement_timeout_ms,
                active_strike_timeout_ms=self.capture_config.active_strike_timeout_ms,
                progress_stall_timeout_ms=self.capture_config.progress_stall_timeout_ms,
                post_roll_ms=self.capture_config.post_roll_ms,
                minimum_elbow_extension_angle_degrees=(
                    self.capture_config.minimum_elbow_extension_angle_degrees
                ),
                max_retries_per_strike=self.capture_config.max_retries_per_strike,
            ),
            strike_plan=[
                StrikePlanMetadata(s.index, s.japanese_count, s.expected_side, s.file_name)
                for s in self.session_plan
            ],
            attempts=self.attempts,
            successful_clips=result.clips,
            summary=GuidedSessionSummary(
                completed=result.completed,
                stopped_by_user=result.stopped_by_user,
                expected_strike_count=len(self.session_plan),
                successful_clip_count=len(result.clips),
                failed_attempt_count=sum(1 for a in self.attempts if not a.clip_saved),
                retry_count=sum(1 for a in self.attempts if a.retry_number > 0),
                skipped_strike_count=self.skipped_strike_count,
                total_attempt_count=len(self.attempts),
                session_summary=result.session_summary,
            ),
        )
        self.metadata_writer.write_guided_session_metadata(metadata)
