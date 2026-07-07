from __future__ import annotations

import pytest

from karate_analyzer.jodan_reference import calculate_jodan_reference


def test_eye_nose_projection_with_full_eye_landmarks_and_nose() -> None:
    landmarks = [
        _landmark(0, 0.50, 0.30, 0.90),
        _landmark(1, 0.40, 0.10, 0.80),
        _landmark(2, 0.45, 0.10, 0.70),
        _landmark(3, 0.50, 0.10, 0.60),
        _landmark(4, 0.55, 0.10, 0.85),
        _landmark(5, 0.60, 0.10, 0.75),
        _landmark(6, 0.50, 0.10, 0.65),
    ]

    reference = calculate_jodan_reference(landmarks)

    assert reference is not None
    assert reference["source"] == "eye_nose_projection"
    assert reference["x"] == pytest.approx(0.50)
    assert reference["y"] == pytest.approx(0.50)
    assert reference["visibility"] == pytest.approx(0.60)
    assert reference["confidence"] == "experimental"
    assert reference["used_landmarks"] == [1, 2, 3, 4, 5, 6, 0]
    assert "not a medical or anatomical chin estimate" in reference["notes"]


def test_eye_reference_averages_multiple_available_eye_points() -> None:
    reference = calculate_jodan_reference(
        {
            0: {"x": 0.50, "y": 0.30, "visibility": 0.90},
            1: {"x": 0.30, "y": 0.20, "visibility": 0.80},
            6: {"x": 0.70, "y": 0.10, "visibility": 0.70},
        }
    )

    assert reference is not None
    assert reference["source"] == "eye_nose_projection"
    assert reference["x"] == pytest.approx(0.50)
    assert reference["y"] == pytest.approx(0.45)
    assert reference["visibility"] == pytest.approx(0.70)
    assert reference["used_landmarks"] == [1, 6, 0]


def test_fallback_to_nose_mouth_projection_when_eyes_are_missing() -> None:
    reference = calculate_jodan_reference(
        [
            _landmark(0, 0.50, 0.30, 0.90),
            _landmark(9, 0.40, 0.60, 0.60),
            _landmark(10, 0.60, 0.70, 0.70),
        ]
    )

    assert reference is not None
    assert reference["source"] == "nose_mouth_projection"
    assert reference["x"] == pytest.approx(0.50)
    assert reference["y"] == pytest.approx(0.65)
    assert reference["visibility"] == pytest.approx(0.60)
    assert reference["confidence"] == "fallback"
    assert reference["used_landmarks"] == [0, 9, 10]


def test_fallback_to_nose_only() -> None:
    reference = calculate_jodan_reference([_landmark(0, 0.50, 0.30, 0.90)])

    assert reference == {
        "source": "nose",
        "x": 0.50,
        "y": 0.30,
        "visibility": 0.90,
        "confidence": "low",
        "used_landmarks": [0],
        "notes": (
            "Approximate experimental Jodan target reference for karate analysis; "
            "not a medical or anatomical chin estimate."
        ),
    }


def test_no_usable_head_landmarks_returns_none() -> None:
    assert calculate_jodan_reference([_landmark(11, 0.1, 0.2, 0.9)]) is None


def test_visibility_is_minimum_from_source_landmarks() -> None:
    reference = calculate_jodan_reference(
        [_landmark(0, 0.5, 0.3, 0.4), _landmark(2, 0.5, 0.1, 0.9)]
    )

    assert reference is not None
    assert reference["visibility"] == pytest.approx(0.4)


def _landmark(index: int, x: float, y: float, visibility: float) -> dict[str, float | int]:
    return {"index": index, "x": x, "y": y, "visibility": visibility}
