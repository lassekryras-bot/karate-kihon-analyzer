"""Deterministic synthetic karate kihon session data generators."""

from __future__ import annotations

from dataclasses import dataclass
from math import cos, radians, sin

from karate_analyzer.angle_analyzer import Point2D, ScoreResult, analyze_jodan_deviation
from karate_analyzer.impact_frame_selector import ExtensionSample, select_impact_frame
from karate_analyzer.punch_sequence import PunchSide, build_mvp_sequence


@dataclass(frozen=True)
class SyntheticFrame:
    """Synthetic pose and extension data captured for one video frame."""

    frame_number: int
    timestamp_seconds: float
    punch_number: int
    side: PunchSide
    shoulder: Point2D
    chin: Point2D
    wrist: Point2D
    extension: float


_EXTENSION_PATTERN = (0.20, 0.40, 0.70, 1.00, 0.85, 0.60)
_MVP_DEVIATIONS_DEGREES = {
    1: 0.0,
    2: 5.0,
    3: -5.0,
    4: 10.0,
    5: -10.0,
    6: 13.0,
    7: -13.0,
    8: 2.0,
    9: -2.0,
    10: 0.0,
}


def generate_synthetic_jodan_punch(
    punch_number: int,
    side: PunchSide,
    deviation_degrees: float = 0.0,
    frame_start: int = 0,
    timestamp_start: float = 0.0,
    frame_interval_seconds: float = 1 / 30,
) -> list[SyntheticFrame]:
    """Generate six deterministic frames for one synthetic Jodan punch.

    The local fourth frame has the unique maximum extension and therefore acts
    as the impact frame. With the fixed shoulder/chin coordinates, the Jodan
    ideal line is horizontal, so positive wrist angles are too high and negative
    wrist angles are too low.
    """

    shoulder = Point2D(x=0, y=0)
    chin = Point2D(x=1, y=0)
    angle_radians = radians(deviation_degrees)

    return [
        SyntheticFrame(
            frame_number=frame_start + index,
            timestamp_seconds=timestamp_start + (index * frame_interval_seconds),
            punch_number=punch_number,
            side=side,
            shoulder=shoulder,
            chin=chin,
            wrist=Point2D(
                x=extension * cos(angle_radians),
                y=extension * sin(angle_radians),
            ),
            extension=extension,
        )
        for index, extension in enumerate(_EXTENSION_PATTERN)
    ]


def generate_synthetic_mvp_session() -> list[SyntheticFrame]:
    """Generate deterministic synthetic frames for the locked 10-punch MVP drill."""

    frames: list[SyntheticFrame] = []
    next_frame_number = 0
    next_timestamp_seconds = 0.0
    frame_interval_seconds = 1 / 30

    for expected_punch in build_mvp_sequence():
        punch_frames = generate_synthetic_jodan_punch(
            punch_number=expected_punch.number,
            side=expected_punch.side,
            deviation_degrees=_MVP_DEVIATIONS_DEGREES[expected_punch.number],
            frame_start=next_frame_number,
            timestamp_start=next_timestamp_seconds,
            frame_interval_seconds=frame_interval_seconds,
        )
        frames.extend(punch_frames)
        next_frame_number += len(punch_frames)
        next_timestamp_seconds += len(punch_frames) * frame_interval_seconds

    return frames


def analyze_synthetic_impact_frame(frames: list[SyntheticFrame]) -> ScoreResult:
    """Select and analyze the impact frame from synthetic frames.

    This helper keeps this module wired to the same pure analysis primitives used
    by the production analyzer while avoiding any media or file dependencies.
    """

    impact_frame = select_impact_frame(
        [
            ExtensionSample(
                frame_number=frame.frame_number,
                timestamp_seconds=frame.timestamp_seconds,
                extension=frame.extension,
            )
            for frame in frames
        ]
    )
    selected_frame = next(
        frame for frame in frames if frame.frame_number == impact_frame.frame_number
    )
    return analyze_jodan_deviation(
        selected_frame.shoulder,
        selected_frame.chin,
        selected_frame.wrist,
    )
