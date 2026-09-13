"""Diagnostic Plot: Top-2 Channel Breakdown during Troublesome Holds in Recording A.

Generates a clear diagnostic visualization showing which kinematic channels dominate
ET and EA during the inter-punch holds (P2, P7, P1, P4), contrasting arm kinematics vs
camera-far occluded leg tracking jitter.
"""
import json
from pathlib import Path
import matplotlib
matplotlib.use("Agg")
import matplotlib.pyplot as plt
import numpy as np

ROOT = Path(r"c:\Users\Lasse\karate-kihon-analyzer")
TRACE_A = json.load(open(ROOT / "output/task5/base_segmentation/recording_a/blind-session.trace.json"))
LABELS_A = json.load(open(ROOT / "docs/validation/task5/proposed-labels.json"))

DEST = ROOT / "docs/validation/task5/diagnostics"
DEST.mkdir(parents=True, exist_ok=True)
OUT_PLOT = DEST / "hold-channel-identities.png"

frames = TRACE_A["frames"]
times = np.array([f["timestamp_ms"] for f in frames])

fig, axs = plt.subplots(4, 2, figsize=(16, 14))

holds_info = [
    (2, "Hold after P2 (LEFT)", 1800, 2100),
    (7, "Hold after P7 (RIGHT)", 5900, 6300),
    (1, "Hold after P1 (RIGHT)", 900, 1300),
    (4, "Hold after P4 (LEFT)", 3450, 3750),
]

def is_leg_channel(name):
    if not name:
        return False
    return "ANKLE" in name or "KNEE" in name or "THIGH" in name or "SHIN" in name

for row_idx, (p_num, title, t_min, t_max) in enumerate(holds_info):
    sub = [f for f in frames if t_min <= f["timestamp_ms"] <= t_max]
    sub_t = [f["timestamp_ms"] for f in sub]
    sub_et = [f.get("top2", {}).get("translation_evidence", 0.0) for f in sub]
    sub_ea = [f.get("top2", {}).get("angular_evidence", 0.0) for f in sub]
    sub_tc1 = [f.get("top2", {}).get("translation_channel_1", "") for f in sub]
    sub_ac1 = [f.get("top2", {}).get("angular_channel_1", "") for f in sub]

    # Translation axis (left column)
    ax_t = axs[row_idx, 0]
    ax_t.plot(sub_t, sub_et, color="#1f77b4", lw=1.5, zorder=1)
    ax_t.axhline(0.70, color="red", linestyle="--", alpha=0.6, label="Moving (0.70)")
    ax_t.axhline(0.45, color="green", linestyle="--", alpha=0.6, label="Quiet (0.45)")
    ax_t.set_ylabel("ET (Lref/s)", fontsize=9)
    ax_t.set_title(f"{title} - Translation Evidence (ET)", fontsize=10, fontweight="bold")
    ax_t.grid(True, alpha=0.3)

    # Color dots by channel family: Red = Far Leg, Blue = Arm/Torso
    for t, et, ch in zip(sub_t, sub_et, sub_tc1):
        c = "crimson" if is_leg_channel(ch) else "royalblue"
        ax_t.scatter(t, et, color=c, s=35, zorder=3)
        # Annotate dominant points
        if et >= 0.45:
            label_txt = ch.replace("LEFT_", "L_").replace("RIGHT_", "R_")
            ax_t.text(t, et + 0.03, label_txt, fontsize=7, rotation=30, ha="left",
                      color="darkred" if is_leg_channel(ch) else "navy")

    # Angular axis (right column)
    ax_a = axs[row_idx, 1]
    ax_a.plot(sub_t, sub_ea, color="#ff7f0e", lw=1.5, zorder=1)
    ax_a.axhline(35.0, color="red", linestyle="--", alpha=0.6, label="Moving (35°/s)")
    ax_a.axhline(20.0, color="green", linestyle="--", alpha=0.6, label="Quiet (20°/s)")
    ax_a.set_ylabel("EA (deg/s)", fontsize=9)
    ax_a.set_title(f"{title} - Angular Evidence (EA)", fontsize=10, fontweight="bold")
    ax_a.grid(True, alpha=0.3)

    for t, ea, ch in zip(sub_t, sub_ea, sub_ac1):
        c = "crimson" if is_leg_channel(ch) else "darkorange"
        ax_a.scatter(t, ea, color=c, s=35, zorder=3)
        if ea >= 20.0:
            label_txt = ch.replace("LEFT_", "L_").replace("RIGHT_", "R_").replace("_ORIENTATION", "").replace("_ANGLE", "")
            ax_a.text(t, ea + 1.5, label_txt, fontsize=7, rotation=30, ha="left",
                      color="darkred" if is_leg_channel(ch) else "darkorange")

    ax_t.set_xlim(t_min, t_max)
    ax_a.set_xlim(t_min, t_max)
    ax_t.set_xlabel("Timestamp (ms)", fontsize=9)
    ax_a.set_xlabel("Timestamp (ms)", fontsize=9)

# Add overarching legend
fig.text(0.5, 0.99, "Top-2 Dominant Channel Analysis: Red = Occluded Far-Leg (Jitter), Blue/Orange = Arm/Torso",
         ha="center", va="top", fontsize=12, fontweight="bold",
         bbox=dict(boxstyle="round,pad=0.3", facecolor="#fff2f2", edgecolor="crimson"))

plt.tight_layout(rect=[0, 0, 1, 0.97])
plt.savefig(OUT_PLOT, dpi=150)
plt.close()
print(f"Saved diagnostic plot to: {OUT_PLOT}")
