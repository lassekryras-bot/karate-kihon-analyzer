# Body Conditioning / Knuckle Push-up Learning Path

Status: OPEN

## Purpose / context

Save a future learning-path idea for later design and implementation.

The dojo's basic no-weight strength work includes four standard exercises:

- push-ups;
- crunches;
- prone back raises / back extensions;
- bodyweight squats.

For the first/basic strength learning path, push-ups should be taught and analyzed without making knuckle support a requirement. Focus first on the movement itself: shoulder-width hand placement, body alignment, depth, elbow path, control, and repetitions.

## Future learning-path idea

Create a separate **body conditioning / hardening** learning path that later introduces the karate-specific knuckle push-up variation.

The dojo variation uses:

- closed fists / knuckle support;
- hands approximately shoulder-width rather than a broad chest-focused push-up position;
- the same core push-up movement quality as the basic version;
- gradual adaptation: a practitioner may initially perform only part of a set on the knuckles and switch to palms when the knuckles become uncomfortable, increasing knuckle-supported volume over time.

The purpose of separating this from the first strength path is to avoid mixing basic strength/form learning with knuckle/tissue conditioning.

## Initial product direction

- Do **not** require knuckle detection or knuckle-supported reps in the first strength-learning-path version.
- Treat the knuckle push-up as a later progression, not as the baseline push-up definition.
- Reuse the normal push-up analysis where possible; add knuckle-specific instruction/verification only when this conditioning path is implemented.
- If knuckle verification is eventually required, investigate MediaPipe Hands or another hand/fist signal rather than relying only on Pose wrist landmarks.

## Acceptance criteria for future design work

Before implementation, define:

- learning activities and progression for the conditioning path;
- when knuckle support is introduced;
- how gradual progression is represented without turning palm-supported repetitions into failures;
- what the camera can reliably verify versus what is instruction-only;
- whether MediaPipe Hands is needed;
- safety/stop guidance for discomfort or pain;
- how this path relates to the general strength learning path.
