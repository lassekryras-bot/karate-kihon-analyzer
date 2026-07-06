from __future__ import annotations

import json
from pathlib import Path

import pytest

from karate_analyzer import mediapipe_pose_spike as spike


def test_analyze_image_rejects_missing_path(tmp_path: Path) -> None:
    missing = tmp_path / "missing.png"

    with pytest.raises(FileNotFoundError, match="Missing image file"):
        spike.analyze_image(missing, tmp_path / "out")


def test_analyze_image_creates_output_directory_and_json(
    tmp_path: Path, monkeypatch: pytest.MonkeyPatch
) -> None:
    image_path = tmp_path / "image.png"
    image_path.write_bytes(b"not a real image but cv2 is mocked")
    output_directory = tmp_path / "debug"

    class FakeCv2:
        COLOR_BGR2RGB = 1

        def imread(self, path: str) -> list[list[int]]:
            return [[0]]

        def cvtColor(self, image: object, code: int) -> object:
            return image

        def imwrite(self, path: str, image: object) -> bool:
            Path(path).write_bytes(b"debug")
            return True

    class FakeRunner:
        def __enter__(self) -> "FakeRunner":
            return self

        def __exit__(self, *_args: object) -> None:
            pass

        def detect_image(self, image_bgr: object) -> spike.DetectionResult:
            return spike.DetectionResult(
                timestamp_ms=None,
                pose_landmarks=[[{"index": 0, "x": 0.5, "y": 0.25, "visibility": 0.9}]],
                pose_world_landmarks=[],
            )

    monkeypatch.setattr(spike, "_import_cv2", lambda: FakeCv2())
    monkeypatch.setattr(spike, "_create_pose_runner", lambda running_mode: FakeRunner())
    monkeypatch.setattr(spike, "_draw_landmarks", lambda image, landmarks: image)

    payload = spike.analyze_image(image_path, output_directory)

    assert output_directory.is_dir()
    assert payload["pose_detected"] is True
    json_payload = json.loads((output_directory / "image_landmarks.json").read_text())
    assert json_payload["poses"][0][0]["visibility"] == 0.9
    assert (output_directory / "image_landmarks.png").exists()


def test_run_default_workflow_prints_helpful_message_when_no_inputs(
    tmp_path: Path, monkeypatch: pytest.MonkeyPatch, capsys: pytest.CaptureFixture[str]
) -> None:
    monkeypatch.chdir(tmp_path)

    spike.run_default_workflow()

    captured = capsys.readouterr()
    assert "Place a test image in input/images/" in captured.out
    assert "test video in input/videos/" in captured.out
