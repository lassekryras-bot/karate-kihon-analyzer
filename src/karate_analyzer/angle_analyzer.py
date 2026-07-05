"""Pure angle analysis utilities for karate technique scoring."""

from __future__ import annotations

from dataclasses import dataclass
from math import atan2, degrees


@dataclass(frozen=True)
class Point2D:
    """A two-dimensional point."""

    x: float
    y: float


@dataclass(frozen=True)
class ScoreResult:
    """Result of comparing an observed punch angle to its ideal angle."""

    deviation_degrees: float
    absolute_deviation_degrees: float
    classification: str
    direction: str


def calculate_angle_degrees(origin: Point2D, target: Point2D) -> float:
    """Calculate the angle from ``origin`` to ``target`` in degrees."""

    return degrees(atan2(target.y - origin.y, target.x - origin.x))


def _normalize_deviation_degrees(deviation_degrees: float) -> float:
    """Normalize an angle deviation to the range [-180, 180]."""

    return (deviation_degrees + 180) % 360 - 180


def _classify_deviation(absolute_deviation_degrees: float) -> str:
    """Classify a deviation using Jodan punch scoring thresholds."""

    if absolute_deviation_degrees <= 3:
        return "perfect"
    if absolute_deviation_degrees <= 7:
        return "good"
    if absolute_deviation_degrees <= 12:
        return "acceptable"
    return "miss"


def _direction_for_deviation(deviation_degrees: float) -> str:
    """Return the punch direction error for a normalized deviation."""

    if abs(deviation_degrees) <= 0.5:
        return "on_target"
    if deviation_degrees > 0:
        return "too_high"
    return "too_low"


def analyze_jodan_deviation(
    shoulder: Point2D,
    chin: Point2D,
    wrist: Point2D,
) -> ScoreResult:
    """Analyze Jodan punch angle deviation from shoulder/chin/wrist points.

    The ideal angle is the shoulder-to-chin line, while the actual angle is the
    shoulder-to-wrist line. Positive normalized deviation is treated as too
    high; negative normalized deviation is treated as too low.
    """

    ideal_angle = calculate_angle_degrees(shoulder, chin)
    actual_angle = calculate_angle_degrees(shoulder, wrist)
    deviation = _normalize_deviation_degrees(actual_angle - ideal_angle)
    absolute_deviation = abs(deviation)

    return ScoreResult(
        deviation_degrees=deviation,
        absolute_deviation_degrees=absolute_deviation,
        classification=_classify_deviation(absolute_deviation),
        direction=_direction_for_deviation(deviation),
    )
