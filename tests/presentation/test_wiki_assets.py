"""Verify the installed example matches the signed graph and portable contract."""
import json
import math
from pathlib import Path

import pytest

ASSETS = Path(__file__).resolve().parents[2] / 'android/KarateClipRecorder/app/src/main/assets'


def example():
    catalogue = json.loads((ASSETS / 'wiki/catalogue.json').read_text())
    selection = catalogue['default_example']
    bundle = json.loads((ASSETS / selection['asset']).read_text())
    presentation = next(p for p in bundle['presentations'] if p['presentation_id'] == selection['presentation_id'])
    return catalogue, bundle, presentation, bundle['motions'][presentation['motion_id']]


def test_default_wiki_punch_is_right_hand_five_and_catalogue_resolves():
    catalogue, bundle, presentation, motion = example()
    assert bundle['contract'] == 'karate_measurement_presentation_v2'
    assert motion['event']['side'] == 'right'
    assert motion['event']['event_index'] == 5
    assert presentation['availability']['status'] == 'available'
    assert len(catalogue['measurements']) == 1
    assert presentation['maximum_marker'] is not None
    assert presentation['coordinate_reference'] == 'fixed_analysis_camera'
    for entry in catalogue['measurements']:
        selection = entry.get('wiki_example', catalogue['default_example'])
        selected = json.loads((ASSETS / selection['asset']).read_text())
        matches = [p for p in selected['presentations'] if p['presentation_id'] == selection['presentation_id'] and p['measurement_id'] == entry['measurement_id']]
        assert len(matches) == 1
        assert matches[0]['motion_id'] in selected['motions']
        assert entry['renderer_id'] in {'pose_with_path_graph'}


def test_pose_trajectory_graph_use_same_reference_and_timestamps():
    _, bundle, presentation, motion = example()
    frames = {f['timestamp_ms']: f for f in motion['frames']}
    geometry = bundle['frame_geometry']['analysis_frame']
    scale = presentation['scale']['analysis_pixels_per_output_unit']
    trajectory = presentation['overlays']['trajectory_samples']
    start, end = [trajectory[i]['camera_wrist'] for i in (0, -1)]
    dx, dy = end[0] - start[0], end[1] - start[1]
    for row, sample in zip(trajectory, presentation['graph']['samples'], strict=True):
        assert row['timestamp_ms'] == sample['timestamp_ms']
        frame = frames[sample['timestamp_ms']]
        assert frame['frame_number'] == sample['frame_number']
        pose = frame['pose']
        wrist, shoulder = pose['right_wrist'], pose['right_shoulder']
        point = [wrist['x'] * geometry['width_px'] / scale,
                 wrist['y'] * geometry['height_px'] / scale]
        assert point == pytest.approx(row['camera_wrist'])
        # Convert image-down cross product to visual-above, independent of punch direction.
        signed = -(dx * (point[1] - start[1]) - dy * (point[0] - start[0])) / math.hypot(dx, dy) * (1 if dx > 0 else -1)
        assert signed == pytest.approx(sample['signed_deviation_output_units'])


def test_wiki_summary_and_assets_have_explicit_units_and_no_video():
    _, bundle, p, motion = example()
    values = [s['signed_deviation_output_units'] for s in p['graph']['samples']]
    assert math.sqrt(sum(v*v for v in values)/len(values)) == pytest.approx(p['summary']['typical_deviation_rms_output_units'])
    assert p['scale']['output_unit'] == 'shoulder_width'
    assert p['scale']['is_physical_measurement'] is False
    assert all('landmarks' not in f for f in motion['frames'])
    assert not list((ASSETS / 'wiki').rglob('*.mp4'))
