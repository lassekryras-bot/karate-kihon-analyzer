"""Jodan guided session plan construction."""

from __future__ import annotations

from karate_app.guided_session.session_models import StrikePlan, StrikeSide

_JAPANESE_COUNTS = [
    "Ichi",
    "Ni",
    "San",
    "Shi",
    "Go",
    "Roku",
    "Shichi",
    "Hachi",
    "Ku",
    "Ju",
]


def create_jodan_session_plan() -> list[StrikePlan]:
    """Create the 10-strike alternating right/left Jodan session plan."""
    strikes: list[StrikePlan] = []
    for zero_based_index, count in enumerate(_JAPANESE_COUNTS):
        strike_index = zero_based_index + 1
        side = StrikeSide.RIGHT if zero_based_index % 2 == 0 else StrikeSide.LEFT
        strikes.append(
            StrikePlan(
                index=strike_index,
                japanese_count=count,
                expected_side=side,
                file_name=f"strike_{strike_index:03d}_{side.value}.mp4",
            )
        )
    return strikes
