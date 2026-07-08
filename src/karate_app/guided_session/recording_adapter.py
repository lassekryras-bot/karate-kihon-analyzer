"""Low-level video recording adapter contract for guided sessions."""

from __future__ import annotations

from typing import Protocol

from karate_app.guided_session.session_models import (
    RecordingResult,
    RecordingStartRequest,
    RecordingState,
    RecordingStopRequest,
)


class RecordingAdapter(Protocol):
    """Generic app-side video recording boundary.

    Implementations only manage video recording lifecycle and saved file details.
    They must not analyze karate technique, detect impact frames, or score strikes.
    """

    def start_recording(self, request: RecordingStartRequest) -> None:
        """Start recording video for the given file request."""

    def stop_recording(self, request: RecordingStopRequest) -> RecordingResult:
        """Stop recording and return saved-file or failure details."""

    def cancel_recording(self, reason: str) -> RecordingResult:
        """Cancel the active recording and return discard details."""

    def current_state(self) -> RecordingState:
        """Return the current low-level recording state."""
