"""Tests for pure angle analyzer logic."""

from __future__ import annotations

from math import cos, radians, sin

import pytest

from karate_analyzer.angle_analyzer import (
    Point2D,
    analyze_jodan_deviation,
    calculate_angle_degrees,
)


def point_at_angle(angle_degrees: float) -> Point2D:
    """Return a unit point at ``angle_degrees`` from the origin."""

    angle_radians = radians(angle_degrees)
    return Point2D(x=cos(angle_radians), y=sin(angle_radians))


def analyze_with_angles(ideal_angle: float, actual_angle: float):
    """Analyze a synthetic punch using shoulder-origin angle fixtures."""

    shoulder = Point2D(x=0, y=0)
    return analyze_jodan_deviation(
        shoulder=shoulder,
        chin=point_at_angle(ideal_angle),
        wrist=point_at_angle(actual_angle),
    )


def test_calculate_angle_degrees_uses_origin_to_target_vector() -> None:
    assert calculate_angle_degrees(Point2D(x=1, y=1), Point2D(x=1, y=2)) == 90


def test_zero_deviation_is_perfect_and_on_target() -> None:
    result = analyze_with_angles(ideal_angle=30, actual_angle=30)

    assert result.deviation_degrees == pytest.approx(0)
    assert result.absolute_deviation_degrees == pytest.approx(0)
    assert result.classification == "perfect"
    assert result.direction == "on_target"


def test_positive_deviation_is_too_high() -> None:
    result = analyze_with_angles(ideal_angle=0, actual_angle=5)

    assert result.deviation_degrees == pytest.approx(5)
    assert result.classification == "good"
    assert result.direction == "too_high"


def test_negative_deviation_is_too_low() -> None:
    result = analyze_with_angles(ideal_angle=0, actual_angle=-5)

    assert result.deviation_degrees == pytest.approx(-5)
    assert result.classification == "good"
    assert result.direction == "too_low"


@pytest.mark.parametrize(
    ("actual_angle", "expected_classification"),
    [
        (3, "perfect"),
        (7, "good"),
        (12, "acceptable"),
        (12.1, "miss"),
    ],
)
def test_threshold_boundaries(actual_angle: float, expected_classification: str) -> None:
    result = analyze_with_angles(ideal_angle=0, actual_angle=actual_angle)

    assert result.absolute_deviation_degrees == pytest.approx(actual_angle)
    assert result.classification == expected_classification


def test_wraparound_normalization_near_positive_and_negative_180_degrees() -> None:
    result = analyze_with_angles(ideal_angle=179, actual_angle=-179)

    assert result.deviation_degrees == pytest.approx(2)
    assert result.absolute_deviation_degrees == pytest.approx(2)
    assert result.classification == "perfect"
    assert result.direction == "too_high"
