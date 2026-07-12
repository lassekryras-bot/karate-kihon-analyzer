#!/usr/bin/env python3
"""Download the MediaPipe Gesture Recognizer task bundle with SHA-256 validation.

Usage:
  python3 scripts/download_gesture_recognizer_model.py --sha256 <expected-sha256>

The checksum is intentionally required so developers do not silently package a
changed or corrupted binary. Record the verified checksum in the PR or release
notes when adding/updating the model asset.
"""

from __future__ import annotations

import argparse
import hashlib
import pathlib
import urllib.request

MODEL_URL = "https://storage.googleapis.com/mediapipe-models/gesture_recognizer/gesture_recognizer/float16/1/gesture_recognizer.task"
DESTINATION = pathlib.Path("app/src/main/assets/mediapipe/gesture_recognizer.task")


def sha256(path: pathlib.Path) -> str:
    digest = hashlib.sha256()
    with path.open("rb") as handle:
        for chunk in iter(lambda: handle.read(1024 * 1024), b""):
            digest.update(chunk)
    return digest.hexdigest()


def main() -> None:
    parser = argparse.ArgumentParser()
    parser.add_argument("--sha256", required=True, help="Expected SHA-256 of the model bundle")
    parser.add_argument("--url", default=MODEL_URL, help="Model URL to download")
    parser.add_argument("--dest", default=str(DESTINATION), help="Destination asset path")
    args = parser.parse_args()

    destination = pathlib.Path(args.dest)
    destination.parent.mkdir(parents=True, exist_ok=True)
    temporary = destination.with_suffix(destination.suffix + ".download")
    urllib.request.urlretrieve(args.url, temporary)
    actual = sha256(temporary)
    if actual.lower() != args.sha256.lower():
        temporary.unlink(missing_ok=True)
        raise SystemExit(f"SHA-256 mismatch for {args.url}: expected {args.sha256}, got {actual}")
    temporary.replace(destination)
    print(f"Downloaded {destination} ({destination.stat().st_size} bytes, sha256={actual})")


if __name__ == "__main__":
    main()
