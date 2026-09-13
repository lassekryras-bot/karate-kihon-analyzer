import json

with open("output/task5/phase5_variants/recording_a/DISABLED/blind-session.trace.json") as f:
    data = json.load(f)

for frame in data["frames"]:
    t = frame["timestamp_ms"]
    if 1030 <= t <= 1150:
        print(f"\n--- Timestamp: {t} ms ---")
        top2 = frame.get("top2", {})
        print(f"ET = {top2.get('translation_evidence'):.3f}, Channels: {top2.get('translation_channel_1')}, {top2.get('translation_channel_2')}")
        # Look at channels in kinematics
        kin = frame.get("kinematics", {})
        for k, v in kin.items():
            if "speed" in k and v is not None:
                print(f"  {k}: {v:.3f}")
