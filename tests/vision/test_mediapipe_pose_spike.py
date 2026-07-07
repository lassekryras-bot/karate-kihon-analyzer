from __future__ import annotations

import json
from pathlib import Path

import pytest

from karate_analyzer.vision import mediapipe_pose_spike as spike


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


def test_tasks_runner_does_not_require_mediapipe_solutions(
    tmp_path: Path, monkeypatch: pytest.MonkeyPatch
) -> None:
    import sys
    import types

    monkeypatch.chdir(tmp_path)
    (tmp_path / "input").mkdir()
    (tmp_path / "input" / "pose_landmarker.task").write_bytes(b"pose model")

    class Landmark:
        x = 0.5
        y = 0.25
        z = 0.0
        visibility = 0.9

    class PoseResult:
        pose_landmarks = [[Landmark()]]
        pose_world_landmarks = [[]]

    class FakePoseLandmarker:
        @classmethod
        def create_from_options(cls, options: object) -> "FakePoseLandmarker":
            return cls()

        def detect(self, image: object) -> PoseResult:
            return PoseResult()

        def close(self) -> None:
            pass

    class FakeImageFormat:
        SRGB = "SRGB"

    class FakeImage:
        def __init__(self, image_format: object, data: object) -> None:
            self.image_format = image_format
            self.data = data

    class FakeBaseOptions:
        def __init__(self, model_asset_path: str) -> None:
            self.model_asset_path = model_asset_path

    class FakePoseLandmarkerOptions:
        def __init__(self, **kwargs: object) -> None:
            self.kwargs = kwargs

    fake_mp = types.ModuleType("mediapipe")
    fake_mp.Image = FakeImage
    fake_mp.ImageFormat = FakeImageFormat

    fake_tasks = types.ModuleType("mediapipe.tasks")
    fake_python = types.ModuleType("mediapipe.tasks.python")
    fake_python.BaseOptions = FakeBaseOptions
    fake_vision = types.ModuleType("mediapipe.tasks.python.vision")
    fake_vision.RunningMode = types.SimpleNamespace(IMAGE="IMAGE", VIDEO="VIDEO")
    fake_vision.PoseLandmarker = FakePoseLandmarker
    fake_vision.PoseLandmarkerOptions = FakePoseLandmarkerOptions
    fake_python.vision = fake_vision
    fake_tasks.python = fake_python

    monkeypatch.setitem(sys.modules, "mediapipe", fake_mp)
    monkeypatch.setitem(sys.modules, "mediapipe.tasks", fake_tasks)
    monkeypatch.setitem(sys.modules, "mediapipe.tasks.python", fake_python)
    monkeypatch.setitem(sys.modules, "mediapipe.tasks.python.vision", fake_vision)
    monkeypatch.setattr(spike, "_bgr_to_rgb", lambda image: image)

    with spike._TasksPoseRunner("IMAGE") as runner:
        detection = runner.detect_image(object())

    assert detection.pose_landmarks[0][0]["visibility"] == 0.9
    assert detection.hand_landmarks == []
    assert detection.face_landmarks == []


def test_tasks_runner_uses_hand_landmarker_when_model_exists(
    tmp_path: Path, monkeypatch: pytest.MonkeyPatch
) -> None:
    import sys
    import types

    monkeypatch.chdir(tmp_path)
    (tmp_path / "input").mkdir()
    (tmp_path / "input" / "pose_landmarker.task").write_bytes(b"pose model")
    (tmp_path / "input" / "hand_landmarker.task").write_bytes(b"hand model")

    class Landmark:
        x = 0.1
        y = 0.2
        visibility = 0.8

    class PoseResult:
        pose_landmarks = [[Landmark()]]
        pose_world_landmarks = [[]]

    class HandResult:
        hand_landmarks = [[Landmark()]]
        handedness = [[types.SimpleNamespace(category_name="Right", score=0.99)]]

    class FakePoseLandmarker:
        @classmethod
        def create_from_options(cls, options: object) -> "FakePoseLandmarker":
            return cls()

        def detect(self, image: object) -> PoseResult:
            return PoseResult()

        def close(self) -> None:
            pass

    class FakeHandLandmarker:
        @classmethod
        def create_from_options(cls, options: object) -> "FakeHandLandmarker":
            return cls()

        def detect(self, image: object) -> HandResult:
            return HandResult()

        def close(self) -> None:
            pass

    class FakeOptions:
        def __init__(self, **kwargs: object) -> None:
            self.kwargs = kwargs

    fake_mp = types.ModuleType("mediapipe")
    fake_mp.Image = lambda image_format, data: data
    fake_mp.ImageFormat = types.SimpleNamespace(SRGB="SRGB")
    fake_tasks = types.ModuleType("mediapipe.tasks")
    fake_python = types.ModuleType("mediapipe.tasks.python")
    fake_python.BaseOptions = lambda model_asset_path: model_asset_path
    fake_vision = types.ModuleType("mediapipe.tasks.python.vision")
    fake_vision.RunningMode = types.SimpleNamespace(IMAGE="IMAGE", VIDEO="VIDEO")
    fake_vision.PoseLandmarker = FakePoseLandmarker
    fake_vision.PoseLandmarkerOptions = FakeOptions
    fake_vision.HandLandmarker = FakeHandLandmarker
    fake_vision.HandLandmarkerOptions = FakeOptions
    fake_python.vision = fake_vision
    fake_tasks.python = fake_python

    monkeypatch.setitem(sys.modules, "mediapipe", fake_mp)
    monkeypatch.setitem(sys.modules, "mediapipe.tasks", fake_tasks)
    monkeypatch.setitem(sys.modules, "mediapipe.tasks.python", fake_python)
    monkeypatch.setitem(sys.modules, "mediapipe.tasks.python.vision", fake_vision)
    monkeypatch.setattr(spike, "_bgr_to_rgb", lambda image: image)

    with spike._TasksPoseRunner("IMAGE") as runner:
        detection = runner.detect_image(object())

    assert detection.hand_landmarks == [
        {
            "landmarks": [{"index": 0, "x": 0.1, "y": 0.2, "visibility": 0.8}],
            "handedness": {"label": "Right", "score": 0.99},
        }
    ]
    assert detection.face_landmarks == []
