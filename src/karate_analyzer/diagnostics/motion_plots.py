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
            outward_reach = _normalized_distance(
                shoulder, wrist, reference_shoulder_width
            )
            production_sample = production_by_side_and_frame[side].get(
                int(source_frame["frame_number"])
            )
            side_rows[side] = {
                "elbow_angle_degrees_2d": _joint_angle(shoulder, elbow, wrist),
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
        "schema_version": 3,
        "coordinate_space": "analysis_image_pixels_normalized_by_shoulder_width",
        "frame_geometry": video_landmarks["frame_geometry"],
        "reference_shoulder_width_analysis_pixels": reference_shoulder_width,
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
    markers = _event_markers(event_payload)

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
    """Render separate left- and right-arm figures from one output basename."""

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
    return outputs


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
        if marker.get("side") != side:
            continue
        row = rows_by_frame.get(marker["frame_number"])
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


def _event_markers(event_payload: dict[str, Any]) -> list[dict[str, Any]]:
    events = event_payload.get("punch_event_landmarks") or event_payload.get("events") or []
    markers: list[dict[str, Any]] = []
    for position, event in enumerate(events, start=1):
        event_index = int(event.get("event_index", position))
        side = event.get("observed_side") or event.get("expected_side")
        candidate = event.get("peak_frame_number")
        if candidate is not None:
            markers.append(
                {
                    "event_index": event_index,
                    "side": side,
                    "kind": "candidate_peak",
                    "frame_number": int(candidate),
                }
            )
        impact = (event.get("theoretical_impact_event") or {}).get(
            "impact_frame_number"
        )
        if impact is not None:
            markers.append(
                {
                    "event_index": event_index,
                    "side": side,
                    "kind": "theoretical_impact",
                    "frame_number": int(impact),
                }
            )
        analysis = event.get("analysis_frame_number")
        if analysis is None:
            analysis = (event.get("analysis_frame") or {}).get("frame_number")
        if analysis is not None:
            markers.append(
                {
                    "event_index": event_index,
                    "side": side,
                    "kind": "selected_analysis",
                    "frame_number": int(analysis),
                }
            )
    return markers


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
