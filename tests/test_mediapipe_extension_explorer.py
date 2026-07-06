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
    _add_smoothed_extension_ratios,
    _find_grouped_peaks,
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
    assert (output_directory / "grouped_peak_frames.json").exists()
    assert summary["frame_count"] == 3
    assert summary["detected_frame_count"] == 3
    assert summary["left_candidate_peak_count"] == 1
    assert "grouped_left_peak_count" in summary
    assert "grouped_right_peak_count" in summary
    assert "grouped_peak_frames.json" in summary["output_files"]

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


def test_moving_average_is_calculated() -> None:
    frames = _extension_frames([0.0, 1.0, None, 0.5, 1.0])

    _add_smoothed_extension_ratios(frames, 3)

    assert frames[0]["left"]["smoothed_extension_ratio"] == pytest.approx(0.5)
    assert frames[1]["left"]["smoothed_extension_ratio"] == pytest.approx(0.5)
    assert frames[2]["left"]["smoothed_extension_ratio"] == pytest.approx(0.75)
    assert frames[3]["left"]["smoothed_extension_ratio"] == pytest.approx(0.75)
    assert frames[4]["left"]["smoothed_extension_ratio"] == pytest.approx(0.75)


def test_one_clean_high_region_produces_one_grouped_peak() -> None:
    frames = _extension_frames([0.5, 0.91, 0.95, 0.93, 0.5])

    grouped_peaks = _find_grouped_peaks(
        frames, "left", threshold=0.90, min_visibility=0.5, merge_gap_frames=0
    )

    assert len(grouped_peaks) == 1
    assert grouped_peaks[0]["start_frame"] == 1
    assert grouped_peaks[0]["end_frame"] == 3
    assert grouped_peaks[0]["peak_frame_number"] == 2


def test_multiple_local_maxima_inside_one_region_produce_only_one_grouped_peak() -> None:
    frames = _extension_frames([0.91, 0.96, 0.93, 0.97, 0.92])

    grouped_peaks = _find_grouped_peaks(
        frames, "left", threshold=0.90, min_visibility=0.5, merge_gap_frames=0
    )

    assert len(grouped_peaks) == 1
    assert grouped_peaks[0]["peak_frame_number"] == 3


def test_two_regions_separated_by_large_gap_produce_two_grouped_peaks() -> None:
    frames = _extension_frames([0.92, 0.95, 0.4, 0.4, 0.4, 0.93, 0.96])

    grouped_peaks = _find_grouped_peaks(
        frames, "left", threshold=0.90, min_visibility=0.5, merge_gap_frames=1
    )

    assert [peak["peak_frame_number"] for peak in grouped_peaks] == [1, 6]


def test_short_gap_inside_region_is_merged() -> None:
    frames = _extension_frames([0.92, 0.95, 0.4, 0.93, 0.96])

    grouped_peaks = _find_grouped_peaks(
        frames, "left", threshold=0.90, min_visibility=0.5, merge_gap_frames=1
    )

    assert len(grouped_peaks) == 1
    assert grouped_peaks[0]["start_frame"] == 0
    assert grouped_peaks[0]["end_frame"] == 4
    assert grouped_peaks[0]["region_frame_count"] == 5


def test_low_visibility_frames_are_ignored_for_grouped_peaks() -> None:
    frames = _extension_frames([0.92, 0.96, 0.93], visibility=0.49)

    grouped_peaks = _find_grouped_peaks(
        frames, "left", threshold=0.90, min_visibility=0.5, merge_gap_frames=1
    )

    assert grouped_peaks == []


def test_grouped_peak_frames_json_is_written_and_contains_grouped_peaks(tmp_path: Path) -> None:
    input_path = tmp_path / "video_landmarks.json"
    output_directory = tmp_path / "debug"
    input_path.write_text(json.dumps(_video_payload(_peak_visibility=0.9)), encoding="utf-8")

    summary = analyze_extension_json(input_path, output_directory, smoothing_window=1)

    grouped_payload = json.loads((output_directory / "grouped_peak_frames.json").read_text())
    assert summary["grouped_left_peak_count"] == 1
    assert grouped_payload["sides"][0]["side"] == "left"
    assert grouped_payload["sides"][0]["grouped_peaks"][0]["peak_frame_number"] == 1


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


def _extension_frames(
    ratios: list[float | None], visibility: float = 0.9
) -> list[dict[str, object]]:
    frames: list[dict[str, object]] = []
    for frame_number, ratio in enumerate(ratios):
        side_payload = {
            "extension": ratio,
            "extension_ratio": ratio,
            "smoothed_extension_ratio": ratio,
            "min_visibility": visibility,
        }
        frames.append(
            {
                "frame_number": frame_number,
                "timestamp_seconds": frame_number / 10,
                "pose_detected": True,
                "left": dict(side_payload),
                "right": dict(side_payload),
            }
        )
    return frames
