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

from karate_analyzer.references.chin_reference import calculate_chin_reference
from karate_analyzer.strike_detection import StrikeDetectorEngine
from karate_analyzer.analyzers.jodan_height_analyzer import (
    analyze_strike_event_jodan_height,
)
from karate_analyzer.references.jodan_reference import (
    calculate_jodan_reference,
    resolve_jodan_references,
)

NOSE = 0
MOUTH_LEFT = 9
MOUTH_RIGHT = 10
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
DEFAULT_SMOOTHING_WINDOW = 5
DEFAULT_GROUP_THRESHOLD = 0.90
DEFAULT_MERGE_GAP_FRAMES = 3
DEFAULT_GROUP_MIN_VISIBILITY = 0.5
DEFAULT_MIN_REGION_FRAME_COUNT = 8
DEFAULT_EXPECTED_PUNCH_COUNT = 10
EXPECTED_PUNCH_SEQUENCE_START_SIDE = "right"


def analyze_extension_json(
    input_path: Path,
    output_directory: Path,
    *,
    smoothing_window: int = DEFAULT_SMOOTHING_WINDOW,
    group_threshold: float = DEFAULT_GROUP_THRESHOLD,
    merge_gap_frames: int = DEFAULT_MERGE_GAP_FRAMES,
    min_visibility: float = DEFAULT_GROUP_MIN_VISIBILITY,
) -> dict[str, Any]:
    """Analyze MediaPipe spike landmarks and write extension debug artifacts."""

    input_path = Path(input_path)
    output_directory = Path(output_directory)
    if not input_path.exists():
        raise FileNotFoundError(MISSING_INPUT_MESSAGE)
    if not input_path.is_file():
        raise FileNotFoundError(
            f"Expected video_landmarks.json to be a file: {input_path}"
        )

    payload = json.loads(input_path.read_text(encoding="utf-8"))
    output_directory.mkdir(parents=True, exist_ok=True)

    extension_frames = [_analyze_frame(frame) for frame in payload.get("frames", [])]
    _add_smoothed_extension_ratios(extension_frames, smoothing_window)

    left_peaks = _find_candidate_peaks(extension_frames, "left")
    right_peaks = _find_candidate_peaks(extension_frames, "right")
    left_grouped_peaks = _find_grouped_peaks(
        extension_frames,
        "left",
        threshold=group_threshold,
        min_visibility=min_visibility,
        merge_gap_frames=merge_gap_frames,
    )
    right_grouped_peaks = _find_grouped_peaks(
        extension_frames,
        "right",
        threshold=group_threshold,
        min_visibility=min_visibility,
        merge_gap_frames=merge_gap_frames,
    )

    extension_payload = {"frames": extension_frames}
    candidate_peak_payload = {
        "sides": [
            {"side": "left", "candidate_peaks": left_peaks},
            {"side": "right", "candidate_peaks": right_peaks},
        ]
    }
    grouped_peak_payload = {
        "sides": [
            {"side": "left", "grouped_peaks": left_grouped_peaks},
            {"side": "right", "grouped_peaks": right_grouped_peaks},
        ]
    }
    punch_event_payload = _extract_punch_event_candidates(
        left_grouped_peaks,
        right_grouped_peaks,
        expected_count=DEFAULT_EXPECTED_PUNCH_COUNT,
        expected_start_side=EXPECTED_PUNCH_SEQUENCE_START_SIDE,
        min_region_frame_count=DEFAULT_MIN_REGION_FRAME_COUNT,
        min_region_visibility=min_visibility,
        min_smoothed_extension_ratio=group_threshold,
    )
    punch_event_landmark_payload = _extract_punch_event_landmarks(
        payload.get("frames", []),
        punch_event_payload["punch_event_candidates"],
    )

    summary = {
        "frame_count": payload.get("frame_count", len(extension_frames)),
        "detected_frame_count": payload.get(
            "detected_frame_count",
            sum(1 for frame in extension_frames if frame["pose_detected"]),
        ),
        "left_candidate_peak_count": len(left_peaks),
        "right_candidate_peak_count": len(right_peaks),
        "grouped_left_peak_count": len(left_grouped_peaks),
        "grouped_right_peak_count": len(right_grouped_peaks),
        "punch_event_candidate_count": len(
            punch_event_payload["punch_event_candidates"]
        ),
        "expected_punch_count": DEFAULT_EXPECTED_PUNCH_COUNT,
        "output_files": [
            "extension_by_frame.json",
            "extension_by_frame.csv",
            "candidate_peak_frames.json",
            "grouped_peak_frames.json",
            "punch_event_candidates.json",
            "punch_event_landmarks.json",
        ],
        "candidate_peak_frames": candidate_peak_payload,
        "grouped_peak_frames": grouped_peak_payload,
        "punch_event_candidates": punch_event_payload,
        "punch_event_landmarks": punch_event_landmark_payload,
    }

    _write_json(output_directory / "extension_by_frame.json", extension_payload)
    _write_csv(output_directory / "extension_by_frame.csv", extension_frames)
    _write_json(output_directory / "candidate_peak_frames.json", candidate_peak_payload)
    _write_json(output_directory / "grouped_peak_frames.json", grouped_peak_payload)
    _write_json(output_directory / "punch_event_candidates.json", punch_event_payload)
    _write_json(
        output_directory / "punch_event_landmarks.json", punch_event_landmark_payload
    )

    return summary


def _extract_punch_event_candidates(
    left_grouped_peaks: list[dict[str, Any]],
    right_grouped_peaks: list[dict[str, Any]],
    *,
    expected_count: int,
    expected_start_side: str,
    min_region_frame_count: int = DEFAULT_MIN_REGION_FRAME_COUNT,
    min_region_visibility: float = DEFAULT_GROUP_MIN_VISIBILITY,
    min_smoothed_extension_ratio: float = DEFAULT_GROUP_THRESHOLD,
) -> dict[str, Any]:
    """Convert grouped extension regions into expected-side strike candidates."""

    return StrikeDetectorEngine().extract_strike_event_candidates(
        left_grouped_peaks,
        right_grouped_peaks,
        expected_count=expected_count,
        expected_start_side=expected_start_side,
        min_region_frame_count=min_region_frame_count,
        min_region_visibility=min_region_visibility,
        min_smoothed_extension_ratio=min_smoothed_extension_ratio,
    )


def _extract_punch_event_landmarks(
    raw_frames: list[dict[str, Any]], punch_event_candidates: list[dict[str, Any]]
) -> dict[str, Any]:
    """Copy peak-frame landmarks needed for later Jodan punch analysis.

    The legacy head reference is intentionally experimental. The karate-specific
    Jodan reference is calculated separately so future technique analyzers can
    consume a target reference without depending directly on raw face landmarks.
    """

    frames_by_number = {frame.get("frame_number"): frame for frame in raw_frames}
    events = []
    for candidate in punch_event_candidates:
        observed_side = candidate.get("observed_side") or candidate["expected_side"]
        peak_frame_number = candidate.get("peak_frame_number")
        strike_detector = StrikeDetectorEngine()
        impact_selection = strike_detector.select_impact_frame(
            raw_frames, candidate, observed_side
        )
        analysis_frame_number = impact_selection["analysis_frame_number"]
        frame = frames_by_number.get(analysis_frame_number, {})
        pose_landmarks = {
            landmark.get("index"): landmark for landmark in _first_pose(frame)
        }
        shoulder_index, elbow_index, wrist_index = _side_landmark_indices(observed_side)
        shoulder = _landmark_payload(pose_landmarks.get(shoulder_index))
        elbow = _landmark_payload(pose_landmarks.get(elbow_index))
        wrist = _landmark_payload(pose_landmarks.get(wrist_index))
        head_reference = _head_reference_candidate(pose_landmarks)
        chin_reference = calculate_chin_reference(_first_face(frame))
        jodan_reference = calculate_jodan_reference(
            chin_reference=chin_reference, analysis_frame_number=analysis_frame_number
        )
        impact_point, impact_point_reason = strike_detector.validated_impact_point(
            frame, wrist
        )
        impact_reason = impact_selection["impact_frame_reason"]
        if impact_point_reason is not None:
            impact_reason = f"{impact_reason}; {impact_point_reason}"

        event = {
            "event_index": candidate["event_index"],
            "expected_side": candidate["expected_side"],
            "observed_side": observed_side,
            "peak_frame_number": peak_frame_number,
            "analysis_frame_number": analysis_frame_number,
            "elbow_angle_degrees": impact_selection["elbow_angle_degrees"],
            "angle_delta_degrees": impact_selection["angle_delta_degrees"],
            "max_elbow_angle_degrees_in_region": impact_selection[
                "max_elbow_angle_degrees_in_region"
            ],
            "extension_distance": impact_selection["extension_distance"],
            "extension_delta": impact_selection["extension_delta"],
            "extension_velocity": impact_selection["extension_velocity"],
            "max_extension_distance_in_region": impact_selection[
                "max_extension_distance_in_region"
            ],
            "angle_is_near_peak": impact_selection["angle_is_near_peak"],
            "extension_is_near_peak": impact_selection["extension_is_near_peak"],
            "angle_is_plateauing": impact_selection["angle_is_plateauing"],
            "extension_is_plateauing": impact_selection["extension_is_plateauing"],
            "angle_is_turning_point": impact_selection["angle_is_turning_point"],
            "extension_is_turning_point": impact_selection[
                "extension_is_turning_point"
            ],
            "peak_alignment_window_frames": impact_selection[
                "peak_alignment_window_frames"
            ],
            "impact_frame_selection_strategy": impact_selection[
                "impact_frame_selection_strategy"
            ],
            "impact_frame_confidence": impact_selection["impact_frame_confidence"],
            "impact_frame_reason": impact_reason,
            "strike_region_start_frame": impact_selection["strike_region_start_frame"],
            "strike_region_end_frame": impact_selection["strike_region_end_frame"],
            "timestamp_seconds": frame.get(
                "timestamp_seconds", candidate.get("timestamp_seconds")
            ),
            "shoulder": shoulder,
            "elbow": elbow,
            "wrist": wrist,
            "impact_point": impact_point,
            "head_reference_candidate": head_reference,
            "chin_reference": chin_reference,
            "jodan_reference": jodan_reference,
            "visibility": {
                "shoulder": _visibility(shoulder),
                "elbow": _visibility(elbow),
                "wrist": _visibility(wrist),
                "impact_point": _visibility(impact_point),
                "head_reference_candidate": (
                    None if head_reference is None else head_reference["visibility"]
                ),
                "chin_reference": _visibility(chin_reference),
                "jodan_reference": (
                    None if jodan_reference is None else jodan_reference["visibility"]
                ),
                "minimum_required_landmark_visibility": _min_visibility(
                    shoulder, elbow, wrist, head_reference
                ),
            },
        }
        events.append(event)

    events = resolve_jodan_references(raw_frames, events)
    for event in events:
        event["visibility"]["chin_reference"] = _visibility(event.get("chin_reference"))
        event["visibility"]["jodan_reference"] = (
            None
            if event.get("jodan_reference") is None
            else event["jodan_reference"]["visibility"]
        )
        event["analysis"] = {"jodan_height": analyze_strike_event_jodan_height(event)}

    return {
        "head_reference_candidate": {
            "status": "experimental",
            "strategy": "nose_then_mouth_midpoint",
        },
        "chin_reference": {
            "status": "optional",
            "strategy": "face_mesh_chin_with_lower_jaw_fallbacks",
        },
        "jodan_reference": {
            "status": "chin_first_temporal",
            "strategy": "same_frame_chin_then_head_cluster_projected_chin",
            "valid_sources": [
                "same_frame_chin",
                "backward_projected_chin",
                "previous_jodan_projected",
                "future_jodan_projected",
                "unknown",
            ],
        },
        "punch_event_landmarks": events,
    }


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


def _frame_hands(frame: dict[str, Any]) -> list[dict[str, Any]]:
    hands = frame.get("hands") or frame.get("hand_landmarks") or []
    if not hands:
        return []
    normalized = []
    for hand in hands:
        if isinstance(hand, dict):
            normalized.append(hand)
        else:
            normalized.append({"landmarks": hand})
    return normalized


def _first_pose(frame: dict[str, Any]) -> list[dict[str, Any]]:
    poses = frame.get("poses") or []
    if not poses:
        return []
    return poses[0]


def _first_face(frame: dict[str, Any]) -> list[dict[str, Any]] | dict[Any, Any] | None:
    faces = frame.get("faces") or frame.get("face_landmarks") or []
    if not faces:
        return None
    first = faces[0]
    if isinstance(first, dict):
        return first.get("landmarks", first)
    return first


def _analyze_side(
    landmarks: dict[Any, dict[str, Any]],
    shoulder_index: int,
    elbow_index: int,
    wrist_index: int,
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


def _side_landmark_indices(side: str) -> tuple[int, int, int]:
    if side == "left":
        return LEFT_SHOULDER, LEFT_ELBOW, LEFT_WRIST
    if side == "right":
        return RIGHT_SHOULDER, RIGHT_ELBOW, RIGHT_WRIST
    raise ValueError("side must be 'left' or 'right'")


def _head_reference_candidate(
    landmarks: dict[Any, dict[str, Any]],
) -> dict[str, float | str] | None:
    nose = _landmark_payload(landmarks.get(NOSE))
    if nose is not None:
        return {"source": "nose", **nose}

    mouth_left = _landmark_payload(landmarks.get(MOUTH_LEFT))
    mouth_right = _landmark_payload(landmarks.get(MOUTH_RIGHT))
    if mouth_left is None or mouth_right is None:
        return None

    return {
        "source": "mouth_midpoint",
        "x": (mouth_left["x"] + mouth_right["x"]) / 2,
        "y": (mouth_left["y"] + mouth_right["y"]) / 2,
        "visibility": min(mouth_left["visibility"], mouth_right["visibility"]),
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


def _visibility(landmark: dict[str, float] | None) -> float | None:
    return None if landmark is None else landmark["visibility"]


def _distance_2d(
    a: dict[str, float] | None, b: dict[str, float] | None
) -> float | None:
    if a is None or b is None:
        return None
    return math.hypot(a["x"] - b["x"], a["y"] - b["y"])


def _min_visibility(*landmarks: dict[str, float] | None) -> float | None:
    if any(landmark is None for landmark in landmarks):
        return None
    return min(landmark["visibility"] for landmark in landmarks if landmark is not None)


def _add_smoothed_extension_ratios(frames: list[dict[str, Any]], window: int) -> None:
    """Add centered moving-average extension ratios to each side payload."""

    if window < 1:
        raise ValueError("smoothing_window must be at least 1")

    radius_before = (window - 1) // 2
    radius_after = window // 2
    for side in ("left", "right"):
        ratios = [frame[side]["extension_ratio"] for frame in frames]
        for index, frame in enumerate(frames):
            start = max(0, index - radius_before)
            end = min(len(frames), index + radius_after + 1)
            values = [ratio for ratio in ratios[start:end] if ratio is not None]
            frame[side]["smoothed_extension_ratio"] = (
                sum(values) / len(values) if values else None
            )


def _find_grouped_peaks(
    frames: list[dict[str, Any]],
    side: str,
    *,
    threshold: float,
    min_visibility: float,
    merge_gap_frames: int,
) -> list[dict[str, Any]]:
    if merge_gap_frames < 0:
        raise ValueError("merge_gap_frames must be non-negative")

    grouped_peaks: list[dict[str, Any]] = []
    region_start_index: int | None = None
    region_end_index: int | None = None
    region_candidates: list[dict[str, Any]] = []
    gap_count = 0

    def flush_region() -> None:
        nonlocal region_start_index, region_end_index, region_candidates, gap_count
        peak_candidates = [
            candidate
            for candidate in region_candidates
            if candidate[side]["extension_ratio"] is not None
        ]
        if (
            region_start_index is not None
            and region_end_index is not None
            and peak_candidates
        ):
            best_frame = min(
                peak_candidates,
                key=lambda candidate: (
                    -candidate[side]["extension_ratio"],
                    -(candidate[side]["extension"] or float("-inf")),
                    candidate["frame_number"],
                ),
            )
            side_payload = best_frame[side]
            grouped_peaks.append(
                {
                    "start_frame": frames[region_start_index]["frame_number"],
                    "end_frame": frames[region_end_index]["frame_number"],
                    "peak_frame_number": best_frame["frame_number"],
                    "timestamp_seconds": best_frame["timestamp_seconds"],
                    "extension": side_payload["extension"],
                    "extension_ratio": side_payload["extension_ratio"],
                    "smoothed_extension_ratio": side_payload[
                        "smoothed_extension_ratio"
                    ],
                    "min_visibility": side_payload["min_visibility"],
                    "region_frame_count": region_end_index - region_start_index + 1,
                }
            )
        region_start_index = None
        region_end_index = None
        region_candidates = []
        gap_count = 0

    for index, frame in enumerate(frames):
        side_payload = frame[side]
        smoothed_ratio = side_payload["smoothed_extension_ratio"]
        visibility = side_payload["min_visibility"]
        is_high_extension = (
            smoothed_ratio is not None
            and visibility is not None
            and smoothed_ratio >= threshold
            and visibility >= min_visibility
        )

        if is_high_extension:
            if region_start_index is None:
                region_start_index = index
            region_end_index = index
            region_candidates.append(frame)
            gap_count = 0
        elif region_start_index is not None:
            gap_count += 1
            if gap_count > merge_gap_frames:
                flush_region()

    flush_region()
    return grouped_peaks


def _find_candidate_peaks(
    frames: list[dict[str, Any]], side: str
) -> list[dict[str, Any]]:
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
    path.write_text(
        json.dumps(payload, indent=2, sort_keys=True) + "\n", encoding="utf-8"
    )


def _write_csv(path: Path, frames: list[dict[str, Any]]) -> None:
    fieldnames = [
        "frame_number",
        "timestamp_seconds",
        "pose_detected",
        "left_extension",
        "left_extension_ratio",
        "left_smoothed_extension_ratio",
        "left_min_visibility",
        "right_extension",
        "right_extension_ratio",
        "right_smoothed_extension_ratio",
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
                    "left_smoothed_extension_ratio": frame["left"][
                        "smoothed_extension_ratio"
                    ],
                    "left_min_visibility": frame["left"]["min_visibility"],
                    "right_extension": frame["right"]["extension"],
                    "right_extension_ratio": frame["right"]["extension_ratio"],
                    "right_smoothed_extension_ratio": frame["right"][
                        "smoothed_extension_ratio"
                    ],
                    "right_min_visibility": frame["right"]["min_visibility"],
                }
            )
