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
