"""Experimental karate-specific Jodan reference calculation.

Architecture concept:

Landmark Layer
    Raw MediaPipe pose landmarks, represented here as plain dictionaries so this
    module does not need a MediaPipe dependency.
Body Reference Layer
    Interprets raw landmarks into useful body/head points, such as averaged eye
    and mouth references.
Karate Reference Layer
    Converts those body/head points into a karate-specific ``jodan_reference``.
Technique Analysis Layer
    Future analyzers consume ``jodan_reference`` rather than asking directly for
    anatomical landmarks such as the nose or chin.
Rendering Layer
    Future renderers can draw the karate references and feedback produced by the
    analysis layer.

The current strategy is intentionally small and experimental. It estimates an
approximate Jodan target reference; it is not a medical or anatomical chin
estimate and should not be treated as one.
"""

from __future__ import annotations

from typing import Any

NOSE = 0
EYE_LANDMARKS = (1, 2, 3, 4, 5, 6)
MOUTH_LANDMARKS = (9, 10)
_EXPERIMENTAL_NOTE = (
    "Approximate experimental Jodan target reference for karate analysis; "
    "not a medical or anatomical chin estimate."
)


def calculate_jodan_reference(
    landmarks: list[dict[str, Any]] | dict[Any, Any],
    *,
    chin_reference: dict[str, Any] | None = None,
) -> dict[str, Any] | None:
    """Return an experimental karate Jodan target reference.

    A normalized Face Mesh chin reference is preferred when supplied. Otherwise,
    the pose-only fallback projects from the averaged eye reference through the
    nose by the same vector length. If eye landmarks are unavailable, the
    fallback projects from the nose toward the averaged mouth reference. If only
    the nose is usable, the nose is returned as a low-confidence fallback.
    """

    chin_payload = _chin_reference_payload(chin_reference)
    if chin_payload is not None:
        return chin_payload

    indexed_landmarks = _index_landmarks(landmarks)
    nose = _landmark_payload(indexed_landmarks.get(NOSE))
    eye_points = _available_landmarks(indexed_landmarks, EYE_LANDMARKS)
    mouth_points = _available_landmarks(indexed_landmarks, MOUTH_LANDMARKS)

    if nose is not None and eye_points:
        eye_reference = _average_point(eye_points)
        return _reference_payload(
            source="eye_nose_projection",
            x=nose["x"] + (nose["x"] - eye_reference["x"]),
            y=nose["y"] + (nose["y"] - eye_reference["y"]),
            visibility=_min_visibility([*eye_points, nose]),
            confidence="experimental",
            used_landmarks=[*_valid_indices(indexed_landmarks, EYE_LANDMARKS), NOSE],
        )

    if nose is not None and mouth_points:
        mouth_reference = _average_point(mouth_points)
        return _reference_payload(
            source="nose_mouth_projection",
            x=nose["x"] + (mouth_reference["x"] - nose["x"]),
            y=nose["y"] + (mouth_reference["y"] - nose["y"]),
            visibility=_min_visibility([nose, *mouth_points]),
            confidence="fallback",
            used_landmarks=[NOSE, *_valid_indices(indexed_landmarks, MOUTH_LANDMARKS)],
        )

    if nose is not None:
        return _reference_payload(
            source="nose",
            x=nose["x"],
            y=nose["y"],
            visibility=nose["visibility"],
            confidence="low",
            used_landmarks=[NOSE],
        )

    return None


def _index_landmarks(landmarks: list[dict[str, Any]] | dict[Any, Any]) -> dict[int, Any]:
    if isinstance(landmarks, dict):
        indexed = {}
        for key, value in landmarks.items():
            try:
                indexed[int(key)] = value
            except (TypeError, ValueError):
                continue
        return indexed

    indexed = {}
    for landmark in landmarks or []:
        if not isinstance(landmark, dict):
            continue
        try:
            indexed[int(landmark["index"])] = landmark
        except (KeyError, TypeError, ValueError):
            continue
    return indexed


def _available_landmarks(landmarks: dict[int, Any], indices: tuple[int, ...]) -> list[dict[str, float]]:
    points = []
    for index in indices:
        point = _landmark_payload(landmarks.get(index))
        if point is not None:
            points.append(point)
    return points


def _valid_indices(landmarks: dict[int, Any], indices: tuple[int, ...]) -> list[int]:
    return [index for index in indices if _landmark_payload(landmarks.get(index)) is not None]


def _landmark_payload(landmark: Any) -> dict[str, float] | None:
    if not isinstance(landmark, dict):
        return None
    try:
        return {
            "x": float(landmark["x"]),
            "y": float(landmark["y"]),
            "visibility": float(landmark.get("visibility", 0.0)),
        }
    except (KeyError, TypeError, ValueError):
        return None


def _average_point(points: list[dict[str, float]]) -> dict[str, float]:
    return {
        "x": sum(point["x"] for point in points) / len(points),
        "y": sum(point["y"] for point in points) / len(points),
        "visibility": _min_visibility(points),
    }


def _min_visibility(points: list[dict[str, float]]) -> float:
    return min(point["visibility"] for point in points)


def _reference_payload(
    *,
    source: str,
    x: float,
    y: float,
    visibility: float,
    confidence: str,
    used_landmarks: list[int],
) -> dict[str, Any]:
    return {
        "source": source,
        "x": x,
        "y": y,
        "visibility": visibility,
        "confidence": confidence,
        "used_landmarks": used_landmarks,
        "notes": _EXPERIMENTAL_NOTE,
    }


def _chin_reference_payload(chin_reference: dict[str, Any] | None) -> dict[str, Any] | None:
    if not isinstance(chin_reference, dict):
        return None
    try:
        return {
            "x": float(chin_reference["x"]),
            "y": float(chin_reference["y"]),
            "visibility": float(chin_reference.get("visibility", 1.0)),
            "source": "face_mesh_chin_reference",
            "strategy": "jodan_target_from_chin_reference",
            "target_zone": "jodan_lower_face_chin_height",
            "chin_reference_source": chin_reference["source"],
            "used_references": ["chin_reference"],
            "notes": "Jodan target height derived from Face Mesh chin / lower jaw reference.",
        }
    except (KeyError, TypeError, ValueError):
        return None
