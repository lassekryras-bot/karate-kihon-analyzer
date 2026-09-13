import json

with open("docs/validation/task5/proposed-labels.json") as f:
    d = json.load(f)

print("PROPOSED LABELS FOR 10 PUNCHES:")
for p in d["punches"]:
    num = p["punch_number"]
    side = p["expected_side"]
    m_start = p["movement_start_timestamp_ms"]
    m_end = p["movement_end_timestamp_ms"]
    h_start = p["terminal_stable_start_timestamp_ms"]
    h_end = p["terminal_stable_end_timestamp_ms"]
    h_dur = h_end - h_start
    print(f"Punch #{num:2d} ({side:5s}): move=[{m_start:5d} .. {m_end:5d}], hold=[{h_start:5d} .. {h_end:5d}], hold_dur={h_dur:3d}ms")
