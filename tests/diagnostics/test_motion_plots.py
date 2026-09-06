from __future__ import annotations

import json
from pathlib import Path

import pytest

from karate_analyzer.diagnostics.motion_plots import (
    _event_markers,
    _symmetric_axis_limit,
    build_motion_diagnostic_series,
    render_motion_diagnostic_plot,
    render_motion_diagnostic_plots,
)
from karate_analyzer.frame_geometry import FrameGeometry


def test_build_series_calculates_geometry_and_time_derivatives() -> None:
    payload = _video_payload([0.65, 0.70, 0.75])

    result = build_motion_diagnostic_series(payload, smoothing_window=1)

    middle = result["frames"][1]["right"]
    assert middle["elbow_angle_degrees_2d"] == pytest.approx(180)
    assert middle["wrist_centerline_distance_shoulder_widths"] == pytest.approx(1)
    assert middle["shoulder_to_wrist_extension_ratio"] == pytest.approx(1)
    assert middle["wrist_speed_shoulder_widths_per_second"] == pytest.approx(2.5)
    assert middle[
        "outward_wrist_velocity_shoulder_widths_per_second"
    ] == pytest.approx(2.5)
    assert middle[
        "outward_wrist_acceleration_shoulder_widths_per_second_squared"
    ] == pytest.approx(0)
    assert middle[
        "signed_elbow_centerline_displacement_shoulder_widths"
    ] == pytest.approx(0.75)
    assert middle[
        "elbow_forward_velocity_shoulder_widths_per_second"
    ] == pytest.approx(1.25)
    assert middle[
        "cross_body_opposition_distance_shoulder_widths"
    ] == pytest.approx(1.75)
    assert result["coordinate_space"] == (
        "analysis_image_pixels_normalized_by_shoulder_width"
    )
    assert result["production_derivative_convention"] == (
        "backward_timestamp_derivative"
    )


def test_plotted_outward_velocity_uses_production_backward_derivative() -> None:
    result = build_motion_diagnostic_series(
        _video_payload([0.65, 0.75, 0.75]), smoothing_window=1
    )

    assert result["frames"][1]["right"][
        "outward_wrist_velocity_shoulder_widths_per_second"
    ] == pytest.approx(5.0)


def test_low_visibility_landmark_produces_missing_dependent_metrics() -> None:
    payload = _video_payload([0.65, 0.70, 0.75])
    right_wrist = next(
        landmark
        for landmark in payload["frames"][1]["poses"][0]
        if landmark["index"] == 16
    )
    right_wrist["visibility"] = 0.49

    result = build_motion_diagnostic_series(payload, smoothing_window=1)

    middle = result["frames"][1]["right"]
    assert middle["elbow_angle_degrees_2d"] is None
    assert middle["wrist_centerline_distance_shoulder_widths"] is None
    assert middle["wrist_speed_shoulder_widths_per_second"] is None
    assert middle["outward_wrist_velocity_shoulder_widths_per_second"] is None
    assert middle["cross_body_opposition_distance_shoulder_widths"] is None


def test_elbow_displacement_sign_is_mirror_invariant() -> None:
    original = build_motion_diagnostic_series(
        _video_payload([0.65, 0.70, 0.75]), smoothing_window=1
    )
    mirrored_payload = _video_payload([0.65, 0.70, 0.75])
    for frame in mirrored_payload["frames"]:
        for landmark in frame["poses"][0]:
            landmark["x"] = 1.0 - landmark["x"]
    mirrored = build_motion_diagnostic_series(
        mirrored_payload, smoothing_window=1
    )

    original_values = [
        frame["right"]["signed_elbow_centerline_displacement_shoulder_widths"]
        for frame in original["frames"]
    ]
    mirrored_values = [
        frame["right"]["signed_elbow_centerline_displacement_shoulder_widths"]
        for frame in mirrored["frames"]
    ]
    assert mirrored_values == pytest.approx(original_values)


def test_event_markers_keep_candidate_impact_and_analysis_distinct() -> None:
    markers = _event_markers(
        {
            "punch_event_landmarks": [
                {
                    "event_index": 1,
                    "observed_side": "right",
                    "peak_frame_number": 10,
                    "theoretical_impact_event": {"impact_frame_number": 12},
                    "analysis_frame_number": 13,
                },
                {
                    "event_index": 2,
                    "observed_side": "left",
                    "peak_frame_number": 20,
                    "analysis_frame_number": 21,
                },
            ]
        }
    )

    assert [(marker["kind"], marker["frame_number"]) for marker in markers] == [
        ("candidate_peak", 10),
        ("theoretical_impact", 12),
        ("selected_analysis", 13),
        ("candidate_peak", 20),
        ("selected_analysis", 21),
    ]


def test_signed_axis_limit_is_symmetric_around_largest_magnitude() -> None:
    assert _symmetric_axis_limit([-4.0, 2.0, float("nan")]) == pytest.approx(4.2)
    assert _symmetric_axis_limit([0.0]) == pytest.approx(1.0)


def test_render_plot_and_companion_data(tmp_path: Path) -> None:
    landmarks_path = tmp_path / "video_landmarks.json"
    events_path = tmp_path / "punch_event_landmarks.json"
    output_path = tmp_path / "motion-diagnostics.png"
    data_path = tmp_path / "motion-diagnostics.json"
    landmarks_path.write_text(json.dumps(_video_payload([0.65, 0.70, 0.75])))
    events_path.write_text(
        json.dumps(
            {
                "punch_event_landmarks": [
                    {
                        "event_index": 1,
                        "observed_side": "right",
                        "peak_frame_number": 1,
                        "theoretical_impact_event": {"impact_frame_number": 1},
                        "analysis_frame_number": 1,
                    }
                ]
            }
        )
    )

    rendered = render_motion_diagnostic_plot(
        video_landmarks_path=landmarks_path,
        events_path=events_path,
        output_path=output_path,
        smoothing_window=1,
        data_output_path=data_path,
        side="right",
    )

    assert rendered == output_path
    assert output_path.stat().st_size > 10_000
    plotted_data = json.loads(data_path.read_text())
    assert len(plotted_data["frames"]) == 3
    assert plotted_data["event_markers"][1]["kind"] == "theoretical_impact"


def test_render_separate_arm_plots_from_one_basename(tmp_path: Path) -> None:
    landmarks_path = tmp_path / "video_landmarks.json"
    events_path = tmp_path / "punch_event_landmarks.json"
    landmarks_path.write_text(json.dumps(_video_payload([0.65, 0.70, 0.75])))
    events_path.write_text(json.dumps({"punch_event_landmarks": []}))

    rendered = render_motion_diagnostic_plots(
        video_landmarks_path=landmarks_path,
        events_path=events_path,
        output_path=tmp_path / "motion-diagnostics.png",
        smoothing_window=1,
    )

    assert rendered == {
        "left": tmp_path / "motion-diagnostics-left.png",
        "right": tmp_path / "motion-diagnostics-right.png",
    }
    assert all(path.stat().st_size > 10_000 for path in rendered.values())


@pytest.mark.parametrize("window", [0, 2, 4])
def test_smoothing_window_must_be_positive_and_odd(window: int) -> None:
    with pytest.raises(ValueError, match="positive odd"):
        build_motion_diagnostic_series(_video_payload([0.7]), smoothing_window=window)


def _video_payload(right_wrist_x_values: list[float]) -> dict:
    frames = []
    for frame_number, wrist_x in enumerate(right_wrist_x_values):
        frames.append(
            {
                "frame_number": frame_number,
                "timestamp_seconds": frame_number * 0.1,
                "poses": [
                    [
                        _landmark(11, 0.4, 0.4),
                        _landmark(12, 0.6, 0.4),
                        _landmark(13, 0.35, 0.4),
                        _landmark(14, (0.6 + wrist_x) / 2, 0.4),
                        _landmark(15, 0.3, 0.4),
                        _landmark(16, wrist_x, 0.4),
                        _landmark(23, 0.45, 0.8),
                        _landmark(24, 0.55, 0.8),
                    ]
                ],
            }
        )
    return {
        "frame_geometry": FrameGeometry.identity(100, 100).to_dict(),
        "frames": frames,
    }


def _landmark(index: int, x: float, y: float) -> dict:
    return {"index": index, "x": x, "y": y, "visibility": 0.9}
