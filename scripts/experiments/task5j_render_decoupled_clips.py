"""Decoupled Cadence & Settling Evidence Scope: Render Clips and Evaluation Timelines.

Produces retained movement clips with 150 ms pre-roll and 200 ms post-roll
for Recording B (NORMAL + WHOLE_BODY), Recording A (NORMAL + UPPER_BODY),
and Recording A (REPETITIONS + UPPER_BODY).
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

CONFIGS = [
    {
        "name": "recording_b_normal_whole",
        "title": "Recording B (NORMAL + WHOLE_BODY)",
        "video": ROOT / "input/task5/20260911_223447.mp4",
        "data_dir": ROOT / "output/task5/base_segmentation/recording_b",
        "dest_dir": ROOT / "docs/validation/task5/decoupled-clips/recording-b-normal-whole",
        "et_thresh": (0.70, 0.45),
        "ea_thresh": (35.0, 20.0),
        "descriptions": {
            1: "Move 1: Chudan Oi-Zuki (Right punch)",
            2: "Move 2: Gedan Barai (Left low block)",
            3: "Move 3: Jodan Age-Uke (Right rising block)",
            4: "Move 4: 1-2 Combination (Rapid Chudan Zuki, 81ms hold)",
            5: "Move 5: Mae Geri (Front kick & recovery)",
            6: "Move 6: Yoko Geri Kekomi (Side thrust kick)",
        },
    },
    {
        "name": "recording_a_normal_upper",
        "title": "Recording A (NORMAL + UPPER_BODY)",
        "video": ROOT / "input/task5/1000002073.mp4",
        "data_dir": ROOT / "output/task5/base_segmentation/recording_a_normal_upper",
        "dest_dir": ROOT / "docs/validation/task5/decoupled-clips/recording-a-normal-upper",
        "et_thresh": (0.70, 0.45),
        "ea_thresh": (35.0, 20.0),
        "descriptions": {
            1: "Punch 1 (Right straight punch, camera near)",
            2: "Punch 2 (Left straight punch, camera far)",
            3: "Punch 3 (Right straight punch, camera near)",
            4: "Punches 4 & 5 (Merged: Hold 4 lacked 100ms quiet)",
            5: "Punch 6 (Left straight punch, camera far)",
            6: "Punch 7 (Right straight punch, camera near)",
            7: "Punch 8 (Left straight punch, camera far)",
            8: "Punch 9 (Right straight punch, camera near)",
            9: "Punch 10 (Left straight punch + Kamae transition)",
            10: "Move 11 (Post-sequence posture reset)",
            11: "Move 12 (Final Yame posture settling)",
        },
    },
    {
        "name": "recording_a_repetitions_upper",
        "title": "Recording A (REPETITIONS + UPPER_BODY)",
        "video": ROOT / "input/task5/1000002073.mp4",
        "data_dir": ROOT / "output/task5/base_segmentation/recording_a_repetitions_upper",
        "dest_dir": ROOT / "docs/validation/task5/decoupled-clips/recording-a-repetitions-upper",
        "et_thresh": (0.70, 0.55),
        "ea_thresh": (35.0, 20.0),
        "descriptions": {
            1: "Punch 1 (Right straight punch, camera near)",
            2: "Punch 2 (Left straight punch, camera far)",
            3: "Punch 3 (Right straight punch, camera near)",
            4: "Punch 4 (Left straight punch, camera far)",
            5: "Punch 5 (Right straight punch, camera near)",
            6: "Punch 6 (Left straight punch, camera far)",
            7: "Punch 7 (Right straight punch, camera near)",
            8: "Punch 8 (Left straight punch, camera far)",
            9: "Punch 9 (Right straight punch, camera near)",
            10: "Punch 10 (Left straight punch + Kamae transition)",
            11: "Move 11 (Post-sequence posture reset)",
            12: "Move 12 (Final Yame posture settling)",
        },
    },
]


def generate_timeline_plot(
    frames: list[dict],
    segments: list[dict],
    title: str,
    et_moving: float,
    et_quiet: float,
    ea_moving: float,
    ea_quiet: float,
    out_path: Path,
) -> None:
    times = [f["timestamp_ms"] / 1000.0 for f in frames]
    ets = [f.get("top2", {}).get("translation_evidence") for f in frames]
    eas = [f.get("top2", {}).get("angular_evidence") for f in frames]

    fig, (ax1, ax2) = plt.subplots(2, 1, figsize=(15, 8), sharex=True)

    # Translation
    ax1.plot(times, ets, label="ET (Top-2 Mean Speed)", color="#1f77b4", lw=1.5)
    ax1.axhline(et_moving, color="red", linestyle="--", alpha=0.7, label=f"Moving ({et_moving:.2f} Lref/s)")
    ax1.axhline(et_quiet, color="green", linestyle="--", alpha=0.7, label=f"Quiet ({et_quiet:.2f} Lref/s)")
    ax1.set_ylabel("Translation (Lref/s)")
    ax1.set_title(f"{title} - Decoupled Cadence & Settling Evidence Scope", fontsize=12, fontweight="bold")
    ax1.grid(True, alpha=0.3)
    ax1.legend(loc="upper right")

    # Angular
    ax2.plot(times, eas, label="EA (Top-2 Mean Angular Rate)", color="#ff7f0e", lw=1.5)
    ax2.axhline(ea_moving, color="red", linestyle="--", alpha=0.7, label=f"Moving ({ea_moving:.1f} deg/s)")
    ax2.axhline(ea_quiet, color="green", linestyle="--", alpha=0.7, label=f"Quiet ({ea_quiet:.1f} deg/s)")
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
            ax1.text(
                mid_t,
                ax1.get_ylim()[1] * 0.82,
                f"M{num}",
                color="darkgreen",
                fontweight="bold",
                ha="center",
                fontsize=9,
                bbox=dict(boxstyle="round,pad=0.2", facecolor="white", alpha=0.85, edgecolor="green"),
            )

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
    desc: str,
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

    cap.set(cv2.CAP_PROP_POS_FRAMES, f_start)

    fourcc = cv2.VideoWriter_fourcc(*"mp4v")
    out_path.parent.mkdir(parents=True, exist_ok=True)
    out = cv2.VideoWriter(str(out_path), fourcc, fps, (width, height))

    rendered_count = 0
    for frame_idx in range(f_start, f_end + 1):
        ret, frame = cap.read()
        if not ret:
            break

        f_meta = trace_frames[min(frame_idx, len(trace_frames) - 1)]
        t = f_meta["timestamp_ms"]
        st = f_meta.get("state", "UNKNOWN")
        top2 = f_meta.get("top2", {})
        et = top2.get("translation_evidence", 0.0) or 0.0
        ea = top2.get("angular_evidence", 0.0) or 0.0
        dt = top2.get("translation_decision", "-")
        da = top2.get("angular_decision", "-")

        is_active = (start_ms <= t <= end_ms)

        # Draw HUD Telemetry Overlay
        overlay = frame.copy()
        cv2.rectangle(overlay, (10, 10), (width - 10, 115), (0, 0, 0), -1)
        cv2.addWeighted(overlay, 0.65, frame, 0.35, 0, frame)

        # Active vs Roll Banner
        banner_color = (0, 255, 0) if is_active else (0, 165, 255)
        banner_text = f"[{'ACTIVE MOVEMENT' if is_active else 'PRE/POST ROLL'}]  {title}"
        cv2.putText(frame, banner_text, (20, 35), cv2.FONT_HERSHEY_SIMPLEX, 0.65, banner_color, 2)

        # Visual Technique Description
        cv2.putText(frame, f"Technique: {desc}", (20, 65), cv2.FONT_HERSHEY_SIMPLEX, 0.60, (255, 255, 255), 1)

        # Kinematics Line
        cv2.putText(
            frame,
            f"t={t:5d}ms | State: {st:<8s} | ET={et:.2f} ({dt}) | EA={ea:.1f} ({da})",
            (20, 95),
            cv2.FONT_HERSHEY_SIMPLEX,
            0.55,
            (0, 255, 255) if st == "MOVING" else (0, 255, 0) if st == "COMPLETE" else (200, 200, 200),
            1,
        )

        out.write(frame)
        rendered_count += 1

    cap.release()
    out.release()
    return {
        "clip_file": str(out_path.relative_to(ROOT)).replace("\\", "/"),
        "clip_start_ms": clip_start_ms,
        "clip_end_ms": clip_end_ms,
        "rendered_frames": rendered_count,
    }


def main():
    print("Executing Task 5 Clip Rendering & Timeline Generation...")
    summary_report = {}

    for cfg in CONFIGS:
        print(f"\nProcessing {cfg['name']} ({cfg['title']})...")
        cfg["dest_dir"].mkdir(parents=True, exist_ok=True)

        res_path = cfg["data_dir"] / "blind-session-result.json"
        trace_path = cfg["data_dir"] / "blind-session.trace.json"

        res = json.loads(res_path.read_text())
        trace = json.loads(trace_path.read_text())

        # 1. Generate Timeline Plot
        timeline_img = cfg["dest_dir"] / f"{cfg['name']}-timeline.png"
        generate_timeline_plot(
            frames=trace["frames"],
            segments=res["segments"],
            title=cfg["title"],
            et_moving=cfg["et_thresh"][0],
            et_quiet=cfg["et_thresh"][1],
            ea_moving=cfg["ea_thresh"][0],
            ea_quiet=cfg["ea_thresh"][1],
            out_path=timeline_img,
        )

        # 2. Render Retained Clips
        clip_records = []
        for seg in res["segments"]:
            if not seg.get("completed"):
                continue
            num = seg["movement_number"]
            s_ms = seg["start_boundary_timestamp_ms"]
            e_ms = seg["terminal_boundary_timestamp_ms"]
            dur_ms = seg["duration_ms"]
            desc = cfg["descriptions"].get(num, f"Movement {num}")

            out_clip = cfg["dest_dir"] / f"movement-{num:02d}.mp4"
            title = f"M{num:02d} ({s_ms}-{e_ms}ms, dur={dur_ms}ms)"
            clip_info = render_movement_clip(
                video_path=cfg["video"],
                trace_frames=trace["frames"],
                start_ms=s_ms,
                end_ms=e_ms,
                out_path=out_clip,
                title=title,
                desc=desc,
            )

            clip_records.append({
                "movement_number": num,
                "description": desc,
                "start_boundary_ms": s_ms,
                "terminal_boundary_ms": e_ms,
                "duration_ms": dur_ms,
                "clip_path": clip_info["clip_file"],
            })

        summary_report[cfg["name"]] = {
            "title": cfg["title"],
            "detected_count": res.get("detected_movement_count", len(res.get("segments", []))),
            "timeline_image": str(timeline_img.relative_to(ROOT)).replace("\\", "/"),
            "clips": clip_records,
        }

    report_path = ROOT / "docs/validation/task5/decoupled-clips/clips-summary.json"
    report_path.write_text(json.dumps(summary_report, indent=2))
    print(f"\nAll clips and timelines generated! Summary saved to {report_path}")


if __name__ == "__main__":
    main()
