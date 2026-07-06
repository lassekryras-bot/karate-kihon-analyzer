"""Pure orchestration for analyzing complete karate kihon sessions."""

from __future__ import annotations

from collections import OrderedDict
from dataclasses import dataclass

from karate_analyzer.angle_analyzer import ScoreResult, analyze_jodan_deviation
from karate_analyzer.impact_frame_selector import ExtensionSample, select_impact_frame
from karate_analyzer.punch_sequence import ExpectedPunch, build_mvp_sequence
from karate_analyzer.synthetic_session import SyntheticFrame


@dataclass(frozen=True)
class PunchAnalysis:
    """Analysis details for one punch in a session."""

    punch_number: int
    expected: ExpectedPunch
    impact_frame_number: int
    impact_timestamp_seconds: float
    score: ScoreResult


@dataclass(frozen=True)
class SessionAnalysis:
    """Analysis details for every punch in a session."""

    punches: list[PunchAnalysis]


def analyze_session(frames: list[SyntheticFrame]) -> SessionAnalysis:
    """Analyze a complete synthetic MVP karate kihon session.

    Frames are grouped by punch number, each punch's impact frame is selected
    from maximum extension, and that selected frame is scored with the Jodan
    angle analyzer. The returned punch analyses are ordered by the locked MVP
    sequence.
    """

    if not frames:
        raise ValueError("At least one frame is required")

    expected_sequence = build_mvp_sequence()
    expected_by_number = {expected.number: expected for expected in expected_sequence}
    grouped_frames = _group_frames_by_contiguous_punch(frames)
    _validate_punch_numbers(grouped_frames, expected_by_number)

    return SessionAnalysis(
        punches=[
            _analyze_punch(expected, grouped_frames[expected.number])
            for expected in expected_sequence
        ]
    )


def _group_frames_by_contiguous_punch(
    frames: list[SyntheticFrame],
) -> OrderedDict[int, list[SyntheticFrame]]:
    """Group frames while rejecting repeated punch groups."""

    grouped_frames: OrderedDict[int, list[SyntheticFrame]] = OrderedDict()
    closed_punch_numbers: set[int] = set()
    current_punch_number: int | None = None

    for frame in frames:
        if frame.punch_number != current_punch_number:
            if current_punch_number is not None:
                closed_punch_numbers.add(current_punch_number)
            if frame.punch_number in closed_punch_numbers:
                raise ValueError(
                    f"Punch number {frame.punch_number} appears in multiple frame groups"
                )
            current_punch_number = frame.punch_number
            grouped_frames.setdefault(frame.punch_number, [])

        grouped_frames[frame.punch_number].append(frame)

    return grouped_frames


def _validate_punch_numbers(
    grouped_frames: OrderedDict[int, list[SyntheticFrame]],
    expected_by_number: dict[int, ExpectedPunch],
) -> None:
    """Validate that a session contains exactly the expected MVP punch numbers."""

    actual_numbers = set(grouped_frames)
    expected_numbers = set(expected_by_number)

    missing_numbers = sorted(expected_numbers - actual_numbers)
    if missing_numbers:
        raise ValueError(f"Missing punch numbers: {missing_numbers}")

    unexpected_numbers = sorted(actual_numbers - expected_numbers)
    if unexpected_numbers:
        raise ValueError(f"Unexpected punch numbers: {unexpected_numbers}")

    empty_numbers = [
        number for number, punch_frames in grouped_frames.items() if not punch_frames
    ]
    if empty_numbers:
        raise ValueError(f"Punches contain zero frames: {empty_numbers}")


def _analyze_punch(
    expected: ExpectedPunch,
    punch_frames: list[SyntheticFrame],
) -> PunchAnalysis:
    """Analyze one punch's frames against its expected sequence entry."""

    if not punch_frames:
        raise ValueError(f"Punch {expected.number} contains zero frames")

    impact_frame = select_impact_frame(
        [
            ExtensionSample(
                frame_number=frame.frame_number,
                timestamp_seconds=frame.timestamp_seconds,
                extension=frame.extension,
            )
            for frame in punch_frames
        ]
    )
    selected_frame = next(
        frame for frame in punch_frames if frame.frame_number == impact_frame.frame_number
    )
    score = analyze_jodan_deviation(
        selected_frame.shoulder,
        selected_frame.chin,
        selected_frame.wrist,
    )

    return PunchAnalysis(
        punch_number=expected.number,
        expected=expected,
        impact_frame_number=impact_frame.frame_number,
        impact_timestamp_seconds=impact_frame.timestamp_seconds,
        score=score,
    )
