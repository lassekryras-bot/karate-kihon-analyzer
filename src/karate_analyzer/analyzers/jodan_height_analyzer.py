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

    def to_dict(self) -> dict[str, Any]:
        """Return a JSON-serializable representation."""

        return asdict(self)


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
        return _result("unknown", impact, target, None, None)

    height_scale = _height_scale(image_height)
    body_scale = math.hypot(shoulder["x"] - target["x"], shoulder["y"] - target["y"])
    if body_scale > _MIN_BODY_SCALE:
        tolerance = body_scale * _BODY_SCALE_TOLERANCE_RATIO
    else:
        tolerance = _FALLBACK_IMAGE_TOLERANCE_RATIO

    vertical_offset = impact["y"] - target["y"]
    if vertical_offset > tolerance:
        status: JodanHeightStatus = "too_low"
    elif vertical_offset < -tolerance:
        status = "too_high"
    else:
        status = "good"

    return _result(
        status, impact, target, tolerance * height_scale, vertical_offset * height_scale
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
) -> JodanHeightAnalysisResult:
    return JodanHeightAnalysisResult(
        status=status,
        impact_point=impact,
        target_point=target,
        tolerance_px=tolerance_px,
        vertical_offset_px=vertical_offset_px,
        message=_MESSAGES[status],
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
