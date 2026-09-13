"""Extract 33 pose landmarks from blind recording 20260911_223447.mp4.

Strictly decodes all source frames, preserving exact frame count and monotonic timestamps.
Absence of detection is represented honestly as empty landmark sets (no interpolation or fabrication).
"""
from __future__ import annotations

import json
import sys
from pathlib import Path

import cv2

ROOT = Path(__file__).resolve().parents[2]
sys.path.append(str(ROOT / "src"))

from karate_analyzer.diagnostics.pose_replay_import import import_analyzer_landmarks

VIDEO_PATH = ROOT / "input/task5/20260911_223447.mp4"
MODEL_PATH = ROOT / "input/models/pose_landmarker.task"
OUTPUT_DIR = ROOT / "output/task5/blind"
DEST_DIR = ROOT / "docs/validation/task5/blind"


def landmark_to_dict(lm, index: int) -> dict[str, float | int]:
    payload = {"index": index}
    for attr in ("x", "y", "z", "visibility", "presence"):
        if hasattr(lm, attr):
            val = getattr(lm, attr)
            if val is not None:
                payload[attr] = float(val)
    return payload


def serialize_landmark_groups(groups) -> list[list[dict[str, float | int]]]:
    if not groups:
        return []
    return [
        [landmark_to_dict(lm, idx) for idx, lm in enumerate(group)]
        for group in groups
    ]


def extract_poses():
    OUTPUT_DIR.mkdir(parents=True, exist_ok=True)
    DEST_DIR.mkdir(parents=True, exist_ok=True)

    import mediapipe as mp
    from mediapipe.tasks import python
    from mediapipe.tasks.python import vision

    print(f"Opening video: {VIDEO_PATH}")
    cap = cv2.VideoCapture(str(VIDEO_PATH))
    if not cap.isOpened():
        raise RuntimeError(f"Could not open video at {VIDEO_PATH}")

    declared_frames = int(cap.get(cv2.CAP_PROP_FRAME_COUNT))
    fps = float(cap.get(cv2.CAP_PROP_FPS)) or 49.18
    width = int(cap.get(cv2.CAP_PROP_FRAME_WIDTH))
    height = int(cap.get(cv2.CAP_PROP_FRAME_HEIGHT))
    print(f"Video metadata: {declared_frames} frames declared, {fps:.2f} fps, {width}x{height}")

    options = vision.PoseLandmarkerOptions(
        base_options=python.BaseOptions(model_asset_path=str(MODEL_PATH)),
        running_mode=vision.RunningMode.VIDEO,
        num_poses=1,
    )

    frames_payload = []
    frame_number = 0
    detected_count = 0
    last_timestamp_ms = -1

    with vision.PoseLandmarker.create_from_options(options) as landmarker:
        while True:
            ret, bgr_frame = cap.read()
            if not ret:
                break

            raw_pos_msec = int(round(cap.get(cv2.CAP_PROP_POS_MSEC)))
            computed_msec = int(round(frame_number * 1000.0 / fps))
            timestamp_ms = raw_pos_msec if raw_pos_msec > last_timestamp_ms else computed_msec
            if timestamp_ms <= last_timestamp_ms:
                timestamp_ms = last_timestamp_ms + 1
            last_timestamp_ms = timestamp_ms

            rgb_frame = cv2.cvtColor(bgr_frame, cv2.COLOR_BGR2RGB)
            mp_image = mp.Image(image_format=mp.ImageFormat.SRGB, data=rgb_frame)

            result = landmarker.detect_for_video(mp_image, timestamp_ms)

            pose_lms = serialize_landmark_groups(result.pose_landmarks)
            world_lms = serialize_landmark_groups(result.pose_world_landmarks)
            has_pose = bool(pose_lms)
            if has_pose:
                detected_count += 1

            frames_payload.append({
                "frame_number": frame_number,
                "timestamp_ms": timestamp_ms,
                "timestamp_seconds": timestamp_ms / 1000.0,
                "pose_detected": has_pose,
                "hand_detected": False,
                "face_detected": False,
                "poses": pose_lms,
                "world_poses": world_lms,
                "hands": [],
                "faces": [],
            })

            frame_number += 1
            if frame_number % 100 == 0 or frame_number == declared_frames:
                print(f"Processed frame {frame_number}/{declared_frames} (detected: {detected_count})")

    cap.release()
    print(f"Finished extraction: {len(frames_payload)} frames decoded, {detected_count} poses detected.")

    landmarks_payload = {
        "source": str(VIDEO_PATH),
        "kind": "video",
        "frame_count": len(frames_payload),
        "detected_frame_count": detected_count,
        "hand_detected_frame_count": 0,
        "face_detected_frame_count": 0,
        "frames": frames_payload,
        "frame_geometry": {
            "coordinate_frame": "image_top_left",
            "height": height,
            "width": width,
            "x_flipped": False,
            "y_flipped": False,
        },
        "pose_detector_backend": "tasks_pose_landmarker",
        "hand_detector_backend": "none",
        "face_detector_backend": "none",
    }

    raw_json_path = OUTPUT_DIR / "blind_video_landmarks.json"
    raw_json_path.write_text(json.dumps(landmarks_payload, indent=2) + "\n", encoding="utf-8")
    print(f"Saved {raw_json_path.name} ({len(frames_payload)} frames).")

    print("Converting to PoseReplayFixture...")
    import_result = import_analyzer_landmarks(
        landmarks_payload,
        sequence_id="blind-20260911_223447",
        source_path=str(VIDEO_PATH),
    )

    fixture = import_result.fixture
    fixture_path = OUTPUT_DIR / "blind-session.fixture.json"
    fixture_path.write_text(json.dumps(fixture, indent=2) + "\n", encoding="utf-8")
    print(f"Saved {fixture_path.name} ({len(fixture['frames'])} frames).")

    summary_path = OUTPUT_DIR / "blind-import-summary.json"
    summary_path.write_text(json.dumps(import_result.summary, indent=2) + "\n", encoding="utf-8")
    print(f"Saved {summary_path.name}.")

    return fixture_path


if __name__ == "__main__":
    extract_poses()
