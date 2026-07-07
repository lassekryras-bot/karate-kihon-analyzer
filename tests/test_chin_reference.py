from __future__ import annotations

import pytest

from karate_analyzer.chin_reference import calculate_chin_reference


def test_uses_landmark_152_when_present() -> None:
    reference = calculate_chin_reference([_landmark(152, 0.51, 0.38, z=-0.02)])

    assert reference is not None
    assert reference["source"] == "face_mesh_chin_152"
    assert reference["strategy"] == "face_mesh_152_primary"
    assert reference["x"] == pytest.approx(0.51)
    assert reference["y"] == pytest.approx(0.38)
    assert reference["z"] == pytest.approx(-0.02)
    assert reference["visibility"] == pytest.approx(1.0)
    assert reference["used_landmarks"] == [152]


def test_falls_back_to_midpoint_of_171_and_148() -> None:
    reference = calculate_chin_reference(
        [_landmark(171, 0.40, 0.30, visibility=0.8), _landmark(148, 0.60, 0.50, visibility=0.7)]
    )

    assert reference is not None
    assert reference["source"] == "face_mesh_chin_neighbors_171_148"
    assert reference["strategy"] == "face_mesh_lower_neighbor_midpoint"
    assert reference["x"] == pytest.approx(0.50)
    assert reference["y"] == pytest.approx(0.40)
    assert reference["visibility"] == pytest.approx(0.7)
    assert reference["used_landmarks"] == [171, 148]


def test_falls_back_to_weighted_lower_jaw_average() -> None:
    reference = calculate_chin_reference(
        [
            _landmark(171, 0.30, 0.30),
            _landmark(150, 0.60, 0.60),
            _landmark(176, 0.90, 0.90),
        ]
    )

    assert reference is not None
    assert reference["source"] == "face_mesh_lower_jaw_weighted_average"
    assert reference["strategy"] == "face_mesh_lower_jaw_fallback"
    assert reference["x"] == pytest.approx((0.30 * 3 + 0.60 * 2 + 0.90) / 6)
    assert reference["y"] == pytest.approx((0.30 * 3 + 0.60 * 2 + 0.90) / 6)
    assert reference["used_landmarks"] == [171, 150, 176]


def test_returns_none_when_fewer_than_two_usable_fallback_landmarks_exist() -> None:
    assert calculate_chin_reference([_landmark(171, 0.30, 0.30)]) is None


def test_handles_indexed_dictionaries() -> None:
    reference = calculate_chin_reference({"152": {"x": 0.51, "y": 0.38}})

    assert reference is not None
    assert reference["used_landmarks"] == [152]


def test_does_not_require_468_landmarks() -> None:
    landmarks = [_landmark(index, 0.1, 0.2) for index in range(10)]

    assert calculate_chin_reference(landmarks) is None


def test_uses_presence_when_visibility_is_missing() -> None:
    reference = calculate_chin_reference([{"index": 152, "x": 0.5, "y": 0.4, "presence": 0.66}])

    assert reference is not None
    assert reference["visibility"] == pytest.approx(0.66)


def _landmark(
    index: int,
    x: float,
    y: float,
    *,
    visibility: float | None = None,
    z: float | None = None,
) -> dict[str, float | int]:
    landmark: dict[str, float | int] = {"index": index, "x": x, "y": y}
    if visibility is not None:
        landmark["visibility"] = visibility
    if z is not None:
        landmark["z"] = z
    return landmark
