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


class CaptureMode(str, Enum):
    FIXED_DURATION = "FIXED_DURATION"
    LIGHTWEIGHT_POSE = "LIGHTWEIGHT_POSE"
    MANUAL = "MANUAL"
    FAKE = "FAKE"


class StrikeCaptureState(str, Enum):
    IDLE = "IDLE"
    PREPARING = "PREPARING"
    RECORDING_PREROLL = "RECORDING_PREROLL"
    PROMPTING = "PROMPTING"
    WAITING_FOR_MOVEMENT = "WAITING_FOR_MOVEMENT"
    STRIKE_IN_PROGRESS = "STRIKE_IN_PROGRESS"
    POSSIBLE_IMPACT_DETECTED = "POSSIBLE_IMPACT_DETECTED"
    POST_ROLL = "POST_ROLL"
    CLIP_READY = "CLIP_READY"
    NO_MOVEMENT_TIMEOUT = "NO_MOVEMENT_TIMEOUT"
    INCOMPLETE_STRIKE_TIMEOUT = "INCOMPLETE_STRIKE_TIMEOUT"
    CANCELLED = "CANCELLED"
    FAILED = "FAILED"


class StrikeCaptureEvent(str, Enum):
    PROMPT_STARTED = "PROMPT_STARTED"
    PROMPT_FINISHED = "PROMPT_FINISHED"
    RECORDING_STARTED = "RECORDING_STARTED"
    MOVEMENT_STARTED = "MOVEMENT_STARTED"
    PROGRESS_DETECTED = "PROGRESS_DETECTED"
    POSSIBLE_IMPACT = "POSSIBLE_IMPACT"
    POST_ROLL_COMPLETE = "POST_ROLL_COMPLETE"
    NO_MOVEMENT_TIMEOUT = "NO_MOVEMENT_TIMEOUT"
    INCOMPLETE_STRIKE_TIMEOUT = "INCOMPLETE_STRIKE_TIMEOUT"
    CANCEL_REQUESTED = "CANCEL_REQUESTED"
    RECORDING_FAILED = "RECORDING_FAILED"


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
    capture_diagnostics: dict[str, str | int | float | bool | None] = field(default_factory=dict)


@dataclass(frozen=True)
class StrikeCaptureResult:
    strike_index: int
    expected_side: StrikeSide
    file_name: str
    state: StrikeCaptureState
    capture_mode: CaptureMode
    capture_reason: str
    clip_duration_ms: int | None = None
    rough_movement_start_ms: int | None = None
    rough_completion_time_ms: int | None = None
    post_roll_ms: int | None = None
    timeout_ms: int | None = None
    cancelled: bool = False
    diagnostics: dict[str, str | int | float | bool | None] = field(default_factory=dict)


@dataclass(frozen=True)
class StrikeCaptureConfig:
    capture_mode: CaptureMode = CaptureMode.FAKE
    fixed_clip_duration_ms: int = 4_000
    waiting_for_movement_timeout_ms: int = 5_000
    active_strike_timeout_ms: int = 10_000
    progress_stall_timeout_ms: int = 2_000
    post_roll_ms: int = 500
    minimum_elbow_extension_angle_degrees: int = 160


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
        """Legacy synchronous recorder; prefer StrikeCaptureController."""


class SessionMetadataWriter(Protocol):
    def write_session_metadata(self, metadata: SessionMetadata) -> None:
        """Persist structured metadata for a guided session."""
