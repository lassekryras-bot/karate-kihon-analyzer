from __future__ import annotations

import sys
from pathlib import Path
from types import SimpleNamespace

import pytest
from typer.testing import CliRunner

from karate_analyzer.frame_extractor import (
    ExtractedFrameMetadata,
    FrameExtractionError,
    default_frame_output_path,
    extract_frame,
)
from karate_analyzer.main import app


class _FakeFrame:
    def __init__(self, width: int, height: int) -> None:
        self.shape = (height, width, 3)


class _FakeCapture:
    CAP_PROP_FRAME_COUNT = 7
    CAP_PROP_POS_FRAMES = 1
    CAP_PROP_FPS = 5

    def __init__(self, path: str, *, opened: bool = True, frame_count: int = 3) -> None:
        self.path = path
        self.opened = opened
        self.frame_count = frame_count
        self.position = 0
        self.released = False

    def isOpened(self) -> bool:
        return self.opened

    def get(self, prop: int) -> float:
        if prop == self.CAP_PROP_FRAME_COUNT:
            return float(self.frame_count)
        if prop == self.CAP_PROP_POS_FRAMES:
            return float(self.position)
        if prop == self.CAP_PROP_FPS:
            return 10.0
        return 0.0

    def set(self, prop: int, value: int) -> bool:
        if prop != self.CAP_PROP_POS_FRAMES:
            return False
        self.position = int(value)
        return True

    def read(self):
        self.position += 1
        return True, _FakeFrame(width=32, height=24)

    def release(self) -> None:
        self.released = True


def _install_fake_cv2(monkeypatch: pytest.MonkeyPatch, *, frame_count: int = 3) -> None:
    fake_cv2 = SimpleNamespace(
        CAP_PROP_FRAME_COUNT=_FakeCapture.CAP_PROP_FRAME_COUNT,
        CAP_PROP_POS_FRAMES=_FakeCapture.CAP_PROP_POS_FRAMES,
        CAP_PROP_FPS=_FakeCapture.CAP_PROP_FPS,
        VideoCapture=lambda path: _FakeCapture(path, frame_count=frame_count),
        imwrite=lambda path, frame: Path(path).write_bytes(b"\x89PNG\r\n\x1a\nfake") > 0,
    )
    monkeypatch.setitem(sys.modules, "cv2", fake_cv2)


def _write_placeholder_video(path: Path) -> None:
    path.write_bytes(b"placeholder video")


def test_missing_video_raises_helpful_error(tmp_path: Path, monkeypatch: pytest.MonkeyPatch) -> None:
    _install_fake_cv2(monkeypatch)
    missing_video = tmp_path / "missing.mp4"

    with pytest.raises(FileNotFoundError, match="Video file does not exist"):
        extract_frame(missing_video, 0, tmp_path / "frame.png")


def test_negative_frame_rejected(tmp_path: Path, monkeypatch: pytest.MonkeyPatch) -> None:
    _install_fake_cv2(monkeypatch)
    video_path = tmp_path / "tiny.mp4"
    _write_placeholder_video(video_path)

    with pytest.raises(ValueError, match="non-negative"):
        extract_frame(video_path, -1, tmp_path / "frame.png")


def test_output_directory_is_created_and_frame_image_is_written(
    tmp_path: Path, monkeypatch: pytest.MonkeyPatch
) -> None:
    _install_fake_cv2(monkeypatch)
    video_path = tmp_path / "tiny.mp4"
    output_path = tmp_path / "new" / "nested" / "frame.png"
    _write_placeholder_video(video_path)

    extract_frame(video_path, 1, output_path)

    assert output_path.exists()
    assert output_path.read_bytes().startswith(b"\x89PNG\r\n\x1a\n")


def test_metadata_is_returned(tmp_path: Path, monkeypatch: pytest.MonkeyPatch) -> None:
    _install_fake_cv2(monkeypatch)
    video_path = tmp_path / "tiny.mp4"
    output_path = tmp_path / "frame.png"
    _write_placeholder_video(video_path)

    metadata = extract_frame(video_path, 2, output_path)

    assert metadata == ExtractedFrameMetadata(
        video_path=video_path,
        requested_frame_number=2,
        actual_frame_number=2,
        output_path=output_path,
        frame_width=32,
        frame_height=24,
        fps=10.0,
        timestamp_seconds=0.2,
    )


def test_frame_number_beyond_video_length_is_rejected(
    tmp_path: Path, monkeypatch: pytest.MonkeyPatch
) -> None:
    _install_fake_cv2(monkeypatch, frame_count=2)
    video_path = tmp_path / "tiny.mp4"
    _write_placeholder_video(video_path)

    with pytest.raises(FrameExtractionError, match="beyond video length"):
        extract_frame(video_path, 2, tmp_path / "frame.png")


def test_default_output_path_is_deterministic() -> None:
    assert default_frame_output_path(185) == Path("output/frames/frame-000185.png")


def test_cli_uses_deterministic_default_output_path(
    tmp_path: Path, monkeypatch: pytest.MonkeyPatch
) -> None:
    _install_fake_cv2(monkeypatch)
    video_path = tmp_path / "tiny.mp4"
    _write_placeholder_video(video_path)
    monkeypatch.chdir(tmp_path)

    result = CliRunner().invoke(
        app,
        ["extract-frame", "--video", str(video_path), "--frame", "1"],
    )

    assert result.exit_code == 0
    assert (tmp_path / "output" / "frames" / "frame-000001.png").exists()
    assert "output_path: output/frames/frame-000001.png" in result.stdout


def test_render_strike_snapshots_cli_renders_deterministic_output(
    tmp_path: Path, monkeypatch: pytest.MonkeyPatch
) -> None:
    video_path = tmp_path / "tiny.mp4"
    analysis_path = tmp_path / "punch_event_landmarks.json"
    output_dir = tmp_path / "rendered"
    _write_placeholder_video(video_path)
    analysis_path.write_text(
        '{"punch_event_landmarks":[{"event_index":1,"observed_side":"right",'
        '"peak_frame_number":2,"shoulder":{"x":0.6,"y":0.4,"visibility":0.9},'
        '"elbow":{"x":0.7,"y":0.4,"visibility":0.9},'
        '"wrist":{"x":0.8,"y":0.4,"visibility":0.9},'
        '"head_reference_candidate":{"source":"nose","x":0.5,"y":0.2,"visibility":0.9},'
        '"visibility":{"minimum_required_landmark_visibility":0.9}}]}',
        encoding="utf-8",
    )

    def fake_render_pipeline(*, video_path, analysis_path, output_directory, frame_directory=None):
        output = Path(output_directory)
        output.mkdir(parents=True, exist_ok=True)
        rendered = output / "strike-001-right.png"
        rendered.write_bytes(b"\x89PNG\r\n\x1a\nfake")
        return [rendered]

    monkeypatch.setattr(
        "karate_analyzer.rendering.snapshot_renderer.render_strike_snapshots_from_analysis",
        fake_render_pipeline,
    )

    result = CliRunner().invoke(
        app,
        [
            "render-strike-snapshots",
            "--video",
            str(video_path),
            "--analysis",
            str(analysis_path),
            "--output",
            str(output_dir),
        ],
    )

    assert result.exit_code == 0
    assert (output_dir / "strike-001-right.png").exists()
    assert "Rendered 1 strike snapshot(s)" in result.stdout
