from dataclasses import replace
from math import atan2, degrees

import pytest

from karate_analyzer.frame_geometry import (
    AffineTransform2D,
    CoordinateFrame,
    FrameGeometry,
    FrameSize,
    FramedPoint2D,
)
from karate_analyzer.targets import (
    LockedTargetController,
    NeutralFrameObservation,
    ProvisionalJodanEstimator,
    TargetId,
    TargetLifecycleState,
    TargetSource,
    build_neutral_reference,
)


def point(x: float, y: float) -> FramedPoint2D:
    return FramedPoint2D(x, y, CoordinateFrame.SOURCE_IMAGE_PIXELS)


def observations(
    *, visibility: float = 0.9, hip_jitter: float = 0
) -> list[NeutralFrameObservation]:
    result = []
    for frame, jitter in enumerate((-hip_jitter, 0, hip_jitter), start=10):
        result.append(
            NeutralFrameObservation(
                frame,
                point(40, 20),
                point(60, 20),
                point(42 + jitter, 80),
                point(58 + jitter, 80),
                (visibility,) * 4,
            )
        )
    return result


def test_normalized_coordinates_convert_to_pixels_on_non_square_source() -> None:
    geometry = FrameGeometry.identity(1920, 1080)
    converted = geometry.normalized_analysis_to_source_point(0.25, 0.75)
    assert converted == point(480, 810)


def test_raw_normalized_angle_is_wrong_on_16_by_9_image() -> None:
    # A 100px x 100px vector is 45 degrees, despite unequal normalized deltas.
    geometry = FrameGeometry.identity(1600, 900)
    origin = geometry.normalized_analysis_to_source_point(0.5, 0.5)
    endpoint = geometry.normalized_analysis_to_source_point(0.5625, 0.6111111111)
    raw_angle = degrees(atan2(0.1111111111, 0.0625))
    corrected_angle = degrees(atan2(endpoint.y - origin.y, endpoint.x - origin.x))
    assert raw_angle == pytest.approx(60.64, abs=0.01)
    assert corrected_angle == pytest.approx(45, abs=0.01)


def test_distance_and_projection_are_invariant_after_pixel_conversion() -> None:
    geometry = FrameGeometry.identity(1600, 900)
    a = geometry.normalized_analysis_to_source_point(0.1, 0.2)
    b = geometry.normalized_analysis_to_source_point(0.2, 0.2)
    assert a.distance_to(b) == pytest.approx(160)
    # Projection onto the horizontal unit axis is the same physical displacement.
    assert (b.x - a.x) * 1 + (b.y - a.y) * 0 == pytest.approx(160)


def test_frame_types_prevent_accidental_mixing() -> None:
    with pytest.raises(ValueError, match="Cannot mix"):
        point(1, 2).distance_to(
            FramedPoint2D(1, 2, CoordinateFrame.DISPLAYED_IMAGE_PIXELS)
        )


@pytest.mark.parametrize(
    ("transform", "expected"),
    [
        (AffineTransform2D(a=-1, c=100, e=1), (75, 50)),  # mirrored
        (AffineTransform2D(a=0, b=-1, c=100, d=1, e=0), (50, 25)),  # 90° rotation
    ],
)
def test_source_to_display_transform_handles_mirror_and_rotation(
    transform, expected
) -> None:
    geometry = FrameGeometry(
        FrameSize(100, 100), FrameSize(100, 100), transform, ("test",)
    )
    displayed = geometry.source_to_display_point(point(25, 50))
    assert (displayed.x, displayed.y) == expected
    assert displayed.frame == CoordinateFrame.DISPLAYED_IMAGE_PIXELS


def test_neutral_reference_uses_temporal_median() -> None:
    samples = observations()
    samples[1] = replace(
        samples[1], left_hip=point(500, 800), right_hip=point(520, 800)
    )
    neutral = build_neutral_reference(
        samples, FrameSize(1000, 1000), maximum_origin_spread_ratio=20
    )
    assert neutral.valid
    assert neutral.hip_midpoint == point(50, 80)
    assert neutral.contributing_frame_count == 3


@pytest.mark.parametrize(
    ("samples", "reason"),
    [
        (observations(visibility=0.2), "INSUFFICIENT_NEUTRAL_FRAMES"),
        (
            [replace(item, left_shoulder=None) for item in observations()],
            "INSUFFICIENT_NEUTRAL_FRAMES",
        ),
        (
            [
                NeutralFrameObservation(
                    i,
                    point(40, 80),
                    point(60, 80),
                    point(40, 80),
                    point(60, 80),
                    (1,) * 4,
                )
                for i in range(3)
            ],
            "DEGENERATE_TORSO_AXIS",
        ),
    ],
)
def test_quality_gates_refuse_bad_neutral_input(samples, reason) -> None:
    neutral = build_neutral_reference(samples, FrameSize(100, 100))
    assert not neutral.valid
    assert neutral.invalidation_reason == reason


def test_unstable_neutral_reference_is_rejected() -> None:
    neutral = build_neutral_reference(observations(hip_jitter=30), FrameSize(100, 100))
    assert not neutral.valid
    assert neutral.invalidation_reason == "UNSTABLE_NEUTRAL_REFERENCE"


def test_locked_target_translates_but_keeps_neutral_orientation() -> None:
    neutral = build_neutral_reference(observations(), FrameSize(100, 100))
    controller = LockedTargetController()
    assert controller.state == TargetLifecycleState.UNINITIALIZED
    controller.start_collecting()
    controller.accept_neutral(neutral, ProvisionalJodanEstimator())
    assert controller.state == TargetLifecycleState.READY
    controller.lock_for_repetition()
    original = controller.target_for_origin(neutral.origin)
    moved = controller.target_for_origin(neutral.origin.translated(10, 5))
    assert moved.centre.x - original.centre.x == 10
    assert moved.centre.y - original.centre.y == 5
    original_zone_axis = (
        original.upper_boundary.x - original.lower_boundary.x,
        original.upper_boundary.y - original.lower_boundary.y,
    )
    moved_zone_axis = (
        moved.upper_boundary.x - moved.lower_boundary.x,
        moved.upper_boundary.y - moved.lower_boundary.y,
    )
    assert (
        moved_zone_axis == original_zone_axis
    )  # instantaneous torso lean is irrelevant
    controller.finish_repetition()
    assert controller.state == TargetLifecycleState.READY


def test_provenance_and_uncertainty_are_exposed_separately_from_tolerance() -> None:
    estimate = ProvisionalJodanEstimator().estimate(
        build_neutral_reference(observations(), FrameSize(100, 100))
    )
    debug = estimate.to_debug_dict()
    assert estimate.target_id == TargetId.JODAN_CHIN
    assert debug["source"] == TargetSource.GENERIC_PROVISIONAL_ESTIMATE.value
    assert debug["uncertainty_px"] != debug["coaching_tolerance_px"]
    assert not debug["coaching_allowed"]
    assert debug["scoring_withheld_reason"]
    import json

    json.dumps(debug)


def test_invalid_neutral_refuses_target_scoring() -> None:
    estimate = ProvisionalJodanEstimator().estimate(
        build_neutral_reference([], FrameSize(100, 100))
    )
    assert estimate.centre is None
    assert not estimate.coaching_allowed
    assert estimate.scoring_withheld_reason == "INSUFFICIENT_NEUTRAL_FRAMES"
