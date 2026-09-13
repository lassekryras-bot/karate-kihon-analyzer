import json

with open("output/task5/phase5_variants/recording_a/DISABLED/blind-session.trace.json") as f:
    data = json.load(f)

print(f"{'time':>6} | {'ET':>6} | {'EA':>6} | {'State':>8} | {'DT':>6} | {'DA':>6} | {'Top2 Trans Channels':<35} | {'Top2 Ang Channels':<35}")
print("-" * 115)

for frame in data["frames"]:
    t = frame["timestamp_ms"]
    if 900 <= t <= 1400:
        top2 = frame.get("top2", {})
        et = top2.get("translation_evidence", 0.0)
        ea = top2.get("angular_evidence", 0.0)
        dt = top2.get("translation_decision", "")
        da = top2.get("angular_decision", "")
        tc1 = top2.get("translation_channel_1", "")
        tc2 = top2.get("translation_channel_2", "")
        ac1 = top2.get("angular_channel_1", "")
        ac2 = top2.get("angular_channel_2", "")
        st = frame["state"]
        print(f"{t:6d} | {et:6.3f} | {ea:6.1f} | {st:8s} | {dt:6s} | {da:6s} | {f'{tc1}, {tc2}':<35} | {f'{ac1}, {ac2}':<35}")
