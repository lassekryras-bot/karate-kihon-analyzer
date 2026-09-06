"""Explicit coordinate contract between analyzed and saved video frames."""

from __future__ import annotations

from dataclasses import dataclass
from enum import Enum
from math import hypot, isclose, isfinite
from typing import Any


class CoordinateFrame(str, Enum):
    """Named spaces; analytical APIs reject accidental cross-frame geometry."""

    SOURCE_IMAGE_PIXELS = "source_image_pixels"
    DISPLAYED_IMAGE_PIXELS = "displayed_image_pixels"
    ASPECT_CORRECTED_2D = "aspect_corrected_2d"
    GRAVITY_CORRECTED_2D = "gravity_camera_roll_corrected_2d"
    NEUTRAL_BODY_NORMALIZED_2D = "neutral_body_normalized_2d"
    MEDIAPIPE_WORLD = "mediapipe_world"


@dataclass(frozen=True)
class FramedPoint2D:
    """A 2D point whose coordinate frame is part of its value and API."""

    x: float
    y: float
    frame: CoordinateFrame

    def __post_init__(self) -> None:
        if not isfinite(self.x) or not isfinite(self.y):
            raise ValueError("Point coordinates must be finite")

    def distance_to(self, other: "FramedPoint2D") -> float:
        self._require_same_frame(other)
        return hypot(other.x - self.x, other.y - self.y)

    def translated(self, dx: float, dy: float) -> "FramedPoint2D":
        return FramedPoint2D(self.x + dx, self.y + dy, self.frame)

    def _require_same_frame(self, other: "FramedPoint2D") -> None:
        if self.frame != other.frame:
            raise ValueError(f"Cannot mix {self.frame.value} and {other.frame.value}")


@dataclass(frozen=True)
class FrameSize:
    """Pixel dimensions of one named frame space."""

    width: int
    height: int

    def __post_init__(self) -> None:
        if self.width <= 0 or self.height <= 0:
            raise ValueError("Frame dimensions must be positive")


@dataclass(frozen=True)
class AffineTransform2D:
    """Affine pixel transform represented by a six-value 2D matrix."""

    a: float = 1.0
    b: float = 0.0
    c: float = 0.0
    d: float = 0.0
    e: float = 1.0
    f: float = 0.0

    def __post_init__(self) -> None:
        values = (self.a, self.b, self.c, self.d, self.e, self.f)
        if not all(isfinite(float(value)) for value in values):
            raise ValueError("Frame transform values must be finite")
        if abs(self._determinant()) < 1e-12:
            raise ValueError("Frame transform must be invertible")

    def apply(self, x: float, y: float) -> tuple[float, float]:
        return self.a * x + self.b * y + self.c, self.d * x + self.e * y + self.f

    def inverse(self) -> "AffineTransform2D":
        determinant = self._determinant()
        return AffineTransform2D(
            a=self.e / determinant,
            b=-self.b / determinant,
            c=(self.b * self.f - self.e * self.c) / determinant,
            d=-self.d / determinant,
            e=self.a / determinant,
            f=(self.d * self.c - self.a * self.f) / determinant,
        )

    def to_list(self) -> list[float]:
        return [self.a, self.b, self.c, self.d, self.e, self.f]

    def _determinant(self) -> float:
        return self.a * self.e - self.b * self.d


@dataclass(frozen=True)
class FrameGeometry:
    """Mapping between MediaPipe's analysis pixels and saved-frame pixels.

    MediaPipe image landmarks remain normalized in the analysis frame. World
    landmarks are intentionally outside this contract and must never be passed
    to these image-space conversion methods.
    """

    analysis_size: FrameSize
    saved_size: FrameSize
    analysis_to_saved: AffineTransform2D
    operations: tuple[str, ...]
    contract_version: int = 1

    def __post_init__(self) -> None:
        if self.contract_version != 1:
            raise ValueError(
                f"Unsupported FrameGeometry version: {self.contract_version}"
            )
        if not self.operations:
            raise ValueError("FrameGeometry must describe its transform operations")

    @classmethod
    def identity(cls, width: int, height: int) -> "FrameGeometry":
        size = FrameSize(width, height)
        return cls(
            size,
            size,
            AffineTransform2D(),
            operations=("identity: same decoded source-frame pixels",),
        )

    @property
    def saved_to_analysis(self) -> AffineTransform2D:
        return self.analysis_to_saved.inverse()

    def normalized_analysis_to_saved_pixels(
        self, x: float, y: float
    ) -> tuple[float, float]:
        analysis_x = x * self.analysis_size.width
        analysis_y = y * self.analysis_size.height
        return self.analysis_to_saved.apply(analysis_x, analysis_y)

    def normalized_analysis_to_source_point(self, x: float, y: float) -> FramedPoint2D:
        """Convert MediaPipe image coordinates before doing 2D geometry."""

        return FramedPoint2D(
            x * self.analysis_size.width,
            y * self.analysis_size.height,
            CoordinateFrame.SOURCE_IMAGE_PIXELS,
        )

    def source_to_display_point(self, point: FramedPoint2D) -> FramedPoint2D:
        if point.frame != CoordinateFrame.SOURCE_IMAGE_PIXELS:
            raise ValueError("source_to_display_point requires source-image pixels")
        x, y = self.analysis_to_saved.apply(point.x, point.y)
        return FramedPoint2D(x, y, CoordinateFrame.DISPLAYED_IMAGE_PIXELS)

    def saved_pixels_to_normalized_analysis(
        self, x: float, y: float
    ) -> tuple[float, float]:
        analysis_x, analysis_y = self.saved_to_analysis.apply(x, y)
        return (
            analysis_x / self.analysis_size.width,
            analysis_y / self.analysis_size.height,
        )

    def validate_saved_size(self, width: int, height: int) -> None:
        actual = FrameSize(width, height)
        if actual != self.saved_size:
            raise ValueError(
                "Saved frame dimensions do not match FrameGeometry: "
                f"expected {self.saved_size.width}x{self.saved_size.height}, "
                f"got {actual.width}x{actual.height}"
            )

    def to_dict(self) -> dict[str, Any]:
        return {
            "contract_version": self.contract_version,
            "analysis_frame": {
                "width_px": self.analysis_size.width,
                "height_px": self.analysis_size.height,
            },
            "saved_frame": {
                "width_px": self.saved_size.width,
                "height_px": self.saved_size.height,
            },
            "analysis_to_saved_affine": self.analysis_to_saved.to_list(),
            "saved_to_analysis_affine": self.saved_to_analysis.to_list(),
            "operations": list(self.operations),
            "contract": "normalized_analysis -> analysis_pixels -> saved_pixels",
            "point_convention": (
                "continuous pixels; normalized x/y multiply by analysis width/height"
            ),
        }

    @classmethod
    def from_dict(cls, payload: dict[str, Any]) -> "FrameGeometry":
        analysis = payload["analysis_frame"]
        saved = payload["saved_frame"]
        values = payload["analysis_to_saved_affine"]
        if len(values) != 6:
            raise ValueError("analysis_to_saved_affine must contain six values")
        geometry = cls(
            analysis_size=FrameSize(analysis["width_px"], analysis["height_px"]),
            saved_size=FrameSize(saved["width_px"], saved["height_px"]),
            analysis_to_saved=AffineTransform2D(*map(float, values)),
            operations=tuple(str(item) for item in payload.get("operations", ())),
            contract_version=int(payload.get("contract_version", 1)),
        )
        declared_inverse = payload.get("saved_to_analysis_affine")
        if declared_inverse is not None:
            if len(declared_inverse) != 6:
                raise ValueError("saved_to_analysis_affine must contain six values")
            expected = geometry.saved_to_analysis.to_list()
            if any(
                not isclose(expected_value, float(actual_value), abs_tol=1e-9)
                for expected_value, actual_value in zip(expected, declared_inverse)
            ):
                raise ValueError(
                    "saved_to_analysis_affine is not the inverse transform"
                )
        return geometry
