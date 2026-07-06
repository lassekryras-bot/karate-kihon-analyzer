from __future__ import annotations

import pytest

from karate_analyzer.angle_analyzer import analyze_jodan_deviation
from karate_analyzer.impact_frame_selector import ExtensionSample, select_impact_frame
from karate_analyzer.punch_sequence import PunchSide, build_mvp_sequence
from karate_analyzer.synthetic_session import (
    SyntheticFrame,
    generate_synthetic_jodan_punch,
    generate_synthetic_mvp_session,
)


def impact_frame_for(frames: list[SyntheticFrame]) -> SyntheticFrame:
    impact = select_impact_frame(
        [
            ExtensionSample(
                frame_number=frame.frame_number,
                timestamp_seconds=frame.timestamp_seconds,
                extension=frame.extension,
            )
            for frame in frames
        ]
    )
    return next(frame for frame in frames if frame.frame_number == impact.frame_number)

def analyze_impact(frames: list[SyntheticFrame]):
    frame = impact_frame_for(frames)
    return analyze_jodan_deviation(frame.shoulder, frame.chin, frame.wrist)


def test_single_punch_returns_6_frames() -> None:
    frames = generate_synthetic_jodan_punch(1, PunchSide.RIGHT)

    assert len(frames) == 6


def test_single_punch_frame_numbers_increase() -> None:
    frames = generate_synthetic_jodan_punch(1, PunchSide.RIGHT, frame_start=12)

    assert [frame.frame_number for frame in frames] == [12, 13, 14, 15, 16, 17]


def test_single_punch_timestamps_increase() -> None:
    frames = generate_synthetic_jodan_punch(
        1,
        PunchSide.RIGHT,
        timestamp_start=1.5,
        frame_interval_seconds=0.25,
    )

    assert [frame.timestamp_seconds for frame in frames] == pytest.approx(
        [1.5, 1.75, 2.0, 2.25, 2.5, 2.75]
    )


def test_single_punch_max_extension_is_unique() -> None:
    frames = generate_synthetic_jodan_punch(1, PunchSide.RIGHT)
    max_extension = max(frame.extension for frame in frames)

    assert sum(frame.extension == max_extension for frame in frames) == 1


def test_single_punch_impact_frame_is_local_frame_4() -> None:
    frames = generate_synthetic_jodan_punch(1, PunchSide.RIGHT, frame_start=100)
    impact_frame = impact_frame_for(frames)

    assert impact_frame.frame_number == 103


def test_zero_deviation_analyzes_as_perfect_and_on_target() -> None:
    result = analyze_impact(generate_synthetic_jodan_punch(1, PunchSide.RIGHT, 0.0))

    assert result.classification == "perfect"
    assert result.direction == "on_target"


def test_positive_deviation_analyzes_as_too_high() -> None:
    result = analyze_impact(generate_synthetic_jodan_punch(1, PunchSide.RIGHT, 5.0))

    assert result.direction == "too_high"


def test_negative_deviation_analyzes_as_too_low() -> None:
    result = analyze_impact(generate_synthetic_jodan_punch(1, PunchSide.RIGHT, -5.0))

    assert result.direction == "too_low"


def test_full_mvp_session_contains_frames_for_10_punches() -> None:
    frames = generate_synthetic_mvp_session()

    assert {frame.punch_number for frame in frames} == set(range(1, 11))


def test_full_mvp_session_has_60_frames_total() -> None:
    frames = generate_synthetic_mvp_session()

    assert len(frames) == 60


def test_full_mvp_session_alternates_right_left_according_to_mvp_sequence() -> None:
    frames = generate_synthetic_mvp_session()
    sequence = build_mvp_sequence()

    for expected_punch in sequence:
        punch_sides = {
            frame.side for frame in frames if frame.punch_number == expected_punch.number
        }
        assert punch_sides == {expected_punch.side}


def test_full_mvp_session_expected_classifications_match_deviation_list() -> None:
    frames = generate_synthetic_mvp_session()
    expected_classifications = {
        1: "perfect",
        2: "good",
        3: "good",
        4: "acceptable",
        5: "acceptable",
        6: "miss",
        7: "miss",
        8: "perfect",
        9: "perfect",
        10: "perfect",
    }

    for punch_number, expected_classification in expected_classifications.items():
        punch_frames = [frame for frame in frames if frame.punch_number == punch_number]
        assert analyze_impact(punch_frames).classification == expected_classification
