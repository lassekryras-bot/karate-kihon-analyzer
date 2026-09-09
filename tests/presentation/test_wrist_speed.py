from copy import deepcopy
import math
import pytest
from karate_analyzer.presentation.wrist_speed import build_wrist_speed_presentation
from test_wiki_assets import example


def test_installed_speed_matches_approved_preview_and_preserves_motion():
    catalogue,bundle,path,motion=example()
    before=deepcopy((path,motion))
    speed=build_wrist_speed_presentation(path,motion,bundle['frame_geometry'])
    installed=next(p for p in bundle['presentations'] if p['measurement_id']=='camera_relative_wrist_speed')
    from karate_analyzer.presentation.metric_scale import metric_bundle
    generated = deepcopy(bundle)
    generated['presentations'] = [speed]
    speed = metric_bundle(generated)['presentations'][0]
    assert speed['scale'] == installed['scale']
    assert speed['summary'] == pytest.approx(installed['summary'])
    for calculated, saved in zip(speed['graph']['samples'], installed['graph']['samples'], strict=True):
        assert calculated == pytest.approx(saved)
    assert (path,motion)==before
    assert speed['summary']['maximum_speed_output_units']==pytest.approx(4.397026095600011)
    assert speed['maximum_marker']['frame_number']==237
    assert speed['maximum_marker']['timestamp_ms']==3950
    assert speed['scale']['reference_side']=='right'
    assert speed['scale']['reference_frame_numbers']==list(range(211,218))
    assert speed['graph']['output_unit']=='meters_per_second'
    assert any(e['measurement_id']==speed['measurement_id'] for e in catalogue['measurements'])


def test_measured_length_converts_speed_and_preserves_pixels_and_peak():
    _,b,p,m=example()
    normalized=build_wrist_speed_presentation(p,m,b['frame_geometry'])
    metric=build_wrist_speed_presentation(p,m,b['frame_geometry'],upper_arm_length_m=.3)
    assert metric['graph']['output_unit']=='meters_per_second'
    assert metric['summary']['maximum_speed_output_units']==pytest.approx(normalized['summary']['maximum_speed_output_units']*.3)
    assert metric['maximum_marker']['frame_number']==normalized['maximum_marker']['frame_number']
    for a,c in zip(normalized['overlays']['trajectory_samples'],metric['overlays']['trajectory_samples']):
        assert [v*normalized['scale']['analysis_pixels_per_output_unit'] for v in a['camera_wrist']]==pytest.approx([v*metric['scale']['analysis_pixels_per_output_unit'] for v in c['camera_wrist']])


def test_constant_velocity_with_irregular_times_including_edges():
    _,b,p,m=example();p,m=deepcopy(p),deepcopy(m)
    times=[0,17,33,50,67,83,100]
    proto=deepcopy(m['frames'][0]['pose']);m['frames']=[]
    p['graph']['samples']=[]
    for i,t in enumerate(times):
        pose=deepcopy(proto)
        pose['right_shoulder'].update(x=.2,y=.2,visibility=1)
        pose['right_elbow'].update(x=.2,y=.3,visibility=1)
        pose['right_wrist'].update(x=.3+.3*t/1000,y=.3)
        m['frames'].append(dict(frame_number=i,timestamp_ms=t,pose=pose))
        p['graph']['samples'].append(dict(frame_number=i,timestamp_ms=t,normalized_time_progress=t/100))
    speed=build_wrist_speed_presentation(p,m,{'analysis_frame':{'width_px':1000,'height_px':1000}})
    assert speed['availability']['status']=='available'
    assert [s['speed_output_units'] for s in speed['graph']['samples']]==pytest.approx([3]*len(times))


@pytest.mark.parametrize('failure',['length','arm','pose','timestamps','window','quality'])
def test_unavailable_speed_does_not_show_zero(failure):
    _,b,p,m=example();p,m=deepcopy(p),deepcopy(m);kwargs={}
    if failure=='length':kwargs['upper_arm_length_m']=-1
    if failure=='arm':
        for f in m['frames']:f['pose']['right_elbow']['visibility']=0
    if failure=='pose':m['frames'][2]['pose']=None
    if failure=='timestamps':p['graph']['samples'][1]['timestamp_ms']=p['graph']['samples'][0]['timestamp_ms']
    if failure=='window':p['graph']['samples']=p['graph']['samples'][::6]
    if failure=='quality':p['availability']['status']='partial'
    speed=build_wrist_speed_presentation(p,m,b['frame_geometry'],**kwargs)
    assert speed['availability']['status']!='available'
    assert speed['maximum_marker'] is None
    assert speed['summary']['maximum_speed_output_units'] is None


def test_near_arm_comes_from_depth_layer_order():
    _,b,p,m=example();m=deepcopy(m)
    m['arm_layer_order']=['right_arm','torso','left_arm','trajectory_overlay']
    speed=build_wrist_speed_presentation(p,m,b['frame_geometry'])
    assert speed['availability']['status']=='available'
    assert speed['scale']['reference_side']=='left'
