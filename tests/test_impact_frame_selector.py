"""Tests for pure impact frame selection logic."""

from __future__ import annotations

import pytest

from karate_analyzer.impact_frame_selector import (
    ExtensionSample,
    select_impact_frame,
)


def test_selects_the_max_extension_frame() -> None:
    samples = [
        ExtensionSample(frame_number=10, timestamp_seconds=0.40, extension=0.50),
        ExtensionSample(frame_number=11, timestamp_seconds=0.44, extension=0.85),
        ExtensionSample(frame_number=12, timestamp_seconds=0.48, extension=0.70),
    ]

    impact_frame = select_impact_frame(samples)

    assert impact_frame.frame_number == 11
    assert impact_frame.timestamp_seconds == 0.44
    assert impact_frame.extension == 0.85


def test_returns_the_first_max_frame_if_there_is_a_plateau() -> None:
    samples = [
        ExtensionSample(frame_number=20, timestamp_seconds=0.80, extension=0.65),
        ExtensionSample(frame_number=21, timestamp_seconds=0.84, extension=0.90),
        ExtensionSample(frame_number=22, timestamp_seconds=0.88, extension=0.90),
        ExtensionSample(frame_number=23, timestamp_seconds=0.92, extension=0.72),
    ]

    impact_frame = select_impact_frame(samples)

    assert impact_frame.frame_number == 21
    assert impact_frame.timestamp_seconds == 0.84
    assert impact_frame.extension == 0.90


def test_raises_value_error_for_empty_sample_list() -> None:
    with pytest.raises(ValueError):
        select_impact_frame([])


def test_preserves_frame_number_and_timestamp() -> None:
    samples = [
        ExtensionSample(frame_number=101, timestamp_seconds=4.04, extension=0.10),
        ExtensionSample(frame_number=205, timestamp_seconds=8.20, extension=1.00),
    ]

    impact_frame = select_impact_frame(samples)

    assert impact_frame.frame_number == 205
    assert impact_frame.timestamp_seconds == 8.20
