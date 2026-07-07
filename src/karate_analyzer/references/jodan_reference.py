"""Chin-first Jodan reference resolution.

The Jodan target is the chin.  When the chin is hidden on an analysis frame,
this module projects a known chin/Jodan reference through vertical motion of a
rigid upper-head landmark cluster.  It intentionally does not use nose/mouth,
eye/nose, pose-only, or face-contour target fallbacks.
"""

from __future__ import annotations

from statistics import median
from typing import Any

from karate_analyzer.references.chin_reference import calculate_chin_reference

VALID_SOURCES = {
    "same_frame_chin",
    "backward_projected_chin",
    "previous_jodan_projected",
    "future_jodan_projected",
    "unknown",
}

# Stable-ish Face Mesh upper-head anchors. Avoid mouth/lower-jaw landmarks.
HEAD_CLUSTER_ANCHORS = (
    33,
    133,
    362,
    263,  # eye corners
    70,
    105,
    336,
    300,  # brows
    6,
    168,
    197,
    195,
    4,  # nose bridge / upper nose
    127,
    234,
    356,
    454,  # upper side face contour
)
MIN_ANCHOR_VISIBILITY = 0.5
MIN_MATCHED_ANCHORS = 3
MAX_ANCHOR_SPREAD_Y = 0.045
MAX_FRAME_OFFSET = 90


def calculate_jodan_reference(
    landmarks: list[dict[str, Any]] | dict[Any, Any] | None = None,
    *,
    chin_reference: dict[str, Any] | None = None,
    analysis_frame_number: int | None = None,
) -> dict[str, Any] | None:
    """Return a same-frame Jodan reference only when a chin is available."""

    del landmarks  # Pose/face approximation fallbacks are intentionally disabled.
    if not _valid_point(chin_reference):
        return None
    return _same_frame_payload(chin_reference, analysis_frame_number)


def resolve_jodan_references(
    raw_frames: list[dict[str, Any]], events: list[dict[str, Any]]
) -> list[dict[str, Any]]:
    """Attach chin/Jodan references to strike events using temporal projection."""

    frames_by_number = {frame.get("frame_number"): frame for frame in raw_frames}
    ordered_frames = sorted(
        raw_frames, key=lambda f: _sortable_frame(f.get("frame_number"))
    )
    frame_chins = {
        frame.get("frame_number"): calculate_chin_reference(_first_face(frame))
        for frame in ordered_frames
    }

    resolved: list[dict[str, Any]] = []
    pending: list[dict[str, Any]] = []
    previous_valid: dict[str, Any] | None = None

    for event in events:
        analysis_frame = event.get("analysis_frame_number")
        chin = frame_chins.get(analysis_frame)
        reference = calculate_jodan_reference(
            chin_reference=chin, analysis_frame_number=analysis_frame
        )
        if reference is None:
            reference = _nearest_backward_chin_projection(
                event, ordered_frames, frames_by_number, frame_chins
            )
        if reference is None and previous_valid is not None:
            reference = _project_reference(
                source_reference=previous_valid,
                source_frame=frames_by_number.get(
                    previous_valid.get("analysis_frame_number")
                    or previous_valid.get("source_frame_number")
                ),
                target_frame=frames_by_number.get(analysis_frame),
                analysis_frame_number=analysis_frame,
                source="previous_jodan_projected",
                direction="forward",
                reason="projected_previous_valid_jodan_reference",
                projected_from_chin=False,
            )
        if reference is None:
            reference = _unknown_reference(
                analysis_frame, "no_previous_valid_jodan_reference"
            )
            pending.append(event)

        event["chin_reference"] = chin
        event["jodan_reference"] = (
            None if reference.get("source") == "unknown" else reference
        )
        event["jodan_reference_status"] = reference
        if event["jodan_reference"] is not None:
            previous_valid = event["jodan_reference"]
        resolved.append(event)

    for event in pending:
        if event.get("jodan_reference") is not None:
            continue
        future = _future_projection(event, resolved, frames_by_number)
        if future is not None:
            event["jodan_reference"] = future
            event["jodan_reference_status"] = future
        else:
            event["jodan_reference_status"] = _unknown_reference(
                event.get("analysis_frame_number"), "no_future_valid_jodan_reference"
            )

    return resolved


def _same_frame_payload(
    chin: dict[str, Any], frame_number: int | None
) -> dict[str, Any]:
    return {
        "source": "same_frame_chin",
        "x": float(chin["x"]),
        "y": float(chin["y"]),
        "visibility": float(chin.get("visibility", 1.0)),
        "confidence": "high",
        "source_frame_number": frame_number,
        "analysis_frame_number": frame_number,
        "frame_offset": 0,
        "matched_head_anchor_count": None,
        "head_cluster_motion_y": 0.0,
        "projection_direction": "none",
        "reason": "chin_visible_on_analysis_frame",
        "used_landmarks": list(chin.get("used_landmarks", [])),
        "source_reference_source": chin.get("source"),
    }


def _nearest_backward_chin_projection(
    event, ordered_frames, frames_by_number, frame_chins
):
    analysis = event.get("analysis_frame_number")
    if analysis is None:
        return None
    start = event.get("start_frame", analysis)
    candidates = [
        f
        for f in ordered_frames
        if f.get("frame_number") is not None
        and int(start) <= int(f["frame_number"]) < int(analysis)
        and _valid_point(frame_chins.get(f.get("frame_number")))
    ]
    if not candidates:
        return None
    source_frame = max(candidates, key=lambda f: int(f["frame_number"]))
    source_chin = frame_chins[source_frame.get("frame_number")]
    source_ref = _same_frame_payload(source_chin, source_frame.get("frame_number"))
    return _project_reference(
        source_reference=source_ref,
        source_frame=source_frame,
        target_frame=frames_by_number.get(analysis),
        analysis_frame_number=analysis,
        source="backward_projected_chin",
        direction="forward",
        reason="projected_nearest_previous_visible_chin",
        projected_from_chin=True,
    )


def _future_projection(event, resolved, frames_by_number):
    analysis = event.get("analysis_frame_number")
    for future_event in resolved:
        ref = future_event.get("jodan_reference")
        if not ref or future_event is event:
            continue
        future_frame = future_event.get("analysis_frame_number")
        if (
            analysis is not None
            and future_frame is not None
            and int(future_frame) > int(analysis)
        ):
            return _project_reference(
                source_reference=ref,
                source_frame=frames_by_number.get(future_frame),
                target_frame=frames_by_number.get(analysis),
                analysis_frame_number=analysis,
                source="future_jodan_projected",
                direction="backward",
                reason="projected_future_valid_jodan_reference",
                projected_from_chin=ref.get("source") == "same_frame_chin",
            )
    return None


def _project_reference(
    *,
    source_reference,
    source_frame,
    target_frame,
    analysis_frame_number,
    source,
    direction,
    reason,
    projected_from_chin,
):
    if not _valid_point(source_reference) or not source_frame or not target_frame:
        return None
    source_num = source_frame.get("frame_number")
    if source_num is None or analysis_frame_number is None:
        return None
    offset = int(analysis_frame_number) - int(source_num)
    if abs(offset) > MAX_FRAME_OFFSET:
        return None
    motion = _head_cluster_motion_y(source_frame, target_frame)
    if motion is None:
        return None
    confidence = _projection_confidence(
        abs(offset), motion, projected_from_chin, source
    )
    return {
        "source": source,
        "x": float(source_reference["x"]),
        "y": float(source_reference["y"]) + motion["dy"],
        "visibility": min(
            float(source_reference.get("visibility", 1.0)), motion["visibility"]
        ),
        "confidence": confidence,
        "source_frame_number": source_num,
        "analysis_frame_number": analysis_frame_number,
        "frame_offset": offset,
        "matched_head_anchor_count": motion["count"],
        "head_cluster_motion_y": motion["dy"],
        "projection_direction": direction,
        "reason": reason,
        "used_landmarks": motion["used_landmarks"],
        "source_reference_source": source_reference.get("source"),
    }


def _head_cluster_motion_y(source_frame, target_frame):
    source_landmarks = _index_landmarks(_first_face(source_frame))
    target_landmarks = _index_landmarks(_first_face(target_frame))
    movements = []
    visibilities = []
    used = []
    for index in HEAD_CLUSTER_ANCHORS:
        a = _landmark_payload(source_landmarks.get(index))
        b = _landmark_payload(target_landmarks.get(index))
        if a and b:
            movements.append(b["y"] - a["y"])
            visibilities.append(min(a["visibility"], b["visibility"]))
            used.append(index)
    if len(movements) < MIN_MATCHED_ANCHORS:
        return None
    center = median(movements)
    inliers = [
        (m, v, i)
        for m, v, i in zip(movements, visibilities, used)
        if abs(m - center) <= MAX_ANCHOR_SPREAD_Y
    ]
    if len(inliers) < MIN_MATCHED_ANCHORS:
        return None
    inlier_movements = [m for m, _v, _i in inliers]
    if max(inlier_movements) - min(inlier_movements) > MAX_ANCHOR_SPREAD_Y:
        return None
    return {
        "dy": median(inlier_movements),
        "count": len(inliers),
        "visibility": min(v for _m, v, _i in inliers),
        "used_landmarks": [i for _m, _v, i in inliers],
    }


def _projection_confidence(frame_gap, motion, projected_from_chin, source):
    if source == "future_jodan_projected" or not projected_from_chin:
        return "low"
    if frame_gap <= 5 and motion["count"] >= 6:
        return "high"
    if frame_gap <= 30 and motion["count"] >= MIN_MATCHED_ANCHORS:
        return "medium"
    return "low"


def _unknown_reference(analysis_frame_number, reason):
    return {
        "source": "unknown",
        "x": None,
        "y": None,
        "visibility": None,
        "confidence": "unknown",
        "source_frame_number": None,
        "analysis_frame_number": analysis_frame_number,
        "frame_offset": None,
        "matched_head_anchor_count": 0,
        "head_cluster_motion_y": None,
        "projection_direction": "none",
        "reason": reason,
        "used_landmarks": [],
    }


def _first_face(frame):
    if not isinstance(frame, dict):
        return None
    faces = frame.get("faces") or frame.get("face_landmarks") or []
    if not faces:
        return None
    first = faces[0]
    if isinstance(first, dict):
        return first.get("landmarks", first)
    return first


def _index_landmarks(face_landmarks):
    if isinstance(face_landmarks, dict):
        items = face_landmarks.items()
    else:
        items = enumerate(face_landmarks or [])
    indexed = {}
    for position, landmark in items:
        if not isinstance(landmark, dict):
            continue
        try:
            index = int(landmark.get("index", position))
        except (TypeError, ValueError):
            continue
        indexed[index] = landmark
    return indexed


def _landmark_payload(landmark):
    if not isinstance(landmark, dict):
        return None
    try:
        visibility = float(landmark.get("visibility", landmark.get("presence", 1.0)))
        if visibility < MIN_ANCHOR_VISIBILITY:
            return None
        return {
            "x": float(landmark["x"]),
            "y": float(landmark["y"]),
            "visibility": visibility,
        }
    except (KeyError, TypeError, ValueError):
        return None


def _valid_point(point):
    if not isinstance(point, dict):
        return False
    try:
        return (
            point.get("x") is not None
            and point.get("y") is not None
            and float(point.get("visibility", 1.0)) >= MIN_ANCHOR_VISIBILITY
        )
    except (TypeError, ValueError):
        return False


def _sortable_frame(frame_number):
    return int(frame_number) if frame_number is not None else 10**12
