"""Strike capture controller protocol for guided session recording."""

from __future__ import annotations

from typing import Protocol

from karate_app.guided_session.session_models import (
    StrikeCaptureConfig,
    StrikeCaptureResult,
    StrikePlan,
)


class StrikeCaptureController(Protocol):
    """App-side interface for recording one planned strike clip.

    Implementations may decide when enough video has been captured, but must not
    score technique or choose the analyzer's exact impact frame.
    """

    def capture_strike_clip(
        self,
        strike_plan: StrikePlan,
        config: StrikeCaptureConfig,
    ) -> StrikeCaptureResult:
        """Capture one strike clip for a planned strike."""

    def cancel_capture(self) -> None:
        """Request cancellation of the active capture if one is running."""
