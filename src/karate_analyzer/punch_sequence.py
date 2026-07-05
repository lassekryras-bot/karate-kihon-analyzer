"""Pure punch sequence utilities for the locked MVP routine."""

from __future__ import annotations

from dataclasses import dataclass
from enum import StrEnum


class PunchSide(StrEnum):
    """Available punching sides for the MVP sequence."""

    LEFT = "LEFT"
    RIGHT = "RIGHT"


class PunchTarget(StrEnum):
    """Available punch targets for the MVP sequence."""

    JODAN = "JODAN"


@dataclass(frozen=True)
class ExpectedPunch:
    """A punch expected at a specific number in the MVP sequence."""

    number: int
    side: PunchSide
    target: PunchTarget


def expected_punch_for_number(number: int) -> ExpectedPunch:
    """Return the expected MVP punch for ``number``.

    Valid punch numbers are 1 through 10. Odd-numbered punches use the right
    arm, even-numbered punches use the left arm, and every punch targets Jodan.
    """

    if number < 1 or number > 10:
        raise ValueError("Punch number must be between 1 and 10")

    side = PunchSide.RIGHT if number % 2 == 1 else PunchSide.LEFT
    return ExpectedPunch(number=number, side=side, target=PunchTarget.JODAN)


def build_mvp_sequence() -> list[ExpectedPunch]:
    """Build the locked MVP sequence of 10 alternating Jodan punches."""

    return [expected_punch_for_number(number) for number in range(1, 11)]
