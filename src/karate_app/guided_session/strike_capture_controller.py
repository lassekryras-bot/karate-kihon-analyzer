"""Strike capture controller protocol and fixed-duration implementation."""

from __future__ import annotations

from typing import Protocol

from karate_app.guided_session.recording_adapter import RecordingAdapter
from karate_app.guided_session.session_models import (
    CaptureMode,
    RecordingResult,
    RecordingStartRequest,
    RecordingState,
    RecordingStopRequest,
    StrikeCaptureConfig,
    StrikeCaptureResult,
    StrikeCaptureState,
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


class FixedDurationStrikeCaptureController:
    """Strike capture controller backed by a low-level recording adapter.

    This implementation proves the recorder boundary with fixed-duration app
    logic only. It does not perform Android recording, MediaPipe detection,
    karate analysis, or scoring.
    """

    def __init__(self, recording_adapter: RecordingAdapter) -> None:
        self.recording_adapter = recording_adapter
        self.cancel_requested = False
        self.results: list[StrikeCaptureResult] = []
        self.recorded_file_names: list[str] = []

    def capture_strike_clip(
        self,
        strike_plan: StrikePlan,
        config: StrikeCaptureConfig,
    ) -> StrikeCaptureResult:
        start_request = RecordingStartRequest(
            file_name=strike_plan.file_name,
            strike_index=strike_plan.index,
            expected_side=strike_plan.expected_side,
            japanese_count=strike_plan.japanese_count,
            capture_mode=CaptureMode.FIXED_DURATION,
            metadata={
                "configured_capture_mode": config.capture_mode.value,
                "fixed_clip_duration_ms": config.fixed_clip_duration_ms,
            },
        )

        try:
            self.recording_adapter.start_recording(start_request)
        except Exception as exc:  # adapter boundary converts implementation failure
            result = self._failed_result(
                strike_plan=strike_plan,
                failure_reason=str(exc),
                adapter_state=self.recording_adapter.current_state(),
            )
            self.results.append(result)
            return result

        if self.cancel_requested:
            recording_result = self.recording_adapter.cancel_recording("cancelled_by_user")
            result = self._cancelled_result(strike_plan, recording_result)
            self.results.append(result)
            return result

        recording_result = self.recording_adapter.stop_recording(
            RecordingStopRequest(
                reason="fixed_duration_complete",
                requested_at_ms=config.fixed_clip_duration_ms,
            )
        )
        result = self._result_from_recording(strike_plan, recording_result, config)
        if result.state == StrikeCaptureState.CLIP_READY:
            self.recorded_file_names.append(result.file_name)
        self.results.append(result)
        print(f"clip saved: {result.file_name}")
        return result

    def cancel_capture(self) -> None:
        self.cancel_requested = True

    def _result_from_recording(
        self,
        strike_plan: StrikePlan,
        recording_result: RecordingResult,
        config: StrikeCaptureConfig,
    ) -> StrikeCaptureResult:
        if recording_result.state == RecordingState.CANCELLED:
            return self._cancelled_result(strike_plan, recording_result)
        if not recording_result.saved or recording_result.state != RecordingState.SAVED:
            return self._failed_result(
                strike_plan=strike_plan,
                failure_reason=recording_result.failure_reason or "recording not saved",
                adapter_state=recording_result.state,
                recording_result=recording_result,
            )
        return StrikeCaptureResult(
            strike_index=strike_plan.index,
            expected_side=strike_plan.expected_side,
            file_name=recording_result.file_name,
            state=StrikeCaptureState.CLIP_READY,
            capture_mode=CaptureMode.FIXED_DURATION,
            capture_reason="fixed_duration_complete",
            clip_duration_ms=recording_result.duration_ms,
            rough_completion_time_ms=recording_result.stop_time_ms,
            post_roll_ms=config.post_roll_ms,
            cancelled=False,
            diagnostics=self._recording_diagnostics(recording_result),
        )

    def _cancelled_result(
        self,
        strike_plan: StrikePlan,
        recording_result: RecordingResult,
    ) -> StrikeCaptureResult:
        return StrikeCaptureResult(
            strike_index=strike_plan.index,
            expected_side=strike_plan.expected_side,
            file_name=recording_result.file_name or strike_plan.file_name,
            state=StrikeCaptureState.CANCELLED,
            capture_mode=CaptureMode.FIXED_DURATION,
            capture_reason="cancelled_by_user",
            cancelled=True,
            diagnostics=self._recording_diagnostics(recording_result),
        )

    def _failed_result(
        self,
        strike_plan: StrikePlan,
        failure_reason: str,
        adapter_state: RecordingState,
        recording_result: RecordingResult | None = None,
    ) -> StrikeCaptureResult:
        diagnostics = {"recording_state": adapter_state.value, "failure_reason": failure_reason}
        if recording_result is not None:
            diagnostics.update(self._recording_diagnostics(recording_result))
        return StrikeCaptureResult(
            strike_index=strike_plan.index,
            expected_side=strike_plan.expected_side,
            file_name=(recording_result.file_name if recording_result else strike_plan.file_name),
            state=StrikeCaptureState.FAILED,
            capture_mode=CaptureMode.FIXED_DURATION,
            capture_reason="recording_failed",
            cancelled=False,
            diagnostics=diagnostics,
        )

    def _recording_diagnostics(
        self, recording_result: RecordingResult
    ) -> dict[str, str | int | float | bool | None]:
        return {
            "recording_state": recording_result.state.value,
            "recording_saved": recording_result.saved,
            "recording_start_time_ms": recording_result.start_time_ms,
            "recording_stop_time_ms": recording_result.stop_time_ms,
            "recording_duration_ms": recording_result.duration_ms,
            "recording_cancel_reason": recording_result.cancel_reason,
            "recording_failure_reason": recording_result.failure_reason,
            **recording_result.diagnostics,
        }
