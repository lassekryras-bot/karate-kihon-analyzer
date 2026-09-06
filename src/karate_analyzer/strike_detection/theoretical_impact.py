"""Sensor-independent theoretical-impact estimation for straight air punches.

All frame numbers are zero based.  Positions are camera-plane observations and
velocities are normalized progress per second; neither is a physical distance.
"""

from __future__ import annotations

from dataclasses import asdict, dataclass
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


@dataclass(frozen=True)
class PunchMotionSample:
    frame_number: int
    timestamp_ms: int
    forward_progress: float | None
    elbow_angle_degrees_2d: float | None = None
    minimum_landmark_visibility: float | None = None
    pose_wrist_available: bool = True
    hand_endpoint_available: bool = False
    is_missing: bool = False
    is_interpolated: bool = False
    quality_flags: tuple[str, ...] = ()
    raw_forward_progress: float | None = None
    normalized_progress_per_second: float | None = None

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
    signal_version: str = "shoulder_relative_camera_plane_v1"
    physical_contact_status: PhysicalContactStatus = PhysicalContactStatus.NOT_ASSESSED
    quality_flags: tuple[str, ...] = ()

    def __post_init__(self) -> None:
        if self.measurement_window_start_ms > self.measurement_window_end_ms:
            raise ValueError("measurement window timestamps are out of order")
        if (self.theoretical_impact_time_ms is None) != (self.impact_frame_number is None):
            raise ValueError("impact frame and time must either both be present or absent")
        if self.theoretical_impact_time_ms is not None and not (
            self.measurement_window_start_ms <= self.theoretical_impact_time_ms
            <= self.measurement_window_end_ms
        ):
            raise ValueError("theoretical impact must be inside the measurement window")
        if self.retraction_start_ms is not None and self.theoretical_impact_time_ms is not None:
            if self.retraction_start_ms < self.theoretical_impact_time_ms:
                raise ValueError("retraction cannot precede theoretical impact")

    def to_dict(self) -> dict[str, Any]:
        value = asdict(self)
        value["confidence_level"] = self.confidence_level.value
        value["physical_contact_status"] = self.physical_contact_status.value
        value["evidence_flags"] = list(self.evidence_flags)
        value["quality_flags"] = list(self.quality_flags)
        return value

    @classmethod
    def from_dict(cls, value: dict[str, Any]) -> "TheoreticalImpactEvent":
        return cls(**{
            **value,
            "confidence_level": ConfidenceLevel(value["confidence_level"]),
            "physical_contact_status": PhysicalContactStatus(value.get("physical_contact_status", "not_assessed")),
            "evidence_flags": tuple(value.get("evidence_flags", ())),
            "quality_flags": tuple(value.get("quality_flags", ())),
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
        value = asdict(self)
        value["phase"] = self.phase.value
        value["quality_flags"] = list(self.quality_flags)
        return value

    @classmethod
    def from_dict(cls, value: dict[str, Any]) -> "SelectedEventFrame":
        return cls(**{**value, "phase": EventPhase(value["phase"]), "quality_flags": tuple(value.get("quality_flags", ()))})


@dataclass(frozen=True)
class TheoreticalImpactConfig:
    smoothing_window_samples: int = 3
    near_terminal_progress_ratio: float = 0.95
    low_velocity_ratio_of_peak: float = 0.20
    confirmation_interval_ms: int = 50
    minimum_visibility: float = 0.5
    minimum_valid_samples: int = 4
    measurement_window_before_ms: int = 150
    measurement_window_after_ms: int = 100
    analysis_frame_window_ms: int = 75
    material_visibility_improvement: float = 0.15


def estimate_theoretical_impact(
    samples: Iterable[PunchMotionSample], config: TheoreticalImpactConfig | None = None
) -> tuple[TheoreticalImpactEvent, list[PunchMotionSample]]:
    """Select the earliest confirmed terminal arrival, never the end of a hold."""
    cfg = config or TheoreticalImpactConfig()
    ordered = list(samples)
    _validate_timeline(ordered)
    valid = [s for s in ordered if s.forward_progress is not None and not s.is_missing]
    if not ordered:
        raise ValueError("at least one motion sample is required")
    start_ms, end_ms = ordered[0].timestamp_ms, ordered[-1].timestamp_ms
    if len(valid) < cfg.minimum_valid_samples:
        return (_unavailable_event(start_ms, end_ms, "insufficient_valid_samples"), ordered)

    raw = [s.forward_progress for s in ordered]
    smooth = _centered_median(raw, cfg.smoothing_window_samples)
    velocities = _time_derivative(smooth, [s.timestamp_ms for s in ordered])
    enriched = [PunchMotionSample(**{**s.__dict__, "raw_forward_progress": s.forward_progress,
        "forward_progress": smooth[i], "normalized_progress_per_second": velocities[i]})
        for i, s in enumerate(ordered)]
    peak_i = max((i for i, v in enumerate(velocities) if v is not None), key=lambda i: velocities[i], default=None)
    peak_v = velocities[peak_i] if peak_i is not None else None
    terminal = max(v for v in smooth if v is not None)
    initial = min(v for v in smooth if v is not None)
    threshold = initial + (terminal - initial) * cfg.near_terminal_progress_ratio
    low_v = max(0.02, (peak_v or 0.0) * cfg.low_velocity_ratio_of_peak)
    chosen = None
    turning = False
    for i, sample in enumerate(enriched):
        velocity = velocities[i]
        if (
            i > (peak_i or 0)
            and velocity is not None
            and velocity < 0
            and enriched[i - 1].forward_progress is not None
            and enriched[i - 1].forward_progress >= threshold
            and enriched[i - 1].pose_wrist_available
            and not enriched[i - 1].is_interpolated
        ):
            # Backward differences reveal the reversal one sample after the
            # positional turning point; preserve the earlier decoded arrival.
            chosen = i - 1
            turning = True
            break
        if i < (peak_i or 0) or sample.forward_progress is None or sample.forward_progress < threshold:
            continue
        quality_ok = sample.pose_wrist_available and not sample.is_interpolated and (
            sample.minimum_landmark_visibility is None or sample.minimum_landmark_visibility >= cfg.minimum_visibility)
        if not quality_ok or velocity is None or velocity > low_v:
            continue
        turning = velocity <= 0
        horizon = sample.timestamp_ms + cfg.confirmation_interval_ms
        future = [x for x in enriched[i:] if x.timestamp_ms <= horizon and x.forward_progress is not None]
        confirmed = bool(future) and future[-1].timestamp_ms >= min(horizon, end_ms) and all(
            x.forward_progress >= threshold for x in future)
        # A reversal is self-confirming; requiring a plateau horizon would move it.
        if turning or confirmed:
            chosen = i
            break
    if chosen is None:
        return (_unavailable_event(start_ms, end_ms, "no_credible_terminal_arrival", peak_i, enriched), enriched)

    impact = enriched[chosen]
    retract_i = next((i for i in range(chosen, len(velocities)) if velocities[i] is not None and velocities[i] < -low_v), None)
    confidence = ConfidenceLevel.HIGH
    quality_flags = tuple(sorted({flag for s in enriched for flag in s.quality_flags}))
    if any(s.is_missing or s.is_interpolated for s in enriched) or quality_flags:
        confidence = ConfidenceLevel.MEDIUM
    evidence = ("peak_forward_velocity_precedes_arrival", "immediate_reversal" if turning else "confirmed_terminal_plateau", "required_pose_quality_met")
    event = TheoreticalImpactEvent(
        impact.timestamp_ms, impact.frame_number, "decoded_sample_arrival_v1", confidence,
        evidence, enriched[peak_i].timestamp_ms if peak_i is not None else None,
        enriched[peak_i].frame_number if peak_i is not None else None,
        None if turning else impact.timestamp_ms,
        enriched[retract_i].timestamp_ms if retract_i is not None else None,
        max(start_ms, impact.timestamp_ms - cfg.measurement_window_before_ms),
        min(end_ms, impact.timestamp_ms + cfg.measurement_window_after_ms),
        quality_flags=quality_flags,
    )
    return event, enriched


def select_analysis_frame(event: TheoreticalImpactEvent, samples: Iterable[PunchMotionSample], config: TheoreticalImpactConfig | None = None) -> SelectedEventFrame | None:
    if event.impact_frame_number is None or event.theoretical_impact_time_ms is None:
        return None
    cfg = config or TheoreticalImpactConfig()
    candidates = [s for s in samples if abs(s.timestamp_ms - event.theoretical_impact_time_ms) <= cfg.analysis_frame_window_ms]
    impact = next(s for s in candidates if s.frame_number == event.impact_frame_number)
    valid = [s for s in candidates if s.pose_wrist_available and not s.is_missing and not s.is_interpolated and
             (event.terminal_pose_start_ms is None or s.timestamp_ms <= event.terminal_pose_start_ms)]
    best = max(valid, key=lambda s: (s.minimum_landmark_visibility or 0.0, -abs(s.timestamp_ms-event.theoretical_impact_time_ms)), default=impact)
    impact_q = impact.minimum_landmark_visibility or 0.0
    if (best.minimum_landmark_visibility or 0.0) < impact_q + cfg.material_visibility_improvement:
        best = impact
    phase = EventPhase.IMPACT if best.frame_number == impact.frame_number else EventPhase.TERMINAL_TRANSITION
    reason = "theoretical_impact_frame" if phase is EventPhase.IMPACT else "improved_required_landmark_visibility"
    return SelectedEventFrame(best.frame_number, best.timestamp_ms, best.timestamp_ms-event.theoretical_impact_time_ms, phase, reason, best.quality_flags)


def select_snapshot_frame(analysis_frame: SelectedEventFrame | None) -> SelectedEventFrame | None:
    if analysis_frame is None:
        return None
    return SelectedEventFrame(analysis_frame.frame_number, analysis_frame.timestamp_ms, analysis_frame.offset_from_impact_ms, analysis_frame.phase, "same_as_analysis_v1", analysis_frame.quality_flags)


def build_motion_samples(raw_frames: Iterable[dict[str, Any]], side: str, *, start_frame: int | None = None, end_frame: int | None = None, analysis_width: int = 1, analysis_height: int = 1) -> list[PunchMotionSample]:
    """Build shoulder-relative, torso-normalized analysis-pixel progress.

    ``analysis_width`` and ``analysis_height`` must be the dimensions from the
    recording's :class:`FrameGeometry`.  The unit defaults retain compatibility
    for isolated normalized-coordinate fixtures; production orchestration
    always supplies the recorded dimensions.
    """
    if analysis_width <= 0 or analysis_height <= 0:
        raise ValueError("analysis dimensions must be positive")
    indices = (11, 13, 15, 12) if side == "left" else (12, 14, 16, 11) if side == "right" else None
    if indices is None:
        raise ValueError("side must be 'left' or 'right'")
    observations = []
    for frame in raw_frames:
        number = frame.get("frame_number")
        if number is None or (start_frame is not None and number < start_frame) or (end_frame is not None and number > end_frame):
            continue
        pose_list = frame.get("poses") or frame.get("pose_landmarks") or []
        if pose_list and isinstance(pose_list[0], list): pose_list = pose_list[0]
        pose = {p.get("index", i): p for i, p in enumerate(pose_list)}
        shoulder, elbow, wrist, opposite_shoulder = (
            pose.get(indices[0]), pose.get(indices[1]), pose.get(indices[2]), pose.get(indices[3])
        )
        timestamp = frame.get("timestamp_ms")
        if timestamp is None: timestamp = round(float(frame.get("timestamp_seconds", 0)) * 1000)
        point = None if not shoulder or not wrist else ((float(wrist["x"])-float(shoulder["x"]))*analysis_width, (float(wrist["y"])-float(shoulder["y"]))*analysis_height)
        visibility = None if not shoulder or not wrist else min(float(shoulder.get("visibility", 0)), float(wrist.get("visibility", 0)))
        angle = _angle_2d(shoulder, elbow, wrist, analysis_width, analysis_height)
        torso_scale = None if not shoulder or not opposite_shoulder else math.hypot(
            (float(opposite_shoulder["x"])-float(shoulder["x"]))*analysis_width,
            (float(opposite_shoulder["y"])-float(shoulder["y"]))*analysis_height,
        )
        observations.append((int(number), int(timestamp), point, visibility, angle, torso_scale))
    valid_points = [o[2] for o in observations if o[2] is not None]
    if not valid_points:
        return [PunchMotionSample(n, t, None, elbow_angle_degrees_2d=a, minimum_landmark_visibility=v, pose_wrist_available=False, is_missing=True, quality_flags=("missing_pose_wrist",)) for n,t,_p,v,a,_s in observations]
    count = max(1, len(valid_points)//4)
    early = (median(p[0] for p in valid_points[:count]), median(p[1] for p in valid_points[:count]))
    late = (median(p[0] for p in valid_points[-count:]), median(p[1] for p in valid_points[-count:]))
    direction = (late[0]-early[0], late[1]-early[1]); norm = math.hypot(*direction) or 1.0
    unit = (direction[0]/norm, direction[1]/norm)
    # Shoulder width is the preferred torso scale; robust displacement is a safe fallback.
    torso_scales = [o[5] for o in observations if o[5] is not None and o[5] > 0]
    scale = median(torso_scales) if torso_scales else max(norm, 1e-9)
    result=[]
    for n,t,p,v,a,_torso_scale in observations:
        progress = None if p is None else ((p[0]-early[0])*unit[0]+(p[1]-early[1])*unit[1])/scale
        result.append(PunchMotionSample(n,t,progress,elbow_angle_degrees_2d=a,minimum_landmark_visibility=v,pose_wrist_available=p is not None,is_missing=p is None,quality_flags=("missing_pose_wrist",) if p is None else ()))
    return result


def _validate_timeline(samples: list[PunchMotionSample]) -> None:
    for previous, current in zip(samples, samples[1:]):
        if current.frame_number <= previous.frame_number:
            raise ValueError("frame numbers must be strictly increasing")
        if current.timestamp_ms <= previous.timestamp_ms:
            raise ValueError("timestamps must be strictly increasing")


def _angle_2d(shoulder: dict[str, Any] | None, elbow: dict[str, Any] | None,
              wrist: dict[str, Any] | None, width: int, height: int) -> float | None:
    if not shoulder or not elbow or not wrist:
        return None
    a = ((float(shoulder["x"])-float(elbow["x"]))*width, (float(shoulder["y"])-float(elbow["y"]))*height)
    b = ((float(wrist["x"])-float(elbow["x"]))*width, (float(wrist["y"])-float(elbow["y"]))*height)
    denominator = math.hypot(*a) * math.hypot(*b)
    if denominator == 0:
        return None
    return math.degrees(math.acos(max(-1.0, min(1.0, (a[0]*b[0]+a[1]*b[1])/denominator))))


def _centered_median(values: list[float | None], window: int) -> list[float | None]:
    if window < 1 or window % 2 == 0: raise ValueError("smoothing window must be a positive odd number")
    radius=window//2
    return [median(v for v in values[max(0,i-radius):i+radius+1] if v is not None) if any(v is not None for v in values[max(0,i-radius):i+radius+1]) else None for i in range(len(values))]


def _time_derivative(values: list[float | None], times: list[int]) -> list[float | None]:
    result=[]
    for i, value in enumerate(values):
        if value is None or i == 0: result.append(None); continue
        previous = next((j for j in range(i-1,-1,-1) if values[j] is not None), None)
        result.append(None if previous is None else (value-values[previous])/((times[i]-times[previous])/1000.0))
    return result


def _unavailable_event(start: int, end: int, reason: str, peak_i: int | None = None, samples: list[PunchMotionSample] | None = None) -> TheoreticalImpactEvent:
    peak = samples[peak_i] if samples is not None and peak_i is not None else None
    return TheoreticalImpactEvent(None, None, "unavailable_v1", ConfidenceLevel.LOW, (reason,), peak.timestamp_ms if peak else None, peak.frame_number if peak else None, None, None, start, end, quality_flags=(reason,))
