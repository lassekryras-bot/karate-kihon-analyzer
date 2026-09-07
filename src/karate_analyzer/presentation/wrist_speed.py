"""Fixed-camera wrist speed for the shared wiki motion player."""
from copy import deepcopy
import math
from statistics import median
from typing import Any


def build_wrist_speed_presentation(
    path: dict[str, Any], motion: dict[str, Any], geometry: dict[str, Any],
    *, upper_arm_length_m: float | None = None,
) -> dict[str, Any]:
    """Use timestamp-local linear slopes; never smooth the displayed pose.

    One reference length is the median camera-near upper arm in the initial
    100 ms. Its selected frames/side are exported, not implied to be calibrated.
    Optional measured length converts that fixed scale to metres.
    """
    result = deepcopy(path)
    result.update(
        presentation_id=path['presentation_id'].replace('punch_path:', 'wrist_speed:', 1),
        measurement_id='camera_relative_wrist_speed',
        measurement_method_id='fixed_camera_local_linear_wrist_speed_v1',
        summary={'maximum_speed_output_units': None}, maximum_marker=None,
        linked_content={'visual_page_key': 'wrist_speed.visual', 'method_page_key': 'wrist_speed.method'},
    )
    unit = 'meters_per_second' if upper_arm_length_m is not None else 'upper_arm_lengths_per_second'
    result['graph'].update(metric_id='wrist_speed', output_unit=unit,
                           zero_meaning='stationary_wrist', positive_display_direction='speed_magnitude', samples=[])
    result['overlays'].update(reference_line=None, trajectory_samples=[])
    result['scale'] = None
    result['provenance']['speed_smoothing'] = {
        'method': 'local_linear_regression_xy_then_velocity_magnitude',
        'half_window_ms': 50, 'minimum_samples': 3,
        'edge_rule': 'use_available_samples', 'pose_smoothing': 'none',
    }
    if path['availability']['status'] != 'available':
        return result
    try:
        if path.get('coordinate_reference') != 'fixed_analysis_camera':
            raise ValueError('fixed_camera_reference_required')
        if upper_arm_length_m is not None and (not math.isfinite(upper_arm_length_m) or upper_arm_length_m <= 0):
            raise ValueError('invalid_measured_upper_arm_length')
        g = geometry['analysis_frame']
        width, height = g['width_px'], g['height_px']
        if not all(math.isfinite(v) and v > 0 for v in (width, height)):
            raise ValueError('invalid_frame_dimensions')
        frames = {(f['frame_number'], f['timestamp_ms']): f for f in motion['frames']}
        samples = path['graph']['samples']
        if len(samples) < 3:
            raise ValueError('insufficient_speed_samples')
        # Layer order carries camera-depth selection; do not equate near with right.
        near = [layer.removesuffix('_arm') for layer in motion['arm_layer_order'] if layer.endswith('_arm')][-1]
        references = []
        for s in samples:
            if s['timestamp_ms'] > samples[0]['timestamp_ms'] + 100:
                break
            pose = frames[(s['frame_number'], s['timestamp_ms'])]['pose']
            a, b = pose[f'{near}_shoulder'], pose[f'{near}_elbow']
            length = math.hypot((a['x']-b['x'])*width, (a['y']-b['y'])*height)
            if min(a.get('visibility', 0), b.get('visibility', 0)) >= .5 and math.isfinite(length) and length > 0:
                references.append((s['frame_number'], length))
        if len(references) < 3:
            raise ValueError('upper_arm_reference_unavailable')
        arm_pixels = median(length for _, length in references)
        pixels_per_unit = arm_pixels / upper_arm_length_m if upper_arm_length_m is not None else arm_pixels
        result['scale'] = {
            'strategy': 'median_camera_near_upper_arm_initial_100_ms_v1',
            'output_unit': 'meter' if upper_arm_length_m is not None else 'upper_arm_length',
            'analysis_pixels_per_output_unit': pixels_per_unit,
            'reference_upper_arm_pixels': arm_pixels, 'reference_side': near,
            'reference_frame_numbers': [f for f, _ in references],
            'measured_upper_arm_length_m': upper_arm_length_m,
            'is_physical_measurement': False,
            'is_estimated_physical_distance': upper_arm_length_m is not None,
        }
        trajectory = []
        for s in samples:
            pose = frames[(s['frame_number'], s['timestamp_ms'])]['pose']
            p = pose[path['overlays']['wrist_role']]
            point = [p['x']*width/pixels_per_unit, p['y']*height/pixels_per_unit]
            if not all(math.isfinite(v) for v in point):
                raise ValueError('invalid_speed_position')
            trajectory.append({**{k: s[k] for k in ('frame_number', 'timestamp_ms', 'normalized_time_progress')}, 'camera_wrist': point})
        if any(b['timestamp_ms'] <= a['timestamp_ms'] for a, b in zip(trajectory, trajectory[1:])):
            raise ValueError('speed_timestamps_not_increasing')
        graph = []
        for row in trajectory:
            neighbours = [r for r in trajectory if abs(r['timestamp_ms']-row['timestamp_ms']) <= 50]
            if len(neighbours) < 3:
                raise ValueError('insufficient_samples_in_speed_window')
            times = [(r['timestamp_ms']-row['timestamp_ms'])/1000 for r in neighbours]
            center = sum(times)/len(times)
            denominator = sum((t-center)**2 for t in times)
            velocity = [sum((t-center)*r['camera_wrist'][axis] for t, r in zip(times, neighbours))/denominator for axis in (0, 1)]
            graph.append({**{k: v for k, v in row.items() if k != 'camera_wrist'},
                          'speed_output_units': math.hypot(*velocity)})
        maximum = min(graph, key=lambda s: (-s['speed_output_units'], s['timestamp_ms'], s['frame_number']))
        point = next(r['camera_wrist'] for r in trajectory if r['frame_number'] == maximum['frame_number'])
        result['summary']['maximum_speed_output_units'] = maximum['speed_output_units']
        result['maximum_marker'] = {**maximum, 'camera_wrist': point}
        result['graph']['samples'] = graph
        result['overlays']['trajectory_samples'] = trajectory
    except (KeyError, TypeError, ValueError, IndexError) as error:
        result['availability'].update(status='unavailable', reason=str(error))
    return result
