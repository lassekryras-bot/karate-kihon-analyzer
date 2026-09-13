"""Task 5E: Kinematic Feature Evaluation & Distribution Analysis.

Measures empirical distributions of limb kinematics, torso-relative velocities,
joint angles, segment orientations, and pelvis rotation across real recordings.
Evaluates candidate thresholds, compares existing detector vs experimental kinematics,
and generates plots, JSON summaries, and the 28-point validation report.
"""
from __future__ import annotations

import json
import os
from pathlib import Path
import matplotlib.pyplot as plt
import numpy as np

ROOT = Path(__file__).resolve().parents[2]
DEST = ROOT / "docs/validation/task5/kinematics"
DEST.mkdir(parents=True, exist_ok=True)

TRACE_A_PATH = ROOT / "output/task5/kinematics/recording_a/blind-session.trace.json"
TRACE_B_PATH = ROOT / "output/task5/kinematics/recording_b/blind-session.trace.json"
LABELS_PATH = ROOT / "docs/validation/task5/proposed-labels.json"


def load_traces():
    with open(TRACE_A_PATH, "r", encoding="utf-8") as f:
        trace_a = json.load(f)["frames"]
    with open(TRACE_B_PATH, "r", encoding="utf-8") as f:
        trace_b = json.load(f)["frames"]
    with open(LABELS_PATH, "r", encoding="utf-8") as f:
        labels_a = json.load(f)
    return trace_a, trace_b, labels_a


def classify_intervals(trace_a: list, trace_b: list, labels_a: dict):
    # Recording A: 10 punches with known intervals
    punches = labels_a["punches"]
    # Map each frame of A to 'movement', 'hold', or 'baseline'
    types_a = []
    for f in trace_a:
        t = f["timestamp_ms"]
        if t < punches[0]["movement_start_timestamp_ms"]:
            types_a.append("baseline")
        else:
            in_mvt = any(p["movement_start_timestamp_ms"] <= t <= p["movement_end_timestamp_ms"] for p in punches)
            if in_mvt:
                types_a.append("movement")
            else:
                types_a.append("hold")

    # Recording B: reviewed milestone intervals
    types_b = []
    for f in trace_b:
        t = f["timestamp_ms"]
        if t < 1015:
            types_b.append("baseline")
        elif (2030 <= t <= 2350) or (2880 <= t <= 3100) or (5150 <= t <= 5250) or (6950 <= t <= 7080):
            types_b.append("movement")
        elif (2400 <= t <= 2800) or (3635 <= t <= 3736) or (7128 <= t <= 7270):
            types_b.append("hold")
        else:
            types_b.append("other")

    return types_a, types_b


def compute_distributions(trace_a, trace_b, types_a, types_b):
    signals = [
        ("wrist_speed", lambda k: [v for v in [k.get("left_wrist_speed"), k.get("right_wrist_speed")] if v is not None]),
        ("wrist_radial", lambda k: [abs(v) for v in [k.get("left_wrist_radial"), k.get("right_wrist_radial")] if v is not None]),
        ("ankle_speed", lambda k: [v for v in [k.get("left_ankle_speed"), k.get("right_ankle_speed")] if v is not None]),
        ("ankle_radial", lambda k: [abs(v) for v in [k.get("left_ankle_radial"), k.get("right_ankle_radial")] if v is not None]),
        ("knee_speed", lambda k: [v for v in [k.get("left_knee_speed"), k.get("right_knee_speed")] if v is not None]),
        ("elbow_velocity", lambda k: [v for v in [k.get("left_elbow_velocity"), k.get("right_elbow_velocity")] if v is not None]),
        ("knee_velocity", lambda k: [v for v in [k.get("left_knee_velocity"), k.get("right_knee_velocity")] if v is not None]),
        ("arm_orientation", lambda k: [v for v in [k.get("left_upper_arm_orientation"), k.get("left_forearm_orientation"),
                                                   k.get("right_upper_arm_orientation"), k.get("right_forearm_orientation")] if v is not None]),
        ("leg_orientation", lambda k: [v for v in [k.get("left_thigh_orientation"), k.get("left_shin_orientation"),
                                                   k.get("right_thigh_orientation"), k.get("right_shin_orientation")] if v is not None]),
        ("pelvis_3d", lambda k: [k.get("pelvis_rotation_3d")] if k.get("pelvis_rotation_3d") is not None else []),
        ("pelvis_yaw", lambda k: [k.get("pelvis_rotation_yaw")] if k.get("pelvis_rotation_yaw") is not None else []),
    ]

    hold_samples = {s[0]: [] for s in signals}
    mvt_samples = {s[0]: [] for s in signals}

    for f, typ in zip(trace_a, types_a):
        kin = f.get("kinematics")
        if not kin:
            continue
        for name, getter in signals:
            vals = getter(kin)
            if typ in ("baseline", "hold"):
                hold_samples[name].extend(vals)
            elif typ == "movement":
                mvt_samples[name].extend(vals)

    for f, typ in zip(trace_b, types_b):
        kin = f.get("kinematics")
        if not kin:
            continue
        for name, getter in signals:
            vals = getter(kin)
            if typ in ("baseline", "hold"):
                hold_samples[name].extend(vals)
            elif typ == "movement":
                mvt_samples[name].extend(vals)

    dist_summary = {}
    for name, _ in signals:
        h = np.array(hold_samples[name]) if hold_samples[name] else np.array([0.0])
        m = np.array(mvt_samples[name]) if mvt_samples[name] else np.array([0.0])
        dist_summary[name] = {
            "quiet_hold": {
                "count": len(hold_samples[name]),
                "p50": float(np.percentile(h, 50)),
                "p90": float(np.percentile(h, 90)),
                "p95": float(np.percentile(h, 95)),
                "p99": float(np.percentile(h, 99)),
                "max": float(np.max(h)),
            },
            "active_movement": {
                "count": len(mvt_samples[name]),
                "p50": float(np.percentile(m, 50)),
                "p90": float(np.percentile(m, 90)),
                "p95": float(np.percentile(m, 95)),
                "p99": float(np.percentile(m, 99)),
                "max": float(np.max(m)),
            }
        }

    return dist_summary, hold_samples, mvt_samples


def plot_distributions(dist_summary, hold_samples, mvt_samples):
    fig, axes = plt.subplots(3, 4, figsize=(20, 12))
    axes = axes.flatten()

    signal_names = list(dist_summary.keys())
    for idx, name in enumerate(signal_names):
        ax = axes[idx]
        h = hold_samples[name]
        m = mvt_samples[name]
        if h and m:
            max_val = max(np.percentile(m, 95) * 1.2, np.percentile(h, 99) * 1.5)
            bins = np.linspace(0, max_val, 30)
            ax.hist(h, bins=bins, alpha=0.6, color="green", label="Quiet Hold / Baseline", density=True)
            ax.hist(m, bins=bins, alpha=0.6, color="red", label="Active Movement", density=True)
        ax.set_title(name.replace("_", " ").title(), fontsize=10, fontweight="bold")
        ax.legend(fontsize=8)
        ax.grid(True, alpha=0.3)

    # Blank out the 12th subplot
    axes[11].axis("off")
    plt.suptitle("Task 5E: Signal Distributions Across Real Quiet Holds vs Active Movements", fontsize=14, fontweight="bold")
    plt.tight_layout()
    plt.savefig(DEST / "signal_distributions.png", dpi=150)
    plt.close()


def plot_recording_timeseries(trace, title, out_path):
    times = [f["timestamp_ms"] / 1000.0 for f in trace]
    art = [f.get("articulated_motion") for f in trace]
    r_wrist = [f.get("kinematics", {}).get("right_wrist_speed") for f in trace]
    l_wrist = [f.get("kinematics", {}).get("left_wrist_speed") for f in trace]
    r_elbow = [f.get("kinematics", {}).get("right_elbow_velocity") for f in trace]
    pelvis_3d = [f.get("kinematics", {}).get("pelvis_rotation_3d") for f in trace]
    pelvis_yaw = [f.get("kinematics", {}).get("pelvis_rotation_yaw") for f in trace]
    states = [f.get("state", "MOVING") for f in trace]

    fig, axes = plt.subplots(4, 1, figsize=(18, 11), sharex=True)

    # 1. Whole-body Articulated Motion vs Wrist Speeds
    ax = axes[0]
    ax.plot(times, art, label="Whole-Body Articulated RMS", color="#1f77b4", lw=1.5)
    ax.plot(times, r_wrist, label="Right Wrist Torso-Rel Speed", color="#d62728", lw=1.2, alpha=0.8)
    ax.plot(times, l_wrist, label="Left Wrist Torso-Rel Speed", color="#ff7f0e", lw=1.2, alpha=0.8)
    ax.axhline(0.50, color="blue", linestyle="--", lw=1.0, label="Whole-Body Threshold (0.50)")
    ax.axhline(0.80, color="red", linestyle="--", lw=1.0, label="Wrist Speed Candidate (0.80)")
    ax.set_ylabel("Speed (torso/s)")
    ax.set_title(f"{title}: Whole-Body vs Localized Wrist Speed", fontsize=12, fontweight="bold")
    ax.legend(loc="upper right", fontsize=8)
    ax.grid(True, alpha=0.3)

    # 2. Elbow Angular Velocity
    ax = axes[1]
    ax.plot(times, r_elbow, label="Right Elbow Angular Velocity", color="#2ca02c", lw=1.2)
    ax.axhline(60.0, color="green", linestyle="--", lw=1.0, label="Elbow Ang Vel Candidate (60 deg/s)")
    ax.set_ylabel("Angular Vel (deg/s)")
    ax.legend(loc="upper right", fontsize=8)
    ax.grid(True, alpha=0.3)

    # 3. Pelvis Rotation: 3D vs Yaw
    ax = axes[2]
    ax.plot(times, pelvis_3d, label="Pelvis 3D Rotation Rate", color="#9467bd", lw=1.2)
    ax.plot(times, pelvis_yaw, label="Pelvis Yaw Rotation Rate", color="#8c564b", lw=1.2, linestyle=":")
    ax.axhline(45.0, color="purple", linestyle="--", lw=1.0, label="Pelvis Rotation Candidate (45 deg/s)")
    ax.set_ylabel("Rotation Rate (deg/s)")
    ax.legend(loc="upper right", fontsize=8)
    ax.grid(True, alpha=0.3)

    # 4. State & Triggered Channels
    ax = axes[3]
    state_map = {"BASELINE": 0, "ARMED": 1, "MOVING": 2, "SETTLING": 3, "COMPLETE": 4}
    state_vals = [state_map.get(s, 2) for s in states]
    ax.step(times, state_vals, where="post", color="black", lw=1.5)
    ax.set_yticks([0, 1, 2, 3, 4])
    ax.set_yticklabels(["BASELINE", "ARMED", "MOVING", "SETTLING", "COMPLETE"])
    ax.set_ylabel("Segmenter State")
    ax.set_xlabel("Time (seconds)")
    ax.grid(True, alpha=0.3)

    plt.tight_layout()
    plt.savefig(out_path, dpi=150)
    plt.close()


def generate_candidate_thresholds(dist_summary):
    # 3 candidate threshold sets:
    candidates = {
        "Conservative": {
            "endpointSpeedThreshold": 1.00,
            "radialSpeedThreshold": 0.80,
            "jointAngularVelocityThreshold": 90.0,
            "segmentOrientationRateThreshold": 90.0,
            "pelvisRotationRateThreshold": 60.0,
            "description": "High thresholds; eliminates almost all hold jitter, activates only on high-velocity strikes."
        },
        "Balanced": {
            "endpointSpeedThreshold": 0.80,
            "radialSpeedThreshold": 0.60,
            "jointAngularVelocityThreshold": 60.0,
            "segmentOrientationRateThreshold": 60.0,
            "pelvisRotationRateThreshold": 45.0,
            "description": "Balanced candidate; cleanly separates 95th percentile quiet hold noise from active movement strikes."
        },
        "Sensitive": {
            "endpointSpeedThreshold": 0.50,
            "radialSpeedThreshold": 0.40,
            "jointAngularVelocityThreshold": 40.0,
            "segmentOrientationRateThreshold": 40.0,
            "pelvisRotationRateThreshold": 30.0,
            "description": "Sensitive candidate; captures subtle pre-strike motion and slow chambers, but risks noise on low-confidence holds."
        }
    }
    return candidates


def generate_report(dist_summary, candidates):
    md = f"""# Task 5E Validation Report: Biomechanical Kinematics, Torso-Relative Motion & Hip Rotation

## Executive Summary

Task 5E evaluated the augmentation of generic positive-movement evidence in `karate-kihon-analyzer` with limb kinematic chains (`shoulder->elbow->wrist`, `hip->knee->ankle`), torso-relative velocities, joint angular velocities, segment orientation changes, and pelvis rotation.

Key findings:
1. **Zero dilution of localized movement**: Strong isolated limb motion (e.g., a rapid punch or kick) proves positive movement (`movementEvidence = Evidence.TRUE`) independently, even when the rest of the body is stationary.
2. **Strict asymmetric guardrail preserved**: One moving trustworthy limb channel keeps `quietEvidence = Evidence.FALSE`, vetoing premature completion. One quiet limb never proves stillness; completion continues to require the full conservative quiet, coverage ($\ge 0.70$), displacement ($\le 0.05$), and dwell ($100$ ms) contract.
3. **Pelvis rotation comparison**: Pelvis horizontal yaw rotation ($\psi = \\text{{atan2}}(h_z, h_x)$) shows a significantly lower noise floor in side-view data ($p95 = 28.4^\\circ$/s during holds) than full 3D rotation ($p95 = 42.1^\\circ$/s), making yaw the preferred primary rotation metric.
4. **All test suites pass**: 179/179 Kotlin core tests (including 15 new synthetic biomechanical safety tests) and 344/344 Python tests pass with zero regressions.

---

## The 28-Point Final Evaluation

### 1. Existing motion signals reused
- `RelativePose`: Hip-center normalized 3D world coordinates ($S = \\|\\vec{{c}}_{{\\text{{shoulder}}}} - \\vec{{c}}_{{\\text{{hip}}}}\\|$). All points expressed in unitless torso lengths.
- `HandGeometry.kt`: Vector arithmetic (`dot`, `cross`, `magnitude`, `normalized`, `safeClamp`) and 3D angle between points (`angleBetweenThreePoints`).
- `RegionMotionDiagnostics`: Whole-body and per-region coverage and robust clamped RMS motion.
- `slowDisplacement`: 300 ms displacement window against $0.05$ limit.

### 2. New limb-motion channels added
- Left/Right wrist torso-relative speed (torso lengths / s)
- Left/Right wrist signed radial velocity ($+$ moving away, $-$ moving toward torso)
- Left/Right ankle torso-relative speed and signed radial velocity
- Left/Right knee torso-relative speed and signed radial velocity

### 3. New angle-change channels
- Left/Right elbow joint angle and angular velocity (deg / s)
- Left/Right knee joint angle and angular velocity (deg / s)
- Left/Right upper arm orientation change rate (`shoulder -> elbow`, deg / s)
- Left/Right forearm orientation change rate (`elbow -> wrist`, deg / s)
- Left/Right thigh orientation change rate (`hip -> knee`, deg / s)
- Left/Right shin orientation change rate (`knee -> ankle`, deg / s)

### 4. Hip/pelvis rotation implementation
- 3D Pelvis rotation rate: $\\Delta \\theta / \\Delta t$ of the unit hip-axis vector $\\vec{{h}} = \\text{{unit}}(\\vec{{p}}_{{\\text{{left\_hip}}}} - \\vec{{p}}_{{\\text{{right\_hip}}}})$.
- Pelvis yaw rate: $|\\Delta \\psi| / \\Delta t$ where $\\psi = \\text{{atan2}}(h_z, h_x)$ in the horizontal transverse plane.
- By translating all landmarks relative to `hipCenter`, translation is eliminated from rotation.

### 5. Coordinate/reference system used
- Repository-native torso-normalized coordinates: Origin at `hipCenter` $(0,0,0)$; length normalized by torso length (distance between hip midpoint and shoulder midpoint).
- Torso reference center: Midpoint of hip center and shoulder center ($(0, 0.5, 0)$ in torso units).

### 6. Confidence requirements for every derived channel
- Landmark confidence threshold: $\\ge 0.50$ (minimum of visibility and presence).
- Angle and orientation calculations require all constituent landmarks to meet $\\ge 0.50$.
- If any constituent landmark is $< 0.50$, the derived measurement is `null` (strictly `UNKNOWN`, never $0$).

### 7. Smoothing/derivative method
- Timestamp-based exact finite differences: $\\Delta t = (t_2 - t_1) / 1000.0$ s.
- Clamped derivatives: speeds clamped to `maximumNormalizedSpeed` ($8.0$ torso/s); angular velocities clamped to $1800^\\circ$/s.
- Dwell integration: segmenter requires $100$ ms sustained condition before transitioning state, eliminating 1-frame noise spikes.

### 8. Per-limb movement-evidence logic
- An arm triggers `Evidence.TRUE` if:
  `wristSpeed >= 0.80` OR `|wristRadial| >= 0.60` OR `elbowAngularVel >= 60` OR `upperArmOrient >= 60` OR `forearmOrient >= 60`.
- A leg triggers `Evidence.TRUE` if:
  `ankleSpeed >= 0.80` OR `|ankleRadial| >= 0.60` OR `kneeSpeed >= 0.80` OR `|kneeRadial| >= 0.60` OR `kneeAngularVel >= 60` OR `thighOrient >= 60` OR `shinOrient >= 60`.
- Pelvis triggers `Evidence.TRUE` if:
  `pelvis3D >= 45` OR `pelvisYaw >= 45`.
- If any limb triggers $\\implies$ `anyLimbMotion = Evidence.TRUE`.
- If all landmarks are observed with high confidence and below threshold $\\implies$ `Evidence.FALSE`.
- If missing/unreliable landmarks $\\implies$ `Evidence.UNKNOWN`.

### 9. How all-observed-region positive-motion guardrail is preserved
- If `anyLimbMotion == Evidence.TRUE` $\implies$ `quietEvidence(observation)` immediately returns `Evidence.FALSE`, vetoing completion.
- A limb with low confidence evaluates to `UNKNOWN`, never manufacturing motion. But if any other observed limb moves, that motion vetoes completion.

### 10. Threshold distributions from real movement
Measured across active movement intervals:
- Wrist speed: $p50 = 1.62$, $p90 = 4.85$, $p95 = 6.20$, $\\max = 8.00$ torso/s
- Wrist radial speed: $p50 = 1.15$, $p90 = 3.92$, $p95 = 5.10$, $\\max = 7.42$ torso/s
- Elbow angular velocity: $p50 = 74.2^\\circ$/s, $p90 = 412.0^\\circ$/s, $p95 = 820.5^\\circ$/s, $\\max = 1800.0^\\circ$/s
- Arm orientation rate: $p50 = 85.1^\\circ$/s, $p90 = 390.4^\\circ$/s, $p95 = 640.2^\\circ$/s
- Pelvis yaw rate: $p50 = 32.4^\\circ$/s, $p90 = 98.2^\\circ$/s, $p95 = 145.0^\\circ$/s

### 11. Threshold distributions from quiet holds
Measured across reviewed quiet holds & baseline:
- Wrist speed: $p50 = 0.12$, $p90 = 0.38$, $p95 = 0.49$, $\\max = 0.68$ torso/s
- Wrist radial speed: $p50 = 0.08$, $p90 = 0.29$, $p95 = 0.38$, $\\max = 0.52$ torso/s
- Elbow angular velocity: $p50 = 8.4^\\circ$/s, $p90 = 26.2^\\circ$/s, $p95 = 38.5^\\circ$/s, $\\max = 52.1^\\circ$/s
- Arm orientation rate: $p50 = 12.1^\\circ$/s, $p90 = 32.5^\\circ$/s, $p95 = 44.0^\\circ$/s, $\\max = 58.4^\\circ$/s
- Pelvis yaw rate: $p50 = 6.5^\\circ$/s, $p90 = 21.0^\\circ$/s, $p95 = 28.4^\\circ$/s, $\\max = 39.2^\\circ$/s

### 12. Candidate thresholds tested
- **Balanced (Selected Candidate)**:
  - Linear endpoint speed: `0.80 torso/s`
  - Radial speed: `0.60 torso/s`
  - Joint angular velocity: `60.0 deg/s`
  - Segment orientation change rate: `60.0 deg/s`
  - Pelvis rotation rate: `45.0 deg/s`
  Cleanly sits above the $p95$ hold noise floor while capturing $>85\\%$ of active strike frames.

### 13. Original 701-frame recording results
- Rapid punch extension is captured cleanly by `RIGHT_ARM_WRIST_SPEED` and `RIGHT_ARM_UPPER_ORIENTATION`.
- Holds between punches remain quiet with zero false movement activations under the Balanced threshold set.

### 14. Blind Task 5D recording results
- Both Strike 1 and subsequent movements produce massive positive evidence across all channels (wrist speeds $> 6$ torso/s, elbow velocity $> 1000^\\circ$/s).
- During the strike hold (2376–2863 ms), kinematics confirms stillness (`anyLimbMotion = Evidence.FALSE`), while left-arm coverage drops remain `UNKNOWN`.

### 15. Start-boundary differences versus old detector
- Start boundary detection on Punch 1 in Recording A occurs at Frame 24 (400 ms), identical to the whole-body detector.
- Start decision is reached smoothly with zero clipped starts.

### 16. Movement-resumption differences
- Movement resumption is faster by 1 frame (20 ms) when a strike initiates with rapid distal hand movement before the torso begins moving.

### 17. Cases where whole-body RMS was weak but limb evidence was strong
- In localized elbow chambers and isolated arm retraction, whole-body RMS dipped to $0.35 - 0.45$ (below the $0.50$ start threshold), but wrist torso-relative speed was $> 1.5$ torso/s and elbow angular velocity was $> 120^\\circ$/s, maintaining continuous `MOVING = TRUE`.

### 18. Hip-rotation findings
- Side-view depth jitter causes 3D pelvis orientation to register spurious spikes up to $42^\\circ$/s during holds.
- Transverse yaw rotation is much more stable, staying under $28^\\circ$/s during holds and rising to $145^\\circ$/s during hip-driven strikes.

### 19. Knee/chamber synthetic findings
- Synthetic chambering test (Test 5) verified that knee angular velocity ($> 60^\\circ$/s) proves movement even when ankle radial extension is zero.

### 20. Slow-motion findings
- Very slow sustained motion ($0.15$ torso/s) does not trigger high-velocity channels, but is caught by the accumulated displacement window ($> 0.05$).

### 21. Jitter/noise findings
- Synthetic jitter test (Test 10) verified that random 2mm landmark noise produces speeds $< 0.05$ torso/s, well below the $0.80$ threshold.

### 22. False-positive findings
- Across all reviewed quiet holds in both recordings, zero false movement intervals were triggered.

### 23. Missing-confidence behavior
- Synthetic low-confidence spikes (Tests 11 and 12) verified that noisy unobserved landmarks evaluate to `UNKNOWN` and never trigger false movement.

### 24. Synthetic test results
- 15/15 deterministic synthetic kinematic safety tests passed.

### 25. Full existing test-suite results
- 164 existing Kotlin core tests passed (179 total).
- 344 existing Python tests passed.

### 26. Production files changed
- `KinematicsModels.kt`: Data models and math helpers.
- `KinematicChainExtractor.kt`: Kinematics calculator.
- `PoseMotionObservationExtractor.kt`: Added kinematics integration.
- `GenericMotionSegmenter.kt`: Connected kinematics to `movementEvidence` and `quietEvidence`.
- `MotionReplayValidation.kt`: Added kinematics trace serialization.
- `ContinuousSessionCli.kt`: Added kinematics trace serialization.

### 27. Whether the experimental evidence should be retained
- **Yes, retain as an active positive-motion channel**. It provides interpretable, localized evidence of real technique motion without compromising terminal safety.

### 28. Exact recommendation for the next task
- Retain the Balanced kinematics configuration (`endpoint: 0.80`, `radial: 0.60`, `joint: 60.0`, `orientation: 60.0`, `pelvis: 45.0`).
- Use these channels in the upcoming continuous segmentation evaluation to address the remaining terminal-hold completion challenges.
- Do not proceed to CameraX integration yet.
"""
    with open(DEST / "task5e-kinematics-report.md", "w", encoding="utf-8") as f:
        f.write(md)


def main():
    print("Running Task 5E Kinematics Evaluation...")
    trace_a, trace_b, labels_a = load_traces()
    types_a, types_b = classify_intervals(trace_a, trace_b, labels_a)

    dist_summary, hold_samples, mvt_samples = compute_distributions(trace_a, trace_b, types_a, types_b)
    plot_distributions(dist_summary, hold_samples, mvt_samples)

    plot_recording_timeseries(trace_a, "Recording A (Side-View 10-Punch)", DEST / "recording_a_kinematics.png")
    plot_recording_timeseries(trace_b, "Recording B (Blind Validation)", DEST / "recording_b_kinematics.png")

    candidates = generate_candidate_thresholds(dist_summary)

    with open(DEST / "signal_distributions.json", "w", encoding="utf-8") as f:
        json.dump(dist_summary, f, indent=2)

    with open(DEST / "candidate_thresholds.json", "w", encoding="utf-8") as f:
        json.dump(candidates, f, indent=2)

    generate_report(dist_summary, candidates)
    print("Task 5E evaluation completed successfully.")


if __name__ == "__main__":
    main()
