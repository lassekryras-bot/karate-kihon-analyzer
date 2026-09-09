"""App-facing punch-path presentation data.

The analyzer owns measurement meaning and provenance. Applications own drawing,
interaction, localization, spoken copy, and coaching presentation.
"""

from __future__ import annotations

import math
from copy import deepcopy
from statistics import median
from typing import Any

POSE_INDEX = {
    "nose": 0,
    "left_ear": 7,
    "right_ear": 8,
    "left_shoulder": 11,
    "right_shoulder": 12,
    "left_elbow": 13,
    "right_elbow": 14,
    "left_wrist": 15,
    "right_wrist": 16,
    "left_hip": 23,
    "right_hip": 24,
}


def build_punch_path_presentation_bundle(
    diagnostic_companion: dict[str, Any],
    video_landmarks: dict[str, Any],
    *,
    event_index: int | None = None,
    upper_arm_length_m: float | None = None,
    body_measurements: dict[str, Any] | None = None,
) -> dict[str, Any]:
    """Build a deterministic, renderer-neutral bundle for one or all events."""

    views = diagnostic_companion.get("event_views", [])
    if event_index is not None:
        views = [view for view in views if view.get("event_index") == event_index]
        if not views:
            raise ValueError(f"Unknown event index: {event_index}")
    landmark_frames = {
        int(frame["frame_number"]): frame
        for frame in video_landmarks.get("frames", [])
    }
    presentations = []
    motions = {}
    for view in views:
        presentation = _build_event_presentation(view, diagnostic_companion, landmark_frames)
        motion = presentation.pop("animation")
        reference_line = motion.pop("reference_line")
        motion_id = f"punch:{view['event_index']}"
        motion["event"] = presentation.pop("event")
        motion["markers"] = view.get("markers", [])
        motion["intervals"] = view.get("intervals", [])
        # The shared player includes the full event window, even when the wrist
        # trajectory is only a terminal fragment or is unavailable.
        rows = view.get("frames") or motion["frames"]
        motion["frames"] = [
            {"frame_number": int(row["frame_number"]),
             "timestamp_ms": int(row["timestamp_ms"]) if "timestamp_ms" in row
                 else round(row["timestamp_seconds"] * 1000),
             "pose": _semantic_upper_body_pose(_first_pose(
                 landmark_frames.get(int(row["frame_number"]), {})))}
            for row in rows
        ]
        motion["arm_layer_order"] = _arm_layer_order(motion["frames"])
        presentation["motion_id"] = motion_id
        presentation["overlays"] = {"reference_line": reference_line,
            "wrist_role": motion["event"]["punching_wrist_role"],
            "layer": "foreground",
            "trajectory_samples": (view.get("trajectory_straightness") or {}).get("samples", [])}
        presentation["graph"]["interaction"] = "scrub_by_timestamp_ms"
        motions[motion_id] = motion
        presentation = build_fixed_camera_path_presentation(
            presentation, motion, video_landmarks.get("frame_geometry"))
        presentations.append(presentation)
        presentations.append(build_maximum_deviation_presentation(presentation))
        from .wrist_speed import build_wrist_speed_presentation
        presentations.append(build_wrist_speed_presentation(
            presentation, motion, video_landmarks.get("frame_geometry"),
            upper_arm_length_m=upper_arm_length_m))
    bundle = {
        "schema_version": 2,
        "contract": "karate_measurement_presentation_v2",
        "content_key": "punch_path_straightness",
        "frame_geometry": video_landmarks.get("frame_geometry"),
        "rendering_responsibility": "application",
        "localization_responsibility": "application",
        "motions": motions,
        "presentations": presentations,
    }
    if body_measurements is not None:
        from .metric_scale import metric_bundle
        bundle['body_measurements'] = deepcopy(body_measurements)
        return metric_bundle(bundle)
    return bundle


def build_fixed_camera_path_presentation(
    source: dict[str, Any], motion: dict[str, Any], geometry: dict[str, Any] | None,
) -> dict[str, Any]:
    """Measure recorded wrist positions using the existing observed path window.

    Shoulder-relative diagnostics select the window only. Their distances must
    never be reused as fixed-camera measurements. Shared poses remain unchanged.
    """
    result = deepcopy(source)
    result["measurement_method_id"] = "fixed_camera_start_to_impact_wrist_path_v1"
    result["coordinate_reference"] = "fixed_analysis_camera"
    result["provenance"].update(
        coordinate_space="fixed_analysis_camera_output_units",
        window_source="motion_diagnostic_observed_path_samples",
        measurement_source="shared_motion_recorded_wrist_positions",
    )
    result["summary"] = {key: None for key in (
        "typical_deviation_rms_output_units", "maximum_deviation_output_units", "path_efficiency_ratio")}
    result["maximum_marker"] = None
    source_samples = source["graph"]["samples"]
    result["graph"]["samples"] = []
    result["overlays"] = {
        "coordinate_space": "fixed_analysis_camera_output_units",
        "reference_line": {"start": None, "end": None},
        "wrist_role": source["overlays"]["wrist_role"],
        "layer": "foreground", "trajectory_samples": [],
    }
    if result["availability"]["status"] != "available":
        return result
    try:
        width = geometry["analysis_frame"]["width_px"]
        height = geometry["analysis_frame"]["height_px"]
        scale = result["scale"]["analysis_pixels_per_output_unit"]
        if not all(math.isfinite(v) and v > 0 for v in (width, height, scale)):
            raise ValueError("invalid_camera_geometry_or_scale")
        if len(source_samples) < 2:
            raise ValueError("insufficient_camera_path_samples")
        frames = {(f["frame_number"], f["timestamp_ms"]): f for f in motion["frames"]}
        samples = []
        for sample in source_samples:
            identity = {k: sample[k] for k in ("frame_number", "timestamp_ms", "normalized_time_progress")}
            frame = frames[(sample["frame_number"], sample["timestamp_ms"])]
            wrist = frame["pose"][result["overlays"]["wrist_role"]]
            point = [wrist["x"] * width / scale, wrist["y"] * height / scale]
            if not all(math.isfinite(v) for v in point):
                raise ValueError("invalid_camera_wrist_position")
            samples.append({**identity, "camera_wrist": point})
        if any(b["timestamp_ms"] <= a["timestamp_ms"] for a, b in zip(samples, samples[1:])):
            raise ValueError("camera_path_timestamps_not_increasing")
        start, end = samples[0]["camera_wrist"], samples[-1]["camera_wrist"]
        dx, dy = end[0] - start[0], end[1] - start[1]
        distance = math.hypot(dx, dy)
        if distance <= 1e-9:
            raise ValueError("camera_path_endpoints_coincident")
        sign = -1 if dx >= 0 else 1
        graph = []
        for sample in samples:
            x, y = sample["camera_wrist"]
            graph.append({**{k: v for k, v in sample.items() if k != "camera_wrist"},
                          "signed_deviation_output_units": sign * (dx * (y - start[1]) - dy * (x - start[0])) / distance})
        values = [s["signed_deviation_output_units"] for s in graph]
        travelled = sum(math.dist(a["camera_wrist"], b["camera_wrist"]) for a, b in zip(samples, samples[1:]))
        result["summary"].update(
            typical_deviation_rms_output_units=math.sqrt(sum(v * v for v in values) / len(values)),
            maximum_deviation_output_units=max(map(abs, values)),
            path_efficiency_ratio=distance / travelled,
        )
        result["graph"]["samples"] = graph
        result["overlays"].update(reference_line={"start": start, "end": end}, trajectory_samples=samples)
        maximum = build_maximum_deviation_presentation(result)
        result["availability"] = maximum["availability"]
        result["maximum_marker"] = maximum["maximum_marker"]
        result["maximum_selection"] = maximum["maximum_selection"]
    except (KeyError, TypeError, ValueError, IndexError) as error:
        result["availability"].update(status="unavailable", reason=str(error))
    return result


def build_maximum_deviation_presentation(source: dict[str, Any]) -> dict[str, Any]:
    """Reuse measured path data; export the maximum's evidence for both views.

    Exact magnitude ties use earliest timestamp, then lowest frame number.
    No smoothing, interpolation, or alternate measurement window is introduced.
    """
    result = deepcopy(source)
    result["presentation_id"] = source["presentation_id"].replace("punch_path:", "punch_path_maximum:", 1)
    result["measurement_id"] = "punch_path_maximum_deviation"
    result["measurement_method_id"] = "maximum_absolute_wrist_deviation_v1"
    result["linked_content"] = {
        "visual_page_key": "punch_path_maximum_deviation.visual",
        "method_page_key": "punch_path_maximum_deviation.method",
    }
    result["maximum_marker"] = None
    result["provenance"]["source_path_method_id"] = source["measurement_method_id"]
    result["maximum_selection"] = "absolute_magnitude_then_earliest_timestamp_then_lowest_frame_v1"
    if result["availability"]["status"] != "available":
        return result
    try:
        samples = result["graph"]["samples"]
        trajectory = result["overlays"]["trajectory_samples"]
        value = result["summary"]["maximum_deviation_output_units"]
        if not samples or not math.isfinite(value) or value < 0:
            raise ValueError("maximum_samples_or_summary_invalid")
        if any(not math.isfinite(s["signed_deviation_output_units"]) for s in samples):
            raise ValueError("maximum_samples_or_summary_invalid")
        selected = min(samples, key=lambda s: (
            -abs(s["signed_deviation_output_units"]), s["timestamp_ms"], s["frame_number"]))
        if not math.isclose(value, abs(selected["signed_deviation_output_units"]), rel_tol=1e-9, abs_tol=1e-12):
            raise ValueError("maximum_summary_sample_mismatch")
        matches = [row for row in trajectory if
                   (row["timestamp_ms"], row["frame_number"]) ==
                   (selected["timestamp_ms"], selected["frame_number"])]
        if len(matches) != 1:
            raise ValueError("maximum_trajectory_sample_missing_or_ambiguous")
        if result.get("coordinate_reference") != "fixed_analysis_camera":
            raise ValueError("fixed_camera_reference_required")
        start, end = [trajectory[i]["camera_wrist"] for i in (0, -1)]
        point = matches[0]["camera_wrist"]
        if not all(math.isfinite(v) for p in (start, end, point) for v in p):
            raise ValueError("maximum_geometry_invalid")
        dx, dy = end[0] - start[0], end[1] - start[1]
        denominator = dx * dx + dy * dy
        if denominator <= 1e-18:
            raise ValueError("maximum_reference_line_degenerate")
        fraction = ((point[0] - start[0]) * dx + (point[1] - start[1]) * dy) / denominator
        projected = [start[0] + fraction * dx, start[1] + fraction * dy]
        if not math.isclose(math.dist(point, projected), value, rel_tol=1e-9, abs_tol=1e-12):
            raise ValueError("maximum_geometry_summary_mismatch")
        result["maximum_marker"] = {
            **selected,
            "absolute_deviation_output_units": value,
            "camera_wrist": point,
            "camera_reference_point": projected,
        }
    except (KeyError, TypeError, ValueError, IndexError) as error:
        result["availability"].update(status="unavailable", reason=str(error))
    return result


def _build_event_presentation(
    view: dict[str, Any],
    companion: dict[str, Any],
    landmark_frames: dict[int, dict[str, Any]],
) -> dict[str, Any]:
    trajectory = view.get("trajectory_straightness") or {}
    samples = trajectory.get("samples") or []
    side = view["side"]
    wrist_role = f"{side}_wrist"
    animation_frames = []
    for sample in samples:
        pose = _first_pose(landmark_frames.get(int(sample["frame_number"]), {}))
        animation_frames.append(
            {
                "frame_number": int(sample["frame_number"]),
                "timestamp_ms": int(sample["timestamp_ms"]),
                "normalized_time_progress": sample["normalized_time_progress"],
                "pose": _semantic_upper_body_pose(pose),
            }
        )

    raw_start, raw_end = _wrist_endpoints(animation_frames, wrist_role)
    display_sign = _screen_above_sign(raw_start, raw_end)
    graph_samples = [
        {
            "frame_number": int(sample["frame_number"]),
            "timestamp_ms": int(sample["timestamp_ms"]),
            "normalized_time_progress": sample["normalized_time_progress"],
            "signed_deviation_output_units": (
                sample["signed_line_deviation_shoulder_widths"] * display_sign
            ),
        }
        for sample in samples
    ]
    status = trajectory.get("status", "unavailable")
    unavailable_reason = trajectory.get("unavailable_reason")
    if status == "available" and any(frame["pose"] is None for frame in animation_frames):
        status = "partial"
        unavailable_reason = "pose_missing_for_one_or_more_measurement_samples"

    return {
        "presentation_id": f"punch_path:event:{view['event_index']}",
        "measurement_id": "punch_path_typical_deviation_rms",
        "measurement_method_id": trajectory.get(
            "method", "camera_plane_start_to_impact_wrist_path_v1"
        ),
        "event": {
            "event_index": int(view["event_index"]),
            "side": side,
            "punching_wrist_role": wrist_role,
        },
        "availability": {
            "status": status,
            "reason": unavailable_reason,
            "valid_path_sample_count": int(
                trajectory.get("valid_path_sample_count", len(samples))
            ),
        },
        "summary": {
            "typical_deviation_rms_output_units": trajectory.get(
                "rms_deviation_shoulder_widths"
            ),
            "maximum_deviation_output_units": trajectory.get(
                "maximum_absolute_deviation_shoulder_widths"
            ),
            "path_efficiency_ratio": trajectory.get("path_efficiency_ratio"),
        },
        "scale": companion.get("length_scale"),
        "animation": {
            "coordinate_space": "analysis_image_normalized",
            "coordinate_axes": {
                "x_positive": "screen_right",
                "y_positive": "screen_down",
                "z_order": "smaller_is_nearer_camera",
            },
            "crop": "head_to_hips",
            "head_rendering": "circle_from_head_center_and_radius",
            "torso_polygon_roles": [
                "left_shoulder",
                "right_shoulder",
                "right_hip",
                "left_hip",
            ],
            "arm_layer_order": _arm_layer_order(animation_frames),
            "reference_line": {"start": raw_start, "end": raw_end},
            "frames": animation_frames,
        },
        "graph": {
            "metric_id": "signed_wrist_deviation_from_straight_path",
            "output_unit": (companion.get("length_scale") or {}).get("output_unit"),
            "zero_meaning": "wrist_on_straight_start_to_impact_path",
            "positive_display_direction": "screen_above_reference_line",
            "interaction": "scrub_by_normalized_time_progress",
            "samples": graph_samples,
        },
        "linked_content": {
            "visual_page_key": "punch_path_straightness.visual",
            "method_page_key": "punch_path_straightness.method",
        },
        "provenance": {
            **(view.get("signal_provenance") or {}),
            "source": "motion_diagnostic_event_view",
        },
    }


def _first_pose(frame: dict[str, Any]) -> list[dict[str, Any]]:
    poses = frame.get("poses") or []
    if not poses:
        return []
    first = poses[0]
    return first if isinstance(first, list) else first.get("landmarks", [])


def _semantic_upper_body_pose(
    pose: list[dict[str, Any]],
) -> dict[str, Any] | None:
    by_index = {int(point["index"]): point for point in pose if "index" in point}
    required = [POSE_INDEX[role] for role in POSE_INDEX]
    if not all(index in by_index for index in required):
        return None

    points = {
        role: _point(by_index[index])
        for role, index in POSE_INDEX.items()
        if role not in {"nose", "left_ear", "right_ear"}
    }
    face = [by_index[POSE_INDEX[role]] for role in ("nose", "left_ear", "right_ear")]
    center_x = sum(point["x"] for point in face) / len(face)
    center_y = sum(point["y"] for point in face) / len(face)
    radius = max(
        math.hypot(point["x"] - center_x, point["y"] - center_y)
        for point in face
    )
    points["head_center"] = {
        "x": center_x,
        "y": center_y,
        "visibility": min(float(point.get("visibility", 0.0)) for point in face),
    }
    points["head_radius"] = radius
    return points


def _point(value: dict[str, Any]) -> dict[str, float]:
    return {
        "x": float(value["x"]),
        "y": float(value["y"]),
        "z": float(value.get("z", 0.0)),
        "visibility": float(value.get("visibility", 0.0)),
    }


def _wrist_endpoints(
    frames: list[dict[str, Any]], wrist_role: str
) -> tuple[dict[str, float] | None, dict[str, float] | None]:
    available = [
        frame["pose"][wrist_role]
        for frame in frames
        if frame["pose"] is not None
    ]
    if not available:
        return None, None
    return available[0], available[-1]


def _screen_above_sign(
    start: dict[str, float] | None, end: dict[str, float] | None
) -> float:
    if start is None or end is None:
        return 1.0
    return -1.0 if end["x"] >= start["x"] else 1.0


def _arm_layer_order(frames: list[dict[str, Any]]) -> list[str]:
    depths: dict[str, list[float]] = {"left": [], "right": []}
    # Image-space presentation intentionally avoids exporting MediaPipe indices.
    # If depth is not retained in the semantic contract, the event side is a
    # deterministic fallback and applications may override it from setup data.
    for frame in frames:
        pose = frame.get("pose")
        if pose is None:
            continue
        for side in ("left", "right"):
            shoulder = pose[f"{side}_shoulder"]
            if shoulder["visibility"] > 0:
                depths[side].append(shoulder["z"])
    front = min(depths, key=lambda side: median(depths[side]) if depths[side] else math.inf)
    back = "right" if front == "left" else "left"
    return [f"{back}_arm", "torso", f"{front}_arm", "trajectory_overlay"]
