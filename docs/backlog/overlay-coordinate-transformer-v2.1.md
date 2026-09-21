# Overlay Coordinate Transformer — Implementation Spec v2.1

**Status:** IN PROGRESS (Implemented locally, addressing review gaps and app integration)  
**Primary platform:** Pure Kotlin analyzer core (`dk.lasse.karateanalyzer.geometry`) + Android Presentation Overlay  
**Repository:** `karate-kihon-analyzer`

## Purpose

Create a shared coordinate and overlay infrastructure that keeps pose landmarks, analysis geometry, and rendered drawings aligned across full-frame video, cropped/chopped images, thumbnails, impact stills, zoomed detail views, full-screen views, Fit/Crop display modes, and portrait/landscape layouts.

Analyzers compute their results once in a stable canonical source-frame coordinate system. Changing crop, viewport, zoom, display mode, or screen orientation must not change the underlying analytical result.

## 1. Core architectural principle

### Analysis geometry is presentation-independent

All semantic analysis results are expressed relative to the canonical source frame, including pose landmarks, composite landmarks, target points/heights, ideal endpoints, actual fist points, skeleton paths, analysis arcs, punch-line endpoints, and annotation anchors.

Do not store these results in crop-local, thumbnail, canvas, fullscreen, or device-screen coordinates.

Cropping, resizing, zooming, panning, Fit/Crop behavior, and fullscreen presentation are rendering concerns.

## 2. Canonical source-frame definition

The canonical coordinate system is the **full upright, unmirrored video frame**:

- origin at top-left
- +X toward image right
- +Y toward image bottom
- normalized source extent `[0,1] × [0,1]`

Incoming video/landmark data is canonicalized at the pipeline boundary.

Encoded-buffer orientation is not canonical. Video rotation metadata and front-camera mirroring are resolved before analyzers receive data.

Source-normalized points may legitimately lie outside `[0,1]`; that interval defines the frame bounds, not the mathematical domain. Do not clamp during coordinate transformation.

## 3. Coordinate spaces

### 3.1 Source normalized frame space

Master spatial coordinate system used for persisted analysis positions and source-relative geometry.

### 3.2 Aspect-correct analysis space

For square-pixel imagery:

`xAnalysis = xSource × sourceWidth / sourceHeight`

`yAnalysis = ySource`

One analysis-space unit corresponds to one source-frame height.

Use this space for Euclidean distances, arm lengths, reach radius, circles, intersections, punch-line angles, projections, and point-to-line distances.

Do not calculate Euclidean geometry directly in raw source-normalized X/Y.

A radius `r` in aspect-correct space corresponds in source-normalized coordinates to:

`radiusX = r × sourceHeight / sourceWidth`

`radiusY = r`

This is an elliptical coordinate representation in normalized space. Under uniform display scaling, the rendered physical reach circle must still appear circular.

### 3.3 Crop-local normalized space

Temporary rendering coordinates relative to a crop inside the canonical source frame. Never persist analysis results here.

### 3.4 Displayed-image / viewport space

Represents the resolved location of the selected image region inside its viewport, including:

- viewport width/height
- content scale mode
- alignment
- displayed-image bounds
- letterbox/pillarbox offsets
- crop overflow
- zoom
- pan

Image and overlay must use the same resolved transform.

### 3.5 Canvas space

Final drawing coordinates are canvas-local pixels. Convert dp/sp presentation sizes only at the rendering boundary.

## 4. Required component separation

### FrameGeometryMath

Pure analyzer/core geometry:

- source-normalized ↔ aspect-correct conversion
- distances
- segment lengths
- angles and signed angles
- projections
- line intersections
- horizontal-line/circle intersections
- axis projections

Must not depend on Compose, crop views, viewport dimensions, or screen size.

Aspect correction does not provide physical metric distance, perspective correction, camera calibration, or gravity/world coordinates.

### OverlayCoordinateTransformer

Owns:

- source ↔ crop-local mapping
- source ↔ displayed-image mapping
- source ↔ canvas mapping
- Fit/Crop transforms
- alignment and offsets
- zoom/pan
- inverse mapping
- hit-testing support

It preserves geometry and does not modify semantic analysis results.

### OverlayRenderer

Owns drawing, clipping, marker sizes, labels, strokes, text, and other presentation details.

## 5. FrameGeometry

Must contain at minimum:

- source width in pixels
- source height in pixels
- aspect ratio
- canonical orientation information if needed for validation

Reject non-positive dimensions explicitly.

## 6. Display transform

Content scaling is mandatory in v1.

The transform applies to the **selected image region**:

- full canonical source frame when no explicit crop exists
- actual applied source crop when a crop exists

### Fit

Displays the entire selected image region while preserving aspect ratio. Letterboxing/pillarboxing may occur.

### Crop

Fills the viewport using the selected image region's aspect ratio while preserving aspect ratio. Portions may extend beyond the viewport and be clipped.

### Alignment

The transform must include alignment. Center alignment may be the initial default but must not be hard-coded as a mathematical assumption.

### Zoom/pan

Where supported, zoom/pan modifies the same resolved transform rather than using a separate overlay path.

## 7. Crop transformation

For v1, requested normalized crop bounds must satisfy:

`0 ≤ left < right ≤ 1`

`0 ≤ top < bottom ≤ 1`

No clamping occurs during point conversion.

Applied pixel crops use:

- left inclusive
- top inclusive
- right exclusive
- bottom exclusive

Pixel rounding is deterministic and shared across crop generation, provenance, and coordinate mapping.

The exact rounding strategy must be documented and tested.

Once rounding occurs, the **actual applied pixel crop** is authoritative for derived-image mapping.

## 8. Visibility and clipping

Geometry is clipped against:

**transformed selected-image bounds ∩ viewport bounds**

Canvas clipping alone is insufficient under Fit because geometry must not appear in letterbox/pillarbox areas.

A line may remain visible even when both endpoints are outside the crop.

Partially visible circles/arcs are clipped without moving their centers.

For v1, canvas clipping plus conservative visibility checks is sufficient.

Labels have a separate presentation policy and may be repositioned/hidden without altering geometry.

## 9. Mathematical inversion vs hit-testing

### Mathematical inverse transform

Unclamped and reversible where the transform is invertible. It may return source coordinates outside the visible selected-image region.

### Hit-testing

Separate operation returning results such as:

- `InsideImage`
- `OutsideImage`
- `OutsideViewport`

Letterbox taps return `OutsideImage`. Points outside the viewport return `OutsideViewport`.

## 10. Frame identity and provenance

Every overlay dataset must identify its source using:

- recording identity
- movement identity where applicable
- actual selected source-frame presentation timestamp
- optional frame index for debugging

The actual selected presentation timestamp in a documented recording-relative timebase is authoritative and distinct from any requested extraction timestamp.

## 11. Derived-image provenance

Derived stills/thumbnails/crops retain:

- source recording identity
- requested extraction timestamp where relevant
- actual selected frame presentation timestamp
- requested normalized crop
- actual applied pixel crop
- resulting bitmap width/height
- relevant canonical orientation state if needed

Bitmap dimensions alone are insufficient to reconstruct source position.

## 12. Drawing units

Image-scaled geometry transforms with the image:

- skeleton
- shoulder/fist points
- target lines
- reach circle
- ideal endpoint
- punch rays
- angle geometry

Presentation sizes remain UI-relative:

- stroke width
- marker radius
- text size
- label padding/offset

Zooming geometry must not automatically thicken UI strokes/labels.

## 13. Angles

Persist/user-facing angles use **degrees**.

Define and test:

- zero-angle direction
- positive direction
- signed-angle convention
- arc sweep convention

Image coordinates have Y downward, so geometry math must explicitly account for mathematical orientation.

## 14. Horizontal target lines

Target localization and line rendering are separate.

For v1, target-height lines are horizontal in the canonical upright source frame. Torso lean does not rotate them.

Future gravity/camera-roll correction is out of scope.

## 15. Reach-circle behavior

Arm reach is calculated in aspect-correct analysis space.

A generic circle-line intersection returns:

- zero intersections
- one intersection
- two intersections

These are valid mathematical outcomes. Zero intersections is not an infrastructure error.

The movement analyzer—not geometry math—chooses the semantically relevant intersection using striking direction and movement context.

## 16. Overlay data ownership

Semantic overlay data remains source-relative, such as:

- impact timestamp
- striking side
- shoulder/fist
- targets
- ideal endpoint
- actual/optimal rays
- reach radius
- angle result
- skeleton landmarks

Rendering decides how this appears in full-frame, crop, thumbnail, fullscreen, or debug views.

## 17. Required API capabilities

### FrameGeometryMath

Equivalent capabilities:

- `sourceToAspectCorrect`
- `aspectCorrectToSource`
- `distance`
- `segmentLength`
- `signedAngle`
- `absoluteAngle`
- `projectPointOntoAxis`
- `circleHorizontalLineIntersections`

### OverlayCoordinateTransformer

Equivalent capabilities:

- `sourceToCrop`
- `cropToSource`
- `sourceToCanvas`
- `canvasToSource`
- `resolveFitTransform`
- `resolveCropTransform`
- `applyZoomPan`
- `hitTestCanvasPoint`

### OverlayRenderer

Equivalent capabilities:

- `drawSkeleton`
- `drawTargetLine`
- `drawReachCircle`
- `drawPunchRay`
- `drawAngleArc`
- `drawMarker`
- `drawLabel`

Names are illustrative.

## 18. Failure behavior

Explicit failures include:

- invalid source dimensions
- invalid crop
- non-finite point
- zero-length vector when direction is required
- missing transform
- degenerate display rectangle

Expected geometry outcomes such as zero intersections are not infrastructure failures.

## 19. Acceptance tests

At minimum:

1. Reference points align under portrait Fit.
2. Reference points align under portrait Crop.
3. Reference points align under landscape Fit.
4. Reference points align under landscape Crop.
5. Rotation normalization preserves alignment.
6. Mirroring normalization preserves alignment.
7. Source → canvas → source round trip stays within tolerance.
8. Crop-local → source → crop-local round trip stays within tolerance.
9. Reach circle remains visually circular under uniform scaling.
10. Aspect-correct reach radius survives source → crop → viewport → inverse transformation.
11. Segment crossing crop renders even when both endpoints are outside.
12. Partially visible reach circle remains centered.
13. Letterbox tap returns `OutsideImage`.
14. Point outside viewport returns `OutsideViewport`.
15. Mathematical inverse remains unclamped.
16. Image and overlay use the same resolved transform.
17. Geometry clips to selected-image bounds intersected with viewport.
18. Crop does not alter stored analysis.
19. Resize does not alter stored analysis.
20. Zoom/pan does not alter stored analysis.
21. Fullscreen does not alter stored analysis.
22. Stroke/marker/text sizes remain presentation-relative during zoom.
23. Invalid dimensions/crops fail explicitly.
24. Derived-image mapping uses actual applied pixel crop.
25. Circle-line intersections return zero/one/two results without semantic selection.
26. Actual selected frame timestamp is distinct from requested extraction time.

## 20. Immediate app use cases

- Movement Detail Analyze tab
- Analysis Debug
- impact-frame poster overlay
- target-height analyzer
- thumbnail overlays
- cropped impact stills
- fullscreen analysis
- punch-line angle visualization
- reach-circle visualization
- future kick/hikite/guard/chamber overlays

## 21. Non-goals for v1

- gravity/device-roll correction
- physical world-horizontal reconstruction
- calibrated perspective correction
- metric 3D reprojection
- advanced label collision layout
- editable overlay geometry

## 22. Recommended first implementation slice

1. canonical source-frame contract
2. typed source/aspect-correct coordinates
3. FrameGeometryMath
4. deterministic crop mapping/provenance
5. Fit/Crop transforms
6. forward/inverse mappings
7. separate hit-testing
8. selected-image + viewport clipping
9. skeleton rendering
10. target lines
11. aspect-correct reach circle
12. actual/ideal punch rays
13. angle arc/label
14. impact-still provenance
15. frame timestamp provenance
16. alignment/round-trip tests

## 23. Architectural invariants

- Use source normalized space for durable spatial analysis results.
- Use aspect-correct space for 2D image-plane geometry.
- Use crop-local space only transiently.
- Use canvas pixels for final drawing.
- Never calculate Euclidean geometry directly in raw normalized X/Y.
- Never store analysis in crop or screen coordinates.
- Never clamp during coordinate transformation.
- Image and overlay always share the same resolved display transform.
- Fit/Crop applies to the selected image region.
- Geometry clips to selected-image bounds intersected with viewport.
- Mathematical inversion and hit-testing are separate.
- Derived images use the actual applied pixel crop as spatial authority.
- Geometry utilities return mathematical possibilities; analyzers apply semantics.
- Overlay frame identity uses the actual selected presentation timestamp.
- Crop, viewport, zoom, fullscreen, or ContentScale changes never alter analytical results.

## Final data flow

Canonical upright/unmirrored video frame  
→ source-normalized landmarks  
→ aspect-correct geometry where required  
→ source-relative analysis result  
→ optional source crop mapping  
→ resolved Fit/Crop + alignment + zoom/pan transform  
→ canvas pixels  
→ overlay rendering

Inverse interaction follows the transform chain in reverse, with hit-testing handled separately.
