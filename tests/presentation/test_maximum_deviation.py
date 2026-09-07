"""Maximum selection must agree with the path, summary, and shared motion."""
from copy import deepcopy
import math

import pytest

from karate_analyzer.presentation.punch_path import build_maximum_deviation_presentation
from karate_analyzer.presentation.catalogue import resolve_measurement_presentation
from test_wiki_assets import example


def source(values):
    _, _, original, _ = example()
    p = deepcopy(original)
    samples = [dict(frame_number=i, timestamp_ms=100 * i,
                    normalized_time_progress=i / (len(values) - 1),
                    signed_deviation_output_units=v)
               for i, v in enumerate(values)]
    p['graph']['samples'] = samples
    p['overlays']['trajectory_samples'] = [
        dict(**s, camera_wrist=[float(i), -v])
        for i, (s, v) in enumerate(zip(samples, values))]
    p['summary']['maximum_deviation_output_units'] = max(map(abs, values))
    return p


@pytest.mark.parametrize('values,frame', [
    ([0, .2, -.5, 0], 2), ([0, .5, -.2, 0], 1),
    ([0, .5, -.5, 0], 1), ([0, 0, 0, 0], 0),
])
def test_selects_absolute_maximum_and_projects_perpendicularly(values, frame):
    p = source(values)
    before = deepcopy(p)
    maximum = build_maximum_deviation_presentation(p)
    m = maximum['maximum_marker']
    assert p == before
    assert maximum['availability']['status'] == 'available'
    assert m['frame_number'] == frame
    assert m['timestamp_ms'] == frame * 100
    assert m['signed_deviation_output_units'] == values[frame]
    assert m['absolute_deviation_output_units'] == max(map(abs, values))
    assert m['camera_reference_point'] == pytest.approx([frame, 0])
    assert maximum['motion_id'] == p['motion_id']
    assert maximum['measurement_id'] != p['measurement_id']
    assert maximum['presentation_id'] != p['presentation_id']


def test_ties_use_timestamp_then_frame_even_if_graph_order_differs():
    p = source([0, .5, -.5, 0])
    for rows in (p['graph']['samples'], p['overlays']['trajectory_samples']):
        rows[1]['timestamp_ms'] = rows[2]['timestamp_ms'] = 100
        rows[1]['frame_number'], rows[2]['frame_number'] = 20, 10
    p['graph']['samples'].reverse()
    assert build_maximum_deviation_presentation(p)['maximum_marker']['frame_number'] == 10


@pytest.mark.parametrize('failure', ['empty', 'nan', 'summary', 'geometry', 'degenerate', 'missing'])
def test_invalid_evidence_is_unavailable_not_zero(failure):
    p = source([0, .5, -.2, 0])
    if failure == 'empty': p['graph']['samples'] = []
    if failure == 'nan': p['graph']['samples'][1]['signed_deviation_output_units'] = math.nan
    if failure == 'summary': p['summary']['maximum_deviation_output_units'] = .2
    if failure == 'geometry': p['overlays']['trajectory_samples'][1]['camera_wrist'][1] = 9
    if failure == 'degenerate': p['overlays']['trajectory_samples'][-1]['camera_wrist'] = [0, 0]
    if failure == 'missing': p['overlays']['trajectory_samples'].pop(1)
    maximum = build_maximum_deviation_presentation(p)
    assert maximum['availability']['status'] == 'unavailable'
    assert maximum['maximum_marker'] is None


@pytest.mark.parametrize('status', ['partial', 'unavailable'])
def test_source_quality_is_preserved(status):
    p = source([0, .5, -.2, 0])
    p['availability'].update(status=status, reason='missing_pose')
    maximum = build_maximum_deviation_presentation(p)
    assert maximum['availability'] == p['availability']
    assert maximum['maximum_marker'] is None


def test_installed_maximum_matches_pose_graph_summary_and_resolver():
    catalogue, bundle, rms, motion = example()
    entry = {'measurement_id': 'punch_path_maximum_deviation'}
    maximum = next(p for p in bundle['presentations'] if p['measurement_id'] == entry['measurement_id'])
    assert maximum == build_maximum_deviation_presentation(rms)
    assert len(bundle['motions']) == 1
    m = maximum['maximum_marker']
    assert m['frame_number'] == 229 and m['timestamp_ms'] == 3817
    assert maximum['motion_id'] == rms['motion_id']
    assert maximum['scale']['output_unit'] == 'shoulder_width'
    assert maximum['graph'] == rms['graph']
    assert max(abs(s['signed_deviation_output_units']) for s in maximum['graph']['samples']) == m['absolute_deviation_output_units']
    assert math.dist(m['camera_wrist'], m['camera_reference_point']) == pytest.approx(m['absolute_deviation_output_units'])
    frame = next(f for f in motion['frames'] if f['frame_number'] == m['frame_number'])
    assert frame['timestamp_ms'] == m['timestamp_ms']
    assert frame['pose'] is not None
    definition = {**entry, 'wiki_example': {'bundle_id': 'basic', 'presentation_id': maximum['presentation_id']}}
    current = deepcopy(bundle)
    current['motions'][maximum['motion_id']]['frames'][0]['timestamp_ms'] = -123
    args = dict(packaged_bundles={'basic': bundle}, current_bundle=current,
                current_presentation_id=maximum['presentation_id'])
    wiki = resolve_measurement_presentation(definition, context='wiki', **args)
    exercise = resolve_measurement_presentation(definition, context='exercise', **args)
    assert wiki['motion'] == motion
    assert exercise['motion']['frames'][0]['timestamp_ms'] == -123
    definition['wiki_example']['presentation_id'] = rms['presentation_id']
    with pytest.raises(ValueError, match='matching measurement'):
        resolve_measurement_presentation(definition, context='wiki', **args)
