"""Strike Detector Engine for selecting kihon strike events and impact frames."""

from __future__ import annotations

import math
from dataclasses import dataclass
from statistics import median
from typing import Any

from karate_analyzer.references.hand_impact_reference import (
    calculate_striking_hand_impact_point,
)

LEFT_SHOULDER = 11
RIGHT_SHOULDER = 12
LEFT_ELBOW = 13
RIGHT_ELBOW = 14
LEFT_WRIST = 15
RIGHT_WRIST = 16


@dataclass(frozen=True)
class StrikeEvent:
    """Domain event selected from a strike region."""

    event_index: int
    expected_side: str
    observed_side: str
    peak_frame_number: int | None
    analysis_frame_number: int | None


class StrikeDetectorEngine:
    """Single engine for strike-region selection and impact-frame selection."""

    impact_frame_selection_strategy = "elbow_extension_then_extension_plateau_v1"

    def extract_strike_event_candidates(
        self,
        left_grouped_peaks: list[dict[str, Any]],
        right_grouped_peaks: list[dict[str, Any]],
        *,
        expected_count: int,
        expected_start_side: str,
        min_region_frame_count: int,
        min_region_visibility: float,
        min_smoothed_extension_ratio: float,
    ) -> dict[str, Any]:
        if expected_count < 0:
            raise ValueError("expected_count must be non-negative")
        if expected_start_side not in {"left", "right"}:
            raise ValueError("expected_start_side must be 'left' or 'right'")
        if min_region_frame_count < 1:
            raise ValueError("min_region_frame_count must be at least 1")

        ignored_initial_regions: list[dict[str, Any]] = []
        strike_regions: list[dict[str, Any]] = []
        for side, grouped_peaks in (
            ("left", left_grouped_peaks),
            ("right", right_grouped_peaks),
        ):
            for grouped_peak in grouped_peaks:
                event = {"side": side, **grouped_peak}
                if grouped_peak.get("start_frame") == 0:
                    ignored_initial_regions.append(
                        {**event, "ignore_reason": "initial_extended_arm_region"}
                    )
                else:
                    strike_regions.append(event)

        strike_regions.sort(key=self._region_sort_key)
        expected_sequence = self._expected_alternating_sides(
            expected_start_side, expected_count
        )
        candidates: list[dict[str, Any]] = []
        ignored_regions: list[dict[str, Any]] = []
        search_index = 0
        for event_index, expected_side in enumerate(expected_sequence, start=1):
            while search_index < len(strike_regions):
                event = strike_regions[search_index]
                search_index += 1
                if event["side"] != expected_side:
                    ignored_regions.append(
                        self._ignored_region(
                            event, event_index, expected_side, "unexpected_side"
                        )
                    )
                    continue
                quality_reason = self._region_quality_ignore_reason(
                    event,
                    min_region_frame_count=min_region_frame_count,
                    min_region_visibility=min_region_visibility,
                    min_smoothed_extension_ratio=min_smoothed_extension_ratio,
                )
                if quality_reason is not None:
                    ignored_regions.append(
                        self._ignored_region(
                            event, event_index, expected_side, quality_reason
                        )
                    )
                    continue
                candidates.append(
                    {
                        "event_index": event_index,
                        "expected_side": expected_side,
                        "observed_side": event["side"],
                        "matches_expected_side": True,
                        "strike_region_start_frame": event.get("start_frame"),
                        "strike_region_end_frame": event.get("end_frame"),
                        **event,
                    }
                )
                break
            else:
                break
        return {
            "expected_punch_count": expected_count,
            "expected_start_side": expected_start_side,
            "expected_sequence": expected_sequence,
            "min_region_frame_count": min_region_frame_count,
            "min_region_visibility": min_region_visibility,
            "min_smoothed_extension_ratio": min_smoothed_extension_ratio,
            "ignored_initial_regions": ignored_initial_regions,
            "ignored_regions": ignored_regions,
            "selected_regions": candidates,
            "strike_event_candidates": candidates,
            "punch_event_candidates": candidates,
        }

    def select_impact_frame(
        self, raw_frames: list[dict[str, Any]], candidate: dict[str, Any], side: str
    ) -> dict[str, Any]:
        peak = candidate.get("peak_frame_number")
        start = candidate.get("start_frame", peak)
        end = candidate.get("end_frame", peak)
        base = {
            "peak_frame_number": peak,
            "analysis_frame_number": peak,
            "impact_frame_selection_strategy": self.impact_frame_selection_strategy,
            "impact_frame_reason": "fallback_peak_frame",
            "strike_region_start_frame": start,
            "strike_region_end_frame": end,
            "elbow_angle_degrees": None,
            "extension_distance": None,
            "extension_velocity": None,
        }
        if peak is None:
            return {
                **base,
                "analysis_frame_number": None,
                "impact_frame_reason": "missing_peak_frame",
            }
        region_frames = [
            f
            for f in raw_frames
            if self._frame_in_region(f.get("frame_number"), start, end)
            and self._first_pose(f)
        ]
        if not region_frames:
            return base

        metrics = [self._frame_metrics(f, side) for f in region_frames]
        self._smooth_metrics(metrics, "elbow_angle_degrees")
        self._smooth_metrics(metrics, "extension_distance")
        self._smooth_metrics(metrics, "extension_velocity")
        max_extension = max(
            (
                m["extension_distance_smoothed"]
                for m in metrics
                if m["extension_distance_smoothed"] is not None
            ),
            default=None,
        )
        cutoff = (
            int(float(start) + (float(end) - float(start)) * 0.30)
            if start is not None and end is not None
            else None
        )
        eligible = [
            m
            for m in metrics
            if (cutoff is None or int(m["frame_number"]) >= cutoff)
            and (m["elbow_angle_degrees_smoothed"] or 0) >= 160
        ]
        if not eligible:
            eligible = [
                m for m in metrics if cutoff is None or int(m["frame_number"]) >= cutoff
            ] or metrics
        span = max(1.0, float(end or peak) - float(start or peak))

        def score(m: dict[str, Any]) -> tuple[float, int]:
            angle = m["elbow_angle_degrees_smoothed"]
            dist = m["extension_distance_smoothed"]
            vel = m["extension_velocity_smoothed"]
            angle_score = (
                max(0.0, 1.0 - abs(180.0 - angle) / 20.0) if angle is not None else 0.0
            )
            extension_score = (
                (dist / max_extension) if dist is not None and max_extension else 0.0
            )
            stop_score = max(0.0, 1.0 - abs(vel or 0.0) / 0.05)
            hand_visible_score = 0.25 if m["has_impact_point"] else 0.0
            late_region_score = (
                float(m["frame_number"]) - float(start or m["frame_number"])
            ) / span
            return (
                angle_score * 4
                + extension_score * 3
                + stop_score * 2
                + hand_visible_score
                + late_region_score,
                int(m["frame_number"]),
            )

        best = max(eligible, key=score)
        return {
            **base,
            "analysis_frame_number": best["frame_number"],
            "impact_frame_reason": "selected_highest_scoring_full_extension_frame",
            "elbow_angle_degrees": best["elbow_angle_degrees_smoothed"],
            "extension_distance": best["extension_distance_smoothed"],
            "extension_velocity": best["extension_velocity_smoothed"],
        }

    def _frame_metrics(self, frame: dict[str, Any], side: str) -> dict[str, Any]:
        pose = {lm.get("index"): lm for lm in self._first_pose(frame)}
        sidx, eidx, widx = self._side_landmark_indices(side)
        shoulder = self._landmark_payload(pose.get(sidx))
        elbow = self._landmark_payload(pose.get(eidx))
        wrist = self._landmark_payload(pose.get(widx))
        extension = self._distance_2d(shoulder, wrist)
        return {
            "frame_number": frame.get("frame_number"),
            "elbow_angle_degrees": self._elbow_angle(shoulder, elbow, wrist),
            "extension_distance": extension,
            "extension_velocity": None,
            "has_impact_point": calculate_striking_hand_impact_point(
                self._frame_hands(frame), wrist
            )
            is not None,
        }

    def _smooth_metrics(
        self, metrics: list[dict[str, Any]], key: str, window: int = 3
    ) -> None:
        if key == "extension_velocity":
            prev = None
            for m in metrics:
                cur = m["extension_distance"]
                m[key] = None if prev is None or cur is None else cur - prev
                if cur is not None:
                    prev = cur
        radius = window // 2
        for i, m in enumerate(metrics):
            vals = [
                x[key]
                for x in metrics[max(0, i - radius) : i + radius + 1]
                if x[key] is not None
            ]
            m[f"{key}_smoothed"] = median(vals) if vals else None

    @staticmethod
    def _elbow_angle(shoulder, elbow, wrist):
        if shoulder is None or elbow is None or wrist is None:
            return None
        ax, ay = shoulder["x"] - elbow["x"], shoulder["y"] - elbow["y"]
        bx, by = wrist["x"] - elbow["x"], wrist["y"] - elbow["y"]
        amag, bmag = math.hypot(ax, ay), math.hypot(bx, by)
        if amag == 0 or bmag == 0:
            return None
        cos = max(-1.0, min(1.0, (ax * bx + ay * by) / (amag * bmag)))
        return math.degrees(math.acos(cos))

    @staticmethod
    def _side_landmark_indices(side):
        if side == "left":
            return LEFT_SHOULDER, LEFT_ELBOW, LEFT_WRIST
        if side == "right":
            return RIGHT_SHOULDER, RIGHT_ELBOW, RIGHT_WRIST
        raise ValueError("side must be 'left' or 'right'")

    @staticmethod
    def _first_pose(frame):
        return (frame.get("poses") or [[]])[0] if frame.get("poses") else []

    @staticmethod
    def _frame_hands(frame):
        return [
            h if isinstance(h, dict) else {"landmarks": h}
            for h in (frame.get("hands") or frame.get("hand_landmarks") or [])
        ]

    @staticmethod
    def _landmark_payload(lm):
        if lm is None:
            return None
        try:
            return {
                "x": float(lm["x"]),
                "y": float(lm["y"]),
                "visibility": float(lm.get("visibility", 0.0)),
            }
        except KeyError:
            return None

    @staticmethod
    def _distance_2d(a, b):
        if a is None or b is None:
            return None
        return math.hypot(a["x"] - b["x"], a["y"] - b["y"])

    @staticmethod
    def _frame_in_region(frame_number, start, end):
        return (
            frame_number is not None
            and start is not None
            and end is not None
            and int(start) <= int(frame_number) <= int(end)
        )

    @staticmethod
    def _expected_alternating_sides(start_side, count):
        sides = [start_side, "left" if start_side == "right" else "right"]
        return [sides[i % 2] for i in range(count)]

    @staticmethod
    def _sortable_timestamp(timestamp):
        return float(timestamp) if timestamp is not None else float("inf")

    @staticmethod
    def _sortable_frame(frame):
        return int(frame) if frame is not None else 10**12

    def _region_sort_key(self, event):
        return (
            self._sortable_timestamp(event.get("timestamp_seconds")),
            self._sortable_frame(event.get("peak_frame_number")),
            event["side"],
        )

    @staticmethod
    def _region_quality_ignore_reason(
        event,
        *,
        min_region_frame_count,
        min_region_visibility,
        min_smoothed_extension_ratio,
    ):
        if (
            event.get("region_frame_count") is None
            or int(event["region_frame_count"]) < min_region_frame_count
        ):
            return "region_too_short"
        if (
            event.get("min_visibility") is None
            or float(event["min_visibility"]) < min_region_visibility
        ):
            return "low_confidence_region"
        if (
            event.get("smoothed_extension_ratio") is None
            or float(event["smoothed_extension_ratio"]) < min_smoothed_extension_ratio
        ):
            return "low_confidence_region"
        return None

    @staticmethod
    def _ignored_region(event, event_index, expected_side, reason):
        return {
            "event_index": event_index,
            "expected_side": expected_side,
            "observed_side": event["side"],
            "matches_expected_side": event["side"] == expected_side,
            "ignore_reason": reason,
            **event,
        }
