from __future__ import annotations

from dataclasses import replace
import json
from pathlib import Path

from PIL import Image

from karate_analyzer.frame_extractor import ExtractedFrameMetadata
from karate_analyzer.session_analyzer import analyze_session
from karate_analyzer.snapshot_renderer import (
    StrikeSnapshotRenderInstructions,
    render_punch_snapshot,
    render_strike_snapshot,
    render_strike_snapshots_from_analysis,
    save_punch_snapshot,
    save_strike_snapshot,
    strike_snapshot_filename,
)
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


def _strike_landmarks():
    return [
        {"index": 0, "x": 0.50, "y": 0.20, "visibility": 0.95},
        {"index": 11, "x": 0.38, "y": 0.42, "visibility": 0.95},
        {"index": 12, "x": 0.62, "y": 0.42, "visibility": 0.95},
        {"index": 13, "x": 0.32, "y": 0.55, "visibility": 0.90},
        {"index": 15, "x": 0.22, "y": 0.62, "visibility": 0.90},
        {"index": 14, "x": 0.70, "y": 0.45, "visibility": 0.91},
        {"index": 16, "x": 0.86, "y": 0.42, "visibility": 0.91},
        {"index": 23, "x": 0.43, "y": 0.72, "visibility": 0.88},
        {"index": 24, "x": 0.57, "y": 0.72, "visibility": 0.88},
    ]


def _strike_instructions():
    return StrikeSnapshotRenderInstructions(
        strike_number=6,
        strike_side="right",
        peak_frame_number=185,
        timestamp_seconds=6.167,
        confidence=0.91,
    )


def _analysis_payload():
    return {
        "punch_event_landmarks": [
            {
                "event_index": 6,
                "expected_side": "right",
                "observed_side": "right",
                "peak_frame_number": 185,
                "timestamp_seconds": None,
                "shoulder": {"x": 0.62, "y": 0.42, "visibility": 0.95},
                "elbow": {"x": 0.70, "y": 0.45, "visibility": 0.92},
                "wrist": {"x": 0.86, "y": 0.42, "visibility": 0.91},
                "head_reference_candidate": {
                    "source": "nose",
                    "x": 0.50,
                    "y": 0.20,
                    "visibility": 0.96,
                },
                "visibility": {"minimum_required_landmark_visibility": 0.91},
            }
        ]
    }


def test_render_strike_snapshot_accepts_real_extracted_frame() -> None:
    background = Image.new("RGB", (160, 120), "gray")

    image = render_strike_snapshot(background, _strike_landmarks(), _strike_instructions())

    assert isinstance(image, Image.Image)
    assert image.size == (160, 120)
    assert image.mode == "RGB"


def test_save_strike_snapshot_produces_png_and_creates_output_directory(tmp_path: Path) -> None:
    background_path = tmp_path / "frame.png"
    output_path = tmp_path / "rendered" / "strike-006-right.png"
    Image.new("RGB", (160, 120), "gray").save(background_path)

    save_strike_snapshot(background_path, _strike_landmarks(), _strike_instructions(), output_path)

    assert output_path.exists()
    assert output_path.read_bytes().startswith(b"\x89PNG\r\n\x1a\n")
    with Image.open(output_path) as image:
        assert image.size == (160, 120)


def test_strike_snapshot_filename_is_deterministic() -> None:
    assert strike_snapshot_filename(1, "right") == "strike-001-right.png"
    assert strike_snapshot_filename(6, "Left") == "strike-006-left.png"


def test_strike_snapshot_text_panel_and_landmarks_change_background() -> None:
    background = Image.new("RGB", (200, 140), "gray")

    image = render_strike_snapshot(background, _strike_landmarks(), _strike_instructions())

    assert image.getbbox() == background.getbbox()
    assert image.tobytes() != background.tobytes()


def test_render_strike_snapshots_from_analysis_extracts_renders_and_names_files(
    tmp_path: Path, monkeypatch
) -> None:
    video_path = tmp_path / "video.mp4"
    analysis_path = tmp_path / "punch_event_landmarks.json"
    output_dir = tmp_path / "rendered-strikes"
    video_path.write_bytes(b"fake video")
    analysis_path.write_text(json.dumps(_analysis_payload()), encoding="utf-8")

    def fake_extract_frame(video, frame_number, output_path):
        Image.new("RGB", (160, 120), "gray").save(output_path)
        return ExtractedFrameMetadata(
            video_path=Path(video),
            requested_frame_number=frame_number,
            actual_frame_number=frame_number,
            output_path=Path(output_path),
            frame_width=160,
            frame_height=120,
            fps=30.0,
            timestamp_seconds=frame_number / 30.0,
        )

    monkeypatch.setattr("karate_analyzer.snapshot_renderer.extract_frame", fake_extract_frame)

    rendered_paths = render_strike_snapshots_from_analysis(
        video_path=video_path,
        analysis_path=analysis_path,
        output_directory=output_dir,
    )

    assert rendered_paths == [output_dir / "strike-006-right.png"]
    assert (output_dir / "strike-006-right.png").exists()
    assert (output_dir / "extracted-frames" / "frame-000185.png").exists()
