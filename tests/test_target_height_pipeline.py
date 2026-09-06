from __future__ import annotations

from dataclasses import replace

import pytest
from PIL import Image

from karate_analyzer.frame_geometry import (
    AffineTransform2D,
    FrameGeometry,
    FrameSize,
)
from karate_analyzer.rendering.snapshot_renderer import (
    StrikeSnapshotRenderInstructions,
    _instructions_from_event,
    render_strike_snapshot,
)
from karate_analyzer.target_height_pipeline import (
    SetupWindowConfig,
    attach_target_height_diagnostics,
    select_setup_window,
)

GEOMETRY = FrameGeometry.identity(200, 100)


def frame(
    number, *, hip_x=0.5, shoulder_x=0.5, shoulder_y=0.25, visibility=0.9, wrist_x=0.5
):
    values = {
        11: (shoulder_x - 0.1, shoulder_y),
        12: (shoulder_x + 0.1, shoulder_y),
        23: (hip_x - 0.1, 0.75),
        24: (hip_x + 0.1, 0.75),
        15: (wrist_x, 0.4),
        16: (1 - wrist_x, 0.4),
    }
    return {
        "frame_number": number,
        "timestamp_seconds": number / 30,
        "pose_detected": True,
        "poses": [
            [
                {"index": index, "x": x, "y": y, "visibility": visibility}
                for index, (x, y) in values.items()
            ]
        ],
    }


def select(frames, **overrides):
    config = replace(SetupWindowConfig(), **overrides)
    return select_setup_window(frames, GEOMETRY, 10, config)


def test_selects_stable_contiguous_pre_punch_window_with_metrics():
    result, observations = select([frame(i) for i in range(7)])
    assert result.valid and result.contributing_frame_indices == (0, 1, 2, 3, 4)
    assert result.start_timestamp_seconds == 0
    assert result.maximum_origin_step_ratio == 0
    assert len(observations) == 5


def test_scattered_frames_are_not_combined():
    result, _ = select([frame(0), frame(2), frame(4), frame(6), frame(8)])
    assert not result.valid
    assert result.failure_reason == "NON_CONTIGUOUS_USABLE_FRAMES"


def test_rejects_window_containing_punch_motion():
    frames = [frame(i, wrist_x=0.3 if i < 2 else 0.8) for i in range(5)]
    result, _ = select(frames)
    assert not result.valid
    assert result.failure_reason == "PUNCH_MOTION_IN_SETUP_WINDOW"


def test_insufficient_pre_strike_frames():
    result, _ = select([frame(i) for i in range(4)])
    assert not result.valid and result.failure_reason == "INSUFFICIENT_STABLE_FRAMES"


@pytest.mark.parametrize(
    ("frames", "reason"),
    [
        (
            [frame(i, hip_x=0.4 if i % 2 else 0.6) for i in range(5)],
            "UNSTABLE_BODY_ORIGIN",
        ),
        (
            [frame(i, shoulder_y=0.25 if i % 2 else 0.5) for i in range(5)],
            "UNSTABLE_TORSO_SCALE",
        ),
        (
            [frame(i, shoulder_x=0.45 if i % 2 else 0.5) for i in range(5)],
            "UNSTABLE_TORSO_AXIS",
        ),
        (
            [frame(i, visibility=0.2) for i in range(5)],
            "LOW_CONFIDENCE_BILATERAL_LANDMARKS",
        ),
    ],
)
def test_rejects_unstable_or_low_quality_setup(frames, reason):
    result, _ = select(frames, maximum_wrist_step_torso_ratio=10)
    assert not result.valid and result.failure_reason == reason


def test_rejects_incompatible_per_frame_dimensions():
    frames = [frame(i) for i in range(5)]
    frames[2]["frame_geometry"] = FrameGeometry.identity(300, 100).to_dict()
    result, _ = select(frames)
    assert not result.valid
    assert result.failure_reason == "INCOMPATIBLE_SOURCE_DIMENSIONS"


def test_end_to_end_timeline_reuses_neutral_and_locks_each_repetition():
    frames = [frame(i) for i in range(5)] + [
        frame(i, hip_x=0.55, shoulder_y=0.20) for i in range(5, 15)
    ]
    events = [
        {
            "event_index": 1,
            "strike_region_start_frame": 6,
            "analysis_frame": {"frame_number": 8, "reason": "theoretical_impact"},
            "analysis_frame_number": 7,
        },
        {
            "event_index": 2,
            "strike_region_start_frame": 10,
            "analysis_frame_number": 12,
        },
    ]
    session = attach_target_height_diagnostics(frames, events, GEOMETRY.to_dict())
    assert (
        session["neutral_reference"]["setup_window_id"]
        == session["setup_window"]["window_id"]
    )
    assert (
        events[0]["neutral_reference"]["reference_id"]
        == events[1]["neutral_reference"]["reference_id"]
    )
    assert (
        events[0]["target_height_diagnostic"]["repetition_lock_id"]
        != events[1]["target_height_diagnostic"]["repetition_lock_id"]
    )
    diagnostic = events[0]["target_height_diagnostic"]
    assert diagnostic["analysis_frame_number"] == 8
    assert diagnostic["geometry_provenance"] == {
        "measurement_frame_number": 8,
        "measurement_frame_role": "analysis_frame",
        "coordinate_frame": "source_image_pixels",
        "transport_to_snapshot": "none",
    }
    assert diagnostic["tracked_origin_displacement"]["x"] == pytest.approx(10)
    assert diagnostic["torso_lean_difference_degrees"] > 0
    estimate = events[0]["target_estimate"]
    assert estimate["coaching_allowed"] is False
    assert (
        estimate["scoring_withheld_reason"]
        == "PROVISIONAL_ESTIMATE_NOT_APPROVED_FOR_COACHING"
    )
    # Translation does not alter the zone's fixed neutral-axis orientation.
    assert estimate["upper_boundary"]["x"] == pytest.approx(
        estimate["lower_boundary"]["x"]
    )


def test_target_failure_does_not_remove_existing_strike_analysis():
    events = [
        {
            "event_index": 1,
            "analysis": {"jodan_height": {"status": "good"}},
            "strike_region_start_frame": 2,
        }
    ]
    attach_target_height_diagnostics([], events, GEOMETRY.to_dict())
    assert events[0]["analysis"]["jodan_height"]["status"] == "good"
    assert events[0]["target_height_diagnostic"]["status"] == "ABSTAINED"


def test_debug_overlay_maps_source_target_through_display_mirroring():
    geometry = FrameGeometry(
        FrameSize(500, 300),
        FrameSize(500, 300),
        AffineTransform2D(a=-1, c=500, e=1),
        ("horizontal mirror",),
    )
    source_point = {
        "x": 50,
        "y": 250,
        "coordinate_frame": "source_image_pixels",
    }
    instructions = StrikeSnapshotRenderInstructions(
        1,
        "right",
        target_estimate={
            "centre": source_point,
            "lower_boundary": {**source_point, "y": 240},
            "upper_boundary": {**source_point, "y": 260},
            "target_id": "JODAN_CHIN",
            "source": "GENERIC_PROVISIONAL_ESTIMATE",
        },
        neutral_reference={
            "origin": source_point,
            "vertical_axis": [0, -1],
            "torso_scale_px": 50,
        },
        frame_geometry=geometry,
    )
    image = render_strike_snapshot(
        Image.new("RGB", (500, 300), "white"), [], instructions
    )
    assert image.getpixel((450, 250)) != (255, 255, 255)
    assert image.getpixel((50, 250)) == (255, 255, 255)


def test_debug_band_endpoints_respect_non_uniform_display_scaling():
    geometry = FrameGeometry(
        FrameSize(100, 100),
        FrameSize(200, 100),
        AffineTransform2D(a=2, e=1),
        ("scale x by two",),
    )
    source_point = {
        "x": 50,
        "y": 80,
        "coordinate_frame": "source_image_pixels",
    }
    instructions = StrikeSnapshotRenderInstructions(
        1,
        "right",
        target_estimate={
            "centre": source_point,
            "lower_boundary": {**source_point, "y": 70},
            "upper_boundary": {**source_point, "y": 90},
        },
        neutral_reference={
            "origin": source_point,
            "vertical_axis": [0, -1],
            "torso_scale_px": 50,
        },
        frame_geometry=geometry,
    )
    image = render_strike_snapshot(
        Image.new("RGB", (200, 100), "white"), [], instructions
    )
    # Source x=20 endpoint maps to x=40. Adding a source-space vector after
    # display conversion would incorrectly begin the line at x=70.
    assert image.getpixel((40, 70)) != (255, 255, 255)


def test_overlay_is_suppressed_when_snapshot_and_measurement_frames_differ():
    event = {
        "event_index": 1,
        "observed_side": "right",
        "analysis_frame": {"frame_number": 8},
        "snapshot_frame": {"frame_number": 9},
        "analysis": {"jodan_height": {"status": "good"}},
        "target_estimate": {"centre": {"x": 10, "y": 20}},
        "neutral_reference": {"origin": {"x": 10, "y": 30}},
        "current_torso_axis": {"origin": {"x": 10, "y": 30}},
        "target_height_diagnostic": {
            "geometry_provenance": {"measurement_frame_number": 8}
        },
    }
    instructions = _instructions_from_event(event)
    assert instructions.snapshot_frame_number == 9
    assert instructions.target_estimate is None
    assert instructions.neutral_reference is None
    assert instructions.current_torso_axis is None
    assert instructions.target_overlay_warning == "TARGET_DIAGNOSTIC_FRAME_MISMATCH"
    assert instructions.jodan_height_analysis == {"status": "good"}


def test_overlay_is_retained_when_snapshot_and_measurement_frames_match():
    event = {
        "event_index": 1,
        "observed_side": "right",
        "analysis_frame_number": 8,
        "snapshot_frame_number": 8,
        "target_estimate": {"centre": {"x": 10, "y": 20}},
        "target_height_diagnostic": {
            "geometry_provenance": {"measurement_frame_number": 8}
        },
    }
    instructions = _instructions_from_event(event)
    assert instructions.target_estimate == event["target_estimate"]
    assert instructions.target_overlay_warning is None


def test_legacy_event_uses_analysis_frame_as_snapshot_fallback():
    instructions = _instructions_from_event(
        {"event_index": 1, "observed_side": "right", "analysis_frame_number": 8}
    )
    assert instructions.analysis_frame_number == 8
    assert instructions.snapshot_frame_number == 8
