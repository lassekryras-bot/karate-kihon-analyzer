from __future__ import annotations

import ast
from pathlib import Path

from karate_app.guided_session.fake_services import (
    FakeCommandListener,
    FakeRecordingAdapter,
    FakeSessionMetadataWriter,
    FakeSpeechPrompter,
    FakeStrikeCaptureController,
)
from karate_app.guided_session.session_models import (
    CaptureMode,
    RecordingStartRequest,
    RecordingState,
    RecordingStopRequest,
    SessionCommand,
    StrikeCaptureConfig,
    StrikeCaptureState,
    StrikeSide,
)
from karate_app.guided_session.session_orchestrator import (
    SETUP_INSTRUCTION,
    GuidedJodanSessionOrchestrator,
)
from karate_app.guided_session.session_plan import create_jodan_session_plan
from karate_app.guided_session.strike_capture_controller import (
    FixedDurationStrikeCaptureController,
)


def build_orchestrator(
    commands: list[SessionCommand] | None = None,
) -> tuple[
    GuidedJodanSessionOrchestrator,
    FakeSpeechPrompter,
    FakeStrikeCaptureController,
    FakeSessionMetadataWriter,
]:
    speech = FakeSpeechPrompter()
    recorder = FakeStrikeCaptureController()
    metadata_writer = FakeSessionMetadataWriter()
    orchestrator = GuidedJodanSessionOrchestrator(
        speech_prompter=speech,
        command_listener=FakeCommandListener(commands=commands or [SessionCommand.OSU]),
        clip_recorder=None,
        capture_controller=recorder,
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
    speech = FakeSpeechPrompter()
    recorder = FakeStrikeCaptureController(scripted_results=[
        StrikeCaptureState.CLIP_READY,
        StrikeCaptureState.CLIP_READY,
        StrikeCaptureState.CLIP_READY,
        StrikeCaptureState.CLIP_READY,
        StrikeCaptureState.CANCELLED,
    ])
    metadata_writer = FakeSessionMetadataWriter()
    orchestrator = GuidedJodanSessionOrchestrator(
        speech_prompter=speech,
        command_listener=FakeCommandListener(commands=[SessionCommand.OSU]),
        clip_recorder=None,
        capture_controller=recorder,
        metadata_writer=metadata_writer,
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
    assert recorder.results[-1].state == StrikeCaptureState.CANCELLED
    assert speech.spoken_prompts[-1] == "Session stopped. 4 clips saved."


def test_no_movement_timeout_retries_same_strike_without_stopping_session() -> None:
    speech = FakeSpeechPrompter()
    recorder = FakeStrikeCaptureController(
        scripted_results=[StrikeCaptureState.NO_MOVEMENT_TIMEOUT]
    )
    metadata_writer = FakeSessionMetadataWriter()
    orchestrator = GuidedJodanSessionOrchestrator(
        speech_prompter=speech,
        command_listener=FakeCommandListener(commands=[SessionCommand.OSU]),
        clip_recorder=None,
        capture_controller=recorder,
        metadata_writer=metadata_writer,
    )

    result = orchestrator.start_session()

    assert result.completed is True
    assert result.stopped_by_user is False
    assert len(result.clips) == 10
    assert [capture.strike_index for capture in recorder.results[:2]] == [1, 1]
    assert recorder.results[0].state == StrikeCaptureState.NO_MOVEMENT_TIMEOUT
    assert recorder.results[1].state == StrikeCaptureState.CLIP_READY
    assert "Punch not detected. Try again." in speech.spoken_prompts



def test_repeated_no_movement_timeout_does_not_infinite_loop() -> None:
    speech = FakeSpeechPrompter()
    recorder = FakeStrikeCaptureController(
        scripted_results=[StrikeCaptureState.NO_MOVEMENT_TIMEOUT] * 30
    )
    metadata_writer = FakeSessionMetadataWriter()
    orchestrator = GuidedJodanSessionOrchestrator(
        speech_prompter=speech,
        command_listener=FakeCommandListener(commands=[SessionCommand.OSU]),
        clip_recorder=None,
        capture_controller=recorder,
        capture_config=StrikeCaptureConfig(max_retries_per_strike=2),
        metadata_writer=metadata_writer,
    )

    result = orchestrator.start_session()

    assert result.completed is True
    assert len(recorder.results) == 30
    assert result.clips == []
    assert speech.spoken_prompts.count("Skipping this strike.") == 10


def test_max_retries_per_strike_is_respected() -> None:
    recorder = FakeStrikeCaptureController(
        scripted_results=[StrikeCaptureState.NO_MOVEMENT_TIMEOUT] * 3
    )
    orchestrator = GuidedJodanSessionOrchestrator(
        speech_prompter=FakeSpeechPrompter(),
        command_listener=FakeCommandListener(commands=[SessionCommand.OSU]),
        clip_recorder=None,
        capture_controller=recorder,
        capture_config=StrikeCaptureConfig(max_retries_per_strike=1),
        metadata_writer=FakeSessionMetadataWriter(),
    )

    result = orchestrator.start_session()

    assert result.completed is True
    assert [capture.strike_index for capture in recorder.results[:3]] == [1, 1, 2]
    assert len(result.clips) == 9


def test_after_retry_limit_session_continues_to_next_strike() -> None:
    speech = FakeSpeechPrompter()
    recorder = FakeStrikeCaptureController(
        scripted_results=[StrikeCaptureState.NO_MOVEMENT_TIMEOUT] * 3
    )
    orchestrator = GuidedJodanSessionOrchestrator(
        speech_prompter=speech,
        command_listener=FakeCommandListener(commands=[SessionCommand.OSU]),
        clip_recorder=None,
        capture_controller=recorder,
        capture_config=StrikeCaptureConfig(max_retries_per_strike=2),
        metadata_writer=FakeSessionMetadataWriter(),
    )

    result = orchestrator.start_session()

    assert result.completed is True
    assert len(result.clips) == 9
    assert result.clips[0].strike_index == 2
    assert "Skipping this strike." in speech.spoken_prompts
    assert "Ni" in speech.spoken_prompts


def test_successful_retry_saves_only_one_clip_for_that_strike() -> None:
    recorder = FakeStrikeCaptureController(
        scripted_results=[StrikeCaptureState.NO_MOVEMENT_TIMEOUT]
    )
    orchestrator = GuidedJodanSessionOrchestrator(
        speech_prompter=FakeSpeechPrompter(),
        command_listener=FakeCommandListener(commands=[SessionCommand.OSU]),
        clip_recorder=None,
        capture_controller=recorder,
        capture_config=StrikeCaptureConfig(max_retries_per_strike=2),
        metadata_writer=FakeSessionMetadataWriter(),
    )

    result = orchestrator.start_session()

    assert result.completed is True
    assert [capture.strike_index for capture in recorder.results[:2]] == [1, 1]
    assert [clip.strike_index for clip in result.clips].count(1) == 1
    assert len(result.clips) == 10

def test_recording_failed_ends_session_without_user_stop_flag() -> None:
    speech = FakeSpeechPrompter()
    recorder = FakeStrikeCaptureController(scripted_results=[StrikeCaptureState.FAILED])
    metadata_writer = FakeSessionMetadataWriter()
    orchestrator = GuidedJodanSessionOrchestrator(
        speech_prompter=speech,
        command_listener=FakeCommandListener(commands=[SessionCommand.OSU]),
        clip_recorder=None,
        capture_controller=recorder,
        metadata_writer=metadata_writer,
    )

    result = orchestrator.start_session()

    assert result.completed is False
    assert result.stopped_by_user is False
    assert result.clips == []
    assert result.session_summary == (
        "Session stopped because capture failed on strike 1. 0 clips saved."
    )
    assert metadata_writer.written_metadata[0].stopped_by_user is False


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


def test_capture_controller_uses_fixed_duration_requests() -> None:
    orchestrator, _, recorder, _ = build_orchestrator(commands=[SessionCommand.OSU])

    orchestrator.start_session()

    assert {result.capture_mode for result in recorder.results} == {CaptureMode.FAKE}
    assert {result.clip_duration_ms for result in recorder.results} == {4_000}


def test_default_strike_capture_config_values_are_correct() -> None:
    config = StrikeCaptureConfig()

    assert config.capture_mode == CaptureMode.FAKE
    assert config.fixed_clip_duration_ms == 4_000
    assert config.max_retries_per_strike == 2
    assert config.waiting_for_movement_timeout_ms == 5_000
    assert config.active_strike_timeout_ms == 10_000
    assert config.progress_stall_timeout_ms == 2_000
    assert config.post_roll_ms == 500
    assert config.minimum_elbow_extension_angle_degrees == 160


def test_fake_capture_controller_returns_clip_ready_with_planned_filename() -> None:
    strike = create_jodan_session_plan()[0]
    recorder = FakeStrikeCaptureController()

    result = recorder.capture_strike_clip(strike, StrikeCaptureConfig())

    assert result.state == StrikeCaptureState.CLIP_READY
    assert result.file_name == "strike_001_right.mp4"
    assert result.cancelled is False


def test_fake_capture_controller_includes_post_roll_and_completion_time() -> None:
    strike = create_jodan_session_plan()[0]
    recorder = FakeStrikeCaptureController()

    result = recorder.capture_strike_clip(strike, StrikeCaptureConfig(post_roll_ms=750))

    assert result.post_roll_ms == 750
    assert result.rough_movement_start_ms == 1_000
    assert result.rough_completion_time_ms == 2_500


def test_fake_capture_controller_can_simulate_no_movement_timeout() -> None:
    strike = create_jodan_session_plan()[0]
    recorder = FakeStrikeCaptureController(
        scripted_results=[StrikeCaptureState.NO_MOVEMENT_TIMEOUT]
    )

    result = recorder.capture_strike_clip(strike, StrikeCaptureConfig())

    assert result.state == StrikeCaptureState.NO_MOVEMENT_TIMEOUT
    assert result.timeout_ms == 5_000


def test_fake_capture_controller_can_simulate_incomplete_strike_timeout() -> None:
    strike = create_jodan_session_plan()[0]
    recorder = FakeStrikeCaptureController(
        scripted_results=[StrikeCaptureState.INCOMPLETE_STRIKE_TIMEOUT]
    )

    result = recorder.capture_strike_clip(strike, StrikeCaptureConfig())

    assert result.state == StrikeCaptureState.INCOMPLETE_STRIKE_TIMEOUT
    assert result.timeout_ms == 2_000


def test_fake_capture_controller_can_simulate_active_strike_timeout() -> None:
    strike = create_jodan_session_plan()[0]
    recorder = FakeStrikeCaptureController(
        scripted_results=[StrikeCaptureState.ACTIVE_STRIKE_TIMEOUT]
    )

    result = recorder.capture_strike_clip(strike, StrikeCaptureConfig())

    assert result.state == StrikeCaptureState.ACTIVE_STRIKE_TIMEOUT
    assert result.timeout_ms == 10_000


def test_fake_capture_controller_can_simulate_cancelled_capture() -> None:
    strike = create_jodan_session_plan()[0]
    recorder = FakeStrikeCaptureController(scripted_results=[StrikeCaptureState.CANCELLED])

    result = recorder.capture_strike_clip(strike, StrikeCaptureConfig())

    assert result.state == StrikeCaptureState.CANCELLED
    assert result.cancelled is True
    assert result.capture_reason == "cancelled_by_user"


def test_cancel_capture_sets_next_capture_to_cancelled() -> None:
    strike = create_jodan_session_plan()[0]
    recorder = FakeStrikeCaptureController()

    recorder.cancel_capture()
    result = recorder.capture_strike_clip(strike, StrikeCaptureConfig())

    assert result.state == StrikeCaptureState.CANCELLED
    assert result.cancelled is True


def recording_start_request() -> RecordingStartRequest:
    return RecordingStartRequest(
        file_name="strike_001_right.mp4",
        strike_index=1,
        expected_side=StrikeSide.RIGHT,
        japanese_count="Ichi",
        capture_mode=CaptureMode.FIXED_DURATION,
        metadata={"test": True},
    )


def test_fake_recording_adapter_starts_in_idle() -> None:
    adapter = FakeRecordingAdapter()

    assert adapter.current_state() == RecordingState.IDLE


def test_fake_recording_adapter_start_changes_state_to_recording() -> None:
    adapter = FakeRecordingAdapter()

    adapter.start_recording(recording_start_request())

    assert adapter.current_state() == RecordingState.RECORDING


def test_fake_recording_adapter_stop_returns_saved_result() -> None:
    adapter = FakeRecordingAdapter(fake_duration_ms=4_500)
    adapter.start_recording(recording_start_request())

    result = adapter.stop_recording(RecordingStopRequest(reason="test_complete"))

    assert result.state == RecordingState.SAVED
    assert result.saved is True
    assert result.duration_ms == 4_500


def test_fake_recording_adapter_stop_returns_saved_file_name() -> None:
    adapter = FakeRecordingAdapter()
    adapter.start_recording(recording_start_request())

    result = adapter.stop_recording(RecordingStopRequest(reason="test_complete"))

    assert result.file_name == "strike_001_right.mp4"


def test_fake_recording_adapter_cancel_returns_cancelled_result() -> None:
    adapter = FakeRecordingAdapter()
    adapter.start_recording(recording_start_request())

    result = adapter.cancel_recording("user_stop")

    assert result.state == RecordingState.CANCELLED
    assert result.saved is False
    assert result.cancel_reason == "user_stop"


def test_fake_recording_adapter_fail_on_start_produces_failed_state() -> None:
    adapter = FakeRecordingAdapter(fail_on_start=True)

    try:
        adapter.start_recording(recording_start_request())
    except RuntimeError as exc:
        assert str(exc) == "fake recording start failed"

    assert adapter.current_state() == RecordingState.FAILED


def test_fake_recording_adapter_fail_on_stop_produces_failed_result() -> None:
    adapter = FakeRecordingAdapter(fail_on_stop=True)
    adapter.start_recording(recording_start_request())

    result = adapter.stop_recording(RecordingStopRequest(reason="test_complete"))

    assert result.state == RecordingState.FAILED
    assert result.saved is False
    assert result.failure_reason == "fake recording stop failed"


def test_fixed_duration_capture_controller_returns_clip_ready_on_successful_recording() -> None:
    controller = FixedDurationStrikeCaptureController(FakeRecordingAdapter())

    result = controller.capture_strike_clip(create_jodan_session_plan()[0], StrikeCaptureConfig())

    assert result.state == StrikeCaptureState.CLIP_READY
    assert result.capture_reason == "fixed_duration_complete"


def test_fixed_duration_capture_controller_uses_planned_strike_filename() -> None:
    adapter = FakeRecordingAdapter()
    controller = FixedDurationStrikeCaptureController(adapter)
    strike = create_jodan_session_plan()[0]

    result = controller.capture_strike_clip(strike, StrikeCaptureConfig())

    assert adapter.start_requests[0].file_name == strike.file_name
    assert result.file_name == strike.file_name


def test_fixed_duration_capture_controller_sets_capture_mode_to_fixed_duration() -> None:
    controller = FixedDurationStrikeCaptureController(FakeRecordingAdapter())

    result = controller.capture_strike_clip(create_jodan_session_plan()[0], StrikeCaptureConfig())

    assert result.capture_mode == CaptureMode.FIXED_DURATION


def test_fixed_duration_capture_controller_includes_recording_diagnostics() -> None:
    controller = FixedDurationStrikeCaptureController(FakeRecordingAdapter())

    result = controller.capture_strike_clip(create_jodan_session_plan()[0], StrikeCaptureConfig())

    assert result.diagnostics["recording_state"] == RecordingState.SAVED.value
    assert result.diagnostics["recording_saved"] is True
    assert result.diagnostics["stop_reason"] == "fixed_duration_complete"


def test_fixed_duration_capture_controller_returns_failed_if_recording_adapter_fails() -> None:
    controller = FixedDurationStrikeCaptureController(
        FakeRecordingAdapter(fail_on_stop=True)
    )

    result = controller.capture_strike_clip(create_jodan_session_plan()[0], StrikeCaptureConfig())

    assert result.state == StrikeCaptureState.FAILED
    assert result.capture_reason == "recording_failed"
    assert result.diagnostics["failure_reason"] == "fake recording stop failed"


def test_orchestrator_can_run_full_session_with_fixed_duration_capture_controller() -> None:
    speech = FakeSpeechPrompter()
    adapter = FakeRecordingAdapter()
    controller = FixedDurationStrikeCaptureController(adapter)
    metadata_writer = FakeSessionMetadataWriter()
    orchestrator = GuidedJodanSessionOrchestrator(
        speech_prompter=speech,
        command_listener=FakeCommandListener(commands=[SessionCommand.OSU]),
        clip_recorder=None,
        capture_controller=controller,
        metadata_writer=metadata_writer,
    )

    result = orchestrator.start_session()

    assert result.completed is True
    assert len(result.clips) == 10
    assert len(adapter.start_requests) == 10


def test_metadata_v2_still_records_successful_clips_with_fixed_duration_controller() -> None:
    adapter = FakeRecordingAdapter()
    metadata_writer = FakeSessionMetadataWriter()
    orchestrator = GuidedJodanSessionOrchestrator(
        speech_prompter=FakeSpeechPrompter(),
        command_listener=FakeCommandListener(commands=[SessionCommand.OSU]),
        clip_recorder=None,
        capture_controller=FixedDurationStrikeCaptureController(adapter),
        metadata_writer=metadata_writer,
    )

    orchestrator.start_session()

    metadata = metadata_writer.written_metadata[0]
    assert len(metadata.clips) == 10
    assert metadata.clips[0].capture_reason == "fixed_duration_complete"
    assert metadata.clips[0].capture_diagnostics["recording_saved"] is True


def test_orchestrator_does_not_import_or_depend_on_recording_adapter_directly() -> None:
    orchestrator_source = Path(
        "src/karate_app/guided_session/session_orchestrator.py"
    ).read_text(encoding="utf-8")
    imports = [
        node
        for node in ast.walk(ast.parse(orchestrator_source))
        if isinstance(node, ast.Import | ast.ImportFrom)
    ]

    assert all("recording_adapter" not in ast.unparse(node) for node in imports)
    assert "RecordingAdapter" not in orchestrator_source
