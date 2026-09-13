"""Task 5D: Blind Exercise-Agnostic Continuous Segmentation Deliverables Generator.

Generates:
1. docs/validation/task5/blind/timeline.png
2. docs/validation/task5/blind/contact-sheet.png
3. docs/validation/task5/blind/uncompleted-movement-001.mp4
4. docs/validation/task5/blind/blind-segmentation.json
5. docs/validation/task5/blind/blind-segmentation.md
"""
from __future__ import annotations

import json
import os
from pathlib import Path
import cv2
import matplotlib.pyplot as plt
import matplotlib.patches as mpatches
import numpy as np

ROOT = Path(__file__).resolve().parents[2]
DEST = ROOT / "docs/validation/task5/blind"
WORK = ROOT / "output/task5/blind"
VIDEO_PATH = ROOT / "input/task5/20260911_223447.mp4"
RESULT_PATH = WORK / "blind-session-result.json"
TRACE_PATH = WORK / "blind-session.trace.json"

DEST.mkdir(parents=True, exist_ok=True)


def load_data():
    with open(RESULT_PATH, "r", encoding="utf-8") as f:
        result = json.load(f)
    with open(TRACE_PATH, "r", encoding="utf-8") as f:
        trace = json.load(f)
    return result, trace


def generate_timeline(result: dict, trace: dict):
    print("Generating timeline.png...")
    frames = trace["frames"]
    times = [f["timestamp_ms"] / 1000.0 for f in frames]
    art_motion = [f["articulated_motion"] if f["articulated_motion"] is not None else np.nan for f in frames]
    img_motion = [f["image_space_motion"] if f["image_space_motion"] is not None else np.nan for f in frames]
    slow_disp = [f["slow_displacement"] for f in frames]
    cov_total = [f["coverage"] for f in frames]
    cov_torso = [f["regions"]["TORSO"]["coverage"] for f in frames]
    cov_left_arm = [f["regions"]["LEFT_ARM"]["coverage"] for f in frames]
    cov_right_arm = [f["regions"]["RIGHT_ARM"]["coverage"] for f in frames]
    similarity = [f["same_similarity"] if f["same_similarity"] is not None else np.nan for f in frames]
    states = [f["state"] for f in frames]

    fig, axes = plt.subplots(5, 1, figsize=(18, 14), sharex=True, gridspec_kw={"height_ratios": [3, 2, 2.5, 2.5, 1.8]})

    # --- Plot 1: Articulated & Image-Space Motion ---
    ax = axes[0]
    ax.plot(times, art_motion, label="Articulated Motion", color="#1f77b4", lw=1.5)
    ax.plot(times, img_motion, label="Image-Space Motion", color="#aec7e8", lw=1.0, alpha=0.7)
    ax.axhline(0.50, color="red", linestyle="--", lw=1.2, label="Quiet / Start Threshold (0.50)")
    ax.set_ylabel("Motion (units/s)", fontsize=11)
    ax.set_title("Task 5D Blind Session: Continuous Generic Motion Segmentation (20260911_223447.mp4)", fontsize=14, fontweight="bold")
    ax.grid(True, alpha=0.3)
    ax.set_ylim(0, max(5.5, max([m for m in art_motion if not np.isnan(m)] + [1.0]) * 1.05))

    # Mark Start Boundary and Decision
    start_b_t = 2030 / 1000.0
    start_d_t = 2132 / 1000.0
    ax.axvline(start_b_t, color="#d62728", linestyle="--", lw=2, label=f"Mvt 1 Start Boundary ({start_b_t:.3f}s / F99)")
    ax.axvline(start_d_t, color="#2ca02c", linestyle="-", lw=1.5, label=f"Mvt 1 Start Decision ({start_d_t:.3f}s / F104)")

    # Background shading for states
    # Find contiguous state blocks
    state_blocks = []
    c_s = states[0]
    c_start = 0
    for i, s in enumerate(states):
        if s != c_s:
            state_blocks.append((c_s, times[c_start], times[i-1]))
            c_s = s
            c_start = i
    state_blocks.append((c_s, times[c_start], times[-1]))

    for s, t0, t1 in state_blocks:
        if s == "BASELINE":
            ax.axvspan(t0, t1, color="#7f7f7f", alpha=0.15)
        elif s == "ARMED":
            ax.axvspan(t0, t1, color="#ff7f0e", alpha=0.15)
        elif s == "SETTLING":
            ax.axvspan(t0, t1, color="#17becf", alpha=0.35)

    ax.legend(loc="upper right", framealpha=0.9, fontsize=9)

    # --- Plot 2: Accumulated Slow Displacement ---
    ax = axes[1]
    ax.plot(times, slow_disp, label="Accumulated Displacement (300 ms)", color="#ff7f0e", lw=1.5)
    ax.axhline(0.05, color="red", linestyle="--", lw=1.2, label="Displacement Limit (0.05)")
    ax.set_ylabel("Displacement", fontsize=11)
    ax.grid(True, alpha=0.3)
    ax.set_ylim(0, 0.8)
    for s, t0, t1 in state_blocks:
        if s == "SETTLING":
            ax.axvspan(t0, t1, color="#17becf", alpha=0.35)
    ax.legend(loc="upper right", framealpha=0.9, fontsize=9)

    # --- Plot 3: Anatomical Region Tracking Coverage ---
    ax = axes[2]
    ax.plot(times, cov_torso, label="Torso Coverage", color="#2ca02c", lw=1.3)
    ax.plot(times, cov_right_arm, label="Right Arm Coverage", color="#1f77b4", lw=1.3)
    ax.plot(times, cov_left_arm, label="Left Arm Coverage", color="#d62728", lw=1.5)
    ax.axhline(0.70, color="black", linestyle="--", lw=1.2, label="Min Required Coverage (0.70)")
    # Fill where left arm < 0.70
    ax.fill_between(times, 0, 1.05, where=[c < 0.70 for c in cov_left_arm], color="red", alpha=0.12, label="Left Arm < 0.70 (Evidence UNKNOWN)")
    ax.set_ylabel("Coverage", fontsize=11)
    ax.grid(True, alpha=0.3)
    ax.set_ylim(0, 1.05)
    ax.legend(loc="lower right", framealpha=0.9, fontsize=9)

    # --- Plot 4: Posture Similarity ---
    ax = axes[3]
    ax.plot(times, similarity, label="sameAsStartSimilarity", color="#9467bd", lw=1.5)
    ax.axhline(0.80, color="purple", linestyle="--", lw=1.2, label="Terminal Similarity Limit (0.80)")
    # Highlight holds where similarity >= 0.80
    ax.set_ylabel("Pose Similarity", fontsize=11)
    ax.grid(True, alpha=0.3)
    ax.set_ylim(0.4, 1.05)
    for s, t0, t1 in state_blocks:
        if s == "SETTLING":
            ax.axvspan(t0, t1, color="#17becf", alpha=0.35)
    # Annotate return to stance
    ax.annotate("Return to Stance Hold\n(sim=0.93 >= 0.80 -> Rejected)",
                xy=(3.68, 0.93), xytext=(4.2, 0.75),
                arrowprops=dict(facecolor="black", shrink=0.05, width=1, headwidth=6),
                fontsize=9, fontweight="bold", backgroundcolor="white")
    ax.annotate("Return to Stance Hold\n(sim=0.93 >= 0.80 -> Rejected)",
                xy=(7.2, 0.93), xytext=(7.8, 0.75),
                arrowprops=dict(facecolor="black", shrink=0.05, width=1, headwidth=6),
                fontsize=9, fontweight="bold", backgroundcolor="white")
    ax.legend(loc="lower right", framealpha=0.9, fontsize=9)

    # --- Plot 5: State & Evidence Discrete Timeline ---
    ax = axes[4]
    state_map = {"BASELINE": 0, "ARMED": 1, "MOVING": 2, "SETTLING": 3, "COMPLETE": 4}
    state_vals = [state_map[s] for s in states]
    ax.step(times, state_vals, where="post", color="#333333", lw=1.5)
    ax.set_yticks([0, 1, 2, 3, 4])
    ax.set_yticklabels(["BASELINE", "ARMED", "MOVING", "SETTLING", "COMPLETE"], fontsize=10)
    ax.set_ylabel("State", fontsize=11)
    ax.set_xlabel("Time (seconds)", fontsize=11)
    ax.grid(True, alpha=0.3)
    ax.set_xlim(0, times[-1])

    plt.tight_layout()
    out_path = DEST / "timeline.png"
    plt.savefig(out_path, dpi=150)
    plt.close()
    print(f"Saved timeline to {out_path}")


def generate_contact_sheet(trace: dict):
    print("Generating contact-sheet.png...")
    frames_meta = trace["frames"]
    # 10 milestone frames:
    # 0: Frame 0 (0 ms) - Video start / Yoi posture
    # 1: Frame 49 (1015 ms) - Baseline ready / ARMED transition
    # 2: Frame 99 (2030 ms) - Movement 1 Start Boundary
    # 3: Frame 104 (2132 ms) - Movement 1 Start Decision (sustained motion)
    # 4: Frame 114 (2335 ms) - Strike 1 peak motion (5.06)
    # 5: Frame 130 (2660 ms) - Strike 1 terminal hold (Left Arm coverage = 0.628 < 0.70 -> UNKNOWN)
    # 6: Frame 178 (3635 ms) - Return-to-stance Hold 1 (sim = 0.929 >= 0.80 -> FALSE)
    # 7: Frame 350 (7128 ms) - Return-to-stance Hold 2 (sim = 0.925 >= 0.80 -> FALSE)
    # 8: Frame 425 (8651 ms) - Strike hold later (Left Arm coverage = 0.333 < 0.70 -> UNKNOWN)
    # 9: Frame 762 (15495 ms) - EOF (final frame in MOVING state)

    milestones = [
        (0, "Frame 0 (0 ms)", "Video Start\nInitial Stance (Yoi)"),
        (49, "Frame 49 (1015 ms)", "Baseline Ready -> ARMED\nStillness proven"),
        (99, "Frame 99 (2030 ms)", "Mvt 1 Start Boundary\nMotion initiates"),
        (104, "Frame 104 (2132 ms)", "Mvt 1 Start Decision\nSustained motion confirmed"),
        (114, "Frame 114 (2335 ms)", "Strike 1 Peak Motion\nArticulated motion = 5.06"),
        (130, "Frame 130 (2660 ms)", "Strike 1 Terminal Hold\nL_Arm cov=0.63 < 0.70 -> UNKNOWN"),
        (178, "Frame 178 (3635 ms)", "Return Hold 1 (101ms dwell)\nSim=0.93 >= 0.80 -> FALSE"),
        (350, "Frame 350 (7128 ms)", "Return Hold 2 (142ms dwell)\nSim=0.93 >= 0.80 -> FALSE"),
        (425, "Frame 425 (8651 ms)", "Later Strike Hold\nL_Arm cov=0.33 < 0.70 -> UNKNOWN"),
        (762, "Frame 762 (15495 ms)", "EOF (15495 ms)\nFinal State: MOVING (Incomplete)"),
    ]

    cap = cv2.VideoCapture(str(VIDEO_PATH))
    images = []
    for f_idx, title, desc in milestones:
        cap.set(cv2.CAP_PROP_POS_FRAMES, f_idx)
        ret, frame = cap.read()
        if not ret:
            frame = np.zeros((1920, 1080, 3), dtype=np.uint8)
        else:
            frame = cv2.cvtColor(frame, cv2.COLOR_BGR2RGB)
        images.append((f_idx, title, desc, frame))
    cap.release()

    fig, axes = plt.subplots(2, 5, figsize=(20, 11))
    axes = axes.flatten()

    for idx, (f_idx, title, desc, img) in enumerate(images):
        ax = axes[idx]
        ax.imshow(img)
        ax.set_title(f"{title}\n{desc}", fontsize=9, fontweight="bold", pad=5)
        ax.axis("off")

    plt.suptitle("Task 5D Contact Sheet: Key Milestones & Terminal Evaluation Events (20260911_223447.mp4)", fontsize=14, fontweight="bold", y=0.98)
    plt.tight_layout()
    out_path = DEST / "contact-sheet.png"
    plt.savefig(out_path, dpi=150)
    plt.close()
    print(f"Saved contact sheet to {out_path}")


def render_diagnostic_video(trace: dict):
    print("Rendering uncompleted-movement-001.mp4...")
    start_frame = 99
    end_frame = 762

    cap = cv2.VideoCapture(str(VIDEO_PATH))
    fps = cap.get(cv2.CAP_PROP_FPS)
    if fps <= 0:
        fps = 49.18
    width = int(cap.get(cv2.CAP_PROP_FRAME_WIDTH))
    height = int(cap.get(cv2.CAP_PROP_FRAME_HEIGHT))

    out_path = DEST / "uncompleted-movement-001.mp4"
    fourcc = cv2.VideoWriter_fourcc(*"mp4v")
    out = cv2.VideoWriter(str(out_path), fourcc, fps, (width, height))

    frames_meta = {f["timestamp_ms"]: f for f in trace["frames"]}
    all_frames = trace["frames"]

    cap.set(cv2.CAP_PROP_POS_FRAMES, start_frame)
    for f_idx in range(start_frame, end_frame + 1):
        ret, frame = cap.read()
        if not ret:
            break
        meta = all_frames[f_idx]
        t_ms = meta["timestamp_ms"]
        st = meta["state"]
        art = meta["articulated_motion"]
        disp = meta["slow_displacement"]
        cov = meta["coverage"]
        l_arm_cov = meta["regions"]["LEFT_ARM"]["coverage"]
        r_arm_cov = meta["regions"]["RIGHT_ARM"]["coverage"]
        torso_cov = meta["regions"]["TORSO"]["coverage"]
        sim = meta["same_similarity"]

        # Draw semi-transparent HUD overlay at top
        overlay = frame.copy()
        cv2.rectangle(overlay, (20, 20), (1060, 220), (0, 0, 0), -1)
        cv2.addWeighted(overlay, 0.65, frame, 0.35, 0, frame)

        # Text labels
        cv2.putText(frame, "DIAGNOSTIC AUDIT: UNCOMPLETED MOVEMENT 001", (40, 60),
                    cv2.FONT_HERSHEY_SIMPLEX, 0.9, (0, 255, 255), 2, cv2.LINE_AA)
        cv2.putText(frame, f"Frame {f_idx:3d}/762 | Time: {t_ms} ms ({t_ms/1000.0:.2f} s) | State: {st}", (40, 100),
                    cv2.FONT_HERSHEY_SIMPLEX, 0.8, (255, 255, 255), 2, cv2.LINE_AA)
        cv2.putText(frame, f"Articulated Motion: {art:.2f} (Thresh: 0.50) | Disp: {disp:.3f} (Limit: 0.05)", (40, 140),
                    cv2.FONT_HERSHEY_SIMPLEX, 0.75, (200, 200, 200), 2, cv2.LINE_AA)
        cv2.putText(frame, f"Coverage: Torso={torso_cov:.2f} | L_Arm={l_arm_cov:.2f} | R_Arm={r_arm_cov:.2f} (Min: 0.70)", (40, 175),
                    cv2.FONT_HERSHEY_SIMPLEX, 0.75, (100, 255, 100) if cov >= 0.70 else (100, 100, 255), 2, cv2.LINE_AA)
        sim_str = f"{sim:.2f}" if sim is not None else "N/A"
        cv2.putText(frame, f"Pose Sim to Start: {sim_str} (Limit: < 0.80 for DIFFERENT_STABLE_POSE)", (40, 210),
                    cv2.FONT_HERSHEY_SIMPLEX, 0.75, (255, 200, 100), 2, cv2.LINE_AA)

        out.write(frame)

    cap.release()
    out.release()
    print(f"Saved diagnostic video to {out_path}")


def generate_json_report(result: dict, trace: dict):
    print("Generating blind-segmentation.json...")
    report = {
        "sequence_id": result["sequence_id"],
        "input_file": "input/task5/20260911_223447.mp4",
        "video_properties": {
            "total_frames": result["total_frames"],
            "total_duration_ms": result["total_duration_ms"],
            "frame_rate_fps": 49.18,
            "resolution": "1080x1920"
        },
        "frozen_configuration": {
            "extractor_reference_motion_limit": 0.30,
            "segmenter_quiet_threshold": 0.50,
            "segmenter_start_threshold": 0.50,
            "accumulated_displacement_window_ms": 300,
            "accumulated_displacement_limit": 0.05,
            "baseline_dwell_ms": 100,
            "movement_start_dwell_ms": 100,
            "settling_dwell_ms": 100,
            "minimum_coverage": 0.70,
            "required_regions": ["TORSO", "LEFT_ARM", "RIGHT_ARM"],
            "end_pose_relationship": "DIFFERENT_STABLE_POSE",
            "terminal_pose_similarity": 0.80,
            "positive_motion_guardrail": True
        },
        "detected_movement_count": result["detected_movement_count"],
        "segments": result["segments"],
        "blind_audit_summary": {
            "completed_movements": 0,
            "started_movements": 1,
            "movement_1": {
                "arm_timestamp_ms": 1015,
                "arm_frame_index": 49,
                "start_boundary_timestamp_ms": 2030,
                "start_boundary_frame_index": 99,
                "start_decision_timestamp_ms": 2132,
                "start_decision_frame_index": 104,
                "start_reason": "sustained_motion",
                "terminal_boundary_timestamp_ms": None,
                "completion_decision_timestamp_ms": None,
                "duration_ms": None,
                "final_state": "MOVING",
                "failure_reason": "INCOMPLETE_AT_EOF (final state was MOVING)",
                "pre_roll_ms": 1015,
                "post_roll_ms": None,
                "settling_resumptions": 13,
                "coverage_unknown_frames": 457
            },
            "failure_mechanisms_identified": [
                {
                    "mechanism": "Coverage Drop on Far/Occluded Arm (Evidence.UNKNOWN)",
                    "description": "During terminal holds of strikes (e.g. frames 116-140 / 2376-2863 ms, and frames 416-679 / 8468-13809 ms), the left arm tracking coverage fell below 0.70 (dropping to 0.54-0.63 and later 0.33). Because LEFT_ARM was in the side-neutral required set [TORSO, LEFT_ARM, RIGHT_ARM], missing required evidence evaluated strictly to Evidence.UNKNOWN, safely blocking terminal completion dwell.",
                    "representative_frames": [130, 425]
                },
                {
                    "mechanism": "Return to Starting Stance Rejected by DIFFERENT_STABLE_POSE (Evidence.FALSE)",
                    "description": "During periods where the participant returned to the initial stance and held stillness with full limb coverage >= 0.95 and quiet motion <= 0.50 (e.g. frames 178-183 / 3635-3736 ms [101 ms dwell] and frames 350-357 / 7128-7270 ms [142 ms dwell]), sameAsStartSimilarity was ~0.93 (>= 0.80). Because endPoseRelationship was set to DIFFERENT_STABLE_POSE, returning to the start posture evaluated strictly to Evidence.FALSE, preventing accidental completion.",
                    "representative_frames": [178, 350]
                }
            ]
        },
        "deliverables": {
            "timeline_png": "docs/validation/task5/blind/timeline.png",
            "contact_sheet_png": "docs/validation/task5/blind/contact-sheet.png",
            "blind_segmentation_json": "docs/validation/task5/blind/blind-segmentation.json",
            "blind_segmentation_md": "docs/validation/task5/blind/blind-segmentation.md",
            "rendered_movement_clips": [],
            "diagnostic_uncompleted_clip": "docs/validation/task5/blind/uncompleted-movement-001.mp4"
        }
    }

    out_path = DEST / "blind-segmentation.json"
    with open(out_path, "w", encoding="utf-8") as f:
        json.dump(report, f, indent=2)
    print(f"Saved JSON report to {out_path}")
    return report


def generate_markdown_report(report: dict):
    print("Generating blind-segmentation.md...")
    md = f"""# Task 5D: Blind Exercise-Agnostic Continuous Segmentation Report

## 1. Executive Summary

This report delivers the **frozen blind validation results** for `20260911_223447.mp4` evaluated under the frozen Task 5B/5C motion calibration and required-region terminal contract.

- **Sequence ID**: `{report["sequence_id"]}`
- **Source Video**: `{report["input_file"]}` ({report["video_properties"]["resolution"]}, {report["video_properties"]["frame_rate_fps"]} fps, {report["video_properties"]["total_frames"]} frames, {report["video_properties"]["total_duration_ms"]} ms)
- **Detected Movements Completed**: **0**
- **Movements Started**: **1** (Movement 1 detected at 2030 ms / Frame 99)
- **Final Session State**: `MOVING` at EOF (Frame 762 / 15495 ms)
- **Completed Clips Rendered**: **0** (No movement satisfied the completion contract; as mandated by the safety rules, no artificial boundary was manufactured)
- **Diagnostic Clip Rendered**: `uncompleted-movement-001.mp4` (Interval: 2030 ms to 15495 ms)

---

## 2. Frozen Configuration & Safety Contract

All parameters were preserved strictly unchanged from Task 5B and Task 5C without tuning against this video:

| Parameter | Frozen Value | Role & Safety Constraint |
|:---|:---|:---|
| `extractor.baselineMaximumArticulatedMotion` | `0.30` | Stricter reference formation limit |
| `segmenter.quietMotionThreshold` | `0.50` | Settling quiet stillness limit |
| `segmenter.startMotionThreshold` | `0.50` | Movement start threshold |
| `extractor.slowDisplacementWindowMs` | `300 ms` | Accumulated displacement integration window |
| `segmenter.maximumStableDisplacement` | `0.05` | Slow displacement decay limit |
| `segmenter.baselineDwellMs` | `100 ms` | Minimum stillness required to arm |
| `segmenter.movementStartDwellMs` | `100 ms` | Minimum motion required to trigger MOVING |
| `segmenter.settlingDwellMs` | `100 ms` | **Unchanged**: Minimum terminal stillness required |
| `segmenter.minimumCoverage` | `0.70` | Threshold below which required evidence is `UNKNOWN` |
| `requiredRegions` | `TORSO, LEFT_ARM, RIGHT_ARM` | Approved side-neutral upper-body required set |
| `endPoseRelationship` | `DIFFERENT_STABLE_POSE` | Must be dissimilar from initial starting reference (< 0.80) |
| `positiveMotionGuardrail` | `ENABLED` | Any moving observed limb vetoes completion |

---

## 3. Detected Movement Inventory

| Mvt # | Start Boundary | Start Decision | Terminal Boundary | Decision Time | Pre-Roll | Settling Resumptions | Coverage UNKNOWN | Final State / Reason |
|:---:|:---:|:---:|:---:|:---:|:---:|:---:|:---:|:---|
| **1** | 2030 ms (F99) | 2132 ms (F104) | *None* | *None* | 1015 ms | 13 | 457 frames | `MOVING` (INCOMPLETE_AT_EOF) |

### Transition Log (Movement 1)

```
0000 ms (F000): BASELINE initiated
1015 ms (F049): BASELINE -> ARMED (trigger: armed, baseline dwell satisfied)
2030 ms (F099): [Estimated Start Boundary]
2132 ms (F104): ARMED -> MOVING (trigger: sustained_motion, motion > 0.50 for 100ms)
3493 ms (F171): MOVING -> SETTLING (trigger: quiet_candidate)
3513 ms (F172): SETTLING -> MOVING (trigger: movement_resumed)
3533 ms (F173): MOVING -> SETTLING (trigger: quiet_candidate)
3574 ms (F175): SETTLING -> MOVING (trigger: movement_resumed)
3594 ms (F176): MOVING -> SETTLING (trigger: quiet_candidate)
3614 ms (F177): SETTLING -> MOVING (trigger: movement_resumed)
3635 ms (F178): MOVING -> SETTLING (trigger: quiet_candidate)
3757 ms (F184): SETTLING -> MOVING (trigger: movement_resumed)
3797 ms (F186): MOVING -> SETTLING (trigger: quiet_candidate)
3899 ms (F191): SETTLING -> MOVING (trigger: movement_resumed)
3919 ms (F192): MOVING -> SETTLING (trigger: quiet_candidate)
3939 ms (F193): SETTLING -> MOVING (trigger: movement_resumed)
5259 ms (F258): MOVING -> SETTLING (trigger: quiet_candidate)
5320 ms (F261): SETTLING -> MOVING (trigger: movement_resumed)
5341 ms (F262): MOVING -> SETTLING (trigger: quiet_candidate)
5442 ms (F267): SETTLING -> MOVING (trigger: movement_resumed)
5483 ms (F269): MOVING -> SETTLING (trigger: quiet_candidate)
5584 ms (F274): SETTLING -> MOVING (trigger: movement_resumed)
5605 ms (F275): MOVING -> SETTLING (trigger: quiet_candidate)
5666 ms (F278): SETTLING -> MOVING (trigger: movement_resumed)
7087 ms (F348): MOVING -> SETTLING (trigger: quiet_candidate)
7108 ms (F349): SETTLING -> MOVING (trigger: movement_resumed)
7128 ms (F350): MOVING -> SETTLING (trigger: quiet_candidate)
7290 ms (F358): SETTLING -> MOVING (trigger: movement_resumed)
7311 ms (F359): MOVING -> SETTLING (trigger: quiet_candidate)
7351 ms (F361): SETTLING -> MOVING (trigger: movement_resumed)
15495 ms (F762): EOF reached while in MOVING
```

---

## 4. Root Cause Analysis: Why Did Zero Movements Complete?

The analyzer operated strictly under the frozen safety contracts. The diagnostic trace reveals that the session remained in `MOVING` due to **two distinct, safe failure mechanisms**:

### Failure Mode 1: Tracking Coverage Drop on Far/Occluded Arm (`Evidence.UNKNOWN`)
- **When**: Strike 1 hold (2376 ms to 2863 ms) and later strike holds (8468 ms to 13809 ms).
- **Observation**:
  - During the terminal hold of Strike 1, the participant came to a complete stop: articulated motion decayed to $0.18 - 0.37$ (well below the $0.50$ quiet threshold) and accumulated displacement decayed to $0.034$ (below the $0.05$ limit).
  - The pose was clearly distinct from the starting stance (`sameAsStartSimilarity` $\approx 0.60 < 0.80$).
  - Torso coverage was $0.999$ and Right Arm coverage was $0.998$.
  - **However, Left Arm coverage dropped to $0.54 - 0.63$** (below the minimum required coverage of $0.70$).
  - Later in the video (from 8468 ms onward), Left Arm coverage dropped to $0.333$ (1 of 3 landmarks detected).
- **Contract Effect**:
  - Because `LEFT_ARM` was designated as a required region in the side-neutral contract (`TORSO, LEFT_ARM, RIGHT_ARM`), `observation.coverage` fell below $0.70$.
  - Under the safety rule *"Missing required evidence remains `UNKNOWN`"*, `quietEvidence` evaluated strictly to `Evidence.UNKNOWN`.
  - Terminal dwell time could not advance while evidence was `UNKNOWN`. Thus, the strike could not complete.

### Failure Mode 2: Return-to-Stance Rejected by `DIFFERENT_STABLE_POSE` (`Evidence.FALSE`)
- **When**: Return holds at 3635–3736 ms (101 ms dwell) and 7128–7270 ms (142 ms dwell).
- **Observation**:
  - Following strikes, the participant returned to the initial ready posture and held motionless for $>100$ ms.
  - Articulated motion was quiet ($< 0.50$).
  - Accumulated displacement was minimal ($< 0.035$).
  - Tracking coverage was excellent ($> 0.95$ across all limbs, including the left arm).
  - **However, `sameAsStartSimilarity` was $\approx 0.93$ ($\ge 0.80$)**.
- **Contract Effect**:
  - The segmenter was configured with `endPoseRelationship = DIFFERENT_STABLE_POSE` (which requires proving the terminal hold is a *different* technique pose, i.e., `sameAsStartSimilarity < 0.80`).
  - Because the participant returned to the start posture, similarity was high ($0.93$), causing the terminal check to evaluate to `Evidence.FALSE`.
  - The segmenter correctly refused to terminate upon returning to the ready stance.
  - Soon after, the participant resumed motion ($3757$ ms and $7290$ ms), kicking the state back to `MOVING`.

---

## 5. Adherence to Task 5D Safety Rules

1. **No threshold tuning**: All thresholds (`0.50`, `0.30`, `0.05`, `100 ms`, `0.70`) remained frozen.
2. **No manufactured boundaries**: Because no movement satisfied the completion contract, zero completed clips were output. No artificial split or end was forced.
3. **No technique assumptions**: The controller did not assume punch types, sides, or intended repetitions.
4. **Preservation of uncertainty**: The 457 frames with incomplete left-arm tracking were strictly treated as `UNKNOWN`.

---

## 6. Deliverables Index

- **Timeline Plot**: [`timeline.png`](timeline.png)
- **Contact Sheet**: [`contact-sheet.png`](contact-sheet.png)
- **JSON Result**: [`blind-segmentation.json`](blind-segmentation.json)
- **Diagnostic Video**: [`uncompleted-movement-001.mp4`](uncompleted-movement-001.mp4) (Full detected moving span: 2030 ms to 15495 ms)

---

## 7. Status: Frozen for Ground Truth Reveal

The blind results are now completely frozen and saved. We await the user's ground-truth reveal (actual movement count, intended sequence, boundaries, and speed variations) before proceeding to accuracy scoring.
"""
    out_path = DEST / "blind-segmentation.md"
    with open(out_path, "w", encoding="utf-8") as f:
        f.write(md)
    print(f"Saved Markdown report to {out_path}")


def main():
    print("Starting Task 5D blind segmentation deliverables generation...")
    result, trace = load_data()
    generate_timeline(result, trace)
    generate_contact_sheet(trace)
    render_diagnostic_video(trace)
    report = generate_json_report(result, trace)
    generate_markdown_report(report)
    print("Task 5D blind deliverables completed successfully.")


if __name__ == "__main__":
    main()

