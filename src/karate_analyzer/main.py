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


if __name__ == "__main__":
    app()
