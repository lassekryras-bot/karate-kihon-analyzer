"""Debug explorer for MediaPipe wrist-extension signals.

This module reads raw landmark JSON produced by the MediaPipe spike and writes
per-frame arm extension diagnostics. It intentionally does not import MediaPipe,
OpenCV, or the karate scoring pipeline.
"""

from __future__ import annotations

import csv
import json
import math
from pathlib import Path
from typing import Any

LEFT_SHOULDER = 11
RIGHT_SHOULDER = 12
LEFT_ELBOW = 13
RIGHT_ELBOW = 14
LEFT_WRIST = 15
RIGHT_WRIST = 16

DEFAULT_INPUT_PATH = Path("output/mediapipe-debug/video_landmarks.json")
DEFAULT_OUTPUT_DIRECTORY = Path("output/mediapipe-debug")

MISSING_INPUT_MESSAGE = (
    "No video_landmarks.json found. Run the MediaPipe spike first:\n"
    "python -m karate_analyzer.main mediapipe-spike --video input/videos/<your-video>.mp4"
)

MIN_PEAK_VISIBILITY = 0.5
MIN_PEAK_EXTENSION_RATIO = 0.75


def analyze_extension_json(input_path: Path, output_directory: Path) -> dict[str, Any]:
    """Analyze MediaPipe spike landmarks and write extension debug artifacts."""

    input_path = Path(input_path)
    output_directory = Path(output_directory)
    if not input_path.exists():
        raise FileNotFoundError(MISSING_INPUT_MESSAGE)
    if not input_path.is_file():
        raise FileNotFoundError(f"Expected video_landmarks.json to be a file: {input_path}")

    payload = json.loads(input_path.read_text(encoding="utf-8"))
    output_directory.mkdir(parents=True, exist_ok=True)

    extension_frames = [_analyze_frame(frame) for frame in payload.get("frames", [])]
    left_peaks = _find_candidate_peaks(extension_frames, "left")
    right_peaks = _find_candidate_peaks(extension_frames, "right")

    extension_payload = {"frames": extension_frames}
    candidate_peak_payload = {
        "sides": [
            {"side": "left", "candidate_peaks": left_peaks},
            {"side": "right", "candidate_peaks": right_peaks},
        ]
    }
    summary = {
        "frame_count": payload.get("frame_count", len(extension_frames)),
        "detected_frame_count": payload.get(
            "detected_frame_count",
            sum(1 for frame in extension_frames if frame["pose_detected"]),
        ),
        "left_candidate_peak_count": len(left_peaks),
        "right_candidate_peak_count": len(right_peaks),
        "output_files": [
            "extension_by_frame.json",
            "extension_by_frame.csv",
            "candidate_peak_frames.json",
        ],
        "candidate_peak_frames": candidate_peak_payload,
    }

    _write_json(output_directory / "extension_by_frame.json", extension_payload)
    _write_csv(output_directory / "extension_by_frame.csv", extension_frames)
    _write_json(output_directory / "candidate_peak_frames.json", candidate_peak_payload)

    return summary


def _analyze_frame(frame: dict[str, Any]) -> dict[str, Any]:
    pose = _first_pose(frame)
    landmarks = {landmark.get("index"): landmark for landmark in pose}
    return {
        "frame_number": frame.get("frame_number"),
        "timestamp_seconds": frame.get("timestamp_seconds"),
        "pose_detected": bool(frame.get("pose_detected")),
        "left": _analyze_side(landmarks, LEFT_SHOULDER, LEFT_ELBOW, LEFT_WRIST),
        "right": _analyze_side(landmarks, RIGHT_SHOULDER, RIGHT_ELBOW, RIGHT_WRIST),
    }


def _first_pose(frame: dict[str, Any]) -> list[dict[str, Any]]:
    poses = frame.get("poses") or []
    if not poses:
        return []
    return poses[0]


def _analyze_side(
    landmarks: dict[Any, dict[str, Any]], shoulder_index: int, elbow_index: int, wrist_index: int
) -> dict[str, Any]:
    shoulder = _landmark_payload(landmarks.get(shoulder_index))
    elbow = _landmark_payload(landmarks.get(elbow_index))
    wrist = _landmark_payload(landmarks.get(wrist_index))

    extension = _distance_2d(shoulder, wrist)
    upper_arm_length = _distance_2d(shoulder, elbow)
    forearm_length = _distance_2d(elbow, wrist)
    arm_chain_length = None
    extension_ratio = None
    if upper_arm_length is not None and forearm_length is not None:
        arm_chain_length = upper_arm_length + forearm_length
        if arm_chain_length > 0 and extension is not None:
            extension_ratio = extension / arm_chain_length

    return {
        "extension": extension,
        "extension_ratio": extension_ratio,
        "upper_arm_length": upper_arm_length,
        "forearm_length": forearm_length,
        "arm_chain_length": arm_chain_length,
        "min_visibility": _min_visibility(shoulder, elbow, wrist),
        "shoulder": shoulder,
        "elbow": elbow,
        "wrist": wrist,
    }


def _landmark_payload(landmark: dict[str, Any] | None) -> dict[str, float] | None:
    if landmark is None:
        return None
    try:
        return {
            "x": float(landmark["x"]),
            "y": float(landmark["y"]),
            "visibility": float(landmark.get("visibility", 0.0)),
        }
    except KeyError:
        return None


def _distance_2d(a: dict[str, float] | None, b: dict[str, float] | None) -> float | None:
    if a is None or b is None:
        return None
    return math.hypot(a["x"] - b["x"], a["y"] - b["y"])


def _min_visibility(*landmarks: dict[str, float] | None) -> float | None:
    if any(landmark is None for landmark in landmarks):
        return None
    return min(landmark["visibility"] for landmark in landmarks if landmark is not None)


def _find_candidate_peaks(frames: list[dict[str, Any]], side: str) -> list[dict[str, Any]]:
    peaks: list[dict[str, Any]] = []
    for previous_frame, frame, next_frame in zip(frames, frames[1:], frames[2:]):
        side_payload = frame[side]
        ratio = side_payload["extension_ratio"]
        previous_ratio = previous_frame[side]["extension_ratio"]
        next_ratio = next_frame[side]["extension_ratio"]
        min_visibility = side_payload["min_visibility"]
        if (
            ratio is not None
            and previous_ratio is not None
            and next_ratio is not None
            and min_visibility is not None
            and ratio > previous_ratio
            and ratio > next_ratio
            and min_visibility >= MIN_PEAK_VISIBILITY
            and ratio >= MIN_PEAK_EXTENSION_RATIO
        ):
            peaks.append(
                {
                    "frame_number": frame["frame_number"],
                    "timestamp_seconds": frame["timestamp_seconds"],
                    "extension": side_payload["extension"],
                    "extension_ratio": ratio,
                    "min_visibility": min_visibility,
                }
            )
    return peaks


def _write_json(path: Path, payload: dict[str, Any]) -> None:
    path.write_text(json.dumps(payload, indent=2, sort_keys=True) + "\n", encoding="utf-8")


def _write_csv(path: Path, frames: list[dict[str, Any]]) -> None:
    fieldnames = [
        "frame_number",
        "timestamp_seconds",
        "pose_detected",
        "left_extension",
        "left_extension_ratio",
        "left_min_visibility",
        "right_extension",
        "right_extension_ratio",
        "right_min_visibility",
    ]
    with path.open("w", newline="", encoding="utf-8") as csv_file:
        writer = csv.DictWriter(csv_file, fieldnames=fieldnames)
        writer.writeheader()
        for frame in frames:
            writer.writerow(
                {
                    "frame_number": frame["frame_number"],
                    "timestamp_seconds": frame["timestamp_seconds"],
                    "pose_detected": frame["pose_detected"],
                    "left_extension": frame["left"]["extension"],
                    "left_extension_ratio": frame["left"]["extension_ratio"],
                    "left_min_visibility": frame["left"]["min_visibility"],
                    "right_extension": frame["right"]["extension"],
                    "right_extension_ratio": frame["right"]["extension_ratio"],
                    "right_min_visibility": frame["right"]["min_visibility"],
                }
            )
