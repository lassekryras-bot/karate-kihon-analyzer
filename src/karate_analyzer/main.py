"""Command-line interface for the karate kihon analyzer."""

from dataclasses import asdict
from pathlib import Path
from typing import Annotated

import typer

app = typer.Typer(help="Analyze karate kihon videos.")


@app.command()
def analyze(
    input_video: Annotated[Path, typer.Argument(help="Path to the input kihon video.")],
    output: Annotated[
        Path,
        typer.Option("--output", "-o", help="Directory for analyzer output files."),
    ] = Path("output"),
    smoothing_window: Annotated[
        int,
        typer.Option(
            "--smoothing-window", help="Moving-average window for extension ratios."
        ),
    ] = 5,
    group_threshold: Annotated[
        float,
        typer.Option(
            "--group-threshold", help="Smoothed extension ratio required for grouping."
        ),
    ] = 0.90,
    merge_gap_frames: Annotated[
        int,
        typer.Option(
            "--merge-gap-frames",
            help="Short below-threshold gaps to merge into a region.",
        ),
    ] = 3,
    min_visibility: Annotated[
        float,
        typer.Option(
            "--min-visibility", help="Minimum landmark visibility for grouped regions."
        ),
    ] = 0.5,
) -> None:
    """Run the end-to-end MVP karate kihon analysis pipeline."""
    from karate_analyzer.pipeline.analysis_pipeline import run_analysis_pipeline
    from karate_analyzer.frame_extractor import FrameExtractionError
    from karate_analyzer.vision.mediapipe_pose_spike import MediaPipeSpikeError

    try:
        result = run_analysis_pipeline(
            input_video=input_video,
            output_directory=output,
            smoothing_window=smoothing_window,
            group_threshold=group_threshold,
            merge_gap_frames=merge_gap_frames,
            min_visibility=min_visibility,
        )
    except (
        FileNotFoundError,
        ValueError,
        MediaPipeSpikeError,
        FrameExtractionError,
    ) as exc:
        typer.echo(f"Analysis failed: {exc}", err=True)
        raise typer.Exit(code=1) from exc

    summary = result["summary"]
    counts = summary["jodan_height"]
    typer.echo("Analysis complete.")
    typer.echo("")
    typer.echo(f"Source video: {summary['source_video']}")
    typer.echo(f"Output directory: {result['output_directory']}")
    typer.echo(
        f"Detected punches: {summary['detected_punch_count']} "
        f"of expected {summary['expected_punch_count']}"
    )
    typer.echo("")
    typer.echo("Jodan height:")
    typer.echo(f"- Good: {counts['good']}")
    typer.echo(f"- Too low: {counts['too_low']}")
    typer.echo(f"- Too high: {counts['too_high']}")
    typer.echo(f"- Unknown: {counts['unknown']}")
    typer.echo("")
    typer.echo("Wrote:")
    typer.echo("- summary.json")
    typer.echo("- analysis_results.json")
    typer.echo("- report.md")
    typer.echo("- rendered-strikes/")


@app.command("extract-frame")
def extract_frame_command(
    video: Annotated[
        Path,
        typer.Option("--video", help="Path to the input video."),
    ],
    frame: Annotated[
        int,
        typer.Option("--frame", help="Zero-based frame number to extract."),
    ],
    output: Annotated[
        Path | None,
        typer.Option("--output", "-o", help="Path for the extracted image."),
    ] = None,
) -> None:
    """Extract a single still frame from a video for debugging."""
    from karate_analyzer.frame_extractor import (
        FrameExtractionError,
        default_frame_output_path,
        extract_frame,
    )

    output_path = output if output is not None else default_frame_output_path(frame)

    try:
        metadata = extract_frame(video, frame, output_path)
    except (FileNotFoundError, FrameExtractionError, ValueError) as exc:
        typer.echo(f"Frame extraction failed: {exc}", err=True)
        raise typer.Exit(code=1) from exc

    typer.echo("Extracted frame:")
    for key, value in asdict(metadata).items():
        typer.echo(f"{key}: {value}")


@app.command("render-strike-snapshots")
def render_strike_snapshots_command(
    video: Annotated[
        Path,
        typer.Option("--video", help="Path to the input video."),
    ],
    analysis: Annotated[
        Path,
        typer.Option("--analysis", help="Path to punch_event_landmarks.json."),
    ],
    output: Annotated[
        Path,
        typer.Option("--output", "-o", help="Directory for rendered strike PNG files."),
    ] = Path("output/rendered-strikes"),
) -> None:
    """Extract peak frames and render annotated strike snapshots."""
    from karate_analyzer.frame_extractor import FrameExtractionError
    from karate_analyzer.rendering.snapshot_renderer import render_strike_snapshots_from_analysis

    try:
        rendered_paths = render_strike_snapshots_from_analysis(
            video_path=video,
            analysis_path=analysis,
            output_directory=output,
        )
    except (FileNotFoundError, FrameExtractionError, ValueError) as exc:
        typer.echo(f"Strike snapshot rendering failed: {exc}", err=True)
        raise typer.Exit(code=1) from exc

    typer.echo(f"Rendered {len(rendered_paths)} strike snapshot(s):")
    for rendered_path in rendered_paths:
        typer.echo(f"- {rendered_path}")


@app.command("mediapipe-spike")
def mediapipe_spike(
    image: Annotated[
        Path | None,
        typer.Option(
            "--image", help="Optional image to process with the MediaPipe spike."
        ),
    ] = None,
    video: Annotated[
        Path | None,
        typer.Option(
            "--video", help="Optional video to process with the MediaPipe spike."
        ),
    ] = None,
    output: Annotated[
        Path,
        typer.Option(
            "--output", "-o", help="Directory for MediaPipe spike debug files."
        ),
    ] = Path("output/mediapipe-debug"),
) -> None:
    """Run the experimental MediaPipe Pose debug spike."""
    from karate_analyzer.vision.mediapipe_pose_spike import (
        MediaPipeSpikeError,
        run_default_workflow,
    )

    try:
        run_default_workflow(
            image_path=image, video_path=video, output_directory=output
        )
    except (FileNotFoundError, MediaPipeSpikeError) as exc:
        typer.echo(f"MediaPipe spike failed: {exc}", err=True)
        raise typer.Exit(code=1) from exc


@app.command("explore-extension")
def explore_extension(
    input_path: Annotated[
        Path,
        typer.Option("--input", help="Path to MediaPipe spike video_landmarks.json."),
    ] = Path("output/mediapipe-debug/video_landmarks.json"),
    output: Annotated[
        Path,
        typer.Option("--output", "-o", help="Directory for extension debug files."),
    ] = Path("output/mediapipe-debug"),
    smoothing_window: Annotated[
        int,
        typer.Option(
            "--smoothing-window", help="Moving-average window for extension ratios."
        ),
    ] = 5,
    group_threshold: Annotated[
        float,
        typer.Option(
            "--group-threshold", help="Smoothed extension ratio required for grouping."
        ),
    ] = 0.90,
    merge_gap_frames: Annotated[
        int,
        typer.Option(
            "--merge-gap-frames",
            help="Short below-threshold gaps to merge into a region.",
        ),
    ] = 3,
    min_visibility: Annotated[
        float,
        typer.Option(
            "--min-visibility", help="Minimum landmark visibility for grouped regions."
        ),
    ] = 0.5,
) -> None:
    """Explore wrist-extension signals in MediaPipe spike landmark JSON."""
    from karate_analyzer.detection.mediapipe_extension_explorer import analyze_extension_json

    try:
        summary = analyze_extension_json(
            input_path,
            output,
            smoothing_window=smoothing_window,
            group_threshold=group_threshold,
            merge_gap_frames=merge_gap_frames,
            min_visibility=min_visibility,
        )
    except (FileNotFoundError, ValueError) as exc:
        typer.echo(str(exc), err=True)
        raise typer.Exit(code=1) from exc

    typer.echo(f"Loaded {summary['frame_count']} frames.")
    typer.echo(f"Detected pose in {summary['detected_frame_count']} frames.")
    typer.echo("")
    typer.echo(f"Simple left candidate peaks: {summary['left_candidate_peak_count']}")
    typer.echo(f"Simple right candidate peaks: {summary['right_candidate_peak_count']}")
    typer.echo("")
    typer.echo(f"Grouped left peaks: {summary['grouped_left_peak_count']}")
    typer.echo(f"Grouped right peaks: {summary['grouped_right_peak_count']}")
    typer.echo("")
    typer.echo(
        f"Punch event candidates: {summary['punch_event_candidate_count']} "
        f"of expected {summary['expected_punch_count']}"
    )
    typer.echo("")
    typer.echo("Wrote:")
    for filename in summary["output_files"]:
        typer.echo(f"- {filename}")


if __name__ == "__main__":
    app()
