"""Command-line simulator for the guided Jodan clip recorder flow."""

from __future__ import annotations

from karate_app.guided_session.fake_services import (
    FakeCommandListener,
    FakeRecordingAdapter,
    FakeSessionMetadataWriter,
    FakeSpeechPrompter,
)
from karate_app.guided_session.session_models import SessionCommand, StrikeCaptureConfig
from karate_app.guided_session.session_orchestrator import GuidedJodanSessionOrchestrator
from karate_app.guided_session.strike_capture_controller import (
    FixedDurationStrikeCaptureController,
)


def main() -> None:
    speech = FakeSpeechPrompter()
    command_listener = FakeCommandListener(commands=[SessionCommand.OSU])
    recording_adapter = FakeRecordingAdapter()
    recorder = FixedDurationStrikeCaptureController(recording_adapter)
    metadata_writer = FakeSessionMetadataWriter()
    orchestrator = GuidedJodanSessionOrchestrator(
        speech_prompter=speech,
        command_listener=command_listener,
        clip_recorder=None,
        capture_controller=recorder,
        capture_config=StrikeCaptureConfig(),
        metadata_writer=metadata_writer,
    )

    result = orchestrator.start_session()

    print("\nSpoken prompts:")
    for prompt in speech.spoken_prompts:
        print(f"- {prompt}")

    print("\nSaved clips:")
    for file_name in recorder.recorded_file_names:
        print(f"- {file_name}")

    print("\nFinal metadata summary:")
    print(f"- {result.session_summary}")
    print(f"- completed: {result.completed}")
    print(f"- clips: {len(result.clips)}")


if __name__ == "__main__":
    main()
