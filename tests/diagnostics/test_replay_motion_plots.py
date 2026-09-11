import json

import pytest

from karate_analyzer.diagnostics.replay_motion_plots import render_replay_trace


def test_replay_trace_plot_renders_deterministically(tmp_path):
    pytest.importorskip("matplotlib")
    trace = tmp_path / "trace.json"
    trace.write_text(json.dumps(_trace()), encoding="utf-8")
    first = render_replay_trace(trace, tmp_path / "first.png")
    second = render_replay_trace(trace, tmp_path / "second.png")
    assert first.read_bytes() == second.read_bytes()


def test_replay_trace_plot_rejects_unknown_schema(tmp_path):
    trace = tmp_path / "trace.json"
    trace.write_text('{"schema_version":"future","frames":[{}]}', encoding="utf-8")
    with pytest.raises(ValueError, match="Unsupported"):
        render_replay_trace(trace, tmp_path / "plot.png")


def _trace():
    frames = []
    for timestamp, motion in ((0, 0.0), (100, 0.8), (200, 0.0)):
        frames.append({
            "timestamp_ms": timestamp,
            "articulated_motion": motion,
            "image_space_motion": motion / 2,
            "slow_displacement": motion / 3,
            "coverage": 0.9,
            "region_balanced_coverage": 0.95,
            "same_similarity": 1.0 - motion / 2,
            "mirrored_similarity": 0.2,
            "regions": {"LEFT_ARM": {"coverage": 0.9, "robust_motion": motion, "clamp_count": 0}},
        })
    return {
        "schema_version": "motion-replay-trace-v1",
        "sequence_id": "synthetic-arm",
        "parameter_set": "conservative",
        "final_state": "COMPLETE",
        "trusted_movement_start_ms": 100,
        "trusted_movement_end_ms": 200,
        "frames": frames,
        "transitions": [{"to": "MOVING", "decision_timestamp_ms": 100, "boundary_timestamp_ms": 100}],
    }
