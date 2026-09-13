"""Phase 5: Render Video Clips and Timelines for Evaluated Variants.

Produces retained movement clips with 150 ms pre-roll and 200 ms post-roll
for Recording A and Recording B under Phase 5 DISABLED policy.
"""
from __future__ import annotations

import json
from pathlib import Path
import cv2
import numpy as np
import matplotlib
matplotlib.use("Agg")
import matplotlib.pyplot as plt

ROOT = Path(__file__).resolve().parents[2]
SRC_A = ROOT / "output/task5/phase5_variants/recording_a/DISABLED"
SRC_B = ROOT / "output/task5/phase5_variants/recording_b/DISABLED"

DEST = ROOT / "docs/validation/task5/phase5-clips"
DEST_A = DEST / "recording-a"
DEST_B = DEST / "recording-b"

VIDEO_A = ROOT / "input/task5/1000002073.mp4"
VIDEO_B = ROOT / "input/task5/20260911_223447.mp4"


def generate_timeline_plot(frames: list[dict], segments: list[dict], title: str, out_path: Path) -> None:
    times = [f["timestamp_ms"] / 1000.0 for f in frames]
    ets = [f.get("top2", {}).get("translation_evidence") for f in frames]
    eas = [f.get("top2", {}).get("angular_evidence") for f in frames]

    fig, (ax1, ax2) = plt.subplots(2, 1, figsize=(14, 8), sharex=True)

    # Translation
    ax1.plot(times, ets, label="ET (Top-2 Mean Speed)", color="#1f77b4", lw=1.5)
    ax1.axhline(0.70, color="red", linestyle="--", alpha=0.7, label="Moving (0.70 Lref/s)")
    ax1.axhline(0.45, color="green", linestyle="--", alpha=0.7, label="Quiet (0.45 Lref/s)")
    ax1.set_ylabel("Translation (Lref/s)")
    ax1.set_title(f"{title} - Top-2 Causal Kinematic Evidence & Continuous Segmentation")
    ax1.grid(True, alpha=0.3)
    ax1.legend(loc="upper right")

    # Angular
    ax2.plot(times, eas, label="EA (Top-2 Mean Angular Rate)", color="#ff7f0e", lw=1.5)
    ax2.axhline(35.0, color="red", linestyle="--", alpha=0.7, label="Moving (35.0 deg/s)")
    ax2.axhline(20.0, color="green", linestyle="--", alpha=0.7, label="Quiet (20.0 deg/s)")
    ax2.set_xlabel("Session Timestamp (seconds)")
    ax2.set_ylabel("Angular (deg/s)")
    ax2.grid(True, alpha=0.3)
    ax2.legend(loc="upper right")

    # Highlight segments
    for seg in segments:
        if seg.get("completed"):
            s_t = seg["start_boundary_timestamp_ms"] / 1000.0
            e_t = seg["terminal_boundary_timestamp_ms"] / 1000.0
            num = seg["movement_number"]
            for ax in (ax1, ax2):
                ax.axvspan(s_t, e_t, color="green", alpha=0.15)
            mid_t = (s_t + e_t) / 2.0
            ax1.text(mid_t, ax1.get_ylim()[1] * 0.85, f"M{num}", color="darkgreen",
                     fontweight="bold", ha="center", fontsize=9,
                     bbox=dict(boxstyle="round,pad=0.2", facecolor="white", alpha=0.8, edgecolor="green"))

    plt.tight_layout()
    out_path.parent.mkdir(parents=True, exist_ok=True)
    plt.savefig(out_path, dpi=150)
    plt.close()
    print(f"Saved timeline plot to {out_path}")


def render_movement_clip(
    video_path: Path,
    trace_frames: list[dict],
    start_ms: int,
    end_ms: int,
    out_path: Path,
    title: str,
    pre_roll_ms: int = 150,
    post_roll_ms: int = 200,
) -> dict:
    clip_start_ms = max(0, start_ms - pre_roll_ms)
    clip_end_ms = end_ms + post_roll_ms

    cap = cv2.VideoCapture(str(video_path))
    if not cap.isOpened():
        raise RuntimeError(f"Cannot open video: {video_path}")

    fps = cap.get(cv2.CAP_PROP_FPS)
    if fps <= 0 or not np.isfinite(fps):
        fps = 49.18
    width = int(cap.get(cv2.CAP_PROP_FRAME_WIDTH))
    height = int(cap.get(cv2.CAP_PROP_FRAME_HEIGHT))

    # Match timestamps to frame indices
    f_start = 0
    f_end = len(trace_frames) - 1
    for i, f in enumerate(trace_frames):
        if f["timestamp_ms"] <= clip_start_ms:
            f_start = i
        if f["timestamp_ms"] <= clip_end_ms:
            f_end = i

    out_path.parent.mkdir(parents=True, exist_ok=True)
    fourcc = cv2.VideoWriter_fourcc(*"mp4v")
    out = cv2.VideoWriter(str(out_path), fourcc, fps, (width, height))

    cap.set(cv2.CAP_PROP_POS_FRAMES, f_start)
    rendered_count = 0

    for idx in range(f_start, min(f_end + 1, len(trace_frames))):
        ret, frame = cap.read()
        if not ret:
            break
        meta = trace_frames[idx]
        t = meta["timestamp_ms"]
        st = meta["state"]

        top2 = meta.get("top2", {})
        et = top2.get("translation_evidence")
        ea = top2.get("angular_evidence")
        dt = top2.get("translation_decision", "UNKNOWN")
        da = top2.get("angular_decision", "UNKNOWN")

        # Overlay banner
        cv2.rectangle(frame, (0, 0), (width, 80), (20, 20, 20), -1)
        cv2.putText(frame, title, (20, 30), cv2.FONT_HERSHEY_SIMPLEX, 0.8, (255, 255, 255), 2)
        cv2.putText(
            frame,
            f"t={t:5d}ms | State: {st:<8s} | ET={et:.2f} ({dt}) | EA={ea:.1f} ({da})",
            (20, 65),
            cv2.FONT_HERSHEY_SIMPLEX,
            0.6,
            (0, 255, 255) if st == "MOVING" else (0, 255, 0) if st == "SETTLING" else (200, 200, 200),
            1,
        )

        out.write(frame)
        rendered_count += 1

    cap.release()
    out.release()
    print(f"Rendered {out_path.name}: {rendered_count} frames ({clip_start_ms}ms - {clip_end_ms}ms)")
    return {
        "clip_file": str(out_path.relative_to(ROOT)),
        "clip_start_ms": clip_start_ms,
        "clip_end_ms": clip_end_ms,
        "rendered_frames": rendered_count,
    }


def main():
    print("Generating Phase 5 retained video clips and timeline plots...")
    DEST_A.mkdir(parents=True, exist_ok=True)
    DEST_B.mkdir(parents=True, exist_ok=True)

    # 1. Recording A
    res_a = json.loads((SRC_A / "blind-session-result.json").read_text())
    trace_a = json.loads((SRC_A / "blind-session.trace.json").read_text())
    generate_timeline_plot(trace_a["frames"], res_a["segments"], "Recording A (Phase 5 DISABLED)", DEST_A / "recording-a-timeline.png")

    for seg in res_a["segments"]:
        num = seg["movement_number"]
        if seg["completed"]:
            s_ms = seg["start_boundary_timestamp_ms"]
            e_ms = seg["terminal_boundary_timestamp_ms"]
            out_clip = DEST_A / f"movement-{num:02d}.mp4"
            title = f"MOVEMENT {num:02d} (Span: {s_ms}-{e_ms} ms, Dur: {seg['duration_ms']} ms)"
            render_movement_clip(VIDEO_A, trace_a["frames"], s_ms, e_ms, out_clip, title)

    # 2. Recording B
    res_b = json.loads((SRC_B / "blind-session-result.json").read_text())
    trace_b = json.loads((SRC_B / "blind-session.trace.json").read_text())
    generate_timeline_plot(trace_b["frames"], res_b["segments"], "Recording B (Phase 5 DISABLED)", DEST_B / "recording-b-timeline.png")

    for seg in res_b["segments"]:
        num = seg["movement_number"]
        if seg["completed"]:
            s_ms = seg["start_boundary_timestamp_ms"]
            e_ms = seg["terminal_boundary_timestamp_ms"]
            out_clip = DEST_B / f"movement-{num:02d}.mp4"
            title = f"MOVEMENT {num:02d} (Span: {s_ms}-{e_ms} ms, Dur: {seg['duration_ms']} ms)"
            render_movement_clip(VIDEO_B, trace_b["frames"], s_ms, e_ms, out_clip, title)

    print("Phase 5 clip rendering complete!")


if __name__ == "__main__":
    main()

