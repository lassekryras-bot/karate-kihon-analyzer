"""App-facing punch-path presentation data.

The analyzer owns measurement meaning and provenance. Applications own drawing,
interaction, localization, spoken copy, and coaching presentation.
"""

from __future__ import annotations

import math
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
    return {
        "schema_version": 1,
        "contract": "karate_measurement_presentation_v1",
        "content_key": "punch_path_straightness",
        "frame_geometry": video_landmarks.get("frame_geometry"),
        "rendering_responsibility": "application",
        "localization_responsibility": "application",
        "presentations": [
            _build_event_presentation(view, diagnostic_companion, landmark_frames)
            for view in views
        ],
    }


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
