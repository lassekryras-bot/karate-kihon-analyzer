"""Full-video camera-plane motion diagnostics.

These plots are intended to explain and debug event selection. They are not
calibrated physical measurements: distances are normalized by the observed
shoulder width, speed is shoulder-widths/second, and acceleration is
shoulder-widths/second squared.
"""

from __future__ import annotations

import json
import math
from pathlib import Path
from statistics import median
from typing import Any

from karate_analyzer.frame_geometry import FrameGeometry
from karate_analyzer.strike_detection.theoretical_impact import (
    build_motion_samples,
    enrich_motion_samples,
)

LEFT_SHOULDER = 11
RIGHT_SHOULDER = 12
LEFT_ELBOW = 13
RIGHT_ELBOW = 14
LEFT_WRIST = 15
RIGHT_WRIST = 16
LEFT_HIP = 23
RIGHT_HIP = 24
SIDES = ("left", "right")


def build_motion_diagnostic_series(
    video_landmarks: dict[str, Any],
    *,
    min_visibility: float = 0.5,
    smoothing_window: int = 3,
) -> dict[str, Any]:
    """Build aspect-ratio-corrected full-video arm motion diagnostics."""

    if not 0 <= min_visibility <= 1:
        raise ValueError("min_visibility must be between 0 and 1")
    if smoothing_window < 1 or smoothing_window % 2 == 0:
        raise ValueError("smoothing_window must be a positive odd number")
    if "frame_geometry" not in video_landmarks:
        raise ValueError("video landmarks require frame_geometry")

    geometry = FrameGeometry.from_dict(video_landmarks["frame_geometry"])
    width = geometry.analysis_size.width
    height = geometry.analysis_size.height
    raw_frames = video_landmarks.get("frames", [])
    production_motion = {
        side: enrich_motion_samples(
            build_motion_samples(
                raw_frames,
                side,
                analysis_width=width,
                analysis_height=height,
                minimum_visibility=min_visibility,
            ),
            smoothing_window_samples=smoothing_window,
        )
        for side in SIDES
    }
    reference_shoulder_width = next(
        (
            sample.normalization_scale_analysis_pixels
            for samples in production_motion.values()
            for sample in samples
            if sample.normalization_scale_analysis_pixels is not None
        ),
        None,
    )
    prepared_frames: list[dict[str, Any]] = []

    for source_frame in raw_frames:
        landmarks = {
            landmark.get("index"): landmark
            for landmark in _first_pose(source_frame)
        }
        points = {
            index: _analysis_pixel_point(
                landmarks.get(index), width, height, min_visibility
            )
            for index in (
                LEFT_SHOULDER,
                RIGHT_SHOULDER,
                LEFT_ELBOW,
                RIGHT_ELBOW,
                LEFT_WRIST,
                RIGHT_WRIST,
                LEFT_HIP,
                RIGHT_HIP,
            )
        }
        shoulder_midpoint = _midpoint(
            points[LEFT_SHOULDER], points[RIGHT_SHOULDER]
        )
        hip_midpoint = _midpoint(points[LEFT_HIP], points[RIGHT_HIP])
        torso_center = _midpoint(shoulder_midpoint, hip_midpoint)
        prepared_frames.append(
            {
                "source": source_frame,
                "points": points,
                "shoulder_midpoint": shoulder_midpoint,
                "hip_midpoint": hip_midpoint,
                "torso_center": torso_center,
            }
        )

    production_by_side_and_frame = {
        side: {sample.frame_number: sample for sample in production_motion[side]}
        for side in SIDES
    }
    rows: list[dict[str, Any]] = []
    for prepared in prepared_frames:
        source_frame = prepared["source"]
        points = prepared["points"]
        shoulder_midpoint = prepared["shoulder_midpoint"]
        hip_midpoint = prepared["hip_midpoint"]
        torso_center = prepared["torso_center"]

        side_rows: dict[str, dict[str, Any]] = {}
        for side in SIDES:
            shoulder_index, elbow_index, wrist_index = _side_indices(side)
            shoulder = points[shoulder_index]
            elbow = points[elbow_index]
            wrist = points[wrist_index]
            relative_wrist = _normalized_relative_point(
                wrist, torso_center, reference_shoulder_width
            )
            shoulder_relative_wrist = _normalized_relative_point(
                wrist, shoulder, reference_shoulder_width
            )
            shoulder_relative_elbow = _normalized_relative_point(
                elbow, shoulder, reference_shoulder_width
            )
            same_side_hip = points[LEFT_HIP if side == "left" else RIGHT_HIP]
            outward_reach = _normalized_distance(
                shoulder, wrist, reference_shoulder_width
            )
            production_sample = production_by_side_and_frame[side].get(
                int(source_frame["frame_number"])
            )
            side_rows[side] = {
                "elbow_angle_degrees_2d": _joint_angle(shoulder, elbow, wrist),
                "shoulder_to_wrist_reach_shoulder_widths": (
                    production_sample.forward_progress
                    if production_sample is not None
                    else None
                ),
                "wrist_centerline_distance_shoulder_widths": (
                    _normalized_line_distance(
                        wrist,
                        shoulder_midpoint,
                        hip_midpoint,
                        reference_shoulder_width,
                    )
                ),
                "wrist_speed_shoulder_widths_per_second": None,
                "outward_wrist_velocity_shoulder_widths_per_second": None,
                "outward_wrist_acceleration_shoulder_widths_per_second_squared": (
                    None
                ),
                "shoulder_to_wrist_extension_ratio": _extension_ratio(
                    shoulder, elbow, wrist
                ),
                "forearm_to_torso_angle_degrees_2d": _line_angle_degrees(
                    elbow, wrist, shoulder_midpoint, hip_midpoint
                ),
                "wrist_to_same_side_hip_distance_shoulder_widths": (
                    _normalized_distance(
                        wrist, same_side_hip, reference_shoulder_width
                    )
                ),
                "wrist_torso_axis_progress": _line_projection_ratio(
                    wrist, shoulder_midpoint, hip_midpoint
                ),
                "signed_elbow_centerline_displacement_shoulder_widths": (
                    production_sample.signed_elbow_displacement
                    if production_sample is not None
                    else None
                ),
                "elbow_forward_velocity_shoulder_widths_per_second": (
                    production_sample.elbow_forward_velocity
                    if production_sample is not None
                    else None
                ),
                "cross_body_opposition_distance_shoulder_widths": (
                    production_sample.cross_body_opposition_distance
                    if production_sample is not None
                    else None
                ),
                "cross_body_opposition_velocity_shoulder_widths_per_second": (
                    production_sample.cross_body_opposition_velocity
                    if production_sample is not None
                    else None
                ),
                "shoulder_relative_wrist_x_shoulder_widths": (
                    shoulder_relative_wrist[0]
                    if shoulder_relative_wrist is not None
                    else None
                ),
                "shoulder_relative_wrist_y_shoulder_widths": (
                    shoulder_relative_wrist[1]
                    if shoulder_relative_wrist is not None
                    else None
                ),
                "shoulder_relative_elbow_x_shoulder_widths": (
                    shoulder_relative_elbow[0]
                    if shoulder_relative_elbow is not None
                    else None
                ),
                "shoulder_relative_elbow_y_shoulder_widths": (
                    shoulder_relative_elbow[1]
                    if shoulder_relative_elbow is not None
                    else None
                ),
                "signed_elbow_deviation_from_shoulder_wrist_line_shoulder_widths": (
                    _signed_normalized_line_distance(
                        elbow,
                        shoulder,
                        wrist,
                        reference_shoulder_width,
                        orientation_sign=(
                            production_sample.forward_orientation_sign
                            if production_sample is not None
                            else None
                        ),
                    )
                ),
                "_relative_wrist": relative_wrist,
                "_outward_reach": outward_reach,
            }

        timestamp_seconds = _timestamp_seconds(source_frame)
        rows.append(
            {
                "frame_number": int(source_frame["frame_number"]),
                "timestamp_seconds": timestamp_seconds,
                "left": side_rows["left"],
                "right": side_rows["right"],
            }
        )

    timestamps = [row["timestamp_seconds"] for row in rows]
    for side in SIDES:
        relative_positions = [row[side]["_relative_wrist"] for row in rows]
        smoothed_positions = _smooth_points(relative_positions, smoothing_window)
        velocities = _differentiate_vectors(smoothed_positions, timestamps)
        production_by_frame = {
            sample.frame_number: sample for sample in production_motion[side]
        }
        outward_velocity = [
            (
                production_by_frame[row["frame_number"]].normalized_progress_per_second
                if row[side]["_outward_reach"] is not None
                else None
            )
            for row in rows
        ]
        outward_acceleration = _differentiate_scalars(
            outward_velocity, timestamps
        )
        for index, row in enumerate(rows):
            row[side]["wrist_speed_shoulder_widths_per_second"] = _magnitude(
                velocities[index]
            )
            row[side][
                "outward_wrist_velocity_shoulder_widths_per_second"
            ] = outward_velocity[index]
            row[side][
                "outward_wrist_acceleration_shoulder_widths_per_second_squared"
            ] = outward_acceleration[index]
            del row[side]["_relative_wrist"]
            del row[side]["_outward_reach"]

    return {
        "schema_version": 4,
        "coordinate_space": "analysis_image_pixels_normalized_by_shoulder_width",
        "frame_geometry": video_landmarks["frame_geometry"],
        "reference_shoulder_width_analysis_pixels": reference_shoulder_width,
        "length_scale": {
            "strategy": "robust_video_median_shoulder_width",
            "analysis_pixels_per_output_unit": reference_shoulder_width,
            "output_unit": "shoulder_width",
            "is_physical_measurement": False,
            "calibration_contract_version": "diagnostic_length_scale_v1",
            "future_replacement": (
                "known_anatomical_length_cm_with_video_specific_pixel_observation"
            ),
        },
        "smoothing_window_frames": smoothing_window,
        "production_signal_version": "analysis_pixel_shoulder_wrist_reach_v2",
        "production_derivative_convention": "backward_timestamp_derivative",
        "forward_orientation": {
            side: {
                "sign": next(
                    (
                        sample.forward_orientation_sign
                        for sample in production_motion[side]
                        if sample.forward_orientation_sign is not None
                    ),
                    None,
                ),
                "strategy": "terminal_wrist_side_of_torso_centerline",
            }
            for side in SIDES
        },
        "minimum_landmark_visibility": min_visibility,
        "metric_definitions": {
            "shoulder_to_wrist_reach_shoulder_widths": (
                "Production shoulder-to-wrist reach divided by the fixed video "
                "shoulder-width reference."
            ),
            "elbow_angle_degrees_2d": (
                "Internal shoulder-elbow-wrist angle in analysis-image pixels."
            ),
            "wrist_centerline_distance_shoulder_widths": (
                "Absolute perpendicular wrist distance from the shoulder-to-hip "
                "body centerline, divided by the median observed shoulder width."
            ),
            "wrist_speed_shoulder_widths_per_second": (
                "Resultant speed of the torso-relative wrist after centered median "
                "position smoothing and median video shoulder-width scaling."
            ),
            "outward_wrist_velocity_shoulder_widths_per_second": (
                "Production signed time derivative of shoulder-to-wrist reach: "
                "positive is extension and negative is retraction."
            ),
            "outward_wrist_acceleration_shoulder_widths_per_second_squared": (
                "Signed time derivative of outward wrist velocity; negative can "
                "mean outward braking or acceleration into retraction."
            ),
            "shoulder_to_wrist_extension_ratio": (
                "Shoulder-wrist distance divided by upper-arm plus forearm length."
            ),
            "forearm_to_torso_angle_degrees_2d": (
                "Smallest camera-plane angle between the elbow-to-wrist forearm "
                "line and shoulder-midpoint-to-hip-midpoint torso line."
            ),
            "wrist_to_same_side_hip_distance_shoulder_widths": (
                "Camera-plane wrist distance to the same-side hip divided by the "
                "fixed video shoulder-width reference."
            ),
            "wrist_torso_axis_progress": (
                "Projection of the wrist onto the torso axis: zero at shoulder "
                "midpoint and one at hip midpoint."
            ),
            "signed_elbow_centerline_displacement_shoulder_widths": (
                "Signed perpendicular punching-elbow distance from the torso "
                "centerline. Positive is toward the terminal punching-wrist side; "
                "negative is behind it."
            ),
            "elbow_forward_velocity_shoulder_widths_per_second": (
                "Signed timestamp derivative of smoothed elbow centerline "
                "displacement. Positive is forward and negative is backward."
            ),
            "cross_body_opposition_distance_shoulder_widths": (
                "Distance from the opposite elbow to the punching wrist, divided "
                "by the fixed video shoulder-width reference."
            ),
            "cross_body_opposition_velocity_shoulder_widths_per_second": (
                "Signed timestamp derivative of smoothed cross-body opposition "
                "distance. Positive is opening and negative is closing."
            ),
            "shoulder_relative_wrist_x_shoulder_widths": (
                "Horizontal wrist position relative to the punching shoulder and "
                "divided by the fixed video shoulder-width reference."
            ),
            "shoulder_relative_wrist_y_shoulder_widths": (
                "Vertical wrist position relative to the punching shoulder and "
                "divided by the fixed video shoulder-width reference."
            ),
            "shoulder_relative_elbow_x_shoulder_widths": (
                "Horizontal elbow position relative to the punching shoulder and "
                "divided by the fixed video shoulder-width reference."
            ),
            "shoulder_relative_elbow_y_shoulder_widths": (
                "Vertical elbow position relative to the punching shoulder and "
                "divided by the fixed video shoulder-width reference."
            ),
            "signed_elbow_deviation_from_shoulder_wrist_line_shoulder_widths": (
                "Mirror-invariant signed perpendicular elbow distance from the "
                "current shoulder-to-wrist line."
            ),
        },
        "frames": rows,
    }


def render_motion_diagnostic_plot(
    *,
    video_landmarks_path: Path,
    events_path: Path,
    output_path: Path,
    min_visibility: float = 0.5,
    smoothing_window: int = 3,
    data_output_path: Path | None = None,
    side: str,
) -> Path:
    """Render one arm's motion signals with event-frame provenance markers."""

    if side not in SIDES:
        raise ValueError("side must be 'left' or 'right'")

    video_landmarks_path = Path(video_landmarks_path)
    events_path = Path(events_path)
    output_path = Path(output_path)
    video_landmarks = _read_json(video_landmarks_path)
    event_payload = _read_json(events_path)
    series = build_motion_diagnostic_series(
        video_landmarks,
        min_visibility=min_visibility,
        smoothing_window=smoothing_window,
    )
    markers = _event_markers(event_payload, series["frames"])

    if data_output_path is not None:
        data_output_path = Path(data_output_path)
        data_output_path.parent.mkdir(parents=True, exist_ok=True)
        data_output_path.write_text(
            json.dumps({**series, "event_markers": markers}, indent=2) + "\n",
            encoding="utf-8",
        )

    plt, Line2D = _import_matplotlib()
    metrics = (
        ("elbow_angle_degrees_2d", "Elbow angle", "Degrees"),
        (
            "wrist_centerline_distance_shoulder_widths",
            "Wrist distance from body centerline",
            "Shoulder widths",
        ),
        (
            "wrist_speed_shoulder_widths_per_second",
            "Torso-relative wrist speed",
            "Shoulder widths / s",
        ),
        (
            "outward_wrist_velocity_shoulder_widths_per_second",
            "Signed outward wrist velocity",
            "Shoulder widths / s",
        ),
        (
            "outward_wrist_acceleration_shoulder_widths_per_second_squared",
            "Signed outward wrist acceleration",
            "Shoulder widths / s²",
        ),
        (
            "shoulder_to_wrist_extension_ratio",
            "Shoulder-to-wrist extension ratio",
            "Ratio",
        ),
        (
            "signed_elbow_centerline_displacement_shoulder_widths",
            "Signed elbow displacement from body centerline",
            "Shoulder widths",
        ),
        (
            "elbow_forward_velocity_shoulder_widths_per_second",
            "Signed elbow forward velocity",
            "Shoulder widths / s",
        ),
        (
            "cross_body_opposition_distance_shoulder_widths",
            "Cross-body opposition: opposite elbow to punching wrist",
            "Shoulder widths",
        ),
    )
    frames = [row["frame_number"] for row in series["frames"]]
    figure, axes = plt.subplots(
        len(metrics), 1, figsize=(16, 2.45 * len(metrics) + 1.5), sharex=True
    )
    figure.subplots_adjust(top=0.90, bottom=0.06, left=0.08, right=0.985, hspace=0.22)
    side_styles = {
        "left": {"color": "#2878B5", "label": "Left arm"},
        "right": {"color": "#C74343", "label": "Right arm"},
    }
    side_style = side_styles[side]

    for axis, (metric, title, ylabel) in zip(axes, metrics):
        values = [
            math.nan if row[side][metric] is None else row[side][metric]
            for row in series["frames"]
        ]
        if metric == "outward_wrist_acceleration_shoulder_widths_per_second_squared":
            positive_values = [
                value if math.isfinite(value) and value > 0 else 0
                for value in values
            ]
            negative_values = [
                value if math.isfinite(value) and value < 0 else 0
                for value in values
            ]
            axis.fill_between(
                frames, 0, positive_values, color="#2E9D57", alpha=0.20
            )
            axis.fill_between(
                frames, 0, negative_values, color="#D45C4A", alpha=0.20
            )
            symmetric_limit = _symmetric_axis_limit(values)
            axis.set_ylim(-symmetric_limit, symmetric_limit)
            axis.text(
                0.01,
                0.93,
                "+ outward acceleration",
                transform=axis.transAxes,
                color="#237A43",
                fontsize=8,
                va="top",
            )
            axis.text(
                0.01,
                0.07,
                "− braking / acceleration into retraction",
                transform=axis.transAxes,
                color="#A33E32",
                fontsize=8,
                va="bottom",
            )
        elif metric in {
            "signed_elbow_centerline_displacement_shoulder_widths",
            "elbow_forward_velocity_shoulder_widths_per_second",
        }:
            symmetric_limit = _symmetric_axis_limit(values)
            axis.set_ylim(-symmetric_limit, symmetric_limit)
        axis.plot(frames, values, linewidth=1.5, zorder=2, **side_style)
        _draw_event_markers(axis, series["frames"], metric, markers, side)
        if metric in {
            "outward_wrist_velocity_shoulder_widths_per_second",
            "outward_wrist_acceleration_shoulder_widths_per_second_squared",
            "signed_elbow_centerline_displacement_shoulder_widths",
            "elbow_forward_velocity_shoulder_widths_per_second",
        }:
            axis.axhline(0, color="#555555", linewidth=0.8, alpha=0.7)
        axis.set_title(title, loc="left", fontsize=11, fontweight="bold")
        axis.set_ylabel(ylabel)
        axis.grid(True, color="#D9D9D9", linewidth=0.6, alpha=0.75)
        axis.spines[["top", "right"]].set_visible(False)

    axes[-1].set_xlabel("Frame number")
    figure.suptitle(
        f"Full-video {side}-arm punch motion diagnostics",
        fontsize=16,
        fontweight="bold",
        y=0.985,
    )
    figure.text(
        0.5,
        0.960,
        "Camera-plane diagnostics; distance uses the median observed shoulder width",
        ha="center",
        va="top",
        fontsize=9,
        color="#555555",
    )
    legend_items = [
        Line2D([0], [0], color=side_style["color"], label=side_style["label"]),
        Line2D(
            [0], [0], marker="^", color="none", markerfacecolor="#E69F00",
            markeredgecolor="#7A5300", label="Candidate peak"
        ),
        Line2D(
            [0], [0], marker="o", color="none", markerfacecolor="#2E9D57",
            markeredgecolor="#185C31", label="Theoretical impact"
        ),
        Line2D(
            [0], [0], marker="X", color="none", markerfacecolor="#7B4AB5",
            markeredgecolor="#47216F", label="Selected analysis frame"
        ),
    ]
    figure.legend(
        handles=legend_items,
        loc="outside upper center",
        ncol=4,
        frameon=False,
        bbox_to_anchor=(0.5, 0.945),
    )
    output_path.parent.mkdir(parents=True, exist_ok=True)
    figure.savefig(output_path, dpi=160, facecolor="white")
    plt.close(figure)
    return output_path


def render_motion_diagnostic_plots(
    *,
    video_landmarks_path: Path,
    events_path: Path,
    output_path: Path,
    min_visibility: float = 0.5,
    smoothing_window: int = 3,
    data_output_path: Path | None = None,
) -> dict[str, Path]:
    """Render full-video arm figures and one compact figure per punch event."""

    outputs: dict[str, Path] = {}
    for side in SIDES:
        side_output = _side_output_path(Path(output_path), side)
        outputs[side] = render_motion_diagnostic_plot(
            video_landmarks_path=video_landmarks_path,
            events_path=events_path,
            output_path=side_output,
            min_visibility=min_visibility,
            smoothing_window=smoothing_window,
            data_output_path=data_output_path if side == SIDES[0] else None,
            side=side,
        )
    outputs.update(
        render_motion_event_diagnostic_plots(
            video_landmarks_path=video_landmarks_path,
            events_path=events_path,
            output_path=output_path,
            min_visibility=min_visibility,
            smoothing_window=smoothing_window,
            data_output_path=data_output_path,
        )
    )
    return outputs


def render_motion_event_diagnostic_plots(
    *,
    video_landmarks_path: Path,
    events_path: Path,
    output_path: Path,
    min_visibility: float = 0.5,
    smoothing_window: int = 3,
    data_output_path: Path | None = None,
) -> dict[str, Path]:
    """Render timestamp-based, event-window views for every detected punch."""

    companion = build_motion_diagnostic_companion(
        _read_json(Path(video_landmarks_path)),
        _read_json(Path(events_path)),
        min_visibility=min_visibility,
        smoothing_window=smoothing_window,
    )
    if data_output_path is not None:
        data_path = Path(data_output_path)
        data_path.parent.mkdir(parents=True, exist_ok=True)
        data_path.write_text(
            json.dumps(companion, indent=2) + "\n",
            encoding="utf-8",
        )

    plt, Line2D = _import_matplotlib()
    outputs: dict[str, Path] = {}
    for view in companion["event_views"]:
        key = f"event-{view['event_index']:02d}-{view['side']}"
        event_output = _event_output_path(
            Path(output_path), view["event_index"], view["side"]
        )
        _render_event_view(plt, Line2D, companion, view, event_output)
        outputs[key] = event_output
        biomechanics_output = _event_biomechanics_output_path(
            Path(output_path), view["event_index"], view["side"]
        )
        _render_biomechanics_view(plt, view, biomechanics_output)
        outputs[f"{key}-biomechanics"] = biomechanics_output
    return outputs


def build_motion_diagnostic_companion(
    video_landmarks: dict[str, Any],
    event_payload: dict[str, Any],
    *,
    min_visibility: float = 0.5,
    smoothing_window: int = 3,
) -> dict[str, Any]:
    """Build serializable diagnostic data without importing Matplotlib."""

    series = build_motion_diagnostic_series(
        video_landmarks,
        min_visibility=min_visibility,
        smoothing_window=smoothing_window,
    )
    markers = _event_markers(event_payload, series["frames"])
    return {
        **series,
        "event_markers": markers,
        "event_views": _event_views(series, event_payload, markers),
    }


def _event_views(
    series: dict[str, Any],
    event_payload: dict[str, Any],
    markers: list[dict[str, Any]],
) -> list[dict[str, Any]]:
    events = event_payload.get("punch_event_landmarks") or event_payload.get("events") or []
    rows = series["frames"]
    if not rows:
        return []
    first_ms = round(rows[0]["timestamp_seconds"] * 1000)
    last_ms = round(rows[-1]["timestamp_seconds"] * 1000)
    views: list[dict[str, Any]] = []
    for position, event in enumerate(events, start=1):
        event_index = int(event.get("event_index", position))
        side = event.get("observed_side") or event.get("expected_side")
        if side not in SIDES:
            continue
        impact = event.get("theoretical_impact_event") or {}
        confirmation = impact.get("terminal_confirmation") or {}
        measurement_start = impact.get("measurement_window_start_ms")
        measurement_end = impact.get("measurement_window_end_ms")
        event_markers = [
            marker for marker in markers if marker["event_index"] == event_index
        ]
        known_times = [
            marker["timestamp_ms"]
            for marker in event_markers
            if marker.get("timestamp_ms") is not None
        ]
        if measurement_start is None:
            measurement_start = min(known_times, default=first_ms)
        if measurement_end is None:
            measurement_end = max(known_times, default=last_ms)
        confirmation_end = confirmation.get("confirmation_window_end_ms")
        plotted_start = max(first_ms, int(measurement_start))
        plotted_end = min(
            last_ms,
            max(
                int(measurement_end),
                int(confirmation_end) if confirmation_end is not None else int(measurement_end),
                *known_times,
            ),
        )
        plotted_rows = [
            row
            for row in rows
            if plotted_start <= round(row["timestamp_seconds"] * 1000) <= plotted_end
        ]
        trajectory = _build_trajectory_straightness(
            rows,
            side=side,
            impact=impact,
        )
        punch_measurements, hikite_measurements = _build_event_measurements(
            rows,
            punching_side=side,
            impact=impact,
        )
        if trajectory["status"] == "available":
            plotted_start = min(
                plotted_start, trajectory["start_selection"]["timestamp_ms"]
            )
            plotted_rows = [
                row
                for row in rows
                if plotted_start
                <= round(row["timestamp_seconds"] * 1000)
                <= plotted_end
            ]
        intervals = [
            {
                "role": "measurement_window",
                "start_ms": int(measurement_start),
                "end_ms": int(measurement_end),
            }
        ]
        braking_start = impact.get("braking_phase_start_ms")
        impact_time = impact.get("theoretical_impact_time_ms")
        retraction_start = impact.get("retraction_start_ms")
        if braking_start is not None:
            intervals.append(
                {
                    "role": "braking_phase",
                    "start_ms": int(braking_start),
                    "end_ms": int(
                        impact_time
                        if impact_time is not None
                        else retraction_start
                        if retraction_start is not None
                        else measurement_end
                    ),
                }
            )
        if impact_time is not None and confirmation_end is not None:
            intervals.append(
                {
                    "role": "terminal_confirmation_window",
                    "start_ms": int(impact_time),
                    "end_ms": int(confirmation_end),
                }
            )
        views.append(
            {
                "event_index": event_index,
                "side": side,
                "plotted_event_window": {
                    "start_ms": plotted_start,
                    "end_ms": plotted_end,
                    "start_frame_number": plotted_rows[0]["frame_number"] if plotted_rows else None,
                    "end_frame_number": plotted_rows[-1]["frame_number"] if plotted_rows else None,
                },
                "markers": event_markers,
                "intervals": intervals,
                "terminal_confirmation_status": confirmation.get("status", "not_assessed"),
                "supporting_signal_count": int(confirmation.get("supporting_signal_count", 0)),
                "available_signal_count": int(confirmation.get("available_signal_count", 0)),
                "unavailable_reason": impact.get("unavailable_reason"),
                "signal_provenance": {
                    "wrist_signal_version": impact.get(
                        "signal_version", series["production_signal_version"]
                    ),
                    "terminal_confirmation_signal_version": confirmation.get("signal_version"),
                    "scale_strategy": impact.get("scale_strategy"),
                    "normalization_scale_analysis_pixels": impact.get(
                        "normalization_scale_analysis_pixels",
                        series["reference_shoulder_width_analysis_pixels"],
                    ),
                    "coordinate_space": series["coordinate_space"],
                    "derivative_convention": series["production_derivative_convention"],
                },
                "trajectory_straightness": trajectory,
                "punch_measurements": punch_measurements,
                "hikite_measurements": hikite_measurements,
                "frames": plotted_rows,
            }
        )
    return views


def _build_event_measurements(
    rows: list[dict[str, Any]],
    *,
    punching_side: str,
    impact: dict[str, Any],
) -> tuple[dict[str, Any], dict[str, Any]]:
    rows_by_frame = {row["frame_number"]: row for row in rows}
    impact_frame = impact.get("impact_frame_number")
    impact_time = impact.get("theoretical_impact_time_ms")
    peak_frame = impact.get("peak_forward_velocity_frame_number")
    peak_time = impact.get("peak_forward_velocity_time_ms")
    peak_row = rows_by_frame.get(peak_frame)
    impact_row = rows_by_frame.get(impact_frame)
    punch_values = impact_row[punching_side] if impact_row is not None else {}
    peak_values = peak_row[punching_side] if peak_row is not None else {}
    punch = {
        "status": "available" if impact_row is not None else "unavailable",
        "impact_frame_number": impact_frame,
        "impact_timestamp_ms": impact_time,
        "peak_outward_wrist_velocity_shoulder_widths_per_second": peak_values.get(
            "outward_wrist_velocity_shoulder_widths_per_second"
        ),
        "peak_velocity_frame_number": peak_frame,
        "peak_velocity_timestamp_ms": peak_time,
        "outward_onset_to_impact_ms": (
            int(impact_time) - int(impact["outward_motion_onset_ms"])
            if impact_time is not None and impact.get("outward_motion_onset_ms") is not None
            else None
        ),
        "peak_velocity_to_impact_ms": (
            int(impact_time) - int(peak_time)
            if impact_time is not None and peak_time is not None
            else None
        ),
        "outward_wrist_velocity_at_impact_shoulder_widths_per_second": punch_values.get(
            "outward_wrist_velocity_shoulder_widths_per_second"
        ),
        "terminal_reach_shoulder_widths": punch_values.get(
            "shoulder_to_wrist_reach_shoulder_widths"
        ),
        "terminal_extension_ratio": punch_values.get(
            "shoulder_to_wrist_extension_ratio"
        ),
        "coordinate_space": "analysis_image_pixels_normalized_by_fixed_video_shoulder_width",
    }

    hikite_side = "left" if punching_side == "right" else "right"
    if impact_row is None:
        return punch, {
            "status": "unavailable",
            "unavailable_reason": "theoretical_impact_unavailable",
            "side": hikite_side,
        }
    values = impact_row[hikite_side]
    angle = values.get("forearm_to_torso_angle_degrees_2d")
    measurement_values = {
        "signed_elbow_centerline_displacement_shoulder_widths": values.get(
            "signed_elbow_centerline_displacement_shoulder_widths"
        ),
        "elbow_forward_velocity_shoulder_widths_per_second": values.get(
            "elbow_forward_velocity_shoulder_widths_per_second"
        ),
        "forearm_to_torso_angle_degrees_2d": angle,
        "forearm_perpendicular_deviation_degrees": (
            angle - 90.0 if angle is not None else None
        ),
        "wrist_to_shoulder_distance_shoulder_widths": values.get(
            "shoulder_to_wrist_reach_shoulder_widths"
        ),
        "wrist_to_same_side_hip_distance_shoulder_widths": values.get(
            "wrist_to_same_side_hip_distance_shoulder_widths"
        ),
        "wrist_distance_from_torso_centerline_shoulder_widths": values.get(
            "wrist_centerline_distance_shoulder_widths"
        ),
        "wrist_torso_axis_progress": values.get("wrist_torso_axis_progress"),
        "elbow_angle_degrees_2d": values.get("elbow_angle_degrees_2d"),
    }
    available_count = sum(value is not None for value in measurement_values.values())
    core_geometry_available = all(
        measurement_values[key] is not None
        for key in (
            "signed_elbow_centerline_displacement_shoulder_widths",
            "forearm_to_torso_angle_degrees_2d",
            "wrist_to_same_side_hip_distance_shoulder_widths",
        )
    )
    status = (
        "available"
        if core_geometry_available
        else "partial"
        if available_count
        else "unavailable"
    )
    return punch, {
        "status": status,
        "unavailable_reason": None if available_count else "hikite_landmarks_unavailable",
        "side": hikite_side,
        "sample_frame_number": impact_frame,
        "sample_timestamp_ms": impact_time,
        "available_measurement_count": available_count,
        "measurement_count": len(measurement_values),
        "core_geometry_available": core_geometry_available,
        "elbow_is_behind_centerline": (
            measurement_values[
                "signed_elbow_centerline_displacement_shoulder_widths"
            ]
            < 0
            if measurement_values[
                "signed_elbow_centerline_displacement_shoulder_widths"
            ]
            is not None
            else None
        ),
        **measurement_values,
        "coordinate_space": "analysis_image_pixels_normalized_by_fixed_video_shoulder_width",
        "interpretation": "diagnostic_measurements_without_coaching_thresholds",
    }


def _build_trajectory_straightness(
    rows: list[dict[str, Any]],
    *,
    side: str,
    impact: dict[str, Any],
    lookback_intervals_ms: tuple[int, ...] = (50, 100, 200),
    maximum_start_lookback_ms: int = 1000,
    minimum_stable_duration_ms: int = 50,
) -> dict[str, Any]:
    """Build experimental camera-plane path diagnostics without classification."""

    impact_frame = impact.get("impact_frame_number")
    impact_time = impact.get("theoretical_impact_time_ms")
    if impact_frame is None or impact_time is None:
        return {
            "status": "unavailable",
            "unavailable_reason": "theoretical_impact_unavailable",
            "method": "camera_plane_start_to_impact_wrist_path_v1",
            "lookback_intervals_ms": list(lookback_intervals_ms),
            "samples": [],
        }

    rows_through_impact = [
        row
        for row in rows
        if round(row["timestamp_seconds"] * 1000) <= int(impact_time)
    ]
    impact_position = next(
        (
            index
            for index, row in enumerate(rows_through_impact)
            if row["frame_number"] == int(impact_frame)
        ),
        len(rows_through_impact) - 1,
    )
    if impact_position < 0 or _wrist_point(rows_through_impact[impact_position], side) is None:
        return {
            "status": "unavailable",
            "unavailable_reason": "wrist_path_unavailable",
            "method": "camera_plane_start_to_impact_wrist_path_v1",
            "lookback_intervals_ms": list(lookback_intervals_ms),
            "samples": [],
        }
    contiguous_start = impact_position
    while (
        contiguous_start > 0
        and _wrist_point(rows_through_impact[contiguous_start - 1], side) is not None
    ):
        contiguous_start -= 1
    valid = rows_through_impact[contiguous_start : impact_position + 1]
    visibility_limited_start = contiguous_start > 0
    impact_row = valid[-1]
    impact_time = round(impact_row["timestamp_seconds"] * 1000)
    onset_time = impact.get("outward_motion_onset_ms")
    search_end = int(onset_time) if onset_time is not None else impact_time
    first_valid_time = round(valid[0]["timestamp_seconds"] * 1000)
    search_start = max(first_valid_time, search_end - maximum_start_lookback_ms)
    noise_floor = impact.get("velocity_noise_floor_per_second")
    chamber_rows = _last_relative_minimum_run(
        valid,
        side=side,
        start_ms=search_start,
        end_ms=search_end,
        minimum_duration_ms=minimum_stable_duration_ms,
    )
    stable_rows = _last_stable_run(
        valid,
        side=side,
        start_ms=search_start,
        end_ms=search_end,
        velocity_limit=float(noise_floor) if noise_floor is not None else None,
        minimum_duration_ms=minimum_stable_duration_ms,
    )
    if chamber_rows:
        start_row = chamber_rows[len(chamber_rows) // 2]
        start_reason = (
            "relative_minimum_after_visibility_gap"
            if visibility_limited_start
            else "relative_minimum_pre_punch_plateau"
        )
        selected_plateau = chamber_rows
    elif stable_rows:
        start_row = stable_rows[len(stable_rows) // 2]
        start_reason = "stable_pre_punch_plateau"
        selected_plateau = stable_rows
    else:
        candidates = [
            row
            for row in valid
            if search_start <= round(row["timestamp_seconds"] * 1000) <= search_end
        ]
        start_row = candidates[0] if candidates else valid[0]
        start_reason = (
            "first_visible_wrist_after_occlusion"
            if visibility_limited_start and start_row is valid[0]
            else "outward_onset_without_stable_plateau"
            if onset_time is not None
            else "earliest_visible_wrist_before_impact"
        )
        selected_plateau = []

    start_time = round(start_row["timestamp_seconds"] * 1000)
    path_rows = [
        row
        for row in valid
        if start_time <= round(row["timestamp_seconds"] * 1000) <= impact_time
    ]
    start_point = _wrist_point(start_row, side)
    end_point = _wrist_point(impact_row, side)
    assert start_point is not None and end_point is not None
    line_dx = end_point[0] - start_point[0]
    line_dy = end_point[1] - start_point[1]
    direct_distance = math.hypot(line_dx, line_dy)
    if direct_distance <= 1e-9:
        return {
            "status": "unavailable",
            "unavailable_reason": "start_and_impact_positions_coincident",
            "method": "camera_plane_start_to_impact_wrist_path_v1",
            "lookback_intervals_ms": list(lookback_intervals_ms),
            "samples": [],
        }

    samples: list[dict[str, Any]] = []
    travelled_distance = 0.0
    previous_point: tuple[float, float] | None = None
    for row in path_rows:
        point = _wrist_point(row, side)
        assert point is not None
        if previous_point is not None:
            travelled_distance += math.dist(previous_point, point)
        previous_point = point
        relative_x = point[0] - start_point[0]
        relative_y = point[1] - start_point[1]
        signed_deviation = (line_dx * relative_y - line_dy * relative_x) / direct_distance
        timestamp_ms = round(row["timestamp_seconds"] * 1000)
        elbow_point = _elbow_point(row, side)
        direction_errors: dict[str, float | None] = {}
        actual_intervals: dict[str, int | None] = {}
        for interval_ms in lookback_intervals_ms:
            previous = _lookback_row(path_rows, timestamp_ms, interval_ms)
            error, actual_interval = _direction_error(
                point,
                previous,
                side,
                line_dx,
                line_dy,
                timestamp_ms,
                noise_floor=float(noise_floor) if noise_floor is not None else None,
            )
            direction_errors[str(interval_ms)] = error
            actual_intervals[str(interval_ms)] = actual_interval
        samples.append(
            {
                "frame_number": row["frame_number"],
                "timestamp_ms": timestamp_ms,
                "normalized_time_progress": (
                    (timestamp_ms - start_time) / (impact_time - start_time)
                    if impact_time > start_time
                    else 1.0
                ),
                "signed_line_deviation_shoulder_widths": signed_deviation,
                "shoulder_relative_wrist": list(point),
                "shoulder_relative_elbow": (
                    list(elbow_point) if elbow_point is not None else None
                ),
                "signed_elbow_deviation_from_shoulder_wrist_line_shoulder_widths": (
                    row[side].get(
                        "signed_elbow_deviation_from_shoulder_wrist_line_shoulder_widths"
                    )
                ),
                "direction_error_degrees_by_lookback_ms": direction_errors,
                "actual_lookback_ms": actual_intervals,
            }
        )

    deviations = [sample["signed_line_deviation_shoulder_widths"] for sample in samples]
    elbow_deviations = [
        sample[
            "signed_elbow_deviation_from_shoulder_wrist_line_shoulder_widths"
        ]
        for sample in samples
        if sample[
            "signed_elbow_deviation_from_shoulder_wrist_line_shoulder_widths"
        ]
        is not None
    ]
    nonzero_signs = [1 if value > 0 else -1 for value in deviations if value != 0]
    direction_summary: dict[str, dict[str, float | int | None]] = {}
    for interval_ms in lookback_intervals_ms:
        values = [
            sample["direction_error_degrees_by_lookback_ms"][str(interval_ms)]
            for sample in samples
            if sample["direction_error_degrees_by_lookback_ms"][str(interval_ms)]
            is not None
        ]
        direction_summary[str(interval_ms)] = {
            "valid_sample_count": len(values),
            "maximum_absolute_error_degrees": max(map(abs, values)) if values else None,
            "rms_error_degrees": (
                math.sqrt(sum(value * value for value in values) / len(values))
                if values
                else None
            ),
        }
    return {
        "status": "available",
        "unavailable_reason": None,
        "method": "camera_plane_start_to_impact_wrist_path_v1",
        "start_selection": {
            "frame_number": start_row["frame_number"],
            "timestamp_ms": start_time,
            "reason": start_reason,
            "stable_duration_ms": (
                round(selected_plateau[-1]["timestamp_seconds"] * 1000)
                - round(selected_plateau[0]["timestamp_seconds"] * 1000)
                if selected_plateau
                else None
            ),
        },
        "impact_frame_number": impact_row["frame_number"],
        "impact_timestamp_ms": impact_time,
        "reference_line": {
            "start": list(start_point),
            "end": list(end_point),
            "coordinate_space": "punching_shoulder_relative_analysis_image_shoulder_widths",
        },
        "lookback_intervals_ms": list(lookback_intervals_ms),
        "direct_distance_shoulder_widths": direct_distance,
        "travelled_distance_shoulder_widths": travelled_distance,
        "path_efficiency_ratio": (
            direct_distance / travelled_distance if travelled_distance > 0 else None
        ),
        "maximum_absolute_deviation_shoulder_widths": max(map(abs, deviations)),
        "rms_deviation_shoulder_widths": math.sqrt(
            sum(value * value for value in deviations) / len(deviations)
        ),
        "deviation_sign_change_count": sum(
            current != previous
            for previous, current in zip(nonzero_signs, nonzero_signs[1:])
        ),
        "valid_path_sample_count": len(samples),
        "valid_elbow_path_sample_count": sum(
            sample["shoulder_relative_elbow"] is not None for sample in samples
        ),
        "maximum_absolute_elbow_line_deviation_shoulder_widths": (
            max(map(abs, elbow_deviations)) if elbow_deviations else None
        ),
        "rms_elbow_line_deviation_shoulder_widths": (
            math.sqrt(
                sum(value * value for value in elbow_deviations)
                / len(elbow_deviations)
            )
            if elbow_deviations
            else None
        ),
        "direction_error_summary_by_lookback_ms": direction_summary,
        "samples": samples,
    }


def _wrist_point(row: dict[str, Any], side: str) -> tuple[float, float] | None:
    x = row[side].get("shoulder_relative_wrist_x_shoulder_widths")
    y = row[side].get("shoulder_relative_wrist_y_shoulder_widths")
    return None if x is None or y is None else (float(x), float(y))


def _elbow_point(row: dict[str, Any], side: str) -> tuple[float, float] | None:
    x = row[side].get("shoulder_relative_elbow_x_shoulder_widths")
    y = row[side].get("shoulder_relative_elbow_y_shoulder_widths")
    return None if x is None or y is None else (float(x), float(y))


def _last_stable_run(
    rows: list[dict[str, Any]],
    *,
    side: str,
    start_ms: int,
    end_ms: int,
    velocity_limit: float | None,
    minimum_duration_ms: int,
) -> list[dict[str, Any]]:
    if velocity_limit is None:
        return []
    runs: list[list[dict[str, Any]]] = []
    current: list[dict[str, Any]] = []
    for row in rows:
        timestamp = round(row["timestamp_seconds"] * 1000)
        velocity = row[side].get("outward_wrist_velocity_shoulder_widths_per_second")
        stable = (
            start_ms <= timestamp <= end_ms
            and velocity is not None
            and abs(float(velocity)) <= velocity_limit
        )
        if stable:
            current.append(row)
        elif current:
            runs.append(current)
            current = []
    if current:
        runs.append(current)
    eligible = [
        run
        for run in runs
        if round(run[-1]["timestamp_seconds"] * 1000)
        - round(run[0]["timestamp_seconds"] * 1000)
        >= minimum_duration_ms
    ]
    return eligible[-1] if eligible else []


def _last_relative_minimum_run(
    rows: list[dict[str, Any]],
    *,
    side: str,
    start_ms: int,
    end_ms: int,
    minimum_duration_ms: int,
    near_minimum_fraction: float = 0.08,
) -> list[dict[str, Any]]:
    candidates = [
        row
        for row in rows
        if start_ms <= round(row["timestamp_seconds"] * 1000) <= end_ms
        and row[side].get("shoulder_to_wrist_reach_shoulder_widths") is not None
    ]
    if not candidates:
        return []
    reaches = [
        float(row[side]["shoulder_to_wrist_reach_shoulder_widths"])
        for row in candidates
    ]
    reach_range = max(reaches) - min(reaches)
    if reach_range <= 1e-9:
        return candidates
    threshold = min(reaches) + reach_range * near_minimum_fraction
    runs: list[list[dict[str, Any]]] = []
    current: list[dict[str, Any]] = []
    for row in candidates:
        if float(row[side]["shoulder_to_wrist_reach_shoulder_widths"]) <= threshold:
            current.append(row)
        elif current:
            runs.append(current)
            current = []
    if current:
        runs.append(current)
    eligible = [
        run
        for run in runs
        if round(run[-1]["timestamp_seconds"] * 1000)
        - round(run[0]["timestamp_seconds"] * 1000)
        >= minimum_duration_ms
    ]
    return eligible[-1] if eligible else []


def _lookback_row(
    rows: list[dict[str, Any]], timestamp_ms: int, interval_ms: int
) -> dict[str, Any] | None:
    earlier = [
        row
        for row in rows
        if round(row["timestamp_seconds"] * 1000) <= timestamp_ms - interval_ms
    ]
    return earlier[-1] if earlier else None


def _direction_error(
    point: tuple[float, float],
    previous_row: dict[str, Any] | None,
    side: str,
    line_dx: float,
    line_dy: float,
    timestamp_ms: int,
    *,
    noise_floor: float | None,
) -> tuple[float | None, int | None]:
    if previous_row is None:
        return None, None
    previous = _wrist_point(previous_row, side)
    if previous is None:
        return None, None
    previous_time = round(previous_row["timestamp_seconds"] * 1000)
    actual_interval = timestamp_ms - previous_time
    dx = point[0] - previous[0]
    dy = point[1] - previous[1]
    displacement = math.hypot(dx, dy)
    if displacement <= 1e-9:
        return None, actual_interval
    if noise_floor is not None and displacement <= noise_floor * actual_interval / 1000:
        return None, actual_interval
    cross = line_dx * dy - line_dy * dx
    dot = line_dx * dx + line_dy * dy
    if dot <= 0:
        return None, actual_interval
    return math.degrees(math.atan2(cross, dot)), actual_interval


def _render_event_view(
    plt: Any,
    Line2D: Any,
    series: dict[str, Any],
    view: dict[str, Any],
    output_path: Path,
) -> None:
    side = view["side"]
    rows = view["frames"]
    times = [row["timestamp_seconds"] for row in rows]
    panels = (
        (
            "Wrist arrival",
            "shoulder_to_wrist_reach_shoulder_widths",
            "outward_wrist_velocity_shoulder_widths_per_second",
            "Reach (shoulder widths)",
            "Outward velocity (/s)",
        ),
        (
            "Elbow terminal arrival",
            "signed_elbow_centerline_displacement_shoulder_widths",
            "elbow_forward_velocity_shoulder_widths_per_second",
            "Displacement (shoulder widths)",
            "Forward velocity (/s)",
        ),
        (
            "Cross-body terminal geometry",
            "cross_body_opposition_distance_shoulder_widths",
            "cross_body_opposition_velocity_shoulder_widths_per_second",
            "Distance (shoulder widths)",
            "Opening velocity (/s)",
        ),
    )
    figure, axes = plt.subplots(5, 1, figsize=(13, 13), sharex=True)
    figure.subplots_adjust(top=0.84, bottom=0.07, left=0.09, right=0.90, hspace=0.33)
    line_color = "#C74343" if side == "right" else "#2878B5"
    for axis, panel in zip(axes, panels):
        title, position_metric, velocity_metric, position_label, velocity_label = panel
        position_values = [
            math.nan if row[side][position_metric] is None else row[side][position_metric]
            for row in rows
        ]
        velocity_values = [
            math.nan if row[side][velocity_metric] is None else row[side][velocity_metric]
            for row in rows
        ]
        velocity_axis = axis.twinx()
        axis.plot(times, position_values, color=line_color, linewidth=2.0, label=position_label)
        velocity_axis.plot(
            times,
            velocity_values,
            color="#505050",
            linewidth=1.3,
            linestyle="--",
            label=velocity_label,
        )
        velocity_axis.axhline(0, color="#777777", linewidth=0.8, alpha=0.8)
        velocity_axis.set_ylim(
            -_symmetric_axis_limit(velocity_values),
            _symmetric_axis_limit(velocity_values),
        )
        axis.set_title(title, loc="left", fontsize=11, fontweight="bold")
        axis.set_ylabel(position_label)
        velocity_axis.set_ylabel(velocity_label)
        axis.grid(True, color="#D9D9D9", linewidth=0.6, alpha=0.75)
        axis.spines["top"].set_visible(False)
        velocity_axis.spines["top"].set_visible(False)
        for interval in view["intervals"]:
            start = interval["start_ms"] / 1000
            end = interval["end_ms"] / 1000
            if interval["role"] == "braking_phase":
                axis.axvspan(start, end, color="#E69F00", alpha=0.10)
            elif interval["role"] == "terminal_confirmation_window":
                axis.axvspan(start, end, color="#2E9D57", alpha=0.10)
        _draw_event_role_lines(axis, view["markers"])

    trajectory = view["trajectory_straightness"]
    deviation_axis = axes[3]
    direction_axis = axes[4]
    if trajectory["status"] == "available":
        trajectory_times = [sample["timestamp_ms"] / 1000 for sample in trajectory["samples"]]
        deviations = [
            sample["signed_line_deviation_shoulder_widths"]
            for sample in trajectory["samples"]
        ]
        deviation_axis.plot(trajectory_times, deviations, color="#009E73", linewidth=2)
        deviation_axis.fill_between(
            trajectory_times, 0, deviations, color="#009E73", alpha=0.15
        )
        interval_styles = {
            "50": ("#0072B2", "50 ms"),
            "100": ("#D55E00", "100 ms"),
            "200": ("#CC79A7", "200 ms"),
        }
        for interval, (color, label) in interval_styles.items():
            values = [
                math.nan
                if sample["direction_error_degrees_by_lookback_ms"][interval] is None
                else sample["direction_error_degrees_by_lookback_ms"][interval]
                for sample in trajectory["samples"]
            ]
            direction_axis.plot(
                trajectory_times, values, color=color, linewidth=1.5, label=label
            )
        direction_axis.legend(loc="upper left", ncol=3, frameon=False, fontsize=8)
        start = trajectory["start_selection"]
        deviation_axis.axvline(start["timestamp_ms"] / 1000, color="#111111", linestyle="--", linewidth=1)
        direction_axis.axvline(start["timestamp_ms"] / 1000, color="#111111", linestyle="--", linewidth=1)
        deviation_axis.text(
            start["timestamp_ms"] / 1000,
            0.96,
            f" path start f{start['frame_number']} ({start['reason']})",
            transform=deviation_axis.get_xaxis_transform(),
            ha="left",
            va="top",
            fontsize=8,
        )
    else:
        for axis in (deviation_axis, direction_axis):
            axis.text(
                0.5,
                0.5,
                f"Unavailable: {trajectory['unavailable_reason']}",
                transform=axis.transAxes,
                ha="center",
                va="center",
                color="#666666",
            )
    deviation_axis.axhline(0, color="#777777", linewidth=0.8)
    direction_axis.axhline(0, color="#777777", linewidth=0.8)
    deviation_axis.set_title("Wrist deviation from start-to-impact line", loc="left", fontsize=11, fontweight="bold")
    deviation_axis.set_ylabel("Signed deviation\n(shoulder widths)")
    direction_axis.set_title("Movement direction error from start-to-impact line", loc="left", fontsize=11, fontweight="bold")
    direction_axis.set_ylabel("Signed angle (degrees)")
    for axis in (deviation_axis, direction_axis):
        axis.grid(True, color="#D9D9D9", linewidth=0.6, alpha=0.75)
        axis.spines[["top", "right"]].set_visible(False)
        _draw_event_role_lines(axis, view["markers"])

    if rows:
        axes[-1].set_xlim(times[0], times[-1])
    axes[-1].set_xlabel("Timestamp (seconds); marker labels include frame number")
    status = view["terminal_confirmation_status"]
    reason = view["unavailable_reason"]
    figure.suptitle(
        f"Punch {view['event_index']} · {side} arm · terminal confirmation: {status}",
        fontsize=15,
        fontweight="bold",
        y=0.99,
    )
    subtitle = (
        f"Unavailable: {reason}"
        if reason
        else (
            f"Secondary support: {view['supporting_signal_count']} / "
            f"{view['available_signal_count']} available signals"
        )
    )
    figure.text(0.5, 0.957, subtitle, ha="center", va="top", fontsize=10)
    annotation_lines = _marker_annotation_lines(view["markers"])
    figure.text(
        0.5,
        0.93,
        "\n".join(annotation_lines),
        ha="center",
        va="top",
        fontsize=8.5,
        color="#333333",
    )
    legend_items = [
        Line2D([0], [0], color=line_color, linewidth=2, label="Position / distance"),
        Line2D([0], [0], color="#505050", linestyle="--", label="Signed velocity"),
        Line2D([0], [0], color="#E69F00", linewidth=6, alpha=0.35, label="Braking phase"),
        Line2D([0], [0], color="#2E9D57", linewidth=6, alpha=0.35, label="Confirmation window"),
    ]
    figure.legend(handles=legend_items, loc="outside upper center", ncol=4, frameon=False, bbox_to_anchor=(0.5, 0.895))
    output_path.parent.mkdir(parents=True, exist_ok=True)
    figure.savefig(output_path, dpi=160, facecolor="white")
    plt.close(figure)


def _render_biomechanics_view(
    plt: Any,
    view: dict[str, Any],
    output_path: Path,
) -> None:
    trajectory = view["trajectory_straightness"]
    figure, axes = plt.subplots(1, 2, figsize=(14, 6))
    figure.subplots_adjust(top=0.84, bottom=0.12, left=0.08, right=0.97, wspace=0.25)
    path_axis, deviation_axis = axes
    if trajectory["status"] == "available":
        samples = trajectory["samples"]
        wrist = [sample["shoulder_relative_wrist"] for sample in samples]
        elbow = [
            sample["shoulder_relative_elbow"]
            for sample in samples
            if sample["shoulder_relative_elbow"] is not None
        ]
        path_axis.plot(
            [point[0] for point in wrist],
            [point[1] for point in wrist],
            color="#0072B2",
            linewidth=2,
            marker="o",
            markersize=3,
            label="Wrist journey",
        )
        if elbow:
            path_axis.plot(
                [point[0] for point in elbow],
                [point[1] for point in elbow],
                color="#D55E00",
                linewidth=2,
                marker="o",
                markersize=3,
                label="Elbow journey",
            )
        path_axis.plot(
            [wrist[0][0], wrist[-1][0]],
            [wrist[0][1], wrist[-1][1]],
            color="#555555",
            linestyle="--",
            linewidth=1.2,
            label="Direct wrist line",
        )
        path_axis.scatter([0], [0], marker="x", s=55, color="#111111", label="Shoulder")
        path_axis.scatter(
            [wrist[0][0], wrist[-1][0]],
            [wrist[0][1], wrist[-1][1]],
            s=55,
            color=["#009E73", "#CC79A7"],
            zorder=5,
        )
        path_axis.invert_yaxis()
        path_axis.set_aspect("equal", adjustable="datalim")

        progress = [sample["normalized_time_progress"] * 100 for sample in samples]
        wrist_deviation = [
            sample["signed_line_deviation_shoulder_widths"] for sample in samples
        ]
        elbow_deviation = [
            math.nan
            if sample[
                "signed_elbow_deviation_from_shoulder_wrist_line_shoulder_widths"
            ]
            is None
            else sample[
                "signed_elbow_deviation_from_shoulder_wrist_line_shoulder_widths"
            ]
            for sample in samples
        ]
        deviation_axis.plot(
            progress,
            wrist_deviation,
            color="#0072B2",
            linewidth=2,
            label="Wrist from direct path",
        )
        deviation_axis.plot(
            progress,
            elbow_deviation,
            color="#D55E00",
            linewidth=2,
            label="Elbow from shoulder–wrist line",
        )
        deviation_axis.axhline(0, color="#777777", linewidth=0.8)
        deviation_axis.set_xlim(0, 100)
        start = trajectory["start_selection"]
        figure.text(
            0.5,
            0.90,
            f"Path f{start['frame_number']} → f{trajectory['impact_frame_number']} · "
            f"start: {start['reason']} · elbow samples: "
            f"{trajectory['valid_elbow_path_sample_count']} / {trajectory['valid_path_sample_count']}",
            ha="center",
            fontsize=9,
        )
    else:
        for axis in axes:
            axis.text(
                0.5,
                0.5,
                f"Unavailable: {trajectory['unavailable_reason']}",
                transform=axis.transAxes,
                ha="center",
                va="center",
            )
    path_axis.set_title("Shoulder-relative wrist and elbow journeys", loc="left", fontweight="bold")
    path_axis.set_xlabel("Horizontal position (shoulder widths)")
    path_axis.set_ylabel("Vertical position (shoulder widths)")
    deviation_axis.set_title("Arm-path deviations over punch progress", loc="left", fontweight="bold")
    deviation_axis.set_xlabel("Normalized start-to-impact time (%)")
    deviation_axis.set_ylabel("Signed deviation (shoulder widths)")
    for axis in axes:
        axis.grid(True, color="#D9D9D9", linewidth=0.6, alpha=0.75)
        axis.spines[["top", "right"]].set_visible(False)
        handles, labels = axis.get_legend_handles_labels()
        if handles:
            axis.legend(handles, labels, loc="best", frameon=False, fontsize=8)
    figure.suptitle(
        f"Punch {view['event_index']} · {view['side']} arm · experimental biomechanics path",
        fontsize=15,
        fontweight="bold",
        y=0.98,
    )
    output_path.parent.mkdir(parents=True, exist_ok=True)
    figure.savefig(output_path, dpi=160, facecolor="white")
    plt.close(figure)


def _draw_event_role_lines(axis: Any, markers: list[dict[str, Any]]) -> None:
    colors = {
        "candidate_peak": "#E69F00",
        "peak_outward_velocity": "#0072B2",
        "braking_phase_start": "#D55E00",
        "theoretical_impact": "#2E9D57",
        "elbow_terminal_arrival": "#8E5BA6",
        "cross_body_terminal_arrival": "#56B4E9",
        "retraction_onset": "#C74343",
        "selected_analysis": "#7B4AB5",
        "selected_snapshot": "#111111",
    }
    for marker in markers:
        timestamp_ms = marker.get("timestamp_ms")
        if timestamp_ms is None:
            continue
        axis.axvline(
            timestamp_ms / 1000,
            color=colors.get(marker["role"], "#777777"),
            linewidth=1.0,
            linestyle=":" if marker["role"] not in {"theoretical_impact", "retraction_onset"} else "-",
            alpha=0.85,
        )


def _marker_annotation_lines(markers: list[dict[str, Any]]) -> list[str]:
    labels = {
        "candidate_peak": "candidate",
        "peak_outward_velocity": "peak +wrist v",
        "braking_phase_start": "braking",
        "theoretical_impact": "impact",
        "elbow_terminal_arrival": "elbow arrival",
        "cross_body_terminal_arrival": "cross-body arrival",
        "retraction_onset": "retraction",
        "selected_analysis": "analysis",
        "selected_snapshot": "snapshot",
    }
    items = []
    for marker in markers:
        frame = marker.get("frame_number")
        timestamp = marker.get("timestamp_ms")
        if timestamp is None:
            continue
        frame_text = f"f{frame}" if frame is not None else "between frames"
        items.append(f"{labels.get(marker['role'], marker['role'])}: {frame_text} @ {timestamp / 1000:.3f}s")
    midpoint = (len(items) + 1) // 2
    return ["  |  ".join(items[:midpoint]), "  |  ".join(items[midpoint:])]


def _event_output_path(output_path: Path, event_index: int, side: str) -> Path:
    suffix = output_path.suffix or ".png"
    stem = output_path.stem if output_path.suffix else output_path.name
    return output_path.with_name(f"{stem}-event-{event_index:02d}-{side}{suffix}")


def _event_biomechanics_output_path(
    output_path: Path, event_index: int, side: str
) -> Path:
    suffix = output_path.suffix or ".png"
    stem = output_path.stem if output_path.suffix else output_path.name
    return output_path.with_name(
        f"{stem}-event-{event_index:02d}-{side}-biomechanics{suffix}"
    )


def _draw_event_markers(
    axis: Any,
    rows: list[dict[str, Any]],
    metric: str,
    markers: list[dict[str, Any]],
    side: str,
) -> None:
    rows_by_frame = {row["frame_number"]: row for row in rows}
    styles = {
        "candidate_peak": ("^", "#E69F00", "#7A5300", 34, 3),
        "theoretical_impact": ("o", "#2E9D57", "#185C31", 42, 4),
        "selected_analysis": ("X", "#7B4AB5", "#47216F", 38, 5),
    }
    for marker in markers:
        if marker.get("side") != side or marker.get("kind") not in styles:
            continue
        row = rows_by_frame.get(marker.get("frame_number"))
        if row is None:
            continue
        value = row[side][metric]
        if value is None:
            continue
        symbol, fill, edge, size, order = styles[marker["kind"]]
        axis.scatter(
            marker["frame_number"],
            value,
            marker=symbol,
            s=size,
            facecolor=fill,
            edgecolor=edge,
            linewidth=0.7,
            zorder=order,
        )


def _side_output_path(output_path: Path, side: str) -> Path:
    suffix = output_path.suffix or ".png"
    stem = output_path.stem if output_path.suffix else output_path.name
    return output_path.with_name(f"{stem}-{side}{suffix}")


def _event_markers(
    event_payload: dict[str, Any],
    rows: list[dict[str, Any]] | None = None,
) -> list[dict[str, Any]]:
    events = event_payload.get("punch_event_landmarks") or event_payload.get("events") or []
    frame_times = {
        int(row["frame_number"]): round(float(row["timestamp_seconds"]) * 1000)
        for row in (rows or [])
    }
    markers: list[dict[str, Any]] = []
    for position, event in enumerate(events, start=1):
        event_index = int(event.get("event_index", position))
        side = event.get("observed_side") or event.get("expected_side")
        impact_event = event.get("theoretical_impact_event") or {}
        confirmation = impact_event.get("terminal_confirmation") or {}

        def append(
            role: str,
            *,
            frame_number: int | None = None,
            timestamp_ms: int | None = None,
        ) -> None:
            if frame_number is None and timestamp_ms is None:
                return
            frame = int(frame_number) if frame_number is not None else _nearest_frame(
                frame_times, int(timestamp_ms)
            )
            timestamp = (
                int(timestamp_ms)
                if timestamp_ms is not None
                else frame_times.get(frame) if frame is not None else None
            )
            markers.append(
                {
                    "event_index": event_index,
                    "side": side,
                    "role": role,
                    "kind": role,
                    "frame_number": frame,
                    "timestamp_ms": timestamp,
                }
            )

        append("candidate_peak", frame_number=event.get("peak_frame_number"))
        append(
            "peak_outward_velocity",
            frame_number=impact_event.get("peak_forward_velocity_frame_number"),
            timestamp_ms=impact_event.get("peak_forward_velocity_time_ms"),
        )
        append(
            "braking_phase_start",
            timestamp_ms=impact_event.get("braking_phase_start_ms"),
        )
        append(
            "theoretical_impact",
            frame_number=impact_event.get("impact_frame_number"),
            timestamp_ms=impact_event.get("theoretical_impact_time_ms"),
        )
        append(
            "elbow_terminal_arrival",
            timestamp_ms=confirmation.get("elbow_terminal_arrival_time_ms"),
        )
        append(
            "cross_body_terminal_arrival",
            timestamp_ms=confirmation.get("cross_body_terminal_arrival_time_ms"),
        )
        append(
            "retraction_onset",
            timestamp_ms=impact_event.get("retraction_start_ms"),
        )
        analysis = event.get("analysis_frame_number")
        if analysis is None:
            analysis = (event.get("analysis_frame") or {}).get("frame_number")
        analysis_record = event.get("analysis_frame") or {}
        append(
            "selected_analysis",
            frame_number=analysis,
            timestamp_ms=analysis_record.get("timestamp_ms"),
        )
        snapshot_record = event.get("snapshot_frame") or {}
        snapshot = snapshot_record.get("frame_number")
        if snapshot is not None and snapshot != analysis:
            append(
                "selected_snapshot",
                frame_number=snapshot,
                timestamp_ms=snapshot_record.get("timestamp_ms"),
            )
    return markers


def _nearest_frame(frame_times: dict[int, int], timestamp_ms: int) -> int | None:
    if not frame_times:
        return None
    return min(frame_times, key=lambda frame: (abs(frame_times[frame] - timestamp_ms), frame))


def _first_pose(frame: dict[str, Any]) -> list[dict[str, Any]]:
    poses = frame.get("poses") or []
    return poses[0] if poses else []


def _analysis_pixel_point(
    landmark: dict[str, Any] | None,
    width: int,
    height: int,
    min_visibility: float,
) -> tuple[float, float] | None:
    if landmark is None or float(landmark.get("visibility", 0.0)) < min_visibility:
        return None
    try:
        x = float(landmark["x"]) * width
        y = float(landmark["y"]) * height
    except (KeyError, TypeError, ValueError):
        return None
    return (x, y) if math.isfinite(x) and math.isfinite(y) else None


def _side_indices(side: str) -> tuple[int, int, int]:
    if side == "left":
        return LEFT_SHOULDER, LEFT_ELBOW, LEFT_WRIST
    return RIGHT_SHOULDER, RIGHT_ELBOW, RIGHT_WRIST


def _timestamp_seconds(frame: dict[str, Any]) -> float:
    value = frame.get("timestamp_seconds")
    if value is None and frame.get("timestamp_ms") is not None:
        value = float(frame["timestamp_ms"]) / 1000
    if value is None:
        raise ValueError("every diagnostic frame requires a timestamp")
    value = float(value)
    if not math.isfinite(value):
        raise ValueError("frame timestamps must be finite")
    return value


def _midpoint(
    a: tuple[float, float] | None, b: tuple[float, float] | None
) -> tuple[float, float] | None:
    if a is None or b is None:
        return None
    return ((a[0] + b[0]) / 2, (a[1] + b[1]) / 2)


def _distance(
    a: tuple[float, float] | None, b: tuple[float, float] | None
) -> float | None:
    if a is None or b is None:
        return None
    return math.hypot(a[0] - b[0], a[1] - b[1])


def _joint_angle(
    shoulder: tuple[float, float] | None,
    elbow: tuple[float, float] | None,
    wrist: tuple[float, float] | None,
) -> float | None:
    if shoulder is None or elbow is None or wrist is None:
        return None
    upper = (shoulder[0] - elbow[0], shoulder[1] - elbow[1])
    lower = (wrist[0] - elbow[0], wrist[1] - elbow[1])
    upper_length = math.hypot(*upper)
    lower_length = math.hypot(*lower)
    if upper_length == 0 or lower_length == 0:
        return None
    cosine = (upper[0] * lower[0] + upper[1] * lower[1]) / (
        upper_length * lower_length
    )
    return math.degrees(math.acos(max(-1.0, min(1.0, cosine))))


def _line_angle_degrees(
    first_start: tuple[float, float] | None,
    first_end: tuple[float, float] | None,
    second_start: tuple[float, float] | None,
    second_end: tuple[float, float] | None,
) -> float | None:
    if None in (first_start, first_end, second_start, second_end):
        return None
    assert first_start and first_end and second_start and second_end
    first = (first_end[0] - first_start[0], first_end[1] - first_start[1])
    second = (second_end[0] - second_start[0], second_end[1] - second_start[1])
    denominator = math.hypot(*first) * math.hypot(*second)
    if denominator <= 1e-9:
        return None
    cosine = abs(first[0] * second[0] + first[1] * second[1]) / denominator
    return math.degrees(math.acos(max(-1.0, min(1.0, cosine))))


def _line_projection_ratio(
    point: tuple[float, float] | None,
    line_start: tuple[float, float] | None,
    line_end: tuple[float, float] | None,
) -> float | None:
    if point is None or line_start is None or line_end is None:
        return None
    dx = line_end[0] - line_start[0]
    dy = line_end[1] - line_start[1]
    denominator = dx * dx + dy * dy
    if denominator <= 1e-9:
        return None
    return (
        (point[0] - line_start[0]) * dx + (point[1] - line_start[1]) * dy
    ) / denominator


def _normalized_line_distance(
    point: tuple[float, float] | None,
    line_start: tuple[float, float] | None,
    line_end: tuple[float, float] | None,
    scale: float | None,
) -> float | None:
    if point is None or line_start is None or line_end is None or not scale:
        return None
    line = (line_end[0] - line_start[0], line_end[1] - line_start[1])
    line_length = math.hypot(*line)
    if line_length == 0:
        return None
    cross = abs(
        line[0] * (line_start[1] - point[1])
        - (line_start[0] - point[0]) * line[1]
    )
    return cross / line_length / scale


def _signed_normalized_line_distance(
    point: tuple[float, float] | None,
    line_start: tuple[float, float] | None,
    line_end: tuple[float, float] | None,
    scale: float | None,
    *,
    orientation_sign: int | None,
) -> float | None:
    if (
        point is None
        or line_start is None
        or line_end is None
        or not scale
        or orientation_sign is None
    ):
        return None
    dx = line_end[0] - line_start[0]
    dy = line_end[1] - line_start[1]
    line_length = math.hypot(dx, dy)
    if line_length <= 1e-9:
        return None
    cross = dx * (point[1] - line_start[1]) - dy * (point[0] - line_start[0])
    return orientation_sign * cross / line_length / scale


def _normalized_relative_point(
    point: tuple[float, float] | None,
    origin: tuple[float, float] | None,
    scale: float | None,
) -> tuple[float, float] | None:
    if point is None or origin is None or not scale:
        return None
    return ((point[0] - origin[0]) / scale, (point[1] - origin[1]) / scale)


def _normalized_distance(
    a: tuple[float, float] | None,
    b: tuple[float, float] | None,
    scale: float | None,
) -> float | None:
    distance = _distance(a, b)
    if distance is None or not scale:
        return None
    return distance / scale


def _extension_ratio(
    shoulder: tuple[float, float] | None,
    elbow: tuple[float, float] | None,
    wrist: tuple[float, float] | None,
) -> float | None:
    extension = _distance(shoulder, wrist)
    upper_arm = _distance(shoulder, elbow)
    forearm = _distance(elbow, wrist)
    if extension is None or upper_arm is None or forearm is None:
        return None
    chain_length = upper_arm + forearm
    return None if chain_length == 0 else extension / chain_length


def _smooth_points(
    values: list[tuple[float, float] | None], window: int
) -> list[tuple[float, float] | None]:
    radius = window // 2
    smoothed: list[tuple[float, float] | None] = []
    for index, value in enumerate(values):
        if value is None:
            smoothed.append(None)
            continue
        local = [
            item
            for item in values[max(0, index - radius) : index + radius + 1]
            if item is not None
        ]
        smoothed.append(
            (median(item[0] for item in local), median(item[1] for item in local))
        )
    return smoothed


def _smooth_scalars(values: list[float | None], window: int) -> list[float | None]:
    radius = window // 2
    smoothed: list[float | None] = []
    for index, value in enumerate(values):
        if value is None:
            smoothed.append(None)
            continue
        local = [
            item
            for item in values[max(0, index - radius) : index + radius + 1]
            if item is not None
        ]
        smoothed.append(median(local))
    return smoothed


def _differentiate_scalars(
    values: list[float | None], times: list[float]
) -> list[float | None]:
    derivatives: list[float | None] = []
    for index, value in enumerate(values):
        if value is None:
            derivatives.append(None)
            continue
        before = index - 1 if index > 0 and values[index - 1] is not None else None
        after = (
            index + 1
            if index + 1 < len(values) and values[index + 1] is not None
            else None
        )
        start = before if before is not None else index
        end = after if after is not None else index
        elapsed = times[end] - times[start]
        if start == end or elapsed <= 0:
            derivatives.append(None)
            continue
        start_value = values[start]
        end_value = values[end]
        assert start_value is not None and end_value is not None
        derivatives.append((end_value - start_value) / elapsed)
    return derivatives


def _differentiate_vectors(
    values: list[tuple[float, float] | None], times: list[float]
) -> list[tuple[float, float] | None]:
    derivatives: list[tuple[float, float] | None] = []
    for index, value in enumerate(values):
        if value is None:
            derivatives.append(None)
            continue
        before = index - 1 if index > 0 and values[index - 1] is not None else None
        after = (
            index + 1
            if index + 1 < len(values) and values[index + 1] is not None
            else None
        )
        start = before if before is not None else index
        end = after if after is not None else index
        if start == end:
            derivatives.append(None)
            continue
        elapsed = times[end] - times[start]
        if elapsed <= 0:
            derivatives.append(None)
            continue
        start_value = values[start]
        end_value = values[end]
        assert start_value is not None and end_value is not None
        derivatives.append(
            (
                (end_value[0] - start_value[0]) / elapsed,
                (end_value[1] - start_value[1]) / elapsed,
            )
        )
    return derivatives


def _magnitude(value: tuple[float, float] | None) -> float | None:
    return None if value is None else math.hypot(*value)


def _symmetric_axis_limit(values: list[float]) -> float:
    finite_values = [abs(value) for value in values if math.isfinite(value)]
    return max(finite_values, default=1.0) * 1.05 or 1.0


def _read_json(path: Path) -> dict[str, Any]:
    if not path.exists():
        raise FileNotFoundError(path)
    if not path.is_file():
        raise ValueError(f"Expected a JSON file: {path}")
    return json.loads(path.read_text(encoding="utf-8"))


def _import_matplotlib() -> tuple[Any, Any]:
    try:
        import matplotlib

        matplotlib.use("Agg")
        import matplotlib.pyplot as plt
        from matplotlib.lines import Line2D
    except ImportError as exc:
        raise RuntimeError(
            "Matplotlib is required for motion diagnostic plots."
        ) from exc
    return plt, Line2D
