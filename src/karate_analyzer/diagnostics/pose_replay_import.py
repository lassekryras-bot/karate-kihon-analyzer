"""Import the analyzer's MediaPipe JSON export into the Kotlin replay fixture.

This module deliberately converts cached observations; it never invokes MediaPipe.
"""

from __future__ import annotations

import argparse
import json
from dataclasses import dataclass
from pathlib import Path
from typing import Any


REPLAY_SCHEMA = "pose-motion-replay-v1"
IMPORT_SUMMARY_SCHEMA = "pose-replay-import-summary-v1"
PROPOSAL_SCHEMA = "motion-review-proposals-v1"

# MediaPipe Pose's stable 33-landmark order. The old export stores numeric indices,
# while the native replay codec uses the repository's matching enum names.
LANDMARK_NAMES = (
    "NOSE", "LEFT_EYE_INNER", "LEFT_EYE", "LEFT_EYE_OUTER",
    "RIGHT_EYE_INNER", "RIGHT_EYE", "RIGHT_EYE_OUTER", "LEFT_EAR",
    "RIGHT_EAR", "MOUTH_LEFT", "MOUTH_RIGHT", "LEFT_SHOULDER",
    "RIGHT_SHOULDER", "LEFT_ELBOW", "RIGHT_ELBOW", "LEFT_WRIST",
    "RIGHT_WRIST", "LEFT_PINKY", "RIGHT_PINKY", "LEFT_INDEX",
    "RIGHT_INDEX", "LEFT_THUMB", "RIGHT_THUMB", "LEFT_HIP", "RIGHT_HIP",
    "LEFT_KNEE", "RIGHT_KNEE", "LEFT_ANKLE", "RIGHT_ANKLE", "LEFT_HEEL",
    "RIGHT_HEEL", "LEFT_FOOT_INDEX", "RIGHT_FOOT_INDEX",
)


@dataclass(frozen=True)
class ImportResult:
    fixture: dict[str, Any]
    summary: dict[str, Any]


def import_analyzer_landmarks(
    payload: dict[str, Any], *, sequence_id: str, source_path: str | None = None
) -> ImportResult:
    """Convert one analyzer ``video_landmarks.json`` payload without reordering."""

    frames = payload.get("frames")
    if not isinstance(frames, list) or not frames:
        raise ValueError("Analyzer landmark export must contain non-empty frames")

    converted: list[dict[str, Any]] = []
    warnings: set[str] = set()
    last_timestamp: int | None = None
    detected = 0
    for ordinal, frame in enumerate(frames):
        if not isinstance(frame, dict):
            raise ValueError(f"Frame {ordinal} is not an object")
        timestamp = _timestamp_ms(frame, ordinal)
        if last_timestamp is not None and timestamp <= last_timestamp:
            raise ValueError("Frame timestamps must be strictly increasing; input is not reordered")
        last_timestamp = timestamp

        normalized = _first_pose(frame.get("poses"), "poses", ordinal)
        world = _first_pose(frame.get("world_poses"), "world_poses", ordinal)
        if normalized:
            detected += 1
        landmarks = _merge_landmarks(normalized, world, ordinal, warnings)
        converted.append({"timestamp_ms": timestamp, "landmarks": landmarks})

    declared_count = payload.get("frame_count")
    if declared_count is not None and declared_count != len(frames):
        raise ValueError("frame_count does not match the exported frames")

    source = source_path or payload.get("source")
    notes = [
        "Imported from the analyzer video_landmarks JSON; MediaPipe was not rerun.",
        "LandmarkSource OBSERVED denotes cached MediaPipe output, not live provenance.",
    ]
    notes.extend(sorted(warnings))
    fixture = {
        "schema_version": REPLAY_SCHEMA,
        "sequence_id": sequence_id,
        "arm_timestamp_ms": None,
        "cue_timestamp_ms": None,
        "activity_deadline_ms": None,
        "expected_end_pose_relationship": "DIFFERENT_STABLE_POSE",
        "notes": notes,
        "labels": None,
        "frames": converted,
    }
    summary = {
        "schema_version": IMPORT_SUMMARY_SCHEMA,
        "sequence_id": sequence_id,
        "source_path": source,
        "source_kind": payload.get("kind"),
        "source_pose_backend": payload.get("pose_detector_backend"),
        "frame_count": len(converted),
        "pose_detected_frame_count": detected,
        "first_timestamp_ms": converted[0]["timestamp_ms"],
        "last_timestamp_ms": converted[-1]["timestamp_ms"],
        "warnings": sorted(warnings),
    }
    return ImportResult(fixture=fixture, summary=summary)


def proposed_review_payload(
    fixture: dict[str, Any], impact_frames: list[int]
) -> dict[str, Any]:
    """Create review context without misrepresenting automatic guesses as truth."""

    frames = fixture["frames"]
    impacts = []
    for punch_number in range(1, 11):
        frame_number = None if punch_number == 1 else impact_frames[punch_number - 2]
        timestamp = None
        if frame_number is not None:
            if frame_number < 0 or frame_number >= len(frames):
                raise ValueError(f"Impact frame {frame_number} is outside the fixture")
            timestamp = frames[frame_number]["timestamp_ms"]
        impacts.append({
            "punch_number": punch_number,
            "expected_side": "RIGHT" if punch_number % 2 else "LEFT",
            "theoretical_impact_frame": frame_number,
            "theoretical_impact_timestamp_ms": timestamp,
            "movement_start_frame": None,
            "movement_end_frame": None,
            "terminal_stable_start_frame": None,
            "review_confidence": None,
            "ambiguity_notes": ["Human video/trace review required"],
        })
    return {
        "schema_version": PROPOSAL_SCHEMA,
        "sequence_id": fixture["sequence_id"],
        "review_status": "PROPOSED",
        "labels_are_ground_truth": False,
        "punches": impacts,
    }


def _timestamp_ms(frame: dict[str, Any], ordinal: int) -> int:
    value = frame.get("timestamp_ms")
    if isinstance(value, bool) or not isinstance(value, (int, float)):
        raise ValueError(f"Frame {ordinal} has no numeric timestamp_ms")
    if int(value) != value:
        raise ValueError(f"Frame {ordinal} timestamp_ms is not an integer")
    return int(value)


def _first_pose(value: Any, name: str, frame: int) -> list[dict[str, Any]]:
    if value is None or value == []:
        return []
    if not isinstance(value, list) or not value or not isinstance(value[0], list):
        raise ValueError(f"Frame {frame} {name} must be a list of pose lists")
    if len(value) > 1:
        raise ValueError(f"Frame {frame} contains multiple poses; selection is undefined")
    return value[0]


def _merge_landmarks(
    normalized: list[dict[str, Any]],
    world: list[dict[str, Any]],
    frame: int,
    warnings: set[str],
) -> list[dict[str, Any]]:
    normalized_by_index = _by_index(normalized, frame, "normalized")
    world_by_index = _by_index(world, frame, "world")
    result = []
    for index in sorted(normalized_by_index.keys() | world_by_index.keys()):
        if index not in range(len(LANDMARK_NAMES)):
            raise ValueError(f"Frame {frame} has unsupported pose landmark index {index}")
        image = normalized_by_index.get(index)
        world_item = world_by_index.get(index)
        confidence_source = image or world_item or {}
        visibility = _confidence(confidence_source, "visibility", warnings)
        presence = _confidence(confidence_source, "presence", warnings)
        result.append({
            "id": LANDMARK_NAMES[index],
            "normalized": _point(image, frame, index, "normalized"),
            "world": _point(world_item, frame, index, "world"),
            "visibility": visibility,
            "presence": presence,
            "source": "OBSERVED",
        })
    return result


def _by_index(items: list[dict[str, Any]], frame: int, kind: str) -> dict[int, dict[str, Any]]:
    result = {}
    for ordinal, item in enumerate(items):
        if not isinstance(item, dict):
            raise ValueError(f"Frame {frame} {kind} landmark {ordinal} is not an object")
        index = item.get("index", ordinal)
        if not isinstance(index, int) or isinstance(index, bool) or index in result:
            raise ValueError(f"Frame {frame} has invalid/duplicate {kind} landmark index")
        result[index] = item
    return result


def _point(item: dict[str, Any] | None, frame: int, index: int, kind: str) -> list[float] | None:
    if item is None:
        return None
    values = [item.get(axis) for axis in "xyz"]
    if all(isinstance(value, (int, float)) and not isinstance(value, bool) for value in values):
        return [float(value) for value in values]
    if not any(axis in item for axis in "xyz"):
        return None
    raise ValueError(f"Frame {frame} landmark {index} has incomplete {kind} coordinates")


def _confidence(item: dict[str, Any], key: str, warnings: set[str]) -> float:
    value = item.get(key)
    if value is None:
        warnings.add(f"Missing {key} was mapped to 0.0 (UNKNOWN evidence), not inferred")
        return 0.0
    if isinstance(value, bool) or not isinstance(value, (int, float)):
        raise ValueError(f"Landmark {key} must be numeric")
    return float(value)


def main() -> None:
    parser = argparse.ArgumentParser(description="Convert cached analyzer landmarks to a motion replay fixture")
    parser.add_argument("source", type=Path)
    parser.add_argument("fixture", type=Path)
    parser.add_argument("--sequence-id", required=True)
    parser.add_argument("--summary", type=Path)
    parser.add_argument("--review-proposal", type=Path)
    args = parser.parse_args()
    source_payload = json.loads(args.source.read_text(encoding="utf-8"))
    result = import_analyzer_landmarks(source_payload, sequence_id=args.sequence_id, source_path=str(args.source))
    args.fixture.parent.mkdir(parents=True, exist_ok=True)
    args.fixture.write_text(json.dumps(result.fixture, indent=2, sort_keys=True) + "\n", encoding="utf-8")
    if args.summary:
        args.summary.parent.mkdir(parents=True, exist_ok=True)
        args.summary.write_text(json.dumps(result.summary, indent=2, sort_keys=True) + "\n", encoding="utf-8")
    if args.review_proposal:
        impacts = [99, 144, 198, 243, 296, 344, 396, 444, 496]
        proposal = proposed_review_payload(result.fixture, impacts)
        args.review_proposal.parent.mkdir(parents=True, exist_ok=True)
        args.review_proposal.write_text(json.dumps(proposal, indent=2, sort_keys=True) + "\n", encoding="utf-8")


if __name__ == "__main__":
    main()
