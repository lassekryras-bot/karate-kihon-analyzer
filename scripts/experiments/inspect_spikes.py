import json
from pathlib import Path

# Load from trace json
with open("output/task5/phase5_variants/recording_a/DISABLED/blind-session.trace.json") as f:
    data = json.load(f)

for frame in data["frames"]:
    t = frame["timestamp_ms"]
    if t in (1050, 1067, 1083, 1100, 1117, 1133):
        print(f"\n================ Frame at {t} ms ================")
        top2 = frame.get("top2", {})
        print(f"ET={top2.get('translation_evidence')}, ch1={top2.get('translation_channel_1')}, ch2={top2.get('translation_channel_2')}")
        print(f"EA={top2.get('angular_evidence')}, ch1={top2.get('angular_channel_1')}, ch2={top2.get('angular_channel_2')}")
