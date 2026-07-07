"""Domain-level Jodan punch height analysis."""

from __future__ import annotations

from dataclasses import asdict, dataclass
from typing import Any, Literal
import math

JodanHeightStatus = Literal["too_low", "good", "too_high", "unknown"]

_MIN_CONFIDENCE = 0.5
_MIN_BODY_SCALE = 1e-6
_BODY_SCALE_TOLERANCE_RATIO = 0.15
_FALLBACK_IMAGE_TOLERANCE_RATIO = 0.04
_MESSAGES: dict[JodanHeightStatus, str] = {
    "good": "Jodan height looks good.",
    "too_low": "Punch is too low for Jodan.",
    "too_high": "Punch is too high for Jodan.",
    "unknown": "Could not evaluate Jodan height.",
}


@dataclass(frozen=True)
class JodanHeightAnalysisResult:
    """Explainable result from comparing a fist impact point to Jodan height."""

    status: JodanHeightStatus
    impact_point: dict[str, float] | None
    target_point: dict[str, float] | None
    tolerance_px: float | None
    vertical_offset_px: float | None
    message: str
    actual_line_start: dict[str, float] | None = None
    actual_line_end: dict[str, float] | None = None
    ideal_line_start: dict[str, float] | None = None
    ideal_line_end: dict[str, float] | None = None
    signed_angle_degrees: float | None = None
    reference_source: str | None = None
    reference_confidence: str | None = None
    unknown_reason: str | None = None

    def to_dict(self) -> dict[str, Any]:
        """Return a JSON-serializable representation."""

        payload = asdict(self)
        optional_geometry_fields = (
            "actual_line_start",
            "actual_line_end",
            "ideal_line_start",
            "ideal_line_end",
            "signed_angle_degrees",
            "reference_source",
            "reference_confidence",
            "unknown_reason",
        )
        for field in optional_geometry_fields:
            if payload.get(field) is None:
                payload.pop(field)
        return payload


def analyze_jodan_height(
    *,
    impact_point: dict[str, Any] | None,
    shoulder_point: dict[str, Any] | None,
    jodan_reference: dict[str, Any] | None,
    image_height: int | float | None = None,
    min_confidence: float = _MIN_CONFIDENCE,
) -> JodanHeightAnalysisResult:
    """Classify Jodan punch height from domain points.

    The inputs are normalized domain points produced by the strike event and
    reference-model layers. This analyzer intentionally does not know about
    MediaPipe landmark indices or call MediaPipe directly.
    """

    impact = _point_payload(impact_point)
    shoulder = _point_payload(shoulder_point)
    target = _point_payload(jodan_reference)
    if (
        impact is None
        or shoulder is None
        or target is None
        or _confidence(impact) < min_confidence
        or _confidence(shoulder) < min_confidence
        or _confidence(target) < min_confidence
    ):
        return _result(
            "unknown",
            impact,
            target,
            None,
            None,
            actual_start=shoulder if impact is not None else None,
            actual_end=impact,
            reference=(
                jodan_reference if impact is not None and shoulder is not None else None
            ),
            unknown_reason=(
                "Missing or low-confidence shoulder, impact point, or Jodan reference."
                if impact is not None and shoulder is not None
                else None
            ),
        )

    height_scale = _height_scale(image_height)
    body_scale = math.hypot(shoulder["x"] - target["x"], shoulder["y"] - target["y"])
    if body_scale > _MIN_BODY_SCALE:
        tolerance = body_scale * _BODY_SCALE_TOLERANCE_RATIO
    else:
        tolerance = _FALLBACK_IMAGE_TOLERANCE_RATIO

    vertical_offset = impact["y"] - target["y"]
    ideal_endpoint = _ideal_endpoint_for_same_length(shoulder, impact, target["y"])
    if ideal_endpoint is None:
        if vertical_offset > tolerance:
            status = "too_low"
        elif vertical_offset < -tolerance:
            status = "too_high"
        else:
            status = "good"
        return _result(
            status,
            impact,
            target,
            tolerance * height_scale,
            vertical_offset * height_scale,
            actual_start=shoulder,
            actual_end=impact,
            reference=jodan_reference,
            unknown_reason="Actual punch length cannot reach the Jodan reference height.",
        )

    signed_angle = _signed_angle_degrees(shoulder, impact, ideal_endpoint)
    angle_tolerance = math.degrees(
        math.asin(min(1.0, tolerance / _distance(shoulder, impact)))
    )
    if signed_angle < -angle_tolerance:
        status: JodanHeightStatus = "too_low"
    elif signed_angle > angle_tolerance:
        status = "too_high"
    else:
        status = "good"

    return _result(
        status,
        impact,
        target,
        tolerance * height_scale,
        vertical_offset * height_scale,
        actual_start=shoulder,
        actual_end=impact,
        ideal_start=shoulder,
        ideal_end=ideal_endpoint,
        signed_angle_degrees=signed_angle,
        reference=jodan_reference,
    )


def analyze_strike_event_jodan_height(
    event: dict[str, Any], *, image_height: int | float | None = None
) -> dict[str, Any]:
    """Return a Jodan height analysis dictionary for one strike event."""

    return analyze_jodan_height(
        impact_point=event.get("impact_point"),
        shoulder_point=event.get("shoulder"),
        jodan_reference=event.get("jodan_reference"),
        image_height=image_height,
    ).to_dict()


def attach_jodan_height_analysis(
    event: dict[str, Any], *, image_height: int | float | None = None
) -> dict[str, Any]:
    """Return ``event`` with a fresh Jodan height analysis attached."""

    return {
        **event,
        "analysis": {
            **(event.get("analysis") or {}),
            "jodan_height": analyze_strike_event_jodan_height(
                event, image_height=image_height
            ),
        },
    }


def _result(
    status: JodanHeightStatus,
    impact: dict[str, float] | None,
    target: dict[str, float] | None,
    tolerance_px: float | None,
    vertical_offset_px: float | None,
    *,
    actual_start: dict[str, float] | None = None,
    actual_end: dict[str, float] | None = None,
    ideal_start: dict[str, float] | None = None,
    ideal_end: dict[str, float] | None = None,
    signed_angle_degrees: float | None = None,
    reference: dict[str, Any] | None = None,
    unknown_reason: str | None = None,
) -> JodanHeightAnalysisResult:
    return JodanHeightAnalysisResult(
        status=status,
        impact_point=impact,
        target_point=target,
        tolerance_px=tolerance_px,
        vertical_offset_px=vertical_offset_px,
        message=_MESSAGES[status],
        actual_line_start=actual_start,
        actual_line_end=actual_end,
        ideal_line_start=ideal_start,
        ideal_line_end=ideal_end,
        signed_angle_degrees=signed_angle_degrees,
        reference_source=_reference_text(reference, "source"),
        reference_confidence=_reference_text(reference, "confidence"),
        unknown_reason=unknown_reason,
    )


def _point_payload(point: dict[str, Any] | None) -> dict[str, float] | None:
    if not isinstance(point, dict):
        return None
    try:
        payload = {"x": float(point["x"]), "y": float(point["y"])}
        if point.get("visibility") is not None:
            payload["visibility"] = float(point["visibility"])
        return payload
    except (KeyError, TypeError, ValueError):
        return None


def _confidence(point: dict[str, float]) -> float:
    return point.get("visibility", 1.0)


def _height_scale(image_height: int | float | None) -> float:
    try:
        height = float(image_height) if image_height is not None else 1.0
    except (TypeError, ValueError):
        return 1.0
    return height if height > 0 else 1.0


def _ideal_endpoint_for_same_length(
    shoulder: dict[str, float], impact: dict[str, float], jodan_y: float
) -> dict[str, float] | None:
    punch_length = _distance(shoulder, impact)
    vertical_delta = jodan_y - shoulder["y"]
    remaining = (punch_length * punch_length) - (vertical_delta * vertical_delta)
    if remaining < -1e-12 or punch_length <= _MIN_BODY_SCALE:
        return None
    horizontal_delta = math.sqrt(max(0.0, remaining))
    direction = 1.0 if impact["x"] >= shoulder["x"] else -1.0
    return {"x": shoulder["x"] + (direction * horizontal_delta), "y": jodan_y}


def _signed_angle_degrees(
    shoulder: dict[str, float],
    actual_end: dict[str, float],
    ideal_end: dict[str, float],
) -> float:
    # Normalized image coordinates have y increasing downward. Convert to a
    # y-up vector space before measuring the sign: positive means the actual
    # punch line is above the ideal Jodan line, negative means below it.
    actual = (actual_end["x"] - shoulder["x"], shoulder["y"] - actual_end["y"])
    ideal = (ideal_end["x"] - shoulder["x"], shoulder["y"] - ideal_end["y"])
    cross = (ideal[0] * actual[1]) - (ideal[1] * actual[0])
    dot = (ideal[0] * actual[0]) + (ideal[1] * actual[1])
    return math.degrees(math.atan2(cross, dot))


def _distance(start: dict[str, float], end: dict[str, float]) -> float:
    return math.hypot(end["x"] - start["x"], end["y"] - start["y"])


def _reference_text(reference: dict[str, Any] | None, key: str) -> str | None:
    if not isinstance(reference, dict) or reference.get(key) is None:
        return None
    return str(reference[key])
