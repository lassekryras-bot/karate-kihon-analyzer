"""Domain impact-point extraction from detected hand landmarks."""

from __future__ import annotations

import math
from typing import Any

INDEX_FINGER_MCP = 5
MIDDLE_FINGER_MCP = 9
DEFAULT_MIN_CONFIDENCE = 0.5


def calculate_impact_point(
    hand_landmarks: list[dict[str, Any]] | dict[Any, dict[str, Any]] | None,
    *,
    min_confidence: float = DEFAULT_MIN_CONFIDENCE,
) -> dict[str, Any] | None:
    """Return the first-two-knuckle midpoint for one detected hand."""

    landmarks = _landmarks_by_index(hand_landmarks)
    index_mcp = _point_payload(landmarks.get(INDEX_FINGER_MCP))
    middle_mcp = _point_payload(landmarks.get(MIDDLE_FINGER_MCP))
    if (
        index_mcp is None
        or middle_mcp is None
        or _confidence(index_mcp) < min_confidence
        or _confidence(middle_mcp) < min_confidence
    ):
        return None

    payload: dict[str, Any] = {
        "x": (index_mcp["x"] + middle_mcp["x"]) / 2,
        "y": (index_mcp["y"] + middle_mcp["y"]) / 2,
        "visibility": min(_confidence(index_mcp), _confidence(middle_mcp)),
        "source": "index_mcp_middle_mcp_midpoint",
        "landmark_indices": {
            "index_mcp": INDEX_FINGER_MCP,
            "middle_mcp": MIDDLE_FINGER_MCP,
        },
    }
    if index_mcp.get("z") is not None and middle_mcp.get("z") is not None:
        payload["z"] = (index_mcp["z"] + middle_mcp["z"]) / 2
    return payload


def match_hand_to_pose_wrist(
    hands: list[dict[str, Any]] | None,
    pose_wrist: dict[str, Any] | None,
    *,
    min_confidence: float = DEFAULT_MIN_CONFIDENCE,
) -> dict[str, Any] | None:
    """Select the detected hand closest to the striking pose wrist."""

    wrist = _point_payload(pose_wrist)
    if wrist is None or not hands:
        return None

    candidates = []
    for index, hand in enumerate(hands):
        landmarks = _hand_landmarks(hand)
        point = _matching_anchor(landmarks, min_confidence=min_confidence)
        if point is None:
            continue
        candidates.append(
            (math.hypot(point["x"] - wrist["x"], point["y"] - wrist["y"]), index, hand)
        )
    if not candidates:
        return None
    distance, index, hand = min(candidates, key=lambda item: (item[0], item[1]))
    return {
        "hand": hand,
        "matched_hand_index": index,
        "match_distance_to_pose_wrist": distance,
    }


def calculate_striking_hand_impact_point(
    hands: list[dict[str, Any]] | None,
    pose_wrist: dict[str, Any] | None,
    *,
    min_confidence: float = DEFAULT_MIN_CONFIDENCE,
) -> dict[str, Any] | None:
    """Match a detected hand to a pose wrist and return its impact point."""

    match = match_hand_to_pose_wrist(hands, pose_wrist, min_confidence=min_confidence)
    if match is None:
        return None
    point = calculate_impact_point(
        _hand_landmarks(match["hand"]), min_confidence=min_confidence
    )
    if point is None:
        return None
    hand = match["hand"]
    return {
        **point,
        "hand_match_strategy": "closest_hand_to_pose_wrist",
        "matched_hand_index": match["matched_hand_index"],
        "match_distance_to_pose_wrist": match["match_distance_to_pose_wrist"],
        **(
            {"handedness": hand.get("handedness")}
            if hand.get("handedness") is not None
            else {}
        ),
    }


def _hand_landmarks(hand: dict[str, Any] | list[dict[str, Any]] | None) -> Any:
    if isinstance(hand, dict):
        return (
            hand.get("landmarks")
            or hand.get("hand_landmarks")
            or hand.get("landmark")
            or []
        )
    return hand


def _matching_anchor(
    landmarks: Any, *, min_confidence: float
) -> dict[str, float] | None:
    by_index = _landmarks_by_index(landmarks)
    for index in (0, INDEX_FINGER_MCP, MIDDLE_FINGER_MCP):
        point = _point_payload(by_index.get(index))
        if point is not None and _confidence(point) >= min_confidence:
            return point
    return None


def _landmarks_by_index(landmarks: Any) -> dict[Any, dict[str, Any]]:
    if isinstance(landmarks, dict):
        return landmarks
    if not landmarks:
        return {}
    return {
        landmark.get("index", index): landmark
        for index, landmark in enumerate(landmarks)
    }


def _point_payload(point: dict[str, Any] | None) -> dict[str, float] | None:
    if not isinstance(point, dict):
        return None
    try:
        payload = {"x": float(point["x"]), "y": float(point["y"])}
        if point.get("z") is not None:
            payload["z"] = float(point["z"])
        if point.get("visibility") is not None:
            payload["visibility"] = float(point["visibility"])
        if point.get("presence") is not None:
            payload["presence"] = float(point["presence"])
        return payload
    except (KeyError, TypeError, ValueError):
        return None


def _confidence(point: dict[str, float]) -> float:
    return point.get("visibility", point.get("presence", 1.0))
