from __future__ import annotations

import csv
import json
import math
from pathlib import Path

import pytest

from karate_analyzer.mediapipe_extension_explorer import (
    LEFT_ELBOW,
    LEFT_SHOULDER,
    LEFT_WRIST,
    MISSING_INPUT_MESSAGE,
    RIGHT_ELBOW,
    RIGHT_SHOULDER,
    RIGHT_WRIST,
    analyze_extension_json,
)


def test_missing_input_file_raises_helpful_error(tmp_path: Path) -> None:
    with pytest.raises(FileNotFoundError, match="Run the MediaPipe spike first") as exc_info:
        analyze_extension_json(tmp_path / "video_landmarks.json", tmp_path / "out")

    assert str(exc_info.value) == MISSING_INPUT_MESSAGE


def test_analyze_extension_json_writes_outputs_and_calculates_values(tmp_path: Path) -> None:
    input_path = tmp_path / "video_landmarks.json"
    output_directory = tmp_path / "nested" / "debug"
    input_path.write_text(json.dumps(_video_payload(_peak_visibility=0.9)), encoding="utf-8")

    summary = analyze_extension_json(input_path, output_directory)

    assert output_directory.is_dir()
    assert (output_directory / "extension_by_frame.json").exists()
    assert (output_directory / "extension_by_frame.csv").exists()
    assert (output_directory / "candidate_peak_frames.json").exists()
    assert summary["frame_count"] == 3
    assert summary["detected_frame_count"] == 3
    assert summary["left_candidate_peak_count"] == 1

    extension_payload = json.loads((output_directory / "extension_by_frame.json").read_text())
    peak_left = extension_payload["frames"][1]["left"]
    assert peak_left["extension"] == pytest.approx(1.0)
    assert peak_left["upper_arm_length"] == pytest.approx(0.5)
    assert peak_left["forearm_length"] == pytest.approx(0.5)
    assert peak_left["arm_chain_length"] == pytest.approx(1.0)
    assert peak_left["extension_ratio"] == pytest.approx(1.0)
    assert peak_left["min_visibility"] == pytest.approx(0.9)

    with (output_directory / "extension_by_frame.csv").open(encoding="utf-8") as csv_file:
        rows = list(csv.DictReader(csv_file))
    assert rows[1]["frame_number"] == "1"
    assert float(rows[1]["left_extension"]) == pytest.approx(1.0)
    assert float(rows[1]["left_extension_ratio"]) == pytest.approx(1.0)

    peak_payload = json.loads((output_directory / "candidate_peak_frames.json").read_text())
    left_peaks = peak_payload["sides"][0]["candidate_peaks"]
    assert left_peaks == [
        {
            "frame_number": 1,
            "timestamp_seconds": 0.1,
            "extension": 1.0,
            "extension_ratio": 1.0,
            "min_visibility": 0.9,
        }
    ]


def test_low_visibility_frame_is_not_selected_as_peak(tmp_path: Path) -> None:
    input_path = tmp_path / "video_landmarks.json"
    output_directory = tmp_path / "debug"
    input_path.write_text(json.dumps(_video_payload(_peak_visibility=0.49)), encoding="utf-8")

    summary = analyze_extension_json(input_path, output_directory)

    assert summary["left_candidate_peak_count"] == 0
    peak_payload = json.loads((output_directory / "candidate_peak_frames.json").read_text())
    assert peak_payload["sides"][0]["candidate_peaks"] == []


def _video_payload(_peak_visibility: float) -> dict[str, object]:
    return {
        "frame_count": 3,
        "detected_frame_count": 3,
        "frames": [
            _frame(0, 0.0, left_wrist=(0.5, 0.5), visibility=0.9),
            _frame(1, 0.1, left_wrist=(1.0, 0.0), visibility=_peak_visibility),
            _frame(2, 0.2, left_wrist=(0.5, 0.5), visibility=0.9),
        ],
    }


def _frame(
    frame_number: int,
    timestamp_seconds: float,
    left_wrist: tuple[float, float],
    visibility: float,
) -> dict[str, object]:
    return {
        "frame_number": frame_number,
        "timestamp_seconds": timestamp_seconds,
        "pose_detected": True,
        "poses": [
            [
                _landmark(LEFT_SHOULDER, 0.0, 0.0, visibility),
                _landmark(LEFT_ELBOW, 0.5, 0.0, visibility),
                _landmark(LEFT_WRIST, left_wrist[0], left_wrist[1], visibility),
                _landmark(RIGHT_SHOULDER, 2.0, 0.0, 0.9),
                _landmark(RIGHT_ELBOW, 2.5, 0.0, 0.9),
                _landmark(RIGHT_WRIST, 2.5, 0.5, 0.9),
            ]
        ],
    }


def _landmark(index: int, x: float, y: float, visibility: float) -> dict[str, float | int]:
    assert math.isfinite(x)
    assert math.isfinite(y)
    return {"index": index, "x": x, "y": y, "visibility": visibility}
