"""Phase 5: Evaluation of Slow-Displacement Variants for Kihon Repetition Recovery.

Evaluates 3 variants for terminal slow displacement:
1. TRAILING_WINDOW (Control: legacy 300 ms window <= 0.05)
2. DISABLED (Pure kinematic quiet with MID-pause dwell)
3. SETTLING_LOCAL (Anchor-relative displacement veto from candidate settling onset)

Across:
- Recording A: real-kihon-10-punch (701 frames, 10 rapid punches, ~200-330 ms holds)
- Recording B: blind-session (763 frames, 6 movements including rapid 81 ms 1-2 strike)
- Multi-rate slow drift challenge: synthetic bursts followed by continuous drift at 3 rates
"""
from __future__ import annotations

import json
import os
import subprocess
import sys
from pathlib import Path
import numpy as np
import matplotlib
matplotlib.use("Agg")
import matplotlib.pyplot as plt

ROOT = Path(__file__).resolve().parents[2]
FIXTURE_A = ROOT / "output/task5/real-kihon-10-punch.fixture.json"
FIXTURE_B = ROOT / "output/task5/blind/blind-session.fixture.json"
GRADLEW = ROOT / "android/KarateClipRecorder/gradlew.bat"
JAVA_HOME = r"C:\Program Files\Android\Android Studio\jbr"
OUTPUT_BASE = ROOT / "output/task5/phase5_variants"
DOCS_DIR = ROOT / "docs/validation/task5/terminal"


def create_synthetic_drift_fixture(drift_rate_torso_per_sec: float, name: str) -> Path:
    """Generate a synthetic multi-rate slow-drift fixture compatible with PoseReplayJson.
    
    0 - 300 ms: Quiet baseline
    300 - 600 ms: Fast punch burst (offset moves rapidly)
    600 - 2000 ms: Sustained slow drift at drift_rate_torso_per_sec
    2000 - 3000 ms: Complete stationary hold
    """
    points = {"NOSE": [0.0, -1.7, 0.0]}
    for side, sign in [("LEFT", -1.0), ("RIGHT", 1.0)]:
        for joint, pt in [
            ("EAR", [0.12, -1.65, 0.0]),
            ("SHOULDER", [0.25, -1.0, 0.0]),
            ("ELBOW", [0.45, -0.8, 0.0]),
            ("WRIST", [0.65, -0.5, 0.0]),
            ("HIP", [0.2, 0.0, 0.0]),
            ("KNEE", [0.25, 1.0, 0.0]),
            ("ANKLE", [0.25, 2.0, 0.0]),
        ]:
            points[f"{side}_{joint}"] = [pt[0] * sign, pt[1], pt[2]]

    frames = []
    # 50 Hz (20 ms interval) up to 3000 ms (151 frames)
    for t_ms in range(0, 3020, 20):
        t = t_ms / 1000.0
        if t < 0.30:
            offset = 0.0
        elif t < 0.60:
            # Rapid movement burst
            offset = (t - 0.30) / 0.30 * 0.8
        elif t <= 2.00:
            # Constant slow drift
            offset = 0.8 + (t - 0.60) * drift_rate_torso_per_sec
        else:
            # Stationary hold
            offset = 0.8 + (2.00 - 0.60) * drift_rate_torso_per_sec

        landmarks = []
        for joint_id, base_pt in points.items():
            is_torso = "HIP" in joint_id or "SHOULDER" in joint_id
            dx = 0.0 if is_torso else offset
            px = base_pt[0] + dx
            py = base_pt[1]
            pz = base_pt[2]
            landmarks.append({
                "id": joint_id,
                "normalized": [0.5 + px * 0.1, 0.35 + (py + 0.5) * 0.1, pz * 0.1],
                "world": [px, py, pz],
                "visibility": 0.95,
                "presence": 0.95,
                "source": "OBSERVED",
            })

        frames.append({
            "timestamp_ms": t_ms,
            "landmarks": landmarks,
        })

    fixture_path = OUTPUT_BASE / f"{name}.fixture.json"
    fixture_path.parent.mkdir(parents=True, exist_ok=True)
    fixture_data = {
        "schema_version": "pose-motion-replay-v1",
        "sequence_id": name,
        "arm_timestamp_ms": None,
        "cue_timestamp_ms": None,
        "activity_deadline_ms": None,
        "expected_end_pose_relationship": "ANY_STABLE_POSE_AFTER_MOVEMENT",
        "notes": ["Synthetic multi-rate slow-drift challenge fixture"],
        "labels": None,
        "frames": frames,
    }
    with open(fixture_path, "w") as f:
        json.dump(fixture_data, f)
    return fixture_path


def run_session(
    fixture_path: Path,
    out_dir: Path,
    policy: str,
    settling_limit: float = 0.03,
    required_regions: str = "TORSO,RIGHT_ARM",
) -> dict:
    out_dir.mkdir(parents=True, exist_ok=True)
    env = os.environ.copy()
    env["JAVA_HOME"] = JAVA_HOME

    cmd = [
        str(GRADLEW),
        "-p", "android/KarateClipRecorder",
        ":karate-analyzer-core:continuousMotion",
        f"-PreplayInput={fixture_path}",
        f"-PreplayOutput={out_dir}",
        f"-PrequiredRegions={required_regions}",
        "-PendPoseRelationship=ANY_STABLE_POSE_AFTER_MOVEMENT",
        "-PenableKinematics=true",
        f"-PslowDisplacementPolicy={policy}",
        f"-PsettlingLocalDisplacementLimit={settling_limit}",
    ]

    result = subprocess.run(cmd, cwd=str(ROOT), env=env, capture_output=True, text=True)
    if result.returncode != 0:
        print(f"FAILED on {fixture_path.name} with policy={policy}")
        print(result.stdout)
        print(result.stderr)
        raise RuntimeError(f"Gradle session execution failed: {result.returncode}")

    summary_file = out_dir / "blind-session-result.json"
    if summary_file.exists():
        data = json.loads(summary_file.read_text())
        segments = data.get("segments", [])
    else:
        segments = []

    return {
        "policy": policy,
        "settling_limit": settling_limit,
        "segments": segments,
        "output_dir": out_dir,
    }


def main():
    print("=================================================================")
    print("Task 5 Phase 5: Terminal Slow-Displacement Variants Evaluation")
    print("=================================================================")

    policies = ["TRAILING_WINDOW", "DISABLED", "SETTLING_LOCAL"]

    # 1. Recording A (real kihon 10 punch)
    print("\n--- Testing Recording A (real-kihon-10-punch) ---")
    rec_a_results = {}
    for pol in policies:
        out = OUTPUT_BASE / "recording_a" / pol
        print(f"Running Recording A with policy={pol}...")
        rec_a_results[pol] = run_session(FIXTURE_A, out, pol, settling_limit=0.03, required_regions="TORSO,RIGHT_ARM")
        completed = [s for s in rec_a_results[pol]["segments"] if s["completed"]]
        print(f"  -> Detected: {len(rec_a_results[pol]['segments'])} segments, Completed: {len(completed)}")
        for s in completed:
            print(f"     Movement #{s['movement_number']}: start={s['start_boundary_timestamp_ms']}ms, end={s['terminal_boundary_timestamp_ms']}ms, duration={s['duration_ms']}ms")

    # 2. Recording B (blind session)
    print("\n--- Testing Recording B (blind-session) ---")
    rec_b_results = {}
    for pol in policies:
        out = OUTPUT_BASE / "recording_b" / pol
        print(f"Running Recording B with policy={pol}...")
        rec_b_results[pol] = run_session(FIXTURE_B, out, pol, settling_limit=0.03, required_regions="TORSO,RIGHT_ARM")
        completed = [s for s in rec_b_results[pol]["segments"] if s["completed"]]
        print(f"  -> Detected: {len(rec_b_results[pol]['segments'])} segments, Completed: {len(completed)}")
        for s in completed:
            print(f"     Movement #{s['movement_number']}: start={s['start_boundary_timestamp_ms']}ms, end={s['terminal_boundary_timestamp_ms']}ms, duration={s['duration_ms']}ms")

    # 3. Multi-rate slow drift challenge fixtures
    print("\n--- Testing Multi-Rate Slow-Drift Challenge ---")
    drift_cases = [
        ("fast_drift_0_60", 0.60 * 0.158),       # ~0.095 torso/s (kinematics MID band)
        ("threshold_drift_0_35", 0.35 * 0.158),  # ~0.055 torso/s (kinematics QUIET band)
        ("slow_drift_0_15", 0.15 * 0.158),       # ~0.024 torso/s (kinematics firmly QUIET)
    ]

    drift_results = {}
    for name, rate in drift_cases:
        fix = create_synthetic_drift_fixture(rate, name)
        drift_results[name] = {}
        print(f"\nDrift Case: {name} (drift rate = {rate:.4f} torso/s)...")
        for pol in policies:
            out = OUTPUT_BASE / "drift" / name / pol
            res = run_session(fix, out, pol, settling_limit=0.03, required_regions="TORSO,RIGHT_ARM")
            drift_results[name][pol] = res
            completed = [s for s in res["segments"] if s["completed"]]
            if completed:
                first_complete = completed[0]
                term_boundary = first_complete["terminal_boundary_timestamp_ms"]
                premature = term_boundary < 2000
                status = "PREMATURE FAILURE" if premature else "SAFE PASS"
                print(f"  [{pol}] Completed: boundary={term_boundary}ms -> {status}")
            else:
                print(f"  [{pol}] No completed segment -> SAFE PASS (stayed moving/rearming)")

    # 4. Generate Comprehensive Report
    DOCS_DIR.mkdir(parents=True, exist_ok=True)
    report_file = DOCS_DIR / "task5i-phase5-displacement-variants.md"

    with open(report_file, "w", encoding="utf-8") as f:
        f.write("# Task 5 Phase 5: Terminal Slow-Displacement Variants & Kihon Recovery Report\n\n")
        f.write("## 1. Executive Summary\n\n")
        f.write("Phase 5 systematically evaluated the three terminal displacement policies to resolve the 10-punch kihon sequence collapse in Recording A while strictly preserving combination grouping in Recording B and slow-drift protection.\n\n")

        f.write("### Policy Definitions\n")
        f.write("1. **Variant 1 (`TRAILING_WINDOW`, Control)**: Legacy 300 ms sliding window displacement $\\le 0.05$ torso units.\n")
        f.write("2. **Variant 2 (`DISABLED`)**: Pure kinematic quiet ($E_T \\le 0.45, E_A \\le 20.0$). In `SETTLING`, `QUIET` accumulates dwell, `MID` pauses dwell, `MOVING` resumes moving. Stateful displacement gate completely removed.\n")
        f.write("3. **Variant 3 (`SETTLING_LOCAL`)**: Kinematics enter `SETTLING` on first quiet candidate $t_q$ and capture anchor pose $P_{\\text{anchor}}$. Settlement requires continuous quiet dwell with anchor-relative displacement $d(P_{\\text{anchor}}, P(t)) \\le \\delta_{\\text{settling\\_max}} = 0.03$.\n\n")

        f.write("## 2. Comparison Matrix\n\n")
        f.write("| Policy | Recording A Completed | Recording B Completed | Drift @ 0.60 Lref/s | Drift @ 0.35 Lref/s | Drift @ 0.15 Lref/s | Verdict |\n")
        f.write("| :--- | :---: | :---: | :---: | :---: | :---: | :---: |\n")

        for pol in policies:
            # Rec A completed count
            c_a = len([s for s in rec_a_results[pol]["segments"] if s["completed"]])
            rec_a_str = f"{c_a} completed"

            # Rec B completed count
            c_b = len([s for s in rec_b_results[pol]["segments"] if s["completed"]])
            rec_b_str = f"{c_b}/5 completed"

            # Drift checks
            d_res = []
            for name, _ in drift_cases:
                comp = [s for s in drift_results[name][pol]["segments"] if s["completed"]]
                if comp:
                    tb = comp[0]["terminal_boundary_timestamp_ms"]
                    if tb < 2000:
                        d_res.append(f"PREMATURE FAIL ({tb}ms)")
                    else:
                        d_res.append(f"PASS ({tb}ms)")
                else:
                    d_res.append("PASS (no prem)")

            verdict = "PASS" if c_b == 5 and all("FAIL" not in r for r in d_res) else "EVALUATE"
            f.write(f"| `{pol}` | {rec_a_str} | {rec_b_str} | {d_res[0]} | {d_res[1]} | {d_res[2]} | **{verdict}** |\n")

        f.write("\n## 3. Recording A: Per-Movement Breakdown\n\n")
        for pol in policies:
            f.write(f"### Policy: `{pol}`\n\n")
            f.write("| Movement # | Start (ms) | End (ms) | Duration (ms) | Start Frame | End Frame | Resumptions |\n")
            f.write("| :---: | :---: | :---: | :---: | :---: | :---: | :---: |\n")
            for s in rec_a_results[pol]["segments"]:
                if s["completed"]:
                    f.write(f"| {s['movement_number']} | {s['start_boundary_timestamp_ms']} | {s['terminal_boundary_timestamp_ms']} | {s['duration_ms']} | {s['start_boundary_frame_index']} | {s['terminal_boundary_frame_index']} | {s['settling_resumptions']} |\n")
            f.write("\n")

        f.write("## 4. Recording B: Movements Breakdown\n\n")
        for pol in policies:
            f.write(f"### Policy: `{pol}`\n\n")
            f.write("| Movement # | Start (ms) | End (ms) | Duration (ms) | Start Frame | End Frame | Resumptions |\n")
            f.write("| :---: | :---: | :---: | :---: | :---: | :---: | :---: |\n")
            for s in rec_b_results[pol]["segments"]:
                if s["completed"]:
                    f.write(f"| {s['movement_number']} | {s['start_boundary_timestamp_ms']} | {s['terminal_boundary_timestamp_ms']} | {s['duration_ms']} | {s['start_boundary_frame_index']} | {s['terminal_boundary_frame_index']} | {s['settling_resumptions']} |\n")
            f.write("\n")

    print(f"\nReport written to {report_file}")


if __name__ == "__main__":
    main()
