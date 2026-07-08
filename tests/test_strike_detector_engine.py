from __future__ import annotations


from karate_analyzer.strike_detection import StrikeDetectorEngine

LS, LE, LW = 11, 13, 15


def _landmark(index: int, x: float, y: float, visibility: float = 0.9):
    return {"index": index, "x": x, "y": y, "visibility": visibility}


def _frame(number: int, wrist: tuple[float, float]):
    return {
        "frame_number": number,
        "timestamp_seconds": number / 10,
        "pose_detected": True,
        "poses": [
            [
                _landmark(LS, 0.0, 0.0),
                _landmark(LE, 0.5, 0.0),
                _landmark(LW, wrist[0], wrist[1]),
            ]
        ],
    }


def test_strike_detector_engine_exists_and_preserves_expected_sequence() -> None:
    engine = StrikeDetectorEngine()
    right = [
        {
            "start_frame": 10,
            "end_frame": 18,
            "peak_frame_number": 15,
            "timestamp_seconds": 1.5,
            "region_frame_count": 9,
            "min_visibility": 0.9,
            "smoothed_extension_ratio": 0.95,
        }
    ]
    left = [
        {
            "start_frame": 20,
            "end_frame": 28,
            "peak_frame_number": 25,
            "timestamp_seconds": 2.5,
            "region_frame_count": 9,
            "min_visibility": 0.9,
            "smoothed_extension_ratio": 0.95,
        }
    ]

    payload = engine.extract_strike_event_candidates(
        left,
        right,
        expected_count=2,
        expected_start_side="right",
        min_region_frame_count=8,
        min_region_visibility=0.5,
        min_smoothed_extension_ratio=0.9,
    )

    assert [event["observed_side"] for event in payload["strike_event_candidates"]] == [
        "right",
        "left",
    ]
    assert [event["expected_side"] for event in payload["strike_event_candidates"]] == [
        "right",
        "left",
    ]


def test_halfway_frame_and_first_threshold_crossing_are_not_selected() -> None:
    engine = StrikeDetectorEngine()
    frames = [
        _frame(10, (0.18, 0.38)),  # about 130 degrees, early threshold crossing
        _frame(11, (0.35, 0.30)),
        _frame(12, (0.82, 0.10)),
        _frame(13, (0.98, 0.02)),
        _frame(14, (1.00, 0.00)),
        _frame(15, (1.00, 0.00)),  # plateau and later tie-breaker
    ]
    selection = engine.select_impact_frame(
        frames,
        {"start_frame": 10, "end_frame": 15, "peak_frame_number": 10},
        "left",
    )

    assert selection["analysis_frame_number"] == 15
    assert selection["analysis_frame_number"] != 10
    assert 10 <= selection["analysis_frame_number"] <= 15
    assert selection["elbow_angle_degrees"] >= 160
    assert selection["impact_frame_selection_strategy"] == "correlated_plateau"


def test_fast_strike_selects_turning_point_without_long_plateau() -> None:
    engine = StrikeDetectorEngine()
    frames = [
        _frame(20, (0.50, 0.28)),
        _frame(21, (0.80, 0.12)),
        _frame(22, (1.00, 0.00)),
        _frame(23, (0.88, 0.08)),
    ]

    selection = engine.select_impact_frame(
        frames,
        {"start_frame": 20, "end_frame": 23, "peak_frame_number": 21},
        "left",
    )

    assert selection["analysis_frame_number"] == 23
    assert selection["impact_frame_selection_strategy"] == "correlated_turning_point"
    assert selection["angle_is_turning_point"] is True
    assert selection["extension_is_turning_point"] is True


def test_peak_mismatch_gets_lower_confidence_reason() -> None:
    engine = StrikeDetectorEngine()
    frames = [
        _frame(30, (0.75, 0.10)),
        _frame(31, (1.30, 0.20)),  # extension peak
        _frame(32, (1.25, 0.20)),
        _frame(33, (0.86, 0.00)),
        _frame(34, (0.98, 0.00)),  # angle peak far from extension peak
        _frame(35, (0.98, 0.00)),
    ]

    selection = engine.select_impact_frame(
        frames,
        {"start_frame": 30, "end_frame": 35, "peak_frame_number": 31},
        "left",
    )

    assert selection["impact_frame_confidence"] in {"medium", "low"}
    if selection["impact_frame_confidence"] == "low":
        assert "mismatch" in selection["impact_frame_reason"]


def test_missing_hand_landmarks_fall_back_to_pose_wrist_impact_point() -> None:
    engine = StrikeDetectorEngine()
    frame = _frame(40, (0.86, 0.20))
    pose_wrist = frame["poses"][0][2]

    impact_point, reason = engine.validated_impact_point(frame, pose_wrist)

    assert impact_point == {
        "x": 0.86,
        "y": 0.20,
        "visibility": 0.9,
        "source": "pose_wrist_fallback",
        "hand_match_strategy": "pose_wrist_when_hand_landmarks_missing",
    }
    assert reason == "pose_wrist_fallback_no_matching_hand_impact_point"


def test_nearby_hand_impact_point_beats_pose_wrist_fallback() -> None:
    engine = StrikeDetectorEngine()
    analysis = _frame(50, (0.86, 0.20))
    nearby = _frame(52, (0.70, 0.20))
    nearby["hands"] = [
        {
            "landmarks": [
                _landmark(0, 0.84, 0.20),
                _landmark(5, 0.86, 0.18),
                _landmark(9, 0.88, 0.20),
            ]
        }
    ]

    impact_point, reason = engine.validated_nearby_impact_point(
        [analysis, nearby], 50, analysis["poses"][0][2]
    )

    assert impact_point is not None
    assert impact_point["source"] == "index_mcp_middle_mcp_midpoint"
    assert impact_point["source_frame_offset"] == 2
    assert impact_point["hand_match_strategy"] == "nearby_closest_hand_to_pose_wrist"
    assert reason == "nearby_hand_impact_point"


def test_distant_hand_frame_does_not_replace_pose_wrist_fallback() -> None:
    engine = StrikeDetectorEngine()
    analysis = _frame(50, (0.86, 0.20))
    distant = _frame(65, (0.70, 0.20))
    distant["hands"] = [
        {
            "landmarks": [
                _landmark(0, 0.84, 0.20),
                _landmark(5, 0.86, 0.18),
                _landmark(9, 0.88, 0.20),
            ]
        }
    ]

    impact_point, reason = engine.validated_nearby_impact_point(
        [analysis, distant], 50, analysis["poses"][0][2]
    )

    assert impact_point is not None
    assert impact_point["source"] == "pose_wrist_fallback"
    assert reason == "pose_wrist_fallback_no_nearby_hand_impact_point"
