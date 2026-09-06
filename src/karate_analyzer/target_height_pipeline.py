"""Diagnostic-only target-height integration for recorded pose timelines."""

from __future__ import annotations

from dataclasses import dataclass, replace
from math import acos, degrees
from statistics import median
from typing import Any

from karate_analyzer.frame_geometry import CoordinateFrame, FrameGeometry, FramedPoint2D
from karate_analyzer.targets import (
    LockedTargetController,
    NeutralFrameObservation,
    ProvisionalJodanEstimator,
    TARGET_DEFINITIONS,
    TargetId,
    build_neutral_reference,
)

LANDMARK_INDICES = (11, 12, 23, 24)


@dataclass(frozen=True)
class SetupWindowConfig:
    """Provisional, configurable setup-stability gates (selector v1)."""

    minimum_consecutive_frames: int = 5
    minimum_landmark_visibility: float = 0.5
    minimum_torso_length_px: float = 10.0
    maximum_origin_step_torso_ratio: float = 0.08
    maximum_scale_range_ratio: float = 0.10
    maximum_axis_deviation_degrees: float = 8.0
    maximum_wrist_step_torso_ratio: float = 0.18
    pre_punch_guard_frames: int = 1
    selector_version: str = "setup-window-selector-v1-provisional"

    def __post_init__(self) -> None:
        if self.minimum_consecutive_frames < 2:
            raise ValueError("minimum_consecutive_frames must be at least 2")
        if not 0 <= self.minimum_landmark_visibility <= 1:
            raise ValueError("minimum_landmark_visibility must be between 0 and 1")
        positive = (
            self.minimum_torso_length_px,
            self.maximum_origin_step_torso_ratio,
            self.maximum_scale_range_ratio,
            self.maximum_axis_deviation_degrees,
            self.maximum_wrist_step_torso_ratio,
        )
        if any(value <= 0 for value in positive):
            raise ValueError("setup geometry thresholds must be positive")
        if self.pre_punch_guard_frames < 0:
            raise ValueError("pre_punch_guard_frames must be non-negative")


@dataclass(frozen=True)
class SetupWindowResult:
    valid: bool
    start_frame: int | None
    end_frame: int | None
    start_timestamp_seconds: float | None
    end_timestamp_seconds: float | None
    contributing_frame_indices: tuple[int, ...]
    rejected_frame_count: int
    maximum_origin_step_ratio: float | None
    scale_range_ratio: float | None
    maximum_axis_deviation_degrees: float | None
    confidence: float
    warnings: tuple[str, ...]
    failure_reason: str | None
    selector_version: str

    @property
    def window_id(self) -> str | None:
        if not self.valid:
            return None
        return f"neutral-window-{self.start_frame}-{self.end_frame}-{self.selector_version}"

    def to_debug_dict(self) -> dict[str, Any]:
        return {
            "valid": self.valid,
            "window_id": self.window_id,
            "start_frame": self.start_frame,
            "end_frame": self.end_frame,
            "start_timestamp_seconds": self.start_timestamp_seconds,
            "end_timestamp_seconds": self.end_timestamp_seconds,
            "contributing_frame_indices": list(self.contributing_frame_indices),
            "rejected_frame_count": self.rejected_frame_count,
            "stability": {
                "maximum_origin_step_torso_ratio": self.maximum_origin_step_ratio,
                "scale_range_ratio": self.scale_range_ratio,
                "maximum_axis_deviation_degrees": self.maximum_axis_deviation_degrees,
            },
            "confidence": self.confidence,
            "warnings": list(self.warnings),
            "failure_reason": self.failure_reason,
            "selector_version": self.selector_version,
        }


def select_setup_window(
    frames: list[dict[str, Any]],
    geometry: FrameGeometry,
    first_punch_start_frame: int | None,
    config: SetupWindowConfig | None = None,
) -> tuple[SetupWindowResult, list[NeutralFrameObservation]]:
    """Select the earliest contiguous stable run wholly before punch motion."""

    config = config or SetupWindowConfig()
    cutoff = first_punch_start_frame
    if cutoff is None:
        return _failed(config, "MISSING_PUNCH_BOUNDARY", 0), []
    cutoff -= config.pre_punch_guard_frames
    rejected = 0
    run: list[
        tuple[
            NeutralFrameObservation,
            FramedPoint2D,
            float,
            tuple[float, float],
            dict[int, FramedPoint2D],
        ]
    ] = []
    failure_signals: set[str] = set()
    for frame in sorted(frames, key=lambda item: item.get("frame_number", -1)):
        number = frame.get("frame_number")
        if not isinstance(number, int) or number >= cutoff:
            continue
        parsed, reason = _observation(frame, geometry, config)
        if parsed is None:
            rejected += 1
            failure_signals.add(reason)
            run = []
            continue
        if run and number != run[-1][0].frame_number + 1:
            rejected += len(run)
            failure_signals.add("NON_CONTIGUOUS_USABLE_FRAMES")
            run = []
        candidate = run + [parsed]
        stable, metrics, reason = _stable(candidate, config)
        if not stable:
            rejected += max(1, len(run))
            failure_signals.add(reason)
            run = [parsed]
        else:
            run = candidate
        if len(run) >= config.minimum_consecutive_frames:
            chosen = run[-config.minimum_consecutive_frames :]
            observations = [item[0] for item in chosen]
            metrics = _stable(chosen, config)[1]
            visibility = min(min(item.visibilities) for item in observations)
            normalized_instability = max(
                metrics["origin"] / config.maximum_origin_step_torso_ratio,
                metrics["scale"] / config.maximum_scale_range_ratio,
                metrics["axis_degrees"] / config.maximum_axis_deviation_degrees,
            )
            confidence = min(visibility, max(0.0, 1.0 - normalized_instability))
            return (
                SetupWindowResult(
                    True,
                    observations[0].frame_number,
                    observations[-1].frame_number,
                    observations[0].timestamp_seconds,
                    observations[-1].timestamp_seconds,
                    tuple(item.frame_number for item in observations),
                    rejected,
                    metrics["origin"],
                    metrics["scale"],
                    metrics["axis_degrees"],
                    confidence,
                    ("PROVISIONAL_SETUP_THRESHOLDS",),
                    None,
                    config.selector_version,
                ),
                observations,
            )
    reason = _dominant_failure(failure_signals)
    return _failed(config, reason, rejected), []


def attach_target_height_diagnostics(
    frames: list[dict[str, Any]],
    events: list[dict[str, Any]],
    geometry_payload: dict[str, Any] | None,
    config: SetupWindowConfig | None = None,
) -> dict[str, Any]:
    """Attach isolated provisional target geometry without changing strike results."""

    config = config or SetupWindowConfig()
    try:
        if not isinstance(geometry_payload, dict):
            raise ValueError("MISSING_FRAME_GEOMETRY")
        geometry = FrameGeometry.from_dict(geometry_payload)
        starts = [
            e.get("strike_region_start_frame")
            for e in events
            if isinstance(e.get("strike_region_start_frame"), int)
        ]
        window, observations = select_setup_window(
            frames, geometry, min(starts) if starts else None, config
        )
        if not window.valid:
            return _abstain_events(
                events, window.failure_reason or "SETUP_WINDOW_UNAVAILABLE", window
            )
        neutral = build_neutral_reference(
            observations,
            geometry.analysis_size,
            minimum_frames=config.minimum_consecutive_frames,
            minimum_visibility=config.minimum_landmark_visibility,
        )
        neutral = replace(
            neutral,
            setup_window_id=window.window_id,
            setup_selector_version=window.selector_version,
        )
        if not neutral.valid:
            return _abstain_events(
                events,
                neutral.invalidation_reason or "INVALID_NEUTRAL_REFERENCE",
                window,
            )
        by_number = {frame.get("frame_number"): frame for frame in frames}
        neutral_debug = _neutral_debug(neutral)
        for event in events:
            controller = LockedTargetController()
            controller.start_collecting()
            controller.accept_neutral(neutral, ProvisionalJodanEstimator())
            controller.lock_for_repetition()  # each event/repetition gets a distinct lock
            analysis_frame_number = _canonical_frame_number(event, "analysis")
            current = _observation(
                by_number.get(analysis_frame_number, {}),
                geometry,
                config,
            )[0]
            if current is None:
                abstention = _abstention("UNRELIABLE_TRACKED_ORIGIN", window)
                event["target_height_diagnostic"] = abstention
                event["target_estimate"] = abstention
                event["neutral_reference"] = neutral_debug
                continue
            _, origin, _, axis, _ = current
            estimate = controller.target_for_origin(origin)
            dx, dy = origin.x - neutral.origin.x, origin.y - neutral.origin.y
            lean = _axis_angle(neutral.vertical_axis, axis)
            snapshot_frame_number = _canonical_frame_number(event, "snapshot")
            if snapshot_frame_number is None:
                snapshot_frame_number = analysis_frame_number
            overlay_warning = (
                "TARGET_DIAGNOSTIC_FRAME_MISMATCH"
                if snapshot_frame_number != analysis_frame_number
                else None
            )
            event["target_estimate"] = estimate.to_debug_dict()
            event["neutral_reference"] = neutral_debug
            event["current_torso_axis"] = _axis_debug(
                origin, axis, neutral.torso_scale_px
            )
            event["target_height_diagnostic"] = {
                **estimate.to_debug_dict(),
                "status": "PROVISIONAL_DIAGNOSTIC_ONLY",
                "definition_version": TARGET_DEFINITIONS[
                    TargetId.JODAN_CHIN
                ].definition_version,
                "neutral_reference_id": neutral.reference_id,
                "setup_window_id": window.window_id,
                "repetition_lock_id": f"target-lock-{event.get('event_index')}",
                "repetition_start_frame": event.get("strike_region_start_frame"),
                "analysis_frame_number": analysis_frame_number,
                "geometry_provenance": {
                    "measurement_frame_number": analysis_frame_number,
                    "measurement_frame_role": "analysis_frame",
                    "coordinate_frame": CoordinateFrame.SOURCE_IMAGE_PIXELS.value,
                    "transport_to_snapshot": "none",
                },
                "snapshot_overlay_allowed": overlay_warning is None,
                "snapshot_overlay_warning": overlay_warning,
                "tracked_origin_displacement": {
                    "x": dx,
                    "y": dy,
                    "coordinate_frame": CoordinateFrame.SOURCE_IMAGE_PIXELS.value,
                },
                "neutral_axis": list(neutral.vertical_axis),
                "current_torso_axis": list(axis),
                "torso_lean_difference_degrees": lean,
                "setup_window": window.to_debug_dict(),
            }
            controller.finish_repetition()
        return {
            "setup_window": window.to_debug_dict(),
            "neutral_reference": neutral_debug,
            "target_id": TargetId.JODAN_CHIN.value,
        }
    except (KeyError, TypeError, ValueError) as exc:
        reason = str(exc) or "TARGET_HEIGHT_INTEGRATION_FAILED"
        return _abstain_events(events, reason, None)


def _canonical_frame_number(event: dict[str, Any], role: str) -> int | None:
    """Read PR #83 frame objects while retaining legacy numbered events."""

    frame = event.get(f"{role}_frame")
    if isinstance(frame, dict):
        for key in ("frame_number", "frame_index"):
            if isinstance(frame.get(key), int):
                return frame[key]
    numbered = event.get(f"{role}_frame_number")
    return numbered if isinstance(numbered, int) else None


def _observation(frame, geometry, config):
    if not frame:
        return None, "MISSING_POSE"
    frame_geometry = frame.get("frame_geometry")
    if (
        frame_geometry is not None
        and FrameGeometry.from_dict(frame_geometry).analysis_size
        != geometry.analysis_size
    ):
        return None, "INCOMPATIBLE_SOURCE_DIMENSIONS"
    poses = frame.get("poses") or []
    landmarks = {item.get("index"): item for item in (poses[0] if poses else [])}
    points = {}
    visibilities = []
    for index in LANDMARK_INDICES:
        item = landmarks.get(index)
        visibility = float(item.get("visibility", 0)) if item else 0
        if (
            not item
            or item.get("x") is None
            or item.get("y") is None
            or visibility < config.minimum_landmark_visibility
        ):
            return None, "LOW_CONFIDENCE_BILATERAL_LANDMARKS"
        points[index] = geometry.normalized_analysis_to_source_point(
            float(item["x"]), float(item["y"])
        )
        visibilities.append(visibility)
    shoulder = _midpoint(points[11], points[12])
    hip = _midpoint(points[23], points[24])
    scale = shoulder.distance_to(hip)
    if scale < config.minimum_torso_length_px:
        return None, "DEGENERATE_TORSO_AXIS"
    axis = ((shoulder.x - hip.x) / scale, (shoulder.y - hip.y) / scale)
    wrists = {}
    for index in (15, 16):
        item = landmarks.get(index)
        if item and item.get("x") is not None and item.get("y") is not None:
            wrists[index] = geometry.normalized_analysis_to_source_point(
                float(item["x"]), float(item["y"])
            )
    observation = NeutralFrameObservation(
        int(frame["frame_number"]),
        points[11],
        points[12],
        points[23],
        points[24],
        tuple(visibilities),
        frame.get("timestamp_seconds"),
    )
    return (observation, hip, scale, axis, wrists), None


def _stable(items, config):
    if len(items) < 2:
        return True, {"origin": 0.0, "scale": 0.0, "axis_degrees": 0.0}, ""
    scales = [item[2] for item in items]
    base = median(scales)
    origin = max(
        items[i][1].distance_to(items[i - 1][1]) / base for i in range(1, len(items))
    )
    scale = (max(scales) - min(scales)) / base
    axis = max(
        _axis_angle(items[0][3], item[3])
        / max(config.maximum_axis_deviation_degrees, 1e-9)
        for item in items
    )
    wrist = 0.0
    for i in range(1, len(items)):
        shared = set(items[i][4]) & set(items[i - 1][4])
        wrist = (
            max(
                wrist,
                *(
                    items[i][4][j].distance_to(items[i - 1][4][j]) / base
                    for j in shared
                ),
            )
            if shared
            else wrist
        )
    metrics = {
        "origin": origin,
        "scale": scale,
        "axis_degrees": axis * config.maximum_axis_deviation_degrees,
    }
    if wrist > config.maximum_wrist_step_torso_ratio:
        return False, metrics, "PUNCH_MOTION_IN_SETUP_WINDOW"
    if origin > config.maximum_origin_step_torso_ratio:
        return False, metrics, "UNSTABLE_BODY_ORIGIN"
    if scale > config.maximum_scale_range_ratio:
        return False, metrics, "UNSTABLE_TORSO_SCALE"
    if metrics["axis_degrees"] > config.maximum_axis_deviation_degrees:
        return False, metrics, "UNSTABLE_TORSO_AXIS"
    return True, metrics, ""


def _axis_angle(a, b):
    return degrees(acos(max(-1.0, min(1.0, a[0] * b[0] + a[1] * b[1]))))


def _midpoint(a, b):
    return FramedPoint2D((a.x + b.x) / 2, (a.y + b.y) / 2, a.frame)


def _failed(config, reason, rejected):
    return SetupWindowResult(
        False,
        None,
        None,
        None,
        None,
        (),
        rejected,
        None,
        None,
        None,
        0.0,
        ("PROVISIONAL_SETUP_THRESHOLDS",),
        reason if reason != "INSUFFICIENT_STABLE_FRAMES" else reason,
        config.selector_version,
    )


def _dominant_failure(signals):
    for reason in (
        "PUNCH_MOTION_IN_SETUP_WINDOW",
        "INCOMPATIBLE_SOURCE_DIMENSIONS",
        "UNSTABLE_BODY_ORIGIN",
        "UNSTABLE_TORSO_SCALE",
        "UNSTABLE_TORSO_AXIS",
        "LOW_CONFIDENCE_BILATERAL_LANDMARKS",
        "NON_CONTIGUOUS_USABLE_FRAMES",
    ):
        if reason in signals:
            return reason
    return "INSUFFICIENT_STABLE_FRAMES"


def _neutral_debug(neutral):
    point = lambda p: {"x": p.x, "y": p.y, "coordinate_frame": p.frame.value}
    return {
        "reference_id": neutral.reference_id,
        "version": neutral.version,
        "setup_window_id": neutral.setup_window_id,
        "setup_selector_version": neutral.setup_selector_version,
        "origin": point(neutral.origin),
        "shoulder_midpoint": point(neutral.shoulder_midpoint),
        "hip_midpoint": point(neutral.hip_midpoint),
        "vertical_axis": list(neutral.vertical_axis),
        "torso_scale_px": neutral.torso_scale_px,
        "contributing_frame_count": neutral.contributing_frame_count,
        "frame_interval": list(neutral.frame_interval),
        "confidence": neutral.confidence,
        "valid": neutral.valid,
    }


def _axis_debug(origin, axis, scale):
    point = lambda p: {"x": p.x, "y": p.y, "coordinate_frame": p.frame.value}
    return {
        "origin": point(origin),
        "end": point(origin.translated(axis[0] * scale, axis[1] * scale)),
        "unit_vector": list(axis),
    }


def _abstention(reason, window):
    return {
        "status": "ABSTAINED",
        "target_id": TargetId.JODAN_CHIN.value,
        "source": "GENERIC_PROVISIONAL_ESTIMATE",
        "coaching_allowed": False,
        "scoring_withheld_reason": reason,
        "setup_window": window.to_debug_dict() if window else None,
    }


def _abstain_events(events, reason, window):
    for event in events:
        abstention = _abstention(reason, window)
        event["target_height_diagnostic"] = abstention
        event["target_estimate"] = abstention
    return {
        "setup_window": window.to_debug_dict() if window else None,
        "neutral_reference": None,
        "target_id": TargetId.JODAN_CHIN.value,
        "failure_reason": reason,
    }
