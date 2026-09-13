"""Offline evaluation of PROPOSED labels; never feeds labels into runtime evidence."""
from __future__ import annotations

import argparse
import csv
import json
import shutil
from pathlib import Path

import numpy as np

ROOT = Path(__file__).resolve().parents[2]
DEST = ROOT / 'docs/validation/task5/calibration'
WORK = ROOT / 'output/task5/calibration'
LABELS = json.loads((ROOT / 'docs/validation/task5/proposed-labels.json').read_text())
CONTROL = json.loads((ROOT / 'docs/validation/task5/full.trace.json').read_text())
PUNCHES = LABELS['punches']
assert LABELS['review_status'] == 'PROPOSED' and not LABELS['labels_are_ground_truth']


def write(path, value):
    path.parent.mkdir(parents=True, exist_ok=True)
    path.write_text(json.dumps(value, indent=2, allow_nan=False) + '\n', encoding='utf-8')


def categories():
    movement = {i for p in PUNCHES for i in range(p['movement_start_frame'], p['movement_end_frame'])}
    stable = {i for p in PUNCHES for i in range(p['terminal_stable_start_frame'], p['terminal_stable_end_frame']+1)}
    initial = set(range(24))
    gaps = set(range(526)) - movement - stable - initial
    return {'movement': movement, 'terminal_stable': stable, 'initial_hold': initial,
            'inter_punch_unreviewed_gap': gaps,
            'low_coverage': {i for i,f in enumerate(CONTROL['frames']) if f['coverage'] < .7}}


def distribution(values):
    known = [v for v in values if v is not None]
    return dict(count=len(values), available=len(known), **dict(zip(
        ['min','median','p75','p90','p95','max'],
        np.percentile(known, [0,50,75,90,95,100]).tolist() if known else [None]*6)))


def distributions():
    frames = CONTROL['frames']
    result = {'interval_convention':'movement [start,end); terminal stable [start,end]; gaps and later movement not assumed still',
              'review_status':'PROPOSED', 'groups':{}}
    for group, indices in categories().items():
        rows = [frames[i] for i in sorted(indices)]
        signals = {key: distribution([r[key] for r in rows]) for key in
                   ['articulated_motion','image_space_motion','slow_displacement','coverage','region_balanced_coverage','image_scale_change']}
        paired_rows = [r for r in rows if r['articulated_status'] != 'FIRST_FRAME']
        signals['regions'] = {region:{key:distribution([r['regions'][region][key] for r in paired_rows]) for key in
                                     ['raw_motion','robust_motion','coverage']} for region in frames[0]['regions']}
        signals['regional_below_threshold'] = {region:{str(threshold):sum(r['regions'][region]['coverage'] < threshold for r in paired_rows)
                                                    for threshold in [.5,.65,.7,.75]} for region in frames[0]['regions']}
        result['groups'][group] = signals
    write(DEST/'distributions.json',result)
    lines = ['# Phase 1: distributions before calibration', '',
             'Review labels remain PROPOSED. Movement excludes its end frame, which belongs to the stable interval. Later movements are excluded from the stillness groups. Values are exported Kotlin observations, including numeric motion with insufficient regional coverage.', '',
             '| Group | Signal | n | Min | Median | p75 | p90 | p95 | Max |',
             '|---|---|---:|---:|---:|---:|---:|---:|---:|']
    for group, signals in result['groups'].items():
        for key, stats in signals.items():
            if key.startswith('region'): continue
            lines.append('| '+group+' | '+key+' | '+str(stats['available'])+' | '+' | '.join(f'{stats[k]:.6f}' for k in ['min','median','p75','p90','p95','max'])+' |')
    (DEST/'distributions.md').write_text('\n'.join(lines)+'\n',encoding='utf-8')
    print('\n'.join(lines))


def runs(mask, frames):
    result=[]; start=None
    for i, yes in enumerate(list(mask)+[False]):
        if yes and start is None: start=i
        if not yes and start is not None:
            end=i-1
            result.append({'first_frame':start, 'last_frame':end,
                           'duration_ms':frames[end]['timestamp_ms']-frames[start]['timestamp_ms']})
            start=None
    return result


def mask_metrics(mask, frames):
    groups=categories()
    measured={group: {'accepted':sum(bool(mask[i]) for i in indices), 'total':len(indices),
                      'percent':100*sum(bool(mask[i]) for i in indices)/len(indices)}
              for group,indices in groups.items()}
    false_runs=runs([yes and i in groups['movement'] for i,yes in enumerate(mask)],frames)
    return {'groups':measured, 'longest_false_quiet_ms':max((r['duration_ms'] for r in false_runs),default=0),
            'false_quiet_runs':false_runs}


def threshold_experiment():
    frames=CONTROL['frames'];results=[]
    movement=categories()['movement'];stable=categories()['terminal_stable']|categories()['initial_hold']
    for q,name in [(.12,'conservative-v1'),(.3,'extractor-030'),(.35,'extractor-035'),(.5,'extractor-050'),(.65,'extractor-065')]:
        mask=[f['articulated_motion'] is not None and f['articulated_motion']<=q and f['image_space_motion'] is not None and f['image_space_motion']<=q for f in frames]
        audit=list(csv.DictReader((WORK/(name+'.audit.tsv')).open(),delimiter='\t'))
        formed=next((int(row['frame']) for row in audit if row['reference_ready']=='true'),None)
        probes=list(csv.DictReader((WORK/(name+'.reference-probes.tsv')).open(),delimiter='\t'))
        unsafe=[p for p in probes if any(i in movement for i in range(int(p['first_frame']),int(p['formed_frame'])+1))]
        results.append({'threshold':q,'reference_first_frame':formed,
                        'reference_first_timestamp_ms':frames[formed]['timestamp_ms'] if formed is not None else None,
                        'reference_in_reviewed_stable':formed in stable if formed is not None else None,
                        'reference_source_frames':list(range(formed-2,formed+1)) if formed is not None else [],
                        'fresh_three_frame_probes_overlapping_movement':unsafe,
                        'motion_only':mask_metrics(mask,frames),
                        'motion_and_coverage':mask_metrics([yes and f['coverage']>=.7 for yes,f in zip(mask,frames)],frames),
                        'all_current_quiet_gates':mask_metrics([yes and f['coverage']>=.7 and f['slow_displacement'] is not None and f['slow_displacement']<=.1 for yes,f in zip(mask,frames)],frames)})
    # Exact 0.1 segmenter control, separately from 0.12 extractor qualification.
    result={'review_status':'PROPOSED','segmenter_control_quiet_01':mask_metrics([f['articulated_motion'] is not None and f['articulated_motion']<=.1 and f['image_space_motion']<=.1 for f in frames],frames),'experiments':results}
    write(DEST/'threshold-experiment.json',result)
    dwell=[]
    for q in [.35,.5,.65]:
        for duration in [75,100,150]:
            for mode in ['motion_only','motion_and_coverage','all_current_gates']:
                mask=[f['articulated_motion'] is not None and f['articulated_motion']<=q and f['image_space_motion'] is not None and f['image_space_motion']<=q and
                      (mode=='motion_only' or f['coverage']>=.7) and
                      (mode!='all_current_gates' or f['slow_displacement'] is not None and f['slow_displacement']<=.1) for f in frames]
                fulfilled=[False]*len(frames)
                for run in runs(mask,frames):
                    for i in range(run['first_frame'],run['last_frame']+1):
                        fulfilled[i]=frames[i]['timestamp_ms']-frames[run['first_frame']]['timestamp_ms']>=duration
                dwell.append({'quiet':q,'duration_ms':duration,'mode':mode,
                              'false_fulfilled_frames':[i for i,v in enumerate(fulfilled) if v and i in movement],
                              'false_occurrences_by_punch':{str(p['punch_number']):[i for i in range(p['movement_start_frame'],p['movement_end_frame']) if fulfilled[i]] for p in PUNCHES},
                              'initial_hold_fulfilled_frames':[i for i in range(24) if fulfilled[i]],
                              'stable_fulfilled_count':sum(fulfilled[i] for i in categories()['terminal_stable'])})
    write(DEST/'dwell-experiment.json',dwell)
    print(json.dumps([{k:v for k,v in r.items() if k.startswith('reference') or k in ['threshold','fresh_three_frame_probes_overlapping_movement']} for r in results],indent=2))
    print('Motion-only acceptance:',[(r['threshold'],r['motion_only']['groups']['terminal_stable']['percent'],r['motion_only']['groups']['movement']['percent'],r['motion_only']['longest_false_quiet_ms']) for r in results])
    print('Dwell:',[(r['quiet'],r['duration_ms'],r['mode'],len(r['false_fulfilled_frames']),len(r['initial_hold_fulfilled_frames']),r['stable_fulfilled_count']) for r in dwell])


def displacement_experiment():
    result=[]; policies=[]
    for name, window, limit in [('extractor-035',600,.1),('window-300',300,.1),('window-150',150,.1),(BEST,300,.05)]:
        frames=json.loads((WORK/(name+'.trace.json')).read_text())['frames']
        for number,p in enumerate(PUNCHES):
            start=p['terminal_stable_start_frame']; end=p['terminal_stable_end_frame']
            next_start=PUNCHES[number+1]['movement_start_frame'] if number<9 else 527
            crossing=next((i for i in range(start,next_start) if frames[i]['slow_displacement'] is not None and frames[i]['slow_displacement']<=limit),None)
            result.append({'window_ms':window,'displacement_limit':limit,'punch':number+1,'onset_displacement':frames[start]['slow_displacement'],
                           'stable_end_displacement':frames[end]['slow_displacement'],
                           'first_crossing_frame':crossing,'delay_ms':frames[crossing]['timestamp_ms']-frames[start]['timestamp_ms'] if crossing is not None else None,
                           'crosses_within_proposed_stable':crossing is not None and crossing<=end,
                           'crosses_before_next_movement':crossing is not None,
                           'curve':[{'frame':i,'elapsed_ms':frames[i]['timestamp_ms']-frames[start]['timestamp_ms'],'displacement':frames[i]['slow_displacement']} for i in range(start,next_start)]})
        for policy in ['minimum','balanced','critical-torso-right-arm-with-floor','critical-torso-both-arms']:
            def coverage(f):
                if policy=='minimum':return f['coverage']>=.7
                if policy=='balanced':return f['region_balanced_coverage']>=.7
                required=['TORSO','RIGHT_ARM']+(['LEFT_ARM'] if policy=='critical-torso-both-arms' else [])
                return f['coverage']>=.5 and all(f['regions'][r]['coverage']>=.7 for r in required)
            mask=[f['articulated_motion'] is not None and f['articulated_motion']<=.5 and f['image_space_motion'] is not None and f['image_space_motion']<=.5 and f['slow_displacement'] is not None and f['slow_displacement']<=limit and coverage(f) for f in frames]
            dwell=[]
            for run in runs(mask,frames):
                for i in range(run['first_frame'],run['last_frame']+1):
                    if frames[i]['timestamp_ms']-frames[run['first_frame']]['timestamp_ms']>=100:dwell.append(i)
            policies.append({'window_ms':window,'policy':policy,'quiet_threshold':.5,'displacement_limit':limit,
                             'metrics':mask_metrics(mask,frames),'dwell_fulfilled_frames':dwell,
                             'false_dwell_movement_frames':sorted(set(dwell)&categories()['movement']),
                             'low_minimum_coverage_dwell_frames':[i for i in dwell if frames[i]['coverage']<.7]})
    write(DEST/'displacement-experiment.json',result)
    write(DEST/'coverage-policy-experiment.json',policies)
    print('Displacement:',[(r['window_ms'],r['punch'],round(r['onset_displacement'],3),r['first_crossing_frame'],r['delay_ms']) for r in result])
    print('Policies:',[(r['window_ms'],r['policy'],r['metrics']['groups']['terminal_stable']['percent'],r['false_dwell_movement_frames'],len(r['low_minimum_coverage_dwell_frames'])) for r in policies])


def slow_challenge():
    # Generic kinematic stress case: stationary torso with other regions moving.
    # A large burst is followed by continuous 0.3 torso-unit/s joint drift until
    # 2 seconds, then a hold. No labels are supplied to either runtime component.
    points={'NOSE':(0,-1.7,0),'LEFT_EAR':(-.12,-1.65,0),'RIGHT_EAR':(.12,-1.65,0),
            'LEFT_SHOULDER':(-.25,-1,0),'RIGHT_SHOULDER':(.25,-1,0),
            'LEFT_ELBOW':(-.45,-.8,0),'RIGHT_ELBOW':(.45,-.8,0),
            'LEFT_WRIST':(-.65,-.5,0),'RIGHT_WRIST':(.65,-.5,0),
            'LEFT_HIP':(-.2,0,0),'RIGHT_HIP':(.2,0,0),
            'LEFT_KNEE':(-.25,1,0),'RIGHT_KNEE':(.25,1,0),
            'LEFT_ANKLE':(-.25,2,0),'RIGHT_ANKLE':(.25,2,0)}
    frames=[]
    for time in range(0,3001,50):
        offset=0 if time<400 else min(1,(time-350)/250)+max(0,min(time,2000)-600)/1000*.3
        landmarks=[]
        for name,(x,y,z) in points.items():
            x += 0 if name.endswith(('HIP','SHOULDER')) else offset
            landmarks.append({'id':name,'world':[x,y,z],'normalized':[.5+x*.1,.35+(y+.5)*.1,z*.1],
                              'visibility':.95,'presence':.95,'source':'OBSERVED'})
        frames.append({'timestamp_ms':time,'landmarks':landmarks})
    write(WORK/'slow-challenge.fixture.json',{'schema_version':'pose-motion-replay-v1','sequence_id':'generic-burst-then-continuous-slow-drift',
                                           'frames':frames,'arm_timestamp_ms':0,'cue_timestamp_ms':None,'activity_deadline_ms':None,
                                           'labels':None,'expected_end_pose_relationship':'DIFFERENT_STABLE_POSE','notes':['Synthetic challenge; motion continues through 2000 ms.']})


BEST='real-sideview-rate-preserving-v1'
CANDIDATES=['conservative-v1','noise-calibration-only-v1','real-sideview-calibration-v1','short-window-diagnostic-v1',BEST]


def read_trace(name):
    return json.loads((WORK/(name+'.trace.json')).read_text())


def candidate_summary():
    groups=categories(); results=[]
    for name in CANDIDATES:
        trace=read_trace(name); frames=trace['frames']
        audit=list(csv.DictReader((WORK/(name+'.audit.tsv')).open(),delimiter='\t'))
        settled=[t for t in trace['transitions'] if t['to']=='SETTLING']
        movement_times={frames[i]['timestamp_ms'] for i in groups['movement']}
        results.append({'name':name,'final_state':trace['final_state'],'frame_count':len(frames),
                        'reference_first_frame':next((int(r['frame']) for r in audit if r['reference_ready']=='true'),None),
                        'transitions':trace['transitions'],
                        'false_settling_transitions':[t for t in settled if t['decision_timestamp_ms'] in movement_times],
                        'false_quiet_dwell_frames':[int(r['frame']) for r in audit if r['continuous_quiet_fulfilled']=='true' and int(r['frame']) in groups['movement']],
                        'quiet_evidence':mask_metrics([r['quiet_evidence']=='TRUE' for r in audit],frames),
                        'completions_during_low_coverage':[t for t in trace['transitions'] if t['to']=='COMPLETE' and next(f['coverage'] for f in frames if f['timestamp_ms']==t['decision_timestamp_ms'])<.7]})
    write(DEST/'candidate-comparison.json',results)
    best=read_trace(BEST);frames=best['frames']
    assert not any(t['to']=='COMPLETE' for t in best['transitions']), 'Reassess per-capture attribution before reporting a newly successful candidate'
    audit=list(csv.DictReader((WORK/(BEST+'.audit.tsv')).open(),delimiter='\t'))
    safety=[]
    for p in PUNCHES:
        start=p['movement_start_frame'];end=p['movement_end_frame'];stable_end=p['terminal_stable_end_frame']
        # Attribute only the first ARMED->MOVING transition, never a resume from SETTLING.
        detected=next((t for t in best['transitions'] if t['from']=='ARMED' and t['to']=='MOVING' and
                       frames[start]['timestamp_ms']<=t['boundary_timestamp_ms']<frames[end]['timestamp_ms']),None)
        completion=next((t for t in best['transitions'] if t['to']=='COMPLETE'),None)
        offset=detected['boundary_timestamp_ms']-frames[start]['timestamp_ms'] if detected else None
        safety.append({'punch':p['punch_number'],'review_status':'PROPOSED','method':'shared full-sequence single-capture run; no per-repetition rearming',
                       'proposed_start_frame':start,'proposed_start_ms':frames[start]['timestamp_ms'],
                       'detected_start_ms':detected['boundary_timestamp_ms'] if detected else None,
                       'start_decision_ms':detected['decision_timestamp_ms'] if detected else None,'start_offset_ms':offset,
                       'proposed_end_frame':end,'proposed_end_ms':frames[end]['timestamp_ms'],
                       'detected_terminal_boundary_ms':None,'completion_decision_ms':None,'end_offset_ms':None,
                       'extra_pre_roll_ms':max(-offset,0) if offset is not None else None,'extra_post_roll_ms':None,
                       'clipped_start':offset>0 if offset is not None else None,'premature_completion':False if completion is None else None,
                       'false_settling_frames':[i for i in range(start,end) if any(t['to']=='SETTLING' and t['decision_timestamp_ms']==frames[i]['timestamp_ms'] for t in best['transitions'])],
                       'movement_low_coverage_frames':[i for i in range(start,end) if frames[i]['coverage']<.7],
                       'stable_low_coverage_frames':[i for i in range(end,stable_end+1) if frames[i]['coverage']<.7],
                       'stable_quiet_frames':[i for i in range(end,stable_end+1) if audit[i]['quiet_evidence']=='TRUE'],
                       'stable_quiet_dwell_frames':[i for i in range(end,stable_end+1) if audit[i]['continuous_quiet_fulfilled']=='true'],
                       'reference_available_at_start':audit[start]['reference_ready']=='true',
                       'reference_is_per_punch':False,
                       'abstention_reason':'INSUFFICIENT_TERMINAL_EVIDENCE' if p['punch_number']==1 else 'PREVIOUS_CAPTURE_UNRESOLVED_NO_REARM',
                       'local_terminal_gate_blocker':'MINIMUM_COVERAGE' if p['punch_number']%2 else 'SAME_AS_INITIAL_POSE_FAILS_DIFFERENT_STABLE_RELATIONSHIP'})
    write(DEST/'per-punch-safety.json',safety)
    lines=['# Per-punch evaluation of the full single-capture replay','',
           'PROPOSED labels. No reset or per-punch reference was injected. The first capture never completes, so independent per-repetition replay is not claimed. `—` means unavailable, not zero error.', '',
           '| Punch | Proposed start/end (ms) | Detected start / offset (ms) | Terminal boundary / completion / end offset | Extra pre/post (ms) | Low-coverage movement / hold frames | Quiet hold frames / 100 ms dwell frames | Result |',
           '|---|---|---|---|---|---|---|---|']
    for r in safety:
        lines.append(f"| {r['punch']} | {r['proposed_start_ms']} / {r['proposed_end_ms']} | "+
                     (f"{r['detected_start_ms']} / {r['start_offset_ms']}" if r['detected_start_ms'] is not None else '— / —')+
                     f" | — / — / — | {r['extra_pre_roll_ms'] if r['extra_pre_roll_ms'] is not None else '—'} / — | {len(r['movement_low_coverage_frames'])} / {len(r['stable_low_coverage_frames'])} | {len(r['stable_quiet_frames'])} / {len(r['stable_quiet_dwell_frames'])} | {r['abstention_reason']} |")
    (DEST/'per-punch-safety.md').write_text('\n'.join(lines)+'\n',encoding='utf-8')
    challenges=[]
    for name in CANDIDATES[1:]:
        trace=json.loads((WORK/'slow-challenge'/(name+'.trace.json')).read_text())
        completed=next(t for t in trace['transitions'] if t['to']=='COMPLETE')
        challenges.append({'name':name,'known_synthetic_movement_end_ms':2000,
                           'boundary_ms':completed['boundary_timestamp_ms'],'decision_ms':completed['decision_timestamp_ms'],
                           'end_offset_ms':completed['boundary_timestamp_ms']-2000,
                           'premature_completion':completed['boundary_timestamp_ms']<2000})
    write(DEST/'slow-challenge-results.json',challenges)
    shutil.copyfile(WORK/(BEST+'.trace.json'),DEST/(BEST+'.trace.json'))
    shutil.copyfile(WORK/(BEST+'.audit.tsv'),DEST/(BEST+'.audit.tsv'))
    print('\n'.join(lines));print('Synthetic slow challenge:',challenges)


def reference_analysis():
    f=read_trace(BEST)['frames']
    original=json.loads((ROOT/'input/task5/video_landmarks.json').read_text())['frames']
    selected=[0,7,8,11,12,13,14,15,16,23,24,25,26,27,28]
    def relative(i):
        points=np.array([[l[k] for k in 'xyz'] for l in original[i]['world_poses'][0]])
        hips=(points[23]+points[24])/2; shoulders=(points[11]+points[12])/2
        return (points[selected]-hips)/np.linalg.norm(shoulders-hips)
    samples=[7,8,9]
    audit=list(csv.DictReader((WORK/(BEST+'.audit.tsv')).open(),delimiter='\t'))
    assert next(int(r['frame']) for r in audit if r['reference_ready']=='true') == samples[-1]
    consistency=[{'frames':[a,b],'relative_pose_rms':float(np.sqrt(np.mean(np.sum((relative(a)-relative(b))**2,axis=1))))} for a,b in [(7,8),(7,9),(8,9)]]
    results={'reference_frame':9,'reference_ms':150,
             'source_samples':[{'frame':i,'timestamp_ms':f[i]['timestamp_ms'],'coverage':f[i]['coverage'],'articulated_motion':f[i]['articulated_motion']} for i in samples],
             'pairwise_source_consistency_double_precision_audit':consistency,'holds':[]}
    for p in PUNCHES:
        rows=f[p['movement_end_frame']:p['terminal_stable_end_frame']+1]
        results['holds'].append({'punch':p['punch_number'],'side':p['expected_side'],
                                 **{k:distribution([r[k] for r in rows]) for k in ['same_similarity','mirrored_similarity']}})
    results['initial_hold']={k:distribution([r[k] for r in f[9:24]]) for k in ['same_similarity','mirrored_similarity']}
    # Fixed-reference consistency across configurations with the same extractor qualification.
    assert all(read_trace('extractor-030')['frames'][i][k]==f[i][k] for i in range(701) for k in ['same_similarity','mirrored_similarity'])
    results['same_fixed_reference_across_window_changes']=True
    write(DEST/'reference-similarity.json',results)
    joint_coverage={}
    for group in ['movement','terminal_stable','initial_hold']:
        joint_coverage[group]={}
        for index in [11,12,13,14,15,16,23,24,25,26,27,28]:
            values=[min(original[i]['poses'][0][index]['visibility'],original[i]['poses'][0][index]['presence']) for i in categories()[group]]
            joint_coverage[group][str(index)]={'confidence':distribution(values),'below_05_count':sum(v<.5 for v in values)}
    write(DEST/'landmark-confidence.json',joint_coverage)


def supplementary_tables():
    data=json.loads((DEST/'distributions.json').read_text())['groups']
    lines=['# Regional frame-to-frame evidence','',
           'Regional coverage is the exported pairwise confidence-weighted coverage, not a count of detected poses. Frame zero has no pair and is excluded from regional statistics. Current-frame minimum/balanced coverage is reported separately in distributions.json.', '',
           '| Group | Region | Signal | n | Min | Median | p75 | p90 | p95 | Max |',
           '|---|---|---|---:|---:|---:|---:|---:|---:|---:|']
    for group in ['movement','terminal_stable','initial_hold','inter_punch_unreviewed_gap','low_coverage']:
        for region,signals in data[group]['regions'].items():
            for signal in ['raw_motion','robust_motion','coverage']:
                r=signals[signal]
                lines.append('| '+group+' | '+region+' | '+signal+' | '+str(r['available'])+' | '+' | '.join(f'{r[k]:.5f}' for k in ['min','median','p75','p90','p95','max'])+' |')
    (DEST/'regional-distributions.md').write_text('\n'.join(lines)+'\n',encoding='utf-8')
    rows=[]
    frames=CONTROL['frames']
    for threshold in [.2,.5,.65,.8,1.0]:
        mask=[r['articulated_motion'] is not None and max(r['articulated_motion'],r['image_space_motion'])>=threshold for r in frames]
        stable=categories()['terminal_stable']|categories()['initial_hold']
        stable_runs=runs([value and i in stable for i,value in enumerate(mask)],frames)
        rows.append({'threshold':threshold,'longest_false_movement_run_in_holds_ms':max((r['duration_ms'] for r in stable_runs),default=0),
                     'first_sustained_motion_run':next((r for r in runs(mask,frames) if r['duration_ms']>=100),None)})
    write(DEST/'start-threshold-interaction.json',rows)
    # Archive compact test outcomes rather than large Gradle logs.
    import xml.etree.ElementTree as ET
    tests=[]
    for directory in sorted((ROOT/'android/KarateClipRecorder').glob('*/build/test-results/*')):
        roots=[ET.parse(p).getroot() for p in directory.glob('TEST-*.xml')]
        if roots:
            tests.append({'suite':directory.relative_to(ROOT).as_posix(),
                          **{key:sum(int(r.attrib.get(key,0)) for r in roots) for key in ['tests','failures','errors','skipped']},
                          'failed_tests':[r.attrib['name']+'.'+case.attrib['name'] for r in roots for case in r.findall('testcase') if case.find('failure') is not None]})
    write(DEST/'kotlin-test-results.json',tests)


def plots():
    import matplotlib
    matplotlib.use('Agg')
    import matplotlib.pyplot as plt
    best=read_trace(BEST)['frames'];audit=list(csv.DictReader((WORK/(BEST+'.audit.tsv')).open(),delimiter='\t'))
    fig,axes=plt.subplots(4,1,figsize=(14,11),sharex=True)
    for key in ['articulated_motion','image_space_motion']:
        axes[0].plot([r[key] for r in best],label=key)
    axes[0].axhline(.5,color='black',ls='--',label='experimental motion limit 0.50')
    for name,label in [('conservative-v1','600 ms / 0.10'),('real-sideview-calibration-v1','300 ms / 0.10')]:
        axes[1].plot([r['slow_displacement'] for r in read_trace(name)['frames']],label=label)
    axes[1].axhline(.05,color='black',ls='--',label='paired 300 ms limit 0.05')
    axes[1].axhline(.1,color='gray',ls=':',label='control limit 0.10')
    axes[2].plot([r['coverage'] for r in best],label='minimum coverage')
    axes[2].plot([r['region_balanced_coverage'] for r in best],label='balanced coverage (diagnostic only)')
    axes[2].axhline(.7,color='black',ls='--',label='required 0.70')
    for key in ['same_similarity','mirrored_similarity']:
        axes[3].plot([r[key] for r in best],label=key)
    quiet=[i for i,r in enumerate(audit) if r['quiet_evidence']=='TRUE']
    fulfilled=[i for i,r in enumerate(audit) if r['continuous_quiet_fulfilled']=='true']
    axes[3].scatter(quiet,[.1]*len(quiet),marker='|',color='green',label='candidate quiet evidence')
    axes[3].scatter(fulfilled,[.2]*len(fulfilled),marker='|',color='black',label='continuous 100 ms quiet dwell')
    for ax in axes:
        for p in PUNCHES:
            ax.axvspan(p['movement_start_frame'],p['movement_end_frame'],color='tab:orange',alpha=.12)
            ax.axvspan(p['movement_end_frame'],p['terminal_stable_end_frame'],color='tab:green',alpha=.10)
        ax.grid(alpha=.15);ax.legend(loc='upper right',fontsize=8,ncol=2)
    axes[3].set_xlabel('Original frame; orange = PROPOSED movement, pale green = PROPOSED hold')
    fig.suptitle('Task 5B: evidence, not ten capture completions; all 701 frames retained')
    fig.tight_layout();fig.savefig(DEST/'candidate-evidence.png',dpi=130);plt.close(fig)
    displacement=json.loads((DEST/'displacement-experiment.json').read_text())
    fig,axes=plt.subplots(5,2,figsize=(12,13))
    for p,ax in zip(PUNCHES,axes.flat):
        for row in displacement:
            if row['punch']==p['punch_number'] and row['displacement_limit']==.1:
                ax.plot([r['elapsed_ms'] for r in row['curve']],[r['displacement'] for r in row['curve']],label=f"{row['window_ms']} ms window")
        ax.axhline(.1,color='gray',ls='--',label='0.10 limit');ax.axhline(.05,color='black',ls=':',label='paired 0.05 limit')
        ax.set_title(f"Punch {p['punch_number']} held pose");ax.grid(alpha=.2)
    axes[0,0].legend(fontsize=8);fig.supxlabel('ms since proposed stable onset (through frame before next movement)')
    fig.suptitle('Displacement decay: original 600 ms memory extends beyond every reviewed hold')
    fig.tight_layout();fig.savefig(DEST/'displacement-decay.png',dpi=120);plt.close(fig)
    fig,axes=plt.subplots(1,2,figsize=(12,4))
    for ax,key in zip(axes,['articulated_motion','image_space_motion']):
        for group in ['movement','terminal_stable','initial_hold']:
            values=sorted(CONTROL['frames'][i][key] for i in categories()[group] if CONTROL['frames'][i][key] is not None)
            ax.plot(values,np.arange(1,len(values)+1)/len(values),label=group)
        ax.set_xlabel(key);ax.set_ylabel('Empirical cumulative fraction');ax.legend();ax.grid(alpha=.2)
    fig.tight_layout();fig.savefig(DEST/'motion-distributions.png',dpi=130);plt.close(fig)


if __name__ == '__main__':
    phases={'distributions':distributions,'thresholds':threshold_experiment,'displacement':displacement_experiment,
            'slow-challenge':slow_challenge,'candidates':candidate_summary,'reference':reference_analysis,'plots':plots,'tables':supplementary_tables}
    parser=argparse.ArgumentParser();parser.add_argument('phase',choices=list(phases));args=parser.parse_args()
    phases[args.phase]()
