"""End-to-end orchestration for the MVP karate kihon analysis pipeline."""

from __future__ import annotations

import json
from pathlib import Path
from typing import Any

from karate_analyzer.detection.mediapipe_extension_explorer import (
    analyze_extension_json,
)
from karate_analyzer.vision.mediapipe_pose_spike import analyze_video
from karate_analyzer.rendering.snapshot_renderer import (
    render_strike_snapshots_from_analysis,
)

JODAN_STATUSES = ("good", "too_low", "too_high", "unknown")


def run_analysis_pipeline(
    *,
    input_video: Path,
    output_directory: Path,
    smoothing_window: int = 5,
    group_threshold: float = 0.90,
    merge_gap_frames: int = 3,
    min_visibility: float = 0.5,
) -> dict[str, Any]:
    input_video = Path(input_video)
    output_directory = Path(output_directory)
    _validate_input_video(input_video)
    output_directory.mkdir(parents=True, exist_ok=True)

    video_payload = analyze_video(input_video, output_directory)
    video_landmarks_path = output_directory / "video_landmarks.json"
    if not video_landmarks_path.exists():
        _write_json(video_landmarks_path, video_payload)

    extension_summary = analyze_extension_json(
        video_landmarks_path,
        output_directory,
        smoothing_window=smoothing_window,
        group_threshold=group_threshold,
        merge_gap_frames=merge_gap_frames,
        min_visibility=min_visibility,
    )

    punch_event_landmarks_path = output_directory / "punch_event_landmarks.json"
    punch_event_payload = _read_json(punch_event_landmarks_path)
    events = punch_event_payload.get("punch_event_landmarks", [])
    if not events:
        raise ValueError("Strike detection produced no strike events.")

    rendered_paths = render_strike_snapshots_from_analysis(
        video_path=input_video,
        analysis_path=punch_event_landmarks_path,
        output_directory=output_directory / "rendered-strikes",
        frame_directory=output_directory / "extracted-frames",
    )
    if not rendered_paths:
        raise ValueError("Snapshot rendering produced no rendered strike images.")

    analysis_results = _build_analysis_results(
        input_video=input_video,
        events=events,
        rendered_paths=rendered_paths,
        output_directory=output_directory,
        expected_punch_count=int(extension_summary.get("expected_punch_count", 10)),
    )
    summary = _build_summary(
        input_video=input_video,
        video_payload=video_payload,
        extension_summary=extension_summary,
        analysis_results=analysis_results,
    )

    _write_json(output_directory / "analysis_results.json", analysis_results)
    _write_json(output_directory / "summary.json", summary)
    (output_directory / "report.md").write_text(
        _build_report(summary, analysis_results), encoding="utf-8"
    )

    return {
        "source_video": str(input_video),
        "output_directory": str(output_directory),
        "summary": summary,
        "analysis_results": analysis_results,
    }


def _validate_input_video(input_video: Path) -> None:
    if not input_video.exists():
        raise FileNotFoundError(f"Input video does not exist: {input_video}")
    if not input_video.is_file():
        raise ValueError(f"Input video path is not a file: {input_video}")


def _build_analysis_results(
    *,
    input_video: Path,
    events: list[dict[str, Any]],
    rendered_paths: list[Path],
    output_directory: Path,
    expected_punch_count: int,
) -> dict[str, Any]:
    snapshots = {
        index: _relative_path(path, output_directory)
        for index, path in enumerate(rendered_paths, start=1)
    }
    clean_events = []
    for position, event in enumerate(events, start=1):
        clean_events.append(
            {
                "event_index": event.get("event_index", position),
                "expected_side": event.get("expected_side"),
                "observed_side": event.get("observed_side"),
                "matches_expected_side": event.get("matches_expected_side"),
                "peak_frame_number": event.get("peak_frame_number"),
                "analysis_frame_number": event.get("analysis_frame_number"),
                "elbow_angle_degrees": event.get("elbow_angle_degrees"),
                "extension_distance": event.get("extension_distance"),
                "extension_velocity": event.get("extension_velocity"),
                "impact_frame_selection_strategy": event.get(
                    "impact_frame_selection_strategy"
                ),
                "impact_frame_reason": event.get("impact_frame_reason"),
                "strike_region_start_frame": event.get("strike_region_start_frame"),
                "strike_region_end_frame": event.get("strike_region_end_frame"),
                "timestamp_seconds": event.get("timestamp_seconds"),
                "impact_point": event.get("impact_point"),
                "chin_reference": event.get("chin_reference"),
                "jodan_reference": event.get("jodan_reference"),
                "analysis": event.get("analysis", {}),
                "snapshot_path": snapshots.get(position),
            }
        )
    return {
        "source_video": str(input_video),
        "expected_punch_count": expected_punch_count,
        "detected_punch_count": len(clean_events),
        "events": clean_events,
    }


def _build_summary(
    *,
    input_video: Path,
    video_payload: dict[str, Any],
    extension_summary: dict[str, Any],
    analysis_results: dict[str, Any],
) -> dict[str, Any]:
    counts = {status: 0 for status in JODAN_STATUSES}
    for event in analysis_results["events"]:
        status = (
            event.get("analysis", {}).get("jodan_height", {}).get("status", "unknown")
        )
        counts[status if status in counts else "unknown"] += 1
    frames = video_payload.get("frames", [])
    return {
        "source_video": str(input_video),
        "frame_count": video_payload.get("frame_count", len(frames)),
        "pose_detected_frame_count": video_payload.get(
            "detected_frame_count",
            sum(1 for frame in frames if frame.get("pose_detected")),
        ),
        "hand_detected_frame_count": video_payload.get(
            "hand_detected_frame_count",
            sum(1 for frame in frames if frame.get("hand_detected")),
        ),
        "face_detected_frame_count": video_payload.get(
            "face_detected_frame_count",
            sum(1 for frame in frames if frame.get("face_detected")),
        ),
        "expected_punch_count": extension_summary.get("expected_punch_count", 10),
        "detected_punch_count": analysis_results["detected_punch_count"],
        "jodan_height": counts,
        "outputs": {
            "video_landmarks": "video_landmarks.json",
            "punch_event_landmarks": "punch_event_landmarks.json",
            "analysis_results": "analysis_results.json",
            "report": "report.md",
            "rendered_snapshots": "rendered-strikes/",
        },
    }


def _build_report(summary: dict[str, Any], analysis_results: dict[str, Any]) -> str:
    counts = summary["jodan_height"]
    lines = [
        "# Karate Kihon Analysis Report",
        "",
        f"Source video: {summary['source_video']}",
        "",
        "## Summary",
        "",
        f"- Expected punches: {summary['expected_punch_count']}",
        f"- Detected punches: {summary['detected_punch_count']}",
        f"- Jodan height good: {counts['good']}",
        f"- Too low: {counts['too_low']}",
        f"- Too high: {counts['too_high']}",
        f"- Unknown: {counts['unknown']}",
        "",
        "## Strike Events",
        "",
        "| # | Expected side | Observed side | Frame | Time | Jodan height | Snapshot |",
        "|---|---------------|---------------|-------|------|--------------|----------|",
    ]
    for event in analysis_results["events"]:
        status = (
            event.get("analysis", {}).get("jodan_height", {}).get("status", "unknown")
        )
        timestamp = event.get("timestamp_seconds")
        time_text = "" if timestamp is None else f"{float(timestamp):.3f}s"
        lines.append(
            f"| {event.get('event_index', '')} | {event.get('expected_side') or ''} | "
            f"{event.get('observed_side') or ''} | {event.get('peak_frame_number') or ''} | "
            f"{time_text} | {status} | {event.get('snapshot_path') or ''} |"
        )
    return "\n".join(lines) + "\n"


def _relative_path(path: Path, base: Path) -> str:
    try:
        return path.relative_to(base).as_posix()
    except ValueError:
        return path.as_posix()


def _read_json(path: Path) -> dict[str, Any]:
    return json.loads(path.read_text(encoding="utf-8"))


def _write_json(path: Path, payload: dict[str, Any]) -> None:
    path.write_text(
        json.dumps(payload, indent=2, sort_keys=True) + "\n", encoding="utf-8"
    )
