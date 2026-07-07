"""Reusable still-frame extraction utilities for video files."""

from __future__ import annotations

from dataclasses import dataclass
from pathlib import Path


@dataclass(frozen=True)
class ExtractedFrameMetadata:
    """Metadata describing a frame extracted from a video."""

    video_path: Path
    requested_frame_number: int
    actual_frame_number: int | None
    output_path: Path
    frame_width: int
    frame_height: int
    fps: float | None
    timestamp_seconds: float | None


class FrameExtractionError(RuntimeError):
    """Raised when a video frame cannot be extracted."""


def default_frame_output_path(frame_number: int) -> Path:
    """Return the deterministic default output path for ``frame_number``."""

    _validate_frame_number(frame_number)
    return Path("output/frames") / f"frame-{frame_number:06d}.png"


def extract_frame(
    video_path: str | Path,
    frame_number: int,
    output_path: str | Path,
) -> ExtractedFrameMetadata:
    """Extract a still image from ``video_path`` at ``frame_number``.

    The function only performs video I/O. It does not run pose detection,
    karate analysis, scoring, or overlay rendering.
    """

    video = Path(video_path)
    output = Path(output_path)
    _validate_frame_number(frame_number)

    if not video.exists():
        raise FileNotFoundError(f"Video file does not exist: {video}")
    if not video.is_file():
        raise FrameExtractionError(f"Video path is not a file: {video}")

    cv2 = _cv2()
    capture = cv2.VideoCapture(str(video))
    try:
        if not capture.isOpened():
            raise FrameExtractionError(f"Could not open video for reading: {video}")

        frame_count = int(capture.get(cv2.CAP_PROP_FRAME_COUNT))
        if frame_count > 0 and frame_number >= frame_count:
            raise FrameExtractionError(
                f"Requested frame {frame_number} is beyond video length "
                f"({frame_count} frames): {video}"
            )

        if not capture.set(cv2.CAP_PROP_POS_FRAMES, frame_number):
            raise FrameExtractionError(f"Could not seek to frame {frame_number}: {video}")

        success, frame = capture.read()
        if not success or frame is None:
            raise FrameExtractionError(f"Could not read frame {frame_number}: {video}")

        output.parent.mkdir(parents=True, exist_ok=True)
        if not cv2.imwrite(str(output), frame):
            raise FrameExtractionError(f"Could not write extracted frame to: {output}")

        height, width = frame.shape[:2]
        fps = _positive_float_or_none(capture.get(cv2.CAP_PROP_FPS))
        actual_frame_number = _actual_frame_number(capture, cv2)
        timestamp_seconds = frame_number / fps if fps else None

        return ExtractedFrameMetadata(
            video_path=video,
            requested_frame_number=frame_number,
            actual_frame_number=actual_frame_number,
            output_path=output,
            frame_width=width,
            frame_height=height,
            fps=fps,
            timestamp_seconds=timestamp_seconds,
        )
    finally:
        capture.release()


def _cv2():
    try:
        import cv2
    except ImportError as exc:
        raise FrameExtractionError(
            "OpenCV is required to extract video frames but could not be imported."
        ) from exc
    return cv2


def _validate_frame_number(frame_number: int) -> None:
    if frame_number < 0:
        raise ValueError(f"Frame number must be non-negative: {frame_number}")


def _positive_float_or_none(value: float) -> float | None:
    return value if value > 0 else None


def _actual_frame_number(capture, cv2) -> int | None:
    position_after_read = int(capture.get(cv2.CAP_PROP_POS_FRAMES))
    if position_after_read <= 0:
        return None
    return position_after_read - 1
