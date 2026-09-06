"""Timestamp-based theoretical-impact estimation for straight air punches.

The production phase signal is the derivative of shoulder-to-wrist *reach* in
analysis-image pixels, divided by one locked scale.  It is therefore positive
for extension in either mirrored orientation and negative for retraction.  The
module deliberately has no dependency on plotting or rendering code.
"""

from __future__ import annotations

from dataclasses import asdict, dataclass, replace
from enum import Enum
import math
from statistics import median
from typing import Any, Iterable


class ConfidenceLevel(str, Enum):
    HIGH = "high"
    MEDIUM = "medium"
    LOW = "low"


class PhysicalContactStatus(str, Enum):
    NOT_ASSESSED = "not_assessed"
    NOT_DETECTED = "not_detected"
    DETECTED = "detected"


class EventPhase(str, Enum):
    IMPACT = "impact"
    TERMINAL_TRANSITION = "terminal_transition"
    TERMINAL_POSE = "terminal_pose"


class TerminalConfirmationStatus(str, Enum):
    CONFIRMED = "confirmed"
    PARTIAL = "partial"
    CONTRADICTED = "contradicted"
    NOT_ASSESSED = "not_assessed"


@dataclass(frozen=True)
class PunchMotionSample:
    frame_number: int
    timestamp_ms: int
    forward_progress: float | None
    elbow_angle_degrees_2d: float | None = None
    extension_ratio: float | None = None
    minimum_landmark_visibility: float | None = None
    pose_wrist_available: bool = True
    required_landmarks_available: bool = True
    hand_endpoint_available: bool = False
    is_missing: bool = False
    is_interpolated: bool = False
    quality_flags: tuple[str, ...] = ()
    raw_forward_progress: float | None = None
    normalized_progress_per_second: float | None = None
    scale_strategy: str = "pre_normalized_stable_scale"
    normalization_scale_analysis_pixels: float | None = None
    signed_elbow_displacement: float | None = None
    elbow_forward_velocity: float | None = None
    cross_body_opposition_distance: float | None = None
    cross_body_opposition_velocity: float | None = None
    forward_orientation_sign: int | None = None

    def __post_init__(self) -> None:
        if self.frame_number < 0 or self.timestamp_ms < 0:
            raise ValueError("frame_number and timestamp_ms must be non-negative")

    def to_dict(self) -> dict[str, Any]:
        value = asdict(self)
        value["quality_flags"] = list(self.quality_flags)
        return value

    @classmethod
    def from_dict(cls, value: dict[str, Any]) -> "PunchMotionSample":
        return cls(**{**value, "quality_flags": tuple(value.get("quality_flags", ()))})


@dataclass(frozen=True)
class TerminalConfirmation:
    status: TerminalConfirmationStatus
    confirmation_window_end_ms: int | None
    supporting_signal_count: int
    available_signal_count: int
    elbow_displacement_at_impact: float | None = None
    elbow_displacement_reference_max: float | None = None
    elbow_terminal_arrival_time_ms: int | None = None
    elbow_terminal_arrival_offset_ms: int | None = None
    elbow_peak_forward_velocity_time_ms: int | None = None
    elbow_peak_forward_velocity_offset_ms: int | None = None
    elbow_forward_velocity_at_impact: float | None = None
    cross_body_distance_at_impact: float | None = None
    cross_body_distance_reference_max: float | None = None
    cross_body_terminal_arrival_time_ms: int | None = None
    cross_body_terminal_arrival_offset_ms: int | None = None
    cross_body_velocity_at_impact: float | None = None
    evidence_flags: tuple[str, ...] = ()
    contradiction_flags: tuple[str, ...] = ()
    quality_flags: tuple[str, ...] = ()
    unavailable_reason: str | None = None
    signal_version: str = "camera_plane_terminal_confirmation_v1"

    def to_dict(self) -> dict[str, Any]:
        value = asdict(self)
        value["status"] = self.status.value
        value["evidence_flags"] = list(self.evidence_flags)
        value["contradiction_flags"] = list(self.contradiction_flags)
        value["quality_flags"] = list(self.quality_flags)
        return value

    @classmethod
    def from_dict(cls, value: dict[str, Any]) -> "TerminalConfirmation":
        return cls(
            **{
                **value,
                "status": TerminalConfirmationStatus(value["status"]),
                "evidence_flags": tuple(value.get("evidence_flags", ())),
                "contradiction_flags": tuple(value.get("contradiction_flags", ())),
                "quality_flags": tuple(value.get("quality_flags", ())),
            }
        )


@dataclass(frozen=True)
class TheoreticalImpactEvent:
    theoretical_impact_time_ms: int | None
    impact_frame_number: int | None
    estimation_method: str
    confidence_level: ConfidenceLevel
    evidence_flags: tuple[str, ...]
    peak_forward_velocity_time_ms: int | None
    peak_forward_velocity_frame_number: int | None
    terminal_pose_start_ms: int | None
    retraction_start_ms: int | None
    measurement_window_start_ms: int
    measurement_window_end_ms: int
    candidate_peak_frame_number: int | None = None
    unavailable_reason: str | None = None
    signal_version: str = "analysis_pixel_shoulder_wrist_reach_v2"
    scale_strategy: str = "robust_event_median_shoulder_width"
    physical_contact_status: PhysicalContactStatus = PhysicalContactStatus.NOT_ASSESSED
    quality_flags: tuple[str, ...] = ()
    outward_motion_onset_ms: int | None = None
    braking_phase_start_ms: int | None = None
    velocity_noise_floor_per_second: float | None = None
    normalization_scale_analysis_pixels: float | None = None
    terminal_confirmation: TerminalConfirmation | None = None

    def __post_init__(self) -> None:
        if self.measurement_window_start_ms > self.measurement_window_end_ms:
            raise ValueError("measurement window timestamps are out of order")
        if (self.theoretical_impact_time_ms is None) != (self.impact_frame_number is None):
            raise ValueError("impact frame and time must either both be present or absent")
        if self.theoretical_impact_time_ms is not None and not (
            self.measurement_window_start_ms <= self.theoretical_impact_time_ms <= self.measurement_window_end_ms
        ):
            raise ValueError("theoretical impact must be inside the measurement window")
        if self.impact_frame_number is None and not self.unavailable_reason:
            raise ValueError("an unavailable impact requires unavailable_reason")

    def to_dict(self) -> dict[str, Any]:
        value = asdict(self)
        value["confidence_level"] = self.confidence_level.value
        value["physical_contact_status"] = self.physical_contact_status.value
        value["evidence_flags"] = list(self.evidence_flags)
        value["quality_flags"] = list(self.quality_flags)
        value["terminal_confirmation"] = (
            self.terminal_confirmation.to_dict()
            if self.terminal_confirmation is not None
            else None
        )
        return value

    @classmethod
    def from_dict(cls, value: dict[str, Any]) -> "TheoreticalImpactEvent":
        confirmation = value.get("terminal_confirmation")
        return cls(**{
            **value,
            "confidence_level": ConfidenceLevel(value["confidence_level"]),
            "physical_contact_status": PhysicalContactStatus(value.get("physical_contact_status", "not_assessed")),
            "evidence_flags": tuple(value.get("evidence_flags", ())),
            "quality_flags": tuple(value.get("quality_flags", ())),
            "terminal_confirmation": (
                TerminalConfirmation.from_dict(confirmation)
                if confirmation is not None
                else None
            ),
        })


@dataclass(frozen=True)
class SelectedEventFrame:
    frame_number: int
    timestamp_ms: int
    offset_from_impact_ms: int
    phase: EventPhase
    reason: str
    quality_flags: tuple[str, ...] = ()

    def __post_init__(self) -> None:
        if self.frame_number < 0 or self.timestamp_ms < 0:
            raise ValueError("frame_number and timestamp_ms must be non-negative")

    def to_dict(self) -> dict[str, Any]:
        value = asdict(self); value["phase"] = self.phase.value; value["quality_flags"] = list(self.quality_flags)
        return value

    @classmethod
    def from_dict(cls, value: dict[str, Any]) -> "SelectedEventFrame":
        return cls(**{**value, "phase": EventPhase(value["phase"]), "quality_flags": tuple(value.get("quality_flags", ()))})


@dataclass(frozen=True)
class TheoreticalImpactConfig:
    smoothing_window_samples: int = 3
    near_terminal_progress_ratio: float = 0.95
    near_terminal_geometry_ratio: float = 0.95
    low_velocity_ratio_of_peak: float = 0.20
    noise_floor_ratio_of_peak: float = 0.08
    confirmation_interval_ms: int = 50
    minimum_visibility: float = 0.5
    minimum_valid_samples: int = 4
    search_before_candidate_ms: int = 200
    search_after_candidate_ms: int = 100
    analysis_frame_window_ms: int = 75
    material_visibility_improvement: float = 0.15
    secondary_confirmation_window_ms: int = 75
    secondary_pre_impact_tolerance_ms: int = 35
    secondary_near_terminal_ratio: float = 0.95


def estimate_theoretical_impact(
    samples: Iterable[PunchMotionSample],
    config: TheoreticalImpactConfig | None = None,
    *,
    candidate_peak_frame_number: int | None = None,
) -> tuple[TheoreticalImpactEvent, list[PunchMotionSample]]:
    """Return the first geometry-confirmed arrival after peak outward velocity."""
    cfg = config or TheoreticalImpactConfig()
    all_samples = list(samples)
    if not all_samples:
        raise ValueError("at least one motion sample is required")
    _validate_timeline(all_samples)
    anchor_i = _anchor_index(all_samples, candidate_peak_frame_number)
    anchor = all_samples[anchor_i]
    start_ms = max(all_samples[0].timestamp_ms, anchor.timestamp_ms - cfg.search_before_candidate_ms)
    end_ms = min(all_samples[-1].timestamp_ms, anchor.timestamp_ms + cfg.search_after_candidate_ms)
    indexed = [(i, s) for i, s in enumerate(all_samples) if start_ms <= s.timestamp_ms <= end_ms]
    window = [s for _, s in indexed]
    valid = [s for s in window if _has_terminal_geometry(s) and not s.is_missing]
    if len(valid) < cfg.minimum_valid_samples:
        return _unavailable(
            start_ms,
            end_ms,
            candidate_peak_frame_number,
            "insufficient_valid_samples",
            scale_strategy=window[0].scale_strategy if window else "unknown",
            normalization_scale_analysis_pixels=(
                window[0].normalization_scale_analysis_pixels if window else None
            ),
        ), all_samples

    enriched_window = enrich_motion_samples(
        window, smoothing_window_samples=cfg.smoothing_window_samples
    )
    velocities = [s.normalized_progress_per_second for s in enriched_window]
    enriched = list(all_samples)
    for (original_i, _), sample in zip(indexed, enriched_window):
        enriched[original_i] = sample

    positive = [(i, v) for i, v in enumerate(velocities) if v is not None and v > 0]
    if not positive:
        return _unavailable(
            start_ms,
            end_ms,
            candidate_peak_frame_number,
            "no_outward_motion",
            scale_strategy=window[0].scale_strategy,
            normalization_scale_analysis_pixels=window[0].normalization_scale_analysis_pixels,
        ), enriched
    peak_i, peak_v = max(positive, key=lambda item: item[1])
    noise = max(abs(peak_v) * cfg.noise_floor_ratio_of_peak, _velocity_noise_floor(velocities, peak_v))
    low_v = max(noise, peak_v * cfg.low_velocity_ratio_of_peak)

    reaches = [s.forward_progress for s in enriched_window if s.forward_progress is not None]
    ratios = [s.extension_ratio for s in enriched_window if s.extension_ratio is not None]
    angles = [s.elbow_angle_degrees_2d for s in enriched_window if s.elbow_angle_degrees_2d is not None]
    reach_max = max(reaches)
    # Reach is a non-negative shoulder-to-wrist distance.  Compare it directly
    # with the repetition maximum: using the range inside a candidate window
    # makes the threshold depend on where that window happens to start and can
    # move arrival toward the end of a slowly drifting terminal hold.
    reach_threshold = reach_max * cfg.near_terminal_progress_ratio
    ratio_threshold = max(ratios) * cfg.near_terminal_geometry_ratio
    angle_threshold = max(angles) * cfg.near_terminal_geometry_ratio

    retract_i = _confirmed_retraction_index(velocities, enriched_window, peak_i + 1, noise, cfg.confirmation_interval_ms)
    onset_i = next(i for i, value in enumerate(velocities[:peak_i + 1]) if value is not None and value > noise)
    braking_i = next((i for i in range(peak_i + 1, len(velocities)) if velocities[i] is not None and velocities[i] < peak_v - noise), None)
    search_stop = retract_i if retract_i is not None else len(enriched_window)
    chosen: int | None = None
    reversal = False
    for i in range(peak_i + 1, search_stop + 1):
        if i >= len(enriched_window):
            break
        sample = enriched_window[i]
        previous = enriched_window[i - 1]
        velocity = velocities[i]
        if velocity is None:
            continue
        if velocity < -noise:
            # Backward differentiation exposes reversal one sample after the
            # turning point.  Select the maximum-reach side of that sign change.
            candidates = [j for j in (i - 1, i) if _terminal_quality(enriched_window[j], reach_threshold, ratio_threshold, angle_threshold, cfg)]
            if candidates:
                chosen = max(candidates, key=lambda j: (enriched_window[j].forward_progress or -math.inf, -j))
                reversal = True
            break
        if not _terminal_quality(sample, reach_threshold, ratio_threshold, angle_threshold, cfg):
            continue
        if abs(velocity) <= low_v:
            # If this is the second equal-reach sample, arrival was the previous
            # decoded frame; confirmation must never shift it to the hold's end.
            if _terminal_quality(previous, reach_threshold, ratio_threshold, angle_threshold, cfg) and (previous.forward_progress or 0) >= (sample.forward_progress or 0) - 1e-9:
                chosen = i - 1
            elif _terminal_confirmed(enriched_window, i, reach_threshold, cfg.confirmation_interval_ms):
                chosen = i
            if chosen is not None:
                break
    if chosen is None:
        return _unavailable(start_ms, end_ms, candidate_peak_frame_number, "no_credible_terminal_arrival", enriched_window[peak_i], window[0].scale_strategy), enriched

    impact = enriched_window[chosen]
    confirmation = evaluate_terminal_confirmation(
        enriched_window,
        impact_index=chosen,
        wrist_peak_velocity_index=peak_i,
        config=cfg,
    )
    if confirmation.status is TerminalConfirmationStatus.CONTRADICTED:
        return _unavailable(
            start_ms,
            end_ms,
            candidate_peak_frame_number,
            "secondary_motion_contradicts_terminal_arrival",
            enriched_window[peak_i],
            window[0].scale_strategy,
            terminal_confirmation=confirmation,
        ), enriched
    flags = tuple(sorted({flag for s in enriched_window for flag in s.quality_flags}))
    confidence = ConfidenceLevel.MEDIUM if flags or any(s.is_interpolated for s in enriched_window) else ConfidenceLevel.HIGH
    if confirmation.status is not TerminalConfirmationStatus.CONFIRMED:
        confidence = ConfidenceLevel.MEDIUM
    terminal_start = None if reversal else impact.timestamp_ms
    evidence_flags = (
        "peak_outward_velocity_precedes_arrival",
        "immediate_reversal" if reversal else "confirmed_terminal_plateau",
        "repetition_relative_terminal_geometry",
        *confirmation.evidence_flags,
    )
    event = TheoreticalImpactEvent(
        impact.timestamp_ms, impact.frame_number, "signed_outward_velocity_terminal_geometry_v2", confidence,
        evidence_flags,
        enriched_window[peak_i].timestamp_ms, enriched_window[peak_i].frame_number,
        terminal_start, enriched_window[retract_i].timestamp_ms if retract_i is not None else None,
        start_ms, end_ms, candidate_peak_frame_number=candidate_peak_frame_number,
        scale_strategy=impact.scale_strategy, quality_flags=flags,
        outward_motion_onset_ms=enriched_window[onset_i].timestamp_ms,
        braking_phase_start_ms=enriched_window[braking_i].timestamp_ms if braking_i is not None else None,
        velocity_noise_floor_per_second=noise,
        normalization_scale_analysis_pixels=impact.normalization_scale_analysis_pixels,
        terminal_confirmation=confirmation,
    )
    return event, enriched


def select_analysis_frame(event: TheoreticalImpactEvent, samples: Iterable[PunchMotionSample], config: TheoreticalImpactConfig | None = None) -> SelectedEventFrame | None:
    if event.impact_frame_number is None or event.theoretical_impact_time_ms is None:
        return None
    cfg = config or TheoreticalImpactConfig()
    candidates = [s for s in samples if abs(s.timestamp_ms-event.theoretical_impact_time_ms) <= cfg.analysis_frame_window_ms]
    impact = next(s for s in candidates if s.frame_number == event.impact_frame_number)
    # Never substitute an established-retraction frame.
    valid = [s for s in candidates if s.required_landmarks_available and not s.is_missing and not s.is_interpolated and
             (s.normalized_progress_per_second is None or s.normalized_progress_per_second >= 0 or
              (event.velocity_noise_floor_per_second is not None and abs(s.normalized_progress_per_second) <= event.velocity_noise_floor_per_second)) and
             (event.retraction_start_ms is None or s.timestamp_ms < event.retraction_start_ms)]
    best = max(valid, key=lambda s: (s.minimum_landmark_visibility or 0.0, -abs(s.timestamp_ms-event.theoretical_impact_time_ms)), default=impact)
    if (best.minimum_landmark_visibility or 0) < (impact.minimum_landmark_visibility or 0) + cfg.material_visibility_improvement:
        best = impact
    phase = EventPhase.IMPACT if best.frame_number == impact.frame_number else EventPhase.TERMINAL_TRANSITION
    return SelectedEventFrame(best.frame_number, best.timestamp_ms, best.timestamp_ms-event.theoretical_impact_time_ms, phase,
                              "theoretical_impact_frame" if phase is EventPhase.IMPACT else "bounded_landmark_quality_fallback", best.quality_flags)


def select_snapshot_frame(analysis_frame: SelectedEventFrame | None) -> SelectedEventFrame | None:
    if analysis_frame is None:
        return None
    return SelectedEventFrame(analysis_frame.frame_number, analysis_frame.timestamp_ms, analysis_frame.offset_from_impact_ms, analysis_frame.phase, "same_as_analysis_v1", analysis_frame.quality_flags)


def build_motion_samples(raw_frames: Iterable[dict[str, Any]], side: str, *, start_frame: int | None = None, end_frame: int | None = None, analysis_width: int = 1, analysis_height: int = 1, minimum_visibility: float = 0.5) -> list[PunchMotionSample]:
    """Build mirror-invariant reach using analysis pixels and one locked scale."""
    if analysis_width <= 0 or analysis_height <= 0:
        raise ValueError("analysis dimensions must be positive")
    if not 0 <= minimum_visibility <= 1:
        raise ValueError("minimum visibility must be between 0 and 1")
    indices = (11, 13, 15, 12, 14) if side == "left" else (12, 14, 16, 11, 13) if side == "right" else None
    if indices is None:
        raise ValueError("side must be 'left' or 'right'")
    observations: list[dict[str, Any]] = []
    for frame in raw_frames:
        number = frame.get("frame_number")
        if number is None or start_frame is not None and number < start_frame or end_frame is not None and number > end_frame:
            continue
        landmarks = frame.get("poses") or frame.get("pose_landmarks") or []
        if landmarks and isinstance(landmarks[0], list): landmarks = landmarks[0]
        pose = {p.get("index", i): p for i, p in enumerate(landmarks)}
        shoulder, elbow, wrist, other_shoulder, opposite_elbow = (pose.get(i) for i in indices)
        timestamp = frame.get("timestamp_ms")
        if timestamp is None: timestamp = round(float(frame.get("timestamp_seconds", 0))*1000)
        required = shoulder is not None and elbow is not None and wrist is not None
        reach = _distance(shoulder, wrist, analysis_width, analysis_height)
        upper = _distance(shoulder, elbow, analysis_width, analysis_height)
        forearm = _distance(elbow, wrist, analysis_width, analysis_height)
        ratio = reach/(upper+forearm) if reach is not None and upper and forearm else None
        scale = _distance(shoulder, other_shoulder, analysis_width, analysis_height)
        shoulder_midpoint = _midpoint(shoulder, other_shoulder, analysis_width, analysis_height)
        hip_midpoint = _midpoint(pose.get(23), pose.get(24), analysis_width, analysis_height)
        torso_landmarks = (shoulder, other_shoulder, pose.get(23), pose.get(24))
        elbow_coordinate_available = _landmarks_visible(
            (*torso_landmarks, elbow), minimum_visibility
        )
        wrist_coordinate_available = _landmarks_visible(
            (*torso_landmarks, wrist), minimum_visibility
        )
        opposition_available = _landmarks_visible(
            (opposite_elbow, wrist), minimum_visibility
        )
        scale_available = _landmarks_visible(
            (shoulder, other_shoulder), minimum_visibility
        )
        visibility = min((float(p.get("visibility", 0)) for p in (shoulder, elbow, wrist) if p), default=None)
        observations.append(
            {
                "frame_number": int(number),
                "timestamp_ms": int(timestamp),
                "reach": reach,
                "extension_ratio": ratio,
                "elbow_angle": _angle_2d(shoulder, elbow, wrist, analysis_width, analysis_height),
                "visibility": visibility,
                "scale": scale if scale_available else None,
                "required": required,
                "raw_elbow_offset": (
                    _signed_line_offset(
                        elbow, shoulder_midpoint, hip_midpoint, analysis_width, analysis_height
                    )
                    if elbow_coordinate_available
                    else None
                ),
                "raw_wrist_offset": (
                    _signed_line_offset(
                        wrist, shoulder_midpoint, hip_midpoint, analysis_width, analysis_height
                    )
                    if wrist_coordinate_available
                    else None
                ),
                "cross_body_distance": (
                    _distance(opposite_elbow, wrist, analysis_width, analysis_height)
                    if opposition_available
                    else None
                ),
            }
        )
    scales = [o["scale"] for o in observations if o["scale"] is not None and o["scale"] > 0]
    if not scales:
        # No moving per-frame divisor: a robust reach range is the explicit fallback.
        reaches = [o["reach"] for o in observations if o["reach"] is not None]
        locked_scale = max((max(reaches)-min(reaches)) if reaches else 0, 1e-9)
    else:
        locked_scale = median(scales)
    orientation_candidates = sorted(
        (
            (observation["reach"], observation["raw_wrist_offset"])
            for observation in observations
            if observation["reach"] is not None
            and observation["raw_wrist_offset"] is not None
        ),
        reverse=True,
    )
    if orientation_candidates:
        count = min(
            len(orientation_candidates), max(5, len(orientation_candidates) // 10)
        )
        terminal_offset = median(
            offset for _reach, offset in orientation_candidates[:count]
        )
        forward_sign = -1 if terminal_offset < 0 else 1
    else:
        forward_sign = 1
    result = []
    for observation in observations:
        required = observation["required"]
        flags = () if required else ("missing_required_arm_landmark",)
        scope = "video" if start_frame is None and end_frame is None else "event"
        strategy = f"robust_{scope}_median_shoulder_width" if scales else f"robust_{scope}_reach_range_fallback"
        result.append(PunchMotionSample(
            observation["frame_number"],
            observation["timestamp_ms"],
            None if observation["reach"] is None else observation["reach"] / locked_scale,
            observation["elbow_angle"],
            observation["extension_ratio"],
            observation["visibility"],
            required,
            required_landmarks_available=required,
            is_missing=not required,
            quality_flags=flags,
            scale_strategy=strategy,
            normalization_scale_analysis_pixels=locked_scale,
            signed_elbow_displacement=(
                None
                if observation["raw_elbow_offset"] is None
                else forward_sign * observation["raw_elbow_offset"] / locked_scale
            ),
            cross_body_opposition_distance=(
                None
                if observation["cross_body_distance"] is None
                else observation["cross_body_distance"] / locked_scale
            ),
            forward_orientation_sign=forward_sign,
        ))
    return result


def enrich_motion_samples(
    samples: Iterable[PunchMotionSample], *, smoothing_window_samples: int = 3
) -> list[PunchMotionSample]:
    """Add the canonical smoothed reach and signed outward velocity signal.

    This pure helper is shared by event selection and diagnostic plots so a
    plotted value has the same phase meaning and derivative convention as the
    production estimator.
    """

    source = list(samples)
    if not source:
        return []
    _validate_timeline(source)
    smoothed = _centered_median(
        [sample.forward_progress for sample in source], smoothing_window_samples
    )
    velocities = _time_derivative(
        smoothed, [sample.timestamp_ms for sample in source]
    )
    smoothed_elbow = _centered_median(
        [sample.signed_elbow_displacement for sample in source],
        smoothing_window_samples,
    )
    elbow_velocities = _time_derivative(
        smoothed_elbow, [sample.timestamp_ms for sample in source]
    )
    smoothed_opposition = _centered_median(
        [sample.cross_body_opposition_distance for sample in source],
        smoothing_window_samples,
    )
    opposition_velocities = _time_derivative(
        smoothed_opposition, [sample.timestamp_ms for sample in source]
    )
    return [
        PunchMotionSample(
            **{
                **sample.__dict__,
                "raw_forward_progress": sample.forward_progress,
                "forward_progress": smoothed[index],
                "normalized_progress_per_second": velocities[index],
                "signed_elbow_displacement": smoothed_elbow[index],
                "elbow_forward_velocity": elbow_velocities[index],
                "cross_body_opposition_distance": smoothed_opposition[index],
                "cross_body_opposition_velocity": opposition_velocities[index],
            }
        )
        for index, sample in enumerate(source)
    ]


def evaluate_terminal_confirmation(
    samples: Iterable[PunchMotionSample],
    *,
    impact_index: int,
    wrist_peak_velocity_index: int,
    config: TheoreticalImpactConfig | None = None,
) -> TerminalConfirmation:
    """Confirm a wrist-selected arrival without moving its timestamp."""

    cfg = config or TheoreticalImpactConfig()
    window = list(samples)
    if not 0 <= impact_index < len(window):
        raise ValueError("impact_index is outside the confirmation samples")
    if not 0 <= wrist_peak_velocity_index <= impact_index:
        raise ValueError("wrist peak velocity must not follow impact")
    impact = window[impact_index]
    confirmation_end = impact.timestamp_ms + cfg.secondary_confirmation_window_ms
    confirmation_indices = [
        index
        for index in range(wrist_peak_velocity_index, len(window))
        if impact.timestamp_ms - cfg.secondary_pre_impact_tolerance_ms
        <= window[index].timestamp_ms
        <= confirmation_end
    ]

    elbow_values = [
        sample.signed_elbow_displacement
        for sample in window
        if sample.signed_elbow_displacement is not None
    ]
    opposition_values = [
        sample.cross_body_opposition_distance
        for sample in window
        if sample.cross_body_opposition_distance is not None
    ]
    elbow_threshold = None
    elbow_max = max(elbow_values) if elbow_values else None
    if elbow_max is not None and elbow_max > 0:
        elbow_threshold = elbow_max * cfg.secondary_near_terminal_ratio
    opposition_max = max(opposition_values) if opposition_values else None
    opposition_threshold = (
        opposition_max * cfg.secondary_near_terminal_ratio
        if opposition_max is not None
        else None
    )

    elbow_arrival = _first_signal_arrival(
        window,
        confirmation_indices,
        "signed_elbow_displacement",
        elbow_threshold,
    )
    opposition_arrival = _first_signal_arrival(
        window,
        confirmation_indices,
        "cross_body_opposition_distance",
        opposition_threshold,
    )
    elbow_positive = [
        (index, sample.elbow_forward_velocity)
        for index, sample in enumerate(window[: impact_index + 1])
        if sample.elbow_forward_velocity is not None
        and sample.elbow_forward_velocity > 0
    ]
    elbow_peak_index = (
        max(elbow_positive, key=lambda item: item[1])[0]
        if elbow_positive
        else None
    )
    elbow_peak_velocity = (
        window[elbow_peak_index].elbow_forward_velocity
        if elbow_peak_index is not None
        else None
    )
    opposition_positive = [
        sample.cross_body_opposition_velocity
        for sample in window[: impact_index + 1]
        if sample.cross_body_opposition_velocity is not None
        and sample.cross_body_opposition_velocity > 0
    ]
    opposition_peak_velocity = max(opposition_positive, default=None)
    elbow_noise = (
        abs(elbow_peak_velocity) * cfg.noise_floor_ratio_of_peak
        if elbow_peak_velocity is not None
        else None
    )
    opposition_noise = (
        abs(opposition_peak_velocity) * cfg.noise_floor_ratio_of_peak
        if opposition_peak_velocity is not None
        else None
    )

    evidence: list[str] = []
    if elbow_peak_index is not None and elbow_peak_index < impact_index:
        evidence.append("elbow_forward_motion_precedes_wrist_arrival")
    if elbow_arrival is not None:
        evidence.append("elbow_displacement_near_terminal")
    if opposition_arrival is not None:
        evidence.append("cross_body_opposition_near_terminal")

    available = int(bool(elbow_values)) + int(bool(opposition_values))
    supporting = int(elbow_arrival is not None) + int(opposition_arrival is not None)
    elbow_below = (
        elbow_threshold is not None
        and impact.signed_elbow_displacement is not None
        and impact.signed_elbow_displacement < elbow_threshold
    )
    opposition_below = (
        opposition_threshold is not None
        and impact.cross_body_opposition_distance is not None
        and impact.cross_body_opposition_distance < opposition_threshold
    )
    elbow_retracting = (
        elbow_noise is not None
        and impact.elbow_forward_velocity is not None
        and impact.elbow_forward_velocity < -elbow_noise
    )
    opposition_closing = (
        opposition_noise is not None
        and impact.cross_body_opposition_velocity is not None
        and impact.cross_body_opposition_velocity < -opposition_noise
    )
    contradiction_flags: list[str] = []
    if (
        available == 2
        and supporting == 0
        and elbow_below
        and opposition_below
        and elbow_retracting
        and opposition_closing
    ):
        contradiction_flags.extend(
            (
                "elbow_retracting_below_terminal_region",
                "cross_body_opposition_closing_below_terminal_region",
            )
        )

    quality_flags: list[str] = []
    if not elbow_values:
        quality_flags.append("elbow_confirmation_unavailable")
    if not opposition_values:
        quality_flags.append("cross_body_confirmation_unavailable")
    if contradiction_flags:
        status = TerminalConfirmationStatus.CONTRADICTED
        unavailable_reason = "secondary_signals_indicate_retraction"
    elif available == 2 and supporting == 2 and elbow_peak_index is not None:
        status = TerminalConfirmationStatus.CONFIRMED
        unavailable_reason = None
    elif available:
        status = TerminalConfirmationStatus.PARTIAL
        unavailable_reason = None
    else:
        status = TerminalConfirmationStatus.NOT_ASSESSED
        unavailable_reason = "secondary_signals_unavailable"

    return TerminalConfirmation(
        status=status,
        confirmation_window_end_ms=confirmation_end,
        supporting_signal_count=supporting,
        available_signal_count=available,
        elbow_displacement_at_impact=impact.signed_elbow_displacement,
        elbow_displacement_reference_max=elbow_max,
        elbow_terminal_arrival_time_ms=(
            window[elbow_arrival].timestamp_ms if elbow_arrival is not None else None
        ),
        elbow_terminal_arrival_offset_ms=(
            window[elbow_arrival].timestamp_ms - impact.timestamp_ms
            if elbow_arrival is not None
            else None
        ),
        elbow_peak_forward_velocity_time_ms=(
            window[elbow_peak_index].timestamp_ms
            if elbow_peak_index is not None
            else None
        ),
        elbow_peak_forward_velocity_offset_ms=(
            window[elbow_peak_index].timestamp_ms - impact.timestamp_ms
            if elbow_peak_index is not None
            else None
        ),
        elbow_forward_velocity_at_impact=impact.elbow_forward_velocity,
        cross_body_distance_at_impact=impact.cross_body_opposition_distance,
        cross_body_distance_reference_max=opposition_max,
        cross_body_terminal_arrival_time_ms=(
            window[opposition_arrival].timestamp_ms
            if opposition_arrival is not None
            else None
        ),
        cross_body_terminal_arrival_offset_ms=(
            window[opposition_arrival].timestamp_ms - impact.timestamp_ms
            if opposition_arrival is not None
            else None
        ),
        cross_body_velocity_at_impact=impact.cross_body_opposition_velocity,
        evidence_flags=tuple(evidence),
        contradiction_flags=tuple(contradiction_flags),
        quality_flags=tuple(quality_flags),
        unavailable_reason=unavailable_reason,
    )


def _distance(a: dict[str, Any] | None, b: dict[str, Any] | None, width: int, height: int) -> float | None:
    if not a or not b: return None
    return math.hypot((float(b["x"])-float(a["x"]))*width, (float(b["y"])-float(a["y"]))*height)


def _midpoint(
    a: dict[str, Any] | None,
    b: dict[str, Any] | None,
    width: int,
    height: int,
) -> tuple[float, float] | None:
    if not a or not b:
        return None
    return (
        (float(a["x"]) + float(b["x"])) * width / 2,
        (float(a["y"]) + float(b["y"])) * height / 2,
    )


def _signed_line_offset(
    point: dict[str, Any] | None,
    line_start: tuple[float, float] | None,
    line_end: tuple[float, float] | None,
    width: int,
    height: int,
) -> float | None:
    if not point or line_start is None or line_end is None:
        return None
    line = (line_end[0] - line_start[0], line_end[1] - line_start[1])
    line_length = math.hypot(*line)
    if line_length == 0:
        return None
    point_from_start = (
        float(point["x"]) * width - line_start[0],
        float(point["y"]) * height - line_start[1],
    )
    return (
        line[0] * point_from_start[1] - line[1] * point_from_start[0]
    ) / line_length


def _landmarks_visible(
    landmarks: Iterable[dict[str, Any] | None], minimum_visibility: float
) -> bool:
    values = list(landmarks)
    return all(
        landmark is not None
        and float(landmark.get("visibility", 0.0)) >= minimum_visibility
        for landmark in values
    )


def _angle_2d(shoulder: dict[str, Any] | None, elbow: dict[str, Any] | None, wrist: dict[str, Any] | None, width: int, height: int) -> float | None:
    if not shoulder or not elbow or not wrist: return None
    a=((float(shoulder["x"])-float(elbow["x"]))*width,(float(shoulder["y"])-float(elbow["y"]))*height)
    b=((float(wrist["x"])-float(elbow["x"]))*width,(float(wrist["y"])-float(elbow["y"]))*height)
    denominator=math.hypot(*a)*math.hypot(*b)
    return None if denominator == 0 else math.degrees(math.acos(max(-1,min(1,(a[0]*b[0]+a[1]*b[1])/denominator))))


def _has_terminal_geometry(s: PunchMotionSample) -> bool:
    return s.forward_progress is not None and s.extension_ratio is not None and s.elbow_angle_degrees_2d is not None and s.required_landmarks_available


def _terminal_quality(s: PunchMotionSample, reach: float, ratio: float, angle: float, cfg: TheoreticalImpactConfig) -> bool:
    return _has_terminal_geometry(s) and not s.is_interpolated and (s.minimum_landmark_visibility is None or s.minimum_landmark_visibility >= cfg.minimum_visibility) and s.forward_progress >= reach and s.extension_ratio >= ratio and s.elbow_angle_degrees_2d >= angle


def _terminal_confirmed(samples: list[PunchMotionSample], i: int, threshold: float, interval: int) -> bool:
    horizon=samples[i].timestamp_ms+interval
    future=[s for s in samples[i:] if s.timestamp_ms <= horizon]
    return bool(future) and future[-1].timestamp_ms >= min(horizon,samples[-1].timestamp_ms) and all(s.forward_progress is not None and s.forward_progress >= threshold for s in future)


def _confirmed_retraction_index(velocities: list[float | None], samples: list[PunchMotionSample], start: int, noise: float, interval: int) -> int | None:
    for i in range(start,len(samples)):
        if velocities[i] is None or velocities[i] >= -noise: continue
        horizon=samples[i].timestamp_ms+interval
        future=[velocities[j] for j in range(i,len(samples)) if samples[j].timestamp_ms <= horizon and velocities[j] is not None]
        if future and samples[min(len(samples)-1, i+len(future)-1)].timestamp_ms >= min(horizon,samples[-1].timestamp_ms) and all(v < -noise for v in future): return i
    return None


def _first_signal_arrival(
    samples: list[PunchMotionSample],
    indices: list[int],
    attribute: str,
    threshold: float | None,
) -> int | None:
    if threshold is None:
        return None
    return next(
        (
            index
            for index in indices
            if getattr(samples[index], attribute) is not None
            and getattr(samples[index], attribute) >= threshold
        ),
        None,
    )


def _velocity_noise_floor(values: list[float | None], peak: float) -> float:
    finite=sorted(abs(v) for v in values if v is not None and abs(v) < peak)
    # A short reversal-only trace has no genuine quiet segment.  Cap this
    # robust estimate so retraction itself cannot be mistaken for noise.
    return min(median(finite[:max(1,len(finite)//3)]) if finite else 0.0, peak * .08)


def _anchor_index(samples: list[PunchMotionSample], frame: int | None) -> int:
    if frame is None: return len(samples)-1
    try: return next(i for i,s in enumerate(samples) if s.frame_number == frame)
    except StopIteration: raise ValueError("candidate peak frame is not present in motion samples") from None


def _centered_median(values: list[float | None], window: int) -> list[float | None]:
    if window < 1 or window % 2 == 0: raise ValueError("smoothing window must be a positive odd number")
    r=window//2
    return [median(x for x in values[max(0,i-r):i+r+1] if x is not None) if any(x is not None for x in values[max(0,i-r):i+r+1]) else None for i in range(len(values))]


def _time_derivative(values: list[float | None], times: list[int]) -> list[float | None]:
    result=[]
    for i,value in enumerate(values):
        previous=next((j for j in range(i-1,-1,-1) if values[j] is not None),None)
        result.append(None if value is None or previous is None else (value-values[previous])/((times[i]-times[previous])/1000))
    return result


def _validate_timeline(samples: list[PunchMotionSample]) -> None:
    for a,b in zip(samples,samples[1:]):
        if b.frame_number <= a.frame_number: raise ValueError("frame numbers must be strictly increasing")
        if b.timestamp_ms <= a.timestamp_ms: raise ValueError("timestamps must be strictly increasing")


def _unavailable(
    start: int,
    end: int,
    candidate: int | None,
    reason: str,
    peak: PunchMotionSample | None = None,
    scale_strategy: str = "pre_normalized_stable_scale",
    normalization_scale_analysis_pixels: float | None = None,
    terminal_confirmation: TerminalConfirmation | None = None,
) -> TheoreticalImpactEvent:
    return TheoreticalImpactEvent(
        None,
        None,
        "unavailable_v2",
        ConfidenceLevel.LOW,
        (),
        peak.timestamp_ms if peak else None,
        peak.frame_number if peak else None,
        None,
        None,
        start,
        end,
        candidate_peak_frame_number=candidate,
        unavailable_reason=reason,
        scale_strategy=scale_strategy,
        quality_flags=(reason,),
        normalization_scale_analysis_pixels=(
            peak.normalization_scale_analysis_pixels
            if peak
            else normalization_scale_analysis_pixels
        ),
        terminal_confirmation=(
            terminal_confirmation
            if terminal_confirmation is not None
            else TerminalConfirmation(
                status=TerminalConfirmationStatus.NOT_ASSESSED,
                confirmation_window_end_ms=None,
                supporting_signal_count=0,
                available_signal_count=0,
                unavailable_reason="primary_impact_unavailable",
            )
        ),
    )
