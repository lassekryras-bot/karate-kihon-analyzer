"""Command-line interface for the karate kihon analyzer."""

from pathlib import Path
from typing import Annotated

import typer

app = typer.Typer(help="Analyze karate kihon videos.")


@app.command()
def analyze(
    input_video: Annotated[
        Path,
        typer.Argument(help="Path to the input kihon video."),
    ],
    output: Annotated[
        Path,
        typer.Option("--output", "-o", help="Directory for analyzer output files."),
    ] = Path("output"),
) -> None:
    """Placeholder command for the future analyzer pipeline."""
    typer.echo(f"Input video: {input_video}")
    typer.echo(f"Output directory: {output}")
    typer.echo("Analyzer pipeline is not implemented yet.")


@app.command("mediapipe-spike")
def mediapipe_spike(
    image: Annotated[
        Path | None,
        typer.Option("--image", help="Optional image to process with the MediaPipe spike."),
    ] = None,
    video: Annotated[
        Path | None,
        typer.Option("--video", help="Optional video to process with the MediaPipe spike."),
    ] = None,
    output: Annotated[
        Path,
        typer.Option("--output", "-o", help="Directory for MediaPipe spike debug files."),
    ] = Path("output/mediapipe-debug"),
) -> None:
    """Run the experimental MediaPipe Pose debug spike."""
    from karate_analyzer.mediapipe_pose_spike import (
        MediaPipeSpikeError,
        run_default_workflow,
    )

    try:
        run_default_workflow(image_path=image, video_path=video, output_directory=output)
    except (FileNotFoundError, MediaPipeSpikeError) as exc:
        typer.echo(f"MediaPipe spike failed: {exc}", err=True)
        raise typer.Exit(code=1) from exc


if __name__ == "__main__":
    app()
