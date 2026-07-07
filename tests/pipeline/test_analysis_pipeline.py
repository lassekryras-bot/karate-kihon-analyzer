from __future__ import annotations

import json
from pathlib import Path

import pytest
from typer.testing import CliRunner

from karate_analyzer.pipeline.analysis_pipeline import run_analysis_pipeline
from karate_analyzer.main import app


def test_pipeline_creates_expected_files(
    tmp_path: Path, monkeypatch: pytest.MonkeyPatch
) -> None:
    video = _write_video(tmp_path / "kihon.mp4")
    output = tmp_path / "run-001"
    _install_pipeline_fakes(monkeypatch)

    run_analysis_pipeline(input_video=video, output_directory=output)

    assert (output / "analysis_results.json").exists()
    assert (output / "summary.json").exists()
    assert (output / "report.md").exists()


def test_summary_counts_jodan_statuses(
    tmp_path: Path, monkeypatch: pytest.MonkeyPatch
) -> None:
    video = _write_video(tmp_path / "kihon.mp4")
    output = tmp_path / "run-001"
    _install_pipeline_fakes(
        monkeypatch,
        events=[
            _event(1, "right", "good"),
            _event(2, "left", "too_low"),
            _event(3, "right", "too_high"),
            _event(4, "left", "unknown"),
        ],
    )

    result = run_analysis_pipeline(input_video=video, output_directory=output)

    assert result["summary"]["jodan_height"] == {
        "good": 1,
        "too_low": 1,
        "too_high": 1,
        "unknown": 1,
    }


def test_snapshot_paths_are_added_to_analysis_results(
    tmp_path: Path, monkeypatch: pytest.MonkeyPatch
) -> None:
    video = _write_video(tmp_path / "kihon.mp4")
    output = tmp_path / "run-001"
    _install_pipeline_fakes(
        monkeypatch,
        events=[_event(1, "right", "good"), _event(2, "left", "too_low")],
        rendered_names=["strike-001-right.png", "strike-002-left.png"],
    )

    run_analysis_pipeline(input_video=video, output_directory=output)

    analysis_results = json.loads((output / "analysis_results.json").read_text())
    assert [event["snapshot_path"] for event in analysis_results["events"]] == [
        "rendered-strikes/strike-001-right.png",
        "rendered-strikes/strike-002-left.png",
    ]


def test_empty_strike_events_fail_clearly(
    tmp_path: Path, monkeypatch: pytest.MonkeyPatch
) -> None:
    video = _write_video(tmp_path / "kihon.mp4")
    _install_pipeline_fakes(monkeypatch, events=[])

    with pytest.raises(ValueError, match="no strike events"):
        run_analysis_pipeline(input_video=video, output_directory=tmp_path / "run")


def test_cli_calls_pipeline(tmp_path: Path, monkeypatch: pytest.MonkeyPatch) -> None:
    video = _write_video(tmp_path / "kihon.mp4")
    output = tmp_path / "run-001"
    calls = []

    def fake_run_analysis_pipeline(**kwargs):
        calls.append(kwargs)
        return {
            "output_directory": str(kwargs["output_directory"]),
            "summary": {
                "source_video": str(kwargs["input_video"]),
                "expected_punch_count": 10,
                "detected_punch_count": 2,
                "jodan_height": {
                    "good": 1,
                    "too_low": 1,
                    "too_high": 0,
                    "unknown": 0,
                },
            },
        }

    monkeypatch.setattr(
        "karate_analyzer.pipeline.analysis_pipeline.run_analysis_pipeline",
        fake_run_analysis_pipeline,
    )

    result = CliRunner().invoke(app, ["analyze", str(video), "--output", str(output)])

    assert result.exit_code == 0
    assert calls[0]["input_video"] == video
    assert calls[0]["output_directory"] == output
    assert "Analysis complete." in result.output
    assert "Detected punches: 2 of expected 10" in result.output


def _install_pipeline_fakes(
    monkeypatch: pytest.MonkeyPatch,
    *,
    events: list[dict] | None = None,
    rendered_names: list[str] | None = None,
) -> None:
    events = (
        [_event(1, "right", "good"), _event(2, "left", "too_low")]
        if events is None
        else events
    )

    def fake_analyze_video(video_path: Path, output_directory: Path) -> dict:
        payload = {
            "source": str(video_path),
            "kind": "video",
            "frame_count": 4,
            "detected_frame_count": 3,
            "hand_detected_frame_count": 2,
            "face_detected_frame_count": 1,
            "frames": [],
        }
        (output_directory / "video_landmarks.json").write_text(json.dumps(payload))
        return payload

    def fake_analyze_extension_json(
        input_path: Path, output_directory: Path, **kwargs
    ) -> dict:
        for filename in [
            "extension_by_frame.json",
            "extension_by_frame.csv",
            "candidate_peak_frames.json",
            "grouped_peak_frames.json",
            "punch_event_candidates.json",
        ]:
            (output_directory / filename).write_text("{}")
        (output_directory / "punch_event_landmarks.json").write_text(
            json.dumps({"punch_event_landmarks": events})
        )
        return {"expected_punch_count": 10, "punch_event_candidate_count": len(events)}

    def fake_render_strike_snapshots_from_analysis(**kwargs) -> list[Path]:
        output_directory = Path(kwargs["output_directory"])
        output_directory.mkdir(parents=True, exist_ok=True)
        names = rendered_names or [
            f"strike-{event['event_index']:03d}-{event['observed_side']}.png"
            for event in events
        ]
        paths = [output_directory / name for name in names]
        for path in paths:
            path.write_bytes(b"png")
        return paths

    monkeypatch.setattr(
        "karate_analyzer.pipeline.analysis_pipeline.analyze_video", fake_analyze_video
    )
    monkeypatch.setattr(
        "karate_analyzer.pipeline.analysis_pipeline.analyze_extension_json",
        fake_analyze_extension_json,
    )
    monkeypatch.setattr(
        "karate_analyzer.pipeline.analysis_pipeline.render_strike_snapshots_from_analysis",
        fake_render_strike_snapshots_from_analysis,
    )


def _event(index: int, side: str, status: str) -> dict:
    expected_side = "right" if index % 2 else "left"
    return {
        "event_index": index,
        "expected_side": expected_side,
        "observed_side": side,
        "matches_expected_side": expected_side == side,
        "peak_frame_number": 100 + index,
        "timestamp_seconds": index / 10,
        "impact_point": {"x": 0.8, "y": 0.2},
        "chin_reference": {"x": 0.5, "y": 0.2},
        "jodan_reference": {"x": 0.5, "y": 0.2},
        "analysis": {"jodan_height": {"status": status, "message": status}},
    }


def _write_video(path: Path) -> Path:
    path.write_bytes(b"video")
    return path
