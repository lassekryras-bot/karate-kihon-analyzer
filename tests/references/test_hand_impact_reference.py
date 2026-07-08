from __future__ import annotations

import pytest

from karate_analyzer.references.hand_impact_reference import (
    calculate_highest_hand_point,
    calculate_impact_point,
    calculate_striking_hand_impact_point,
)


def test_calculate_impact_point_midpoint_from_index_and_middle_mcp() -> None:
    point = calculate_impact_point([_lm(5, 0.20, 0.30), _lm(9, 0.40, 0.50)])

    assert point is not None
    assert point["x"] == pytest.approx(0.30)
    assert point["y"] == pytest.approx(0.40)
    assert point["source"] == "index_mcp_middle_mcp_midpoint"
    assert point["landmark_indices"] == {"index_mcp": 5, "middle_mcp": 9}


def test_missing_index_mcp_returns_none() -> None:
    assert calculate_impact_point([_lm(9, 0.40, 0.50)]) is None


def test_missing_middle_mcp_returns_none() -> None:
    assert calculate_impact_point([_lm(5, 0.20, 0.30)]) is None


def test_low_confidence_knuckle_returns_none() -> None:
    assert (
        calculate_impact_point([_lm(5, 0.20, 0.30, 0.49), _lm(9, 0.40, 0.50)]) is None
    )


def test_calculate_highest_hand_point_uses_lowest_y_visible_landmark() -> None:
    point = calculate_highest_hand_point(
        [_lm(0, 0.40, 0.50), _lm(8, 0.60, 0.20), _lm(12, 0.55, 0.25)]
    )

    assert point == {
        "x": 0.60,
        "y": 0.20,
        "visibility": 0.95,
        "source": "highest_visible_hand_landmark",
        "landmark_index": 8,
    }


def test_calculate_highest_hand_point_ignores_low_confidence_landmark() -> None:
    point = calculate_highest_hand_point(
        [_lm(8, 0.60, 0.20, 0.49), _lm(12, 0.55, 0.25)]
    )

    assert point is not None
    assert point["landmark_index"] == 12


def test_two_hands_choose_closest_to_pose_wrist() -> None:
    far = {"landmarks": [_lm(0, 0.90, 0.90), _lm(5, 0.90, 0.90), _lm(9, 0.94, 0.94)]}
    near = {"landmarks": [_lm(0, 0.20, 0.20), _lm(5, 0.25, 0.30), _lm(9, 0.35, 0.30)]}

    point = calculate_striking_hand_impact_point([far, near], {"x": 0.21, "y": 0.20})

    assert point is not None
    assert point["matched_hand_index"] == 1
    assert point["x"] == pytest.approx(0.30)
    assert point["hand_match_strategy"] == "closest_hand_to_pose_wrist"


def test_striking_hand_falls_back_to_highest_point_when_knuckles_missing() -> None:
    hand = {"landmarks": [_lm(0, 0.20, 0.20), _lm(8, 0.30, 0.10), _lm(12, 0.32, 0.15)]}

    point = calculate_striking_hand_impact_point([hand], {"x": 0.21, "y": 0.20})

    assert point is not None
    assert point["source"] == "highest_visible_hand_landmark"
    assert point["landmark_index"] == 8
    assert point["hand_match_strategy"] == "closest_hand_to_pose_wrist"


def test_handedness_metadata_is_not_required_for_matching() -> None:
    point = calculate_striking_hand_impact_point(
        [{"landmarks": [_lm(0, 0.10, 0.10), _lm(5, 0.20, 0.20), _lm(9, 0.30, 0.20)]}],
        {"x": 0.11, "y": 0.10},
    )

    assert point is not None
    assert "handedness" not in point


def _lm(
    index: int, x: float, y: float, visibility: float = 0.95
) -> dict[str, float | int]:
    return {"index": index, "x": x, "y": y, "visibility": visibility}
