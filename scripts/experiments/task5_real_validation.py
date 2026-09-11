"""Reproduce the Task 5 import audit and proposed review artifacts (offline).

Run from repository root with PYTHONPATH=src. Run --prepare before Kotlin replay,
then run without arguments to archive and summarize the resulting traces.
"""
import argparse
from collections import Counter
import hashlib
import json
from pathlib import Path
import shutil

from karate_analyzer.diagnostics.pose_replay_import import (
    LANDMARK_NAMES, import_analyzer_landmarks, proposed_review_payload,
)
from karate_analyzer.diagnostics.replay_motion_plots import render_replay_trace

WORK = Path('output/task5')
DEST = Path('docs/validation/task5')


def write(path, value):
    path.parent.mkdir(parents=True, exist_ok=True)
    path.write_text(json.dumps(value, indent=2) + '\n', encoding='utf-8')


def prepare():
    source = Path('input/task5/video_landmarks.json')
    original = json.loads(source.read_text(encoding='utf-8'))
    result = import_analyzer_landmarks(original, sequence_id='real-kihon-10-punch-1000002073-v1', source_path=source.as_posix())
    frames = result.fixture['frames']
    assert len(frames) == len(original['frames']) == 701
    for ordinal, (old, new) in enumerate(zip(original['frames'], frames)):
        assert old['frame_number'] == ordinal
        assert old['timestamp_ms'] == new['timestamp_ms']
        assert len(new['landmarks']) == 33
        for index, landmark in enumerate(new['landmarks']):
            assert landmark['id'] == LANDMARK_NAMES[index]
            for source_key, target in [('poses', 'normalized'), ('world_poses', 'world')]:
                assert landmark[target] == [old[source_key][0][index][axis] for axis in 'xyz']
            for confidence in ['visibility', 'presence']:
                assert landmark[confidence] == old['poses'][0][index][confidence]
            assert landmark['source'] == 'OBSERVED'
    result.summary['exact_preservation_verified'] = True
    result.summary['pose_landmark_count'] = 701 * 33
    import cv2
    video = cv2.VideoCapture('input/task5/1000002073.mp4')
    result.summary['video'] = {
        'frame_count': int(video.get(cv2.CAP_PROP_FRAME_COUNT)),
        'fps': video.get(cv2.CAP_PROP_FPS),
        'width': int(video.get(cv2.CAP_PROP_FRAME_WIDTH)),
        'height': int(video.get(cv2.CAP_PROP_FRAME_HEIGHT)),
    }
    decoded_count = 0
    while video.read()[0]:
        decoded_count += 1
    video.release()
    assert decoded_count == 701
    result.summary['video']['decoded_frame_count'] = decoded_count
    result.summary['sha256'] = {p.name: hashlib.sha256(p.read_bytes()).hexdigest() for p in [source, Path('input/task5/1000002073.mp4')]}
    write(WORK / 'real-kihon-10-punch.fixture.json', result.fixture)
    write(DEST / 'import-summary.json', result.summary)
    armed = dict(result.fixture, arm_timestamp_ms=0)
    armed['notes'] = result.fixture['notes'] + ['Diagnostic arming request at recording start; no cue inferred.']
    write(WORK / 'arm-request.fixture.json', armed)

    # Proposals from video contact-sheet and motion-trace review, NOT impact offsets.
    bounds = [(24,56,76),(80,110,123),(125,158,175),(179,210,224),
              (225,257,275),(277,307,324),(325,357,375),(377,407,425),
              (427,457,475),(477,507,525)]
    proposal = proposed_review_payload(result.fixture, [99,144,198,243,296,344,396,444,496])
    proposal['method'] = 'Assistant video contact-sheet and trace review; approximate full-body boundaries; pending human review.'
    proposal['frame_numbering'] = 'zero-based source frame_number'
    for punch, (start,end,stable_end) in zip(proposal['punches'], bounds):
        punch.update(movement_start_frame=start, movement_end_frame=end,
                     terminal_stable_start_frame=end, terminal_stable_end_frame=stable_end,
                     left_censored=False, review_confidence='MEDIUM', boundary_uncertainty_frames=5,
                     ambiguity_notes=['Include preparation, opposite-arm withdrawal, torso turn and settling. Stable means visually held pose, not zero landmark motion.'])
        for key in ['movement_start','movement_end','terminal_stable_start','terminal_stable_end']:
            punch[key+'_timestamp_ms'] = frames[punch[key+'_frame']]['timestamp_ms']
    proposal['opening_hold'] = {'frames':[0,23], 'preceding_movement_left_censored':True, 'is_numbered_punch_1':False}
    proposal['additional_visible_movements'] = [
        {'kind':'punch', 'approximate_frames':[527,557]},
        {'kind':'punch', 'approximate_frames':[577,607]},
        {'kind':'punch', 'approximate_frames':[627,657]},
        {'kind':'lowering_arms', 'approximate_frames':[685,700], 'right_censored':True},
    ]
    write(DEST / 'proposed-labels.json', proposal)
    review_sheets(original)


def review_sheets(original):
    import cv2
    from PIL import Image, ImageDraw
    video = cv2.VideoCapture('input/task5/1000002073.mp4')
    groups = {
        'contact-0': (list(range(0,350,10)), 7, 200, 220),
        'contact-1': (list(range(350,701,10)), 7, 200, 220),
        'detail-0': (list(range(0,65,2)), 8, 180, 195),
        'boundaries-0': ([i for start in [20,75,125,175,225] for i in range(start,start+50,5)], 10,145,190),
        'boundaries-1': ([i for start in [275,325,375,425,475] for i in range(start,start+50,5)], 10,145,190),
        'alignment': ([0,50,99,344,496,650], 6,200,290),
    }
    for name, (indices, columns, width, height) in groups.items():
        sheet = Image.new('RGB', (columns*width, ((len(indices)+columns-1)//columns)*height), 'white')
        draw = ImageDraw.Draw(sheet)
        for ordinal, frame in enumerate(indices):
            video.set(cv2.CAP_PROP_POS_FRAMES, frame)
            ok, pixels = video.read()
            assert ok, frame
            if name == 'alignment':
                h,w = pixels.shape[:2]
                for landmark in original['frames'][frame]['poses'][0]:
                    if landmark['index'] in [11,12,13,14,15,16,23,24,25,26,27,28]:
                        cv2.circle(pixels,(round(landmark['x']*w),round(landmark['y']*h)),8,(0,255,0),-1)
            img = Image.fromarray(cv2.cvtColor(pixels,cv2.COLOR_BGR2RGB))
            img.thumbnail((width,height-25))
            x,y = ordinal%columns*width, ordinal//columns*height
            sheet.paste(img,(x,y));draw.text((x+4,y+height-22),str(frame),fill='black')
        sheet.save(DEST/(name+'.jpg'))
    video.release()


def report():
    DEST.mkdir(parents=True, exist_ok=True)
    for name in ['full', 'arm-request']:
        trace = json.loads((WORK / (name+'.trace.json')).read_text())
        assert len(trace['frames']) == 701
        shutil.copyfile(WORK / (name+'.trace.json'), DEST / (name+'.trace.json'))
        render_replay_trace(DEST / (name+'.trace.json'), DEST / (name+'.png'))
    frames = trace['frames']
    metrics = {'final_state':trace['final_state'], 'transitions':trace['transitions'],
               'below_completion_coverage_count':sum(f['coverage'] < .7 for f in frames),
               'clamped_landmark_samples':sum(r['clamp_count'] for f in frames for r in f['regions'].values()),
               'status_counts':{k:dict(Counter(f[k] for f in frames)) for k in ['articulated_status','image_status']}}
    for key in ['coverage','region_balanced_coverage','articulated_motion','image_space_motion',
                'image_scale_change','slow_displacement','same_similarity','mirrored_similarity','baseline_dwell_ms']:
        values = [f[key] for f in frames if f[key] is not None]
        metrics[key] = {'available':len(values), 'min':min(values) if values else None, 'max':max(values) if values else None}
    write(DEST / 'replay-summary.json', metrics)
    import matplotlib.pyplot as plt
    fig, axes = plt.subplots(2,1,figsize=(14,7),sharex=True)
    indices = list(range(701))
    for key in ['articulated_motion','image_space_motion','slow_displacement']:
        axes[0].plot(indices,[f[key] for f in frames],label=key)
    for key in ['coverage','region_balanced_coverage','image_scale_change']:
        axes[1].plot(indices,[f[key] for f in frames],label=key)
    proposal = json.loads((DEST/'proposed-labels.json').read_text())
    for ax in axes:
        for p in proposal['punches']:
            ax.axvspan(p['movement_start_frame'],p['movement_end_frame'],alpha=.10,color='blue')
            ax.axvspan(p['terminal_stable_start_frame'],p['terminal_stable_end_frame'],alpha=.13,color='green')
        ax.legend(); ax.grid(alpha=.2)
    for p in proposal['punches']:
        axes[0].text(p['movement_start_frame'],5,str(p['punch_number']))
    axes[1].set_xlabel('Source frame (zero-based)')
    fig.suptitle('PROPOSED movements (blue) and visual terminal holds (green); no trusted labels')
    fig.tight_layout();fig.savefig(DEST/'proposed-boundaries.png',dpi=140);plt.close(fig)


if __name__ == '__main__':
    parser=argparse.ArgumentParser();parser.add_argument('--prepare',action='store_true');args=parser.parse_args()
    prepare() if args.prepare else report()
