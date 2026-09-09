import json
import math
from pathlib import Path

import pytest

from karate_analyzer.presentation.metric_scale import metric_bundle

ASSETS = Path(__file__).resolve().parents[2] / 'android/KarateClipRecorder/app/src/main/assets/wiki/examples'


def load(name):
    return json.loads((ASSETS / name).read_text(encoding='utf-8'))


def test_metric_path_matches_raw_camera_positions_and_forearm_scale():
    bundle = load('basic-right-punch.json')
    assert metric_bundle(bundle) == bundle
    for p in bundle['presentations']:
        assert p['scale']['output_unit'] == 'meter'
        assert p['scale']['measured_forearm_length_m'] == .3
        motion = bundle['motions'][p['motion_id']]
        frames = {f['frame_number']: f for f in motion['frames']}
        geometry = bundle['frame_geometry']['analysis_frame']
        scale = p['scale']['analysis_pixels_per_output_unit']
        for sample in p['overlays']['trajectory_samples']:
            wrist = frames[sample['frame_number']]['pose'][p['overlays']['wrist_role']]
            assert sample['camera_wrist'] == pytest.approx([wrist['x'] * geometry['width_px'] / scale, wrist['y'] * geometry['height_px'] / scale])
        if p['measurement_id'] != 'camera_relative_wrist_speed':
            a, b = p['overlays']['reference_line'].values()
            distances = [abs((b[0]-a[0])*(s['camera_wrist'][1]-a[1])-(b[1]-a[1])*(s['camera_wrist'][0]-a[0]))/math.dist(a,b) for s in p['overlays']['trajectory_samples']]
            assert p['summary']['maximum_deviation_output_units'] == pytest.approx(max(distances))
            assert p['summary']['typical_deviation_rms_output_units'] == pytest.approx(math.sqrt(sum(v*v for v in distances)/len(distances)))


def test_hikite_metric_geometry_and_peak_are_consistent():
    bundle = load('hikite-punch-six.json')
    assert metric_bundle(bundle) == bundle
    p = bundle['presentations'][0]
    assert (p['distance_unit'], p['speed_unit']) == ('meter', 'meters_per_second')
    rows = bundle['motions'][p['motion_id']]['samples']
    peak = max(rows, key=lambda r: r['speed'])
    assert peak['f'] == p['maximum_marker']['frame_number']
    assert peak['speed'] == pytest.approx(p['maximum_marker']['speed'])
    assert peak['point'] == pytest.approx(p['maximum_marker']['camera_elbow'])


def test_invalid_owner_measurement_is_rejected():
    bundle = load('basic-right-punch.json')
    bundle['body_measurements']['forearm_length_m'] = 0
    with pytest.raises(ValueError):
        metric_bundle(bundle)
