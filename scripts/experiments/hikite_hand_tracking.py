"""Inspect MediaPipe hand detections near a selected hikite pose wrist.

Diagnostic only: 2D visible bend is not an anatomical flexion classification.
No training warning thresholds are assigned. Output contains personal pose/image
excerpts and must not be committed without separate asset authorization.
"""
import argparse
import json
import math
from pathlib import Path

import cv2
import mediapipe as mp
from mediapipe.tasks import python
from mediapipe.tasks.python import vision
from karate_analyzer.diagnostics.wrist_alignment import visible_wrist_bend_degrees
from karate_analyzer.vision.mediapipe_pose_spike import _serialize_task_hands, _CoordinateTransform


def main():
    parser = argparse.ArgumentParser()
    for name in ('video', 'poses', 'model', 'output'):
        parser.add_argument('--'+name, required=True, type=Path)
    parser.add_argument('--start', type=int, default=285)
    parser.add_argument('--end', type=int, default=305)
    parser.add_argument('--side', choices=['left', 'right'], default='right')
    args = parser.parse_args()
    args.output.mkdir(parents=True, exist_ok=True)
    bundle = json.loads(args.poses.read_text())
    poses = {f['frame_number']: f for f in bundle['frames']}
    wrist_id, elbow_id, shoulder_id, other_id = (16,14,12,15) if args.side=='right' else (15,13,11,16)
    detector = vision.HandLandmarker.create_from_options(vision.HandLandmarkerOptions(
        base_options=python.BaseOptions(model_asset_path=str(args.model)),
        running_mode=vision.RunningMode.IMAGE, num_hands=2))
    cap = cv2.VideoCapture(str(args.video))
    records = []
    # Decode sequentially to preserve frame identity.
    frame_number = -1
    while True:
        ok, frame = cap.read()
        if not ok: break
        frame_number += 1
        if frame_number < args.start: continue
        if frame_number > args.end: break
        height, width = frame.shape[:2]
        expected = bundle['frame_geometry']['analysis_frame']
        assert (width,height)==(expected['width_px'],expected['height_px'])
        pose = {p['index']:p for p in poses[frame_number]['poses'][0]}
        def pixel(i):return (pose[i]['x']*width,pose[i]['y']*height)
        wrist, elbow, shoulder, other = map(pixel,(wrist_id,elbow_id,shoulder_id,other_id))
        arm = math.dist(elbow,shoulder)
        candidates=[]
        for ratio in (None,.22,.30,.40):
            if ratio is None: x0,y0,x1,y1=0,0,width,height
            else:
                size=round(max(width,height)*ratio)
                x0=max(0,round(wrist[0]-size/2));y0=max(0,round(wrist[1]-size/2))
                x1=min(width,x0+size);y1=min(height,y0+size)
            rgb=cv2.cvtColor(frame[y0:y1,x0:x1],cv2.COLOR_BGR2RGB)
            result=detector.detect(mp.Image(image_format=mp.ImageFormat.SRGB,data=rgb))
            transform=_CoordinateTransform(x0/width,y0/height,(x1-x0)/width,(y1-y0)/height)
            for hand in _serialize_task_hands(result,transform):
                points={p['index']:(p['x']*width,p['y']*height) for p in hand['landmarks']}
                anchor=points[0];gap=math.dist(anchor,wrist)
                matched=gap<=.5*arm and gap<math.dist(anchor,other)
                # Compare elbow -> Hand wrist with Hand wrist -> middle MCP.
                # A straight continuation has zero turn; finger curl is excluded.
                angle=visible_wrist_bend_degrees(elbow,anchor,points[9])
                candidates.append(dict(source='full_frame' if ratio is None else 'wrist_crop',crop_ratio=ratio,
                    matched=matched,pose_hand_wrist_gap_px=gap,visible_bend_degrees=angle,
                    crop_bounds=[x0,y0,x1,y1],hand=hand))
        records.append(dict(frame_number=frame_number,timestamp_ms=poses[frame_number]['timestamp_ms'],
                            pose_wrist_px=wrist,pose_elbow_px=elbow,upper_arm_px=arm,candidates=candidates))
        if frame_number==295:
            size=300;x0=max(0,round(wrist[0]-size/2));y0=max(0,round(wrist[1]-size/2))
            cv2.imwrite(str(args.output/'finish-hand.png'),frame[y0:y0+size,x0:x0+size])
            cv2.imwrite(str(args.output/'finish-frame.png'),frame)
    cap.release();detector.close()
    (args.output/'hand-detections.json').write_text(json.dumps(records,indent=2))
    for r in records:
        matched=[c for c in r['candidates'] if c['matched']]
        print(r['frame_number'],[(c['source'],c['crop_ratio'],round(c['visible_bend_degrees'],1) if c['visible_bend_degrees'] is not None else None,round(c['pose_hand_wrist_gap_px'],1)) for c in matched])


if __name__=='__main__':main()
