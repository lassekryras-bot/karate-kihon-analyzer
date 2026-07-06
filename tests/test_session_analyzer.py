from __future__ import annotations

import pytest

from karate_analyzer.punch_sequence import build_mvp_sequence
from karate_analyzer.session_analyzer import _analyze_punch, analyze_session
from karate_analyzer.synthetic_session import generate_synthetic_mvp_session


def test_analyze_session_happy_path_returns_expected_punch_analyses() -> None:
    frames = generate_synthetic_mvp_session()
    analysis = analyze_session(frames)

    assert len(analysis.punches) == 10
    assert [punch.punch_number for punch in analysis.punches] == list(range(1, 11))
    assert [punch.expected for punch in analysis.punches] == build_mvp_sequence()
    assert [punch.impact_frame_number for punch in analysis.punches] == [
        3,
        9,
        15,
        21,
        27,
        33,
        39,
        45,
        51,
        57,
    ]
    assert [punch.score.classification for punch in analysis.punches] == [
        "perfect",
        "good",
        "good",
        "acceptable",
        "acceptable",
        "miss",
        "miss",
        "perfect",
        "perfect",
        "perfect",
    ]


def test_analyze_session_rejects_empty_session() -> None:
    with pytest.raises(ValueError, match="At least one frame"):
        analyze_session([])


def test_analyze_session_rejects_missing_punch() -> None:
    frames = [
        frame for frame in generate_synthetic_mvp_session() if frame.punch_number != 5
    ]

    with pytest.raises(ValueError, match=r"Missing punch numbers: \[5\]"):
        analyze_session(frames)


def test_analyze_session_rejects_duplicate_punch_groups() -> None:
    frames = generate_synthetic_mvp_session()
    malformed_frames = frames[:12] + frames[:1] + frames[12:]

    with pytest.raises(ValueError, match="appears in multiple frame groups"):
        analyze_session(malformed_frames)


def test_analyze_punch_rejects_empty_punch_frame_list() -> None:
    expected = build_mvp_sequence()[0]

    with pytest.raises(ValueError, match="Punch 1 contains zero frames"):
        _analyze_punch(expected, [])
