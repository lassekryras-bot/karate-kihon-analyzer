"""Render deterministic Kotlin motion-replay traces without making replay decisions."""

from __future__ import annotations

import json
import argparse
from pathlib import Path
from typing import Any


def render_replay_trace(trace_path: Path, output_path: Path) -> Path:
    payload = json.loads(trace_path.read_text(encoding="utf-8"))
    if payload.get("schema_version") != "motion-replay-trace-v1":
        raise ValueError("Unsupported motion replay trace schema")
    frames = payload.get("frames", [])
    if not frames:
        raise ValueError("Motion replay trace contains no frames")

    import matplotlib

    matplotlib.use("Agg")
    import matplotlib.pyplot as plt

    times = [frame["timestamp_ms"] / 1000 for frame in frames]
    figure, axes = plt.subplots(4, 1, figsize=(12, 11), sharex=True)
    _line(axes[0], times, frames, "articulated_motion", "Articulated")
    _line(axes[0], times, frames, "image_space_motion", "Image-space")
    _line(axes[0], times, frames, "slow_displacement", "Slow displacement")
    axes[0].set_ylabel("Motion")
    _line(axes[1], times, frames, "coverage", "Completion coverage")
    _line(axes[1], times, frames, "region_balanced_coverage", "Balanced coverage")
    for region in _regions(frames):
        axes[1].plot(times, [_region_value(frame, region, "coverage") for frame in frames], alpha=0.45, label=region)
    axes[1].set_ylim(-0.02, 1.02)
    axes[1].set_ylabel("Coverage")
    _line(axes[2], times, frames, "same_similarity", "Same")
    _line(axes[2], times, frames, "mirrored_similarity", "Mirrored")
    axes[2].set_ylabel("Similarity")
    for region in _regions(frames):
        axes[3].plot(times, [_region_value(frame, region, "robust_motion") for frame in frames], label=region)
        clamp_times = [frame["timestamp_ms"] / 1000 for frame in frames if _region_value(frame, region, "clamp_count", 0) > 0]
        if clamp_times:
            axes[3].scatter(clamp_times, [0] * len(clamp_times), marker="x", s=24)
    axes[3].set_ylabel("Regional motion")
    axes[3].set_xlabel("Time (seconds)")

    for axis in axes:
        _markers(axis, payload)
        _intervals(axis, payload)
        axis.grid(alpha=0.2)
        axis.legend(loc="upper right", fontsize=7, ncol=3)
    figure.suptitle(f"{payload['sequence_id']} · {payload['parameter_set']} · {payload['final_state']}")
    figure.tight_layout()
    output_path.parent.mkdir(parents=True, exist_ok=True)
    figure.savefig(output_path, dpi=140)
    plt.close(figure)
    return output_path


def _line(axis: Any, times: list[float], frames: list[dict[str, Any]], key: str, label: str) -> None:
    axis.plot(times, [frame.get(key) for frame in frames], label=label)


def _regions(frames: list[dict[str, Any]]) -> list[str]:
    return sorted({region for frame in frames for region in frame.get("regions", {})})


def _region_value(frame: dict[str, Any], region: str, key: str, default: Any = None) -> Any:
    return frame.get("regions", {}).get(region, {}).get(key, default)


def _markers(axis: Any, payload: dict[str, Any]) -> None:
    markers = [
        (payload.get("trusted_movement_start_ms"), "tab:green", "Trusted start"),
        (payload.get("trusted_movement_end_ms"), "tab:red", "Trusted end"),
        (payload.get("cue_timestamp_ms"), "tab:blue", "Cue"),
        (payload.get("activity_deadline_ms"), "tab:orange", "Deadline"),
    ]
    for transition in payload.get("transitions", []):
        markers.append((transition.get("decision_timestamp_ms"), "0.55", transition.get("to", "transition")))
        boundary = transition.get("boundary_timestamp_ms")
        if boundary is not None:
            markers.append((boundary, "tab:purple", f"{transition.get('to')} boundary"))
    for timestamp, color, label in markers:
        if timestamp is not None:
            axis.axvline(timestamp / 1000, color=color, alpha=0.35, linewidth=1, label=label)


def _intervals(axis: Any, payload: dict[str, Any]) -> None:
    groups = (
        ("terminal_stable_interval", "tab:green", "Terminal stable"),
        ("intermediate_stable_plateaus", "tab:orange", "Intermediate plateau"),
        ("tracking_loss_intervals", "tab:red", "Tracking loss"),
        ("ambiguous_intervals", "0.5", "Ambiguous"),
    )
    for key, color, label in groups:
        value = payload.get(key)
        intervals = [] if value is None else ([value] if key == "terminal_stable_interval" else value)
        for index, (start, end) in enumerate(intervals):
            axis.axvspan(
                start / 1000,
                end / 1000,
                color=color,
                alpha=0.08,
                label=label if index == 0 else None,
            )


def main() -> None:
    parser = argparse.ArgumentParser(description="Plot a Kotlin motion replay diagnostic trace.")
    parser.add_argument("trace", type=Path)
    parser.add_argument("output", type=Path)
    args = parser.parse_args()
    render_replay_trace(args.trace, args.output)


if __name__ == "__main__":
    main()
