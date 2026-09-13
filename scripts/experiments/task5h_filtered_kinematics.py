"""Replay the Kotlin Task 5H candidate and matched kinematics-off control; labels are evaluation only.

Requires the existing A/B pose fixtures and JAVA_HOME pointing to JDK 17.
Run from any directory. --reuse uses already-generated Kotlin traces for report iteration.
"""
from __future__ import annotations

import argparse
from collections import Counter
import hashlib
import json
import os
from pathlib import Path
import re
import subprocess
import xml.etree.ElementTree as ET

import matplotlib
matplotlib.use("Agg")
import matplotlib.pyplot as plt
import numpy as np

ROOT = Path(__file__).resolve().parents[2]
DEST = ROOT / "docs/validation/task5/filtered-kinematics"
WORK = ROOT / "output/task5/filtered-kinematics/verified"
FIXTURES = {"a": ROOT / "output/task5/real-kihon-10-punch.fixture.json",
            "b": ROOT / "output/task5/blind/blind-session.fixture.json"}


def read(path):
    return json.loads(path.read_text(encoding="utf-8"))


def write(path, data):
    path.write_text(json.dumps(data, indent=2, allow_nan=False) + "\n", encoding="utf-8")


def intervals(recording):
    if recording == "a":
        labels = read(ROOT / "docs/validation/task5/proposed-labels.json")["punches"]
        return [(p["movement_start_timestamp_ms"], p["movement_end_timestamp_ms"]) for p in labels], [
            (p["terminal_stable_start_timestamp_ms"], p["terminal_stable_end_timestamp_ms"]) for p in labels]
    # Existing Task 5G review intervals, provisional rather than independently adjudicated truth.
    return ([(2030, 2680), (3800, 7128), (7300, 8468), (8850, 11230), (11555, 12652), (13769, 15048)],
            [(0, 2030), (2680, 2860), (3635, 3800), (7128, 7300), (8468, 8850), (11230, 11555), (12652, 13769)])


def distribution(values):
    v = [v for v in values if v is not None]
    return {"count": len(v), **({k: float(np.percentile(v, p)) for k, p in [("p50", 50), ("p95", 95), ("p99", 99), ("max", 100)]} if v else {})}


def summarize(result, trace, recording):
    frames = trace["frames"]
    movements, holds = intervals(recording)
    hold = [any(a <= f["timestamp_ms"] < b for a, b in holds) for f in frames]
    active = [f.get("kinematics", {}).get("any_limb_motion") == "TRUE" for f in frames]
    bursts, start = [], None
    for i, f in enumerate(frames):
        if hold[i] and active[i]:
            if start is None:
                start = f["timestamp_ms"]
        elif start is not None:
            bursts.append(f["timestamp_ms"] - start)
            start = None
    if start is not None:
        bursts.append(frames[-1]["timestamp_ms"] - start)
    associations = []
    for number, (a, b) in enumerate(movements, 1):
        matches = [s for s in result["segments"] if s["start_boundary_timestamp_ms"] < b and
                   (s["terminal_boundary_timestamp_ms"] or frames[-1]["timestamp_ms"]) > a]
        associations.append({"review_movement": number, "review_interval_ms": [a, b],
                             "overlapping_segments": [s["movement_number"] for s in matches],
                             "start_error_ms": matches[0]["start_boundary_timestamp_ms"] - a if len(matches) == 1 else None,
                             "end_error_ms": matches[0]["terminal_boundary_timestamp_ms"] - b if len(matches) == 1 and matches[0]["completed"] else None})
    return {**result, "state_frame_counts": dict(Counter(f["state"] for f in frames)),
            "first_armed_frame": next((i for i, f in enumerate(frames) if f["state"] == "ARMED"), None),
            "hold_frame_count": sum(hold), "hold_positive_frames": sum(h and a for h, a in zip(hold, active)),
            "maximum_hold_positive_burst_ms": max(bursts, default=0),
            "kinematic_evidence_counts": dict(Counter(f.get("kinematics", {}).get("any_limb_motion", "UNKNOWN") for f in frames)),
            "kinematic_altered_evidence_or_dwell_frames": sum(f["kinematics_altered_evidence_or_dwell"] for f in frames),
            "quiet_unknown_frames": sum(f["quiet_evidence"] == "UNKNOWN" for f in frames),
            "trigger_counts": dict(Counter(c for f in frames for c in f.get("kinematics", {}).get("triggering_channels", []))),
            "review_associations_not_ground_truth": associations,
            "world_scale": distribution(f["world_scale"] for f in frames),
            "image_scale": distribution(f["image_scale"] for f in frames),
            "mirrored_similarity": distribution(f["mirrored_similarity"] for f in frames)}


def plot(recording, runs):
    fig, axes = plt.subplots(4, 1, figsize=(15, 10), sharex=True)
    frames = runs["h"][1]["frames"]
    t = [f["timestamp_ms"] / 1000 for f in frames]
    axes[0].plot(t, [f["articulated_motion"] for f in frames], label="Whole-body motion")
    axes[0].plot(t, [f["image_space_motion"] for f in frames], label="Image motion", alpha=.65)
    axes[0].axhline(.5, color="black", ls="--", label="Start/quiet threshold")
    axes[0].set_ylabel("Motion / s")
    ratios = [max((c["activity"] / c["threshold"] for c in f["channels"] if c["activity"] is not None and c["used_in_gating"]), default=np.nan) for f in frames]
    axes[1].plot(t, ratios, color="purple", label="Maximum kinematic activity / threshold")
    axes[1].axhline(1, color="black", ls="--")
    axes[1].set_ylabel("Threshold ratio")
    axes[2].plot(t, [f["slow_displacement"] for f in frames], label="300 ms displacement")
    axes[2].axhline(.05, color="black", ls="--")
    axes[2].plot(t, [min(f["regions"][r]["coverage"] for r in ("TORSO", "LEFT_ARM", "RIGHT_ARM")) for f in frames], label="Minimum required coverage", alpha=.7)
    axes[2].axhline(.7, color="grey", ls=":")
    states = {s: i for i, s in enumerate(["BASELINE", "ARMED", "MOVING", "SETTLING", "COMPLETE", "FAILED"])}
    for name, (result, trace) in runs.items():
        axes[3].step(t, [states[f["state"]] + (-.06 if name == "f" else .06) for f in trace["frames"]], where="post", label=f"{'F control' if name == 'f' else 'H candidate'}: {result['detected_movement_count']} complete")
    axes[3].set_yticks(list(states.values()), list(states))
    axes[3].set_xlabel("Recording time (seconds)")
    for a, b in intervals(recording)[1]:
        for ax in axes:
            ax.axvspan(a / 1000, b / 1000, alpha=.10, color="green")
    for ax in axes:
        ax.grid(alpha=.2)
        ax.legend(loc="upper right", fontsize=8)
    fig.suptitle(f"Recording {recording.upper()}: matched control vs Task 5H — shaded holds are provisional review intervals")
    fig.tight_layout()
    fig.savefig(DEST / f"recording-{recording}-comparison.png", dpi=140)
    plt.close(fig)


def report(comparison):
    results = ROOT / "android/KarateClipRecorder/karate-analyzer-core/build/test-results/test"
    suites = [ET.parse(p).getroot() for p in results.glob("TEST-*.xml")]
    validation = {k: sum(int(s.get(k, 0)) for s in suites) for k in ("tests", "failures", "errors", "skipped")}
    output = "\n".join(s.findtext("system-out", "") for s in suites)
    latency = [{"speed_torso_per_s": float(speed), "fps": int(fps), "threshold_ms": int(delay), "boundary_error_ms": int(error)}
               for speed, fps, delay, error in re.findall(r"TASK5H_LATENCY speed=([\d.]+) fps=(\d+) threshold_ms=(\d+) boundary_error_ms=(-?\d+)", output)]
    validation["latency"] = latency
    validation["threshold_latency_target_met"] = len(latency) == 6 and all(r["threshold_ms"] < 80 for r in latency)
    validation["boundary_target_met_in_synthetic_cases"] = len(latency) == 6 and all(abs(r["boundary_error_ms"]) <= 25 for r in latency)
    validation["production_acceptance"] = "REJECTED"
    validation["rejection_reasons"] = ["A loses both control completions", "B never establishes readiness", "Slow translation exceeds the <80 ms crossing target"]
    comparison["validation"] = validation
    a, b = (comparison["recordings"][r] for r in "ab")
    rows = ["| Recording | Control completions | H completions | H hold-positive frames | Longest H hold-positive burst |", "|---|---:|---:|---:|---:|"]
    for label, r in [("A", a), ("B", b)]:
        h = r["h"]
        rows.append(f"| {label} | {r['f']['detected_movement_count']} | {h['detected_movement_count']} | {h['hold_positive_frames']}/{h['hold_frame_count']} | {h['maximum_hold_positive_burst_ms']} ms |")
    timing = ["| Speed (torso/s) | FPS | Threshold crossing delay | Boundary error |", "|---:|---:|---:|---:|"]
    timing += [f"| {r['speed_torso_per_s']} | {r['fps']} | {r['threshold_ms']} ms | {r['boundary_error_ms']} ms |" for r in latency]
    topics = [
        ("Outcome and scope", "**Reject default activation of this candidate.** The core decision path and offline diagnostics are implemented, but the requested production acceptance is not achieved. GenericMotionSegmenter defaults kinematic gating off; explicit H replays enable it. No CameraX, Android UI, deployment, or threshold search was performed."),
        ("Configuration provenance", "Task 5G's JSON evaluates a 0.35 torso/s wrist activity threshold; its report recommends the same. Task 5H uses the user's corrected 0.35 endpoint, 0.25 radial, 35 degree/s joint/orientation, and 18 degree/s yaw thresholds. The 5G evaluated angular activity threshold was 30 degree/s. These are candidate values, not independently validated universal thresholds."),
        ("Filter identity", "The resumed implementation uses endpoint-pair averages divided by their **midpoint timestamp separation**, multiplied by directional coherence. This resembles Task 5G's sixth hybrid method, not its recommended fifth method (confidence-weighted regression slope × coherence). Its denominator also differs from dividing by the complete window span. The 5G winning-method claims therefore do not establish acceptance of this implementation. A faithful Method 5 port remains a separate unresolved implementation item; this report does not silently equate the two."),
        ("Causal window", "Only samples in [t−100 ms,t] contribute. At least three samples, at least 60 ms span, current-sample presence, finite geometry and confidence ≥0.50 are required. Confidence is checked for each channel's actual landmarks. A missing elbow cannot hide trustworthy endpoint translation. Scalar, unit-vector orientation, circular yaw, and hip-centered torso-normalized endpoint/radial channels are supported."),
        ("Strict ternary evidence", "Any evaluable production channel at or above its threshold yields TRUE. FALSE requires every production channel in that aggregate to be evaluable and below threshold; partial absence or degenerate geometry otherwise yields UNKNOWN. Pelvis 3D is diagnostic-only and excluded from that aggregate by default. Regression tests cover missing elbows, degenerate limbs, invalid confidence, and stale windows."),
        ("Positive-only integration", "Kinematic TRUE can start movement and veto baseline/terminal stillness. FALSE and UNKNOWN never prove quiet: whole-body motion, displacement, coverage and the configured pose relationship remain authoritative. Required coverage regions do not mask movement in a visible optional limb."),
        ("Baseline readiness and short bursts", "Arming again requires a confirmed quiet baseline. The interrupted implementation bypassed the kinematic veto during baseline; that bypass was removed. Short bursts can fail to satisfy movement dwell yet still interrupt a quiet dwell. The prior claim that all such noise is 'absorbed' by 100 ms dwell is incorrect."),
        ("Causality and measured latency", "Prefix causality tests pass. Synthetic tests isolate kinematics by keeping whole-body channels below threshold. Crossing latency excludes the 100 ms movement dwell. The slow case fails the requested <80 ms gate; fast translation passes. No W/2 assumption is used.\n\n" + "\n".join(timing)),
        ("Boundary recovery", "After a filtered channel is confirmed, a contiguous trustworthy raw precursor within the causal window can select the earlier start. The prior sample is the left edge of a frame-to-frame raw-motion interval. Selection cannot cross arm/rearm. All six controlled onset cases recover within ±25 ms; this is not proof of real-video boundary accuracy."),
        ("Slow and plateau behavior", "A geometrically verified 140-degree locked elbow translates at 0.40 torso/s and triggers via translation without joint rotation. Both travel directions for wrists, knees and ankles are tested, plus slow joint and pelvis rotation. Slow near-threshold motion incurs 82–100 ms crossing delay. Physical pauses still need the independent displacement and stable-dwell criteria; a low angular rate alone cannot complete a segment."),
        ("Adversarial coverage", "The suite covers at least 13 adverse/lifecycle cases: static jitter; alternating jitter; isolated high-confidence spike; low-confidence wrist spike; low-confidence knee spike; missing wrist; missing elbow; degenerate limb; NaN confidence; stale sample window; camera translation; seamless rearm; full reset. These are deterministic synthetic checks, not certification against every occlusion, camera zoom, or real noise pattern. Real holds fail despite the synthetic checks."),
        ("Frame-rate behavior", "Tests use rounded timestamp grids at actual 60, 49 and 30 fps. Steady translational rates remain approximately correct, but threshold latency is not invariant. See the timing table; 30 fps slow motion needs the full 100 ms window. Frame-rate equivalence is not claimed."),
        ("Seamless rearm", "Confirmed terminal samples are preserved, so the next frame can have evaluable filtered kinematics without warm-up. Explicit reset clears history. Rearm now records COMPLETE→ARMED and clears prior-segment transition records; otherwise subsequent segments incorrectly reused the first movement's boundaries. This bookkeeping correction is shared by the matched F and H runs."),
        ("Compound movements", "Evidence-level tests show an 80 ms quiet gap does not complete and a 120 ms gap can complete/rearm with distinct later boundaries. The older test labelled 150 ms remains as an additional check. These are evidence-level tests: a physical 120 ms pause need not produce a 120 ms quiet-evidence interval after smoothing and the 300 ms displacement window. That stronger end-to-end claim is not established."),
        ("Recording A provenance and replay", f"All {a['frames']} fixture frames and timestamps are replayed in order. Fixtures are label-free; their SHA256 hashes are in task5h-comparison.json. Existing importer preservation tests remain passing. The complete sequence, not selected punch windows, is fed to Kotlin.\n\n" + "\n".join(rows)),
        ("Causal four-way ablation", "On controlled geometry with whole-body channels below threshold, existing-only, angular-only, translation-only and both yield [false,false,true,true] for locked-joint translation and [false,true,false,true] for isolated angular movement. Ablation filters the channels supplied to the same segmenter; it does not use review labels or future data."),
        ("Recording A boundaries and clipping", "All ten prior movement/terminal proposals remain provisional in ../proposed-labels.json. The JSON associates each proposal with overlapping emitted segments and reports signed start/end error where defined; a single long segment overlapping several punches is not ten detections. H emits one incomplete movement and no terminal completions, so individual H punch boundaries are unavailable. An incomplete EOF segment represents missing completion, not a successful punch window."),
        ("Punch 1 and extra recording content", "The prior review identifies the initial held pose as left-censored, while numbered Punch 1 begins visibly and is not left-censored. No impact frame was used to determine segmentation. Frames outside the ten proposal intervals, including later movements and final arm lowering, remain in the replay. This run does not re-adjudicate the earlier video review."),
        ("Recording B comparison", f"The complete {b['frames']}-frame blind sequence is replayed. The matched F control completes {b['f']['detected_movement_count']} movements, then remains incomplete. H stays BASELINE for all frames and never arms. Review intervals come from Task 5G and are evaluation-only, not independently adjudicated ground truth. The control matches Task 5F's side-neutral configuration, not its weakened right-arm-only coverage ablation."),
        ("Coverage and abstentions", f"Quiet evidence is UNKNOWN on {a['h']['quiet_unknown_frames']} A frames and {b['h']['quiet_unknown_frames']} B frames. Coverage is unchanged between matched runs. Occluded required regions continue to block proof of quiet. H adds false-positive vetoes; weakening coverage or suppressing optional-limb motion to restore counts would conceal the failure."),
        ("Hold noise and premature completion", f"H is positive on {a['h']['hold_positive_frames']}/{a['h']['hold_frame_count']} reviewed A hold frames and {b['h']['hold_positive_frames']}/{b['h']['hold_frame_count']} B hold frames. The longest sample-supported runs are {a['h']['maximum_hold_positive_burst_ms']} and {b['h']['maximum_hold_positive_burst_ms']} ms. H has no premature completed segments because it has no completed segments; that does not make it successful. Hold review intervals are provisional and rates include their boundary ambiguity."),
        ("Camera-scale behavior", "World torso scale and image scale distributions are exported in the comparison; per-frame scales and scale changes are in the trigger trace. The synthetic camera-translation check passes. Real data do not isolate camera zoom from anatomical/tracker changes, so scale invariance cannot be inferred. Hip-centered normalized translation can still reflect scale-estimation noise."),
        ("Mirrored similarity", "Same and mirrored similarities remain available in the trace. ANY_STABLE_POSE_AFTER_MOVEMENT accepts a stable pose regardless of those similarities. No mirrored-similarity threshold was tuned and no mirrored-pose requirement is used to force the ten-punch count."),
        ("Decision diagnostics", "Each channel includes raw rate, smoothed rate, coherence, activity, threshold, confidence, evidence and whether it participates in gating. Activity = abs(smoothed rate) × coherence, so smoothed rate alone must not be compared to the gate. The altered-evidence-or-dwell flag includes withheld readiness/settling; it is not a count of extra emitted state transitions."),
        ("Corrections from the interrupted session", "Restored baseline prerequisite/veto; removed required-region masking of positive optional-limb evidence; corrected unavailable-channel ternary aggregation and pelvis 3D threshold; honored configured history size; validated smoother timestamps, finite inputs and current-sample presence; repaired rearm transition lifetime; added raw-supported start selection and auditable channel diagnostics. The two tests previously arming before extraction established a full quiet dwell now seed a preceding static sample."),
        ("Validation evidence", f"Core JDK 17 result: {validation['tests']} tests, {validation['failures']} failures, {validation['errors']} errors, {validation['skipped']} skipped. Python suite: 344 passed in the local run. The slow-latency characterization asserts eventual crossing within the window; the stricter requested <80 ms release gate is separately recorded as FAILED, not redefined as passing. No native app/device build is claimed."),
        ("Reproduction", "Set JAVA_HOME to JDK 17; run `./gradlew.bat :karate-analyzer-core:test --rerun-tasks` from android/KarateClipRecorder, then `python scripts/experiments/task5h_filtered_kinematics.py` from the repository root. The script requires the existing A/B pose fixtures and asserts frame counts, label absence, timestamp order and replay preservation. `--reuse` regenerates artifacts from saved traces. Gradle jobs must run sequentially in this shared checkout; an initial concurrent attempt collided on Kotlin's build cache and was rerun sequentially."),
        ("Acceptance and remaining work", "**Production activation remains rejected.** Required follow-up is a faithful evaluation of the nominated Task 5G Method 5, resolution of hold-noise/readiness and slow-latency failures, and physical-gap end-to-end acceptance. No threshold was adjusted to manufacture a pass. The original numbered 28-question checklist was not present in the supplied plan; these 28 topics cover the available plan and corrections without claiming exact unseen question wording."),
    ]
    text = "# Task 5H — filtered kinematics validation\n\nLocal validation of the resumed candidate. Production acceptance: **REJECTED**.\n\n"
    text += "\n\n".join(f"## {i}. {title}\n\n{body}" for i, (title, body) in enumerate(topics, 1))
    text += "\n\n![Recording A comparison](recording-a-comparison.png)\n\n![Recording B comparison](recording-b-comparison.png)\n"
    (DEST / "task5h-filtered-kinematics-report.md").write_text(text, encoding="utf-8")


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--reuse", action="store_true")
    args = parser.parse_args()
    DEST.mkdir(parents=True, exist_ok=True)
    WORK.mkdir(parents=True, exist_ok=True)
    comparison = {"schema_version": "task5h-comparison-v1", "review_labels_are_ground_truth": False,
                  "parameters_changed_during_validation": False,
                  "control": "Matched Task 5F configuration, kinematics disabled; corrected shared rearm bookkeeping",
                  "candidate": "100 ms causal endpoint-pair rate multiplied by directional coherence; corrected midpoint time denominator",
                  "thresholds": {"endpoint_torso_per_s": .35, "radial_torso_per_s": .25, "joint_deg_per_s": 35, "orientation_deg_per_s": 35, "pelvis_yaw_deg_per_s": 18, "pelvis_3d_gating": False},
                  "required_regions": ["TORSO", "LEFT_ARM", "RIGHT_ARM"], "recordings": {}}
    triggers = {}
    for recording, fixture in FIXTURES.items():
        data = read(fixture)
        assert len(data["frames"]) == {"a": 701, "b": 763}[recording]
        assert data.get("labels") is None, "Detector fixtures must be label-free"
        timestamps = [f["timestamp_ms"] for f in data["frames"]]
        assert all(b > a for a, b in zip(timestamps, timestamps[1:]))
        runs, summaries = {}, {}
        for mode in ("f", "h"):
            target = WORK / f"recording-{recording}-{mode}"
            target.mkdir(exist_ok=True)
            if not args.reuse:
                cmd = [str(ROOT / "android/KarateClipRecorder/gradlew.bat"), ":karate-analyzer-core:continuousMotion", "--console=plain",
                       f"-PreplayInput={fixture}", f"-PreplayOutput={target}", "-PrequiredRegions=TORSO,LEFT_ARM,RIGHT_ARM",
                       "-PendPoseRelationship=ANY_STABLE_POSE_AFTER_MOVEMENT", f"-PenableKinematics={str(mode == 'h').lower()}"]
                with (target / "gradle.log").open("w", encoding="utf-8") as log:
                    subprocess.run(cmd, cwd=ROOT / "android/KarateClipRecorder", stdout=log, stderr=subprocess.STDOUT, check=True)
            result, trace = read(target / "blind-session-result.json"), read(target / "blind-session.trace.json")
            assert [f["timestamp_ms"] for f in trace["frames"]] == timestamps
            runs[mode] = result, trace
            summaries[mode] = summarize(result, trace, recording)
            print(f"{recording.upper()} {mode.upper()}: {result['detected_movement_count']} complete; states {summaries[mode]['state_frame_counts']}", flush=True)
        comparison["recordings"][recording] = {"fixture_sha256": hashlib.sha256(fixture.read_bytes()).hexdigest(), "frames": len(timestamps), **summaries}
        triggers[recording] = [{"frame": i, **f} for i, f in enumerate(runs["h"][1]["frames"])]
        plot(recording, runs)
    report(comparison)
    write(DEST / "task5h-comparison.json", comparison)
    write(DEST / "kinematic-trigger-trace.json", triggers)


if __name__ == "__main__":
    main()
