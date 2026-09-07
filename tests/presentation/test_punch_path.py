from __future__ import annotations

import json

import pytest

from karate_analyzer.presentation import build_punch_path_presentation_bundle


def test_bundle_exposes_semantic_pose_and_spatially_aligned_graph() -> None:
    bundle = build_punch_path_presentation_bundle(
        _companion(), _landmarks(), event_index=5
    )

    presentation = bundle["presentations"][0]
    assert bundle["contract"] == "karate_measurement_presentation_v2"
    assert bundle["motions"][presentation["motion_id"]]["event"] == {
        "event_index": 5,
        "side": "right",
        "punching_wrist_role": "right_wrist",
    }
    pose = bundle["motions"][presentation["motion_id"]]["frames"][0]["pose"]
    assert "right_wrist" in pose
    assert "head_center" in pose
    assert "nose" not in pose
    assert bundle["motions"][presentation["motion_id"]]["arm_layer_order"] == [
        "left_arm",
        "torso",
        "right_arm",
        "trajectory_overlay",
    ]
    assert presentation["graph"]["positive_display_direction"] == (
        "screen_above_reference_line"
    )
    assert presentation["graph"]["samples"][1][
        "signed_deviation_output_units"
    ] == pytest.approx(0.1)


def test_bundle_is_deterministic_and_contains_no_mediapipe_indices() -> None:
    first = build_punch_path_presentation_bundle(_companion(), _landmarks())
    second = build_punch_path_presentation_bundle(_companion(), _landmarks())

    first_json = json.dumps(first, sort_keys=True, separators=(",", ":"))
    second_json = json.dumps(second, sort_keys=True, separators=(",", ":"))
    assert first_json == second_json
    assert '"index"' not in first_json


def test_unavailable_event_remains_explicitly_unavailable() -> None:
    companion = _companion()
    trajectory = companion["event_views"][0]["trajectory_straightness"]
    trajectory.update(
        {
            "status": "unavailable",
            "unavailable_reason": "outward_approach_not_observed",
            "samples": [],
        }
    )

    bundle = build_punch_path_presentation_bundle(companion, _landmarks())
    presentation = bundle["presentations"][0]

    assert presentation["availability"] == {
        "status": "unavailable",
        "reason": "outward_approach_not_observed",
        "valid_path_sample_count": 3,
    }
    assert bundle["motions"][presentation["motion_id"]]["frames"] == []
    assert presentation["overlays"]["reference_line"] == {
        "start": None,
        "end": None,
    }


def test_unknown_event_index_is_rejected() -> None:
    with pytest.raises(ValueError, match="Unknown event index: 99"):
        build_punch_path_presentation_bundle(
            _companion(), _landmarks(), event_index=99
        )


def _companion() -> dict:
    samples = [
        {
            "frame_number": frame,
            "timestamp_ms": frame * 100,
            "normalized_time_progress": frame / 2,
            "signed_line_deviation_shoulder_widths": deviation,
        }
        for frame, deviation in ((0, 0.0), (1, -0.1), (2, 0.0))
    ]
    return {
        "length_scale": {
            "strategy": "robust_video_median_shoulder_width",
            "analysis_pixels_per_output_unit": 20.0,
            "output_unit": "shoulder_width",
            "is_physical_measurement": False,
        },
        "event_views": [
            {
                "event_index": 5,
                "side": "right",
                "signal_provenance": {"coordinate_space": "analysis_image"},
                "trajectory_straightness": {
                    "status": "available",
                    "unavailable_reason": None,
                    "method": "camera_plane_start_to_impact_wrist_path_v1",
                    "valid_path_sample_count": 3,
                    "rms_deviation_shoulder_widths": 0.057735,
                    "maximum_absolute_deviation_shoulder_widths": 0.1,
                    "path_efficiency_ratio": 0.95,
                    "samples": samples,
                },
            }
        ],
    }


def _landmarks() -> dict:
    frames = []
    for frame, wrist in enumerate(((0.55, 0.48), (0.70, 0.38), (0.85, 0.48))):
        points = {
            0: (0.42, 0.16, -0.1),
            7: (0.39, 0.17, -0.1),
            8: (0.45, 0.17, -0.1),
            11: (0.48, 0.30, 0.2),
            12: (0.38, 0.30, -0.2),
            13: (0.58, 0.42, 0.2),
            14: ((0.38 + wrist[0]) / 2, (0.30 + wrist[1]) / 2, -0.2),
            15: (0.62, 0.50, 0.2),
            16: (wrist[0], wrist[1], -0.2),
            23: (0.47, 0.68, 0.1),
            24: (0.39, 0.68, -0.1),
        }
        frames.append(
            {
                "frame_number": frame,
                "poses": [
                    [
                        {
                            "index": index,
                            "x": x,
                            "y": y,
                            "z": z,
                            "visibility": 0.99,
                        }
                        for index, (x, y, z) in points.items()
                    ]
                ],
            }
        )
    return {"frame_geometry": {"example": True}, "frames": frames}


def test_shared_motion_survives_unavailable_trajectory():
    companion = _companion()
    view = companion['event_views'][0]
    view['frames'] = [{'frame_number': i, 'timestamp_seconds': i / 10} for i in range(3)]
    view['trajectory_straightness'].update(status='unavailable', samples=[])
    bundle = build_punch_path_presentation_bundle(companion, _landmarks())
    assert len(bundle['motions']['punch:5']['frames']) == 3
    assert 'animation' not in bundle['presentations'][0]
    assert bundle['presentations'][0]['graph']['samples'] == []


def test_wiki_override_changes_whole_example_but_never_exercise():
    from copy import deepcopy
    from karate_analyzer.presentation.catalogue import resolve_measurement_presentation
    current = build_punch_path_presentation_bundle(_companion(), _landmarks())
    example = deepcopy(current)
    example['motions']['punch:5']['frames'][0]['timestamp_ms'] = 999
    definition = {'measurement_id': 'punch_path_typical_deviation_rms',
                  'wiki_example': {'bundle_id': 'acted-curve',
                                   'presentation_id': 'punch_path:event:5'}}
    args = dict(current_bundle=current, current_presentation_id='punch_path:event:5',
                packaged_bundles={'acted-curve': example})
    wiki = resolve_measurement_presentation(definition, context='wiki', **args)
    exercise = resolve_measurement_presentation(definition, context='exercise', **args)
    assert wiki['motion']['frames'][0]['timestamp_ms'] == 999
    assert exercise['motion']['frames'][0]['timestamp_ms'] == 0
    definition['wiki_example']['bundle_id'] = 'missing'
    with pytest.raises(ValueError):
        resolve_measurement_presentation(definition, context='wiki', **args)
