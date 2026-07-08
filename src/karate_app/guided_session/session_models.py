"""Domain models for guided Jodan clip recorder sessions."""

from __future__ import annotations

from dataclasses import dataclass, field
from enum import Enum
from typing import Protocol


class SessionCommand(str, Enum):
    OSU = "OSU"
    STOP = "STOP"
    REPEAT = "REPEAT"
    PAUSE = "PAUSE"
    CONTINUE = "CONTINUE"
    UNKNOWN = "UNKNOWN"


class SessionState(str, Enum):
    IDLE = "IDLE"
    SETUP = "SETUP"
    WAITING_FOR_OSU = "WAITING_FOR_OSU"
    YOI = "YOI"
    BASELINE_CAPTURE = "BASELINE_CAPTURE"
    PROMPTING_STRIKE = "PROMPTING_STRIKE"
    CAPTURING_STRIKE = "CAPTURING_STRIKE"
    STRIKE_COMPLETE = "STRIKE_COMPLETE"
    STOPPED = "STOPPED"
    COMPLETE = "COMPLETE"


class StrikeSide(str, Enum):
    RIGHT = "right"
    LEFT = "left"


@dataclass(frozen=True)
class StrikePlan:
    index: int
    japanese_count: str
    expected_side: StrikeSide
    file_name: str


@dataclass(frozen=True)
class CapturedStrikeClip:
    strike_index: int
    expected_side: StrikeSide
    file_name: str
    capture_reason: str
    rough_completion_time_ms: int | None = None
    clip_duration_ms: int | None = None


@dataclass(frozen=True)
class SessionResult:
    completed: bool
    stopped_by_user: bool
    clips: list[CapturedStrikeClip]
    session_summary: str


@dataclass(frozen=True)
class SessionMetadata:
    completed: bool
    stopped_by_user: bool
    clips: list[CapturedStrikeClip] = field(default_factory=list)
    session_summary: str = ""


class SpeechPrompter(Protocol):
    def speak(self, text: str) -> None:
        """Speak a prompt to the trainee."""


class CommandListener(Protocol):
    def listen_for_command(
        self, allowed_commands: set[SessionCommand]
    ) -> SessionCommand:
        """Listen for one of the allowed session commands."""


class StrikeClipRecorder(Protocol):
    def record_strike_clip(self, strike_plan: StrikePlan) -> CapturedStrikeClip:
        """Record one strike clip for a planned strike."""


class SessionMetadataWriter(Protocol):
    def write_session_metadata(self, metadata: SessionMetadata) -> None:
        """Persist structured metadata for a guided session."""
