"""Task 5I: Causal Evidence Distribution & Threshold Generalization Evaluation.

Evaluates causal Top-2 mechanical evidence ET(t) and EA(t) across Recordings A and B:
1. Quiet distributions: median, p90, p95, p99, max, and burst durations.
2. Movement onset dynamics: rise time, lead family (translation vs angular), crossing delays.
3. Ternary combination model: translation and angular separate evaluations.
4. Generalizing threshold grid: evaluate candidate fixed thresholds on both A and B.

Does NOT modify production segmentation or smoother/Top-2 mechanical implementation.
"""
from __future__ import annotations

import json
import math
from pathlib import Path
from collections import Counter

import matplotlib
matplotlib.use("Agg")
import matplotlib.pyplot as plt
import numpy as np

ROOT = Path(__file__).resolve().parents[2]
TRACE_A_PATH = ROOT / "output/task5/trace-verification/recording-a/blind-session.trace.json"
TRACE_B_PATH = ROOT / "output/task5/trace-verification/recording-b/blind-session.trace.json"
LABELS_A_PATH = ROOT / "docs/validation/task5/proposed-labels.json"
DEST = ROOT / "docs/validation/task5/threshold-evaluation"
DEST.mkdir(parents=True, exist_ok=True)


def load_trace(path: Path) -> list[dict]:
    with open(path, "r", encoding="utf-8") as f:
        data = json.load(f)
    return data["frames"]


def get_intervals(recording: str) -> tuple[list[tuple[int, int]], list[tuple[int, int]]]:
    """Returns (movement_intervals, hold_intervals) in milliseconds."""
    if recording == "a":
        with open(LABELS_A_PATH, "r", encoding="utf-8") as f:
            labels_data = json.load(f)
        punches = labels_data["punches"]
        movements = [(p["movement_start_timestamp_ms"], p["movement_end_timestamp_ms"]) for p in punches]
        # Include opening hold (0 to 400 ms) plus terminal stable holds after each punch
        holds = [(0, 400)]
        for p in punches:
            holds.append((p["terminal_stable_start_timestamp_ms"], p["terminal_stable_end_timestamp_ms"]))
        return movements, holds
    else:
        # Recording B review intervals (from Task 5D/5G review)
        movements = [
            (2030, 2680),
            (3800, 7128),
            (7300, 8468),
            (8850, 11230),
            (11555, 12652),
            (13769, 15048),
        ]
        holds = [
            (0, 2030),      # Opening hold
            (2680, 2860),
            (3635, 3800),
            (7128, 7300),
            (8468, 8850),
            (11230, 11555),
            (12652, 13769),
        ]
        return movements, holds


def compute_distribution_stats(values: list[float]) -> dict:
    valid = [v for v in values if v is not None and math.isfinite(v)]
    if not valid:
        return {"count": 0}
    arr = np.array(valid)
    return {
        "count": len(valid),
        "mean": float(np.mean(arr)),
        "std": float(np.std(arr)),
        "min": float(np.min(arr)),
        "median": float(np.median(arr)),
        "p75": float(np.percentile(arr, 75)),
        "p90": float(np.percentile(arr, 90)),
        "p95": float(np.percentile(arr, 95)),
        "p98": float(np.percentile(arr, 98)),
        "p99": float(np.percentile(arr, 99)),
        "max": float(np.max(arr)),
    }


def analyze_spikes(frames: list[dict], holds: list[tuple[int, int]],
                   threshold_t: float, threshold_a: float) -> dict:
    """Measures hold-positive frames and longest burst durations during holds."""
    total_hold_frames = 0
    trans_pos_count = 0
    ang_pos_count = 0
    either_pos_count = 0

    all_bursts_trans = []
    all_bursts_ang = []
    all_bursts_either = []

    for h_start, h_end in holds:
        h_frames = [f for f in frames if h_start <= f["timestamp_ms"] < h_end]
        total_hold_frames += len(h_frames)

        def get_hold_bursts(pos_flags: list[bool], ts_list: list[int]) -> list[int]:
            bursts = []
            cur_start = None
            for i, p in enumerate(pos_flags):
                if p:
                    if cur_start is None:
                        cur_start = ts_list[i]
                else:
                    if cur_start is not None:
                        bursts.append(ts_list[i] - cur_start)
                        cur_start = None
            if cur_start is not None:
                bursts.append(ts_list[-1] - cur_start)
            return bursts

        ts_list = [f["timestamp_ms"] for f in h_frames]
        trans_flags = []
        ang_flags = []
        either_flags = []

        for f in h_frames:
            top2 = f.get("top2", {})
            et = top2.get("translation_evidence")
            ea = top2.get("angular_evidence")
            t_pos = (et is not None and et >= threshold_t)
            a_pos = (ea is not None and ea >= threshold_a)
            trans_flags.append(t_pos)
            ang_flags.append(a_pos)
            either_flags.append(t_pos or a_pos)

        trans_pos_count += sum(trans_flags)
        ang_pos_count += sum(ang_flags)
        either_pos_count += sum(either_flags)

        all_bursts_trans.extend(get_hold_bursts(trans_flags, ts_list))
        all_bursts_ang.extend(get_hold_bursts(ang_flags, ts_list))
        all_bursts_either.extend(get_hold_bursts(either_flags, ts_list))

    if total_hold_frames == 0:
        return {}

    return {
        "total_hold_frames": total_hold_frames,
        "translation_pos_frames": trans_pos_count,
        "angular_pos_frames": ang_pos_count,
        "either_pos_frames": either_pos_count,
        "translation_pos_pct": trans_pos_count / total_hold_frames * 100.0,
        "angular_pos_pct": ang_pos_count / total_hold_frames * 100.0,
        "either_pos_pct": either_pos_count / total_hold_frames * 100.0,
        "max_burst_ms_trans": max(all_bursts_trans, default=0),
        "max_burst_ms_ang": max(all_bursts_ang, default=0),
        "max_burst_ms_either": max(all_bursts_either, default=0),
    }


def analyze_onsets(frames: list[dict], movements: list[tuple[int, int]],
                   candidate_t_thresh: float, candidate_a_thresh: float) -> list[dict]:
    """Measures onset dynamics, which channel leads, and crossing latency."""
    onsets = []
    ts_list = [f["timestamp_ms"] for f in frames]

    for mvt_idx, (start_ms, end_ms) in enumerate(movements, 1):
        # Find index of frame closest to start_ms
        start_idx = min(range(len(ts_list)), key=lambda i: abs(ts_list[i] - start_ms))
        
        # Look forward up to end_ms or +500 ms
        window_frames = [f for f in frames if start_ms <= f["timestamp_ms"] <= min(end_ms, start_ms + 600)]
        
        t_cross_ms = None
        a_cross_ms = None
        first_cross_ms = None
        leading_family = None

        for f in window_frames:
            ts = f["timestamp_ms"]
            top2 = f.get("top2", {})
            et = top2.get("translation_evidence")
            ea = top2.get("angular_evidence")

            if t_cross_ms is None and et is not None and et >= candidate_t_thresh:
                t_cross_ms = ts
            if a_cross_ms is None and ea is not None and ea >= candidate_a_thresh:
                a_cross_ms = ts

        if t_cross_ms is not None and a_cross_ms is not None:
            first_cross_ms = min(t_cross_ms, a_cross_ms)
            if t_cross_ms < a_cross_ms:
                leading_family = "TRANSLATION"
            elif a_cross_ms < t_cross_ms:
                leading_family = "ANGULAR"
            else:
                leading_family = "SIMULTANEOUS"
        elif t_cross_ms is not None:
            first_cross_ms = t_cross_ms
            leading_family = "TRANSLATION_ONLY"
        elif a_cross_ms is not None:
            first_cross_ms = a_cross_ms
            leading_family = "ANGULAR_ONLY"
        else:
            leading_family = "NEITHER"

        onsets.append({
            "movement_number": mvt_idx,
            "reviewed_start_ms": start_ms,
            "reviewed_end_ms": end_ms,
            "translation_crossing_ms": t_cross_ms,
            "translation_latency_ms": (t_cross_ms - start_ms) if t_cross_ms is not None else None,
            "angular_crossing_ms": a_cross_ms,
            "angular_latency_ms": (a_cross_ms - start_ms) if a_cross_ms is not None else None,
            "first_crossing_ms": first_cross_ms,
            "first_crossing_latency_ms": (first_cross_ms - start_ms) if first_cross_ms is not None else None,
            "leading_family": leading_family,
        })

    return onsets


def evaluate_threshold_grid(frames_a: list[dict], holds_a: list[tuple[int, int]], mvts_a: list[tuple[int, int]],
                            frames_b: list[dict], holds_b: list[tuple[int, int]], mvts_b: list[tuple[int, int]]) -> list[dict]:
    """Tests a grid of fixed candidate thresholds across both A and B."""
    t_thresholds = [0.30, 0.40, 0.50, 0.60, 0.70, 0.80, 0.90]
    a_thresholds = [20.0, 25.0, 30.0, 35.0, 40.0, 45.0, 50.0]

    grid_results = []

    for t_th in t_thresholds:
        for a_th in a_thresholds:
            spikes_a = analyze_spikes(frames_a, holds_a, t_th, a_th)
            spikes_b = analyze_spikes(frames_b, holds_b, t_th, a_th)
            onsets_a = analyze_onsets(frames_a, mvts_a, t_th, a_th)
            onsets_b = analyze_onsets(frames_b, mvts_b, t_th, a_th)

            detected_a = sum(1 for o in onsets_a if o["first_crossing_ms"] is not None)
            detected_b = sum(1 for o in onsets_b if o["first_crossing_ms"] is not None)
            
            latencies_a = [o["first_crossing_latency_ms"] for o in onsets_a if o["first_crossing_latency_ms"] is not None]
            latencies_b = [o["first_crossing_latency_ms"] for o in onsets_b if o["first_crossing_latency_ms"] is not None]

            mean_lat_a = float(np.mean(latencies_a)) if latencies_a else 999.0
            mean_lat_b = float(np.mean(latencies_b)) if latencies_b else 999.0

            grid_results.append({
                "t_threshold": t_th,
                "a_threshold": a_th,
                "rec_a_hold_pos_pct": spikes_a["either_pos_pct"],
                "rec_a_max_burst_ms": spikes_a["max_burst_ms_either"],
                "rec_a_detected_movements": f"{detected_a}/{len(mvts_a)}",
                "rec_a_mean_latency_ms": round(mean_lat_a, 1),
                "rec_b_hold_pos_pct": spikes_b["either_pos_pct"],
                "rec_b_max_burst_ms": spikes_b["max_burst_ms_either"],
                "rec_b_detected_movements": f"{detected_b}/{len(mvts_b)}",
                "rec_b_mean_latency_ms": round(mean_lat_b, 1),
            })

    return grid_results


def generate_plots(frames_a: list[dict], mvts_a: list[tuple[int, int]], holds_a: list[tuple[int, int]],
                   frames_b: list[dict], mvts_b: list[tuple[int, int]], holds_b: list[tuple[int, int]],
                   cand_t_th: float, cand_a_th: float):
    """Generates trace plots with reviewed intervals and candidate thresholds."""
    for rec_name, frames, mvts, holds in [("A", frames_a, mvts_a, holds_a), ("B", frames_b, mvts_b, holds_b)]:
        fig, (ax1, ax2) = plt.subplots(2, 1, figsize=(15, 8), sharex=True)
        t_sec = [f["timestamp_ms"] / 1000.0 for f in frames]
        et = [f.get("top2", {}).get("translation_evidence") for f in frames]
        ea = [f.get("top2", {}).get("angular_evidence") for f in frames]

        # Translation trace
        ax1.plot(t_sec, et, color="navy", lw=1.3, label="Causal Top-2 Translation E_T")
        ax1.axhline(cand_t_th, color="crimson", ls="--", lw=1.2, label=f"Candidate Threshold ({cand_t_th} L_ref/s)")
        ax1.set_ylabel("Translation (L_ref / s)")
        ax1.grid(True, alpha=0.3)
        ax1.legend(loc="upper right", fontsize=9)

        # Angular trace
        ax2.plot(t_sec, ea, color="darkgreen", lw=1.3, label="Causal Top-2 Angular E_A")
        ax2.axhline(cand_a_th, color="darkred", ls="--", lw=1.2, label=f"Candidate Threshold ({cand_a_th} deg/s)")
        ax2.set_ylabel("Angular Speed (deg / s)")
        ax2.set_xlabel("Recording time (seconds)")
        ax2.grid(True, alpha=0.3)
        ax2.legend(loc="upper right", fontsize=9)

        # Shade intervals
        for start_ms, end_ms in mvts:
            for ax in (ax1, ax2):
                ax.axvspan(start_ms / 1000.0, end_ms / 1000.0, color="lightcoral", alpha=0.20, label="_movement" if start_ms == mvts[0][0] else "")
        for start_ms, end_ms in holds:
            for ax in (ax1, ax2):
                ax.axvspan(start_ms / 1000.0, end_ms / 1000.0, color="lightgreen", alpha=0.25, label="_hold" if start_ms == holds[0][0] else "")

        fig.suptitle(f"Recording {rec_name}: Causal Top-2 Evidence with Reviewed Movement (Red) & Hold (Green) Intervals", fontsize=12)
        fig.tight_layout()
        out_path = DEST / f"recording-{rec_name.lower()}-causal-evidence-trace.png"
        fig.savefig(out_path, dpi=140)
        plt.close(fig)
        print(f"Saved trace plot: {out_path}")

    # Distribution comparison histograms (Quiet vs Active)
    fig, ((ax1, ax2), (ax3, ax4)) = plt.subplots(2, 2, figsize=(14, 10))

    def split_signals(frames, mvts, holds):
        q_et, a_et = [], []
        q_ea, a_ea = [], []
        for f in frames:
            ts = f["timestamp_ms"]
            top2 = f.get("top2", {})
            et_val = top2.get("translation_evidence")
            ea_val = top2.get("angular_evidence")
            is_hold = any(a <= ts < b for a, b in holds)
            is_mvt = any(a <= ts < b for a, b in mvts)
            if is_hold:
                if et_val is not None: q_et.append(et_val)
                if ea_val is not None: q_ea.append(ea_val)
            elif is_mvt:
                if et_val is not None: a_et.append(et_val)
                if ea_val is not None: a_ea.append(ea_val)
        return q_et, a_et, q_ea, a_ea

    qa_et, aa_et, qa_ea, aa_ea = split_signals(frames_a, mvts_a, holds_a)
    qb_et, ab_et, qb_ea, ab_ea = split_signals(frames_b, mvts_b, holds_b)

    # Plot A Translation
    ax1.hist(qa_et, bins=30, alpha=0.6, color="green", density=True, label="Quiet Hold")
    ax1.hist(aa_et, bins=30, alpha=0.6, color="red", density=True, label="Active Movement")
    ax1.axvline(cand_t_th, color="black", ls="--", label=f"Threshold ({cand_t_th})")
    ax1.set_title("Recording A: Translation Evidence Distribution")
    ax1.set_xlabel("E_T (L_ref / s)")
    ax1.legend(loc="upper right")
    ax1.grid(True, alpha=0.3)

    # Plot A Angular
    ax2.hist(qa_ea, bins=30, alpha=0.6, color="green", density=True, label="Quiet Hold")
    ax2.hist(aa_ea, bins=30, alpha=0.6, color="red", density=True, label="Active Movement")
    ax2.axvline(cand_a_th, color="black", ls="--", label=f"Threshold ({cand_a_th}°/s)")
    ax2.set_title("Recording A: Angular Evidence Distribution")
    ax2.set_xlabel("E_A (deg / s)")
    ax2.legend(loc="upper right")
    ax2.grid(True, alpha=0.3)

    # Plot B Translation
    ax3.hist(qb_et, bins=30, alpha=0.6, color="green", density=True, label="Quiet Hold")
    ax3.hist(ab_et, bins=30, alpha=0.6, color="red", density=True, label="Active Movement")
    ax3.axvline(cand_t_th, color="black", ls="--", label=f"Threshold ({cand_t_th})")
    ax3.set_title("Recording B: Translation Evidence Distribution")
    ax3.set_xlabel("E_T (L_ref / s)")
    ax3.legend(loc="upper right")
    ax3.grid(True, alpha=0.3)

    # Plot B Angular
    ax4.hist(qb_ea, bins=30, alpha=0.6, color="green", density=True, label="Quiet Hold")
    ax4.hist(ab_ea, bins=30, alpha=0.6, color="red", density=True, label="Active Movement")
    ax4.axvline(cand_a_th, color="black", ls="--", label=f"Threshold ({cand_a_th}°/s)")
    ax4.set_title("Recording B: Angular Evidence Distribution")
    ax4.set_xlabel("E_A (deg / s)")
    ax4.legend(loc="upper right")
    ax4.grid(True, alpha=0.3)

    fig.tight_layout()
    dist_plot_path = DEST / "quiet-vs-active-distributions.png"
    fig.savefig(dist_plot_path, dpi=140)
    plt.close(fig)
    print(f"Saved distributions plot: {dist_plot_path}")


def main():
    print("Loading traces...")
    frames_a = load_trace(TRACE_A_PATH)
    frames_b = load_trace(TRACE_B_PATH)

    mvts_a, holds_a = get_intervals("a")
    mvts_b, holds_b = get_intervals("b")

    print(f"Recording A: {len(frames_a)} frames, {len(mvts_a)} reviewed movements, {len(holds_a)} holds")
    print(f"Recording B: {len(frames_b)} frames, {len(mvts_b)} reviewed movements, {len(holds_b)} holds")

    # 1. Separate quiet and active frame signals
    def extract_channel_series(frames, intervals):
        et_list, ea_list = [], []
        for f in frames:
            ts = f["timestamp_ms"]
            if any(a <= ts < b for a, b in intervals):
                top2 = f.get("top2", {})
                et = top2.get("translation_evidence")
                ea = top2.get("angular_evidence")
                if et is not None: et_list.append(et)
                if ea is not None: ea_list.append(ea)
        return et_list, ea_list

    quiet_et_a, quiet_ea_a = extract_channel_series(frames_a, holds_a)
    active_et_a, active_ea_a = extract_channel_series(frames_a, mvts_a)

    quiet_et_b, quiet_ea_b = extract_channel_series(frames_b, holds_b)
    active_et_b, active_ea_b = extract_channel_series(frames_b, mvts_b)

    dist_report = {
        "recording_a": {
            "quiet_translation_et": compute_distribution_stats(quiet_et_a),
            "quiet_angular_ea": compute_distribution_stats(quiet_ea_a),
            "active_translation_et": compute_distribution_stats(active_et_a),
            "active_angular_ea": compute_distribution_stats(active_ea_a),
        },
        "recording_b": {
            "quiet_translation_et": compute_distribution_stats(quiet_et_b),
            "quiet_angular_ea": compute_distribution_stats(quiet_ea_b),
            "active_translation_et": compute_distribution_stats(active_et_b),
            "active_angular_ea": compute_distribution_stats(active_ea_b),
        },
    }

    print("\n--- Quiet vs Active Distribution Summary ---")
    print("Recording A (Quiet Holds):")
    print(f"  ET: median = {dist_report['recording_a']['quiet_translation_et']['median']:.4f}, p90 = {dist_report['recording_a']['quiet_translation_et']['p90']:.4f}, p95 = {dist_report['recording_a']['quiet_translation_et']['p95']:.4f}, p99 = {dist_report['recording_a']['quiet_translation_et']['p99']:.4f}, max = {dist_report['recording_a']['quiet_translation_et']['max']:.4f} L_ref/s")
    print(f"  EA: median = {dist_report['recording_a']['quiet_angular_ea']['median']:.2f}, p90 = {dist_report['recording_a']['quiet_angular_ea']['p90']:.2f}, p95 = {dist_report['recording_a']['quiet_angular_ea']['p95']:.2f}, p99 = {dist_report['recording_a']['quiet_angular_ea']['p99']:.2f}, max = {dist_report['recording_a']['quiet_angular_ea']['max']:.2f} deg/s")
    print("Recording A (Active Movements):")
    print(f"  ET: median = {dist_report['recording_a']['active_translation_et']['median']:.4f}, p90 = {dist_report['recording_a']['active_translation_et']['p90']:.4f}, max = {dist_report['recording_a']['active_translation_et']['max']:.4f} L_ref/s")
    print(f"  EA: median = {dist_report['recording_a']['active_angular_ea']['median']:.2f}, p90 = {dist_report['recording_a']['active_angular_ea']['p90']:.2f}, max = {dist_report['recording_a']['active_angular_ea']['max']:.2f} deg/s")

    print("\nRecording B (Quiet Holds):")
    print(f"  ET: median = {dist_report['recording_b']['quiet_translation_et']['median']:.4f}, p90 = {dist_report['recording_b']['quiet_translation_et']['p90']:.4f}, p95 = {dist_report['recording_b']['quiet_translation_et']['p95']:.4f}, p99 = {dist_report['recording_b']['quiet_translation_et']['p99']:.4f}, max = {dist_report['recording_b']['quiet_translation_et']['max']:.4f} L_ref/s")
    print(f"  EA: median = {dist_report['recording_b']['quiet_angular_ea']['median']:.2f}, p90 = {dist_report['recording_b']['quiet_angular_ea']['p90']:.2f}, p95 = {dist_report['recording_b']['quiet_angular_ea']['p95']:.2f}, p99 = {dist_report['recording_b']['quiet_angular_ea']['p99']:.2f}, max = {dist_report['recording_b']['quiet_angular_ea']['max']:.2f} deg/s")
    print("Recording B (Active Movements):")
    print(f"  ET: median = {dist_report['recording_b']['active_translation_et']['median']:.4f}, p90 = {dist_report['recording_b']['active_translation_et']['p90']:.4f}, max = {dist_report['recording_b']['active_translation_et']['max']:.4f} L_ref/s")
    print(f"  EA: median = {dist_report['recording_b']['active_angular_ea']['median']:.2f}, p90 = {dist_report['recording_b']['active_angular_ea']['p90']:.2f}, max = {dist_report['recording_b']['active_angular_ea']['max']:.2f} deg/s")

    # 2. Movement onset dynamics with a baseline candidate threshold
    # Candidate thresholds: ET = 0.35 L_ref/s, EA = 25 deg/s
    cand_t = 0.35
    cand_a = 25.0
    onsets_a = analyze_onsets(frames_a, mvts_a, cand_t, cand_a)
    onsets_b = analyze_onsets(frames_b, mvts_b, cand_t, cand_a)

    print(f"\n--- Movement Onset Dynamics (Thresholds: ET={cand_t} L_ref/s, EA={cand_a}°/s) ---")
    print("Recording A Punches:")
    for o in onsets_a:
        print(f"  Punch {o['movement_number']}: first cross = {o['first_crossing_ms']} ms (latency={o['first_crossing_latency_ms']} ms), lead={o['leading_family']} (T_lat={o['translation_latency_ms']} ms, A_lat={o['angular_latency_ms']} ms)")

    print("\nRecording B Movements:")
    for o in onsets_b:
        print(f"  Movement {o['movement_number']}: first cross = {o['first_crossing_ms']} ms (latency={o['first_crossing_latency_ms']} ms), lead={o['leading_family']} (T_lat={o['translation_latency_ms']} ms, A_lat={o['angular_latency_ms']} ms)")

    # 3. Threshold Grid Evaluation
    print("\n--- Evaluating Fixed Threshold Grid across Recordings A and B ---")
    grid_results = evaluate_threshold_grid(frames_a, holds_a, mvts_a, frames_b, holds_b, mvts_b)

    # Sort grid by hold_pos_pct and latency
    print(f"{'T_th':>6} {'A_th':>6} | {'A_Hold%':>8} {'A_MaxBurst':>11} {'A_Detect':>9} {'A_Lat':>6} | {'B_Hold%':>8} {'B_MaxBurst':>11} {'B_Detect':>9} {'B_Lat':>6}")
    print("-" * 88)
    for r in grid_results:
        print(f"{r['t_threshold']:6.2f} {r['a_threshold']:6.1f} | {r['rec_a_hold_pos_pct']:7.2f}% {r['rec_a_max_burst_ms']:9d}ms {r['rec_a_detected_movements']:>9} {r['rec_a_mean_latency_ms']:5.1f}m | {r['rec_b_hold_pos_pct']:7.2f}% {r['rec_b_max_burst_ms']:9d}ms {r['rec_b_detected_movements']:>9} {r['rec_b_mean_latency_ms']:5.1f}m")

    # Generate Plots
    generate_plots(frames_a, mvts_a, holds_a, frames_b, mvts_b, holds_b, cand_t, cand_a)

    # Save complete JSON analysis
    full_analysis = {
        "distributions": dist_report,
        "onsets_recording_a": onsets_a,
        "onsets_recording_b": onsets_b,
        "grid_results": grid_results,
    }
    with open(DEST / "causal-threshold-evaluation-summary.json", "w", encoding="utf-8") as f:
        json.dump(full_analysis, f, indent=2)
    print(f"\nSaved complete analysis summary to {DEST / 'causal-threshold-evaluation-summary.json'}")


if __name__ == "__main__":
    main()
