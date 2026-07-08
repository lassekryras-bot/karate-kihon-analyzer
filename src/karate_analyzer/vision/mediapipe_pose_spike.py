"""Experimental MediaPipe Pose spike.

This module is intentionally independent of the karate analysis pipeline. It reads
images or videos, records raw MediaPipe pose landmarks, and writes debug
artifacts that can later inform a proper landmark adapter.
"""

from __future__ import annotations

import json
import os
from dataclasses import dataclass, field
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
DEFAULT_HAND_MODEL_CANDIDATES = (
    Path("input/hand_landmarker.task"),
    Path("input/models/hand_landmarker.task"),
)
DEFAULT_FACE_MODEL_CANDIDATES = (
    Path("input/face_landmarker.task"),
    Path("input/models/face_landmarker.task"),
)
FACE_LANDMARKER_CONFIDENCE_THRESHOLD = 0.05
FACE_CROP_HEAD_INDICES = tuple(range(0, 11))
FACE_CROP_SHOULDER_INDICES = (11, 12)
FACE_CROP_MIN_IMAGE_RATIO = 0.45
FACE_CROP_MIN_PIXELS = 240
HAND_CROP_POSE_WRIST_INDICES = (15, 16)
HAND_CROP_IMAGE_RATIOS = (0.22, 0.30, 0.40)
MAX_HAND_ANCHOR_TO_POSE_WRIST_DISTANCE = 0.14


@dataclass(frozen=True)
class _CoordinateTransform:
    x_offset: float
    y_offset: float
    x_scale: float
    y_scale: float


class MediaPipeSpikeError(RuntimeError):
    """Raised when the experimental MediaPipe spike cannot complete."""


@dataclass(frozen=True)
class DetectionResult:
    """Serializable pose detection data for one image or video frame."""

    timestamp_ms: int | None
    pose_landmarks: list[list[dict[str, float | int]]]
    pose_world_landmarks: list[list[dict[str, float | int]]]
    hand_landmarks: list[dict[str, Any]] = field(default_factory=list)
    face_landmarks: list[dict[str, Any]] = field(default_factory=list)

    @property
    def has_pose(self) -> bool:
        return bool(self.pose_landmarks)

    @property
    def has_hands(self) -> bool:
        return bool(self.hand_landmarks)

    @property
    def has_face(self) -> bool:
        return bool(self.face_landmarks)


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
        "hand_detected": detection.has_hands,
        "face_detected": detection.has_face,
        "poses": detection.pose_landmarks,
        "world_poses": detection.pose_world_landmarks,
        "hands": detection.hand_landmarks,
        "faces": detection.face_landmarks,
        "pose_detector_backend": _runner_backend(runner, "pose"),
        "hand_detector_backend": _runner_backend(runner, "hand"),
        "face_detector_backend": _runner_backend(runner, "face"),
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
                    "hand_detected": detection.has_hands,
                    "face_detected": detection.has_face,
                    "poses": detection.pose_landmarks,
                    "world_poses": detection.pose_world_landmarks,
                    "hands": detection.hand_landmarks,
                    "faces": detection.face_landmarks,
                }
                frames.append(frame_payload)
                if detection.has_pose:
                    debug_image = _draw_landmarks(
                        frame_bgr, detection.pose_landmarks[0]
                    )
                    first_debug = (
                        first_debug if first_debug is not None else debug_image
                    )
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
        "hand_detected_frame_count": sum(
            1 for frame in frames if frame["hand_detected"]
        ),
        "face_detected_frame_count": sum(
            1 for frame in frames if frame["face_detected"]
        ),
        "frames": frames,
        "pose_detector_backend": _runner_backend(runner, "pose"),
        "hand_detector_backend": _runner_backend(runner, "hand"),
        "face_detector_backend": _runner_backend(runner, "face"),
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

    image_path = image_path or _first_existing_file(
        DEFAULT_IMAGE_INPUT, IMAGE_EXTENSIONS
    )
    video_path = video_path or _first_existing_file(
        DEFAULT_VIDEO_INPUT, VIDEO_EXTENSIONS
    )

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


def _runner_backend(runner: Any, detector: str) -> str:
    return str(getattr(runner, f"{detector}_detector_backend", "unknown"))


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
    path.write_text(
        json.dumps(payload, indent=2, sort_keys=True) + "\n", encoding="utf-8"
    )


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
        raise MediaPipeSpikeError(
            "OpenCV is required for the MediaPipe spike."
        ) from exc
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


def _serialize_faces(
    results: Any, coordinate_transform: _CoordinateTransform | None = None
) -> list[dict[str, Any]]:
    if results is None:
        return []
    task_face_groups = getattr(results, "face_landmarks", None)
    if task_face_groups is not None:
        groups = (
            task_face_groups
            if isinstance(task_face_groups, list)
            else [task_face_groups]
        )
        return [
            {
                "landmarks": _serialize_landmark_group(
                    face_landmarks, coordinate_transform
                )
            }
            for face_landmarks in groups
        ]
    face_groups = getattr(results, "multi_face_landmarks", None) or []
    return [
        {
            "landmarks": _serialize_landmark_group(
                face_landmarks.landmark, coordinate_transform
            )
        }
        for face_landmarks in face_groups
    ]


def _serialize_landmark_group(
    landmarks: Any, coordinate_transform: _CoordinateTransform | None = None
) -> list[dict[str, float | int]]:
    serialized = []
    for index, landmark in enumerate(landmarks):
        payload = _landmark_to_dict(landmark, index)
        if coordinate_transform is not None:
            payload = _transform_normalized_landmark(payload, coordinate_transform)
        if "visibility" not in payload:
            payload["visibility"] = float(payload.get("presence", 1.0))
        serialized.append(payload)
    return serialized


def _transform_normalized_landmark(
    landmark: dict[str, float | int], coordinate_transform: _CoordinateTransform
) -> dict[str, float | int]:
    transformed = dict(landmark)
    if "x" in transformed:
        transformed["x"] = (
            float(transformed["x"]) * coordinate_transform.x_scale
            + coordinate_transform.x_offset
        )
    if "y" in transformed:
        transformed["y"] = (
            float(transformed["y"]) * coordinate_transform.y_scale
            + coordinate_transform.y_offset
        )
    if "z" in transformed:
        transformed["z"] = float(transformed["z"]) * coordinate_transform.x_scale
    return transformed


def _serialize_hands(results: Any) -> list[dict[str, Any]]:
    hand_groups = getattr(results, "multi_hand_landmarks", None) or []
    handedness_groups = getattr(results, "multi_handedness", None) or []
    hands = []
    for index, hand_landmarks in enumerate(hand_groups):
        hand: dict[str, Any] = {
            "landmarks": _serialize_landmark_groups([hand_landmarks.landmark])[0]
        }
        if index < len(handedness_groups):
            classifications = getattr(handedness_groups[index], "classification", [])
            if classifications:
                hand["handedness"] = {
                    "label": classifications[0].label,
                    "score": float(classifications[0].score),
                }
        hands.append(hand)
    return hands


def _serialize_task_hands(
    result: Any, coordinate_transform: _CoordinateTransform | None = None
) -> list[dict[str, Any]]:
    if result is None:
        return []
    hand_groups = getattr(result, "hand_landmarks", None) or []
    handedness_groups = getattr(result, "handedness", None) or []
    hands = []
    for index, hand_landmarks in enumerate(hand_groups):
        hand: dict[str, Any] = {
            "landmarks": _serialize_landmark_group(
                hand_landmarks, coordinate_transform
            )
        }
        if index < len(handedness_groups) and handedness_groups[index]:
            category = handedness_groups[index][0]
            label = getattr(category, "category_name", None) or getattr(
                category, "display_name", None
            )
            score = getattr(category, "score", None)
            if label is not None or score is not None:
                hand["handedness"] = {}
                if label is not None:
                    hand["handedness"]["label"] = label
                if score is not None:
                    hand["handedness"]["score"] = float(score)
        hands.append(hand)
    return hands


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


def _face_crop_bounds(
    pose_landmark_groups: Any, image_width: int, image_height: int
) -> tuple[int, int, int, int] | None:
    if not pose_landmark_groups:
        return None

    pose_landmarks = pose_landmark_groups[0]
    head_points = _pose_points(
        pose_landmarks, FACE_CROP_HEAD_INDICES, image_width, image_height
    )
    if not head_points:
        return None

    shoulder_points = _pose_points(
        pose_landmarks, FACE_CROP_SHOULDER_INDICES, image_width, image_height
    )
    x_values = [point[0] for point in head_points]
    y_values = [point[1] for point in head_points]
    center_x = (min(x_values) + max(x_values)) / 2
    center_y = (min(y_values) + max(y_values)) / 2

    head_width = max(x_values) - min(x_values)
    head_height = max(y_values) - min(y_values)
    shoulder_width = (
        abs(shoulder_points[0][0] - shoulder_points[1][0])
        if len(shoulder_points) >= 2
        else 0
    )
    crop_size = max(
        head_width * 4.0,
        head_height * 4.0,
        shoulder_width * 1.1,
        min(image_width, image_height) * FACE_CROP_MIN_IMAGE_RATIO,
        FACE_CROP_MIN_PIXELS,
    )
    return _clamped_square_bounds(
        center_x, center_y, crop_size, image_width, image_height
    )


def _hand_crop_bounds(
    pose_landmark_groups: Any,
    image_width: int,
    image_height: int,
    size_ratio: float,
) -> list[tuple[int, int, int, int]]:
    if not pose_landmark_groups:
        return []
    pose_landmarks = pose_landmark_groups[0]
    crop_size = min(image_width, image_height) * size_ratio
    bounds = []
    for index in HAND_CROP_POSE_WRIST_INDICES:
        if index >= len(pose_landmarks):
            continue
        landmark = pose_landmarks[index]
        if _landmark_confidence(landmark) < 0.2:
            continue
        bounds.append(
            _clamped_square_bounds(
                float(landmark.x) * image_width,
                float(landmark.y) * image_height,
                crop_size,
                image_width,
                image_height,
            )
        )
    return bounds


def _hands_are_near_pose_wrists(hands: list[dict[str, Any]], pose_landmarks: Any) -> bool:
    wrist_points = _pose_wrist_points(pose_landmarks)
    if not wrist_points:
        return True
    for hand in hands:
        for hand_point in _hand_anchor_points(hand):
            if any(
                _normalized_distance(hand_point, wrist_point)
                <= MAX_HAND_ANCHOR_TO_POSE_WRIST_DISTANCE
                for wrist_point in wrist_points
            ):
                return True
    return False


def _pose_wrist_points(pose_landmark_groups: Any) -> list[dict[str, float]]:
    if not pose_landmark_groups:
        return []
    pose_landmarks = pose_landmark_groups[0]
    points = []
    for index in HAND_CROP_POSE_WRIST_INDICES:
        if index >= len(pose_landmarks):
            continue
        landmark = pose_landmarks[index]
        if _landmark_confidence(landmark) < 0.2:
            continue
        points.append({"x": float(landmark.x), "y": float(landmark.y)})
    return points


def _hand_anchor_points(hand: dict[str, Any]) -> list[dict[str, float]]:
    landmarks = {landmark.get("index"): landmark for landmark in hand.get("landmarks", [])}
    anchors = []
    for index in (0, 5, 9):
        landmark = landmarks.get(index)
        if not landmark:
            continue
        try:
            anchors.append({"x": float(landmark["x"]), "y": float(landmark["y"])})
        except (KeyError, TypeError, ValueError):
            continue
    return anchors


def _normalized_distance(a: dict[str, float], b: dict[str, float]) -> float:
    return ((a["x"] - b["x"]) ** 2 + (a["y"] - b["y"]) ** 2) ** 0.5


def _pose_points(
    landmarks: Any, indices: Iterable[int], image_width: int, image_height: int
) -> list[tuple[float, float]]:
    points = []
    for index in indices:
        if index >= len(landmarks):
            continue
        landmark = landmarks[index]
        if _landmark_confidence(landmark) < 0.2:
            continue
        points.append(
            (float(landmark.x) * image_width, float(landmark.y) * image_height)
        )
    return points


def _landmark_confidence(landmark: Any) -> float:
    for attr in ("visibility", "presence"):
        if hasattr(landmark, attr):
            value = getattr(landmark, attr)
            if value is not None:
                return float(value)
    return 1.0


def _clamped_square_bounds(
    center_x: float,
    center_y: float,
    crop_size: float,
    image_width: int,
    image_height: int,
) -> tuple[int, int, int, int]:
    crop_size = min(crop_size, image_width, image_height)
    x_min = int(round(center_x - crop_size / 2))
    y_min = int(round(center_y - crop_size / 2))
    x_min = max(0, min(x_min, image_width - int(round(crop_size))))
    y_min = max(0, min(y_min, image_height - int(round(crop_size))))
    x_max = min(image_width, x_min + int(round(crop_size)))
    y_max = min(image_height, y_min + int(round(crop_size)))
    return x_min, y_min, x_max, y_max


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
            raise MediaPipeSpikeError(
                "MediaPipe Tasks Vision is not available."
            ) from exc

        self._mp = mp
        self._vision = vision
        mode = getattr(vision.RunningMode, running_mode)
        options = vision.PoseLandmarkerOptions(
            base_options=python.BaseOptions(model_asset_path=str(model_path)),
            running_mode=mode,
            num_poses=1,
        )
        self._landmarker = vision.PoseLandmarker.create_from_options(options)
        self._hand_landmarker = self._create_hand_landmarker(python, vision, mode)
        self._hand_crop_landmarker = self._create_hand_landmarker(
            python, vision, getattr(vision.RunningMode, "IMAGE")
        )
        self._face_landmarker = self._create_face_landmarker(python, vision, mode)
        self._face_mesh = None
        if self._face_landmarker is None:
            self._face_mesh = self._create_optional_face_mesh(mp)
        self.pose_detector_backend = "tasks_pose_landmarker"
        self.hand_detector_backend = (
            "tasks_hand_landmarker" if self._hand_landmarker is not None else "none"
        )
        self.face_detector_backend = (
            "tasks_face_landmarker"
            if self._face_landmarker is not None
            else "solutions_face_mesh" if self._face_mesh is not None else "none"
        )

    def __enter__(self) -> "_TasksPoseRunner":
        return self

    def __exit__(self, *_args: object) -> None:
        self._landmarker.close()
        if self._hand_landmarker is not None:
            self._hand_landmarker.close()
        if self._hand_crop_landmarker is not None:
            self._hand_crop_landmarker.close()
        if self._face_landmarker is not None:
            self._face_landmarker.close()
        if self._face_mesh is not None:
            self._face_mesh.close()

    def detect_image(self, image_bgr: Any) -> DetectionResult:
        rgb = _bgr_to_rgb(image_bgr)
        mp_image = self._mp.Image(image_format=self._mp.ImageFormat.SRGB, data=rgb)
        pose_result = self._landmarker.detect(mp_image)
        return self._serialize(
            pose_result,
            self._detect_hands(mp_image, rgb, pose_result.pose_landmarks),
            self._detect_face(mp_image, rgb, pose_result.pose_landmarks),
            None,
        )

    def detect_video(self, image_bgr: Any, timestamp_ms: int) -> DetectionResult:
        rgb = _bgr_to_rgb(image_bgr)
        mp_image = self._mp.Image(image_format=self._mp.ImageFormat.SRGB, data=rgb)
        pose_result = self._landmarker.detect_for_video(mp_image, timestamp_ms)
        return self._serialize(
            pose_result,
            self._detect_hands_for_video(
                mp_image, rgb, pose_result.pose_landmarks, timestamp_ms
            ),
            self._detect_face_for_video(
                mp_image, rgb, pose_result.pose_landmarks, timestamp_ms
            ),
            timestamp_ms,
        )

    def _serialize(
        self,
        result: Any,
        hand_results: list[dict[str, Any]],
        face_results: list[dict[str, Any]],
        timestamp_ms: int | None,
    ) -> DetectionResult:
        return DetectionResult(
            timestamp_ms=timestamp_ms,
            pose_landmarks=_serialize_landmark_groups(result.pose_landmarks),
            pose_world_landmarks=_serialize_landmark_groups(
                result.pose_world_landmarks
            ),
            hand_landmarks=hand_results,
            face_landmarks=face_results,
        )

    def _create_hand_landmarker(
        self, python: Any, vision: Any, mode: Any
    ) -> Any | None:
        hand_model_path = _hand_model_path()
        if hand_model_path is None or not hasattr(vision, "HandLandmarker"):
            return None
        options = vision.HandLandmarkerOptions(
            base_options=python.BaseOptions(model_asset_path=str(hand_model_path)),
            running_mode=mode,
            num_hands=2,
        )
        return vision.HandLandmarker.create_from_options(options)

    def _create_face_landmarker(
        self, python: Any, vision: Any, mode: Any
    ) -> Any | None:
        face_model_path = _face_model_path()
        if face_model_path is None or not hasattr(vision, "FaceLandmarker"):
            return None
        options = vision.FaceLandmarkerOptions(
            base_options=python.BaseOptions(model_asset_path=str(face_model_path)),
            running_mode=mode,
            num_faces=1,
            min_face_detection_confidence=FACE_LANDMARKER_CONFIDENCE_THRESHOLD,
            min_face_presence_confidence=FACE_LANDMARKER_CONFIDENCE_THRESHOLD,
            min_tracking_confidence=FACE_LANDMARKER_CONFIDENCE_THRESHOLD,
            output_face_blendshapes=False,
            output_facial_transformation_matrixes=False,
        )
        return vision.FaceLandmarker.create_from_options(options)

    def _create_optional_face_mesh(self, mp: Any) -> Any | None:
        solutions = getattr(mp, "solutions", None)
        face_mesh = getattr(solutions, "face_mesh", None) if solutions else None
        if face_mesh is None:
            return None
        return face_mesh.FaceMesh(
            static_image_mode=False, max_num_faces=1, refine_landmarks=False
        )

    def _detect_hands(
        self, mp_image: Any, rgb: Any, pose_landmarks: Any
    ) -> list[dict[str, Any]]:
        if self._hand_landmarker is None:
            return []
        hands = _serialize_task_hands(self._hand_landmarker.detect(mp_image))
        if hands and _hands_are_near_pose_wrists(hands, pose_landmarks):
            return hands
        return self._detect_hands_from_pose_wrist_crops(rgb, pose_landmarks, None)

    def _detect_hands_for_video(
        self, mp_image: Any, rgb: Any, pose_landmarks: Any, timestamp_ms: int
    ) -> list[dict[str, Any]]:
        if self._hand_landmarker is None:
            return []
        hands = _serialize_task_hands(
            self._hand_landmarker.detect_for_video(mp_image, timestamp_ms)
        )
        if hands and _hands_are_near_pose_wrists(hands, pose_landmarks):
            return hands
        return self._detect_hands_from_pose_wrist_crops(
            rgb, pose_landmarks, timestamp_ms
        )

    def _detect_hands_from_pose_wrist_crops(
        self, rgb: Any, pose_landmarks: Any, timestamp_ms: int | None
    ) -> list[dict[str, Any]]:
        if self._hand_crop_landmarker is None or not hasattr(rgb, "shape"):
            return []
        height, width = rgb.shape[:2]
        for size_ratio in HAND_CROP_IMAGE_RATIOS:
            for bounds in _hand_crop_bounds(
                pose_landmarks, width, height, size_ratio
            ):
                x_min, y_min, x_max, y_max = bounds
                crop = rgb[y_min:y_max, x_min:x_max].copy()
                transform = _CoordinateTransform(
                    x_offset=x_min / width,
                    y_offset=y_min / height,
                    x_scale=(x_max - x_min) / width,
                    y_scale=(y_max - y_min) / height,
                )
                mp_image = self._mp.Image(
                    image_format=self._mp.ImageFormat.SRGB, data=crop
                )
                result = self._hand_crop_landmarker.detect(mp_image)
                hands = _serialize_task_hands(result, transform)
                if hands:
                    for hand in hands:
                        hand["detection_source"] = "pose_wrist_crop"
                        hand["crop_bounds"] = {
                            "x_min": x_min,
                            "y_min": y_min,
                            "x_max": x_max,
                            "y_max": y_max,
                        }
                        hand["crop_size_ratio"] = size_ratio
                    return hands
        return []

    def _detect_face(
        self, mp_image: Any, rgb: Any, pose_landmarks: Any
    ) -> list[dict[str, Any]]:
        if self._face_landmarker is not None:
            face_image, transform = self._face_image_from_pose(rgb, pose_landmarks)
            return _serialize_faces(self._face_landmarker.detect(face_image), transform)
        if self._face_mesh is not None:
            return _serialize_faces(self._face_mesh.process(rgb))
        return []

    def _detect_face_for_video(
        self, mp_image: Any, rgb: Any, pose_landmarks: Any, timestamp_ms: int
    ) -> list[dict[str, Any]]:
        if self._face_landmarker is not None:
            face_image, transform = self._face_image_from_pose(
                rgb, pose_landmarks, fallback_image=mp_image
            )
            return _serialize_faces(
                self._face_landmarker.detect_for_video(face_image, timestamp_ms),
                transform,
            )
        if self._face_mesh is not None:
            return _serialize_faces(self._face_mesh.process(rgb))
        return []

    def _face_image_from_pose(
        self, rgb: Any, pose_landmarks: Any, fallback_image: Any | None = None
    ) -> tuple[Any, _CoordinateTransform | None]:
        if not hasattr(rgb, "shape"):
            mp_image = fallback_image or self._mp.Image(
                image_format=self._mp.ImageFormat.SRGB, data=rgb
            )
            return mp_image, None

        bounds = _face_crop_bounds(pose_landmarks, rgb.shape[1], rgb.shape[0])
        if bounds is None:
            mp_image = fallback_image or self._mp.Image(
                image_format=self._mp.ImageFormat.SRGB, data=rgb
            )
            return mp_image, None

        x_min, y_min, x_max, y_max = bounds
        crop = rgb[y_min:y_max, x_min:x_max].copy()
        mp_image = self._mp.Image(image_format=self._mp.ImageFormat.SRGB, data=crop)
        height, width = rgb.shape[:2]
        return mp_image, _CoordinateTransform(
            x_offset=x_min / width,
            y_offset=y_min / height,
            x_scale=(x_max - x_min) / width,
            y_scale=(y_max - y_min) / height,
        )


class _SolutionsPoseRunner:
    def __init__(self) -> None:
        try:
            import mediapipe as mp
        except ImportError as exc:
            raise MediaPipeSpikeError("MediaPipe is not available.") from exc
        self._mp = mp
        solutions = getattr(mp, "solutions", None)
        if solutions is None:
            raise MediaPipeSpikeError("MediaPipe Solutions is not available.")
        self._pose = solutions.pose.Pose(static_image_mode=False, model_complexity=1)
        self._hands = solutions.hands.Hands(static_image_mode=False, max_num_hands=2)
        face_mesh = getattr(solutions, "face_mesh", None)
        self._face_mesh = (
            None
            if face_mesh is None
            else face_mesh.FaceMesh(
                static_image_mode=False, max_num_faces=1, refine_landmarks=False
            )
        )
        self.pose_detector_backend = "solutions_pose"
        self.hand_detector_backend = "solutions_hands"
        self.face_detector_backend = (
            "solutions_face_mesh" if self._face_mesh is not None else "none"
        )

    def __enter__(self) -> "_SolutionsPoseRunner":
        return self

    def __exit__(self, *_args: object) -> None:
        self._pose.close()
        self._hands.close()
        if self._face_mesh is not None:
            self._face_mesh.close()

    def detect_image(self, image_bgr: Any) -> DetectionResult:
        return self._detect(image_bgr, None)

    def detect_video(self, image_bgr: Any, timestamp_ms: int) -> DetectionResult:
        return self._detect(image_bgr, timestamp_ms)

    def _detect(self, image_bgr: Any, timestamp_ms: int | None) -> DetectionResult:
        rgb = _bgr_to_rgb(image_bgr)
        results = self._pose.process(rgb)
        hand_results = self._hands.process(rgb)
        face_results = None if self._face_mesh is None else self._face_mesh.process(rgb)
        landmarks = (
            [] if results.pose_landmarks is None else [results.pose_landmarks.landmark]
        )
        world = (
            []
            if results.pose_world_landmarks is None
            else [results.pose_world_landmarks.landmark]
        )
        hands = _serialize_hands(hand_results)
        faces = _serialize_faces(face_results)
        return DetectionResult(
            timestamp_ms,
            _serialize_landmark_groups(landmarks),
            _serialize_landmark_groups(world),
            hands,
            faces,
        )


def _model_path() -> Path | None:
    configured = os.environ.get("MEDIAPIPE_POSE_MODEL_PATH")
    candidates = ([Path(configured)] if configured else []) + list(
        DEFAULT_MODEL_CANDIDATES
    )
    return next((candidate for candidate in candidates if candidate.exists()), None)


def _hand_model_path() -> Path | None:
    return next(
        (
            candidate
            for candidate in DEFAULT_HAND_MODEL_CANDIDATES
            if candidate.exists()
        ),
        None,
    )


def _face_model_path() -> Path | None:
    configured = os.environ.get("MEDIAPIPE_FACE_MODEL_PATH")
    candidates = ([Path(configured)] if configured else []) + list(
        DEFAULT_FACE_MODEL_CANDIDATES
    )
    return next((candidate for candidate in candidates if candidate.exists()), None)


def _bgr_to_rgb(image_bgr: Any) -> Any:
    cv2 = _import_cv2()
    return cv2.cvtColor(image_bgr, cv2.COLOR_BGR2RGB)
