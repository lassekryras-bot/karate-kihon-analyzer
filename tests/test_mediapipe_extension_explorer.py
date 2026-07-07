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
    _extract_punch_event_candidates,
    _extract_punch_event_landmarks,
    _find_grouped_peaks,
    analyze_extension_json,
)


def test_missing_input_file_raises_helpful_error(tmp_path: Path) -> None:
    with pytest.raises(
        FileNotFoundError, match="Run the MediaPipe spike first"
    ) as exc_info:
        analyze_extension_json(tmp_path / "video_landmarks.json", tmp_path / "out")

    assert str(exc_info.value) == MISSING_INPUT_MESSAGE


def test_analyze_extension_json_writes_outputs_and_calculates_values(
    tmp_path: Path,
) -> None:
    input_path = tmp_path / "video_landmarks.json"
    output_directory = tmp_path / "nested" / "debug"
    input_path.write_text(
        json.dumps(_video_payload(_peak_visibility=0.9)), encoding="utf-8"
    )

    summary = analyze_extension_json(input_path, output_directory)

    assert output_directory.is_dir()
    assert (output_directory / "extension_by_frame.json").exists()
    assert (output_directory / "extension_by_frame.csv").exists()
    assert (output_directory / "candidate_peak_frames.json").exists()
    assert (output_directory / "grouped_peak_frames.json").exists()
    assert (output_directory / "punch_event_candidates.json").exists()
    assert (output_directory / "punch_event_landmarks.json").exists()
    assert summary["frame_count"] == 3
    assert summary["detected_frame_count"] == 3
    assert summary["left_candidate_peak_count"] == 1
    assert "grouped_left_peak_count" in summary
    assert "grouped_right_peak_count" in summary
    assert summary["expected_punch_count"] == 10
    assert "grouped_peak_frames.json" in summary["output_files"]
    assert "punch_event_candidates.json" in summary["output_files"]
    assert "punch_event_landmarks.json" in summary["output_files"]

    extension_payload = json.loads(
        (output_directory / "extension_by_frame.json").read_text()
    )
    peak_left = extension_payload["frames"][1]["left"]
    assert peak_left["extension"] == pytest.approx(1.0)
    assert peak_left["upper_arm_length"] == pytest.approx(0.5)
    assert peak_left["forearm_length"] == pytest.approx(0.5)
    assert peak_left["arm_chain_length"] == pytest.approx(1.0)
    assert peak_left["extension_ratio"] == pytest.approx(1.0)
    assert peak_left["min_visibility"] == pytest.approx(0.9)

    with (output_directory / "extension_by_frame.csv").open(
        encoding="utf-8"
    ) as csv_file:
        rows = list(csv.DictReader(csv_file))
    assert rows[1]["frame_number"] == "1"
    assert float(rows[1]["left_extension"]) == pytest.approx(1.0)
    assert float(rows[1]["left_extension_ratio"]) == pytest.approx(1.0)

    peak_payload = json.loads(
        (output_directory / "candidate_peak_frames.json").read_text()
    )
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
    input_path.write_text(
        json.dumps(_video_payload(_peak_visibility=0.49)), encoding="utf-8"
    )

    summary = analyze_extension_json(input_path, output_directory)

    assert summary["left_candidate_peak_count"] == 0
    peak_payload = json.loads(
        (output_directory / "candidate_peak_frames.json").read_text()
    )
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


def test_multiple_local_maxima_inside_one_region_produce_only_one_grouped_peak() -> (
    None
):
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


def test_grouped_peak_frames_json_is_written_and_contains_grouped_peaks(
    tmp_path: Path,
) -> None:
    input_path = tmp_path / "video_landmarks.json"
    output_directory = tmp_path / "debug"
    input_path.write_text(
        json.dumps(_video_payload(_peak_visibility=0.9)), encoding="utf-8"
    )

    summary = analyze_extension_json(input_path, output_directory, smoothing_window=1)

    grouped_payload = json.loads(
        (output_directory / "grouped_peak_frames.json").read_text()
    )
    assert summary["grouped_left_peak_count"] == 1
    assert grouped_payload["sides"][0]["side"] == "left"
    assert grouped_payload["sides"][0]["grouped_peaks"][0]["peak_frame_number"] == 1


def test_punch_event_candidates_ignore_initial_region_and_alternate_expected_sides() -> (
    None
):
    left_grouped_peaks = [
        _grouped_peak(start_frame=0, peak_frame_number=0, timestamp_seconds=0.0),
        _grouped_peak(start_frame=20, peak_frame_number=25, timestamp_seconds=2.5),
    ]
    right_grouped_peaks = [
        _grouped_peak(start_frame=10, peak_frame_number=15, timestamp_seconds=1.5),
        _grouped_peak(start_frame=30, peak_frame_number=35, timestamp_seconds=3.5),
    ]

    payload = _extract_punch_event_candidates(
        left_grouped_peaks,
        right_grouped_peaks,
        expected_count=10,
        expected_start_side="right",
    )

    assert payload["expected_sequence"] == [
        "right",
        "left",
        "right",
        "left",
        "right",
        "left",
        "right",
        "left",
        "right",
        "left",
    ]
    assert payload["ignored_initial_regions"] == [
        {
            **left_grouped_peaks[0],
            "side": "left",
            "ignore_reason": "initial_extended_arm_region",
        }
    ]
    assert [event["observed_side"] for event in payload["punch_event_candidates"]] == [
        "right",
        "left",
        "right",
    ]
    assert [event["expected_side"] for event in payload["punch_event_candidates"]] == [
        "right",
        "left",
        "right",
    ]
    assert all(
        event["matches_expected_side"] for event in payload["punch_event_candidates"]
    )


def test_punch_event_candidates_build_by_expected_side_and_ignore_wrong_side_noise() -> (
    None
):
    right_grouped_peaks = [
        _grouped_peak(start_frame=10, peak_frame_number=12, timestamp_seconds=1.2),
        _grouped_peak(start_frame=30, peak_frame_number=32, timestamp_seconds=3.2),
        _grouped_peak(start_frame=50, peak_frame_number=52, timestamp_seconds=5.2),
    ]
    left_grouped_peaks = [
        _grouped_peak(
            start_frame=20,
            peak_frame_number=21,
            timestamp_seconds=2.1,
            region_frame_count=2,
        ),
        _grouped_peak(start_frame=40, peak_frame_number=42, timestamp_seconds=4.2),
        _grouped_peak(start_frame=60, peak_frame_number=62, timestamp_seconds=6.2),
    ]

    payload = _extract_punch_event_candidates(
        left_grouped_peaks,
        right_grouped_peaks,
        expected_count=4,
        expected_start_side="right",
    )

    assert [event["expected_side"] for event in payload["punch_event_candidates"]] == [
        "right",
        "left",
        "right",
        "left",
    ]
    assert [event["observed_side"] for event in payload["punch_event_candidates"]] == [
        "right",
        "left",
        "right",
        "left",
    ]
    assert [
        event["peak_frame_number"] for event in payload["punch_event_candidates"]
    ] == [12, 42, 52, 62]
    assert payload["ignored_regions"][0]["observed_side"] == "left"
    assert payload["ignored_regions"][0]["ignore_reason"] == "region_too_short"


def test_punch_event_landmarks_copy_peak_frame_analysis_landmarks() -> None:
    raw_frames = [
        _frame(0, 0.0, left_wrist=(0.5, 0.5), visibility=0.9),
        _frame(5, 0.5, left_wrist=(1.0, 0.0), visibility=0.8),
    ]
    events = [
        {
            "event_index": 1,
            "expected_side": "left",
            "observed_side": "left",
            "peak_frame_number": 5,
            "timestamp_seconds": 0.5,
        }
    ]

    payload = _extract_punch_event_landmarks(raw_frames, events)

    assert payload["head_reference_candidate"] == {
        "status": "experimental",
        "strategy": "nose_then_mouth_midpoint",
    }
    assert payload["jodan_reference"] == {
        "status": "experimental",
        "strategy": "chin_reference_then_eye_nose_projection_with_fallbacks",
    }
    assert payload["punch_event_landmarks"] == [
        {
            "event_index": 1,
            "expected_side": "left",
            "observed_side": "left",
            "peak_frame_number": 5,
            "analysis_frame_number": 5,
            "timestamp_seconds": 0.5,
            "shoulder": {"x": 0.0, "y": 0.0, "visibility": 0.8},
            "elbow": {"x": 0.5, "y": 0.0, "visibility": 0.8},
            "wrist": {"x": 1.0, "y": 0.0, "visibility": 0.8},
            "impact_point": None,
            "head_reference_candidate": {
                "source": "nose",
                "x": 0.1,
                "y": 0.2,
                "visibility": 0.7,
            },
            "chin_reference": None,
            "jodan_reference": {
                "source": "nose_mouth_projection",
                "x": 0.2,
                "y": 0.4,
                "visibility": 0.6,
                "confidence": "fallback",
                "used_landmarks": [0, 9, 10],
                "notes": (
                    "Approximate experimental Jodan target reference for karate analysis; "
                    "not a medical or anatomical chin estimate."
                ),
            },
            "analysis": {
                "jodan_height": {
                    "status": "unknown",
                    "impact_point": None,
                    "target_point": {"x": 0.2, "y": 0.4, "visibility": 0.6},
                    "tolerance_px": None,
                    "vertical_offset_px": None,
                    "message": "Could not evaluate Jodan height.",
                }
            },
            "visibility": {
                "shoulder": 0.8,
                "elbow": 0.8,
                "wrist": 0.8,
                "impact_point": None,
                "head_reference_candidate": 0.7,
                "chin_reference": None,
                "jodan_reference": 0.6,
                "minimum_required_landmark_visibility": 0.7,
            },
        }
    ]


def test_punch_event_landmarks_add_jodan_reference_and_fall_back_to_mouth_midpoint_when_nose_is_missing() -> (
    None
):
    raw_frame = _frame(5, 0.5, left_wrist=(1.0, 0.0), visibility=0.8)
    raw_frame["poses"][0] = [
        landmark for landmark in raw_frame["poses"][0] if landmark["index"] != 0
    ]

    payload = _extract_punch_event_landmarks(
        [raw_frame],
        [
            {
                "event_index": 1,
                "expected_side": "left",
                "observed_side": "left",
                "peak_frame_number": 5,
                "timestamp_seconds": 0.5,
            }
        ],
    )

    assert payload["punch_event_landmarks"][0]["head_reference_candidate"] == {
        "source": "mouth_midpoint",
        "x": 0.2,
        "y": 0.4,
        "visibility": 0.6,
    }
    assert payload["punch_event_landmarks"][0]["jodan_reference"] is None


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
                _landmark(0, 0.1, 0.2, 0.7),
                _landmark(9, 0.1, 0.4, 0.6),
                _landmark(10, 0.3, 0.4, 0.65),
                _landmark(RIGHT_SHOULDER, 2.0, 0.0, 0.9),
                _landmark(RIGHT_ELBOW, 2.5, 0.0, 0.9),
                _landmark(RIGHT_WRIST, 2.5, 0.5, 0.9),
            ]
        ],
    }


def _landmark(
    index: int, x: float, y: float, visibility: float
) -> dict[str, float | int]:
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


def _grouped_peak(
    *,
    start_frame: int,
    peak_frame_number: int,
    timestamp_seconds: float,
    region_frame_count: int = 8,
) -> dict[str, float | int]:
    return {
        "start_frame": start_frame,
        "end_frame": start_frame + region_frame_count - 1,
        "peak_frame_number": peak_frame_number,
        "timestamp_seconds": timestamp_seconds,
        "extension": 1.0,
        "extension_ratio": 1.0,
        "smoothed_extension_ratio": 0.95,
        "min_visibility": 0.9,
        "region_frame_count": region_frame_count,
    }


def test_punch_event_landmarks_choose_nearby_analysis_frame_with_hand_impact() -> None:
    peak_frame = _frame(5, 0.5, left_wrist=(1.0, 0.0), visibility=0.8)
    analysis_frame = _frame(6, 0.6, left_wrist=(1.0, 0.0), visibility=0.8)
    analysis_frame["hands"] = [
        {
            "landmarks": [
                _landmark(0, 1.0, 0.0, 0.9),
                _landmark(5, 0.9, 0.38, 0.9),
                _landmark(9, 1.1, 0.42, 0.8),
            ]
        }
    ]

    payload = _extract_punch_event_landmarks(
        [peak_frame, analysis_frame],
        [
            {
                "event_index": 1,
                "expected_side": "left",
                "observed_side": "left",
                "start_frame": 5,
                "end_frame": 7,
                "peak_frame_number": 5,
                "timestamp_seconds": 0.5,
            }
        ],
    )

    event = payload["punch_event_landmarks"][0]
    assert event["peak_frame_number"] == 5
    assert event["analysis_frame_number"] == 6
    assert event["impact_point"] is not None
    assert event["timestamp_seconds"] == pytest.approx(0.6)


def test_punch_event_landmarks_contains_impact_point_when_hand_landmarks_available() -> (
    None
):
    frame = _frame(5, 0.5, left_wrist=(1.0, 0.0), visibility=0.8)
    frame["hands"] = [
        {
            "landmarks": [
                _landmark(0, 1.0, 0.0, 0.9),
                _landmark(5, 0.9, 0.38, 0.9),
                _landmark(9, 1.1, 0.42, 0.8),
            ]
        }
    ]

    payload = _extract_punch_event_landmarks(
        [frame],
        [
            {
                "event_index": 1,
                "expected_side": "left",
                "observed_side": "left",
                "peak_frame_number": 5,
                "timestamp_seconds": 0.5,
            }
        ],
    )

    event = payload["punch_event_landmarks"][0]
    assert event["impact_point"]["x"] == pytest.approx(1.0)
    assert event["impact_point"]["y"] == pytest.approx(0.4)
    assert event["analysis"]["jodan_height"]["status"] == "good"


def test_punch_event_landmarks_falls_back_to_expected_side_for_impact_matching() -> (
    None
):
    frame = _frame(5, 0.5, left_wrist=(1.0, 0.0), visibility=0.8)
    frame["hands"] = [
        {
            "handedness": {"label": "Right", "score": 0.99},
            "landmarks": [
                _landmark(0, 1.0, 0.0, 0.9),
                _landmark(5, 0.9, 0.38, 0.9),
                _landmark(9, 1.1, 0.42, 0.8),
            ],
        }
    ]

    payload = _extract_punch_event_landmarks(
        [frame],
        [
            {
                "event_index": 1,
                "expected_side": "left",
                "peak_frame_number": 5,
                "timestamp_seconds": 0.5,
            }
        ],
    )

    event = payload["punch_event_landmarks"][0]
    assert event["observed_side"] == "left"
    assert event["impact_point"]["x"] == pytest.approx(1.0)
    assert event["impact_point"]["handedness"] == {"label": "Right", "score": 0.99}


def test_punch_event_contains_chin_reference_when_face_landmarks_are_present() -> None:
    frame = _frame(5, 0.5, left_wrist=(1.0, 0.0), visibility=0.8)
    frame["faces"] = [{"landmarks": [{"index": 152, "x": 0.51, "y": 0.38, "z": -0.02}]}]

    payload = _extract_punch_event_landmarks(
        [frame],
        [
            {
                "event_index": 1,
                "expected_side": "left",
                "observed_side": "left",
                "peak_frame_number": 5,
                "timestamp_seconds": 0.5,
            }
        ],
    )

    event = payload["punch_event_landmarks"][0]
    assert event["chin_reference"]["source"] == "face_mesh_chin_152"
    assert event["visibility"]["chin_reference"] == pytest.approx(1.0)
    assert event["jodan_reference"]["source"] == "face_mesh_chin_reference"
    assert event["jodan_reference"]["y"] == pytest.approx(0.38)


def test_punch_event_still_works_without_face_landmarks() -> None:
    payload = _extract_punch_event_landmarks(
        [_frame(5, 0.5, left_wrist=(1.0, 0.0), visibility=0.8)],
        [
            {
                "event_index": 1,
                "expected_side": "left",
                "observed_side": "left",
                "peak_frame_number": 5,
                "timestamp_seconds": 0.5,
            }
        ],
    )

    event = payload["punch_event_landmarks"][0]
    assert event["chin_reference"] is None
    assert event["jodan_reference"]["source"] == "nose_mouth_projection"
