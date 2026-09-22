# Landmark Geometry Core — Shared Utility Specification v1.0

Date: 22 September 2026  
Project: Karate Analyzer / karate-kihon-analyzer  
Status: Proposed implementation specification following architecture discussion; not a claim that these capabilities are already implemented.  
Placement: Pure Kotlin within the existing karate-analyzer-core module. No new module or dependency-injection framework is required.

## 1. Purpose

Provide a shared vocabulary and deterministic calculations for interpreting pose landmarks. Segmentation, movement analyzers, and live guidance must reuse the same definitions of anchors, geometry, reference frames, and evidence quality.

The core answers questions such as:

- Where is the wrist relative to the torso center?
- How far is the elbow from the body center line?
- What is the angle between two directed landmark lines?
- How did the wrist move relative to the shoulder between two instants?
- How far did the torso center travel through the image?

The core describes observed projected geometry. Consumers decide what the geometry means for a technique, activity, or movement boundary. It does not infer that a punch is good, define an optimal target, select an impact event, or assign a session score.

“Landmark Geometry Core” names the capability. It is an in-process library of focused utilities, not a remote service or one large stateful manager.

## 2. Architectural boundaries

Canonical landmark evidence enters the core. The core exposes anchors, body frames, geometric relations, trajectories, and evidence diagnostics. Segmenters, analyzers, and live guidance consume those outputs.

Consumers persist analytical results through the application layer. Presentation maps saved canonical geometry through OverlayCoordinateTransformer into screen coordinates.

The core MUST NOT depend on Android, CameraX, Room, MediaPipe runtime classes, UI components, filesystem access, job scheduling, or wall-clock time. Adapters provide canonical samples and configuration. Existing canonical domain types may be reused if they obey these boundaries.

Camera recording remains independent: a master MP4 must be captured correctly even when live landmark processing is disabled or fails. Live guidance owns its own transient state. Offline processing owns persisted evidence and durable jobs outside this core.

## 3. Vocabulary and names

These names should appear consistently in API names, analyzer requirements, debug output, and measurement documentation.

| Term | Definition |
| --- | --- |
| Landmark | One identified pose point from a source sample, with observation evidence. |
| Anchor | A named point derived from one landmark or an explicitly defined group of landmarks. |
| Anchor definition | Versioned recipe for constructing an anchor. |
| Spatial aggregation | Combining landmarks within the same sample, for example a shoulder midpoint. |
| Temporal aggregation | Combining compatible observations across a declared time window. |
| Body frame | Origin, orthonormal up/across axes, and normalization scale derived from body anchors. |
| Body center line | Infinite line through the torso center along body up. It is not an anatomical spine estimate. |
| Body height | Signed position along body up, relative to the body-frame origin. |
| Body lateral offset | Signed perpendicular offset from the body center line. |
| Body position | Pair: lateral offset and body height. |
| Image position | Position in the full upright, unmirrored source image. |
| Landmark line | Directed line through an ordered pair of anchors. |
| Landmark segment | Finite segment between an ordered pair of anchors. |
| Distance | Nonnegative separation at an instant in a declared metric space. |
| Displacement | End position minus start position; a vector with a separately available magnitude. |
| Travel distance | Sum of distances along an observed trajectory, not endpoint separation. |
| Relative displacement | Change in one anchor's position relative to another anchor. |
| Path deviation | Distance from trajectory samples to an explicitly selected reference line or segment. |
| Velocity / speed | Time derivative of position / magnitude of velocity, with a declared reference and units. |

Avoid ambiguous API terms such as height(), movement(), offset(), angle(), and horizontal() without a reference or operation qualifier. “Horizontal” means image horizontal; “body lateral” means perpendicular to body up.

In v1, body lateral is a projected image-plane direction. It MUST NOT be labelled anatomical left/right or forward/backward without additional view-specific semantics supplied by the consumer.

## 4. Required query contract

Every measurement request MUST identify:

1. Subject: anchor, ordered line, segment, or trajectory.
2. Operation: position, distance, signed offset, angle, displacement, travel, deviation, velocity, or speed.
3. Reference: image frame, explicit body frame, another anchor, or reference line.
4. Time selection: exact source sample, declared sample-selection policy, or bounded interval.
5. Units and scale policy.
6. Evidence/window/filter configuration.

Mandatory choices must be represented as typed arguments or named presets. Do not parse free-form language in this library. Natural-language analyzer requirements are translated into explicit calls by the implementer.

Unsupported combinations return a diagnostic result; they must not silently substitute a different reference, model, or unit.

## 5. Coordinate spaces and mathematical convention

### 5.1 Canonical source coordinates

Canonical source-normalized coordinates use the complete upright, unmirrored image: x increases right, y increases down. W and H are the canonical image dimensions, not screen dimensions or encoded dimensions before rotation.

Canonicalization is an input-adapter responsibility. Rotation and mirror state must be explicit; unknown orientation must not be guessed from portrait/landscape shape. Coordinates must not be clamped: valid geometry can extend beyond the visible image.

### 5.2 Aspect-correct image metric

Raw normalized x and y have different physical pixel scales on a nonsquare image. Euclidean operations MUST use the existing shared aspect-correct math, with explicit conversion where conventions differ.

For this specification, a convenient mathematical representation is:

    Q = (x * W/H, -y)

This uses image-height units and an upward-positive mathematical y axis. Its inverse is x = Qx * H/W and y = -Qy. Multiplying metric coordinates by H gives equivalent pixel distances.

If FrameGeometryMath currently uses downward-positive y, retain that established representation internally and provide explicit adapters for signs and angles. Do not globally change existing conventions as part of this migration. Named coordinate types must prevent accidental mixing.

Display crop, zoom, viewport size, and display mirroring MUST NOT affect analytical values.

### 5.3 Body-frame construction

For each valid source sample:

- S = midpoint of left and right shoulders.
- Hc = midpoint of left and right hips.
- O = (S + Hc) / 2, the torso center.
- L = length(S - Hc), evaluated in aspect-correct image metric space.
- U = (S - Hc) / L, the body-up unit vector.
- R = (Uy, -Ux), the body-across unit vector in the upward-positive convention.

R points toward image right for an upright body. It must not flip according to the active arm or nearer side. Do not force U toward image up; it follows hip-to-shoulder geometry. Reject degenerate frames rather than inventing axes.

Body position of a metric point Q, in torso lengths:

    lateral = dot(Q - O, R) / L
    height  = dot(Q - O, U) / L

Inverse:

    Q = O + L * (lateral * R + height * U)

Shoulder center has height +0.5; hip center has height -0.5. Both have zero lateral offset. Signed distance from the center line is the lateral coordinate; unsigned distance is its absolute value. Body height is not distance above the floor or full-person height.

The result retains the exact frame identity used for conversion. A body coordinate cannot be converted back using an unrelated or newly estimated body frame.

## 6. Reference frames and scale

| Reference | Ownership and meaning |
| --- | --- |
| IMAGE | Fixed canonical recording image. Describes visible motion, including camera motion if present. |
| CURRENT_BODY | Body frame evaluated at each requested sample. Describes pose relative to the current torso. |
| START_BODY | Explicit immutable body-frame snapshot selected for the start of the interval. |
| NEUTRAL_BODY | Explicit immutable calibration snapshot accepted by a consumer's neutral/stillness policy. |

The core constructs frames and validates supplied snapshots; it does not decide whether a pose is neutral. START_BODY and NEUTRAL_BODY require snapshot IDs and source provenance. Missing snapshots produce unavailable results, not a fallback to current geometry.

A frame snapshot freezes origin, axes, and scale. A frame that follows current hips but retains neutral axes is a separate transported-reference policy. It is not NEUTRAL_BODY and is outside the initial v1 implementation unless a consumer explicitly specifies and versions it.

CURRENT_BODY positions may use instantaneous L. Across time, that measures changing body coordinates: translation, rotation, and scale of the reference all affect the result. It must not be labelled image velocity or physical limb speed.

For displacement, travel, and speed normalized by torso length, the default named preset uses one explicit frozen reference torso length throughout the interval. Requests must record whether scale is frozen or instantaneous. No implicit scale changes between samples.

v1 units: source-normalized position, image-height units, pixels, torso lengths, degrees, seconds, and corresponding speed units. Metres and metres/second require a separately validated calibration contract and are not supplied by v1. MediaPipe depth/world coordinates are not silently interchangeable with these 2D coordinates.

## 7. Anchors and aggregation

Initial built-in anchors: any named source landmark, SHOULDER_CENTER, HIP_CENTER, and TORSO_CENTER. Preserve existing BodyHeightModel head-anchor behavior through a named versioned definition when exposed; do not create a new implicit average of all head landmarks.

Custom anchor definitions declare required members, fixed weights, minimum observation rules, and definition version. Optional members and changing weights require an explicit policy. No silent one-sided substitution for a bilateral anchor.

Construct spatial anchors within each sample first. A torso observation requires both shoulders and both hips in that sample. Do not combine shoulders from one frame and hips from another to manufacture a torso observation.

Temporal aggregation then operates on eligible complete observations. Body frame origins and axes must be derived consistently from the same contributing torso observations. Head anchors may have different contributing samples and report separate provenance.

Averaging across time changes the observation's meaning. Return the selected timestamp and actual contributing timestamps; never describe a multi-frame aggregate as a raw observation at one instant.

## 8. Temporal evidence contract

Input samples carry recording-relative integer timestamps with a declared microsecond timebase, recording identity, landmark-track identity, geometry identity, and observation metadata. Existing millisecond types may be adapted explicitly; conversion must not imply greater timestamp precision.

Input order must be strictly increasing within a track. Duplicate or decreasing timestamps are rejected before window evaluation. No silent sorting or averaging of duplicate samples.

Supported window modes:

- EXACT_SAMPLE: one specified source sample.
- CENTERED_SAMPLES: selected sample plus up to N neighbours on either side; offline only.
- TRAILING_SAMPLES: selected sample plus up to N preceding neighbours; live-compatible.

Nearest-sample selection is allowed only with an explicit maximum time tolerance. Return both requested and selected timestamps. Out-of-range requests must not silently use a distant endpoint.

Window policy declares maximum adjacent timestamp gap, maximum total time span, observation thresholds, aggregation method, and minimum usable samples. Cadence-dependent presets record their actual resolved limits. Do not bury new timing constants in consumers.

Select the requested window before filtering; do not backfill rejected observations from farther away. A broken continuity interval disconnects all farther samples on that side. Known track or geometry changes are boundaries. Cross-track aggregation and mixing Lite/Full/Heavy evidence are forbidden in v1.

Live execution never consumes future samples. Offline and live calculations match for identical evidence and identical explicit window policies; centered and trailing policies are not expected to produce identical values.

Raw evidence remains immutable. No implicit interpolation or smoothing. If filtering is requested, record its method/version/parameters, timing effect, and boundary behavior. Initial v1 may expose raw trajectories only; unsupported filter requests must be rejected.

## 9. Geometry and motion operations

### 9.1 Instantaneous relations

Provide position conversion, vectorBetweenAnchors, distanceBetweenAnchors, signedDistanceToLine, distanceToSegment, unsignedAngleBetweenLines, signedAngleBetweenLines, and jointAngle.

Lines have ordered endpoints. jointAngle(A, B, C) is the angle at B between B→A and B→C, in [0, 180] degrees. Unsigned line angle is in [0, 180]. Signed angle uses atan2(cross, dot), counterclockwise positive in the upward-positive metric convention, normalized to [-180, 180). Opposite vectors normalize to -180. Zero-length lines yield unavailable results.

All operands must share compatible evidence geometry, metric space, and reference frames or undergo an explicit conversion. Mixed-reference arithmetic is forbidden.

### 9.2 Motion through an interval

Displacement = P(end) - P(start). Return its vector and magnitude separately. Endpoint selection follows the explicit timestamp policy.

Relative displacement of A with respect to B = [A(end)-B(end)] - [A(start)-B(start)], evaluated in a common fixed basis and declared scale. A rotating CURRENT_BODY trajectory is a different operation and must be labelled accordingly.

Travel distance = sum of lengths of consecutive valid trajectory edges. Do not connect across rejected gaps. A result from disconnected spans is PARTIAL, reports observed spans/coverage, and must not be presented as complete travel distance.

Velocity v1 uses explicit backward finite differences between adjacent valid samples and actual time deltas. Return the interval it describes; do not imply an instantaneous exact derivative. Speed is its magnitude. Peak speed reports that interval and quality. No acceleration or physical force estimate is included in v1.

Path deviation requires an explicit reference: infinite line, finite segment, or a consumer-supplied versioned path. Provide maximum deviation and its timestamp; time-weighted RMS may be added using a documented integration rule. A start-to-end reference with coincident endpoints is unavailable. The core does not select a technique's ideal path.

## 10. Result, quality, and provenance contracts

Every result contains value or absence, units, reference identity, operation/version, configuration identity, and evidence diagnostics.

Result states:

- AVAILABLE: requested operation has sufficient evidence under the declared policy.
- PARTIAL: value describes a declared reduced window or incomplete observed interval.
- UNAVAILABLE: required evidence or geometry is missing/degenerate.
- INVALID_REQUEST: inconsistent units, reference identities, configuration, or unsupported operation.

Quality is component-specific. A usable head anchor must not make a missing torso usable. Report unavailable reasons such as MISSING_REQUIRED_LANDMARK, LOW_OBSERVATION_QUALITY, DEGENERATE_AXIS, TIMESTAMP_GAP, TRACK_MISMATCH, UNKNOWN_GEOMETRY, MISSING_REFERENCE, and OUTSIDE_SAMPLE_TOLERANCE.

Include requested/used/excluded timestamps, exclusion reasons, observation counts, contributing landmark IDs, and coverage where applicable. A SINGLE_FRAME_FALLBACK diagnostic must identify a requested temporal estimate reduced to one frame. It must not masquerade as a full temporal window.

Pose visibility/presence values are evidence diagnostics, not calibrated probabilities of metric correctness. Do not manufacture “95% accurate” measurements by averaging landmark confidence.

Persistable provenance includes recording/track identity and hash when available, model/configuration identity, canonical dimensions/orientation contract, anchor definitions, body-frame snapshot or exact reconstruction recipe, reference policy, frozen scale, time selection, window/filter versions, and calculation version. Live consumers may use transient identities but must not claim persisted reproducibility.

## 11. Suggested API organization

Use focused utilities and immutable values. Names below express responsibilities; adapt to established repository naming without duplicating equivalent types.

| Component | Responsibility |
| --- | --- |
| LandmarkEvidenceWindow | Validate/select compatible samples and expose evidence diagnostics. |
| LandmarkAnchors | Construct versioned spatial anchors and explicit temporal aggregates. |
| BodyFrameGeometry | Construct and validate body frames from torso evidence. |
| LandmarkCoordinates | Convert image/body positions with explicit reference identities. |
| LandmarkRelations | Distances, signed offsets, angles, and projections. |
| LandmarkTrajectories | Displacement, travel, deviation, and speed over valid spans. |
| FrameGeometryMath | Existing aspect-correct primitives; reuse as the numerical foundation. |
| OverlayCoordinateTransformer | Existing presentation mapping; receives canonical output, never defines technique metrics. |

Representative call names: bodyPositionOf, imagePositionOf, bodyHeightOf, bodyLateralOffsetOf, displacementOf, relativeDisplacementOf, travelDistanceOf, angleBetweenLines, and jointAngle. Avoid a string-based generic query engine in v1.

Core data types should distinguish SourceNormalizedPoint, aspect-correct points/vectors, BodyPosition, BodyFrameSnapshot, AnchorObservation, directed lines, and MeasurementEvidence. Units and reference identity must be structurally explicit.

A thin facade is optional. Session caches and streaming buffers belong in adapters/consumer contexts, are bounded, and are keyed by evidence/configuration/version. No global mutable reference frame shared between sessions or modes.

## 12. Analyzer question examples

| Human question | Explicit interpretation |
| --- | --- |
| How far is the elbow from my center line? | Signed body lateral offset of the specified elbow in CURRENT_BODY at a selected sample, torso lengths. |
| How high is the knee? | Body height of the specified knee in a named current or neutral frame. These are different measurements. |
| How far did I move from my start? | Displacement magnitude of torso center in IMAGE between selected start/end samples, using frozen torso scale if normalized. |
| How much ground did I cover in the image? | Torso-center travel distance in IMAGE through the interval, with gap coverage disclosed. |
| How much did the hand move independently of the torso? | Wrist relative displacement with respect to shoulder in a fixed image basis; specify side, interval, and scale. |
| How straight was the punch path? | Wrist trajectory relative to shoulder, evaluated against a declared start-to-end line; maximum deviation in frozen torso lengths. |
| How far off was my punch direction? | Signed angle between actual shoulder→wrist and consumer-defined shoulder→ideal-endpoint at the selected estimated-impact sample. |
| Was the arm extended? | Joint angle at elbow, plus shoulder-to-wrist distance; interpretation/thresholds belong to the analyzer. |

“Impact,” “neutral,” “movement start,” “intended target,” and “ideal endpoint” are semantic inputs from consumers. The core must not select the nearest target when an intended target is missing.

## 13. Persistence and progressive movement processing

The application stores core-derived evidence alongside analyzer results and updates durable jobs. This utility does not own MLS file layout or queue state.

It must accept either a complete recording stream or an explicitly bounded movement window. Both retain original recording timestamps and track identity. Missing context at a movement boundary must be reported.

Per-movement analysis may reference a neutral snapshot established earlier in the recording. It must not silently recreate neutral calibration from the impact frame or assume all required evidence is inside the movement file.

A fast discovery track and a Heavy analysis track remain separate evidence sources. Reuse the same core on either track; do not splice their landmarks or reuse an incompatible calibration without an explicit future cross-track calibration contract.

## 14. Migration from the current implementation

This specification extends the reviewed BodyHeightModel v2.1 and shared geometry direction. The repository review baseline is commit 8383d3a; implementation must inspect the current branch before editing.

1. Reuse FrameGeometryMath and canonical geometry types. Centralize reference-frame definitions and provenance first.
2. Extract/reuse the torso observation and temporal evidence logic from BodyHeightModel. BodyHeightModel remains a compatibility facade over shared anchors/body-frame geometry; there must be one authoritative torso calculation.
3. Retain separately versioned head-anchor and compatibility TargetHeightEstimator behavior. Target-placement formulas remain outside generic geometry and are not automatically promoted into scoring.
4. Implement the replacement straight-punch angle analyzer as the first production consumer. It owns intended target, estimated-impact selection, neutral/transport policy, active arm, and technique interpretation.
5. Persist actual and ideal line endpoints and the resulting angle from the same calculation. Rendering consumes that evidence.
6. Compare old/new outputs on identical evidence; explain expected differences. Assign a new analyzer/calculation version before production routing changes.
7. Route new runs to the accepted analyzer and remove obsolete calculations/callers. Historical results remain renderable from saved evidence; migration must not silently recompute them with new formulas.
8. Move segmenter and live consumers onto relevant shared primitives incrementally, preserving their existing behavior until separately accepted. Do not bundle a detector redesign into this utility change.

## 15. Acceptance tests

Tests must demonstrate observable contracts, not merely duplicate implementation formulas.

| Area | Required behavior |
| --- | --- |
| Body coordinates | Upright fixture: shoulder/hip heights +0.5/-0.5, zero center-line offset; known left/right points have correct signs. |
| Lean | Rotating a synthetic body rotates its axes; corresponding body coordinates remain stable while image coordinates change. |
| Translation and scale | Body-relative position is invariant under uniform whole-body translation/scaling; IMAGE displacement detects translation. |
| Aspect ratio | Equivalent pixel geometry represented with different W/H yields equivalent angles and correctly scaled distances. |
| Conversion | Image→body→image round trip uses the same snapshot; mismatched snapshot IDs are rejected. |
| Angles | Ordered vectors, sign, wrap convention, joint vertex order, and degenerate lines have fixed expectations. |
| Aggregation | Missing opposite shoulder/hip does not silently substitute a single side; complementary invalid frames cannot form a torso. |
| Windows | Gap barriers, no backfill, maximum span, out-of-range tolerance, duplicate timestamps, and track changes are handled explicitly. |
| Live causality | Adding future samples cannot change a result requested with a trailing window ending earlier. |
| Motion | Out-and-back path has zero endpoint displacement and nonzero travel; relative displacement removes shared translation. |
| Missing data | No distance or speed edge bridges an invalid span; partial coverage is visible. |
| Scale drift | Fixed-scale and instantaneous-body-coordinate measurements are distinguishable; no accidental scale switching. |
| Rotation integration | Actual canonicalization adapter tests cover encoded rotation and mirroring; manually rotated synthetic points alone are insufficient. |
| Persistence integration | Save/reload geometry and reference provenance through Room, then reproduce results; missing source video does not silently change frame interpretation. |
| Display invariance | Crop, zoom, and resize change drawings only; saved angle and canonical endpoints remain unchanged. |
| Real evidence | Frozen MLS fixtures verify deterministic outputs; separately labelled video validates measurement usefulness and bias. |

Keep pure core tests separate from Android adapter/persistence tests. Existing meaningful regression tests should be reused or redirected; do not delete them just because the implementation moves.

## 16. Delivery scope and completion criteria

v1 delivers named anchors, body frames and conversions, geometric relations, explicit temporal evidence selection, basic motion operations, diagnostics, and the documented vocabulary. Establish the core contracts first; use the replacement punch analyzer as the integration acceptance case.

Excluded: 3D anatomical reconstruction, automatic camera-motion correction, physical force/power, automatic neutral or impact detection, universal technique scoring, an overall session score, arbitrary mixed-model fusion, and a generic formula language.

The implementation is complete when a consumer can express each example question without reimplementing anchor/coordinate math; identical evidence/configuration gives deterministic results; invalid evidence cannot produce plausible-looking default values; current and neutral references remain distinct; and the production migration leaves one authoritative implementation per shared calculation.

## 17. Repository references

- [Reviewed baseline commit](https://github.com/lassekryras-bot/karate-kihon-analyzer/commit/8383d3a1b94c13db10e1ab2c9e8c06b50fbb8254)
- [BodyHeightModel v2.1 specification](https://github.com/lassekryras-bot/karate-kihon-analyzer/blob/8383d3a/docs/backlog/body-height-model-v2.1.md)
- [OverlayCoordinateTransformer v2.1 specification](https://github.com/lassekryras-bot/karate-kihon-analyzer/blob/8383d3a/docs/backlog/overlay-coordinate-transformer-v2.1.md)

These are baseline context. New names and requirements in this document are proposed contracts, not descriptions of already accepted biomechanical validity.
