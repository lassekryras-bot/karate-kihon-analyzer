#!/usr/bin/env python3
"""
Audio Cue Package Generator & Waveform Cumulative Absolute Amplitude Analyzer.

Deterministically analyzes raw audio files, computes cumulative absolute amplitude,
extracts anchor offsets at configurable thresholds (default: A10 = 0.10),
calculates SHA-256 digests, and outputs validated audio package metadata.
"""

import argparse
import hashlib
import json
import os
import sys
import wave
import numpy as np

DEFAULT_COUNT_FILES = [
    ("COUNT_1", "order_ichi.wav"),
    ("COUNT_2", "order_ni.wav"),
    ("COUNT_3", "order_san.wav"),
    ("COUNT_4", "order_shi.wav"),
    ("COUNT_5", "order_go.wav"),
    ("COUNT_6", "order_roku.wav"),
    ("COUNT_7", "order_shichi.wav"),
    ("COUNT_8", "order_hachi.wav"),
    ("COUNT_9", "order_kyu.wav"),
    ("COUNT_10", "order_ju.wav"),
]

def analyze_wav_file(file_path: str, cue_id: str, target_fraction: float = 0.10) -> dict:
    with open(file_path, "rb") as f:
        raw_bytes = f.read()
    sha256 = hashlib.sha256(raw_bytes).hexdigest()

    with wave.open(file_path, "rb") as w:
        channels = w.getnchannels()
        sample_rate = w.getframerate()
        sample_width = w.getsampwidth()
        num_frames = w.getnframes()
        frames = w.readframes(num_frames)

    if sample_width != 2:
        raise ValueError(f"Expected 16-bit PCM, found {sample_width * 8}-bit in {file_path}")

    # 16-bit little-endian
    samples = np.frombuffer(frames, dtype=np.int16)
    if channels > 1:
        samples = samples.reshape(-1, channels)
        abs_amp = np.mean(np.abs(samples.astype(np.float64)), axis=1)
    else:
        abs_amp = np.abs(samples.astype(np.float64))

    cumsum = np.cumsum(abs_amp)
    total_area = cumsum[-1] if len(cumsum) > 0 else 0.0
    norm_cum = cumsum / total_area if total_area > 0 else np.zeros_like(cumsum)

    def offset_at_fraction(frac: float) -> int:
        idx = int(np.searchsorted(norm_cum, frac))
        if idx >= len(norm_cum):
            idx = len(norm_cum) - 1
        return int(round((idx / sample_rate) * 1_000_000))

    duration_us = int(round((num_frames / sample_rate) * 1_000_000))
    anchor_offset_us = offset_at_fraction(target_fraction)

    diagnostic_anchors = {
        "A5": offset_at_fraction(0.05),
        "A10": offset_at_fraction(0.10),
        "A15": offset_at_fraction(0.15),
        "A20": offset_at_fraction(0.20),
        "A98": offset_at_fraction(0.98),
    }

    resource_name = os.path.splitext(os.path.basename(file_path))[0]

    return {
        "cueId": cue_id,
        "resourceName": resource_name,
        "sha256Hex": sha256,
        "durationUs": duration_us,
        "anchorOffsetUs": anchor_offset_us,
        "sampleRate": sample_rate,
        "channelCount": channels,
        "sourceFormat": "WAV_PCM_16BIT",
        "diagnosticAnchorsUs": diagnostic_anchors,
    }

def generate_package(audio_dir: str, package_id: str = "japanese_count", version: str = "v1", fraction: float = 0.10) -> dict:
    package_version_id = f"{package_id}:{version}"
    assets = {}
    for cue_id, filename in DEFAULT_COUNT_FILES:
        path = os.path.join(audio_dir, filename)
        if not os.path.exists(path):
            raise FileNotFoundError(f"Missing audio file: {path}")
        asset = analyze_wav_file(path, cue_id, target_fraction=fraction)
        asset["packageVersionId"] = package_version_id
        assets[cue_id] = asset

    return {
        "packageId": package_id,
        "version": version,
        "packageVersionId": package_version_id,
        "anchorMethod": "CUMULATIVE_ABSOLUTE_AMPLITUDE",
        "anchorFraction": fraction,
        "analyzerVersion": "cumulative_absolute_amplitude_v1",
        "assets": assets,
    }

def main():
    parser = argparse.ArgumentParser(description="Generate Audio Cue Package timing metadata.")
    parser.add_argument(
        "--dir",
        default="android/KarateClipRecorder/app/src/main/res/raw",
        help="Directory containing WAV files.",
    )
    parser.add_argument("--package-id", default="japanese_count", help="Audio package identifier.")
    parser.add_argument("--version", default="v1", help="Audio package version.")
    parser.add_argument("--fraction", type=float, default=0.10, help="Anchor fraction (e.g. 0.10 for A10).")
    args = parser.parse_args()

    try:
        pkg = generate_package(args.dir, args.package_id, args.version, args.fraction)
        print(json.dumps(pkg, indent=2))
    except Exception as e:
        print(f"Error: {e}", file=sys.stderr)
        sys.exit(1)

if __name__ == "__main__":
    main()

