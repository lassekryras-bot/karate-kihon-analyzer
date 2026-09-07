"""Geometry checks for the approved frozen hikite wiki excerpt."""
import json
import math
from pathlib import Path

ASSET = Path(__file__).parents[2] / 'android/KarateClipRecorder/app/src/main/assets/wiki/examples/hikite-punch-six.json'


def test_hikite_geometry_and_peak():
    bundle = json.loads(ASSET.read_text())
    presentation = bundle['presentations'][0]
    rows = bundle['motions'][presentation['motion_id']]['samples']
    assert (rows[0]['f'], rows[-1]['f']) == (271, 295)
    peak = min(rows, key=lambda r: (-r['speed'], r['t'], r['f']))
    assert peak['f'] == presentation['maximum_marker']['frame_number'] == 289
    assert peak['speed'] == presentation['maximum_marker']['speed']
    for r in rows:
        p = r['p']
        a, b, w = p['left_shoulder'], p['right_shoulder'], p['right_wrist']
        dx, dy = b[0]-a[0], b[1]-a[1]
        distance = abs(dx*(w[1]-a[1])-dy*(w[0]-a[0])) / math.hypot(dx,dy)
        assert math.isclose(distance, r['shoulder_distance'], abs_tol=1e-12)
        forearm = [p['right_elbow'][i]-w[i] for i in (0,1)]
        torso = [r['shoulder_mid'][i]-r['hip_mid'][i] for i in (0,1)]
        angle = math.degrees(math.acos(sum(a*b for a,b in zip(forearm,torso))/math.hypot(*forearm)/math.hypot(*torso)))
        assert math.isclose(angle, r['forearm_angle'], abs_tol=1e-10)
    assert presentation['wrist_bend']['status'] == 'unavailable'
