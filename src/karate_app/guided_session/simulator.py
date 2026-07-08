"""Command-line simulator for the fake guided Jodan clip recorder flow."""

from __future__ import annotations

from karate_app.guided_session.fake_services import (
    FakeCommandListener,
    FakeSessionMetadataWriter,
    FakeSpeechPrompter,
    FakeStrikeCaptureController,
)
from karate_app.guided_session.session_models import SessionCommand
from karate_app.guided_session.session_orchestrator import GuidedJodanSessionOrchestrator


def main() -> None:
    speech = FakeSpeechPrompter()
    command_listener = FakeCommandListener(commands=[SessionCommand.OSU])
    recorder = FakeStrikeCaptureController()
    metadata_writer = FakeSessionMetadataWriter()
    orchestrator = GuidedJodanSessionOrchestrator(
        speech_prompter=speech,
        command_listener=command_listener,
        clip_recorder=None,
        capture_controller=recorder,
        metadata_writer=metadata_writer,
    )

    orchestrator.start_session()

    print("\nSpoken prompts:")
    for prompt in speech.spoken_prompts:
        print(f"- {prompt}")

    print("\nSaved clips:")
    for file_name in recorder.recorded_file_names:
        print(f"- {file_name}")


if __name__ == "__main__":
    main()
