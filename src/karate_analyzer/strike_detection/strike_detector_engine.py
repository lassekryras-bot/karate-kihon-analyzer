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

    minimum_elbow_extension_angle_degrees = 160.0
    angle_near_peak_tolerance_degrees = 5.0
    extension_near_peak_ratio = 0.97
    angle_plateau_delta_degrees = 2.0
    extension_plateau_delta = 0.01
    peak_alignment_window_frames = 3
    max_hand_to_wrist_match_distance = 0.08

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
            "impact_frame_selection_strategy": "fallback_peak_frame",
            "impact_frame_confidence": "low",
            "impact_frame_reason": "fallback_peak_frame",
            "strike_region_start_frame": start,
            "strike_region_end_frame": end,
            "elbow_angle_degrees": None,
            "angle_delta_degrees": None,
            "max_elbow_angle_degrees_in_region": None,
            "extension_distance": None,
            "extension_delta": None,
            "extension_velocity": None,
            "max_extension_distance_in_region": None,
            "angle_is_near_peak": False,
            "extension_is_near_peak": False,
            "angle_is_plateauing": False,
            "extension_is_plateauing": False,
            "angle_is_turning_point": False,
            "extension_is_turning_point": False,
            "peak_alignment_window_frames": self.peak_alignment_window_frames,
            "impact_frame_score": None,
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
        self._add_deltas(metrics, "elbow_angle_degrees_smoothed", "angle_delta_degrees")
        self._add_deltas(metrics, "extension_distance_smoothed", "extension_delta")
        max_angle = max(
            (
                m["elbow_angle_degrees_smoothed"]
                for m in metrics
                if m["elbow_angle_degrees_smoothed"] is not None
            ),
            default=None,
        )
        max_extension = max(
            (
                m["extension_distance_smoothed"]
                for m in metrics
                if m["extension_distance_smoothed"] is not None
            ),
            default=None,
        )
        self._classify_metrics(metrics, max_angle, max_extension)
        best_angle_peak = self._best_peak_frame(metrics, "elbow_angle_degrees_smoothed")
        best_extension_peak = self._best_peak_frame(
            metrics, "extension_distance_smoothed"
        )
        for m in metrics:
            m["score"] = self._impact_score(
                m,
                metrics,
                start,
                end,
                max_angle,
                max_extension,
                best_angle_peak,
                best_extension_peak,
            )
        plateaus = [
            m
            for i, m in enumerate(metrics)
            if m["angle_is_near_peak"]
            and m["extension_is_near_peak"]
            and m["angle_is_plateauing"]
            and m["extension_is_plateauing"]
            and self._next_is_plateauing(metrics, i)
            and not self._still_extending_quickly(m)
        ]
        turns = [
            m
            for m in metrics
            if m["angle_is_near_peak"]
            and m["extension_is_near_peak"]
            and (m["angle_is_turning_point"] or m["extension_is_turning_point"])
            and not self._still_extending_quickly(m)
        ]
        if plateaus:
            best = max(plateaus, key=lambda m: (m["score"], int(m["frame_number"])))
            strategy = "correlated_plateau"
            confidence = (
                "high"
                if self._peaks_aligned(best_angle_peak, best_extension_peak)
                else "medium"
            )
            reason = "angle and extension are near peak and plateauing"
        elif turns:
            best = max(turns, key=lambda m: (m["score"], -int(m["frame_number"])))
            strategy = "correlated_turning_point"
            confidence = (
                "high"
                if self._peaks_aligned(best_angle_peak, best_extension_peak)
                else "medium"
            )
            reason = "fast strike turning point selected from correlated local maximum"
        else:
            eligible = [
                m
                for m in metrics
                if m["angle_is_near_peak"]
                and m["extension_is_near_peak"]
                and not self._still_extending_quickly(m)
            ]
            if not eligible:
                eligible = metrics
            best = max(eligible, key=lambda m: (m["score"], int(m["frame_number"])))
            strategy = "best_available_correlated_score"
            confidence = (
                "medium"
                if self._peaks_aligned(best_angle_peak, best_extension_peak)
                else "low"
            )
            reason = (
                "best correlated score; peak/stop mismatch"
                if confidence == "low"
                else "best correlated score"
            )
        return {
            **base,
            "analysis_frame_number": best["frame_number"],
            "impact_frame_selection_strategy": strategy,
            "impact_frame_confidence": confidence,
            "impact_frame_reason": reason,
            "elbow_angle_degrees": best["elbow_angle_degrees_smoothed"],
            "angle_delta_degrees": best["angle_delta_degrees"],
            "max_elbow_angle_degrees_in_region": max_angle,
            "extension_distance": best["extension_distance_smoothed"],
            "extension_delta": best["extension_delta"],
            "extension_velocity": best["extension_delta"],
            "max_extension_distance_in_region": max_extension,
            "angle_is_near_peak": best["angle_is_near_peak"],
            "extension_is_near_peak": best["extension_is_near_peak"],
            "angle_is_plateauing": best["angle_is_plateauing"],
            "extension_is_plateauing": best["extension_is_plateauing"],
            "angle_is_turning_point": best["angle_is_turning_point"],
            "extension_is_turning_point": best["extension_is_turning_point"],
            "impact_frame_score": best["score"],
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
            "has_impact_point": self.validated_impact_point(frame, wrist)[0]
            is not None,
        }

    def validated_impact_point(
        self, frame: dict[str, Any], wrist: dict[str, Any] | None
    ) -> tuple[dict[str, Any] | None, str | None]:
        point = calculate_striking_hand_impact_point(self._frame_hands(frame), wrist)
        if point is None:
            return None, "no_matching_hand_impact_point"
        distance = point.get("match_distance_to_pose_wrist")
        if (
            distance is not None
            and float(distance) > self.max_hand_to_wrist_match_distance
        ):
            return (
                None,
                f"hand_to_wrist_match_distance_exceeds_{self.max_hand_to_wrist_match_distance}",
            )
        return point, None

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
    def _add_deltas(metrics, value_key, delta_key):
        prev = None
        for m in metrics:
            cur = m.get(value_key)
            m[delta_key] = None if prev is None or cur is None else cur - prev
            if cur is not None:
                prev = cur

    def _classify_metrics(self, metrics, max_angle, max_extension):
        for i, m in enumerate(metrics):
            angle = m.get("elbow_angle_degrees_smoothed")
            ext = m.get("extension_distance_smoothed")
            ad = m.get("angle_delta_degrees")
            ed = m.get("extension_delta")
            m["angle_is_near_peak"] = bool(
                angle is not None
                and max_angle is not None
                and angle
                >= max(
                    self.minimum_elbow_extension_angle_degrees,
                    max_angle - self.angle_near_peak_tolerance_degrees,
                )
            )
            m["extension_is_near_peak"] = bool(
                ext is not None
                and max_extension is not None
                and ext >= max_extension * self.extension_near_peak_ratio
            )
            m["angle_is_plateauing"] = bool(
                ad is not None and abs(ad) <= self.angle_plateau_delta_degrees
            )
            m["extension_is_plateauing"] = bool(
                ed is not None and abs(ed) <= self.extension_plateau_delta
            )
            prev = metrics[i - 1] if i else None
            nxt = metrics[i + 1] if i + 1 < len(metrics) else None
            m["angle_is_turning_point"] = self._local_peak(
                m,
                prev,
                nxt,
                "elbow_angle_degrees_smoothed",
                "angle_delta_degrees",
                self.angle_plateau_delta_degrees,
            )
            m["extension_is_turning_point"] = self._local_peak(
                m,
                prev,
                nxt,
                "extension_distance_smoothed",
                "extension_delta",
                self.extension_plateau_delta,
            )

    @staticmethod
    def _local_peak(m, prev, nxt, key, delta_key, tol):
        cur = m.get(key)
        if cur is None:
            return False
        local = (prev is None or prev.get(key) is None or cur >= prev[key]) and (
            nxt is None or nxt.get(key) is None or cur >= nxt[key]
        )
        d = m.get(delta_key)
        return bool(local or (d is not None and d <= tol))

    def _impact_score(
        self,
        m,
        metrics,
        start,
        end,
        max_angle,
        max_extension,
        angle_peak,
        extension_peak,
    ):
        angle = m.get("elbow_angle_degrees_smoothed")
        ext = m.get("extension_distance_smoothed")
        angle_peak_score = (
            0
            if angle is None or max_angle in (None, 0)
            else max(
                0,
                1
                - abs(max_angle - angle)
                / max(self.angle_near_peak_tolerance_degrees, 1),
            )
        )
        ext_peak_score = (
            0 if ext is None or not max_extension else min(1, ext / max_extension)
        )
        angle_stop = 1 if m["angle_is_plateauing"] or m["angle_is_turning_point"] else 0
        ext_stop = (
            1 if m["extension_is_plateauing"] or m["extension_is_turning_point"] else 0
        )
        align = 1 if self._peaks_aligned(angle_peak, extension_peak) else 0
        hand = 1 if m["has_impact_point"] else 0
        span = max(
            1, float(end or m["frame_number"]) - float(start or m["frame_number"])
        )
        late = (float(m["frame_number"]) - float(start or m["frame_number"])) / span
        penalty = -3 if self._still_extending_quickly(m) else 0
        return (
            angle_peak_score
            + angle_stop
            + ext_peak_score
            + ext_stop
            + align
            + (0.25 * hand)
            + late
            + penalty
        )

    def _still_extending_quickly(self, m):
        if m.get("angle_is_turning_point") and m.get("extension_is_turning_point"):
            return False
        ad = m.get("angle_delta_degrees")
        ed = m.get("extension_delta")
        return bool(
            (ad is not None and ad > self.angle_plateau_delta_degrees)
            or (ed is not None and ed > self.extension_plateau_delta)
        )

    def _next_is_plateauing(self, metrics, index):
        if index + 1 >= len(metrics):
            return True
        nxt = metrics[index + 1]
        return bool(
            nxt.get("angle_is_plateauing") and nxt.get("extension_is_plateauing")
        )

    def _best_peak_frame(self, metrics, key):
        vals = [m for m in metrics if m.get(key) is not None]
        return (
            None
            if not vals
            else int(
                max(vals, key=lambda m: (m[key], int(m["frame_number"])))[
                    "frame_number"
                ]
            )
        )

    def _peaks_aligned(self, a, b):
        return (
            a is not None
            and b is not None
            and abs(int(a) - int(b)) <= self.peak_alignment_window_frames
        )

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
