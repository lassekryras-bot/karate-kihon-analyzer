"""Guided Jodan session orchestration using app-side service abstractions."""

from __future__ import annotations

from karate_app.guided_session.session_models import (
    CapturedStrikeClip,
    CommandListener,
    SessionCommand,
    SessionMetadata,
    SessionMetadataWriter,
    SessionResult,
    SessionState,
    SpeechPrompter,
    StrikeCaptureConfig,
    StrikeCaptureResult,
    StrikeCaptureState,
    StrikeClipRecorder,
    StrikePlan,
)
from karate_app.guided_session.session_plan import create_jodan_session_plan
from karate_app.guided_session.strike_capture_controller import StrikeCaptureController

SETUP_INSTRUCTION = (
    "Stand in ready position. Place the phone so your full body is visible. "
    "Say osu when ready."
)


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
    ) -> None:
        self.speech_prompter = speech_prompter
        self.command_listener = command_listener
        self.clip_recorder = clip_recorder
        self.capture_controller = capture_controller
        self.capture_config = capture_config or StrikeCaptureConfig()
        self.metadata_writer = metadata_writer
        self.session_plan = session_plan or create_jodan_session_plan()
        self.state = SessionState.IDLE

    def start_session(self) -> SessionResult:
        clips: list[CapturedStrikeClip] = []
        self.state = SessionState.SETUP
        self.speech_prompter.speak(SETUP_INSTRUCTION)

        self.state = SessionState.WAITING_FOR_OSU
        command = self.command_listener.listen_for_command(
            {SessionCommand.OSU, SessionCommand.STOP}
        )
        if command == SessionCommand.STOP:
            return self._stop(clips)
        if command != SessionCommand.OSU:
            return self._stop(clips)

        self.state = SessionState.YOI
        self.speech_prompter.speak("Yoi.")
        self.state = SessionState.BASELINE_CAPTURE

        for strike_plan in self.session_plan:
            if self._received_stop_if_available():
                self._cancel_capture_if_available()
                return self._stop(clips)

            self.state = SessionState.PROMPTING_STRIKE
            self.speech_prompter.speak(strike_plan.japanese_count)
            self.state = SessionState.CAPTURING_STRIKE
            capture_result = self._capture_strike(strike_plan)
            if capture_result.state != StrikeCaptureState.CLIP_READY:
                return self._stop(clips)
            clips.append(self._to_captured_clip(capture_result))
            self.state = SessionState.STRIKE_COMPLETE
            self.speech_prompter.speak(f"Clip {strike_plan.index} saved.")

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

    def _stop(self, clips: list[CapturedStrikeClip]) -> SessionResult:
        self.state = SessionState.STOPPED
        summary = f"Session stopped. {len(clips)} clips saved."
        self.speech_prompter.speak(summary)
        result = SessionResult(
            completed=False,
            stopped_by_user=True,
            clips=clips,
            session_summary=summary,
        )
        self._write_metadata(result)
        return result

    def _write_metadata(self, result: SessionResult) -> None:
        self.metadata_writer.write_session_metadata(
            SessionMetadata(
                completed=result.completed,
                stopped_by_user=result.stopped_by_user,
                clips=result.clips,
                session_summary=result.session_summary,
            )
        )
