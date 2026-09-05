"""Explicit coordinate contract between analyzed and saved video frames."""

from __future__ import annotations

from dataclasses import dataclass
from typing import Any


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

    def apply(self, x: float, y: float) -> tuple[float, float]:
        return self.a * x + self.b * y + self.c, self.d * x + self.e * y + self.f

    def inverse(self) -> "AffineTransform2D":
        determinant = self.a * self.e - self.b * self.d
        if abs(determinant) < 1e-12:
            raise ValueError("Frame transform must be invertible")
        return AffineTransform2D(
            a=self.e / determinant,
            b=-self.b / determinant,
            c=(self.b * self.f - self.e * self.c) / determinant,
            d=-self.d / determinant,
            e=self.a / determinant,
            f=(self.d * self.c - self.a * self.f) / determinant,
        )


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

    @classmethod
    def identity(cls, width: int, height: int) -> "FrameGeometry":
        size = FrameSize(width, height)
        return cls(size, size, AffineTransform2D())

    @property
    def saved_to_analysis(self) -> AffineTransform2D:
        return self.analysis_to_saved.inverse()

    def normalized_analysis_to_saved_pixels(
        self, x: float, y: float
    ) -> tuple[float, float]:
        analysis_x = x * self.analysis_size.width
        analysis_y = y * self.analysis_size.height
        return self.analysis_to_saved.apply(analysis_x, analysis_y)

    def saved_pixels_to_normalized_analysis(
        self, x: float, y: float
    ) -> tuple[float, float]:
        analysis_x, analysis_y = self.saved_to_analysis.apply(x, y)
        return (
            analysis_x / self.analysis_size.width,
            analysis_y / self.analysis_size.height,
        )

    def to_dict(self) -> dict[str, Any]:
        return {
            "analysis_frame": {
                "width_px": self.analysis_size.width,
                "height_px": self.analysis_size.height,
            },
            "saved_frame": {
                "width_px": self.saved_size.width,
                "height_px": self.saved_size.height,
            },
            "analysis_to_saved_affine": [
                self.analysis_to_saved.a,
                self.analysis_to_saved.b,
                self.analysis_to_saved.c,
                self.analysis_to_saved.d,
                self.analysis_to_saved.e,
                self.analysis_to_saved.f,
            ],
            "operations": [],
            "contract": "normalized_analysis -> analysis_pixels -> saved_pixels",
        }

    @classmethod
    def from_dict(cls, payload: dict[str, Any]) -> "FrameGeometry":
        analysis = payload["analysis_frame"]
        saved = payload["saved_frame"]
        values = payload["analysis_to_saved_affine"]
        if len(values) != 6:
            raise ValueError("analysis_to_saved_affine must contain six values")
        return cls(
            analysis_size=FrameSize(analysis["width_px"], analysis["height_px"]),
            saved_size=FrameSize(saved["width_px"], saved["height_px"]),
            analysis_to_saved=AffineTransform2D(*map(float, values)),
        )
