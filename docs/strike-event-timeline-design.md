# Strike Event Timeline Design

## Purpose

This document defines the long-term domain model for representing complete striking movements in the Karate Kihon Analyzer.

The project has reached the point where punch detection is working reliably enough that the architecture should stop treating isolated frames as the primary unit of analysis. Future analysis should be built around complete martial arts movements.

A **Strike Event** represents one complete striking movement. It becomes the primary input for future analysis modules.

The project should no longer analyse:

> Frame 185

Instead, it should analyse:

> Strike Event #6

The vision engine finds movements. The analysis engine evaluates movements.

This document replaces the earlier, narrower Punch Event concept with the more generic Strike Event concept. It is a design document only and does not define production code changes.

## Architecture Overview

The conceptual flow is:

```text
Training Session
↓
Vision Provider
↓
Pose Frames
↓
Strike Detection
↓
Strike Event Timeline
↓
Analysis Modules
↓
Coaching Feedback
↓
Visual Rendering
```

MediaPipe is the current expected vision provider, but a Strike Event must be independent of MediaPipe. MediaPipe, another pose-estimation library, synthetic fixtures, or future sensors may provide pose frames. Once those inputs are converted into the project's internal movement representation, downstream analysis should not depend on the source technology.

The Strike Event Timeline is the boundary where frame-level pose data becomes movement-level domain data.

## Why Strike Event Instead of Punch Event?

The model is generalized because the long-term product should analyze martial arts techniques, not only punches.

A Strike Event can represent techniques such as:

- Jodan Tsuki
- Chudan Tsuki
- Mae Geri
- Mawashi Geri
- Yoko Geri
- Elbow Strike
- Knee Strike

The architecture should not require redesign when new techniques are introduced. Punches, kicks, elbows, knees, and future striking techniques should all fit the same movement-event model, even if each technique later requires different landmarks, measurements, validation rules, or coaching interpretation.

## Strike Event

A Strike Event is the conceptual record of one complete strike inside a training session.

A Strike Event should contain concepts such as:

- A strike identifier within the session
- The strike type or intended technique
- The striking side or limb when known
- Detection confidence
- The movement timeline
- The landmark timeline for the frames that belong to the movement
- Derived measurements produced from the timeline
- References to important phase moments, such as peak extension or estimated impact
- Metadata about detection quality and uncertainty

This document intentionally avoids implementation-specific field names. The important architectural point is that a Strike Event owns the movement, not just a single selected frame.

## Timeline

Each Strike Event contains a timeline describing the movement from preparation through completion.

Typical phases include:

1. Initial stance
2. Movement begins
3. Acceleration
4. Peak extension
5. Estimated impact
6. Recovery
7. End of movement

These phases are conceptual. Future algorithms may refine them, rename them, add intermediate phases, or identify multiple candidate moments when confidence is uncertain.

The phase model should support incremental improvement. Early implementations may only identify a simple movement window and a peak frame. Later implementations may estimate acceleration, impact timing, recovery quality, rhythm, and stability.

## Timeline Window

One Strike Event owns every frame belonging to the movement.

The event should preserve the movement history rather than only storing the impact frame or peak-extension frame. The impact frame may be useful for some analysis, but it is only one moment inside a larger action.

Preserving the full timeline enables future analysis that cannot be derived from a snapshot, including:

- Whether the strike traveled in a straight path
- Whether the limb accelerated and decelerated efficiently
- Whether the practitioner recovered balance after the strike
- Whether the technique was smooth or hesitant
- Whether rhythm and tempo stayed consistent across repeated strikes
- Whether the strike finished sharply enough to approximate kime

A system that stores only a snapshot will repeatedly lose information that future modules need. A system that stores the movement timeline can always derive snapshots later.

## Static Analysis

Static analysis uses one important frame from the Strike Event, usually the peak frame or estimated impact frame.

Examples include:

- Punch height
- Head position
- Wrist alignment
- Guard position
- Arm extension

Static analysis is useful and should remain supported, but it should be treated as analysis of a selected moment inside a Strike Event rather than analysis of an isolated frame with no movement context.

## Dynamic Analysis

Dynamic analysis requires the complete movement timeline.

Examples include:

- Straight punch path
- Path efficiency
- Velocity
- Acceleration
- Deceleration
- Finish stability
- Kime proxy
- Rhythm
- Tempo

Keeping the timeline enables metrics that cannot yet be implemented. Even if early versions only use peak-frame analysis, the architecture should preserve enough information for future modules to evaluate motion quality over time.

## Camera View Context

Every Training Session should eventually describe the recording viewpoint.

Possible viewpoints include:

- Side view (left)
- Side view (right)
- Front view
- Rear view
- Diagonal
- Unknown

Different analyses require different viewpoints. The analysis engine should eventually decide whether an analysis is valid for the recorded viewpoint before producing feedback.

For side-view recordings, useful analyses may include:

- Punch height
- Path
- Extension
- Kime proxy

For front-view recordings, useful analyses may include:

- Guard symmetry
- Centreline
- Shoulder alignment

The viewpoint is session context, not a MediaPipe detail. It should remain part of the domain model so that any vision provider can feed the same analysis engine.

## Source of Truth

The Strike Event is the long-term source of truth for movement analysis.

Future outputs should be generated from Strike Events rather than becoming primary stored data. Examples include:

- Snapshots
- Overlays
- GIFs
- Reports
- Comparison views

Rendered artifacts and reports are views over the Strike Event. They should not become the canonical representation of the movement.

## Design Principles

The Strike Event Timeline architecture should follow these principles:

- Input agnostic
- Vision provider agnostic
- Independent of MediaPipe
- Independent of rendering
- Independent of karate scoring
- Support future striking techniques
- Separate motion analysis from karate interpretation
- Preserve movement rather than only snapshots
- Allow static analysis without reducing the whole model to one frame
- Allow dynamic analysis as the project matures
- Keep source data and derived measurements distinguishable
- Treat uncertainty and confidence as first-class analysis context

These principles keep the core model reusable as the project grows from punch detection into broader martial arts movement analysis.

## Future Analysis Modules

Future modules should be organized so that generic motion analysis remains separate from karate-specific interpretation.

### Motion Analysis

Motion Analysis should measure movement characteristics that are not inherently karate-specific, such as:

- Path efficiency
- Velocity
- Acceleration
- Deceleration
- Stability
- Smoothness

These measurements should describe how the body or limb moved.

### Karate Analysis

Karate Analysis should interpret motion and posture measurements according to Kyokushin technique expectations.

Examples include:

- Jodan height
- Head position
- Wrist alignment
- Opposite arm position
- Straight punch
- Kime proxy
- Technique consistency

This separation allows the project to reuse generic motion measurements while keeping martial-arts-specific coaching rules explicit and replaceable.

## Out of Scope

This document does not define:

- Scoring algorithms
- UI behavior
- Rendering implementation
- MediaPipe implementation
- Storage format
- File formats
- Database schema
- API contracts

Those concerns should be covered by separate design documents when the project is ready for them.

## Expected Outcome

This document should be one of the primary architecture references for the project.

Future contributors should understand that the project analyses Strike Events, not isolated frames. This abstraction is intended to support many martial arts techniques while remaining independent of the underlying computer vision implementation.

## Experimental Jodan Reference Contract

Future Jodan punch analysis should consume a karate-specific `jodan_reference` instead of directly selecting raw anatomical landmarks such as `nose` or `mouth`.

The intended flow is:

1. **Landmark Layer**: raw MediaPipe pose landmarks.
2. **Body Reference Layer**: derived body/head points such as averaged eye or mouth references.
3. **Karate Reference Layer**: karate-specific targets such as `jodan_reference`.
4. **Technique Analysis Layer**: technique-specific evaluation, such as future Jodan height analysis.
5. **Rendering Layer**: visual overlays for references and feedback.

The first `jodan_reference` strategy is experimental. It prefers an eye-to-nose projection, falls back to a nose-to-mouth projection, then falls back to the nose alone when necessary. This value is an approximate karate target reference, not a medical or anatomical chin estimate.
