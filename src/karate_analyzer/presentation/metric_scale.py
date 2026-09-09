"""Convert camera-plane wiki measurements using a frozen forearm observation."""
from copy import deepcopy
import math
from statistics import median


def metric_bundle(source):
    """Return a metric copy; retain timing, angles, ratios and camera reference."""
    bundle = deepcopy(source)
    length = bundle['body_measurements']['forearm_length_m']
    if not isinstance(length, (int, float)) or not math.isfinite(length) or length <= 0:
        raise ValueError('A positive example-owner forearm measurement is required')
    for presentation in bundle['presentations']:
        motion = bundle['motions'][presentation['motion_id']]
        if presentation['measurement_id'] == 'hikite_finish':
            if presentation['distance_unit'] == 'meter':
                continue
            rows = motion['samples']
            references = [r for r in rows if r['t'] <= rows[0]['t'] + 100 and r.get('wrist_visible', False)]
            if len(references) < 3:
                raise ValueError('Insufficient visible forearm reference samples')
            observed = median(math.dist(r['p']['right_elbow'], r['p']['right_wrist']) for r in references)
            if not math.isfinite(observed) or observed <= 0:
                raise ValueError('Invalid forearm reference')
            factor = length / observed
            for row in rows:
                row['p'] = {key: [v * factor for v in point] for key, point in row['p'].items()}
                for key in ('point', 'shoulder_mid', 'hip_mid', 'foot', 'shoulder_foot'):
                    row[key] = [v * factor for v in row[key]]
                for key in ('radius', 'behind', 'speed', 'shoulder_distance'):
                    row[key] *= factor
            marker = presentation['maximum_marker']
            marker['speed'] *= factor
            marker['camera_elbow'] = [v * factor for v in marker['camera_elbow']]
            presentation.update(distance_unit='meter', speed_unit='meters_per_second')
            presentation['scale'] = dict(strategy='fixed_example_forearm_initial_100_ms_v1',
                measured_forearm_length_m=length, reference_side='right',
                reference_frame_numbers=[r['f'] for r in references],
                reference_forearm_source_units=observed, output_unit='meter',
                is_estimated_physical_distance=True)
            motion['coordinate_space'] = 'fixed_camera_meters'
            continue
        if presentation['availability']['status'] != 'available':
            continue
        if presentation['scale']['output_unit'] == 'meter' and presentation['scale'].get('measured_forearm_length_m') == length:
            continue
        geometry = bundle['frame_geometry']['analysis_frame']
        side = [s.removesuffix('_arm') for s in motion['arm_layer_order'] if s.endswith('_arm')][-1]
        start = presentation['graph']['samples'][0]['timestamp_ms']
        observations = []
        for frame in motion['frames']:
            if not start <= frame['timestamp_ms'] <= start + 100:
                continue
            pose = frame['pose']
            a, b = pose[f'{side}_elbow'], pose[f'{side}_wrist']
            pixels = math.hypot((a['x']-b['x'])*geometry['width_px'], (a['y']-b['y'])*geometry['height_px'])
            if min(a.get('visibility', 0), b.get('visibility', 0)) >= .5 and math.isfinite(pixels) and pixels > 0:
                observations.append((frame['frame_number'], pixels))
        if len(observations) < 3:
            raise ValueError('Insufficient visible forearm reference samples')
        pixels_per_meter = median(v for _, v in observations) / length
        factor = presentation['scale']['analysis_pixels_per_output_unit'] / pixels_per_meter
        dimensional = {'camera_wrist', 'camera_reference_point', 'start', 'end'}
        def convert(value):
            if isinstance(value, dict):
                for key, item in value.items():
                    if key in dimensional and isinstance(item, list):
                        value[key] = [v * factor for v in item]
                    elif key.endswith('_output_units') and isinstance(item, (int, float)):
                        value[key] = item * factor
                    else:
                        convert(item)
            elif isinstance(value, list):
                for item in value:
                    convert(item)
        for key in ('summary', 'graph', 'overlays', 'maximum_marker'):
            convert(presentation[key])
        presentation['graph']['output_unit'] = 'meters_per_second' if presentation['measurement_id'] == 'camera_relative_wrist_speed' else 'meter'
        presentation['scale'] = dict(strategy='median_camera_near_forearm_initial_100_ms_v1',
            output_unit='meter', analysis_pixels_per_output_unit=pixels_per_meter,
            measured_forearm_length_m=length, reference_side=side,
            reference_frame_numbers=[f for f, _ in observations],
            reference_forearm_pixels=median(v for _, v in observations),
            is_physical_measurement=False, is_estimated_physical_distance=True)
    return bundle
