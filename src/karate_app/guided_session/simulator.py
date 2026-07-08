"""Command-line simulator for the fake guided Jodan clip recorder flow."""

from __future__ import annotations

from karate_app.guided_session.fake_services import (
    FakeCommandListener,
    FakeSessionMetadataWriter,
    FakeSpeechPrompter,
    FakeStrikeCaptureController,
)
from karate_app.guided_session.session_models import SessionCommand, StrikeCaptureState
from karate_app.guided_session.session_orchestrator import GuidedJodanSessionOrchestrator


def main() -> None:
    speech = FakeSpeechPrompter()
    command_listener = FakeCommandListener(commands=[SessionCommand.OSU])
    recorder = FakeStrikeCaptureController(
        scripted_results=[StrikeCaptureState.NO_MOVEMENT_TIMEOUT]
    )
    metadata_writer = FakeSessionMetadataWriter()
    orchestrator = GuidedJodanSessionOrchestrator(
        speech_prompter=speech,
        command_listener=command_listener,
        clip_recorder=None,
        capture_controller=recorder,
        metadata_writer=metadata_writer,
    )

    result = orchestrator.start_session()
    metadata = metadata_writer.written_guided_session_metadata[0]

    print("\nSpoken prompts:")
    for prompt in speech.spoken_prompts:
        print(f"- {prompt}")

    print("\nSaved clips:")
    for file_name in recorder.recorded_file_names:
        print(f"- {file_name}")

    print(f"\nSession ID: {metadata.session_id}")

    print("\nAttempted strikes:")
    for attempt in metadata.attempts:
        retry_label = f" retry {attempt.retry_number}" if attempt.retry_number else ""
        print(
            f"- attempt {attempt.attempt_id}: strike {attempt.strike_index}"
            f" {attempt.expected_side.value}{retry_label} -> {attempt.outcome.value}"
        )

    print("\nSummary counts:")
    print(f"- successful clips: {metadata.summary.successful_clip_count}")
    print(f"- failed attempts: {metadata.summary.failed_attempt_count}")
    print(f"- retries: {metadata.summary.retry_count}")
    print(f"- skipped strikes: {metadata.summary.skipped_strike_count}")
    print(f"- total attempts: {metadata.summary.total_attempt_count}")
    print(f"- result: {result.session_summary}")
    if metadata_writer.last_metadata_path is not None:
        print(f"- metadata file: {metadata_writer.last_metadata_path}")
    else:
        print("- metadata file: not written (no output folder configured)")


if __name__ == "__main__":
    main()
