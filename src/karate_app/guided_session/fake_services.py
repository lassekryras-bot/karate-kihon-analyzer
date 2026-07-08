"""Fake services for guided session tests and command-line simulation."""

from __future__ import annotations

import json
from dataclasses import asdict, is_dataclass
from pathlib import Path

from karate_app.guided_session.session_models import (
    CaptureMode,
    CapturedStrikeClip,
    SessionCommand,
    GuidedSessionMetadata,
    SessionMetadata,
    StrikeCaptureConfig,
    StrikeCaptureEvent,
    StrikeCaptureResult,
    StrikeCaptureState,
    StrikePlan,
)


class FakeSpeechPrompter:
    """In-memory speech service that also prints prompts for simulator output."""

    def __init__(self) -> None:
        self.spoken_prompts: list[str] = []

    def speak(self, text: str) -> None:
        self.spoken_prompts.append(text)
        print(text)


class FakeCommandListener:
    """Command listener backed by a predefined queue."""

    def __init__(
        self,
        commands: list[SessionCommand] | None = None,
        default_command: SessionCommand = SessionCommand.OSU,
    ) -> None:
        self.commands = list(commands or [])
        self.default_command = default_command

    def listen_for_command(
        self, allowed_commands: set[SessionCommand]
    ) -> SessionCommand:
        if self.commands:
            command = self.commands.pop(0)
        else:
            command = self.default_command

        if command in allowed_commands:
            return command
        if SessionCommand.OSU in allowed_commands and command == SessionCommand.CONTINUE:
            return SessionCommand.OSU
        return SessionCommand.UNKNOWN


class FakeStrikeClipRecorder:
    """Legacy fake fixed-length clip recorder."""

    def __init__(self) -> None:
        self.recorded_file_names: list[str] = []

    def record_strike_clip(self, strike_plan: StrikePlan) -> CapturedStrikeClip:
        print(f"record {strike_plan.file_name}")
        self.recorded_file_names.append(strike_plan.file_name)
        return CapturedStrikeClip(
            strike_index=strike_plan.index,
            expected_side=strike_plan.expected_side,
            file_name=strike_plan.file_name,
            capture_reason="fixed_length_fake_capture",
            rough_completion_time_ms=2000,
        )


class FakeStrikeCaptureController:
    """Scriptable fake implementation of the strike capture controller."""

    def __init__(
        self,
        scripted_results: list[StrikeCaptureState | StrikeCaptureResult] | None = None,
    ) -> None:
        self.scripted_results = list(scripted_results or [])
        self.cancel_requested = False
        self.events: list[StrikeCaptureEvent] = []
        self.results: list[StrikeCaptureResult] = []
        self.recorded_file_names: list[str] = []

    def capture_strike_clip(
        self,
        strike_plan: StrikePlan,
        config: StrikeCaptureConfig,
    ) -> StrikeCaptureResult:
        self.events.extend(
            [
                StrikeCaptureEvent.RECORDING_STARTED,
                StrikeCaptureEvent.PROMPT_STARTED,
                StrikeCaptureEvent.PROMPT_FINISHED,
            ]
        )
        if self.cancel_requested:
            result = self._result_for_state(
                strike_plan=strike_plan,
                config=config,
                state=StrikeCaptureState.CANCELLED,
            )
        elif self.scripted_results:
            scripted_result = self.scripted_results.pop(0)
            if isinstance(scripted_result, StrikeCaptureResult):
                result = scripted_result
            else:
                result = self._result_for_state(
                    strike_plan=strike_plan,
                    config=config,
                    state=scripted_result,
                )
        else:
            result = self._successful_result(strike_plan, config)

        if result.state == StrikeCaptureState.CLIP_READY:
            self.events.extend(
                [
                    StrikeCaptureEvent.MOVEMENT_STARTED,
                    StrikeCaptureEvent.PROGRESS_DETECTED,
                    StrikeCaptureEvent.POSSIBLE_IMPACT,
                    StrikeCaptureEvent.POST_ROLL_COMPLETE,
                ]
            )
            self.recorded_file_names.append(result.file_name)
        elif result.state == StrikeCaptureState.NO_MOVEMENT_TIMEOUT:
            self.events.append(StrikeCaptureEvent.NO_MOVEMENT_TIMEOUT)
        elif result.state == StrikeCaptureState.INCOMPLETE_STRIKE_TIMEOUT:
            self.events.append(StrikeCaptureEvent.INCOMPLETE_STRIKE_TIMEOUT)
        elif result.state == StrikeCaptureState.ACTIVE_STRIKE_TIMEOUT:
            self.events.append(StrikeCaptureEvent.ACTIVE_STRIKE_TIMEOUT)
        elif result.state == StrikeCaptureState.CANCELLED:
            self.events.append(StrikeCaptureEvent.CANCEL_REQUESTED)
        elif result.state == StrikeCaptureState.FAILED:
            self.events.append(StrikeCaptureEvent.RECORDING_FAILED)

        self.results.append(result)
        print(f"capture {result.file_name}")
        print(f"capture reason: {result.capture_reason}")
        return result

    def cancel_capture(self) -> None:
        self.cancel_requested = True

    def _successful_result(
        self,
        strike_plan: StrikePlan,
        config: StrikeCaptureConfig,
    ) -> StrikeCaptureResult:
        return StrikeCaptureResult(
            strike_index=strike_plan.index,
            expected_side=strike_plan.expected_side,
            file_name=strike_plan.file_name,
            state=StrikeCaptureState.CLIP_READY,
            capture_mode=CaptureMode.FAKE,
            capture_reason="fixed_length_fake_capture",
            clip_duration_ms=config.fixed_clip_duration_ms,
            rough_movement_start_ms=1_000,
            rough_completion_time_ms=2_500,
            post_roll_ms=config.post_roll_ms,
            cancelled=False,
            diagnostics={"configured_mode": config.capture_mode.value},
        )

    def _result_for_state(
        self,
        strike_plan: StrikePlan,
        config: StrikeCaptureConfig,
        state: StrikeCaptureState,
    ) -> StrikeCaptureResult:
        reason_by_state = {
            StrikeCaptureState.NO_MOVEMENT_TIMEOUT: "no_movement_timeout",
            StrikeCaptureState.INCOMPLETE_STRIKE_TIMEOUT: "incomplete_strike_timeout",
            StrikeCaptureState.ACTIVE_STRIKE_TIMEOUT: "active_strike_timeout",
            StrikeCaptureState.CANCELLED: "cancelled_by_user",
            StrikeCaptureState.FAILED: "recording_failed",
        }
        timeout_by_state = {
            StrikeCaptureState.NO_MOVEMENT_TIMEOUT: config.waiting_for_movement_timeout_ms,
            StrikeCaptureState.INCOMPLETE_STRIKE_TIMEOUT: config.progress_stall_timeout_ms,
            StrikeCaptureState.ACTIVE_STRIKE_TIMEOUT: config.active_strike_timeout_ms,
        }
        if state == StrikeCaptureState.CLIP_READY:
            return self._successful_result(strike_plan, config)

        return StrikeCaptureResult(
            strike_index=strike_plan.index,
            expected_side=strike_plan.expected_side,
            file_name=strike_plan.file_name,
            state=state,
            capture_mode=CaptureMode.FAKE,
            capture_reason=reason_by_state.get(state, "scripted_capture_result"),
            post_roll_ms=config.post_roll_ms,
            timeout_ms=timeout_by_state.get(state),
            cancelled=state == StrikeCaptureState.CANCELLED,
            diagnostics={"scripted_state": state.value},
        )


class FakeSessionMetadataWriter:
    """Stores session metadata in memory and optionally writes JSON to a folder."""

    def __init__(self, output_folder: Path | None = None) -> None:
        self.output_folder = output_folder
        self.written_metadata: list[SessionMetadata] = []
        self.written_guided_session_metadata: list[GuidedSessionMetadata] = []
        self.last_metadata_path: Path | None = None

    def write_session_metadata(self, metadata: SessionMetadata) -> None:
        self.written_metadata.append(metadata)
        if self.output_folder is None:
            return

        self.output_folder.mkdir(parents=True, exist_ok=True)
        metadata_path = self.output_folder / "guided_jodan_session_metadata.json"
        self.last_metadata_path = metadata_path
        metadata_path.write_text(
            json.dumps(_to_json_safe(metadata), indent=2), encoding="utf-8"
        )

    def write_guided_session_metadata(self, metadata: GuidedSessionMetadata) -> None:
        self.written_guided_session_metadata.append(metadata)
        if self.output_folder is None:
            return

        self.output_folder.mkdir(parents=True, exist_ok=True)
        metadata_path = self.output_folder / "guided_jodan_session_metadata_v2.json"
        self.last_metadata_path = metadata_path
        metadata_path.write_text(
            json.dumps(_to_json_safe(metadata), indent=2), encoding="utf-8"
        )


def _to_json_safe(value: object) -> object:
    if is_dataclass(value) and not isinstance(value, type):
        return {key: _to_json_safe(item) for key, item in asdict(value).items()}
    if isinstance(value, dict):
        return {str(key): _to_json_safe(item) for key, item in value.items()}
    if isinstance(value, list):
        return [_to_json_safe(item) for item in value]
    if hasattr(value, "value"):
        return value.value
    return value
