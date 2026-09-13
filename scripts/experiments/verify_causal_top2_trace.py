"""Phase 2C Verification: Causal Top-2 Kinematics Trace Parity (Kotlin vs Python).

Verifies frame-by-frame mathematical parity of the causal trailing linear regression smoother
and Top-2 mechanical evidence aggregation on Recording A (real-kihon-10-punch.fixture.json).
"""
from __future__ import annotations

import json
import math
from pathlib import Path
import matplotlib
matplotlib.use("Agg")
import matplotlib.pyplot as plt
import numpy as np

ROOT = Path(__file__).resolve().parents[2]
FIXTURE_PATH = ROOT / "output/task5/real-kihon-10-punch.fixture.json"
KOTLIN_TRACE_PATH = ROOT / "output/task5/trace-verification/recording-a/blind-session.trace.json"
DEST = ROOT / "docs/validation/task5/trace-verification"
DEST.mkdir(parents=True, exist_ok=True)


def unwrap_difference_360(diff_deg: float) -> float:
    return ((diff_deg + 180.0) % 360.0 + 360.0) % 360.0 - 180.0


def unwrap_difference_180(diff_deg: float) -> float:
    return ((diff_deg + 90.0) % 180.0 + 180.0) % 180.0 - 90.0


def fit_velocity_2d(samples: list[tuple[float, float, float, float]], target_t: float,
                    window_sec: float = 0.100, min_samples: int = 3, min_span_ratio: float = 0.60,
                    min_conf: float = 0.50) -> tuple[float, float, float, float] | None:
    """OLS linear regression for 2D velocity: returns (vx, vy, speed, min_quality)."""
    min_t = target_t - window_sec - 1e-6
    window = [s for s in samples if min_t <= s[0] <= target_t]

    if not window or abs(window[-1][0] - target_t) > 1e-4:
        return None
    if len(window) < min_samples:
        return None
    span = window[-1][0] - window[0][0]
    if span < window_sec * min_span_ratio:
        return None

    min_quality = min(s[3] for s in window)
    mean_t = sum(s[0] for s in window) / len(window)
    sum_tau_sq = 0.0
    sum_tau_x = 0.0
    sum_tau_y = 0.0
    for t, x, y, conf in window:
        tau = t - mean_t
        sum_tau_sq += tau * tau
        sum_tau_x += tau * x
        sum_tau_y += tau * y

    if sum_tau_sq <= 1e-9:
        return None

    vx = sum_tau_x / sum_tau_sq
    vy = sum_tau_y / sum_tau_sq
    speed = math.sqrt(vx * vx + vy * vy)
    return (vx, vy, speed, min_quality)


def fit_angular_rate(samples: list[tuple[float, float, float]], target_t: float,
                     is_undirected: bool = False, window_sec: float = 0.100,
                     min_samples: int = 3, min_span_ratio: float = 0.60,
                     min_conf: float = 0.50) -> tuple[float, float, float] | None:
    """OLS linear regression for unwrapped angle rate: returns (signed_rate, speed, min_quality)."""
    min_t = target_t - window_sec - 1e-6
    window = [s for s in samples if min_t <= s[0] <= target_t]

    if not window or abs(window[-1][0] - target_t) > 1e-4:
        return None
    if len(window) < min_samples:
        return None
    span = window[-1][0] - window[0][0]
    if span < window_sec * min_span_ratio:
        return None

    min_quality = min(s[2] for s in window)
    unwrapped = [window[0][1]]
    for i in range(1, len(window)):
        raw_diff = window[i][1] - window[i - 1][1]
        diff = unwrap_difference_180(raw_diff) if is_undirected else unwrap_difference_360(raw_diff)
        unwrapped.append(unwrapped[-1] + diff)

    mean_t = sum(s[0] for s in window) / len(window)
    sum_tau_sq = 0.0
    sum_tau_theta = 0.0
    for i, (t, _, _) in enumerate(window):
        tau = t - mean_t
        sum_tau_sq += tau * tau
        sum_tau_theta += tau * unwrapped[i]

    if sum_tau_sq <= 1e-9:
        return None

    signed_rate = sum_tau_theta / sum_tau_sq
    speed = abs(signed_rate)
    return (signed_rate, speed, min_quality)


def compute_python_top2_trace(fixture: dict, ref_scales: list[float | None]) -> list[dict]:
    frames = fixture["frames"]
    all_poses = []

    # 1. Parse landmark positions with confidence gating (matching PoseMotionObservationExtractor.usable)
    for f in frames:
        t_sec = f["timestamp_ms"] / 1000.0
        lm_map = {}
        for lm in f["landmarks"]:
            ident = lm["id"]
            pos = lm.get("normalized")
            vis = lm.get("visibility", 1.0)
            pres = lm.get("presence", 1.0)
            conf = min(vis, pres) if (vis is not None and pres is not None) else 0.0
            if pos is not None and len(pos) >= 2 and conf >= 0.50:
                # Store as 32-bit float to match Kotlin's Float Point3 coordinates exactly
                lm_map[ident] = (float(np.float32(pos[0])), float(np.float32(pos[1])), conf)
        all_poses.append({"timestamp_ms": f["timestamp_ms"], "t_sec": t_sec, "landmarks": lm_map})

    translation_landmarks = [
        "LEFT_SHOULDER", "RIGHT_SHOULDER",
        "LEFT_ELBOW", "RIGHT_ELBOW",
        "LEFT_WRIST", "RIGHT_WRIST",
        "LEFT_HIP", "RIGHT_HIP",
        "LEFT_KNEE", "RIGHT_KNEE",
        "LEFT_ANKLE", "RIGHT_ANKLE",
    ]

    joint_angles = [
        (("LEFT_SHOULDER", "LEFT_ELBOW", "LEFT_WRIST"), "LEFT_ELBOW_ANGLE"),
        (("RIGHT_SHOULDER", "RIGHT_ELBOW", "RIGHT_WRIST"), "RIGHT_ELBOW_ANGLE"),
        (("LEFT_HIP", "LEFT_KNEE", "LEFT_ANKLE"), "LEFT_KNEE_ANGLE"),
        (("RIGHT_HIP", "RIGHT_KNEE", "RIGHT_ANKLE"), "RIGHT_KNEE_ANGLE"),
    ]

    segments = [
        (("LEFT_SHOULDER", "LEFT_ELBOW"), "LEFT_UPPER_ARM_ORIENTATION"),
        (("RIGHT_SHOULDER", "RIGHT_ELBOW"), "RIGHT_UPPER_ARM_ORIENTATION"),
        (("LEFT_ELBOW", "LEFT_WRIST"), "LEFT_FOREARM_ORIENTATION"),
        (("RIGHT_ELBOW", "RIGHT_WRIST"), "RIGHT_FOREARM_ORIENTATION"),
        (("LEFT_HIP", "LEFT_KNEE"), "LEFT_THIGH_ORIENTATION"),
        (("RIGHT_HIP", "RIGHT_KNEE"), "RIGHT_THIGH_ORIENTATION"),
        (("LEFT_KNEE", "LEFT_ANKLE"), "LEFT_SHIN_ORIENTATION"),
        (("RIGHT_KNEE", "RIGHT_ANKLE"), "RIGHT_SHIN_ORIENTATION"),
    ]

    axes = [
        (("LEFT_SHOULDER", "RIGHT_SHOULDER"), "SHOULDER_AXIS_ORIENTATION"),
        (("LEFT_HIP", "RIGHT_HIP"), "HIP_AXIS_ORIENTATION"),
    ]

    py_trace = []
    history = []

    for frame_idx, p in enumerate(all_poses):
        history.append(p)
        t_curr = p["t_sec"]
        cutoff = t_curr - 0.400
        history = [h for h in history if h["t_sec"] >= cutoff]

        l_ref = ref_scales[frame_idx]
        effective_scale = l_ref if l_ref is not None else 1.0
        min_seg_len = 0.15 * effective_scale
        min_axis_len = 0.25 * effective_scale

        # A. Fit translation channels (scaled by l_ref)
        trans_results = {}
        if l_ref is not None and l_ref > 1e-6:
            for lm_id in translation_landmarks:
                samples = []
                for h in history:
                    lm = h["landmarks"].get(lm_id)
                    if lm is not None:
                        samples.append((h["t_sec"], lm[0] / l_ref, lm[1] / l_ref, lm[2]))
                res = fit_velocity_2d(samples, t_curr)
                if res is not None:
                    trans_results[lm_id] = res

        # B. Fit joint interior angles
        angle_results = {}
        for (jA, jB, jC), name in joint_angles:
            samples = []
            for h in history:
                lA = h["landmarks"].get(jA)
                lB = h["landmarks"].get(jB)
                lC = h["landmarks"].get(jC)
                if lA and lB and lC:
                    uX = lA[0] - lB[0]
                    uY = lA[1] - lB[1]
                    vX = lC[0] - lB[0]
                    vY = lC[1] - lB[1]
                    uLen = math.sqrt(uX * uX + uY * uY)
                    vLen = math.sqrt(vX * vX + vY * vY)
                    if uLen >= 1e-6 and vLen >= 1e-6:
                        dot = (uX * vX + uY * vY) / (uLen * vLen)
                        dot_clamped = max(-1.0, min(1.0, dot))
                        deg = math.degrees(math.acos(dot_clamped))
                        samples.append((h["t_sec"], deg, min(lA[2], lB[2], lC[2])))
            res = fit_angular_rate(samples, t_curr, is_undirected=False)
            if res is not None:
                angle_results[name] = res

        # C. Fit directed segment orientations
        for (p1_id, p2_id), name in segments:
            samples = []
            for h in history:
                l1 = h["landmarks"].get(p1_id)
                l2 = h["landmarks"].get(p2_id)
                if l1 and l2:
                    dx = l2[0] - l1[0]
                    dy = l2[1] - l1[1]
                    length = math.sqrt(dx * dx + dy * dy)
                    if length >= min_seg_len:
                        deg = math.degrees(math.atan2(dy, dx))
                        samples.append((h["t_sec"], deg, min(l1[2], l2[2])))
            res = fit_angular_rate(samples, t_curr, is_undirected=False)
            if res is not None:
                angle_results[name] = res

        # D. Fit undirected axes
        for (p1_id, p2_id), name in axes:
            samples = []
            for h in history:
                l1 = h["landmarks"].get(p1_id)
                l2 = h["landmarks"].get(p2_id)
                if l1 and l2:
                    dx = l2[0] - l1[0]
                    dy = l2[1] - l1[1]
                    length = math.sqrt(dx * dx + dy * dy)
                    if length >= min_axis_len:
                        deg = math.degrees(math.atan2(dy, dx))
                        samples.append((h["t_sec"], deg, min(l1[2], l2[2])))
            res = fit_angular_rate(samples, t_curr, is_undirected=True)
            if res is not None:
                angle_results[name] = res

        # Top-2 translation aggregation: arithmetic mean of top 2
        sorted_trans = sorted(
            [(k, v[2]) for k, v in trans_results.items() if v[3] >= 0.50],
            key=lambda x: x[1], reverse=True
        )
        if len(sorted_trans) >= 2:
            e_t = (sorted_trans[0][1] + sorted_trans[1][1]) * 0.5
            tc1, tc2 = sorted_trans[0][0], sorted_trans[1][0]
        elif len(sorted_trans) == 1:
            e_t = sorted_trans[0][1]
            tc1, tc2 = sorted_trans[0][0], None
        else:
            e_t, tc1, tc2 = None, None, None

        # Top-2 angular aggregation: arithmetic mean of top 2
        sorted_ang = sorted(
            [(k, v[1]) for k, v in angle_results.items() if v[2] >= 0.50],
            key=lambda x: x[1], reverse=True
        )
        if len(sorted_ang) >= 2:
            e_a = (sorted_ang[0][1] + sorted_ang[1][1]) * 0.5
            ac1, ac2 = sorted_ang[0][0], sorted_ang[1][0]
        elif len(sorted_ang) == 1:
            e_a = sorted_ang[0][1]
            ac1, ac2 = sorted_ang[0][0], None
        else:
            e_a, ac1, ac2 = None, None, None

        py_trace.append({
            "frame": frame_idx,
            "timestamp_ms": p["timestamp_ms"],
            "e_t": e_t,
            "tc1": tc1,
            "tc2": tc2,
            "e_a": e_a,
            "ac1": ac1,
            "ac2": ac2,
            "ref_scale": l_ref,
            "trans_channels": {k: v[2] for k, v in trans_results.items()},
            "ang_channels": {k: v[1] for k, v in angle_results.items()},
        })

    return py_trace


def compare_traces():
    print(f"Loading fixture: {FIXTURE_PATH}")
    with open(FIXTURE_PATH, "r", encoding="utf-8") as f:
        fixture = json.load(f)

    print(f"Loading Kotlin trace: {KOTLIN_TRACE_PATH}")
    with open(KOTLIN_TRACE_PATH, "r", encoding="utf-8") as f:
        kt_data = json.load(f)

    kt_frames = kt_data["frames"]
    ref_scales = [f.get("top2", {}).get("reference_scale") for f in kt_frames]

    py_trace = compute_python_top2_trace(fixture, ref_scales)

    assert len(py_trace) == len(kt_frames), f"Frame count mismatch: {len(py_trace)} vs {len(kt_frames)}"

    diff_e_t = []
    diff_e_a = []
    t_ms = []
    kt_et_series = []
    py_et_series = []
    kt_ea_series = []
    py_ea_series = []

    channel_mismatches_t = 0
    channel_mismatches_a = 0

    for i in range(len(py_trace)):
        py_row = py_trace[i]
        kt_row = kt_frames[i]

        assert py_row["timestamp_ms"] == kt_row["timestamp_ms"], f"Timestamp mismatch at frame {i}"
        t = py_row["timestamp_ms"]
        t_ms.append(t)

        kt_top2 = kt_row.get("top2", {})
        kt_et = kt_top2.get("translation_evidence")
        kt_ea = kt_top2.get("angular_evidence")
        kt_tc1 = kt_top2.get("translation_channel_1")
        kt_tc2 = kt_top2.get("translation_channel_2")
        kt_ac1 = kt_top2.get("angular_channel_1")
        kt_ac2 = kt_top2.get("angular_channel_2")

        py_et = py_row["e_t"]
        py_ea = py_row["e_a"]
        py_tc1 = py_row["tc1"]
        py_tc2 = py_row["tc2"]
        py_ac1 = py_row["ac1"]
        py_ac2 = py_row["ac2"]

        kt_et_series.append(kt_et)
        py_et_series.append(py_et)
        kt_ea_series.append(kt_ea)
        py_ea_series.append(py_ea)

        if kt_et is not None and py_et is not None:
            diff_e_t.append(abs(kt_et - py_et))
            if (kt_tc1, kt_tc2) != (py_tc1, py_tc2):
                channel_mismatches_t += 1
        elif (kt_et is None) != (py_et is None):
            print(f"Nullness mismatch E_T at frame {i} ({t} ms): Kotlin={kt_et}, Python={py_et}")

        if kt_ea is not None and py_ea is not None:
            diff_e_a.append(abs(kt_ea - py_ea))
            if (kt_ac1, kt_ac2) != (py_ac1, py_ac2):
                channel_mismatches_a += 1
        elif (kt_ea is None) != (py_ea is None):
            print(f"Nullness mismatch E_A at frame {i} ({t} ms): Kotlin={kt_ea}, Python={py_ea}")

    max_diff_t = max(diff_e_t) if diff_e_t else 0.0
    mean_diff_t = sum(diff_e_t) / len(diff_e_t) if diff_e_t else 0.0
    max_diff_a = max(diff_e_a) if diff_e_a else 0.0
    mean_diff_a = sum(diff_e_a) / len(diff_e_a) if diff_e_a else 0.0

    print("\n--- Parity Results ---")
    print(f"Total compared frames: {len(py_trace)}")
    print(f"Translation Evidence E_T: max absolute difference = {max_diff_t:.6e} L_ref/s, mean = {mean_diff_t:.6e}")
    print(f"Angular Evidence E_A:     max absolute difference = {max_diff_a:.6e} deg/s,   mean = {mean_diff_a:.6e}")
    print(f"Top-2 Translation Channel order matches: {len(diff_e_t) - channel_mismatches_t}/{len(diff_e_t)} ({(len(diff_e_t) - channel_mismatches_t)/len(diff_e_t)*100:.1f}%)")
    print(f"Top-2 Angular Channel order matches:     {len(diff_e_a) - channel_mismatches_a}/{len(diff_e_a)} ({(len(diff_e_a) - channel_mismatches_a)/len(diff_e_a)*100:.1f}%)")

    # Plot overlay comparison
    fig, (ax1, ax2, ax3) = plt.subplots(3, 1, figsize=(14, 9), sharex=True)
    t_sec = np.array(t_ms) / 1000.0

    # 1. Translation overlay
    ax1.plot(t_sec, kt_et_series, label="Kotlin E_T", color="blue", lw=1.5)
    ax1.plot(t_sec, py_et_series, label="Python E_T (causal reference)", color="orange", ls="--", lw=1.2, alpha=0.8)
    ax1.set_ylabel("Translation Evidence (L_ref / s)")
    ax1.legend(loc="upper right")
    ax1.grid(True, alpha=0.3)
    ax1.set_title("Causal Top-2 Translation Evidence: Kotlin vs Python Exact Overlay")

    # 2. Angular overlay
    ax2.plot(t_sec, kt_ea_series, label="Kotlin E_A", color="green", lw=1.5)
    ax2.plot(t_sec, py_ea_series, label="Python E_A (causal reference)", color="red", ls="--", lw=1.2, alpha=0.8)
    ax2.set_ylabel("Angular Evidence (deg / s)")
    ax2.legend(loc="upper right")
    ax2.grid(True, alpha=0.3)
    ax2.set_title("Causal Top-2 Angular Evidence: Kotlin vs Python Exact Overlay")

    # 3. Residual difference
    t_valid_t = [t_sec[i] for i in range(len(t_sec)) if kt_et_series[i] is not None and py_et_series[i] is not None]
    t_valid_a = [t_sec[i] for i in range(len(t_sec)) if kt_ea_series[i] is not None and py_ea_series[i] is not None]
    ax3.plot(t_valid_t, diff_e_t, label="E_T absolute error", color="purple", lw=1.0)
    ax3.plot(t_valid_a, diff_e_a, label="E_A absolute error", color="brown", lw=1.0)
    ax3.set_ylabel("Absolute Error")
    ax3.set_xlabel("Recording time (seconds)")
    ax3.legend(loc="upper right")
    ax3.grid(True, alpha=0.3)
    ax3.set_yscale("log")
    ax3.set_title("Numerical Residuals (Log Scale)")

    fig.tight_layout()
    plot_path = DEST / "causal-top2-trace-parity.png"
    fig.savefig(plot_path, dpi=150)
    plt.close(fig)
    print(f"Saved parity plot: {plot_path}")

    report = {
        "recording": "real-kihon-10-punch.fixture.json",
        "total_frames": len(py_trace),
        "evaluated_frames_et": len(diff_e_t),
        "evaluated_frames_ea": len(diff_e_a),
        "max_difference_translation": max_diff_t,
        "mean_difference_translation": mean_diff_t,
        "max_difference_angular": max_diff_a,
        "mean_difference_angular": mean_diff_a,
        "channel_matches_translation": len(diff_e_t) - channel_mismatches_t,
        "channel_matches_angular": len(diff_e_a) - channel_mismatches_a,
        "numerical_parity_status": "EXACT" if (max_diff_t < 1e-4 and max_diff_a < 1e-4) else "HIGH_PARITY" if (mean_diff_t < 1e-3 and mean_diff_a < 0.1) else "DIVERGENT",
    }
    with open(DEST / "causal-top2-parity-summary.json", "w", encoding="utf-8") as f:
        json.dump(report, f, indent=2)

    return report


if __name__ == "__main__":
    compare_traces()

