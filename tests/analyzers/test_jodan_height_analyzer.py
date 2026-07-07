from __future__ import annotations

import pytest

from karate_analyzer.analyzers.jodan_height_analyzer import (
    analyze_jodan_height,
    analyze_strike_event_jodan_height,
    attach_jodan_height_analysis,
)


def test_impact_point_within_tolerance_band_is_good() -> None:
    result = analyze_jodan_height(
        impact_point=_point(0.70, 0.205),
        shoulder_point=_point(0.62, 0.42),
        jodan_reference=_point(0.50, 0.20),
        image_height=1000,
    )

    assert result.status == "good"
    assert result.tolerance_px == pytest.approx(37.6, abs=0.1)
    assert result.vertical_offset_px == pytest.approx(5.0)
    assert result.message == "Jodan height looks good."


def test_impact_point_below_target_band_is_too_low_in_image_coordinates() -> None:
    result = analyze_jodan_height(
        impact_point=_point(0.70, 0.30),
        shoulder_point=_point(0.62, 0.42),
        jodan_reference=_point(0.50, 0.20),
    )

    assert result.status == "too_low"
    assert result.vertical_offset_px is not None and result.vertical_offset_px > 0
    assert result.message == "Punch is too low for Jodan."


def test_impact_point_above_target_band_is_too_high_in_image_coordinates() -> None:
    result = analyze_jodan_height(
        impact_point=_point(0.70, 0.10),
        shoulder_point=_point(0.62, 0.42),
        jodan_reference=_point(0.50, 0.20),
    )

    assert result.status == "too_high"
    assert result.vertical_offset_px is not None and result.vertical_offset_px < 0
    assert result.message == "Punch is too high for Jodan."


def test_missing_impact_point_or_jodan_reference_is_unknown() -> None:
    missing_impact_point = analyze_jodan_height(
        impact_point=None,
        shoulder_point=_point(0.62, 0.42),
        jodan_reference=_point(0.50, 0.20),
    )
    missing_reference = analyze_jodan_height(
        impact_point=_point(0.70, 0.20),
        shoulder_point=_point(0.62, 0.42),
        jodan_reference=None,
    )

    assert missing_impact_point.status == "unknown"
    assert missing_reference.status == "unknown"
    assert missing_reference.message == "Could not evaluate Jodan height."


def test_low_confidence_data_is_unknown() -> None:
    result = analyze_jodan_height(
        impact_point=_point(0.70, 0.20, visibility=0.2),
        shoulder_point=_point(0.62, 0.42),
        jodan_reference=_point(0.50, 0.20),
    )

    assert result.status == "unknown"


def test_event_helpers_attach_analysis_without_mediapipe_indices() -> None:
    event = {
        "observed_side": "right",
        "impact_point": _point(0.70, 0.30),
        "wrist": _point(0.70, 0.90),
        "shoulder": _point(0.62, 0.42),
        "jodan_reference": _point(0.50, 0.20),
    }

    assert analyze_strike_event_jodan_height(event)["status"] == "too_low"
    enriched = attach_jodan_height_analysis(event)

    assert enriched["analysis"]["jodan_height"]["status"] == "too_low"
    assert "analysis" not in event


def test_fallback_tolerance_is_based_on_image_height_when_body_scale_is_too_small() -> (
    None
):
    result = analyze_jodan_height(
        impact_point=_point(0.50, 0.25),
        shoulder_point=_point(0.50, 0.20),
        jodan_reference=_point(0.50, 0.20),
        image_height=200,
    )

    assert result.status == "too_low"
    assert result.tolerance_px == pytest.approx(8.0)


def _point(x: float, y: float, visibility: float = 0.95) -> dict[str, float]:
    return {"x": x, "y": y, "visibility": visibility}


def test_wrist_on_event_does_not_affect_jodan_height_result() -> None:
    event = {
        "impact_point": _point(0.70, 0.20),
        "wrist": _point(0.70, 0.90),
        "shoulder": _point(0.62, 0.42),
        "jodan_reference": _point(0.50, 0.20),
    }

    assert analyze_strike_event_jodan_height(event)["status"] == "good"


def test_ideal_endpoint_uses_same_punch_length_on_jodan_height() -> None:
    result = analyze_jodan_height(
        impact_point=_point(0.80, 0.70),
        shoulder_point=_point(0.20, 0.70),
        jodan_reference=_point(0.40, 0.40),
    )

    assert result.actual_line_start == _point(0.20, 0.70)
    assert result.actual_line_end == _point(0.80, 0.70)
    assert result.ideal_line_start == _point(0.20, 0.70)
    assert result.ideal_line_end is not None
    assert result.ideal_line_end["y"] == pytest.approx(0.40)
    actual_length = ((0.80 - 0.20) ** 2 + (0.70 - 0.70) ** 2) ** 0.5
    ideal_length = (
        (result.ideal_line_end["x"] - 0.20) ** 2
        + (result.ideal_line_end["y"] - 0.70) ** 2
    ) ** 0.5
    assert ideal_length == pytest.approx(actual_length)


def test_signed_angle_is_positive_when_actual_line_is_above_ideal_line() -> None:
    result = analyze_jodan_height(
        impact_point=_point(0.80, 0.30),
        shoulder_point=_point(0.20, 0.70),
        jodan_reference=_point(0.40, 0.40),
    )

    assert result.signed_angle_degrees is not None
    assert result.signed_angle_degrees > 0
    assert result.status == "too_high"


def test_signed_angle_is_negative_when_actual_line_is_below_ideal_line() -> None:
    result = analyze_jodan_height(
        impact_point=_point(0.80, 0.55),
        shoulder_point=_point(0.20, 0.70),
        jodan_reference=_point(0.40, 0.40),
    )

    assert result.signed_angle_degrees is not None
    assert result.signed_angle_degrees < 0
    assert result.status == "too_low"


def test_small_signed_angle_is_good_with_current_threshold() -> None:
    result = analyze_jodan_height(
        impact_point=_point(0.80, 0.39),
        shoulder_point=_point(0.20, 0.70),
        jodan_reference=_point(0.40, 0.40),
    )

    assert result.signed_angle_degrees == pytest.approx(0.9, abs=0.1)
    assert result.status == "good"
