from karate_analyzer.references.jodan_reference import (
    calculate_jodan_reference,
    resolve_jodan_references,
)


def _landmark(index, x, y, visibility=0.95):
    return {"index": index, "x": x, "y": y, "visibility": visibility}


def _face(frame_number, chin_y=None, dy=0.0, outlier=False):
    anchors = [33, 133, 362, 263, 70, 105]
    landmarks = [
        _landmark(i, 0.1 + n * 0.02, 0.20 + dy, 0.9) for n, i in enumerate(anchors)
    ]
    if outlier:
        landmarks[-1]["y"] += 0.30
    if chin_y is not None:
        landmarks.append(_landmark(152, 0.50, chin_y, 0.98))
    return {"frame_number": frame_number, "faces": [{"landmarks": landmarks}]}


def _event(index, frame, start=None, end=None):
    return {
        "event_index": index,
        "analysis_frame_number": frame,
        "start_frame": frame if start is None else start,
        "end_frame": frame if end is None else end,
    }


def test_same_frame_chin_is_preferred_when_available():
    reference = calculate_jodan_reference(
        chin_reference={
            "x": 0.5,
            "y": 0.4,
            "visibility": 0.9,
            "source": "face_mesh_chin_152",
            "used_landmarks": [152],
        },
        analysis_frame_number=12,
    )

    assert reference["source"] == "same_frame_chin"
    assert reference["confidence"] == "high"
    assert reference["source_frame_number"] == 12
    assert reference["analysis_frame_number"] == 12


def test_previous_chin_is_projected_using_multiple_head_cluster_anchors():
    frames = [_face(9, chin_y=0.40, dy=0.0), _face(10, chin_y=None, dy=0.03)]
    [event] = resolve_jodan_references(frames, [_event(0, 10, 9, 10)])

    reference = event["jodan_reference"]
    assert reference["source"] == "backward_projected_chin"
    assert reference["matched_head_anchor_count"] >= 3
    assert reference["head_cluster_motion_y"] == 0.03
    assert reference["y"] == 0.43 or abs(reference["y"] - 0.43) < 1e-9


def test_outlier_anchor_movement_does_not_dominate_projection():
    frames = [_face(1, chin_y=0.40), _face(2, chin_y=None, dy=0.02, outlier=True)]
    [event] = resolve_jodan_references(frames, [_event(0, 2, 1, 2)])

    assert event["jodan_reference"]["source"] == "backward_projected_chin"
    assert abs(event["jodan_reference"]["head_cluster_motion_y"] - 0.02) < 1e-9


def test_disagreeing_head_cluster_anchors_make_reference_unknown():
    source = _face(1, chin_y=0.40)
    target = _face(2, chin_y=None)
    for i, lm in enumerate(target["faces"][0]["landmarks"]):
        lm["y"] += i * 0.10
    [event] = resolve_jodan_references([source, target], [_event(0, 2, 1, 2)])

    assert event["jodan_reference"] is None
    assert event["jodan_reference_status"]["source"] == "unknown"


def test_previous_valid_jodan_reference_can_be_projected_forward():
    frames = [
        _face(1, chin_y=0.40),
        _face(2, chin_y=None, dy=0.02),
        _face(3, chin_y=None, dy=0.04),
    ]
    events = resolve_jodan_references(frames, [_event(0, 1), _event(1, 3, 3, 3)])

    assert events[1]["jodan_reference"]["source"] == "previous_jodan_projected"
    assert events[1]["jodan_reference"]["confidence"] == "low"


def test_future_valid_jodan_reference_can_be_projected_backward_with_low_confidence():
    frames = [_face(1, chin_y=None, dy=0.0), _face(2, chin_y=0.42, dy=0.02)]
    events = resolve_jodan_references(frames, [_event(0, 1), _event(1, 2)])

    assert events[0]["jodan_reference"]["source"] == "future_jodan_projected"
    assert events[0]["jodan_reference"]["confidence"] == "low"
    assert events[0]["jodan_reference"]["source_frame_number"] == 2


def test_nearby_visible_chin_can_seed_low_confidence_jodan_reference():
    frames = [_face(10, chin_y=None), _face(14, chin_y=0.42)]
    [event] = resolve_jodan_references(frames, [_event(0, 12, 12, 12)])

    assert event["chin_reference"]["source_frame_number"] == 14
    assert event["jodan_reference"]["source"] == "nearest_temporal_chin"
    assert event["jodan_reference"]["source_frame_number"] == 14
    assert event["jodan_reference"]["y"] == 0.42


def test_nearby_temporal_chin_ignores_eye_level_outlier():
    frames = [
        _face(10, chin_y=0.42),
        _face(14, chin_y=0.421),
        _face(20, chin_y=0.20),
        _face(30, chin_y=0.419),
        _face(34, chin_y=0.422),
        {"frame_number": 22, "faces": []},
    ]
    [event] = resolve_jodan_references(frames, [_event(0, 22, 22, 22)])

    assert event["jodan_reference"]["source"] == "nearest_temporal_chin"
    assert event["jodan_reference"]["source_frame_number"] == 14
    assert event["jodan_reference"]["y"] == 0.421


def test_old_fallback_sources_are_not_produced():
    reference = calculate_jodan_reference([_landmark(0, 0.5, 0.2)])
    assert reference is None

    frames = [_face(1, chin_y=None)]
    [event] = resolve_jodan_references(frames, [_event(0, 1)])
    assert event["jodan_reference"] is None
    assert event["jodan_reference_status"]["source"] == "unknown"
