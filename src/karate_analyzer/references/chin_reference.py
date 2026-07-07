"""Face Mesh chin reference extraction for Jodan target height.

This module is the only place that knows MediaPipe Face Mesh lower-jaw indices.
Technique analyzers should consume the normalized ``chin_reference`` payload
rather than depending on raw Face Mesh landmark numbers.
"""

from __future__ import annotations

from typing import Any

PRIMARY_CHIN = 152
DIRECT_NEIGHBORS = (171, 148)
LOWER_JAW_WEIGHTS = {
    171: 3.0,
    148: 3.0,
    150: 2.0,
    149: 2.0,
    176: 1.0,
    145: 1.0,
    144: 1.0,
    166: 1.0,
}
_CHIN_NOTE = "Face Mesh lower-jaw chin reference for karate Jodan target height."


def calculate_chin_reference(face_landmarks: Any) -> dict[str, Any] | None:
    """Return a normalized chin reference from MediaPipe Face Mesh landmarks."""

    indexed_landmarks = _index_landmarks(face_landmarks)

    primary = _landmark_payload(indexed_landmarks.get(PRIMARY_CHIN))
    if primary is not None:
        return _reference_payload(
            source="face_mesh_chin_152",
            strategy="face_mesh_152_primary",
            used_landmarks=[PRIMARY_CHIN],
            x=primary["x"],
            y=primary["y"],
            z=primary.get("z"),
            visibility=primary["visibility"],
        )

    direct_points = [
        _landmark_payload(indexed_landmarks.get(index)) for index in DIRECT_NEIGHBORS
    ]
    if all(point is not None for point in direct_points):
        points = [point for point in direct_points if point is not None]
        return _reference_payload(
            source="face_mesh_chin_neighbors_171_148",
            strategy="face_mesh_lower_neighbor_midpoint",
            used_landmarks=list(DIRECT_NEIGHBORS),
            x=sum(point["x"] for point in points) / len(points),
            y=sum(point["y"] for point in points) / len(points),
            z=_average_optional_z(points),
            visibility=_min_visibility(points),
        )

    weighted_points = []
    used_landmarks = []
    for index, weight in LOWER_JAW_WEIGHTS.items():
        point = _landmark_payload(indexed_landmarks.get(index))
        if point is not None:
            weighted_points.append((point, weight))
            used_landmarks.append(index)
    if len(weighted_points) < 2:
        return None

    total_weight = sum(weight for _point, weight in weighted_points)
    points = [point for point, _weight in weighted_points]
    return _reference_payload(
        source="face_mesh_lower_jaw_weighted_average",
        strategy="face_mesh_lower_jaw_fallback",
        used_landmarks=used_landmarks,
        x=sum(point["x"] * weight for point, weight in weighted_points) / total_weight,
        y=sum(point["y"] * weight for point, weight in weighted_points) / total_weight,
        z=_weighted_optional_z(weighted_points),
        visibility=_min_visibility(points),
    )


def _index_landmarks(face_landmarks: Any) -> dict[int, Any]:
    if isinstance(face_landmarks, dict):
        indexed = {}
        for key, value in face_landmarks.items():
            try:
                indexed[int(key)] = value
            except (TypeError, ValueError):
                continue
        return indexed

    indexed = {}
    for position, landmark in enumerate(face_landmarks or []):
        if not isinstance(landmark, dict):
            continue
        try:
            index = int(landmark.get("index", position))
        except (TypeError, ValueError):
            continue
        indexed[index] = landmark
    return indexed


def _landmark_payload(landmark: Any) -> dict[str, float] | None:
    if not isinstance(landmark, dict):
        return None
    try:
        payload = {
            "x": float(landmark["x"]),
            "y": float(landmark["y"]),
            "visibility": _confidence(landmark),
        }
        if landmark.get("z") is not None:
            payload["z"] = float(landmark["z"])
        return payload
    except (KeyError, TypeError, ValueError):
        return None


def _confidence(landmark: dict[str, Any]) -> float:
    for key in ("visibility", "presence"):
        if landmark.get(key) is not None:
            return float(landmark[key])
    return 1.0


def _average_optional_z(points: list[dict[str, float]]) -> float | None:
    z_values = [point["z"] for point in points if "z" in point]
    return None if not z_values else sum(z_values) / len(z_values)


def _weighted_optional_z(points: list[tuple[dict[str, float], float]]) -> float | None:
    z_points = [(point, weight) for point, weight in points if "z" in point]
    if not z_points:
        return None
    total_weight = sum(weight for _point, weight in z_points)
    return sum(point["z"] * weight for point, weight in z_points) / total_weight


def _min_visibility(points: list[dict[str, float]]) -> float:
    return min(point["visibility"] for point in points)


def _reference_payload(
    *,
    source: str,
    strategy: str,
    used_landmarks: list[int],
    x: float,
    y: float,
    z: float | None,
    visibility: float,
) -> dict[str, Any]:
    payload: dict[str, Any] = {
        "x": x,
        "y": y,
        "visibility": visibility,
        "source": source,
        "strategy": strategy,
        "used_landmarks": used_landmarks,
        "notes": _CHIN_NOTE,
    }
    if z is not None:
        payload["z"] = z
    return payload
