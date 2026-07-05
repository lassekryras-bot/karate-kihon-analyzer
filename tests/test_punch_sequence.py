"""Tests for pure MVP punch sequence logic."""

from __future__ import annotations

import pytest

from karate_analyzer.punch_sequence import (
    PunchSide,
    PunchTarget,
    build_mvp_sequence,
    expected_punch_for_number,
)


def test_sequence_length_is_10() -> None:
    sequence = build_mvp_sequence()

    assert len(sequence) == 10


def test_first_punch_is_right_jodan() -> None:
    punch = expected_punch_for_number(1)

    assert punch.number == 1
    assert punch.side == PunchSide.RIGHT
    assert punch.target == PunchTarget.JODAN


def test_second_punch_is_left_jodan() -> None:
    punch = expected_punch_for_number(2)

    assert punch.number == 2
    assert punch.side == PunchSide.LEFT
    assert punch.target == PunchTarget.JODAN


def test_tenth_punch_is_left_jodan() -> None:
    punch = expected_punch_for_number(10)

    assert punch.number == 10
    assert punch.side == PunchSide.LEFT
    assert punch.target == PunchTarget.JODAN


def test_all_targets_are_jodan() -> None:
    sequence = build_mvp_sequence()

    assert all(punch.target == PunchTarget.JODAN for punch in sequence)


@pytest.mark.parametrize("number", [0, -1, 11])
def test_invalid_punch_numbers_raise_value_error(number: int) -> None:
    with pytest.raises(ValueError):
        expected_punch_for_number(number)
