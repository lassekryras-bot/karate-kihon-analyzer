from __future__ import annotations

import json
import subprocess
from pathlib import Path

import pytest

from karate_analyzer.diagnostics.pose_replay_import import (
    import_analyzer_landmarks,
    proposed_review_payload,
)


def _landmark(index: int, x: float, *, presence: float = 0.8) -> dict[str, float | int]:
    return {
        "index": index,
        "x": x,
        "y": 0.25,
        "z": -0.1,
        "visibility": 0.9,
        "presence": presence,
    }


def _payload(frame_count: int = 2) -> dict[str, object]:
    frames = []
    for number in range(frame_count):
        frames.append(
            {
                "frame_number": number,
                "timestamp_ms": number * 17,
                "timestamp_seconds": number * 0.017,
                "pose_detected": True,
                "poses": [[_landmark(0, 0.5 + number / 100)]],
                "world_poses": [[_landmark(0, 0.05 + number / 100)]],
            }
        )
    return {
        "source": "input/videos/kihon-test.mp4",
        "kind": "video",
        "frame_count": frame_count,
        "detected_frame_count": frame_count,
        "pose_detector_backend": "mediapipe_tasks",
        "frames": frames,
    }


def test_import_preserves_timestamp_coordinates_confidence_and_source() -> None:
    result = import_analyzer_landmarks(
        _payload(), sequence_id="real-kihon-10-punch-1000002073-v1"
    )

    assert [frame["timestamp_ms"] for frame in result.fixture["frames"]] == [0, 17]
    landmark = result.fixture["frames"][1]["landmarks"][0]
    assert landmark == {
        "id": "NOSE",
        "normalized": [0.51, 0.25, -0.1],
        "world": [0.060000000000000005, 0.25, -0.1],
        "visibility": 0.9,
        "presence": 0.8,
        "source": "OBSERVED",
    }
    assert result.summary["source_path"] == "input/videos/kihon-test.mp4"


def test_import_preserves_701_frame_order_without_resampling() -> None:
    result = import_analyzer_landmarks(_payload(701), sequence_id="701-frame-test")

    assert len(result.fixture["frames"]) == 701
    assert result.fixture["frames"][496]["timestamp_ms"] == 8432
    assert result.summary["pose_detected_frame_count"] == 701


def test_missing_presence_becomes_unknown_instead_of_inferred_stillness() -> None:
    payload = _payload(1)
    del payload["frames"][0]["poses"][0][0]["presence"]  # type: ignore[index]
    del payload["frames"][0]["world_poses"][0][0]["presence"]  # type: ignore[index]

    result = import_analyzer_landmarks(payload, sequence_id="missing-presence")

    assert result.fixture["frames"][0]["landmarks"][0]["presence"] == 0.0
    assert result.summary["warnings"] == [
        "Missing presence was mapped to 0.0 (UNKNOWN evidence), not inferred"
    ]


def test_import_rejects_non_increasing_timestamps_without_sorting() -> None:
    payload = _payload(2)
    payload["frames"][1]["timestamp_ms"] = 0  # type: ignore[index]

    with pytest.raises(ValueError, match="strictly increasing"):
        import_analyzer_landmarks(payload, sequence_id="bad-time")


def test_proposed_review_context_round_trips_without_claiming_ground_truth() -> None:
    fixture = import_analyzer_landmarks(_payload(600), sequence_id="review").fixture
    proposal = proposed_review_payload(
        fixture, [99, 144, 198, 243, 296, 344, 396, 444, 496]
    )

    decoded = json.loads(json.dumps(proposal))
    assert decoded["review_status"] == "PROPOSED"
    assert decoded["labels_are_ground_truth"] is False
    assert decoded["punches"][0]["theoretical_impact_frame"] is None
    assert decoded["punches"][1]["theoretical_impact_timestamp_ms"] == 99 * 17
    assert decoded["punches"][1]["movement_start_frame"] is None


def test_private_fixture_location_is_gitignored() -> None:
    repository = Path(__file__).resolve().parents[2]
    check = subprocess.run(
        ["git", "check-ignore", "input/private/real-kihon.fixture.json"],
        cwd=repository,
        check=False,
        capture_output=True,
        text=True,
    )

    assert check.returncode == 0
