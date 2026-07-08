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
    StrikeClipRecorder,
    StrikePlan,
)
from karate_app.guided_session.session_plan import create_jodan_session_plan

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
        clip_recorder: StrikeClipRecorder,
        metadata_writer: SessionMetadataWriter,
        session_plan: list[StrikePlan] | None = None,
    ) -> None:
        self.speech_prompter = speech_prompter
        self.command_listener = command_listener
        self.clip_recorder = clip_recorder
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
                return self._stop(clips)

            self.state = SessionState.PROMPTING_STRIKE
            self.speech_prompter.speak(strike_plan.japanese_count)
            self.state = SessionState.CAPTURING_STRIKE
            clips.append(self.clip_recorder.record_strike_clip(strike_plan))
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
