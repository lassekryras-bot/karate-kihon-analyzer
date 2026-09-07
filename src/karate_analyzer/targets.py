"""Typed, abstention-first foundations for punch-target height estimation.

Targets in this module are curriculum definitions and estimates, not claims of
validated anatomy.  In particular, generic ratios remain explicitly provisional.
"""

from __future__ import annotations

from abc import ABC, abstractmethod
from dataclasses import dataclass, field
from enum import Enum
from math import hypot
from statistics import median
from typing import Any, Iterable, Mapping
from uuid import uuid4

from karate_analyzer.frame_geometry import CoordinateFrame, FrameSize, FramedPoint2D


class KarateLevel(str, Enum):
    JODAN = "JODAN"
    CHUDAN = "CHUDAN"
    GEDAN = "GEDAN"


class TargetId(str, Enum):
    JODAN_CHIN = "JODAN_CHIN"
    CHUDAN_SOLAR_PLEXUS = "CHUDAN_SOLAR_PLEXUS"
    GEDAN_LOWER_ABDOMEN = "GEDAN_LOWER_ABDOMEN"
    GEDAN_GROIN_LEVEL = "GEDAN_GROIN_LEVEL"


class TargetSource(str, Enum):
    INSTRUCTOR_APPROVED_PERSONAL = "INSTRUCTOR_APPROVED_PERSONAL"
    APP_DERIVED_PERSONAL_ANATOMY = "APP_DERIVED_PERSONAL_ANATOMY"
    CURRICULUM_POPULATION_ESTIMATE = "CURRICULUM_POPULATION_ESTIMATE"
    GENERIC_PROVISIONAL_ESTIMATE = "GENERIC_PROVISIONAL_ESTIMATE"
    USER_DEMONSTRATED_BASELINE = "USER_DEMONSTRATED_BASELINE"


class TargetLifecycleState(str, Enum):
    UNINITIALIZED = "UNINITIALIZED"
    COLLECTING_NEUTRAL_REFERENCE = "COLLECTING_NEUTRAL_REFERENCE"
    READY = "READY"
    LOCKED_FOR_REPETITION = "LOCKED_FOR_REPETITION"
    DEGRADED = "DEGRADED"
    INVALID = "INVALID"


@dataclass(frozen=True)
class TargetDefinition:
    target_id: TargetId
    broad_level: KarateLevel
    description: str
    definition_version: str
    applicability: Mapping[str, str] = field(default_factory=dict)


TARGET_DEFINITIONS = {
    TargetId.JODAN_CHIN: TargetDefinition(
        TargetId.JODAN_CHIN,
        KarateLevel.JODAN,
        "Curriculum target described as chin height; estimator remains provisional.",
        "1",
    ),
    TargetId.CHUDAN_SOLAR_PLEXUS: TargetDefinition(
        TargetId.CHUDAN_SOLAR_PLEXUS,
        KarateLevel.CHUDAN,
        "Curriculum target described as solar-plexus height.",
        "1",
    ),
    TargetId.GEDAN_LOWER_ABDOMEN: TargetDefinition(
        TargetId.GEDAN_LOWER_ABDOMEN,
        KarateLevel.GEDAN,
        "Curriculum target described as lower-abdomen height.",
        "1",
    ),
    TargetId.GEDAN_GROIN_LEVEL: TargetDefinition(
        TargetId.GEDAN_GROIN_LEVEL,
        KarateLevel.GEDAN,
        "Curriculum target described as groin level.",
        "1",
    ),
}


@dataclass(frozen=True)
class LandmarkQualitySummary:
    minimum_visibility: float
    median_visibility: float
    warnings: tuple[str, ...] = ()


@dataclass(frozen=True)
class NeutralBodyReference:
    reference_id: str
    version: str
    origin: FramedPoint2D
    vertical_axis: tuple[float, float]
    shoulder_midpoint: FramedPoint2D
    hip_midpoint: FramedPoint2D
    torso_scale_px: float
    source_size: FrameSize
    camera_roll_degrees: float | None
    contributing_frame_count: int
    frame_interval: tuple[int, int]
    quality: LandmarkQualitySummary
    confidence: float
    valid: bool
    invalidation_reason: str | None = None
    setup_window_id: str | None = None
    setup_selector_version: str | None = None


@dataclass(frozen=True)
class NeutralFrameObservation:
    frame_number: int
    left_shoulder: FramedPoint2D | None
    right_shoulder: FramedPoint2D | None
    left_hip: FramedPoint2D | None
    right_hip: FramedPoint2D | None
    visibilities: tuple[float, float, float, float]
    timestamp_seconds: float | None = None


def build_neutral_reference(
    observations: Iterable[NeutralFrameObservation],
    source_size: FrameSize,
    *,
    minimum_frames: int = 3,
    minimum_visibility: float = 0.5,
    maximum_origin_spread_ratio: float = 0.12,
) -> NeutralBodyReference:
    """Median-aggregate a setup window and return structured invalidity."""

    supplied = list(observations)
    usable: list[tuple[int, FramedPoint2D, FramedPoint2D, float]] = []
    degenerate_count = 0
    for item in supplied:
        points = (
            item.left_shoulder,
            item.right_shoulder,
            item.left_hip,
            item.right_hip,
        )
        if (
            any(point is None for point in points)
            or min(item.visibilities) < minimum_visibility
        ):
            continue
        concrete = tuple(point for point in points if point is not None)
        if any(
            point.frame != CoordinateFrame.SOURCE_IMAGE_PIXELS for point in concrete
        ):
            raise ValueError("Neutral observations require source-image pixel points")
        shoulder = _midpoint(concrete[0], concrete[1])
        hip = _midpoint(concrete[2], concrete[3])
        torso = shoulder.distance_to(hip)
        if torso > 1e-6:
            usable.append((item.frame_number, shoulder, hip, min(item.visibilities)))
        else:
            degenerate_count += 1
    if len(usable) < minimum_frames:
        reason = (
            "DEGENERATE_TORSO_AXIS"
            if degenerate_count >= minimum_frames
            else "INSUFFICIENT_NEUTRAL_FRAMES"
        )
        return _invalid_reference(source_size, supplied, reason)
    shoulder = _median_point(row[1] for row in usable)
    hip = _median_point(row[2] for row in usable)
    dx, dy = shoulder.x - hip.x, shoulder.y - hip.y
    scale = hypot(dx, dy)
    if scale <= 1e-6:
        return _invalid_reference(source_size, supplied, "DEGENERATE_TORSO_AXIS")
    origins = [row[2] for row in usable]
    spread = max(origin.distance_to(hip) for origin in origins)
    if spread / scale > maximum_origin_spread_ratio:
        return _invalid_reference(source_size, supplied, "UNSTABLE_NEUTRAL_REFERENCE")
    qualities = [row[3] for row in usable]
    return NeutralBodyReference(
        reference_id=str(uuid4()),
        version="neutral-reference-v1",
        origin=hip,
        vertical_axis=(dx / scale, dy / scale),
        shoulder_midpoint=shoulder,
        hip_midpoint=hip,
        torso_scale_px=scale,
        source_size=source_size,
        camera_roll_degrees=None,
        contributing_frame_count=len(usable),
        frame_interval=(usable[0][0], usable[-1][0]),
        quality=LandmarkQualitySummary(min(qualities), median(qualities)),
        confidence=min(qualities),
        valid=True,
    )


@dataclass(frozen=True)
class TargetEstimate:
    target_id: TargetId
    centre: FramedPoint2D | None
    lower_boundary: FramedPoint2D | None
    upper_boundary: FramedPoint2D | None
    coordinate_frame: CoordinateFrame
    source: TargetSource
    confidence: float
    uncertainty_px: float | None
    coaching_tolerance_px: float | None
    estimator_version: str
    neutral_reference_id: str | None
    quality_warnings: tuple[str, ...] = ()
    coaching_allowed: bool = False
    scoring_withheld_reason: str | None = None

    def to_debug_dict(self) -> dict[str, Any]:
        def encoded_point(point: FramedPoint2D | None) -> dict[str, Any] | None:
            return (
                None
                if point is None
                else {"x": point.x, "y": point.y, "coordinate_frame": point.frame.value}
            )

        return {
            "target_id": self.target_id.value,
            "centre": encoded_point(self.centre),
            "lower_boundary": encoded_point(self.lower_boundary),
            "upper_boundary": encoded_point(self.upper_boundary),
            "coordinate_frame": self.coordinate_frame.value,
            "source": self.source.value,
            "confidence": self.confidence,
            "uncertainty_px": self.uncertainty_px,
            "coaching_tolerance_px": self.coaching_tolerance_px,
            "estimator_version": self.estimator_version,
            "neutral_reference_id": self.neutral_reference_id,
            "quality_warnings": list(self.quality_warnings),
            "coaching_allowed": self.coaching_allowed,
            "scoring_withheld_reason": self.scoring_withheld_reason,
        }


@dataclass(frozen=True)
class ProvisionalTargetConfig:
    """Legacy-compatible values; none are anatomically validated."""

    jodan_offset_torso_ratio: float = 0.0
    coaching_tolerance_torso_ratio: float = 0.15
    estimation_uncertainty_torso_ratio: float = 0.10


class TargetEstimator(ABC):
    target_id: TargetId

    @abstractmethod
    def estimate(self, neutral: NeutralBodyReference) -> TargetEstimate: ...


class ProvisionalJodanEstimator(TargetEstimator):
    target_id = TargetId.JODAN_CHIN

    def __init__(self, config: ProvisionalTargetConfig | None = None) -> None:
        self.config = config or ProvisionalTargetConfig()

    def estimate(self, neutral: NeutralBodyReference) -> TargetEstimate:
        if not neutral.valid:
            return _withheld(
                self.target_id,
                neutral.invalidation_reason or "INVALID_NEUTRAL_REFERENCE",
            )
        # The neutral shoulder midpoint is only a compatibility anchor. A future
        # face-assisted estimator must replace it before authoritative coaching.
        centre = neutral.shoulder_midpoint.translated(
            neutral.vertical_axis[0]
            * neutral.torso_scale_px
            * self.config.jodan_offset_torso_ratio,
            neutral.vertical_axis[1]
            * neutral.torso_scale_px
            * self.config.jodan_offset_torso_ratio,
        )
        tolerance = neutral.torso_scale_px * self.config.coaching_tolerance_torso_ratio
        uncertainty = (
            neutral.torso_scale_px * self.config.estimation_uncertainty_torso_ratio
        )
        upper = centre.translated(
            neutral.vertical_axis[0] * tolerance, neutral.vertical_axis[1] * tolerance
        )
        lower = centre.translated(
            -neutral.vertical_axis[0] * tolerance, -neutral.vertical_axis[1] * tolerance
        )
        return TargetEstimate(
            self.target_id,
            centre,
            lower,
            upper,
            centre.frame,
            TargetSource.GENERIC_PROVISIONAL_ESTIMATE,
            neutral.confidence,
            uncertainty,
            tolerance,
            "provisional-jodan-v1",
            neutral.reference_id,
            ("PROVISIONAL_ANATOMICAL_ASSUMPTION",),
            False,
            "PROVISIONAL_ESTIMATE_NOT_APPROVED_FOR_COACHING",
        )


class FaceAssistedJodanEstimator(TargetEstimator, ABC):
    """Extension point; no Face Landmarker inference is introduced here."""

    target_id = TargetId.JODAN_CHIN


class ChudanTargetEstimator(TargetEstimator, ABC):
    target_id = TargetId.CHUDAN_SOLAR_PLEXUS


class GedanTargetEstimator(TargetEstimator, ABC):
    """Implementations must select one explicit Gedan TargetId."""


class LockedTargetController:
    """Deterministic lifecycle and fixed-axis repetition target policy."""

    def __init__(self) -> None:
        self.state = TargetLifecycleState.UNINITIALIZED
        self.neutral: NeutralBodyReference | None = None
        self.estimate: TargetEstimate | None = None
        self._locked_origin: FramedPoint2D | None = None

    def start_collecting(self) -> None:
        if self.state != TargetLifecycleState.UNINITIALIZED:
            raise ValueError("Neutral collection can only start when uninitialized")
        self.state = TargetLifecycleState.COLLECTING_NEUTRAL_REFERENCE

    def accept_neutral(
        self, neutral: NeutralBodyReference, estimator: TargetEstimator
    ) -> None:
        if self.state != TargetLifecycleState.COLLECTING_NEUTRAL_REFERENCE:
            raise ValueError("Neutral reference is not currently being collected")
        self.neutral = neutral
        if not neutral.valid:
            self.state = TargetLifecycleState.INVALID
            return
        self.estimate = estimator.estimate(neutral)
        self.state = TargetLifecycleState.READY

    def lock_for_repetition(self) -> None:
        if self.state != TargetLifecycleState.READY or self.neutral is None:
            raise ValueError("A valid ready target is required before locking")
        self._locked_origin = self.neutral.origin
        self.state = TargetLifecycleState.LOCKED_FOR_REPETITION

    def target_for_origin(self, current_origin: FramedPoint2D) -> TargetEstimate:
        if (
            self.state != TargetLifecycleState.LOCKED_FOR_REPETITION
            or self.estimate is None
            or self._locked_origin is None
        ):
            raise ValueError("Target is not locked for a repetition")
        self._locked_origin.distance_to(current_origin)  # validates frame
        dx, dy = (
            current_origin.x - self._locked_origin.x,
            current_origin.y - self._locked_origin.y,
        )
        return _translate_estimate(self.estimate, dx, dy)

    def finish_repetition(self) -> None:
        if self.state != TargetLifecycleState.LOCKED_FOR_REPETITION:
            raise ValueError("No repetition is locked")
        self._locked_origin = None
        self.state = TargetLifecycleState.READY

    def mark_degraded(self) -> None:
        if self.state not in {
            TargetLifecycleState.READY,
            TargetLifecycleState.LOCKED_FOR_REPETITION,
        }:
            raise ValueError("Only an initialized target can become degraded")
        self.state = TargetLifecycleState.DEGRADED

    def invalidate(self) -> None:
        if self.state == TargetLifecycleState.UNINITIALIZED:
            raise ValueError("An uninitialized controller has nothing to invalidate")
        self.state = TargetLifecycleState.INVALID


def _midpoint(a: FramedPoint2D, b: FramedPoint2D) -> FramedPoint2D:
    a.distance_to(b)
    return FramedPoint2D((a.x + b.x) / 2, (a.y + b.y) / 2, a.frame)


def _median_point(points: Iterable[FramedPoint2D]) -> FramedPoint2D:
    values = list(points)
    frame = values[0].frame
    if any(point.frame != frame for point in values):
        raise ValueError("Cannot aggregate points from different coordinate frames")
    return FramedPoint2D(
        median(point.x for point in values), median(point.y for point in values), frame
    )


def _invalid_reference(
    size: FrameSize, observations: list[NeutralFrameObservation], reason: str
) -> NeutralBodyReference:
    zero = FramedPoint2D(0, 0, CoordinateFrame.SOURCE_IMAGE_PIXELS)
    interval = (
        (observations[0].frame_number, observations[-1].frame_number)
        if observations
        else (0, 0)
    )
    return NeutralBodyReference(
        str(uuid4()),
        "neutral-reference-v1",
        zero,
        (0, -1),
        zero,
        zero,
        0,
        size,
        None,
        0,
        interval,
        LandmarkQualitySummary(0, 0, (reason,)),
        0,
        False,
        reason,
    )


def _withheld(target_id: TargetId, reason: str) -> TargetEstimate:
    return TargetEstimate(
        target_id,
        None,
        None,
        None,
        CoordinateFrame.SOURCE_IMAGE_PIXELS,
        TargetSource.GENERIC_PROVISIONAL_ESTIMATE,
        0,
        None,
        None,
        "provisional-v1",
        None,
        (reason,),
        False,
        reason,
    )


def _translate_estimate(
    estimate: TargetEstimate, dx: float, dy: float
) -> TargetEstimate:
    def moved(point: FramedPoint2D | None) -> FramedPoint2D | None:
        return point.translated(dx, dy) if point else None

    return TargetEstimate(
        estimate.target_id,
        moved(estimate.centre),
        moved(estimate.lower_boundary),
        moved(estimate.upper_boundary),
        estimate.coordinate_frame,
        estimate.source,
        estimate.confidence,
        estimate.uncertainty_px,
        estimate.coaching_tolerance_px,
        estimate.estimator_version,
        estimate.neutral_reference_id,
        estimate.quality_warnings,
        estimate.coaching_allowed,
        estimate.scoring_withheld_reason,
    )
