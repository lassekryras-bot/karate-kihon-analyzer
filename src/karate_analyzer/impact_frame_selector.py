"""Pure impact frame selection utilities."""

from __future__ import annotations

from dataclasses import dataclass


@dataclass(frozen=True)
class ExtensionSample:
    """Extension measurement captured at a specific video frame."""

    frame_number: int
    timestamp_seconds: float
    extension: float


@dataclass(frozen=True)
class ImpactFrame:
    """Selected impact frame details."""

    frame_number: int
    timestamp_seconds: float
    extension: float


def select_impact_frame(samples: list[ExtensionSample]) -> ImpactFrame:
    """Return the first sample with the maximum extension.

    If multiple samples have the same maximum extension, the earliest sample in
    the sequence is selected. An empty sample list is invalid because there is no
    frame to select.
    """

    if not samples:
        raise ValueError("At least one extension sample is required")

    max_sample = max(samples, key=lambda sample: sample.extension)
    return ImpactFrame(
        frame_number=max_sample.frame_number,
        timestamp_seconds=max_sample.timestamp_seconds,
        extension=max_sample.extension,
    )
