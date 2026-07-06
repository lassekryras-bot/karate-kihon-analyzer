from __future__ import annotations

from dataclasses import replace

from PIL import Image

from karate_analyzer.session_analyzer import analyze_session
from karate_analyzer.snapshot_renderer import render_punch_snapshot, save_punch_snapshot
from karate_analyzer.synthetic_session import generate_synthetic_mvp_session


def _first_synthetic_punch():
    return analyze_session(generate_synthetic_mvp_session()).punches[0]


def test_render_punch_snapshot_returns_pillow_image() -> None:
    image = render_punch_snapshot(_first_synthetic_punch())

    assert isinstance(image, Image.Image)


def test_render_punch_snapshot_default_size_is_800_by_600() -> None:
    image = render_punch_snapshot(_first_synthetic_punch())

    assert image.size == (800, 600)


def test_render_punch_snapshot_custom_size_works() -> None:
    image = render_punch_snapshot(_first_synthetic_punch(), width=320, height=240)

    assert image.size == (320, 240)


def test_save_punch_snapshot_creates_png_file(tmp_path) -> None:
    output_path = tmp_path / "punch.png"

    save_punch_snapshot(_first_synthetic_punch(), output_path)

    assert output_path.exists()
    assert output_path.read_bytes().startswith(b"\x89PNG\r\n\x1a\n")


def test_render_punch_snapshot_does_not_mutate_punch_analysis() -> None:
    punch = _first_synthetic_punch()
    original = replace(punch)

    render_punch_snapshot(punch)

    assert punch == original


def test_render_punch_snapshot_works_with_analyzed_synthetic_mvp_first_punch() -> None:
    analysis = analyze_session(generate_synthetic_mvp_session())

    image = render_punch_snapshot(analysis.punches[0])

    assert isinstance(image, Image.Image)
    assert image.size == (800, 600)
