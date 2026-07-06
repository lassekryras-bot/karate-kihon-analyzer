"""Experimental MediaPipe Pose spike.

This module is intentionally independent of the karate analysis pipeline. It reads
images or videos, records raw MediaPipe pose landmarks, and writes debug
artifacts that can later inform a proper landmark adapter.
"""

from __future__ import annotations

import json
import os
from dataclasses import dataclass
from pathlib import Path
from typing import Any, Iterable

IMAGE_EXTENSIONS = (".jpg", ".jpeg", ".png", ".bmp", ".webp")
VIDEO_EXTENSIONS = (".mp4", ".mov", ".avi", ".mkv", ".webm")
DEFAULT_IMAGE_INPUT = Path("input/images")
DEFAULT_VIDEO_INPUT = Path("input/videos")
DEFAULT_OUTPUT_DIRECTORY = Path("output/mediapipe-debug")
DEFAULT_MODEL_CANDIDATES = (
    Path("input/pose_landmarker.task"),
    Path("input/models/pose_landmarker.task"),
)


class MediaPipeSpikeError(RuntimeError):
    """Raised when the experimental MediaPipe spike cannot complete."""


@dataclass(frozen=True)
class DetectionResult:
    """Serializable pose detection data for one image or video frame."""

    timestamp_ms: int | None
    pose_landmarks: list[list[dict[str, float | int]]]
    pose_world_landmarks: list[list[dict[str, float | int]]]

    @property
    def has_pose(self) -> bool:
        return bool(self.pose_landmarks)


def analyze_image(image_path: Path, output_directory: Path) -> dict[str, Any]:
    """Run MediaPipe Pose on one image and write raw landmarks plus a debug PNG."""

    image_path = Path(image_path)
    output_directory = _ensure_output_directory(output_directory)
    _require_file(image_path, "image")

    cv2 = _import_cv2()
    image_bgr = cv2.imread(str(image_path))
    if image_bgr is None:
        raise MediaPipeSpikeError(f"Could not read image: {image_path}")

    with _create_pose_runner(running_mode="IMAGE") as runner:
        detection = runner.detect_image(image_bgr)

    payload: dict[str, Any] = {
        "source": str(image_path),
        "kind": "image",
        "pose_detected": detection.has_pose,
        "poses": detection.pose_landmarks,
        "world_poses": detection.pose_world_landmarks,
    }
    _write_json(output_directory / "image_landmarks.json", payload)

    if detection.has_pose:
        debug_image = _draw_landmarks(image_bgr, detection.pose_landmarks[0])
        cv2.imwrite(str(output_directory / "image_landmarks.png"), debug_image)
    else:
        print(f"No pose detected in image: {image_path}")

    return payload


def analyze_video(video_path: Path, output_directory: Path) -> dict[str, Any]:
    """Run MediaPipe Pose on each frame of a short video and write debug artifacts."""

    video_path = Path(video_path)
    output_directory = _ensure_output_directory(output_directory)
    _require_file(video_path, "video")

    cv2 = _import_cv2()
    capture = cv2.VideoCapture(str(video_path))
    if not capture.isOpened():
        raise MediaPipeSpikeError(f"Could not open video: {video_path}")

    frames: list[dict[str, Any]] = []
    first_debug: Any | None = None
    last_debug: Any | None = None
    frame_number = 0

    try:
        with _create_pose_runner(running_mode="VIDEO") as runner:
            while True:
                ok, frame_bgr = capture.read()
                if not ok:
                    break
                timestamp_ms = _frame_timestamp_ms(capture, frame_number)
                detection = runner.detect_video(frame_bgr, timestamp_ms)
                frame_payload = {
                    "frame_number": frame_number,
                    "timestamp_ms": timestamp_ms,
                    "timestamp_seconds": timestamp_ms / 1000,
                    "pose_detected": detection.has_pose,
                    "poses": detection.pose_landmarks,
                    "world_poses": detection.pose_world_landmarks,
                }
                frames.append(frame_payload)
                if detection.has_pose:
                    debug_image = _draw_landmarks(frame_bgr, detection.pose_landmarks[0])
                    first_debug = first_debug if first_debug is not None else debug_image
                    last_debug = debug_image
                frame_number += 1
    finally:
        capture.release()

    if not frames:
        raise MediaPipeSpikeError(f"Video contains no readable frames: {video_path}")

    payload = {
        "source": str(video_path),
        "kind": "video",
        "frame_count": len(frames),
        "detected_frame_count": sum(1 for frame in frames if frame["pose_detected"]),
        "frames": frames,
    }
    _write_json(output_directory / "video_landmarks.json", payload)

    if first_debug is not None:
        cv2.imwrite(str(output_directory / "frame0001.png"), first_debug)
        cv2.imwrite(str(output_directory / "frame_last.png"), last_debug)
    else:
        print(f"No pose detected in video: {video_path}")

    return payload


def run_default_workflow(
    image_path: Path | None = None,
    video_path: Path | None = None,
    output_directory: Path = DEFAULT_OUTPUT_DIRECTORY,
) -> None:
    """Run the spike from explicit paths or the default input directories."""

    image_path = image_path or _first_existing_file(DEFAULT_IMAGE_INPUT, IMAGE_EXTENSIONS)
    video_path = video_path or _first_existing_file(DEFAULT_VIDEO_INPUT, VIDEO_EXTENSIONS)

    if image_path is None and video_path is None:
        print(
            "No input media found. Place a test image in input/images/ or a "
            "test video in input/videos/, then run the MediaPipe spike again."
        )
        return

    if image_path is not None:
        analyze_image(image_path, output_directory)
        print(f"Wrote image MediaPipe debug output to {output_directory}")
    if video_path is not None:
        analyze_video(video_path, output_directory)
        print(f"Wrote video MediaPipe debug output to {output_directory}")


def _ensure_output_directory(output_directory: Path) -> Path:
    output_directory = Path(output_directory)
    output_directory.mkdir(parents=True, exist_ok=True)
    return output_directory


def _require_file(path: Path, description: str) -> None:
    if not path.exists():
        raise FileNotFoundError(f"Missing {description} file: {path}")
    if not path.is_file():
        raise MediaPipeSpikeError(f"Expected {description} path to be a file: {path}")


def _write_json(path: Path, payload: dict[str, Any]) -> None:
    path.write_text(json.dumps(payload, indent=2, sort_keys=True) + "\n", encoding="utf-8")


def _first_existing_file(directory: Path, extensions: Iterable[str]) -> Path | None:
    if not directory.exists():
        return None
    for candidate in sorted(directory.iterdir()):
        if candidate.is_file() and candidate.suffix.lower() in extensions:
            return candidate
    return None


def _frame_timestamp_ms(capture: Any, frame_number: int) -> int:
    cv2 = _import_cv2()
    timestamp_ms = int(capture.get(cv2.CAP_PROP_POS_MSEC))
    if timestamp_ms > 0:
        return timestamp_ms
    fps = capture.get(cv2.CAP_PROP_FPS) or 30
    return int(round(frame_number * 1000 / fps))


def _import_cv2() -> Any:
    try:
        import cv2
    except ImportError as exc:
        raise MediaPipeSpikeError("OpenCV is required for the MediaPipe spike.") from exc
    return cv2


def _create_pose_runner(running_mode: str) -> Any:
    try:
        return _TasksPoseRunner(running_mode)
    except MediaPipeSpikeError:
        try:
            return _SolutionsPoseRunner()
        except Exception as fallback_error:  # pragma: no cover - dependency dependent
            raise MediaPipeSpikeError(
                "Could not initialize MediaPipe Pose. Provide a Tasks model via "
                "MEDIAPIPE_POSE_MODEL_PATH or input/pose_landmarker.task, or ensure "
                "MediaPipe Solutions Pose is available."
            ) from fallback_error


def _landmark_to_dict(landmark: Any, index: int) -> dict[str, float | int]:
    payload: dict[str, float | int] = {"index": index}
    for attr in ("x", "y", "z", "visibility", "presence"):
        if hasattr(landmark, attr):
            value = getattr(landmark, attr)
            if value is not None:
                payload[attr] = float(value)
    return payload


def _serialize_landmark_groups(
    groups: Iterable[Iterable[Any]],
) -> list[list[dict[str, float | int]]]:
    return [
        [_landmark_to_dict(landmark, index) for index, landmark in enumerate(group)]
        for group in groups
    ]


def _draw_landmarks(image_bgr: Any, landmarks: list[dict[str, float | int]]) -> Any:
    cv2 = _import_cv2()
    image = image_bgr.copy()
    height, width = image.shape[:2]
    for landmark in landmarks:
        visibility = float(landmark.get("visibility", 1.0))
        if visibility < 0.2:
            continue
        x = int(float(landmark["x"]) * width)
        y = int(float(landmark["y"]) * height)
        cv2.circle(image, (x, y), 3, (0, 255, 0), -1)
    return image


class _TasksPoseRunner:
    def __init__(self, running_mode: str) -> None:
        model_path = _model_path()
        if model_path is None:
            raise MediaPipeSpikeError("No MediaPipe Tasks Pose model was found.")
        try:
            import mediapipe as mp
            from mediapipe.tasks import python
            from mediapipe.tasks.python import vision
        except ImportError as exc:
            raise MediaPipeSpikeError("MediaPipe Tasks Vision is not available.") from exc

        self._mp = mp
        self._vision = vision
        mode = getattr(vision.RunningMode, running_mode)
        options = vision.PoseLandmarkerOptions(
            base_options=python.BaseOptions(model_asset_path=str(model_path)),
            running_mode=mode,
            num_poses=1,
        )
        self._landmarker = vision.PoseLandmarker.create_from_options(options)

    def __enter__(self) -> "_TasksPoseRunner":
        return self

    def __exit__(self, *_args: object) -> None:
        self._landmarker.close()

    def detect_image(self, image_bgr: Any) -> DetectionResult:
        mp_image = self._mp.Image(
            image_format=self._mp.ImageFormat.SRGB, data=_bgr_to_rgb(image_bgr)
        )
        return self._serialize(self._landmarker.detect(mp_image), None)

    def detect_video(self, image_bgr: Any, timestamp_ms: int) -> DetectionResult:
        mp_image = self._mp.Image(
            image_format=self._mp.ImageFormat.SRGB, data=_bgr_to_rgb(image_bgr)
        )
        return self._serialize(
            self._landmarker.detect_for_video(mp_image, timestamp_ms), timestamp_ms
        )

    def _serialize(self, result: Any, timestamp_ms: int | None) -> DetectionResult:
        return DetectionResult(
            timestamp_ms=timestamp_ms,
            pose_landmarks=_serialize_landmark_groups(result.pose_landmarks),
            pose_world_landmarks=_serialize_landmark_groups(result.pose_world_landmarks),
        )


class _SolutionsPoseRunner:
    def __init__(self) -> None:
        try:
            import mediapipe as mp
        except ImportError as exc:
            raise MediaPipeSpikeError("MediaPipe is not available.") from exc
        self._mp = mp
        self._pose = mp.solutions.pose.Pose(static_image_mode=False, model_complexity=1)

    def __enter__(self) -> "_SolutionsPoseRunner":
        return self

    def __exit__(self, *_args: object) -> None:
        self._pose.close()

    def detect_image(self, image_bgr: Any) -> DetectionResult:
        return self._detect(image_bgr, None)

    def detect_video(self, image_bgr: Any, timestamp_ms: int) -> DetectionResult:
        return self._detect(image_bgr, timestamp_ms)

    def _detect(self, image_bgr: Any, timestamp_ms: int | None) -> DetectionResult:
        results = self._pose.process(_bgr_to_rgb(image_bgr))
        landmarks = [] if results.pose_landmarks is None else [results.pose_landmarks.landmark]
        world = [] if results.pose_world_landmarks is None else [results.pose_world_landmarks.landmark]
        return DetectionResult(
            timestamp_ms,
            _serialize_landmark_groups(landmarks),
            _serialize_landmark_groups(world),
        )


def _model_path() -> Path | None:
    configured = os.environ.get("MEDIAPIPE_POSE_MODEL_PATH")
    candidates = ([Path(configured)] if configured else []) + list(DEFAULT_MODEL_CANDIDATES)
    return next((candidate for candidate in candidates if candidate.exists()), None)


def _bgr_to_rgb(image_bgr: Any) -> Any:
    cv2 = _import_cv2()
    return cv2.cvtColor(image_bgr, cv2.COLOR_BGR2RGB)
