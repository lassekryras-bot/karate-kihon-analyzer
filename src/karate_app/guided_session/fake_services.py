"""Fake services for guided session tests and command-line simulation."""

from __future__ import annotations

import json
from dataclasses import asdict
from pathlib import Path

from karate_app.guided_session.session_models import (
    CapturedStrikeClip,
    SessionCommand,
    SessionMetadata,
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
    """Fake fixed-length clip recorder."""

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


class FakeSessionMetadataWriter:
    """Stores session metadata in memory and optionally writes JSON to a folder."""

    def __init__(self, output_folder: Path | None = None) -> None:
        self.output_folder = output_folder
        self.written_metadata: list[SessionMetadata] = []

    def write_session_metadata(self, metadata: SessionMetadata) -> None:
        self.written_metadata.append(metadata)
        if self.output_folder is None:
            return

        self.output_folder.mkdir(parents=True, exist_ok=True)
        metadata_path = self.output_folder / "guided_jodan_session_metadata.json"
        metadata_path.write_text(json.dumps(asdict(metadata), indent=2), encoding="utf-8")
