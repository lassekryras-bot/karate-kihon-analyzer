import json

with open("output/task5/phase5_variants/recording_a/DISABLED/blind-session.trace.json") as f:
    data = json.load(f)

for frame in data["frames"]:
    t = frame["timestamp_ms"]
    if t == 1067:
        print(f"Timestamp: {t}")
        regions = frame.get("regions", {})
        print("Regions:", json.dumps(regions, indent=2))
        top2 = frame.get("top2", {})
        print("Top2:", json.dumps(top2, indent=2))
