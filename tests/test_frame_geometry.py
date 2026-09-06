import pytest

from karate_analyzer.frame_geometry import AffineTransform2D, FrameGeometry, FrameSize


def test_identity_maps_normalized_analysis_landmark_to_same_saved_frame() -> None:
    geometry = FrameGeometry.identity(1920, 1080)

    assert geometry.normalized_analysis_to_saved_pixels(0.25, 0.75) == (480, 810)
    assert geometry.saved_pixels_to_normalized_analysis(480, 810) == (0.25, 0.75)


def test_explicit_crop_resize_and_mirror_transform_round_trips() -> None:
    # x_saved = 1280 - (2 * x_analysis); y_saved = 3 * y_analysis + 20
    geometry = FrameGeometry(
        analysis_size=FrameSize(640, 360),
        saved_size=FrameSize(1280, 1100),
        analysis_to_saved=AffineTransform2D(a=-2, c=1280, e=3, f=20),
        operations=("inverse resize", "inverse mirror", "inverse crop"),
    )

    saved = geometry.normalized_analysis_to_saved_pixels(0.25, 0.5)

    assert saved == (960, 560)
    assert geometry.saved_pixels_to_normalized_analysis(*saved) == pytest.approx(
        (0.25, 0.5)
    )


def test_geometry_serialization_preserves_named_spaces_and_transform() -> None:
    geometry = FrameGeometry(
        FrameSize(480, 640),
        FrameSize(1920, 1080),
        AffineTransform2D(0, -3, 1920, 2.25, 0, 0),
        operations=("rotate 90 degrees clockwise", "resize"),
    )

    assert FrameGeometry.from_dict(geometry.to_dict()) == geometry


def test_non_invertible_transform_is_rejected_at_construction() -> None:
    with pytest.raises(ValueError, match="invertible"):
        AffineTransform2D(a=0, e=0)


def test_serialized_contract_rejects_incorrect_declared_inverse() -> None:
    payload = FrameGeometry.identity(1280, 720).to_dict()
    payload["saved_to_analysis_affine"][2] = 5.0

    with pytest.raises(ValueError, match="not the inverse"):
        FrameGeometry.from_dict(payload)


def test_saved_frame_dimensions_must_match_contract() -> None:
    geometry = FrameGeometry.identity(1280, 720)

    with pytest.raises(ValueError, match="expected 1280x720, got 720x1280"):
        geometry.validate_saved_size(720, 1280)
