from __future__ import annotations

import json
from pathlib import Path

import pytest

from karate_analyzer.diagnostics.motion_plots import (
    _build_trajectory_straightness,
    _build_event_measurements,
    _event_markers,
    _event_views,
    _symmetric_axis_limit,
    build_motion_diagnostic_series,
    render_motion_diagnostic_plot,
    render_motion_diagnostic_plots,
    render_motion_event_diagnostic_plots,
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
    assert middle["forearm_to_torso_angle_degrees_2d"] == pytest.approx(90)
    assert middle["wrist_to_same_side_hip_distance_shoulder_widths"] == pytest.approx(
        2.1360, abs=0.001
    )
    assert middle["wrist_torso_axis_progress"] == pytest.approx(0)
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


def test_event_window_clips_to_video_start_and_end() -> None:
    series = build_motion_diagnostic_series(
        _video_payload([0.65, 0.70, 0.75]), smoothing_window=1
    )
    payload = {
        "punch_event_landmarks": [
            _event(1, "right", candidate=0, window=(-100, 100)),
            _event(2, "left", candidate=2, window=(100, 500)),
        ]
    }

    views = _event_views(series, payload, _event_markers(payload, series["frames"]))

    assert views[0]["plotted_event_window"] == {
        "start_ms": 0,
        "end_ms": 100,
        "start_frame_number": 0,
        "end_frame_number": 1,
    }
    assert views[1]["plotted_event_window"]["end_ms"] == 200
    assert views[1]["plotted_event_window"]["end_frame_number"] == 2


def test_event_markers_retain_all_roles_when_frames_overlap() -> None:
    series = build_motion_diagnostic_series(
        _video_payload([0.65, 0.70, 0.75]), smoothing_window=1
    )
    event = _event(1, "right", candidate=1, window=(0, 200))
    event["theoretical_impact_event"].update(
        {
            "peak_forward_velocity_frame_number": 1,
            "peak_forward_velocity_time_ms": 100,
            "braking_phase_start_ms": 100,
            "impact_frame_number": 1,
            "theoretical_impact_time_ms": 100,
            "retraction_start_ms": 200,
        }
    )
    event["theoretical_impact_event"]["terminal_confirmation"].update(
        {
            "elbow_terminal_arrival_time_ms": 100,
            "cross_body_terminal_arrival_time_ms": 100,
        }
    )
    event["analysis_frame"] = {"frame_number": 1, "timestamp_ms": 100}
    event["analysis_frame_number"] = 1
    event["snapshot_frame"] = {"frame_number": 2, "timestamp_ms": 200}

    markers = _event_markers({"punch_event_landmarks": [event]}, series["frames"])

    assert [marker["role"] for marker in markers] == [
        "candidate_peak",
        "peak_outward_velocity",
        "braking_phase_start",
        "theoretical_impact",
        "elbow_terminal_arrival",
        "cross_body_terminal_arrival",
        "retraction_onset",
        "selected_analysis",
        "selected_snapshot",
    ]
    assert sum(marker["frame_number"] == 1 for marker in markers) == 7


@pytest.mark.parametrize(
    ("status", "supporting", "available"),
    [
        ("partial", 1, 2),
        ("confirmed", 2, 2),
        ("contradicted", 0, 2),
        ("not_assessed", 0, 0),
    ],
)
def test_event_view_serializes_confirmation_states(
    status: str, supporting: int, available: int
) -> None:
    series = build_motion_diagnostic_series(
        _video_payload([0.65, 0.70, 0.75]), smoothing_window=1
    )
    event = _event(1, "right", candidate=1, window=(0, 200))
    event["theoretical_impact_event"]["terminal_confirmation"].update(
        {
            "status": status,
            "supporting_signal_count": supporting,
            "available_signal_count": available,
        }
    )

    [view] = _event_views(
        series,
        {"punch_event_landmarks": [event]},
        _event_markers({"punch_event_landmarks": [event]}, series["frames"]),
    )

    assert view["terminal_confirmation_status"] == status
    assert view["supporting_signal_count"] == supporting
    assert view["available_signal_count"] == available


def test_unavailable_event_and_missing_secondary_signals_are_explicit() -> None:
    series = build_motion_diagnostic_series(
        _video_payload([0.65, 0.70, 0.75]), smoothing_window=1
    )
    event = _event(1, "right", candidate=0, window=(0, 100))
    impact = event["theoretical_impact_event"]
    impact["impact_frame_number"] = None
    impact["theoretical_impact_time_ms"] = None
    impact["unavailable_reason"] = "no_outward_motion"
    impact["terminal_confirmation"] = None

    [view] = _event_views(
        series,
        {"punch_event_landmarks": [event]},
        _event_markers({"punch_event_landmarks": [event]}, series["frames"]),
    )

    assert view["unavailable_reason"] == "no_outward_motion"
    assert view["terminal_confirmation_status"] == "not_assessed"
    assert view["supporting_signal_count"] == 0
    assert view["available_signal_count"] == 0
    assert not any(marker["role"] == "theoretical_impact" for marker in view["markers"])


def test_event_markers_use_irregular_timestamps() -> None:
    payload = _video_payload([0.65, 0.70, 0.75])
    payload["frames"][1]["timestamp_seconds"] = 0.037
    payload["frames"][2]["timestamp_seconds"] = 0.181
    series = build_motion_diagnostic_series(payload, smoothing_window=1)
    event = _event(1, "right", candidate=1, window=(0, 181))

    markers = _event_markers({"punch_event_landmarks": [event]}, series["frames"])

    assert markers[0]["timestamp_ms"] == 37


def test_straight_wrist_path_has_zero_deviation_and_direction_error() -> None:
    series = build_motion_diagnostic_series(
        _video_payload([0.65, 0.70, 0.75, 0.80]), smoothing_window=1
    )
    impact = _event(1, "right", candidate=3, window=(0, 300))[
        "theoretical_impact_event"
    ]

    trajectory = _build_trajectory_straightness(
        series["frames"], side="right", impact=impact
    )

    assert trajectory["status"] == "available"
    assert trajectory["maximum_absolute_deviation_shoulder_widths"] == pytest.approx(0)
    errors = [
        value
        for sample in trajectory["samples"]
        for value in sample["direction_error_degrees_by_lookback_ms"].values()
        if value is not None
    ]
    assert errors == pytest.approx([0] * len(errors))


def test_curved_wrist_path_records_signed_camera_plane_deviation() -> None:
    payload = _video_payload([0.65, 0.70, 0.75, 0.80])
    right_wrist = next(
        landmark
        for landmark in payload["frames"][2]["poses"][0]
        if landmark["index"] == 16
    )
    right_wrist["y"] = 0.45
    series = build_motion_diagnostic_series(payload, smoothing_window=1)
    impact = _event(1, "right", candidate=3, window=(0, 300))[
        "theoretical_impact_event"
    ]

    trajectory = _build_trajectory_straightness(
        series["frames"], side="right", impact=impact
    )

    assert trajectory["maximum_absolute_deviation_shoulder_widths"] > 0
    assert any(
        sample["signed_line_deviation_shoulder_widths"] != 0
        for sample in trajectory["samples"]
    )


def test_trajectory_uses_timestamp_lookback_and_is_unavailable_without_impact() -> None:
    payload = _video_payload([0.65, 0.70, 0.75, 0.80])
    for frame, timestamp in zip(payload["frames"], (0.0, 0.04, 0.13, 0.31)):
        frame["timestamp_seconds"] = timestamp
    series = build_motion_diagnostic_series(payload, smoothing_window=1)
    impact = _event(1, "right", candidate=3, window=(0, 310))[
        "theoretical_impact_event"
    ]
    impact["theoretical_impact_time_ms"] = 310

    trajectory = _build_trajectory_straightness(
        series["frames"], side="right", impact=impact
    )
    final_sample = trajectory["samples"][-1]

    assert final_sample["actual_lookback_ms"] == {
        "50": 180,
        "100": 180,
        "200": 270,
    }

    impact["impact_frame_number"] = None
    impact["theoretical_impact_time_ms"] = None
    unavailable = _build_trajectory_straightness(
        series["frames"], side="right", impact=impact
    )
    assert unavailable["unavailable_reason"] == "theoretical_impact_unavailable"


def test_trajectory_does_not_bridge_wrist_occlusion() -> None:
    payload = _video_payload([0.65, 0.70, 0.75, 0.80])
    wrist = next(
        landmark
        for landmark in payload["frames"][1]["poses"][0]
        if landmark["index"] == 16
    )
    wrist["visibility"] = 0.1
    series = build_motion_diagnostic_series(payload, smoothing_window=1)
    impact = _event(1, "right", candidate=3, window=(0, 300))[
        "theoretical_impact_event"
    ]

    trajectory = _build_trajectory_straightness(
        series["frames"], side="right", impact=impact
    )

    assert trajectory["start_selection"]["frame_number"] == 2
    assert trajectory["start_selection"]["reason"] == "first_visible_wrist_after_occlusion"
    assert [sample["frame_number"] for sample in trajectory["samples"]] == [2, 3]


def test_event_measurements_keep_punch_and_hikite_sides_separate() -> None:
    series = build_motion_diagnostic_series(
        _video_payload([0.65, 0.70, 0.75, 0.80]), smoothing_window=1
    )
    impact = _event(1, "right", candidate=3, window=(0, 300))[
        "theoretical_impact_event"
    ]
    impact.update(
        {
            "outward_motion_onset_ms": 100,
            "peak_forward_velocity_frame_number": 2,
            "peak_forward_velocity_time_ms": 200,
        }
    )

    punch, hikite = _build_event_measurements(
        series["frames"], punching_side="right", impact=impact
    )

    assert punch["peak_velocity_to_impact_ms"] == 100
    assert punch["outward_onset_to_impact_ms"] == 200
    assert punch["terminal_reach_shoulder_widths"] == pytest.approx(1)
    assert hikite["side"] == "left"
    assert hikite["forearm_to_torso_angle_degrees_2d"] == pytest.approx(90)
    assert hikite["forearm_perpendicular_deviation_degrees"] == pytest.approx(0)
    assert hikite["available_measurement_count"] == hikite["measurement_count"]
    assert hikite["core_geometry_available"] is True
    assert hikite["elbow_is_behind_centerline"] is False


def test_renders_left_and_right_event_plots_and_deterministic_json(tmp_path: Path) -> None:
    landmarks_path = tmp_path / "video_landmarks.json"
    events_path = tmp_path / "punch_event_landmarks.json"
    data_path = tmp_path / "motion-diagnostics.json"
    landmarks_path.write_text(json.dumps(_video_payload([0.65, 0.70, 0.75])))
    events_path.write_text(
        json.dumps(
            {
                "punch_event_landmarks": [
                    _event(1, "left", candidate=1, window=(0, 200)),
                    _event(2, "right", candidate=1, window=(0, 200)),
                ]
            }
        )
    )

    rendered = render_motion_event_diagnostic_plots(
        video_landmarks_path=landmarks_path,
        events_path=events_path,
        output_path=tmp_path / "motion-diagnostics.png",
        smoothing_window=1,
        data_output_path=data_path,
    )
    first_json = data_path.read_bytes()
    render_motion_event_diagnostic_plots(
        video_landmarks_path=landmarks_path,
        events_path=events_path,
        output_path=tmp_path / "motion-diagnostics.png",
        smoothing_window=1,
        data_output_path=data_path,
    )

    assert set(rendered) == {
        "event-01-left",
        "event-01-left-biomechanics",
        "event-02-right",
        "event-02-right-biomechanics",
    }
    assert all(path.stat().st_size > 10_000 for path in rendered.values())
    assert data_path.read_bytes() == first_json


def test_event_companion_json_survives_missing_matplotlib(
    tmp_path: Path, monkeypatch: pytest.MonkeyPatch
) -> None:
    landmarks_path = tmp_path / "video_landmarks.json"
    events_path = tmp_path / "punch_event_landmarks.json"
    data_path = tmp_path / "motion-diagnostics.json"
    landmarks_path.write_text(json.dumps(_video_payload([0.65, 0.70, 0.75])))
    events_path.write_text(
        json.dumps(
            {"punch_event_landmarks": [_event(1, "right", candidate=1, window=(0, 200))]}
        )
    )
    monkeypatch.setattr(
        "karate_analyzer.diagnostics.motion_plots._import_matplotlib",
        lambda: (_ for _ in ()).throw(RuntimeError("Matplotlib unavailable")),
    )

    with pytest.raises(RuntimeError, match="Matplotlib unavailable"):
        render_motion_event_diagnostic_plots(
            video_landmarks_path=landmarks_path,
            events_path=events_path,
            output_path=tmp_path / "motion-diagnostics.png",
            smoothing_window=1,
            data_output_path=data_path,
        )

    assert json.loads(data_path.read_text())["event_views"][0]["event_index"] == 1


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


def _event(
    event_index: int,
    side: str,
    *,
    candidate: int,
    window: tuple[int, int],
) -> dict:
    candidate_ms = candidate * 100
    return {
        "event_index": event_index,
        "observed_side": side,
        "peak_frame_number": candidate,
        "analysis_frame_number": candidate,
        "analysis_frame": {"frame_number": candidate, "timestamp_ms": candidate_ms},
        "snapshot_frame": {"frame_number": candidate, "timestamp_ms": candidate_ms},
        "theoretical_impact_event": {
            "impact_frame_number": candidate,
            "theoretical_impact_time_ms": candidate_ms,
            "peak_forward_velocity_frame_number": max(0, candidate - 1),
            "peak_forward_velocity_time_ms": max(0, candidate_ms - 100),
            "braking_phase_start_ms": candidate_ms,
            "retraction_start_ms": None,
            "measurement_window_start_ms": window[0],
            "measurement_window_end_ms": window[1],
            "signal_version": "analysis_pixel_shoulder_wrist_reach_v2",
            "scale_strategy": "robust_video_median_shoulder_width",
            "normalization_scale_analysis_pixels": 20.0,
            "unavailable_reason": None,
            "terminal_confirmation": {
                "status": "confirmed",
                "confirmation_window_end_ms": candidate_ms + 75,
                "supporting_signal_count": 2,
                "available_signal_count": 2,
                "elbow_terminal_arrival_time_ms": candidate_ms,
                "cross_body_terminal_arrival_time_ms": candidate_ms,
                "signal_version": "camera_plane_terminal_confirmation_v1",
            },
        },
    }
