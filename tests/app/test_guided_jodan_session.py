from __future__ import annotations

import ast
from pathlib import Path

from karate_app.guided_session.fake_services import (
    FakeCommandListener,
    FakeSessionMetadataWriter,
    FakeSpeechPrompter,
    FakeStrikeClipRecorder,
)
from karate_app.guided_session.session_models import SessionCommand, StrikeSide
from karate_app.guided_session.session_orchestrator import (
    SETUP_INSTRUCTION,
    GuidedJodanSessionOrchestrator,
)
from karate_app.guided_session.session_plan import create_jodan_session_plan


def build_orchestrator(
    commands: list[SessionCommand] | None = None,
) -> tuple[
    GuidedJodanSessionOrchestrator,
    FakeSpeechPrompter,
    FakeStrikeClipRecorder,
    FakeSessionMetadataWriter,
]:
    speech = FakeSpeechPrompter()
    recorder = FakeStrikeClipRecorder()
    metadata_writer = FakeSessionMetadataWriter()
    orchestrator = GuidedJodanSessionOrchestrator(
        speech_prompter=speech,
        command_listener=FakeCommandListener(commands=commands or [SessionCommand.OSU]),
        clip_recorder=recorder,
        metadata_writer=metadata_writer,
    )
    return orchestrator, speech, recorder, metadata_writer


def test_jodan_session_plan_creates_10_strikes() -> None:
    assert len(create_jodan_session_plan()) == 10


def test_japanese_counts_are_correct_and_ordered() -> None:
    assert [strike.japanese_count for strike in create_jodan_session_plan()] == [
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


def test_expected_sides_alternate_right_left() -> None:
    assert [strike.expected_side for strike in create_jodan_session_plan()] == [
        StrikeSide.RIGHT,
        StrikeSide.LEFT,
        StrikeSide.RIGHT,
        StrikeSide.LEFT,
        StrikeSide.RIGHT,
        StrikeSide.LEFT,
        StrikeSide.RIGHT,
        StrikeSide.LEFT,
        StrikeSide.RIGHT,
        StrikeSide.LEFT,
    ]


def test_filenames_are_correct() -> None:
    assert [strike.file_name for strike in create_jodan_session_plan()] == [
        "strike_001_right.mp4",
        "strike_002_left.mp4",
        "strike_003_right.mp4",
        "strike_004_left.mp4",
        "strike_005_right.mp4",
        "strike_006_left.mp4",
        "strike_007_right.mp4",
        "strike_008_left.mp4",
        "strike_009_right.mp4",
        "strike_010_left.mp4",
    ]


def test_starting_session_speaks_setup_instruction() -> None:
    orchestrator, speech, _, _ = build_orchestrator(commands=[SessionCommand.STOP])

    orchestrator.start_session()

    assert speech.spoken_prompts[0] == SETUP_INSTRUCTION


def test_osu_command_starts_session_flow() -> None:
    orchestrator, speech, _, _ = build_orchestrator(commands=[SessionCommand.OSU])

    orchestrator.start_session()

    assert speech.spoken_prompts[:3] == [SETUP_INSTRUCTION, "Yoi.", "Ichi"]


def test_full_session_records_10_clips() -> None:
    orchestrator, _, recorder, _ = build_orchestrator(commands=[SessionCommand.OSU])

    result = orchestrator.start_session()

    assert len(result.clips) == 10
    assert recorder.recorded_file_names == [
        strike.file_name for strike in create_jodan_session_plan()
    ]


def test_completion_result_has_completed_true() -> None:
    orchestrator, _, _, metadata_writer = build_orchestrator(commands=[SessionCommand.OSU])

    result = orchestrator.start_session()

    assert result.completed is True
    assert result.stopped_by_user is False
    assert result.session_summary == "Session complete. 10 clips saved."
    assert metadata_writer.written_metadata[0].completed is True


def test_stop_before_osu_returns_stopped_result_with_0_clips() -> None:
    orchestrator, speech, _, _ = build_orchestrator(commands=[SessionCommand.STOP])

    result = orchestrator.start_session()

    assert result.completed is False
    assert result.stopped_by_user is True
    assert result.clips == []
    assert speech.spoken_prompts[-1] == "Session stopped. 0 clips saved."


def test_stop_during_strike_flow_returns_stopped_result_with_partial_clips() -> None:
    orchestrator, speech, _, _ = build_orchestrator(
        commands=[
            SessionCommand.OSU,
            SessionCommand.CONTINUE,
            SessionCommand.CONTINUE,
            SessionCommand.CONTINUE,
            SessionCommand.CONTINUE,
            SessionCommand.STOP,
        ]
    )

    result = orchestrator.start_session()

    assert result.completed is False
    assert result.stopped_by_user is True
    assert len(result.clips) == 4
    assert [clip.file_name for clip in result.clips] == [
        "strike_001_right.mp4",
        "strike_002_left.mp4",
        "strike_003_right.mp4",
        "strike_004_left.mp4",
    ]
    assert speech.spoken_prompts[-1] == "Session stopped. 4 clips saved."


def test_orchestrator_does_not_import_karate_analyzer() -> None:
    orchestrator_source = Path(
        "src/karate_app/guided_session/session_orchestrator.py"
    ).read_text(encoding="utf-8")
    imports = [
        node
        for node in ast.walk(ast.parse(orchestrator_source))
        if isinstance(node, ast.Import | ast.ImportFrom)
    ]

    assert all("karate_analyzer" not in ast.unparse(node) for node in imports)


def test_fake_speech_service_records_prompts_in_expected_order() -> None:
    orchestrator, speech, _, _ = build_orchestrator(commands=[SessionCommand.OSU])

    orchestrator.start_session()

    assert speech.spoken_prompts == [
        SETUP_INSTRUCTION,
        "Yoi.",
        "Ichi",
        "Clip 1 saved.",
        "Ni",
        "Clip 2 saved.",
        "San",
        "Clip 3 saved.",
        "Shi",
        "Clip 4 saved.",
        "Go",
        "Clip 5 saved.",
        "Roku",
        "Clip 6 saved.",
        "Shichi",
        "Clip 7 saved.",
        "Hachi",
        "Clip 8 saved.",
        "Ku",
        "Clip 9 saved.",
        "Ju",
        "Clip 10 saved.",
        "Session complete. 10 clips saved.",
    ]
