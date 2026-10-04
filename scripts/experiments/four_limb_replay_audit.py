"""Audit the sparse geometry fixture and regenerate a separate full replay from its source.

Run from repository root with PYTHONPATH=src. No Kotlin measurements are duplicated here.
Outputs stay under ignored output/four-limb-audit; original video/export/fixture are read-only.
"""
from __future__ import annotations

import argparse
import hashlib
import json
import os
from pathlib import Path

import cv2
import mediapipe

from karate_analyzer.diagnostics.pose_replay_import import import_analyzer_landmarks
from karate_analyzer.vision.mediapipe_pose_spike import analyze_video

ROOT = Path(__file__).resolve().parents[2]
OUT = ROOT / "output/four-limb-audit"
VIDEO = ROOT / "input/task5/1000002073.mp4"
OLD = ROOT / "input/task5/video_landmarks.json"
SPARSE = ROOT / "android/KarateClipRecorder/karate-analyzer-core/src/test/resources/fixtures/real-kihon-sample.fixture.json"
MODEL = ROOT / "input/models/pose_landmarker_heavy.task"


def digest(path):
    return hashlib.sha256(path.read_bytes()).hexdigest()


def write(path, value):
    path.parent.mkdir(parents=True, exist_ok=True)
    path.write_text(json.dumps(value, indent=2) + "\n", encoding="utf-8")


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("--regenerate", action="store_true")
    args = parser.parse_args()
    capture = cv2.VideoCapture(str(VIDEO))
    if not capture.isOpened():
        raise RuntimeError("Original source video unavailable")
    ok, first = capture.read()
    if not ok:
        raise RuntimeError("Original source video has no decoded frame")
    height, width = first.shape[:2]
    video_metadata = {"decoded_width": width, "decoded_height": height,
                      "declared_frames": int(capture.get(cv2.CAP_PROP_FRAME_COUNT)),
                      "fps": capture.get(cv2.CAP_PROP_FPS),
                      "orientation_metadata": capture.get(cv2.CAP_PROP_ORIENTATION_META)}
    capture.release()
    old = json.loads(OLD.read_text(encoding="utf-8"))
    sparse = json.loads(SPARSE.read_text(encoding="utf-8"))
    imported = import_analyzer_landmarks(old, sequence_id="original-full-task5", source_path=str(OLD))
    full = imported.fixture
    full.update(source_width=width, source_height=height)
    by_time = {frame["timestamp_ms"]: frame for frame in full["frames"]}
    exact = all(frame == by_time.get(frame["timestamp_ms"]) for frame in sparse["frames"])
    if not exact:
        raise RuntimeError("Sparse fixture is not an exact timestamp-keyed subset of the claimed original")
    write(OUT / "original-full.fixture.json", full)
    times = [f["timestamp_ms"] for f in sparse["frames"]]
    windows = [[times[0], times[0], 1]]
    for time in times[1:]:
        if time - windows[-1][1] > 100:
            windows.append([time, time, 1])
        else:
            windows[-1][1] = time
            windows[-1][2] += 1
    manifest = {"source_video": str(VIDEO.relative_to(ROOT)), "video": video_metadata,
                "sha256": {"video": digest(VIDEO), "original_export": digest(OLD), "sparse_fixture": digest(SPARSE)},
                "original_frame_count": len(full["frames"]), "sparse_frame_count": len(times),
                "sparse_exact_subset": exact, "sparse_windows_start_end_count_ms": windows,
                "original_import": imported.summary,
                "coordinate_contract": "Source-normalized decoded upright frame; width/height verified from source video; no world-coordinate substitution.",
                "opencv_version": cv2.__version__, "mediapipe_python_version": mediapipe.__version__}
    write(OUT / "provenance.json", manifest)
    print(json.dumps(manifest, indent=2), flush=True)
    if args.regenerate:
        os.environ["MEDIAPIPE_POSE_MODEL_PATH"] = str(MODEL)
        print("Regenerating with existing Python analyze_video, heavy model; Android decoder/runtime parity is not claimed.", flush=True)
        regenerated = analyze_video(VIDEO, OUT / "regenerated")
        fresh = import_analyzer_landmarks(regenerated, sequence_id="regenerated-heavy-task5",
                                           source_path=str(OUT / "regenerated/video_landmarks.json"))
        fresh.fixture.update(source_width=width, source_height=height)
        write(OUT / "regenerated-full.fixture.json", fresh.fixture)
        manifest["regeneration"] = {"model_sha256": digest(MODEL), "model": str(MODEL.relative_to(ROOT)),
                                     "summary": fresh.summary, "frame_geometry": regenerated.get("frame_geometry"),
                                     "pose_backend": regenerated.get("pose_detector_backend"),
                                     "same_timestamps": [f["timestamp_ms"] for f in fresh.fixture["frames"]] ==
                                     [f["timestamp_ms"] for f in full["frames"]],
                                     "fixture_sha256": digest(OUT / "regenerated-full.fixture.json")}
        write(OUT / "provenance.json", manifest)
        print(json.dumps(manifest["regeneration"], indent=2), flush=True)


if __name__ == "__main__":
    main()
