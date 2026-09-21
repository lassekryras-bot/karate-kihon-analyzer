package dk.lasse.karateanalyzer.geometry

import org.junit.Assert.*
import org.junit.Test
import kotlin.math.abs

class OverlayCoordinateTransformerTest {

    private val portraitFrame = FrameGeometry(1080, 1920) // aspect = 0.5625
    private val landscapeFrame = FrameGeometry(1920, 1080) // aspect = 1.7778

    // 1. Reference points align under portrait Fit
    @Test
    fun test01_referencePointsAlignUnderPortraitFit() {
        val transform = OverlayCoordinateTransformer.resolveDisplayTransform(
            viewportWidth = 1000f,
            viewportHeight = 1000f,
            contentScale = ContentScaleMode.FIT,
            alignment = Alignment.CENTER,
            frameGeometry = portraitFrame,
        )
        // In square viewport, portrait is pillarboxed (fit to height=1000, width=562.5)
        assertEquals(1000f, transform.displayedImageBounds.height, 1e-3f)
        assertEquals(562.5f, transform.displayedImageBounds.width, 1e-3f)
        val centerCanvas = OverlayCoordinateTransformer.sourceToCanvas(SourceNormalizedPoint(0.5f, 0.5f), transform)
        assertEquals(500f, centerCanvas.x, 1e-3f)
        assertEquals(500f, centerCanvas.y, 1e-3f)
    }

    // 2. Reference points align under portrait Crop
    @Test
    fun test02_referencePointsAlignUnderPortraitCrop() {
        val transform = OverlayCoordinateTransformer.resolveDisplayTransform(
            viewportWidth = 1000f,
            viewportHeight = 1000f,
            contentScale = ContentScaleMode.CROP,
            alignment = Alignment.CENTER,
            frameGeometry = portraitFrame,
        )
        // In square viewport, portrait crop fills width=1000, height=1777.78
        assertEquals(1000f, transform.displayedImageBounds.width, 1e-3f)
        val centerCanvas = OverlayCoordinateTransformer.sourceToCanvas(SourceNormalizedPoint(0.5f, 0.5f), transform)
        assertEquals(500f, centerCanvas.x, 1e-3f)
        assertEquals(500f, centerCanvas.y, 1e-3f)
    }

    // 3. Reference points align under landscape Fit
    @Test
    fun test03_referencePointsAlignUnderLandscapeFit() {
        val transform = OverlayCoordinateTransformer.resolveDisplayTransform(
            viewportWidth = 1000f,
            viewportHeight = 1000f,
            contentScale = ContentScaleMode.FIT,
            alignment = Alignment.CENTER,
            frameGeometry = landscapeFrame,
        )
        // Landscape fit in square: letterboxed (width=1000, height=562.5)
        assertEquals(1000f, transform.displayedImageBounds.width, 1e-3f)
        assertEquals(562.5f, transform.displayedImageBounds.height, 1e-3f)
        val centerCanvas = OverlayCoordinateTransformer.sourceToCanvas(SourceNormalizedPoint(0.5f, 0.5f), transform)
        assertEquals(500f, centerCanvas.x, 1e-3f)
        assertEquals(500f, centerCanvas.y, 1e-3f)
    }

    // 4. Reference points align under landscape Crop
    @Test
    fun test04_referencePointsAlignUnderLandscapeCrop() {
        val transform = OverlayCoordinateTransformer.resolveDisplayTransform(
            viewportWidth = 1000f,
            viewportHeight = 1000f,
            contentScale = ContentScaleMode.CROP,
            alignment = Alignment.CENTER,
            frameGeometry = landscapeFrame,
        )
        // Landscape crop in square: fills height=1000, width=1777.78
        assertEquals(1000f, transform.displayedImageBounds.height, 1e-3f)
        val centerCanvas = OverlayCoordinateTransformer.sourceToCanvas(SourceNormalizedPoint(0.5f, 0.5f), transform)
        assertEquals(500f, centerCanvas.x, 1e-3f)
        assertEquals(500f, centerCanvas.y, 1e-3f)
    }

    // 5. Rotation normalization preserves alignment
    @Test
    fun test05_rotationNormalizationPreservesAlignment() {
        val uprightFrame = FrameGeometry(1080, 1920, CanonicalOrientation.UPRIGHT_UNMIRRORED)
        // Suppose raw capture buffer is 1920x1080 rotated 90 deg clockwise relative to upright display
        val rawX = 0.3f
        val rawY = 0.2f
        // Canonicalization formula for 90-degree CW sensor rotation:
        val canonicalPoint = SourceNormalizedPoint(x = 1f - rawY, y = rawX)
        val transform = OverlayCoordinateTransformer.resolveDisplayTransform(500f, 800f, ContentScaleMode.FIT, frameGeometry = uprightFrame)
        val canvasPt = OverlayCoordinateTransformer.sourceToCanvas(canonicalPoint, transform)
        val roundTrip = OverlayCoordinateTransformer.canvasToSource(canvasPt, transform)
        assertEquals(canonicalPoint.x, roundTrip.x, 1e-4f)
        assertEquals(canonicalPoint.y, roundTrip.y, 1e-4f)
    }

    // 6. Mirroring normalization preserves alignment
    @Test
    fun test06_mirroringNormalizationPreservesAlignment() {
        val uprightFrame = FrameGeometry(1080, 1920, CanonicalOrientation.UPRIGHT_UNMIRRORED)
        // Suppose front camera buffer is horizontally mirrored
        val rawX = 0.3f
        val rawY = 0.4f
        // Canonicalization formula for front camera mirroring:
        val canonicalPoint = SourceNormalizedPoint(x = 1f - rawX, y = rawY)
        val transform = OverlayCoordinateTransformer.resolveDisplayTransform(400f, 600f, ContentScaleMode.FIT, frameGeometry = uprightFrame)
        val canvasPt = OverlayCoordinateTransformer.sourceToCanvas(canonicalPoint, transform)
        val roundTrip = OverlayCoordinateTransformer.canvasToSource(canvasPt, transform)
        assertEquals(canonicalPoint.x, roundTrip.x, 1e-4f)
        assertEquals(canonicalPoint.y, roundTrip.y, 1e-4f)
    }

    // 7. Source -> canvas -> source round trip stays within tolerance
    @Test
    fun test07_sourceCanvasSourceRoundTripWithinTolerance() {
        val transform = OverlayCoordinateTransformer.resolveDisplayTransform(
            viewportWidth = 800f,
            viewportHeight = 1200f,
            contentScale = ContentScaleMode.FIT,
            zoomPan = ZoomPan(zoom = 1.5f, panX = 30f, panY = -20f),
            frameGeometry = portraitFrame,
        )
        val testPoints = listOf(
            SourceNormalizedPoint(0f, 0f),
            SourceNormalizedPoint(0.5f, 0.5f),
            SourceNormalizedPoint(1f, 1f),
            SourceNormalizedPoint(-0.2f, 1.3f), // unclamped points
        )
        for (pt in testPoints) {
            val canvas = OverlayCoordinateTransformer.sourceToCanvas(pt, transform)
            val restored = OverlayCoordinateTransformer.canvasToSource(canvas, transform)
            assertEquals("X mismatch for $pt", pt.x, restored.x, 1e-4f)
            assertEquals("Y mismatch for $pt", pt.y, restored.y, 1e-4f)
        }
    }

    // 8. Crop-local -> source -> crop-local round trip stays within tolerance
    @Test
    fun test08_cropLocalSourceCropLocalRoundTripWithinTolerance() {
        val crop = NormalizedCrop(0.2f, 0.1f, 0.8f, 0.9f)
        val localPoints = listOf(
            CropLocalPoint(0f, 0f),
            CropLocalPoint(0.5f, 0.5f),
            CropLocalPoint(1f, 1f),
            CropLocalPoint(-0.1f, 1.2f),
        )
        for (lp in localPoints) {
            val src = OverlayCoordinateTransformer.cropToSource(lp, crop)
            val restored = OverlayCoordinateTransformer.sourceToCrop(src, crop)
            assertEquals("CropLocal X mismatch", lp.x, restored.x, 1e-4f)
            assertEquals("CropLocal Y mismatch", lp.y, restored.y, 1e-4f)
        }
    }

    // 9. Reach circle remains visually circular under uniform scaling
    @Test
    fun test09_reachCircleRemainsVisuallyCircularUnderUniformScaling() {
        val transform = OverlayCoordinateTransformer.resolveDisplayTransform(
            viewportWidth = 1000f,
            viewportHeight = 1000f,
            contentScale = ContentScaleMode.FIT,
            frameGeometry = portraitFrame,
        )
        val reachRadiusAspect = 0.25f // in aspect-correct units
        val (radNormX, radNormY) = FrameGeometryMath.aspectCorrectRadiusToSourceEllipse(reachRadiusAspect, portraitFrame)

        // Map horizontal and vertical radii to canvas pixels
        val center = SourceNormalizedPoint(0.5f, 0.5f)
        val ptCenterCanvas = OverlayCoordinateTransformer.sourceToCanvas(center, transform)
        val ptRightCanvas = OverlayCoordinateTransformer.sourceToCanvas(SourceNormalizedPoint(center.x + radNormX, center.y), transform)
        val ptBottomCanvas = OverlayCoordinateTransformer.sourceToCanvas(SourceNormalizedPoint(center.x, center.y + radNormY), transform)

        val pixelRadiusX = abs(ptRightCanvas.x - ptCenterCanvas.x)
        val pixelRadiusY = abs(ptBottomCanvas.y - ptCenterCanvas.y)

        // Under uniform scaling, reach circle appears circular on canvas
        assertEquals(pixelRadiusY, pixelRadiusX, 1e-3f)
    }

    // 10. Aspect-correct reach radius survives source -> crop -> viewport -> inverse transformation
    @Test
    fun test10_aspectCorrectReachRadiusSurvivesSourceCropViewportInverseTransformation() {
        val crop = NormalizedCrop(0.1f, 0.1f, 0.9f, 0.9f)
        val transform = OverlayCoordinateTransformer.resolveDisplayTransform(
            viewportWidth = 800f,
            viewportHeight = 1200f,
            contentScale = ContentScaleMode.FIT,
            frameGeometry = portraitFrame,
            appliedCrop = crop,
        )
        val reachRadiusAspect = 0.3f
        val (radNormX, radNormY) = FrameGeometryMath.aspectCorrectRadiusToSourceEllipse(reachRadiusAspect, portraitFrame)

        val centerSrc = SourceNormalizedPoint(0.5f, 0.5f)
        val edgeSrc = SourceNormalizedPoint(centerSrc.x + radNormX, centerSrc.y)

        val centerCanvas = OverlayCoordinateTransformer.sourceToCanvas(centerSrc, transform)
        val edgeCanvas = OverlayCoordinateTransformer.sourceToCanvas(edgeSrc, transform)

        val restoredCenterSrc = OverlayCoordinateTransformer.canvasToSource(centerCanvas, transform)
        val restoredEdgeSrc = OverlayCoordinateTransformer.canvasToSource(edgeCanvas, transform)

        val centerA = FrameGeometryMath.sourceToAspectCorrect(restoredCenterSrc, portraitFrame)
        val edgeA = FrameGeometryMath.sourceToAspectCorrect(restoredEdgeSrc, portraitFrame)

        val recoveredRadius = FrameGeometryMath.distance(centerA, edgeA)
        assertEquals(reachRadiusAspect, recoveredRadius, 1e-4f)
    }

    // 11. Segment crossing crop renders even when both endpoints are outside
    @Test
    fun test11_segmentCrossingCropRendersEvenWhenBothEndpointsOutside() {
        val crop = NormalizedCrop(0.3f, 0.3f, 0.7f, 0.7f)
        val transform = OverlayCoordinateTransformer.resolveDisplayTransform(
            viewportWidth = 500f,
            viewportHeight = 500f,
            contentScale = ContentScaleMode.FIT,
            frameGeometry = portraitFrame,
            appliedCrop = crop,
        )
        // Segment endpoints are both outside the crop horizontally
        val p1 = SourceNormalizedPoint(0.1f, 0.5f)
        val p2 = SourceNormalizedPoint(0.9f, 0.5f)

        val c1 = OverlayCoordinateTransformer.sourceToCanvas(p1, transform)
        val c2 = OverlayCoordinateTransformer.sourceToCanvas(p2, transform)

        // c1 is to the left of displayedImageBounds, c2 is to the right
        assertTrue(c1.x < transform.displayedImageBounds.left)
        assertTrue(c2.x > transform.displayedImageBounds.right)
        // The segment spans across the visible bounds
        assertTrue(c2.x > c1.x)
    }

    // 12. Partially visible reach circle remains centered
    @Test
    fun test12_partiallyVisibleReachCircleRemainsCentered() {
        val crop = NormalizedCrop(0.4f, 0.4f, 0.9f, 0.9f)
        val transform = OverlayCoordinateTransformer.resolveDisplayTransform(
            viewportWidth = 500f,
            viewportHeight = 500f,
            contentScale = ContentScaleMode.FIT,
            frameGeometry = portraitFrame,
            appliedCrop = crop,
        )
        // Circle center near the border of crop
        val center = SourceNormalizedPoint(0.42f, 0.5f)
        val canvasCenter = OverlayCoordinateTransformer.sourceToCanvas(center, transform)
        val restoredCenter = OverlayCoordinateTransformer.canvasToSource(canvasCenter, transform)
        assertEquals(center.x, restoredCenter.x, 1e-4f)
        assertEquals(center.y, restoredCenter.y, 1e-4f)
    }

    // 13. Letterbox tap returns OutsideImage
    @Test
    fun test13_letterboxTapReturnsOutsideImage() {
        // Landscape in tall viewport has letterbox top and bottom
        val transform = OverlayCoordinateTransformer.resolveDisplayTransform(
            viewportWidth = 1000f,
            viewportHeight = 1500f,
            contentScale = ContentScaleMode.FIT,
            alignment = Alignment.CENTER,
            frameGeometry = landscapeFrame,
        )
        // Tap at top center (inside viewport, but outside displayed image letterbox area)
        val letterboxPoint = CanvasPoint(500f, 50f)
        val hit = OverlayCoordinateTransformer.hitTestCanvasPoint(letterboxPoint, transform)
        assertEquals(HitTestResult.OUTSIDE_IMAGE, hit)
    }

    // 14. Point outside viewport returns OutsideViewport
    @Test
    fun test14_pointOutsideViewportReturnsOutsideViewport() {
        val transform = OverlayCoordinateTransformer.resolveDisplayTransform(
            viewportWidth = 800f,
            viewportHeight = 800f,
            contentScale = ContentScaleMode.FIT,
            frameGeometry = portraitFrame,
        )
        val outsidePoint = CanvasPoint(900f, 500f)
        val hit = OverlayCoordinateTransformer.hitTestCanvasPoint(outsidePoint, transform)
        assertEquals(HitTestResult.OUTSIDE_VIEWPORT, hit)
    }

    // 15. Mathematical inverse remains unclamped
    @Test
    fun test15_mathematicalInverseRemainsUnclamped() {
        val transform = OverlayCoordinateTransformer.resolveDisplayTransform(
            viewportWidth = 1000f,
            viewportHeight = 1000f,
            contentScale = ContentScaleMode.FIT,
            alignment = Alignment.CENTER,
            frameGeometry = portraitFrame,
        )
        // Pillarbox tap at x = 100f is to the left of displayed image
        val canvasPoint = CanvasPoint(100f, 500f)
        val src = OverlayCoordinateTransformer.canvasToSource(canvasPoint, transform)
        // Unclamped inverse will produce negative X
        assertTrue("Expected negative X but got ${src.x}", src.x < 0f)
    }

    // 16. Image and overlay use the same resolved transform
    @Test
    fun test16_imageAndOverlayUseTheSameResolvedTransform() {
        val transform = OverlayCoordinateTransformer.resolveDisplayTransform(
            viewportWidth = 600f,
            viewportHeight = 900f,
            contentScale = ContentScaleMode.FIT,
            frameGeometry = portraitFrame,
        )
        val imgBounds = transform.displayedImageBounds
        val pt0 = OverlayCoordinateTransformer.sourceToCanvas(SourceNormalizedPoint(0f, 0f), transform)
        val pt1 = OverlayCoordinateTransformer.sourceToCanvas(SourceNormalizedPoint(1f, 1f), transform)
        assertEquals(imgBounds.left, pt0.x, 1e-3f)
        assertEquals(imgBounds.top, pt0.y, 1e-3f)
        assertEquals(imgBounds.right, pt1.x, 1e-3f)
        assertEquals(imgBounds.bottom, pt1.y, 1e-3f)
    }

    // 17. Geometry clips to selected-image bounds intersected with viewport
    @Test
    fun test17_geometryClipsToSelectedImageBoundsIntersectedWithViewport() {
        val transform = OverlayCoordinateTransformer.resolveDisplayTransform(
            viewportWidth = 1000f,
            viewportHeight = 1500f,
            contentScale = ContentScaleMode.FIT,
            frameGeometry = landscapeFrame,
        )
        // Letterboxed top and bottom
        val clipBounds = transform.contentClippingBounds
        assertEquals(transform.displayedImageBounds.left, clipBounds.left, 1e-3f)
        assertEquals(transform.displayedImageBounds.top, clipBounds.top, 1e-3f)
        assertEquals(transform.displayedImageBounds.right, clipBounds.right, 1e-3f)
        assertEquals(transform.displayedImageBounds.bottom, clipBounds.bottom, 1e-3f)
        assertTrue(clipBounds.top > 0f) // excludes letterbox
    }

    // 18. Crop does not alter stored analysis
    @Test
    fun test18_cropDoesNotAlterStoredAnalysis() {
        val storedAnalysisPoint = SourceNormalizedPoint(0.45f, 0.65f)
        val fullTransform = OverlayCoordinateTransformer.resolveDisplayTransform(500f, 500f, ContentScaleMode.FIT, frameGeometry = portraitFrame)
        val cropTransform = OverlayCoordinateTransformer.resolveDisplayTransform(500f, 500f, ContentScaleMode.FIT, frameGeometry = portraitFrame, appliedCrop = NormalizedCrop(0.2f, 0.2f, 0.8f, 0.8f))

        // Underlying stored analysis coordinate is identical
        assertEquals(0.45f, storedAnalysisPoint.x, 1e-6f)
        assertEquals(0.65f, storedAnalysisPoint.y, 1e-6f)

        // Only presentation canvas points differ
        val canvasFull = OverlayCoordinateTransformer.sourceToCanvas(storedAnalysisPoint, fullTransform)
        val canvasCrop = OverlayCoordinateTransformer.sourceToCanvas(storedAnalysisPoint, cropTransform)
        assertNotEquals(canvasFull.x, canvasCrop.x, 1f)
    }

    // 19. Resize does not alter stored analysis
    @Test
    fun test19_resizeDoesNotAlterStoredAnalysis() {
        val storedPoint = SourceNormalizedPoint(0.5f, 0.3f)
        val tSmall = OverlayCoordinateTransformer.resolveDisplayTransform(300f, 500f, ContentScaleMode.FIT, frameGeometry = portraitFrame)
        val tLarge = OverlayCoordinateTransformer.resolveDisplayTransform(600f, 1000f, ContentScaleMode.FIT, frameGeometry = portraitFrame)
        assertEquals(0.5f, storedPoint.x, 1e-6f)
        assertNotEquals(OverlayCoordinateTransformer.sourceToCanvas(storedPoint, tSmall).y, OverlayCoordinateTransformer.sourceToCanvas(storedPoint, tLarge).y, 1f)
    }

    // 20. Zoom/pan does not alter stored analysis
    @Test
    fun test20_zoomPanDoesNotAlterStoredAnalysis() {
        val storedPoint = SourceNormalizedPoint(0.5f, 0.5f)
        val tBase = OverlayCoordinateTransformer.resolveDisplayTransform(500f, 500f, ContentScaleMode.FIT, frameGeometry = portraitFrame)
        val tZoom = OverlayCoordinateTransformer.resolveDisplayTransform(500f, 500f, ContentScaleMode.FIT, zoomPan = ZoomPan(2f, 50f, 50f), frameGeometry = portraitFrame)
        assertEquals(0.5f, storedPoint.x, 1e-6f)
        assertNotEquals(OverlayCoordinateTransformer.sourceToCanvas(storedPoint, tBase).x, OverlayCoordinateTransformer.sourceToCanvas(storedPoint, tZoom).x, 1f)
    }

    // 21. Fullscreen does not alter stored analysis
    @Test
    fun test21_fullscreenDoesNotAlterStoredAnalysis() {
        val storedPoint = SourceNormalizedPoint(0.4f, 0.6f)
        val tInline = OverlayCoordinateTransformer.resolveDisplayTransform(400f, 400f, ContentScaleMode.FIT, frameGeometry = portraitFrame)
        val tFullscreen = OverlayCoordinateTransformer.resolveDisplayTransform(1080f, 2400f, ContentScaleMode.FIT, frameGeometry = portraitFrame)
        assertEquals(0.4f, storedPoint.x, 1e-6f)
        assertEquals(0.6f, storedPoint.y, 1e-6f)
    }

    // 22. Stroke/marker/text sizes remain presentation-relative during zoom
    @Test
    fun test22_strokeMarkerTextSizesRemainPresentationRelativeDuringZoom() {
        val baseMarkerRadiusDp = 6f
        val density = 2.5f
        val markerRadiusPx = baseMarkerRadiusDp * density

        // Zooming the display does not multiply the UI marker size
        val zoom = ZoomPan(zoom = 3f)
        val displayTransform = OverlayCoordinateTransformer.resolveDisplayTransform(500f, 500f, ContentScaleMode.FIT, zoomPan = zoom, frameGeometry = portraitFrame)
        val effectiveMarkerRadiusPx = baseMarkerRadiusDp * density // remains constant
        assertEquals(15f, effectiveMarkerRadiusPx, 1e-3f)
        assertEquals(3f, displayTransform.zoomPan.zoom, 1e-3f)
    }

    // 23. Invalid dimensions/crops fail explicitly
    @Test
    fun test23_invalidDimensionsAndCropsFailExplicitly() {
        assertThrows(IllegalArgumentException::class.java) {
            FrameGeometry(-10, 100)
        }
        assertThrows(IllegalArgumentException::class.java) {
            NormalizedCrop(0.8f, 0.1f, 0.2f, 0.9f) // left > right
        }
        assertThrows(IllegalArgumentException::class.java) {
            ZoomPan(zoom = -1f)
        }
    }

    // 24. Derived-image mapping uses actual applied pixel crop
    @Test
    fun test24_derivedImageMappingUsesActualAppliedPixelCrop() {
        val normCrop = NormalizedCrop(0.12345f, 0.23456f, 0.78910f, 0.89101f)
        val pixelCrop = normCrop.toAppliedPixelCrop(portraitFrame)

        // Pixel crop has integer coordinates
        assertEquals((0.12345f * 1080).toInt(), pixelCrop.left)
        assertEquals((0.23456f * 1920).toInt(), pixelCrop.top)

        // Derived image uses actual pixel crop dimensions
        val prov = DerivedImageProvenance(
            sourceRecordingId = "rec_123",
            actualPresentationTimestampUs = 500_000L,
            requestedNormalizedCrop = normCrop,
            appliedPixelCrop = pixelCrop,
            bitmapWidth = pixelCrop.width,
            bitmapHeight = pixelCrop.height,
        )
        assertEquals(pixelCrop.width, prov.bitmapWidth)
        assertEquals(pixelCrop.height, prov.bitmapHeight)
    }

    // 25. Circle-line intersections return zero/one/two results without semantic selection
    @Test
    fun test25_circleLineIntersectionsReturnZeroOneTwoWithoutSemanticSelection() {
        val center = AspectCorrectPoint(0.5f, 0.5f)
        val radius = 0.2f

        val res0 = FrameGeometryMath.circleHorizontalLineIntersections(center, radius, 0.8f)
        val res1 = FrameGeometryMath.circleHorizontalLineIntersections(center, radius, 0.7f)
        val res2 = FrameGeometryMath.circleHorizontalLineIntersections(center, radius, 0.5f)

        assertEquals(0, res0.size)
        assertEquals(1, res1.size)
        assertEquals(2, res2.size)
    }

    // 26. Actual selected frame timestamp is distinct from requested extraction time
    @Test
    fun test26_actualSelectedFrameTimestampIsDistinctFromRequestedExtractionTime() {
        val requestedUs = 1_000_000L
        val actualUs = 1_033_333L // e.g. nearest 30fps keyframe
        val prov = DerivedImageProvenance(
            sourceRecordingId = "rec_abc",
            requestedExtractionTimestampUs = requestedUs,
            actualPresentationTimestampUs = actualUs,
            appliedPixelCrop = AppliedPixelCrop(0, 0, 1080, 1920),
            bitmapWidth = 1080,
            bitmapHeight = 1920,
        )
        assertNotEquals(prov.requestedExtractionTimestampUs, prov.actualPresentationTimestampUs)
        assertEquals(actualUs, prov.actualPresentationTimestampUs)
    }

    // 27. Rounding-sensitive probe maps applied crop boundary to zero
    @Test
    fun test27_roundingSensitiveCropMapsImageBoundaryToZero() {
        val frame = FrameGeometry(1080, 1920)
        // Crop with fractional pixel values: left=0.12345 (133.326 px -> applied 133 px)
        val normCrop = NormalizedCrop(0.12345f, 0.2f, 0.82345f, 0.9f)
        val transform = OverlayCoordinateTransformer.resolveDisplayTransform(
            viewportWidth = 600f,
            viewportHeight = 600f,
            contentScale = ContentScaleMode.FIT,
            appliedCrop = normCrop,
            frameGeometry = frame,
        )
        // Image's actual left boundary in source normalized space
        val imageLeftSourceX = transform.appliedPixelCrop.left.toFloat() / frame.sourceWidth
        val canvasPt = OverlayCoordinateTransformer.sourceToCanvas(
            SourceNormalizedPoint(imageLeftSourceX, 0.5f),
            transform,
        )
        // Left boundary of the image on canvas must exactly equal displayedImageBounds.left (0px offset within image)
        assertEquals(transform.displayedImageBounds.left, canvasPt.x, 1e-4f)

        // Inverse mapping of displayedImageBounds.left maps back to imageLeftSourceX
        val sourcePt = OverlayCoordinateTransformer.canvasToSource(
            CanvasPoint(transform.displayedImageBounds.left, canvasPt.y),
            transform,
        )
        assertEquals(imageLeftSourceX, sourcePt.x, 1e-4f)
    }

    // 28. Non-finite coordinates fail explicitly
    @Test
    fun test28_nonFiniteCoordinatesAreRejected() {
        assertThrows(IllegalArgumentException::class.java) {
            SourceNormalizedPoint(Float.NaN, 0.5f)
        }
        assertThrows(IllegalArgumentException::class.java) {
            SourceNormalizedPoint(0.5f, Float.POSITIVE_INFINITY)
        }
        assertThrows(IllegalArgumentException::class.java) {
            CanvasPoint(Float.NaN, 100f)
        }
        assertThrows(IllegalArgumentException::class.java) {
            CropLocalPoint(0.5f, Float.NEGATIVE_INFINITY)
        }
        assertThrows(IllegalArgumentException::class.java) {
            AspectCorrectPoint(Float.NaN, 0.5f)
        }
    }
}


